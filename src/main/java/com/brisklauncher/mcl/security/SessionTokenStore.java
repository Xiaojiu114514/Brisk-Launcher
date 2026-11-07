package com.brisklauncher.mcl.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.brisklauncher.mcl.AppPaths;
import com.brisklauncher.mcl.auth.api.AccountType;
import com.brisklauncher.mcl.auth.api.StoredSession;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 会话持久化。令牌只落 Windows 凭据管理器，绝不写文件。
 *
 * <p>说明：Java 无法彻底消除内存中的 String，这里尽量缩短令牌的 String 生命周期。
 *
 * <h2>为什么凭据名里带「安装实例 id」（第 166 轮）</h2>
 * 凭据管理器是**按 Windows 用户**存的，不像 {@code accounts.json} 那样跟着数据目录走。
 * 所以只要玩家有两个数据目录（例如：旧的文件夹版在 %LOCALAPPDATA%，新的单 exe 版在 exe 旁边），
 * 另一个实例的凭据就会被当成「无法对应到账号的残留」，弹窗叫人清除 —— 清完那个实例的账号就掉登录。
 *
 * <p>现在凭据名是 {@code MCL/account/<实例id>/<uuid>}：实例 id 存在数据目录里的
 * {@code instance-id.txt}，跟着数据目录走（整个目录搬走也还是同一个 id），
 * 所以两个实例互不干扰，孤儿判定只看自己这一份。
 *
 * <p>兼容：老的 {@code MCL/account/<uuid>} 仍然会读；只有「没有其他实例认领」时才会被当作孤儿。
 */
public final class SessionTokenStore {

    /** 旧格式前缀（也是新格式的前缀部分）。 */
    private static final String TARGET_PREFIX = "MCL/account/";

    private final CredentialStore store;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String instanceId;

    public SessionTokenStore(CredentialStore store) {
        this(store, instanceId());
    }

    SessionTokenStore(CredentialStore store, String instanceId) {
        this.store = store;
        this.instanceId = instanceId == null || instanceId.isBlank() ? "default" : instanceId;
    }

    public static SessionTokenStore defaultStore() {
        WindowsCredentialStore windows = new WindowsCredentialStore();
        return new SessionTokenStore(windows.isAvailable() ? windows : new InMemoryCredentialStore());
    }

    /**
     * 本数据目录的实例 id（首次调用时生成并写在 {@code <launcherRoot>/instance-id.txt}）。
     *
     * <p>跟着数据目录走：把整个目录复制/搬到别的路径，账号还是同一份；两个不同目录则是两个实例。
     */
    public static String instanceId() {
        try {
            Path file = AppPaths.instanceIdFile();
            if (Files.isRegularFile(file)) {
                String existing = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (!existing.isEmpty()) {
                    return existing;
                }
            }
            String created = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            Files.createDirectories(file.getParent());
            Files.writeString(file, created, StandardCharsets.UTF_8);
            FilePerms.ensurePrivateFile(file);
            return created;
        } catch (Exception error) {
            // 写不了就用固定值：至少同一个进程内一致（跨实例隔离会退化，但不影响可用性）
            return "default";
        }
    }

    /** 本实例的凭据目标名。 */
    private String target(String uuid) {
        return TARGET_PREFIX + instanceId + "/" + uuid;
    }

    /** 旧格式的目标名（第 166 轮之前的版本写的）。 */
    private static String legacyTarget(String uuid) {
        return TARGET_PREFIX + uuid;
    }

    /** 令牌是否能跨进程持久化（Windows 凭据管理器可用时为 true）。 */
    public boolean isPersistent() {
        return store instanceof WindowsCredentialStore;
    }

    public void save(StoredSession session) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", session.type().name());
        map.put("uuid", session.uuid());
        map.put("name", session.name());
        map.put("expiresAt", session.expiresAt());
        map.put("endpoint", session.endpoint());
        map.put("refreshToken", toStr(session.refreshToken()));
        map.put("accessToken", toStr(session.accessToken()));

        char[] payload = null;
        try {
            // 双保险：先 AES-GCM 加密，再交给 Windows 凭据管理器（它本身也加密）
            String encrypted = SecretBox.encrypt(mapper.writeValueAsString(map));
            if (!SecretBox.isEncrypted(encrypted)) {
                // SecretBox 加密失败时会退回明文；令牌绝不能以明文落盘/入库，
                // 这里直接拒绝持久化（调用方会降级成「仅本次会话可用」）。
                throw new IllegalStateException("令牌加密失败，拒绝以明文保存");
            }
            payload = encrypted.toCharArray();
            store.put(target(session.uuid()), payload);
            // 顺手清掉旧格式的同账号凭据（避免同一个账号在凭据管理器里留两份）
            try {
                store.delete(legacyTarget(session.uuid()));
            } catch (Exception ignored) {
                // 没有就算了
            }
        } catch (Exception e) {
            SafeLog.warn("令牌未持久化（" + e.getClass().getSimpleName() + "）：本次会话仍可使用");
            throw new IllegalStateException("保存会话失败");
        } finally {
            if (payload != null) {
                Arrays.fill(payload, '\0');
            }
        }
    }

    public Optional<StoredSession> load(String uuid) {
        Optional<char[]> raw = store.get(target(uuid));
        if (raw.isEmpty()) {
            // 兼容旧格式（升级后第一次启动）；读到之后由 save() 迁移成新格式
            raw = store.get(legacyTarget(uuid));
        }
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        char[] chars = raw.get();
        try {
            String rawText = new String(chars);
            if (SecretBox.isEncrypted(rawText)) {
                rawText = SecretBox.decrypt(rawText);
            }
            Map<String, Object> map = mapper.readValue(rawText, Map.class);
            return Optional.of(new StoredSession(
                    AccountType.valueOf(String.valueOf(map.get("type"))),
                    String.valueOf(map.get("uuid")),
                    String.valueOf(map.get("name")),
                    toChars(map.get("refreshToken")),
                    toChars(map.get("accessToken")),
                    ((Number) map.getOrDefault("expiresAt", 0L)).longValue(),
                    map.get("endpoint") == null ? null : String.valueOf(map.get("endpoint"))));
        } catch (Exception e) {
            SafeLog.warn("会话解析失败，已丢弃");
            return Optional.empty();
        } finally {
            Arrays.fill(chars, '\0');
        }
    }

    public void delete(String uuid) {
        store.delete(target(uuid));
        try {
            store.delete(legacyTarget(uuid));
        } catch (Exception ignored) {
            // 兼容旧格式
        }
    }

    /** 本实例的凭据前缀。 */
    private String ownPrefix() {
        return TARGET_PREFIX + instanceId + "/";
    }

    /**
     * 列出「凭据管理器里有、但 accounts.json 里没有」的令牌目标名（安全审计 v2 A-09）。
     *
     * <p>只认**本实例**的凭据（{@code MCL/account/<实例id>/<uuid>}）。旧格式
     * （{@code MCL/account/<uuid>}）不参与判定 —— 它可能属于另一个数据目录的实例，
     * 清掉就等于让别人的账号掉登录。
     *
     * @param knownUuids 目前 accounts.json 里的 UUID 集合
     */
    public java.util.List<String> listOrphanTargets(java.util.Collection<String> knownUuids) {
        java.util.List<String> targets;
        try {
            targets = store.listTargets(ownPrefix());
        } catch (Throwable error) {
            return java.util.List.of();
        }
        java.util.List<String> orphans = new java.util.ArrayList<>();
        String prefix = ownPrefix();
        for (String target : targets) {
            if (!target.startsWith(prefix)) {
                continue;
            }
            String uuid = target.substring(prefix.length());
            if (uuid.isBlank() || (knownUuids != null && knownUuids.contains(uuid))) {
                continue;
            }
            orphans.add(target);
        }
        return orphans;
    }

    /** 删除枚举出来的孤儿凭据（A-09）。 */
    public int purgeTargets(java.util.List<String> targets) {
        int removed = 0;
        for (String target : targets) {
            try {
                store.delete(target);
                removed++;
            } catch (Throwable ignored) {
                // 尽力而为
            }
        }
        return removed;
    }

    private static String toStr(char[] chars) {
        return chars == null ? null : new String(chars);
    }

    private static char[] toChars(Object value) {
        return value == null ? null : String.valueOf(value).toCharArray();
    }
}
