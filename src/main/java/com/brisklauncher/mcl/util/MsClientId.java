package com.brisklauncher.mcl.util;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 微软客户端 ID 取值入口。
 *
 * <p>优先级：
 * <ol>
 *   <li>系统属性 {@code -Dbrisk.ms.client.id=...}（仅开发期临时覆盖）；</li>
 *   <li>设置页里填的值（使用者显式选择，优先级高于构建内置值）；</li>
 *   <li>jar 内 {@code assets/brisk-build.properties}（构建期由 gradle 注入）；</li>
 *   <li>环境变量 {@code BRISK_MS_CLIENT_ID} / 旧名 {@code MCL_MS_CLIENT_ID}（兼容用，逐步淘汰）。</li>
 * </ol>
 *
 * <p><b>为什么环境变量排在最后</b>：环境变量会被启动游戏的子进程继承，
 * 任何不受信的 mod 一行 {@code System.getenv(...)} 就能拿到；而且它优先级高于构建内置值时，
 * 外部可以「劫持登录」到别的 appid。所以环境变量只保留为兼容项，不再作为主来源。
 */
public final class MsClientId {

    private static final String BUILD_INFO = "/assets/brisk-build.properties";
    private static final String PROP_KEY = "microsoft.client.id";
    private static final String SYS_PROP = "brisk.ms.client.id";
    private static final String ENV = "BRISK_MS_CLIENT_ID";
    /** 旧名称（兼容之前设过环境变量的人）。 */
    private static final String LEGACY_ENV = "MCL_MS_CLIENT_ID";

    private MsClientId() {
    }

    /**
     * 规范化使用者提供的客户端 ID：去首尾空白、去掉误贴的引号；
     * 若为 {@code enc:...} 旧混淆串则解码（否则会把 enc: 当成 client_id 发给微软 → 403）。
     */
    @SuppressWarnings("removal") // 兼容旧配置的临时路径，Obfuscated 本身已标为待移除
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
        // 旧版曾把 enc: 混淆串写进 launcher.json / 环境变量；保留解码 2~3 个版本做兼容
        if (Obfuscated.isEncoded(value)) {
            value = Obfuscated.decode(value).trim();
        }
        return value;
    }

    /** 取出生效的客户端 ID；都为空时返回空字符串（调用方负责提示）。 */
    public static String resolve(String fromSettings) {
        String value = clean(System.getProperty(SYS_PROP));
        if (!value.isEmpty()) {
            return value;
        }
        value = clean(fromSettings);
        if (!value.isEmpty()) {
            return value;
        }
        value = clean(fromBuildInfo());
        if (!value.isEmpty()) {
            return value;
        }
        value = clean(System.getenv(ENV));
        if (!value.isEmpty()) {
            return value;
        }
        return clean(System.getenv(LEGACY_ENV));
    }

    /**
     * 当前 ID 来自哪里（设置页显示、导出诊断用）。
     *
     * @return {@code system-property} / {@code settings} / {@code build} / {@code env} /
     *         {@code env-legacy} / {@code none}
     */
    public static String source(String fromSettings) {
        if (!clean(System.getProperty(SYS_PROP)).isEmpty()) {
            return "system-property";
        }
        if (!clean(fromSettings).isEmpty()) {
            return "settings";
        }
        if (!clean(fromBuildInfo()).isEmpty()) {
            return "build";
        }
        if (!clean(System.getenv(ENV)).isEmpty()) {
            return "env";
        }
        if (!clean(System.getenv(LEGACY_ENV)).isEmpty()) {
            return "env-legacy";
        }
        return "none";
    }

    /** 是否是「本构建内置了 ID」的官方构建。 */
    public static boolean bundled() {
        return !clean(fromBuildInfo()).isEmpty();
    }

    /** jar 内构建期注入的值；开发环境（classes 目录）读不到时回空。 */
    private static String fromBuildInfo() {
        try (InputStream in = MsClientId.class.getResourceAsStream(BUILD_INFO)) {
            if (in == null) {
                return "";
            }
            Properties props = new Properties();
            // 用 Reader：Properties.load(InputStream) 是 ISO-8859-1
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return props.getProperty(PROP_KEY, "");
        } catch (Exception ignored) {
            return "";
        }
    }
}
