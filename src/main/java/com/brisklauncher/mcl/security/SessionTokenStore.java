package com.brisklauncher.mcl.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.brisklauncher.mcl.auth.api.AccountType;
import com.brisklauncher.mcl.auth.api.StoredSession;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 会话持久化。令牌只落 Windows 凭据管理器，绝不写文件。
 * 说明：Java 无法彻底消除内存中的 String，这里尽量缩短令牌的 String 生命周期。
 */
public final class SessionTokenStore {

    private static final String TARGET_PREFIX = "MCL/account/";

    private final CredentialStore store;
    private final ObjectMapper mapper = new ObjectMapper();

    public SessionTokenStore(CredentialStore store) {
        this.store = store;
    }

    public static SessionTokenStore defaultStore() {
        WindowsCredentialStore windows = new WindowsCredentialStore();
        return new SessionTokenStore(windows.isAvailable() ? windows : new InMemoryCredentialStore());
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
            store.put(TARGET_PREFIX + session.uuid(), payload);
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
        Optional<char[]> raw = store.get(TARGET_PREFIX + uuid);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        char[] chars = raw.get();
        try {
            @SuppressWarnings("unchecked")
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
        store.delete(TARGET_PREFIX + uuid);
    }

    /**
     * 列出「凭据管理器里有、但 accounts.json 里没有」的令牌目标名（安全审计 v2 A-09）。
     *
     * <p>这种孤儿令牌是「元数据写失败」留下的：账号页看不到、也删不掉，
     * 只能靠枚举凭据目标名找出来。
     *
     * @param knownUuids 目前 accounts.json 里的 UUID 集合
     */
    public java.util.List<String> listOrphanTargets(java.util.Collection<String> knownUuids) {
        java.util.List<String> targets;
        try {
            targets = store.listTargets(TARGET_PREFIX);
        } catch (Throwable error) {
            return java.util.List.of();
        }
        java.util.List<String> orphans = new java.util.ArrayList<>();
        for (String target : targets) {
            String uuid = target.substring(TARGET_PREFIX.length());
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