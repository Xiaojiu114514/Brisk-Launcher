package com.brisklauncher.mcl.util;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 轻度混淆：给「不便公开但又不是密码」的常量用。
 *
 * <p><b>已停止使用，仅保留用于兼容旧配置</b>（{@code launcher.json} 或环境变量里
 * 可能还存着 {@code enc:} 串）。新代码不要再产生这种形态：
 * 盐固定在源码里、解码器也可从同一仓库取得，所以它<strong>不提供任何保密性</strong>，
 * 只能挡住随手 grep。微软客户端 ID 现在的做法是「承认公开 + 构建期内联明文」（见
 * {@code config/client-id.properties.example} 的说明）。
 *
 * <p>为什么不做加密学意义上的安全承诺：客户端 ID 在运行期必然要出现在内存与网络请求里，
 * 任何本地加密都挡不住同机的进程。真正的机密（client secret）本项目不用。
 *
 * @deprecated 混淆不提供保密性；读取路径只保留给旧配置做兼容，2~3 个版本后移除。
 */
@Deprecated(forRemoval = true)
public final class Obfuscated {

    /** 混淆后的前缀；解析时见到它就解码。 */
    public static final String PREFIX = "enc:";

    /** 固定盐（只提高「肉眼可读性」门槛，不是密钥）。 */
    private static final byte[] SALT = {
            0x4D, 0x65, 0x6F, 0x77, 0x43, 0x72, 0x61, 0x66, 0x74, 0x2D, 0x4D, 0x43, 0x4C
    };

    private Obfuscated() {
    }

    /** 明文 → "enc:base64(xor)"。 */
    public static String encode(String plain) {
        if (plain == null || plain.isEmpty()) {
            return "";
        }
        byte[] data = plain.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = (byte) (data[i] ^ SALT[i % SALT.length] ^ (i * 31));
        }
        return PREFIX + Base64.getEncoder().encodeToString(out);
    }

    /** "enc:base64(xor)" → 明文；不是该格式时原样返回。 */
    public static String decode(String value) {
        if (value == null || value.isEmpty() || !value.startsWith(PREFIX)) {
            return value == null ? "" : value;
        }
        try {
            byte[] data = Base64.getDecoder().decode(value.substring(PREFIX.length()).trim());
            byte[] out = new byte[data.length];
            for (int i = 0; i < data.length; i++) {
                out[i] = (byte) (data[i] ^ SALT[i % SALT.length] ^ (i * 31));
            }
            return new String(out, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    public static boolean isEncoded(String value) {
        return value != null && value.startsWith(PREFIX);
    }
}
