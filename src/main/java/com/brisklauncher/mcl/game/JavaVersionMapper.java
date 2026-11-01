package com.brisklauncher.mcl.game;

import java.util.Locale;

/**
 * MC 版本 → Java 主版本映射。
 * 规则与 PCL2 / HMCL 一致：以版本 JSON 的 {@code javaVersion.majorVersion} 为准，
 * 缺失时（老版本或第三方整合包）按 MC 版本号推算。
 */
public final class JavaVersionMapper {

    private JavaVersionMapper() {
    }

    public static int forMinecraft(String mcVersion) {
        if (mcVersion == null || mcVersion.isBlank()) {
            return 17;
        }
        int[] parts = parse(mcVersion);
        int major = parts[0];
        int minor = parts.length > 1 ? parts[1] : 0;
        int patch = parts.length > 2 ? parts[2] : 0;

        if (major != 1) {
            // 新的版本号方案（26.x 起）：官方运行时是 Java 25。
            // 版本 JSON 里自带 javaVersion 时以它为准，这里只是剥离掉 JSON 时的兜底。
            return major >= 26 ? 25 : 17;
        }
        // 1.20.5 起官方要求 Java 21
        if (minor > 20 || (minor == 20 && patch >= 5)) {
            return 21;
        }
        // 1.17 ~ 1.20.4 是 Java 17
        if (minor >= 17) {
            return 17;
        }
        // 1.12 ~ 1.16 官方 Java 8，但大量整合包跑在 11/17 上；默认 8 最稳
        return 8;
    }

    /** 展示用："Java 17"。 */
    public static String display(int major) {
        return major <= 0 ? "Java" : "Java " + major;
    }

    private static int[] parse(String version) {
        String cleaned = version.trim();
        int dash = cleaned.indexOf('-');
        if (dash > 0) {
            cleaned = cleaned.substring(0, dash);
        }
        String[] raw = cleaned.split("\\.");
        int[] parts = new int[raw.length];
        for (int i = 0; i < raw.length; i++) {
            try {
                parts[i] = Integer.parseInt(raw[i].replaceAll("[^0-9]", ""));
            } catch (NumberFormatException e) {
                parts[i] = 0;
            }
        }
        if (parts.length == 0) {
            return new int[]{1, 0, 0};
        }
        return parts;
    }

    /** 从版本号里判断是不是快照，用于 UI 排序/标签。 */
    public static boolean looksLikeSnapshot(String mcVersion) {
        if (mcVersion == null) {
            return false;
        }
        String lower = mcVersion.toLowerCase(Locale.ROOT);
        return lower.contains("snapshot") || lower.matches(".*\\d{2}w\\d{2}[a-z].*");
    }
}
