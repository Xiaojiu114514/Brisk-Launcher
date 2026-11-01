package com.brisklauncher.mcl.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 图片解压/尺寸炸弹防护（安全审计 v2 A-06）。
 *
 * <p>JavaFX 的 {@code new Image(..., requestedWidth, requestedHeight, ...)} 里的尺寸只是**显示**尺寸，
 * 解码仍然是整张图。一张 30000×30000 的 PNG 压缩后可能只有几百 KB，解码却要 3.6GB 常驻 ——
 * 在 192MB 堆下必死。这里在真正解码前先读文件头拿宽高并拒绝超限。
 *
 * <p>只解析头部（PNG / JPEG / GIF / WebP），未知格式一律放行（宁可放过也不要误伤正常图片）。
 */
public final class ImageGuard {

    /** 单边上限（超过通常是异常图片）。 */
    public static final int MAX_SIDE = 8192;
    /** 总像素上限（8192×8192 ≈ 67M；这里用 33M 足够覆盖 4K/8K 贴图，同时挡住 900M 那种）。 */
    public static final long MAX_PIXELS = 33_000_000L;
    /** 认不出格式时的字节上限：正常图标/皮肤远小于此值。 */
    public static final long MAX_UNKNOWN_BYTES = 4L * 1024 * 1024;

    private ImageGuard() {
    }

    /**
     * 文件版：读**有上限的整个文件**判断。
     *
     * <p>安全审计 v3 F-1：以前只读前 64KB。而 JPEG 的 SOF（含宽高）位置没有规定——
     * 在 SOF 前面塞 100KB 的 APPn/COM 填充段，64KB 窗口就看不到 SOF → 认不出格式 →
     * 走「< 4MB 就放行」的兜底 → 解码时按 30000×30000 分配 3.6GB。
     * 现在读 {@code min(size, 4MB)}：SOF 在 4MB 之后的那种文件，总大小也必然 > 4MB，
     * 会落到「未知格式且过大 → 拒绝」。
     */
    public static boolean acceptable(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return false;
        }
        try {
            long size = Files.size(file);
            if (size <= 0) {
                return false;
            }
            try (var in = Files.newInputStream(file)) {
                byte[] head = in.readNBytes((int) Math.min(size, MAX_UNKNOWN_BYTES));
                return acceptable0(head, size);
            }
        } catch (IOException error) {
            return false;
        }
    }

    /** 字节版：能识别格式就按宽高判断，认不出格式按字节数兜底（不再无条件放行）。 */
    public static boolean acceptable(byte[] data) {
        return acceptable0(data, data == null ? 0 : data.length);
    }

    private static boolean acceptable0(byte[] data, long totalBytes) {
        if (data == null || data.length == 0) {
            return false;
        }
        int[] size = dimensions(data);
        if (size == null) {
            // 声称是常见图片格式（magic 命中）却解析不出宽高 → 可疑，直接拒绝（F-1 第 2 档）
            if (looksLikeKnownFormat(data)) {
                return false;
            }
            // 安全审计 v3 A-11：JavaFX 还内建 BMP/ICO/TIFF 等解码器，
            // 「认不出就放行」等于让这些格式绕过整套防护 → 改成按字节数兜底。
            return totalBytes <= MAX_UNKNOWN_BYTES;
        }
        return size[0] > 0 && size[1] > 0
                && size[0] <= MAX_SIDE && size[1] <= MAX_SIDE
                && (long) size[0] * size[1] <= MAX_PIXELS;
    }

    /** magic 是否指向「我们声称能解析的格式」。 */
    private static boolean looksLikeKnownFormat(byte[] data) {
        if (data.length >= 4 && (data[0] & 0xFF) == 0x89 && data[1] == 'P'
                && data[2] == 'N' && data[3] == 'G') {
            return true;
        }
        if (data.length >= 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8
                && (data[2] & 0xFF) == 0xFF) {
            return true;
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0x42 && (data[1] & 0xFF) == 0x4D) {
            return true;
        }
        if (data.length >= 6 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F'
                && data[3] == '8') {
            return true;
        }
        return data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F'
                && data[3] == 'F';
    }

    /** 解析常见图片格式的宽高；识别不了返回 {@code null}。 */
    static int[] dimensions(byte[] data) {
        if (data == null || data.length < 8) {
            return null;
        }
        // BMP：BITMAPFILEHEADER(14) + DIB 头，宽高是小端 int32（offset 18 / 22）
        if ((data[0] & 0xFF) == 0x42 && (data[1] & 0xFF) == 0x4D && data.length >= 26) {
            int width = intLe(data, 18);
            int height = Math.abs(intLe(data, 22));   // 负高度表示自上而下
            return new int[]{width, height};
        }
        // ICO / CUR：目录项里就是宽高（1 字节，0 表示 256）
        if ((data[0] & 0xFF) == 0 && (data[1] & 0xFF) == 0
                && ((data[2] & 0xFF) == 1 || (data[2] & 0xFF) == 2)) {
            int width = data[6] == 0 ? 256 : (data[6] & 0xFF);
            int height = data[7] == 0 ? 256 : (data[7] & 0xFF);
            return new int[]{width, height};
        }
        if (data.length < 24) {
            return null;
        }
        // PNG：IHDR 固定在第 16..23 字节
        if ((data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
            return new int[]{intAt(data, 16), intAt(data, 20)};
        }
        // GIF：6 字节头 + 宽高（小端 16 位）
        String head = new String(data, 0, Math.min(6, data.length), StandardCharsets.ISO_8859_1);
        if (head.startsWith("GIF8")) {
            int width = (data[6] & 0xFF) | ((data[7] & 0xFF) << 8);
            int height = (data[8] & 0xFF) | ((data[9] & 0xFF) << 8);
            return new int[]{width, height};
        }
        // WebP：RIFF....WEBP，VP8/VP8L/VP8X 三种
        if (data.length > 30 && new String(data, 0, 4, StandardCharsets.ISO_8859_1).equals("RIFF")
                && new String(data, 8, 4, StandardCharsets.ISO_8859_1).equals("WEBP")) {
            String kind = new String(data, 12, 4, StandardCharsets.ISO_8859_1);
            if ("VP8X".equals(kind)) {
                int width = 1 + ((data[24] & 0xFF) | ((data[25] & 0xFF) << 8) | ((data[26] & 0xFF) << 16));
                int height = 1 + ((data[27] & 0xFF) | ((data[28] & 0xFF) << 8) | ((data[29] & 0xFF) << 16));
                return new int[]{width, height};
            }
            if ("VP8 ".equals(kind)) {
                int width = ((data[26] & 0xFF) | ((data[27] & 0xFF) << 8)) & 0x3FFF;
                int height = ((data[28] & 0xFF) | ((data[29] & 0xFF) << 8)) & 0x3FFF;
                return new int[]{width, height};
            }
            return null;
        }
        // JPEG：逐段找 SOFn
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) {
            int index = 2;
            while (index + 9 < data.length) {
                if ((data[index] & 0xFF) != 0xFF) {
                    index++;
                    continue;
                }
                int marker = data[index + 1] & 0xFF;
                if (marker == 0xD8 || marker == 0xD9
                        || (marker >= 0xD0 && marker <= 0xD7)) {
                    index += 2;
                    continue;
                }
                int segmentLength = ((data[index + 2] & 0xFF) << 8) | (data[index + 3] & 0xFF);
                if (marker >= 0xC0 && marker <= 0xCF
                        && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    int height = ((data[index + 5] & 0xFF) << 8) | (data[index + 6] & 0xFF);
                    int width = ((data[index + 7] & 0xFF) << 8) | (data[index + 8] & 0xFF);
                    return new int[]{width, height};
                }
                if (segmentLength <= 0) {
                    return null;
                }
                index += 2 + segmentLength;
            }
        }
        return null;
    }

    private static int intAt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }

    /** 小端 int32（BMP 的宽高）。 */
    private static int intLe(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }
}
