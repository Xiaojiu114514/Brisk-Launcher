package com.brisklauncher.mcl.setting;

import com.brisklauncher.mcl.security.SafeLog;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 主題的「設計變數 → 顏色」解析。
 *
 * <p>用途：JVM {@code -splash:} 的原生啟動圖必須是**圖片檔**，而在 JavaFX 起來之前沒有 CSS 引擎，
 * 所以這裡用最笨但可靠的方式：把 {@code mcl-theme.css}（內建）+ 目前主題的 CSS 當純文字掃一遍，
 * 抓 {@code -brisk-xxx: <顏色>;} 的宣告（後面的覆蓋前面的），得到一張 name → color 的表。
 *
 * <p>這樣第三方主題只要是「覆蓋 -brisk-* 變數」的寫法（本專案推薦的寫法），原生啟動圖就能跟著換色。
 * 完全沒定義變數、只覆蓋選擇器的主題會退回內建預設色 —— 至少不會是突兀的黑色。
 */
public final class ThemeColors {

    /** {@code -brisk-name: value;}（允許前面有空白；值只取到分號）。 */
    private static final Pattern TOKEN = Pattern.compile(
            "(?m)^\\s*(-brisk-[a-z0-9-]+)\\s*:\\s*([^;{}]+);");

    private ThemeColors() {
    }

    /** 合併「內建 + 目前主題」的設計變數（後者優先）。 */
    public static Map<String, String> current() {
        Map<String, String> tokens = new LinkedHashMap<>();
        readInto(tokens, baseCss());
        for (Path css : themeCss()) {
            try {
                readInto(tokens, Files.readString(css, StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                // 讀不到就少一份覆蓋，用內建值
            }
        }
        return tokens;
    }

    /** 內建樣式表（classpath）。 */
    public static String baseCss() {
        try (InputStream input = ThemeColors.class.getResourceAsStream("/styles/mcl-theme.css")) {
            if (input == null) {
                return "";
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception error) {
            SafeLog.warn("[theme] 讀取內建樣式失敗：" + error.getClass().getSimpleName());
            return "";
        }
    }

    /**
     * 目前主題的 CSS 檔案（輕量路徑：不初始化 Jackson / I18n）。
     *
     * <p>{@link ThemePacks#current()} 會做完整掃描（含 theme.json），在啟動階段要 ~0.6 秒；
     * 這裡只需要「哪幾個 css 檔」，用 {@link ThemePacks#startupCssUris()} 就夠。
     */
    private static List<Path> themeCss() {
        List<Path> result = new ArrayList<>();
        for (String uri : ThemePacks.startupCssUris()) {
            try {
                result.add(Path.of(java.net.URI.create(uri)));
            } catch (Exception ignored) {
                // 非 file: URI 就跳過
            }
        }
        return result;
    }

    private static void readInto(Map<String, String> tokens, String css) {
        if (css == null || css.isEmpty()) {
            return;
        }
        // 去掉註解，避免把說明文字裡的範例當成宣告
        String clean = css.replaceAll("(?s)/\\*.*?\\*/", "");
        Matcher matcher = TOKEN.matcher(clean);
        while (matcher.find()) {
            String name = matcher.group(1).trim().toLowerCase(Locale.ROOT);
            String value = matcher.group(2).trim();
            if (!value.isEmpty()) {
                tokens.put(name, value);
            }
        }
    }

    /** 取某個 token 的 AWT 顏色（解析不了就用 fallback）。 */
    public static java.awt.Color awt(Map<String, String> tokens, String name,
                                     java.awt.Color fallback) {
        String value = tokens.get(name);
        java.awt.Color parsed = parse(value);
        return parsed == null ? fallback : parsed;
    }

    /** 解析 {@code #rgb} / {@code #rrggbb} / {@code rgba(r,g,b,a)} / {@code rgb(r,g,b)}。 */
    public static java.awt.Color parse(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        if (text.startsWith("#")) {
            String hex = text.substring(1);
            try {
                if (hex.length() == 3) {
                    int r = Integer.parseInt(hex.substring(0, 1).repeat(2), 16);
                    int g = Integer.parseInt(hex.substring(1, 2).repeat(2), 16);
                    int b = Integer.parseInt(hex.substring(2, 3).repeat(2), 16);
                    return new java.awt.Color(r, g, b);
                }
                if (hex.length() == 6) {
                    return new java.awt.Color(Integer.parseInt(hex, 16));
                }
                if (hex.length() == 8) {
                    int r = Integer.parseInt(hex.substring(0, 2), 16);
                    int g = Integer.parseInt(hex.substring(2, 4), 16);
                    int b = Integer.parseInt(hex.substring(4, 6), 16);
                    int a = Integer.parseInt(hex.substring(6, 8), 16);
                    return new java.awt.Color(r, g, b, a);
                }
            } catch (NumberFormatException ignored) {
                return null;
            }
            return null;
        }
        if (text.toLowerCase(Locale.ROOT).startsWith("rgb")) {
            Matcher matcher = Pattern.compile(
                    "rgba?\\(\\s*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*,\\s*([0-9.]+)"
                            + "(?:\\s*,\\s*([0-9.]+)\\s*)?\\)").matcher(text);
            if (matcher.find()) {
                try {
                    int r = clamp((int) Math.round(Double.parseDouble(matcher.group(1))));
                    int g = clamp((int) Math.round(Double.parseDouble(matcher.group(2))));
                    int b = clamp((int) Math.round(Double.parseDouble(matcher.group(3))));
                    double alpha = matcher.group(4) == null ? 1.0
                            : Double.parseDouble(matcher.group(4));
                    if (alpha <= 1.0) {
                        return new java.awt.Color(r, g, b,
                                clamp((int) Math.round(alpha * 255)));
                    }
                    return new java.awt.Color(r, g, b, clamp((int) Math.round(alpha)));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
