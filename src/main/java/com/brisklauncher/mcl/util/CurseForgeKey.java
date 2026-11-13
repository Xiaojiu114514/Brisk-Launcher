package com.brisklauncher.mcl.util;

import com.brisklauncher.mcl.security.SafeLog;

import java.io.InputStream;
import java.util.Properties;

/**
 * CurseForge API key 的解析（与微软客户端 ID 同一套注入方式）。
 *
 * <p>优先级：{@code -Dmcl.curseforge.key} → 设置页填的 key（加密存 {@code launcher.json}）
 * → jar 内 {@code assets/brisk-build.properties} → 环境变量 {@code BRISK_CURSEFORGE_KEY}。
 * 构建值来自 {@code config/secrets.properties}（不入库），由 gradle 注入。
 *
 * <p>没有 key 时回传 null，不抛例外：CurseForge 搜索会跳过官方端点、直接走镜像
 * （mod.mcimirror.top，不需要 key）；只有镜像也失败时界面才提示「API 为空」。
 */
public final class CurseForgeKey {

    private static volatile String cached;
    private static volatile boolean resolved;
    /** 设置页填的 key（运行时由 {@link #setUserKey(String)} 同步；空字串 = 没填）。 */
    private static volatile String userKey = "";

    private CurseForgeKey() {
    }

    /** 设置页保存时调用：更新使用者填的 key，并让下一次 {@link #resolve()} 重新解析。 */
    public static void setUserKey(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.equals(userKey)) {
            return;
        }
        userKey = normalized;
        synchronized (CurseForgeKey.class) {
            resolved = false;
            cached = null;
        }
    }

    public static String resolve() {
        if (resolved) {
            return cached;
        }
        synchronized (CurseForgeKey.class) {
            if (resolved) {
                return cached;
            }
            String value = firstNonBlank(
                    System.getProperty("mcl.curseforge.key"),
                    userKey,
                    fromBuildInfo(),
                    System.getenv("BRISK_CURSEFORGE_KEY"));
            cached = value;
            resolved = true;
            if (value == null) {
                SafeLog.info("[curseforge] 没有可用的 API key（设置页未填、构建也未注入）");
            }
            return value;
        }
    }

    public static boolean available() {
        return resolve() != null;
    }

    private static String fromBuildInfo() {
        try (InputStream input = CurseForgeKey.class.getResourceAsStream("/assets/brisk-build.properties")) {
            if (input == null) {
                return null;
            }
            Properties properties = new Properties();
            properties.load(input);
            return properties.getProperty("curseforge.api.key");
        } catch (Exception error) {
            SafeLog.warn("[curseforge] 读取构建信息失败：" + error.getClass().getSimpleName());
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}