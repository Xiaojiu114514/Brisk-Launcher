package com.brisklauncher.mcl.util;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 轻度混淆：给「不便公开但又不是密码」的常量（例如微软客户端 ID）用。
 *
 * <p>不做加密学意义上的安全承诺——客户端 ID 在运行期必然要出现在内存里；
 * 这里只是让 {@code strings}/{@code javap -c} 之类的手段**拿不到明文**，
 * 防止随手反编译就抄走。真正的机密（client secret）本项目不用。
 */
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
