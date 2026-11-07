package com.brisklauncher.mcl.setting;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 主題包自檢（不需要開 JavaFX、不需要真的啟動啟動器）。
 *
 * <p>驗證的是「主題包契約」裡最容易悄悄壞掉的部分：目錄掃描、theme.json 解析、
 * 多份 CSS 的順序、背景圖參數、安全檢查（遠端 url / @font-face）、
 * 路徑逃逸防護、選擇回退、以及快取是否真的生效。
 *
 * <p>跑法（先 {@code mvn -o compile}）：
 * <pre>
 *   java -cp "target\classes;build\install\brisk-launcher\lib\*" \
 *        -Dmcl.themes.dir=target\theme-selftest\themes \
 *        com.brisklauncher.mcl.setting.ThemeSelfTest
 * </pre>
 * 結束碼 0 = 全過。
 */
public final class ThemePacksSelfTest {

    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        Path work = Path.of("target", "theme-selftest");
        Path themes = work.resolve("themes");
        prepare(work, themes);
        System.setProperty("mcl.themes.dir", themes.toString());
        System.setProperty("mcl.home", work.resolve("home").toString());
        Files.createDirectories(work.resolve("home"));
        ThemePacks.invalidate();

        testEnsure(themes);
        testBuiltins();
        testCustomTheme(themes);
        testGuardRejectsBadCss(themes);
        testPathEscape(themes);
        testSelectionFallback(work);
        testCache(themes);
        testExampleSample(themes);

        System.out.println();
        System.out.println("主题包自检：" + (checks - failures) + "/" + checks + " 通过");
        if (failures > 0) {
            System.out.println("失败 " + failures + " 项");
            System.exit(1);
        }
    }

    // ---------- 各项检查 ----------

    private static void testEnsure(Path themes) {
        ThemePacks.ensure();
        check("ensure: 建立主题目录", Files.isDirectory(themes));
        check("ensure: 写 README", Files.isRegularFile(themes.resolve("README.txt")));
        for (String id : List.of("night", "sakura", "contrast")) {
            check("ensure: 内建主题 " + id, Files.isRegularFile(themes.resolve(id).resolve("theme.css"))
                    && Files.isRegularFile(themes.resolve(id).resolve("theme.json")));
        }
    }

    private static void testBuiltins() {
        List<ThemePacks.Theme> all = ThemePacks.all();
        check("内建: 至少 4 个主题（含 default）", all.size() >= 4, Integer.toString(all.size()));
        check("内建: 第一个是 default", ThemePacks.DEFAULT_ID.equals(all.get(0).id()));
        ThemePacks.Theme night = byId(all, "night");
        check("内建: night 存在", night != null);
        if (night != null) {
            check("内建: night 名字（zh_CN）", "夜色".equals(night.name()), night.name());
            check("内建: night 是深色", night.dark());
            check("内建: night 有说明", !night.description().isBlank());
            check("内建: night accent", "#7C9CFF".equals(night.accent()), night.accent());
            check("内建: night 有 1 份 CSS", night.styles().size() == 1,
                    Integer.toString(night.styles().size()));
            check("内建: night 无警告", night.warnings().isEmpty(), night.warnings().toString());
        }
        ThemePacks.Theme sakura = byId(all, "sakura");
        check("内建: sakura 名字（zh_CN）", sakura != null && "樱花".equals(sakura.name()));
        check("内建: sakura 自带 logo（复制到 data/themes）",
                sakura != null && sakura.logo() != null && Files.isRegularFile(sakura.logo()),
                sakura == null || sakura.logo() == null ? "null" : sakura.logo().toString());
        check("内建: night 没有 logo（回退内建樱花方块）",
                night != null && night.logo() == null);
        ThemePacks.Theme contrast = byId(all, "contrast");
        check("内建: contrast 名字（zh_CN）", contrast != null && "高对比".equals(contrast.name()));
        check("内建: default 没有 CSS", ThemePacks.all().get(0).styles().isEmpty());
    }

    private static void testCustomTheme(Path themes) throws Exception {
        Path dir = themes.resolve("demo");
        Files.createDirectories(dir);
        write(dir.resolve("theme.json"), """
                {
                  "name": {"zh_CN": "演示主题", "en_US": "Demo"},
                  "author": "tester",
                  "version": "2.1.0",
                  "description": {"zh_CN": "演示用的主题", "en_US": "demo theme"},
                  "base": "dark",
                  "accent": "#123456",
                  "styles": ["b.css", "theme.css"],
                  "backgroundFit": "repeat",
                  "backgroundDim": 0.4
                }
                """);
        write(dir.resolve("theme.css"), ".root-pane { -fx-background-color: #101010; }\n");
        write(dir.resolve("b.css"), ".nav-button { -fx-text-fill: #EEEEEE; }\n");
        write(dir.resolve("a.css"), ".card { -fx-background-color: #181818; }\n");
        ThemePacks.invalidate();
        ThemePacks.Theme demo = byId(ThemePacks.all(), "demo");
        check("自订: 被扫描到", demo != null);
        if (demo == null) {
            return;
        }
        check("自订: 多语言名字取当前语言（zh_CN）", "演示主题".equals(demo.name()), demo.name());
        check("自订: 作者/版本", "tester".equals(demo.author()) && "2.1.0".equals(demo.version()));
        check("自订: styles 依宣告顺序", demo.styles().size() == 2
                && "b.css".equals(demo.styles().get(0).getFileName().toString())
                && "theme.css".equals(demo.styles().get(1).getFileName().toString()),
                demo.styles().toString());
        check("自订: backgroundFit = repeat", "repeat".equals(demo.backgroundFit()),
                demo.backgroundFit());
        check("自订: backgroundDim = 0.4", Math.abs(demo.backgroundDim() - 0.4) < 0.0001,
                Double.toString(demo.backgroundDim()));
        check("自订: 深色", demo.dark());
        check("自订: displayName 带作者", demo.displayName().contains("tester"), demo.displayName());

        // 没写 styles 时：theme.css 第一，其余按档名
        write(dir.resolve("theme.json"), """
                {"name": "演示主题", "author": "tester"}
                """);
        ThemePacks.invalidate();
        ThemePacks.Theme scanned = byId(ThemePacks.all(), "demo");
        check("自订: 未宣告 styles 时 theme.css 优先", scanned != null
                && "theme.css".equals(scanned.styles().get(0).getFileName().toString()),
                scanned == null ? "null" : scanned.styles().toString());
        check("自订: 其余 CSS 依档名", scanned != null && scanned.styles().size() == 3
                && "a.css".equals(scanned.styles().get(1).getFileName().toString()),
                scanned == null ? "null" : scanned.styles().toString());
    }

    private static void testGuardRejectsBadCss(Path themes) throws Exception {
        Path dir = themes.resolve("evil");
        Files.createDirectories(dir);
        write(dir.resolve("theme.json"), "{\"name\": \"evil\"}\n");
        write(dir.resolve("theme.css"),
                ".root-pane { -fx-background-image: url('https://evil.example/x.png'); }\n");
        ThemePacks.invalidate();
        ThemePacks.Theme evil = byId(ThemePacks.all(), "evil");
        check("安全: 远端 url 的主题仍在清单里（方便显示原因）", evil != null);
        check("安全: 远端 url 的 CSS 不被加载", evil != null && evil.styles().isEmpty());
        check("安全: 有对应警告", evil != null && !evil.warnings().isEmpty(),
                evil == null ? "null" : evil.warnings().toString());

        Path font = themes.resolve("fontface");
        Files.createDirectories(font);
        write(font.resolve("theme.css"), "@font-face { font-family: x; src: url(x.ttf); }\n");
        ThemePacks.invalidate();
        ThemePacks.Theme fontface = byId(ThemePacks.all(), "fontface");
        check("安全: @font-face 被拒绝", fontface == null || fontface.styles().isEmpty());
    }

    private static void testPathEscape(Path themes) throws Exception {
        Path dir = themes.resolve("escape");
        Files.createDirectories(dir);
        write(themes.resolve("outside.css"), ".card { -fx-background-color: #000000; }\n");
        write(dir.resolve("theme.json"), "{\"name\": \"escape\", \"styles\": [\"../outside.css\"]}\n");
        ThemePacks.invalidate();
        ThemePacks.Theme escape = byId(ThemePacks.all(), "escape");
        check("安全: 不允许跳出主题资料夹", escape == null || escape.styles().isEmpty());
        check("安全: 跳出有警告", escape != null && !escape.warnings().isEmpty(),
                escape == null ? "null" : escape.warnings().toString());
    }

    private static void testSelectionFallback(Path work) throws Exception {
        Path home = work.resolve("home");
        Files.createDirectories(home);
        Files.writeString(home.resolve("theme.txt"), "no-such-theme", StandardCharsets.UTF_8);
        ThemePacks.invalidate();
        check("选择: 未知 id 回退清单第一项（default）",
                ThemePacks.DEFAULT_ID.equals(ThemePacks.current().id()),
                ThemePacks.current().id());
        // 第一次启动（没有 theme.txt）：品牌主色是樱花粉，所以初始主题是 sakura
        Files.deleteIfExists(home.resolve("theme.txt"));
        ThemePacks.invalidate();
        check("选择: 没有 theme.txt 时用初始主题（sakura）",
                ThemePacks.INITIAL_ID.equals(ThemePacks.current().id()),
                ThemePacks.current().id());
        check("选择: selectedId 也回初始主题",
                ThemePacks.INITIAL_ID.equals(ThemePacks.selectedId()),
                ThemePacks.selectedId());
        ThemePacks.setCurrent("night");
        check("选择: setCurrent 生效", "night".equals(ThemePacks.current().id()),
                ThemePacks.current().id());
        check("选择: 记进 theme.txt",
                "night".equals(Files.readString(home.resolve("theme.txt")).trim()));
        List<String> uris = ThemePacks.cssUris();
        check("选择: CSS URI 指向主题文件",
                uris.size() == 1 && uris.get(0).contains("/themes/night/theme.css"), uris.toString());
        ThemePacks.setCurrent(ThemePacks.DEFAULT_ID);
        check("选择: default 没有 CSS", ThemePacks.cssUris().isEmpty());
    }

    private static void testCache(Path themes) throws Exception {
        ThemePacks.invalidate();
        List<ThemePacks.Theme> first = ThemePacks.all();
        List<ThemePacks.Theme> second = ThemePacks.all();
        check("缓存: 同样的目录拿到同一份结果", first == second);
        Path css = themes.resolve("demo").resolve("a.css");
        Files.writeString(css, ".card { -fx-background-color: #101010; }\n", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(css, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() + 2000));
        List<ThemePacks.Theme> third = ThemePacks.all();
        check("缓存: 档案变了会重建清单", third != first);
    }

    // ---------- 工具 ----------

    /** 專案裡附的範例主題（examples/my-theme）也要真的讀得進來。 */
    private static void testExampleSample(Path themesRestore) throws Exception {
        Path examples = Path.of("examples");
        if (!Files.isDirectory(examples.resolve("my-theme"))) {
            check("范例: examples/my-theme 不存在（跳过）", true);
            return;
        }
        System.setProperty("mcl.themes.dir", examples.toString());
        ThemePacks.invalidate();
        ThemePacks.Theme sample = byId(ThemePacks.all(), "my-theme");
        check("范例: examples/my-theme 被扫描到", sample != null);
        if (sample != null) {
            check("范例: 两份 CSS" , sample.styles().size() == 2,
                    Integer.toString(sample.styles().size()));
            check("范例: 背景图存在", sample.background() != null && Files.isRegularFile(sample.background()));
            check("范例: 缩图存在", sample.preview() != null && Files.isRegularFile(sample.preview()));
            check("范例: backgroundDim = 0.35", Math.abs(sample.backgroundDim() - 0.35) < 0.0001,
                    Double.toString(sample.backgroundDim()));
            check("范例: 没有警告", sample.warnings().isEmpty(), sample.warnings().toString());
        }
        System.setProperty("mcl.themes.dir", themesRestore.toString());
        ThemePacks.invalidate();
    }

    private static void prepare(Path work, Path themes) throws Exception {
        if (Files.isDirectory(work)) {
            try (var stream = Files.walk(work)) {
                List<Path> paths = stream.sorted(java.util.Comparator.reverseOrder()).toList();
                for (Path path : paths) {
                    Files.deleteIfExists(path);
                }
            }
        }
        Files.createDirectories(themes);
    }

    private static ThemePacks.Theme byId(List<ThemePacks.Theme> themes, String id) {
        return themes.stream().filter(theme -> theme.id().equals(id)).findFirst().orElse(null);
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static void check(String label, boolean ok) {
        check(label, ok, "");
    }

    private static void check(String label, boolean ok, String detail) {
        checks++;
        if (ok) {
            System.out.println("ok    " + label + (detail.isEmpty() ? "" : "  → " + detail));
        } else {
            failures++;
            System.out.println("FAIL  " + label + (detail.isEmpty() ? "" : "  → " + detail));
        }
    }

    private ThemePacksSelfTest() {
    }
}
