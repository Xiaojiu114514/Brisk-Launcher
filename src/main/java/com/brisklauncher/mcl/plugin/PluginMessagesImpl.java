package com.brisklauncher.mcl.plugin;

import com.brisklauncher.mcl.i18n.I18n;
import com.brisklauncher.mcl.plugin.api.PluginMessages;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link PluginMessages} 的實現：從插件 jar 的 {@code i18n/messages*.properties}（UTF-8）讀字串。
 *
 * <p>查找順序：當前標籤（{@code zh_TW}）→ 同語言（{@code zh}）→ {@code en_US} → {@code en}
 * → 預設 {@code i18n/messages.properties}。全部沒有就回傳鍵名本身。
 */
final class PluginMessagesImpl implements PluginMessages {

    private static final String BASE = "i18n/messages";
    private static final String SUFFIX = ".properties";

    private final ClassLoader loader;
    private final List<String> locales;
    private final Map<String, Properties> cache = new ConcurrentHashMap<>();

    PluginMessagesImpl(ClassLoader loader, List<String> locales) {
        this.loader = loader;
        this.locales = locales == null ? List.of() : List.copyOf(locales);
    }

    /** 掃描 jar 內的語言包標籤（{@code messages_zh_TW.properties} → {@code zh_TW}）。 */
    static List<String> scanLocales(java.util.zip.ZipFile zip) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (!name.startsWith(BASE) || !name.endsWith(SUFFIX) || name.indexOf('/', BASE.length()) >= 0) {
                continue;
            }
            String middle = name.substring(BASE.length(), name.length() - SUFFIX.length());
            if (middle.startsWith("_") && middle.length() > 1) {
                tags.add(middle.substring(1));
            }
        }
        return new ArrayList<>(tags);
    }

    @Override
    public String t(String key) {
        if (key == null) {
            return "";
        }
        for (String candidate : candidates()) {
            Properties bundle = bundle(candidate);
            if (bundle != null) {
                String value = bundle.getProperty(key);
                if (value != null) {
                    return value;
                }
            }
        }
        return key;
    }

    @Override
    public String t(String key, Object... args) {
        String raw = t(key);
        if (args == null || args.length == 0) {
            return raw;
        }
        try {
            return java.text.MessageFormat.format(raw, args);
        } catch (Throwable broken) {
            // 插件自己的 pattern 寫壞了不該讓 UI 掛掉
            return raw;
        }
    }

    @Override
    public boolean has(String key) {
        if (key == null) {
            return false;
        }
        for (String candidate : candidates()) {
            Properties bundle = bundle(candidate);
            if (bundle != null && bundle.getProperty(key) != null) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<String> locales() {
        return locales;
    }

    /** 依當前界面語言排出「要試哪些檔案」。 */
    private List<String> candidates() {
        String tag = I18n.currentLocaleTag();
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        if (tag != null && !tag.isBlank()) {
            ordered.add(tag);
            int cut = tag.indexOf('_');
            if (cut > 0) {
                ordered.add(tag.substring(0, cut));
            }
        }
        ordered.add("en_US");
        ordered.add("en");
        ordered.add("");
        return new ArrayList<>(ordered);
    }

    private Properties bundle(String tag) {
        return cache.computeIfAbsent(tag, this::load);
    }

    private Properties load(String tag) {
        String resource = BASE + (tag == null || tag.isEmpty() ? "" : "_" + tag) + SUFFIX;
        try (InputStream input = loader.getResourceAsStream(resource)) {
            if (input == null) {
                return null;
            }
            Properties properties = new Properties();
            properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            return properties;
        } catch (Exception error) {
            return null;
        }
    }

    /** 語言標籤正規化（{@code zh-tw} → {@code zh_TW}）。 */
    static String normalizeTag(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String[] parts = raw.trim().split("[-_]");
        if (parts.length == 1) {
            return parts[0].toLowerCase(Locale.ROOT);
        }
        return parts[0].toLowerCase(Locale.ROOT) + "_" + parts[1].toUpperCase(Locale.ROOT);
    }
}
