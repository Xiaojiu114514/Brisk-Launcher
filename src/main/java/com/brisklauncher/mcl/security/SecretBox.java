package com.brisklauncher.mcl.security;

import com.brisklauncher.mcl.AppPaths;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 本地敏感数据的「静态加密」。
 *
 * <p>用途：{@code accounts.json} 里的令牌、{@code launcher.json} 里的微软客户端 ID
 * 等不该以明文躺在磁盘上的内容。
 *
 * <p>密钥来自共享账号目录的 {@code secret.key}（首次运行随机生成），并用
 * 「机器名 + 用户名」参与 KDF —— 把文件拷到别的电脑/别的账号上解不开。
 * 旧版把密钥放在安装目录的 {@code data/}，换一个 exe 路径就会拿到另一把密钥；现在会迁到
 * 共享账号目录，并在解密时回退尝试旧的安装目录密钥（让之前保存的令牌还能读出来）。
 *
 * <p><b>边界说明（很重要）</b>：这只能防「别人捡到文件/上传仓库」，
 * 挡不住能在你账号下运行代码的攻击者（他可以直接调用本类解密）。
 * 客户端 ID 也会在向后端发请求时出现在网络上，因此源码混淆只是提高门槛，
 * 不是机密级别的保护。
 */
public final class SecretBox {

    private static final String PREFIX = "sb1:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static byte[] cachedKey;
    /** 主密钥（共享账号目录）失败时依序尝试的旧密钥（安装目录 data/ 等）。 */
    private static List<byte[]> cachedKeys;

    /** 解密结果；{@code usedFallbackKey} 表示不是用共享账号目录的主密钥解开的。 */
    public record Decrypted(String text, boolean usedFallbackKey) {
    }

    private SecretBox() {
    }

    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /**
     * 明文 → "sb1:base64(iv+cipher)"。
     *
     * <p>安全审计 M-03：以前加密失败会**回退明文**（`return plain`），
     * 于是 {@code launcher.json} 里的客户端 ID 会静默以明文落盘。
     * 现在失败返回 {@code null}，由调用方决定「不保存该字段」；{@link SessionTokenStore}
     * 也会据此拒绝持久化令牌。
     */
    public static String encrypt(String plain) {
        if (plain == null || plain.isEmpty() || isEncrypted(plain)) {
            return plain == null ? "" : plain;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception error) {
            // 绝不回退明文：宁可这次不保存
            SafeLog.warn("敏感字段加密失败，本次不保存（" + error.getClass().getSimpleName() + "）");
            return null;
        }
    }

    /** "sb1:base64(iv+cipher)" → 明文；不是本格式时原样返回（兼容旧文件）。 */
    public static String decrypt(String value) {
        return decryptDetailed(value).text();
    }

    /**
     * 同 {@link #decrypt(String)}，但额外告诉调用方是否用了旧安装目录的密钥。
     *
     * <p>{@code SessionTokenStore} 用这个标记判断要不要把令牌用共享密钥重写一次，
     * 下次换 exe 路径启动时才能直接解开。
     */
    public static Decrypted decryptDetailed(String value) {
        if (!isEncrypted(value)) {
            return new Decrypted(value == null ? "" : value, false);
        }
        byte[] data;
        try {
            data = Base64.getDecoder().decode(value.substring(PREFIX.length()).trim());
            if (data.length <= IV_LENGTH) {
                return new Decrypted("", false);
            }
        } catch (Exception error) {
            SafeLog.warn("敏感字段解密失败（格式错误）");
            return new Decrypted("", false);
        }
        byte[] iv = new byte[IV_LENGTH];
        System.arraycopy(data, 0, iv, 0, IV_LENGTH);
        byte[] body = new byte[data.length - IV_LENGTH];
        System.arraycopy(data, IV_LENGTH, body, 0, body.length);

        List<byte[]> keys;
        try {
            keys = keys();
        } catch (Exception error) {
            SafeLog.warn("读取 secret.key 失败：" + error.getClass().getSimpleName());
            return new Decrypted("", false);
        }
        Exception last = null;
        for (int index = 0; index < keys.size(); index++) {
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keys.get(index), "AES"),
                        new GCMParameterSpec(TAG_BITS, iv));
                return new Decrypted(new String(cipher.doFinal(body), StandardCharsets.UTF_8), index > 0);
            } catch (Exception error) {
                last = error;
            }
        }
        SafeLog.warn("敏感字段解密失败（可能换了电脑）："
                + (last == null ? "unknown" : last.getClass().getSimpleName()));
        return new Decrypted("", false);
    }

    /** 主密钥（索引 0）；加密永远用它。 */
    private static synchronized byte[] key() throws Exception {
        if (cachedKey != null) {
            return cachedKey;
        }
        cachedKey = keys().get(0);
        return cachedKey;
    }

    /** 主密钥 + 旧安装目录的密钥，解密时依序尝试。 */
    private static synchronized List<byte[]> keys() throws Exception {
        if (cachedKeys != null) {
            return cachedKeys;
        }
        // 安全审计 H-02：密钥以前放在 data/（安装目录，可能是 U 盘/公共目录），
        // 而注释却说「永远在 %LOCALAPPDATA%」。现在统一走共享账号目录的 AppPaths.secretFile()。
        Path primary = AppPaths.secretFile();
        migrateLegacyKey(primary);
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        candidates.add(primary);
        candidates.add(AppPaths.launcherRoot().resolve("secret.key"));
        candidates.add(AppPaths.dataDir().resolve("secret.key"));
        List<byte[]> keys = new ArrayList<>();
        for (Path file : candidates) {
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                byte[] raw = Base64.getDecoder().decode(Files.readString(file).trim());
                keys.add(derive(raw));
            } catch (Exception error) {
                SafeLog.warn("忽略无法读取的 secret.key：" + file.getFileName()
                        + "（" + error.getClass().getSimpleName() + "）");
            }
        }
        if (keys.isEmpty()) {
            byte[] raw = new byte[32];
            RANDOM.nextBytes(raw);
            Files.createDirectories(primary.getParent());
            Files.writeString(primary, Base64.getEncoder().encodeToString(raw));
            FilePerms.ensurePrivateFile(primary);
            keys.add(derive(raw));
        }
        cachedKey = keys.get(0);
        cachedKeys = List.copyOf(keys);
        return cachedKeys;
    }

    /**
     * 旧版本把 secret.key 放在安装目录（data/ 或 launcherRoot）：搬一次到共享账号目录，
     * 并删掉旧位置（L-02 同类问题）。目标已存在时不覆盖 —— 另一条安装路径的旧密钥会作为
     * 解密回退保留（见 {@link #keys()}）。
     */
    private static void migrateLegacyKey(Path target) {
        if (Files.exists(target)) {
            return;
        }
        for (Path legacy : List.of(
                AppPaths.launcherRoot().resolve("secret.key"),
                AppPaths.dataDir().resolve("secret.key"))) {
            if (Files.exists(target)) {
                return;
            }
            try {
                if (legacy.equals(target) || !Files.isRegularFile(legacy)) {
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.copy(legacy, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(legacy);
                FilePerms.ensurePrivateFile(target);
                SafeLog.info("已把 secret.key 迁移到共享账号目录");
                return;
            } catch (Exception error) {
                SafeLog.warn("迁移 secret.key 失败：" + error.getClass().getSimpleName());
            }
        }
    }

    /** 用机器名+用户名做一次简单 KDF，让密钥文件离开本机就失效。 */
    private static byte[] derive(byte[] raw) throws Exception {
        String salt = System.getProperty("user.name", "")
                + "|" + System.getenv().getOrDefault("COMPUTERNAME", "")
                + "|mcl-secret";
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        digest.update(raw);
        digest.update(salt.getBytes(StandardCharsets.UTF_8));
        return digest.digest();
    }
}
