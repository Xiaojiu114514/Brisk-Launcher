package com.brisklauncher.mcl.i18n;

import com.brisklauncher.mcl.setting.Packs;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 国际化入口。
 *
 * <p>内置简体中文与英文（打包在 resources 里）；玩家自己做的语言包放在
 * {@code <launcherRoot>/lang/<id>/messages.properties}，键名与内置一致即可。
 * 语言切换通过 {@link #addListener(Runnable)} 通知 UI 重建。
 */
public final class I18n {

    private static final String BUNDLE = "com.brisklauncher.mcl.i18n.messages";
    private static final List<String> SUPPORTED = List.of("zh_CN", "zh_TW", "en_US");

    private static String currentTag = "zh_CN";
    private static Locale currentLocale = Locale.SIMPLIFIED_CHINESE;
    private static ResourceBundle bundle = load(Locale.SIMPLIFIED_CHINESE);
    /** 当前生效的自定义语言包（null = 用内置）。 */
    private static Properties customBundle;
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private I18n() {
    }

    public static String t(String key) {
        String custom = customValue(key);
        if (custom != null) {
            return custom;
        }
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            return "!" + key + "!";
        }
    }

    public static String t(String key, Object... args) {
        String raw = customValue(key);
        if (raw == null) {
            try {
                raw = bundle.getString(key);
            } catch (MissingResourceException e) {
                return "!" + key + "!";
            }
        }
        // 安全审计 v3 A-17：MessageFormat 对 {0,choice,...} 是递归解析的，
        // 第三方语言包塞几百层嵌套会抛 StackOverflowError（Error，catch IllegalArgumentException 接不住）
        // → 该调用点所在线程直接死掉。这里先做深度/长度预检，再兜底 catch Throwable。
        if (!safePattern(raw)) {
            return raw;
        }
        try {
            return MessageFormat.format(raw, args);
        } catch (Throwable error) {
            return raw;
        }
    }

    /** 仅测试用：直接对一段 pattern 做格式化（验证不会 StackOverflow）。 */
    public static String formatForTest(String pattern, Object... args) {
        if (!safePattern(pattern)) {
            return pattern;
        }
        try {
            return MessageFormat.format(pattern, args);
        } catch (Throwable error) {
            return pattern;
        }
    }

    /** 语言包 pattern 预检：长度上限 + 大括号嵌套深度上限 + choice 层数上限。 */    private static boolean safePattern(String raw) {
        if (raw == null) {
            return true;
        }
        if (raw.length() > 4096) {
            return false;
        }
        int depth = 0;
        int maxDepth = 0;
        for (int index = 0; index < raw.length(); index++) {
            char current = raw.charAt(index);
            if (current == '{') {
                depth++;
                maxDepth = Math.max(maxDepth, depth);
                if (maxDepth > 8) {
                    return false;
                }
            } else if (current == '}') {
                depth--;
            }
        }
        return !raw.toLowerCase(java.util.Locale.ROOT).contains("choice")
                || countOccurrences(raw, "choice") <= 2;
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    /** 自定义语言包有这个键就用它，否则回退内置（缺键时不会满屏 !key!）。 */
    private static String customValue(String key) {
        Properties custom = customBundle;
        return custom == null ? null : custom.getProperty(key);
    }

    public static String currentLocaleTag() {
        return currentTag;
    }

    /** 内置 + 自定义语言包。 */
    public static List<String> supportedLocales() {
        // 内建语言包现在也会以文件形式出现在 lang/ 里，
        // 直接用 Set 去重，避免下拉框出现「简体中文 / 简体中文」重复项。
        java.util.Set<String> result = new java.util.LinkedHashSet<>(SUPPORTED);
        for (Packs.Pack pack : Packs.languages()) {
            if (pack.custom()) {
                result.add(pack.id());
            }
        }
        return new ArrayList<>(result);
    }

    public static String displayName(String tag) {
        return switch (tag) {
            case "zh_CN" -> "简体中文";
            case "zh_TW" -> "繁体中文（台湾）";
            case "en_US" -> "English";
            default -> Packs.languageName(tag);
        };
    }

    /**
     * 切换语言：内置 zh_CN / en_US 走 ResourceBundle，
     * 其他值视为 {@code lang/<id>/messages.properties} 的自定义语言包。
     */
    public static void setLocale(String tag) {
        if (tag == null || tag.isBlank() || tag.equals(currentTag)) {
            return;
        }
        boolean builtin = SUPPORTED.contains(tag);
        Properties custom = builtin ? null : Packs.customLanguage(tag);
        if (!builtin && custom == null) {
            return;
        }
        currentTag = tag;
        customBundle = custom;
        if (custom == null) {
            currentLocale = switch (tag) {
                case "en_US" -> Locale.US;
                case "zh_TW" -> Locale.TRADITIONAL_CHINESE;
                    default -> Locale.SIMPLIFIED_CHINESE;
            };
            bundle = load(currentLocale);
        } else {
            // 自訂語言包：缺鍵時回退到「最接近的內建語言」，而不是上一個選過的語言
            currentLocale = closestBuiltin(tag);
            bundle = load(currentLocale);
        }
        listeners.forEach(Runnable::run);
    }

    /** 自訂語言包 id → 最接近的內建語言（{@code my_zh_TW} 會回退到繁中）。 */
    private static Locale closestBuiltin(String tag) {
        String lower = tag == null ? "" : tag.toLowerCase(Locale.ROOT);
        if (lower.contains("hk") || lower.contains("hant-hk")) {
            return Locale.forLanguageTag("zh-Hant-HK");
        }
        if (lower.contains("tw") || lower.contains("hant")) {
            return Locale.TRADITIONAL_CHINESE;
        }
        if (lower.startsWith("en") || lower.contains("_en")) {
            return Locale.US;
        }
        return Locale.SIMPLIFIED_CHINESE;
    }

    /** 註冊語言切換監聽；回傳的 Runnable 用來取消註冊（插件卸載時要用）。 */
    public static Runnable addListener(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private static ResourceBundle load(Locale locale) {
        return ResourceBundle.getBundle(BUNDLE, locale);
    }
}
