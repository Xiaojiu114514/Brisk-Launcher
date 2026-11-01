package com.brisklauncher.mcl.auth;

import com.brisklauncher.mcl.auth.api.AccountType;
import com.brisklauncher.mcl.auth.api.AuthProvider;
import com.brisklauncher.mcl.auth.api.GameSession;
import com.brisklauncher.mcl.auth.api.LoginResult;
import com.brisklauncher.mcl.auth.api.StoredSession;
import com.brisklauncher.mcl.auth.impl.AuthProviderRegistry;
import com.brisklauncher.mcl.security.SafeLog;
import com.brisklauncher.mcl.security.SessionTokenStore;
import com.brisklauncher.mcl.util.Uuids;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 账号服务：三类账号的增删查、当前账号、令牌读取与刷新。
 *
 * <p>当前账号会持久化，切换后立即落盘；刷新只在真正需要时发生
 * （正版过期只能重新走设备码，第三方走 Yggdrasil refresh）。
 */
public final class AccountList {

    private final AccountStore store;
    private final SessionTokenStore tokenStore;
    private final List<Runnable> changeListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    private String currentUuid;

    /**
     * 注册「帐号清单/当前帐号有变化」的通知，供顶栏与帐户页即时同步。
     *
     * @return 取消注册的动作；页面/顶栏被丢弃时务必呼叫
     */
    public Runnable addChangeListener(Runnable listener) {
        changeListeners.add(listener);
        return () -> changeListeners.remove(listener);
    }

    private void notifyChanged() {
        for (Runnable listener : changeListeners) {
            try {
                listener.run();
            } catch (Exception error) {
                SafeLog.warn("帐号变更通知失败：" + error.getClass().getSimpleName());
            }
        }
    }

    public AccountList() {
        this(new AccountStore(), SessionTokenStore.defaultStore());
    }

    public AccountList(AccountStore store, SessionTokenStore tokenStore) {
        this.store = store;
        this.tokenStore = tokenStore;
        this.currentUuid = store.loadCurrentUuid();
        if (currentUuid == null) {
            List<AccountStore.Account> all = store.load();
            if (!all.isEmpty()) {
                currentUuid = all.get(0).uuid();
            }
        }
    }

    public List<AccountStore.Account> list(AccountType type) {
        return store.load().stream().filter(account -> account.type() == type).toList();
    }

    public List<AccountStore.Account> listAll() {
        return store.load();
    }

    public Optional<AccountStore.Account> current() {
        if (currentUuid == null) {
            return Optional.empty();
        }
        return store.load().stream().filter(account -> account.uuid().equals(currentUuid)).findFirst();
    }

    public String currentUuid() {
        return currentUuid;
    }

    public boolean hasAccounts() {
        return !store.load().isEmpty();
    }

    /**
     * 孤儿令牌清理（安全审计 v2 A-09）：凭据管理器里有、但 accounts.json 里没有的条目。
     *
     * @return 被清除的条目数
     */
    public int purgeOrphanTokens() {
        java.util.Set<String> uuids = new java.util.HashSet<>();
        for (AccountStore.Account account : store.load()) {
            uuids.add(account.uuid());
        }
        java.util.List<String> orphans = tokenStore.listOrphanTargets(uuids);
        return orphans.isEmpty() ? 0 : tokenStore.purgeTargets(orphans);
    }

    /** 只取孤儿条目（不删除），给 UI 询问用。 */
    public java.util.List<String> orphanTokenTargets() {
        java.util.Set<String> uuids = new java.util.HashSet<>();
        for (AccountStore.Account account : store.load()) {
            uuids.add(account.uuid());
        }
        return tokenStore.listOrphanTargets(uuids);
    }

    /** 删除指定目标名的孤儿凭据。 */
    public int purgeTokenTargets(java.util.List<String> targets) {
        return tokenStore.purgeTargets(targets);
    }

    /** 令牌能否持久化；false 时 UI 应提示“仅本次会话有效”。 */
    public boolean isTokenPersistent() {
        return tokenStore.isPersistent();
    }

    /** 保存登录结果：元数据 + 令牌；返回元数据是否落盘成功。 */
    public boolean save(LoginResult result) {
        if (result == null || !result.success() || result.sessions().isEmpty()) {
            return false;
        }
        List<AccountStore.Account> all = new ArrayList<>(store.load());
        // 合规：没有微软正版帐号时，拒绝保存离线 / 第三方帐号（UI 也会先挡，这里是最后一道）
        boolean hasMicrosoft = all.stream().anyMatch(
                account -> account.type() == AccountType.MICROSOFT);
        for (StoredSession session : result.sessions()) {
            if (!hasMicrosoft && session.type() != AccountType.MICROSOFT) {
                SafeLog.warn("合规限制：请先登入微软正版帐号，再添加离线/第三方帐号");
                return false;
            }
            try {
                tokenStore.save(session);
            } catch (Throwable error) {
                // 凭据管理器不可写时不要让整个登录流程崩掉：元数据仍然保存，令牌仅本次会话可用
                SafeLog.warn("令牌持久化失败，本次会话仍可使用：" + error.getClass().getSimpleName());
            }
            all.removeIf(account -> account.uuid().equals(session.uuid()));
            all.add(new AccountStore.Account(
                    session.uuid(), session.name(), session.type(), session.endpoint()));
            if (currentUuid == null) {
                currentUuid = session.uuid();
            }
        }
        boolean saved = store.save(all, currentUuid);
        if (!saved) {
            // 安全审计 v2 A-09：令牌已经写进凭据管理器，但 accounts.json 没写成 →
            // 账号页看不到、删不掉，令牌永久残留。这里把刚写入的令牌回滚掉。
            for (StoredSession session : result.sessions()) {
                try {
                    tokenStore.delete(session.uuid());
                } catch (Throwable ignored) {
                    // 尽力而为
                }
            }
            SafeLog.warn("账号元数据保存失败，已回滚本次写入的令牌");
            return false;
        }
        notifyChanged();
        return true;
    }

    public void setCurrent(String uuid) {
        if (uuid == null) {
            return;
        }
        this.currentUuid = uuid;
        store.save(store.load(), uuid);
        notifyChanged();
    }

    public void delete(String uuid) {
        List<AccountStore.Account> all = new ArrayList<>(store.load());
        all.removeIf(account -> account.uuid().equals(uuid));
        tokenStore.delete(uuid);
        if (uuid.equals(currentUuid)) {
            currentUuid = all.isEmpty() ? null : all.get(0).uuid();
        }
        store.save(all, currentUuid);
        notifyChanged();
    }

    /** 读取当前账号的完整会话（含令牌），供启动使用。 */
    public Optional<StoredSession> loadCurrentSession() {
        if (currentUuid == null) {
            return Optional.empty();
        }
        return tokenStore.load(currentUuid);
    }

    /**
     * 取一个可以用于启动的会话：过期时先尝试刷新。
     * 正版过期无法刷新，返回失败原因由 UI 提示重新登录。
     */
    public GameSession resolveSessionForLaunch(String uuid) {
        StoredSession session = tokenStore.load(uuid)
                .orElseThrow(() -> new IllegalStateException("账号令牌不存在，请重新登录"));
        AccountType type = session.type();

        AuthProvider provider = AuthProviderRegistry.get(type)
                .orElseThrow(() -> new IllegalStateException("缺少 " + type + " 认证实现"));

        if (session.isExpired()) {
            LoginResult refreshed = provider.refresh(session);
            if (refreshed.success()) {
                tokenStore.save(refreshed.session());
                session = refreshed.session();
            } else if (type == AccountType.THIRD_PARTY) {
                throw new IllegalStateException("第三方令牌刷新失败，请重新登录");
            } else if (type == AccountType.MICROSOFT) {
                throw new IllegalStateException("正版登录已过期，请重新登录");
            }
        }
        SafeLog.tokenRefresh(type);
        return provider.toGameSession(session);
    }

    /** 读令牌并组装 GameSession（不刷新，供 UI 展示当前账号）。 */
    public Optional<GameSession> gameSessionFor(String uuid) {
        return tokenStore.load(uuid).flatMap(session ->
                AuthProviderRegistry.get(session.type()).map(provider -> provider.toGameSession(session)));
    }

    /** 没有账号时给一个离线兜底会话，保证“能启动”。 */
    public GameSession fallbackSession() {
        StoredSession offline = new StoredSession(AccountType.OFFLINE,
                Uuids.offlineUuid("Player"), "Player", null, new char[]{'0'}, Long.MAX_VALUE, null);
        return new com.brisklauncher.mcl.auth.impl.OfflineAuthProvider().toGameSession(offline);
    }
}
