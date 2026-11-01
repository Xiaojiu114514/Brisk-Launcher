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

    /** 一個可選的包。 */
    public record Pack(String id, String name, Path dir, boolean custom) {
    }

    private static final String THEME_FILE = "theme.txt";

    private Packs() {
    }

    public static Path langDir() {
        return AppPaths.dataDir().resolve("lang");
    }

    public static Path themeDir() {
        return AppPaths.themesDir();
    }

    /** 首次執行時建立目錄並寫入說明檔（已存在就跳過，不會覆蓋玩家的包）。 */
    public static void ensure() {
        try {
            Files.createDirectories(langDir());
            Files.createDirectories(themeDir());
            Path langReadme = langDir().resolve("README.txt");
            if (!Files.isRegularFile(langReadme)
                    || !Files.readString(langReadme, StandardCharsets.UTF_8).contains("模板副本")) {
                Files.writeString(langReadme, """
                        语言包目录 / Language packs
                        ==========================

                        每个语言包一个文件夹，文件夹名就是语言标识，例如 my_lang。
                        文件夹内放 messages.properties（UTF-8），键名与内置语言包一致
                        （可参考 zh_CN / zh_TW / zh_HK / en_US 里的 messages.properties）。
                        可选：同目录放 pack.properties，内容 name=显示名称。

                        注意：zh_CN / zh_TW / zh_HK / en_US 这四个是内置语言的「模板副本」，
                        每次启动都会跟着版本刷新；想自己改翻译，请把整个文件夹复制成新名字
                        （例如 zh_CN_my），再改里面的内容，然后在「设置 → 外观」里选它。

                        加好后重启启动器，在「设置 → 外观」里就能选到。
                        """, StandardCharsets.UTF_8);
            }
            copyBuiltinLanguages();
            writeExampleTheme();
            Path themeReadme = themeDir().resolve("README.txt");
            if (!Files.isRegularFile(themeReadme)) {
                Files.writeString(themeReadme, """
                        主题包目录 / Theme packs
                        ========================

                        每个主题包一个文件夹，文件夹名就是主题标识，例如 my_theme。
                        文件夹内放 theme.css（会叠加在默认主题之上，写你要覆盖的规则即可）。
                        可选：同目录放 pack.properties，内容 name=显示名称。

                        加好后重启启动器，在「设置 → 外观」里就能选到。
                        """, StandardCharsets.UTF_8);
            }
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

    /** 附一个示例主题，演示覆盖颜色与使用背景图。 */
    private static void writeExampleTheme() {
        try {
            Path dir = themeDir().resolve("example");
            Files.createDirectories(dir);
            Path meta = dir.resolve("pack.properties");
            if (!Files.isRegularFile(meta)) {
                Files.writeString(meta, "name=示例主题（可改）\n", StandardCharsets.UTF_8);
            }
            Path css = dir.resolve("theme.css");
            if (!Files.isRegularFile(css)) {
                Files.writeString(css, """
                        /* 示例主题：把这里的颜色改成你喜欢的即可。
                           保存后到「设置 → 外观 → 主题包」选择它（切换即时生效）。 */

                        /* 顶栏 / 卡片主色 */
                        .root {
                            -fx-background-color: #F4FCFA;
                        }

                        /* 想要背景图：把 background.png 放进本文件夹，
                           启动器会自动把它铺满窗口底层；也可以在这里自己指定：
                        .root-backdrop {
                            -fx-background-image: url("background.png");
                            -fx-background-size: cover;
                        }
                        */

                        /* 标题文字颜色 */
                        .card-title, .header-title {
                            -fx-text-fill: #14453F;
                        }
                        """, StandardCharsets.UTF_8);
            }
        } catch (Exception error) {
            SafeLog.warn("写入示例主题失败：" + error.getClass().getSimpleName());
        }
    }

    /** 当前主题的背景图（background.png/jpg/jpeg/webp），没有就回传 null。 */
    public static Path currentThemeBackground() {
        String id = currentTheme();
        if ("default".equals(id)) {
            return null;
        }
        for (Pack pack : scan(themeDir(), "theme.css")) {
            if (!pack.id().equals(id)) {
                continue;
            }
            for (String name : List.of("background.png", "background.jpg",
                    "background.jpeg", "background.webp")) {
                Path file = pack.dir().resolve(name);
                if (Files.isRegularFile(file)) {
                    return file;
                }
            }
        }
        return null;
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

    public static List<Pack> themes() {
        List<Pack> result = new ArrayList<>();
        result.add(new Pack("default", "默认（Brisk）", null, false));
        // 去重：如果玩家自己建了一个叫 default 的主题目录，别让它出现两次
        for (Pack pack : scan(themeDir(), "theme.css")) {
            boolean duplicate = result.stream().anyMatch(existing -> existing.id().equals(pack.id()));
            if (!duplicate) {
                result.add(pack);
            }
        }
        return result;
    }

    /** 目前選中的主題 id；沒有記錄或包被刪掉就回 default。 */
    public static String currentTheme() {
        Path file = AppPaths.launcherRoot().resolve(THEME_FILE);
        String id = "default";
        try {
            if (Files.isRegularFile(file)) {
                id = stripBom(Files.readString(file, StandardCharsets.UTF_8)).trim();
            }
        } catch (Exception ignored) {
            // 读不到就用预设
        }
        for (Pack pack : themes()) {
            if (pack.id().equals(id)) {
                return id;
            }
        }
        return "default";
    }

    public static void setTheme(String id) {
        try {
            Files.createDirectories(AppPaths.launcherRoot());
            Files.writeString(AppPaths.launcherRoot().resolve(THEME_FILE),
                    id == null ? "default" : id, StandardCharsets.UTF_8);
        } catch (Exception error) {
            SafeLog.warn("保存主题选择失败：" + error.getClass().getSimpleName());
        }
    }

    /** 目前主題的 CSS 檔案；default 或找不到回傳 null。 */
    public static Path currentThemeCss() {
        String id = currentTheme();
        if ("default".equals(id)) {
            return null;
        }
        for (Pack pack : scan(themeDir(), "theme.css")) {
            if (pack.id().equals(id)) {
                return pack.dir().resolve("theme.css");
            }
        }
        return null;
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
                String name = displayNameOf(path, id);
                packs.add(new Pack(id, name, path, true));
            }
        } catch (Exception error) {
            SafeLog.warn("扫描包目录失败：" + dir);
        }
        return packs;
    }

    /** pack.properties 裡的 name=（沒有就用文件夾名）。 */
    private static String displayNameOf(Path dir, String fallback) {
        Path meta = dir.resolve("pack.properties");
        if (Files.isRegularFile(meta)) {
            try {
                Properties properties = new Properties();
                properties.load(new java.io.StringReader(stripBom(
                        Files.readString(meta, StandardCharsets.UTF_8))));
                String name = properties.getProperty("name");
                if (name != null && !name.isBlank()) {
                    return name.trim();
                }
            } catch (Exception ignored) {
                // 名称读不到不影响可用性
            }
        }
        return fallback;
    }
}
