package com.brisklauncher.mcl.setting;

import com.brisklauncher.mcl.AppPaths;
import com.brisklauncher.mcl.security.SafeLog;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * 语言包 / 主题包。
 *
 * <p>目录结构（放在启动器数据目录下，玩家自己就能看懂、自己加包）：
 * <pre>
 *   &lt;launcherRoot&gt;/
 *     lang/
 *       README.txt
 *       zh_CN/messages.properties        ← 内建（打包在 jar 里，这里只是复本说明）
 *       my_lang/messages.properties      ← 自订语言包
 *                    pack.properties     ← 可选：name=显示名称
 *     themes/
 *       README.txt
 *       my_theme/theme.css               ← 自订主题（额外一段 CSS，覆盖在预设主题之上）
 *                 pack.properties        ← 可选：name=显示名称
 * </pre>
 * 语言包 key 与内建一致（见 messages_zh_CN.properties）；主题包只需要一个 theme.css。
 */
public final class Packs {

    /** 一個可選的包。{@code author} / {@code version} 來自 {@code pack.properties}（可選）。 */
    public record Pack(String id, String name, Path dir, boolean custom, String author,
                       String version) {

        public Pack(String id, String name, Path dir, boolean custom) {
            this(id, name, dir, custom, "", "");
        }
    }

    private Packs() {
    }

    public static Path langDir() {
        return AppPaths.dataDir().resolve("lang");
    }

    /** 主題包目錄（真正的實作在 {@link ThemePacks}）。 */
    public static Path themeDir() {
        return ThemePacks.dir();
    }

    /** 首次執行時建立目錄並寫入說明檔（已存在就跳過，不會覆蓋玩家的包）。 */
    public static void ensure() {
        try {
            Files.createDirectories(langDir());
            Path langReadme = langDir().resolve("README.txt");
            if (!Files.isRegularFile(langReadme)
                    || !Files.readString(langReadme, StandardCharsets.UTF_8).contains("模板副本")) {
                Files.writeString(langReadme, """
                        语言包目录 / Language packs
                        ==========================

                        每个语言包一个文件夹，文件夹名就是语言标识，例如 my_lang。
                        文件夹内放 messages.properties（UTF-8），键名与内置语言包一致
                        （可参考 zh_CN / zh_TW / zh_HK / en_US 里的 messages.properties）。
                        可选：同目录放 pack.properties，内容 name=显示名称 / author=作者 / version=版本。

                        注意：zh_CN / zh_TW / zh_HK / en_US 这四个是内置语言的「模板副本」，
                        每次启动都会跟着版本刷新；想自己改翻译，请把整个文件夹复制成新名字
                        （例如 zh_CN_my），再改里面的内容，然后在「设置 → 外观」里选它。

                        加好后重启启动器，在「设置 → 外观」里就能选到。
                        """, StandardCharsets.UTF_8);
            }
            copyBuiltinLanguages();
            // 主题包：目录、说明档、内建主题（night / sakura / contrast）都在 ThemePacks 里
            ThemePacks.ensure();
        } catch (Exception error) {
            SafeLog.warn("建立语言包/主题包目录失败：" + error.getClass().getSimpleName());
        }
    }

    /**
     * 把内建语言包写成文件（玩家可直接复制改翻译）。只在文件不存在时写，不会覆盖自订包。
     *
     * <p>四种语言都要写出来（含繁中 TW / HK）——使用者要在 {@code data/lang} 底下
     * 直接看到并能改全部内建语言包，而不是只有简中与英文。
     */
    private static void copyBuiltinLanguages() {
        writeResourceLanguage("zh_CN", "简体中文", "/com/brisklauncher/mcl/i18n/messages.properties");
        writeResourceLanguage("zh_TW", "繁体中文（台湾）",
                "/com/brisklauncher/mcl/i18n/messages_zh_TW.properties");
        writeResourceLanguage("zh_HK", "繁体中文（香港）",
                "/com/brisklauncher/mcl/i18n/messages_zh_HK.properties");
        writeResourceLanguage("en_US", "English",
                "/com/brisklauncher/mcl/i18n/messages_en_US.properties");
    }

    private static void writeResourceLanguage(String id, String displayName, String resource) {
        try {
            Path dir = langDir().resolve(id);
            Files.createDirectories(dir);
            Path target = dir.resolve("messages.properties");
            byte[] builtin;
            try (InputStream input = Packs.class.getResourceAsStream(resource)) {
                if (input == null) {
                    SafeLog.warn("内建语言包资源不存在：" + resource);
                    return;
                }
                builtin = input.readAllBytes();
            }
            // 内建语言包的复本会「跟著版本刷新」：这里是给玩家参考/复制用的模板，
            // 想改翻译请复制整个资料夹改成新 id（README 有写），否则更新启动器后会被覆盖。
            boolean stale = !Files.isRegularFile(target)
                    || !java.util.Arrays.equals(Files.readAllBytes(target), builtin);
            if (stale) {
                Files.write(target, builtin);
            }
            Path meta = dir.resolve("pack.properties");
            String expected = "name=" + displayName + "\nbuiltin=true\n";
            if (!Files.isRegularFile(meta)
                    || !expected.equals(stripBom(Files.readString(meta, StandardCharsets.UTF_8)))) {
                Files.writeString(meta, expected, StandardCharsets.UTF_8);
            }
        } catch (Exception error) {
            SafeLog.warn("写入内建语言包失败：" + id);
        }
    }

    /** 当前主题的背景图（background.png/jpg/jpeg/webp 或 theme.json 指定），没有就回传 null。 */
    public static Path currentThemeBackground() {
        return ThemePacks.background();
    }

    /** 去掉 UTF-8 BOM：记事本/PowerShell 保存的 UTF-8 文件常带 BOM，会让 id/键名比对失败。 */
    static String stripBom(String text) {
        if (text == null) {
            return "";
        }
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }
    // ---------- 语言包 ----------

    /** 內建語言 + 自訂語言包。 */
    public static List<Pack> languages() {
        List<Pack> result = new ArrayList<>();
        result.add(new Pack("zh_CN", "简体中文", null, false));
        result.add(new Pack("zh_TW", "繁体中文（台湾）", null, false));
        result.add(new Pack("zh_HK", "繁体中文（香港）", null, false));
        result.add(new Pack("en_US", "English", null, false));
        // lang/ 下的内建复本会被扫成 custom=true，这里去重避免下拉出现两份
        for (Pack pack : scan(langDir(), "messages.properties")) {
            boolean duplicate = result.stream().anyMatch(existing -> existing.id().equals(pack.id()));
            if (!duplicate) {
                result.add(pack);
            }
        }
        return result;
    }

    public static String languageName(String id) {
        for (Pack pack : languages()) {
            if (pack.id().equals(id)) {
                return pack.name();
            }
        }
        return id;
    }

    /** 讀自訂語言包；回傳 null 表示用內建 ResourceBundle。 */
    public static Properties customLanguage(String id) {
        for (Pack pack : scan(langDir(), "messages.properties")) {
            if (pack.id().equals(id)) {
                try {
                    String text = stripBom(Files.readString(
                            pack.dir().resolve("messages.properties"), StandardCharsets.UTF_8));
                    Properties properties = new Properties();
                    properties.load(new java.io.StringReader(text));
                    return properties;
                } catch (Exception error) {
                    SafeLog.warn("读取语言包失败：" + id);
                }
            }
        }
        return null;
    }

    // ---------- 主题包 ----------
    //
    // 真正的实作在 {@link ThemePacks}（theme.json、多 CSS、背景图参数、快取、安全扫描）。
    // 这里保留旧方法名，避免其它地方（含外部习惯）突然编译不过。

    /** @deprecated 用 {@link ThemePacks#all()}。 */
    @Deprecated
    public static List<Pack> themes() {
        List<Pack> result = new ArrayList<>();
        for (ThemePacks.Theme theme : ThemePacks.all()) {
            result.add(new Pack(theme.id(), theme.displayName(),
                    theme.custom() ? ThemePacks.dir().resolve(theme.id()) : null,
                    theme.custom(), theme.author(), theme.version()));
        }
        return result;
    }

    /** 目前選中的主題 id；沒有記錄或包被刪掉就回 default。 */
    public static String currentTheme() {
        return ThemePacks.currentId();
    }

    public static void setTheme(String id) {
        ThemePacks.setCurrent(id);
    }

    /** 目前主題的主 CSS（第一個）；default 或找不到回傳 null。 */
    public static Path currentThemeCss() {
        List<Path> styles = ThemePacks.current().styles();
        return styles.isEmpty() ? null : styles.get(0);
    }

    // ---------- 扫描 ----------

    private static List<Pack> scan(Path dir, String requiredFile) {
        List<Pack> packs = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return packs;
        }
        try (var stream = Files.list(dir)) {
            for (Path path : stream.sorted().toList()) {
                if (!Files.isDirectory(path) || !Files.isRegularFile(path.resolve(requiredFile))) {
                    continue;
                }
                String id = path.getFileName().toString();
                String[] meta = metaOf(path, id);
                packs.add(new Pack(id, meta[0], path, true, meta[1], meta[2]));
            }
        } catch (Exception error) {
            SafeLog.warn("扫描包目录失败：" + dir);
        }
        return packs;
    }

    /**
     * 讀 {@code pack.properties}：{@code name=} / {@code author=} / {@code version=}（都可選）。
     *
     * @return {@code [显示名, 作者, 版本]}；显示名会带上作者（有的话），方便在设置页区分同名包
     */
    private static String[] metaOf(Path dir, String fallback) {
        String name = fallback;
        String author = "";
        String version = "";
        Path meta = dir.resolve("pack.properties");
        if (Files.isRegularFile(meta)) {
            try {
                Properties properties = new Properties();
                properties.load(new java.io.StringReader(stripBom(
                        Files.readString(meta, StandardCharsets.UTF_8))));
                String declaredName = properties.getProperty("name");
                if (declaredName != null && !declaredName.isBlank()) {
                    name = declaredName.trim();
                }
                author = trimToEmpty(properties.getProperty("author"));
                version = trimToEmpty(properties.getProperty("version"));
            } catch (Exception ignored) {
                // 名称读不到不影响可用性
            }
        }
        return new String[]{author.isBlank() ? name : name + "（" + author + "）", author, version};
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
