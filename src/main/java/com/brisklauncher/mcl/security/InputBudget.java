package com.brisklauncher.mcl.security;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/**
 * 「不可信输入预算」：所有由使用者开启的外部档案都必须走这里。
 *
 * <p>安全审计 v2 A-01：启动器预设堆只有 192MB，而
 * <ul>
 *   <li>{@code Files.readAllBytes(file)} 没有任何上限（手滑选到 4GB 档案 → 直接 OOM）；</li>
 *   <li>{@code GZIPInputStream.readAllBytes()} 是标准的压缩炸弹入口
 *       （1KB 的 gzip 可以解出 10GB，足以 100% 打死启动器）。</li>
 * </ul>
 * 这里提供「有上限的读档」与「有上限的解压」两个工具，超限就抛 {@link IOException}
 * （由上层转成可读的中文错误），而不是让 JVM 被 OOM 打死。
 */
public final class InputBudget {

    /** 使用者主動開啟的本地檔案上限（spark 報告 / 投影檔等）。 */
    public static final long MAX_LOCAL_FILE = 32L * 1024 * 1024;
    /** 解壓後上限：gzip/zlib 炸彈的門檻。 */
    public static final long MAX_INFLATED = 64L * 1024 * 1024;
    /** 單一 zip 條目 / 解壓總量的上限（整合包導入）。 */
    public static final long MAX_ARCHIVE_TOTAL = 2L * 1024 * 1024 * 1024;
    public static final int MAX_ARCHIVE_ENTRIES = 20_000;
    /** mrpack 索引 JSON 的上限（正常只有幾百 KB）。 */
    public static final long MAX_INDEX_JSON = 16L * 1024 * 1024;
    /** NBT 單一 byte array 的上限（原 64MB 太寬）。 */
    public static final long MAX_NBT_ARRAY = 8L * 1024 * 1024;
    /** NBT 巢狀深度上限。 */
    public static final int MAX_NBT_DEPTH = 64;
    /** 認不出格式的圖片（串流）上限：先讀成位元組再交給 JavaFX。 */
    public static final long MAX_UNKNOWN_IMAGE_BYTES = 4L * 1024 * 1024;

    private InputBudget() {
    }

    /** 有上限的串流讀取：超限回 {@code null}（呼叫方當作「不是可用資源」）。 */
    public static byte[] readBoundedStream(java.io.InputStream stream, long max)
            throws IOException {
        if (stream == null) {
            return null;
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(8192);
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        int read;
        while ((read = stream.read(buffer)) > 0) {
            total += read;
            if (total > max) {
                return null;
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** 有上限的讀檔：先看 size，再讀。 */
    public static byte[] readBounded(Path file, long max) throws IOException {
        long size = Files.size(file);
        if (size > max) {
            throw new IOException("档案过大（" + human(size) + " > " + human(max) + "），已拒绝载入");
        }
        return Files.readAllBytes(file);
    }

    /** 有上限的 gzip 解壓：絕不用 readAllBytes，超限立刻拋。 */
    public static byte[] gunzipBounded(byte[] compressed, long max) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream out = new ByteArrayOutputStream(
                     Math.min(1 << 20, Math.max(8192, compressed.length * 4)))) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > max) {
                    throw new IOException("压缩档解压后过大（> " + human(max)
                            + "），疑似压缩炸弹，已拒绝载入");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    /** 讀檔 + 自動判斷 gzip（兩段都有上限）。 */
    public static byte[] readPossiblyGzip(Path file) throws IOException {
        byte[] data = readBounded(file, MAX_LOCAL_FILE);
        if (data.length > 2 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B) {
            return gunzipBounded(data, MAX_INFLATED);
        }
        return data;
    }

    /**
     * 有上限的流拷贝（zip 条目解压用）：超过 {@code max} 立刻抛。
     *
     * @return 实际写入位元组数
     */
    public static long copyBounded(java.io.InputStream in, java.io.OutputStream out, long max)
            throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) > 0) {
            total += read;
            if (total > max) {
                throw new IOException("解压后资料量过大（> " + human(max) + "），已中止");
            }
            out.write(buffer, 0, read);
        }
        return total;
    }

    /**
     * zip 条目名称净化：拒绝 NTFS 备用资料流（冒号）、结尾点/空格、控制字元。
     *
     * <p>安全审计 v2 A-10：{@code a.txt:evil} 会写进 ADS（资源管理器看不见但确实存在）。
     */
    public static String safeArchiveEntryName(String name) {
        if (name == null || name.isBlank()) {
            throw new SecurityException("压缩档内有空名称的条目");
        }
        String value = name.replace('\\', '/');
        for (char c : value.toCharArray()) {
            if (c == ':' || c < 0x20 || c == 0x7F) {
                throw new SecurityException("压缩档条目名称含非法字元：" + name);
            }
        }
        for (String part : value.split("/")) {
            if (part.isBlank() || part.equals(".") || part.equals("..")) {
                throw new SecurityException("压缩档条目名称非法：" + name);
            }
            if (part.endsWith(".") || part.endsWith(" ")) {
                throw new SecurityException("压缩档条目名称以点或空格结尾：" + name);
            }
        }
        return value;
    }

    public static String human(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1073741824.0);
        }
        if (bytes >= 1024L * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0);
        }
        if (bytes >= 1024L) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }
}
