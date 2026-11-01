package com.brisklauncher.mcl.download;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 哈希/大小校验工具。安装链路的所有“文件是否已经好了”的判断都走这里。 */
public final class HashUtils {

    private HashUtils() {
    }

    public static String sha1(Path file) throws IOException {
        return hash(file, "SHA-1");
    }

    public static String hash(Path file, String algorithm) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("不支持的哈希算法: " + algorithm, e);
        }
    }

    /**
     * 判断本地文件是否已经满足任务要求。
     * 有 sha1 时以 sha1 为准；没有 sha1 时退化为大小比对。
     */
    public static boolean matches(Path file, String expectedSha1, long expectedSize) {
        try {
            if (!Files.isRegularFile(file)) {
                return false;
            }
            if (expectedSize > 0 && Files.size(file) != expectedSize) {
                return false;
            }
            if (expectedSha1 != null && !expectedSha1.isBlank()) {
                return expectedSha1.equalsIgnoreCase(sha1(file));
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean isBlankSha1(String sha1) {
        return sha1 == null || sha1.isBlank();
    }

    /** 字符串的 SHA-1（用于生成缓存签名，不涉及安全场景）。 */
    public static String hashText(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
