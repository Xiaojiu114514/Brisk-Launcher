package com.brisklauncher.mcl.util;

import com.brisklauncher.mcl.auth.impl.MicrosoftClientIdLocal;

/**
 * 微软客户端 ID 取值入口。
 *
 * <p>优先级：设置页里填的值 → 环境变量 {@code MCL_MS_CLIENT_ID} →
 * 本地源码 {@link MicrosoftClientIdLocal#CLIENT_ID}（该文件不入库）。
 * 都为空时返回调用方给的兜底值。
 */
public final class MsClientId {

    private static final String ENV = "BRISK_MS_CLIENT_ID";
    /** 舊名稱（兼容之前設過環境變數的人）。 */
    private static final String LEGACY_ENV = "MCL_MS_CLIENT_ID";

    private MsClientId() {
    }

    /**
     * 规范化用户提供的客户端 ID：去首尾空白、去掉误贴的引号，
     * 若为 {@code enc:...} 混淆串则解码（否则会把 enc: 当成 client_id 发给微软 → 403）。
     */
    private static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            value = value.substring(1, value.length() - 1).trim();
        }
        if (value.startsWith("enc:")) {
            value = com.brisklauncher.mcl.util.Obfuscated.decode(value).trim();
        }
        return value;
    }
    public static String resolve(String fromSettings) {
        String cleaned = clean(fromSettings);
        if (!cleaned.isEmpty()) {
            return cleaned;
        }
        String fromEnv = clean(System.getenv(ENV));
        if (!fromEnv.isEmpty()) {
            return fromEnv;
        }
        String legacy = clean(System.getenv(LEGACY_ENV));
        if (!legacy.isEmpty()) {
            return legacy;
        }
        String local = MicrosoftClientIdLocal.CLIENT_ID;
        if (local == null) {
            return "";
        }
        // 源码里的 ID 支持混淆形式（enc:...），防止反编译直接拿到明文
        return Obfuscated.decode(local.trim());
    }
}