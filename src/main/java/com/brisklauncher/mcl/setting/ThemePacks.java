package com.brisklauncher.mcl.setting;

import com.brisklauncher.mcl.AppPaths;
import com.brisklauncher.mcl.i18n.I18n;
import com.brisklauncher.mcl.net.Http;
import com.brisklauncher.mcl.security.SafeLog;
import com.brisklauncher.mcl.ui.ThemeCssGuard;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * 主題包引擎：{@code data/themes/<id>/} 的掃描、清單快取、CSS 與背景圖解析。
 *
 * <p>一個主題包可以有：
 * <pre>
 *   data/themes/my-theme/
 *     theme.json        可選，宣告 name / author / version / description / styles / background …
 *     pack.properties   舊格式（等於 theme.json 的最小子集）
 *     theme.css         主樣式（會被疊在啟動器預設樣式之上）
 *     extra.css         其它樣式（依 styles 或檔名順序疊上）
 *     background.png    背景圖（也可用 theme.json 指定）
 *     preview.png       設定頁縮圖（可選）
 * </pre>
 *
 * <p><b>效能</b>：整份清單與 CSS URI 都按「資料夾 + 檔案（大小 / 修改時間）」簽名快取，
 * 對話框每次套樣式時只做一次簽名比對，不會重讀 CSS、不會重跑安全檢查。
 *
 * <p><b>安全</b>：每個 CSS 都先過 {@link ThemeCssGuard}（禁止遠端 url()、禁止 {@code @font-face}），
 * 被拒絕的檔案不載入，並記在 {@link Theme#warnings()} 裡給設定頁顯示。
 */
public final class ThemePacks {

    public static final String DEFAULT_ID = "default";

    /**
     * 首次啟動用的主題（{@code theme.txt} 不存在時）。
     *
     * <p>櫻花粉是品牌主色，所以預設就進 sakura；已經選過主題的使用者不受影響
     * （只要 {@code theme.txt} 有值，一律以檔案為準）。
     */
    public static final String INITIAL_ID = "sakura";
    /** 內建（會複製到 data/themes 的）主題 id。 */
    private static final List<String> BUILTIN_IDS = List.of("night", "sakura", "contrast");
    private static final List<String> IMAGE_EXTENSIONS = List.of("png", "jpg", "jpeg", "webp");
    private static final String MANIFEST_JSON = "theme.json";
    private static final String MANIFEST_PROPS = "pack.properties";
    private static final String MAIN_CSS = "theme.css";
    private static final String THEME_FILE = "theme.txt";
    private static final long MAX_IMAGE_BYTES = 16L * 1024 * 1024;

    /**
     * 一個主題包。
     *
     * @param id            資料夾名（啟動器內建主題是 {@code default}）
     * @param styles        依序套用的 CSS（已通過安全檢查）
     * @param backgroundFit {@code cover} / {@code contain} / {@code repeat} / {@code center}
     * @param backgroundDim 0–1，背景圖上再蓋一層黑色的比例（深色主題常用 0.2–0.5）
     * @param logo          主題自己的 logo 圖（可選；沒有就用啟動器內建的櫻花方塊）
     * @param wordmark      頂欄是否顯示「Brisk」大字（小字「Brisk Launcher」一律保留）
     */
    public record Theme(String id, String name, String author, String version, String description,
                        String homepage, String base, String accent, List<Path> styles,
                        Path background, String backgroundFit, double backgroundDim,
                        Path logo, boolean wordmark, Path preview, boolean custom,
                        List<String> warnings) {

        /** 深色基調（給設定頁圖示 / 外掛參考）。 */
        public boolean dark() {
            return "dark".equalsIgnoreCase(base);
        }

        /** 下拉選單上顯示的名字：有作者就附在後面，避免同名主題分不出來。 */
        public String displayName() {
            return author == null || author.isBlank() ? name : name + "（" + author + "）";
        }
    }

    /** 快取：簽名 + 清單 + 目前主題 + CSS URI。 */
    private record Cache(String signature, List<Theme> themes, Theme current, List<String> cssUris) {
    }

    private static volatile Cache cache;

    private ThemePacks() {
    }

    /** 主題包目錄；可用 {@code -Dmcl.themes.dir=...} 覆寫（測試 / 進階使用者）。 */
    public static Path dir() {
        String override = System.getProperty("mcl.themes.dir");
        if (override != null && !override.isBlank()) {
            return Path.of(override.trim());
        }
        return AppPaths.themesDir();
    }

    // ---------- 查詢 ----------

    /** 全部主題（內建 default 在最前）。 */
    public static List<Theme> all() {
        return cache().themes();
    }

    /** 目前選中的主題（找不到就回 default）。 */
    public static Theme current() {
        return cache().current();
    }

    public static String currentId() {
        return current().id();
    }

    /**
     * 目前選中的主題 id（只讀 {@code theme.txt}，不做完整掃描）。
     *
     * <p>啟動階段（原生 splash、簽名比對）只能用它：{@link #current()} 會初始化 Jackson / 語言包，
     * 在啟動路徑上要 ~0.6 秒。
     */
    public static String selectedId() {
        String id = readSelection();
        return id == null || id.isBlank() ? DEFAULT_ID : id;
    }

    /**
     * 啟動動畫專用：只找「目前主題的 CSS」，不解析 {@code theme.json}、不初始化 Jackson / I18n。
     *
     * <p>啟動動畫要「雙擊後立刻出現」，所以不能為了套主題去跑完整掃描 —— 實測
     * {@link #all()} 第一次初始化要 ~0.6 秒（Jackson 的 ObjectMapper 冷啟動 + 語言包），
     * 那會把 splash 延後到 2.8 秒才出現，反而更糟。
     *
     * <p>這裡只做三件便宜的事：讀 {@code theme.txt} → 列出 {@code *.css} → 過安全檢查。
     * metadata / 背景圖 / 縮圖留給主介面（那裡本來就會跑完整掃描）。
     */
    public static List<String> startupCssUris() {
        List<String> result = new ArrayList<>();
        // 第一次启动时 data/themes 还是空的：先把内建主题补上，否则启动动画拿不到主题 CSS，
        // 会退回基础青绿色（用户看到的「薄荷绿」就是这个）。
        ensureBuiltinFiles();
        try {
            String id = readSelection();
            if (id == null || id.isBlank() || DEFAULT_ID.equals(id)) {
                return result;
            }
            Path themeDir = safeResolve(dir(), id);
            if (themeDir == null || !Files.isDirectory(themeDir)) {
                return result;
            }
            List<Path> css = new ArrayList<>();
            try (var stream = Files.list(themeDir)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                                .endsWith(".css"))
                        .forEach(css::add);
            }
            css.sort((left, right) -> {
                String a = left.getFileName().toString();
                String b = right.getFileName().toString();
                if (MAIN_CSS.equals(a)) {
                    return -1;
                }
                if (MAIN_CSS.equals(b)) {
                    return 1;
                }
                return a.compareToIgnoreCase(b);
            });
            for (Path path : css) {
                if (ThemeCssGuard.allowed(path)) {
                    result.add(path.toUri().toString());
                }
            }
        } catch (Throwable ignored) {
            // 主題壞掉不該擋住啟動動畫
        }
        return result;
    }

    /** 目前主題要套用的 CSS URI（已通過安全檢查；default 為空）。 */
    public static List<String> cssUris() {
        return cache().cssUris();
    }

    /** 目前主題的背景圖（沒有就 null）。 */
    public static Path background() {
        return current().background();
    }

    /** 切換主題並記住選擇（{@code theme.txt} 在啟動器資料根目錄）。 */
    public static void setCurrent(String id) {
        String value = id == null || id.isBlank() ? DEFAULT_ID : id;
        try {
            Files.createDirectories(AppPaths.launcherRoot());
            Files.writeString(AppPaths.launcherRoot().resolve(THEME_FILE), value,
                    StandardCharsets.UTF_8);
        } catch (Exception error) {
            SafeLog.warn("保存主题选择失败：" + error.getClass().getSimpleName());
        }
        invalidate();
        // 主題換了就通知介面（頂欄 logo、視窗圖示要跟著換）
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (Throwable error) {
                SafeLog.warn("主题监听器异常：" + error.getClass().getSimpleName());
            }
        }
    }

    private static final java.util.List<Runnable> listeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * 註冊「主題變了」的監聽（回傳的 Runnable 用來取消）。
     *
     * <p>目前使用者：頂欄的 logo 圖、視窗圖示。切主題不會重建整個介面，
     * 所以需要一個輕量的通知點。
     */
    public static Runnable addThemeListener(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
        return () -> listeners.remove(listener);
    }

    /** 丟掉快取（換主題、資料夾變動、設定頁按「重新掃描」時呼叫）。 */
    public static void invalidate() {
        cache = null;
    }

    // ---------- 初始化 ----------

    /** 建立主題目錄、寫入說明，並把內建主題複製出來（只在資料夾不存在時複製）。 */
    public static void ensure() {
        try {
            Path root = dir();
            Files.createDirectories(root);
            Path readme = root.resolve("README.txt");
            if (!Files.isRegularFile(readme)) {
                Files.writeString(readme, """
                        主题包目录 / Theme packs
                        ========================

                        每个主题包一个文件夹，文件夹名就是主题标识，例如 my_theme。
                        文件夹里放：
                          theme.css        必需（会叠在启动器默认样式之上，写你要覆盖的规则即可）
                          theme.json       可选：name / author / version / description / styles /
                                           background / backgroundFit / backgroundDim / accent / base / preview
                          pack.properties  可选（旧格式）：name=显示名称 / author=作者 / version=版本
                          background.png   可选：背景图（png / jpg / jpeg / webp）
                          preview.png      可选：设置页的缩略图
                        多个 CSS 用 theme.json 的 "styles": ["theme.css", "extra.css"] 指定顺序；
                        不写 styles 时按档名排序，theme.css 一定排第一。

                        安全限制：主题 CSS 不能引用 http/https/file/jar 资源，也不能用 @font-face，
                        违反者在设置页会显示警告并且不会被加载。

                        加好后到「设置 → 外观」选择即可，切换即时生效，不用重启。
                        详细说明见 THEME_DEV.md。
                        """, StandardCharsets.UTF_8);
            }
            copyBuiltinThemes(root);
        } catch (Exception error) {
            SafeLog.warn("建立主题包目录失败：" + error.getClass().getSimpleName());
        }
    }

    /**
     * 啟動動畫專用：只把「不存在」的內建主題檔案補上，不做版本比較、不碰 Jackson / I18n。
     *
     * <p>為什麼需要：第一次啟動時 {@code data/themes/} 還是空的，而 {@link #ensure()}
     * 要等 MclContext 初始化才跑 —— 在那之前畫的啟動動畫拿不到主題 CSS，就會用基礎青綠色。
     */
    public static void ensureBuiltinFiles() {
        try {
            Path root = dir();
            Files.createDirectories(root);
            for (String id : BUILTIN_IDS) {
                Path target = root.resolve(id);
                Files.createDirectories(target);
                Path css = target.resolve(MAIN_CSS);
                if (!Files.isRegularFile(css)) {
                    copyResource("/themes/" + id + "/theme.css", css);
                }
                Path json = target.resolve(MANIFEST_JSON);
                if (!Files.isRegularFile(json)) {
                    copyResource("/themes/" + id + "/theme.json", json);
                }
                for (String extension : IMAGE_EXTENSIONS) {
                    String resource = "/themes/" + id + "/logo." + extension;
                    Path logo = target.resolve("logo." + extension);
                    if (!Files.isRegularFile(logo) && ThemePacks.class.getResource(resource) != null) {
                        copyResource(resource, logo);
                        break;
                    }
                }
            }
        } catch (Exception error) {
            SafeLog.warn("补充内建主题文件失败：" + error.getClass().getSimpleName());
        }
    }

    /** 把 jar 内的内建主题写到 {@code data/themes/}（資料夾已存在就跳過，不覆盖玩家改动）。 */
    private static void copyBuiltinThemes(Path root) {
        for (String id : BUILTIN_IDS) {
            Path target = root.resolve(id);
            String packaged = packagedVersion(id);
            if (Files.isDirectory(target) && !bundledOutdated(target, packaged)) {
                continue;
            }
            try {
                Files.createDirectories(target);
                copyResource("/themes/" + id + "/theme.json", target.resolve(MANIFEST_JSON));
                copyResource("/themes/" + id + "/theme.css", target.resolve(MAIN_CSS));
                copyBundledLogo(id, target);
            } catch (Exception error) {
                SafeLog.warn("写入内建主题失败：" + id);
            }
        }
    }

    /** 內建主題若自帶 logo，一起複製出去（沒有就跳過，介面會回退到內建櫻花方塊）。 */
    private static void copyBundledLogo(String id, Path target) {
        for (String extension : IMAGE_EXTENSIONS) {
            String resource = "/themes/" + id + "/logo." + extension;
            if (ThemePacks.class.getResource(resource) == null) {
                continue;
            }
            try {
                copyResource(resource, target.resolve("logo." + extension));
            } catch (Exception error) {
                SafeLog.warn("写入内建主题 logo 失败：" + id);
            }
            return;
        }
    }

    /** 读 jar 内建主题的版本号（读不到就回传空字串）。 */
    private static String packagedVersion(String id) {
        try (InputStream input = ThemePacks.class.getResourceAsStream(
                "/themes/" + id + "/" + MANIFEST_JSON)) {
            if (input == null) {
                return "";
            }
            return Http.mapper().readTree(input.readAllBytes()).path("version").asText("");
        } catch (Exception error) {
            return "";
        }
    }

    /**
     * 内建主题要不要刷新。
     *
     * <p>判定「这是启动器自己写的主题」：{@code theme.json} 里 {@code "bundled": true}，
     * 或作者是 {@code Brisk Launcher}（旧版内建主题没有这个旗标）。命中且版本号与内建版本不同
     * → 覆盖，这样升级启动器后 night / sakura / contrast 会跟着换新。
     *
     * <p>玩家想自己改，就把整个资料夹复制成新名字，并把 {@code bundled} 拿掉 / 改掉作者，
     * 启动器就不会再动它。
     */
    private static boolean bundledOutdated(Path dir, String packaged) {
        Path json = dir.resolve(MANIFEST_JSON);
        if (!Files.isRegularFile(json)) {
            return true;
        }
        try {
            var node = Http.mapper().readTree(Files.readString(json, StandardCharsets.UTF_8));
            boolean bundled = node.path("bundled").asBoolean(false)
                    || "Brisk Launcher".equalsIgnoreCase(node.path("author").asText(""));
            if (!bundled) {
                return false;
            }
            return !packaged.equals(node.path("version").asText(""));
        } catch (Exception error) {
            return false;
        }
    }

    private static void copyResource(String resource, Path target) throws Exception {
        try (InputStream input = ThemePacks.class.getResourceAsStream(resource)) {
            if (input == null) {
                SafeLog.warn("内建主题资源不存在：" + resource);
                return;
            }
            Files.write(target, input.readAllBytes());
        }
    }

    // ---------- 快取 ----------

    private static Cache cache() {
        String signature = signature();
        Cache current = cache;
        if (current != null && current.signature().equals(signature)) {
            return current;
        }
        synchronized (ThemePacks.class) {
            current = cache;
            if (current != null && current.signature().equals(signature)) {
                return current;
            }
            List<Theme> themes = scan();
            String id = readSelection();
            Theme selected = themes.stream()
                    .filter(theme -> theme.id().equals(id))
                    .findFirst()
                    .orElse(themes.get(0));
            List<String> uris = new ArrayList<>();
            for (Path css : selected.styles()) {
                uris.add(css.toUri().toString());
            }
            Cache loaded = new Cache(signature, List.copyOf(themes), selected, List.copyOf(uris));
            cache = loaded;
            return loaded;
        }
    }

    /**
     * 快取簽名：資料夾 + 每個主題資料夾 + 其中每個檔案（名稱 / 大小 / 修改時間）。
     *
     * <p>主題包都很小（幾個檔案），這比「每次重讀 CSS + 重跑安全檢查」便宜得多。
     */
    private static String signature() {
        Path root = dir();
        StringBuilder builder = new StringBuilder();
        builder.append(root.toString()).append('|').append(root.toFile().lastModified()).append('|');
        if (!Files.isDirectory(root)) {
            return builder.append("missing").toString();
        }
        try (var stream = Files.list(root)) {
            List<Path> dirs = stream.filter(Files::isDirectory).sorted().toList();
            for (Path themeDir : dirs) {
                builder.append(themeDir.getFileName()).append('=').append(themeDir.toFile().lastModified());
                try (var files = Files.list(themeDir)) {
                    for (Path file : files.sorted().toList()) {
                        builder.append(',').append(file.getFileName())
                                .append(':').append(file.toFile().length())
                                .append('@').append(file.toFile().lastModified());
                    }
                } catch (Exception ignored) {
                    // 讀不到就當沒變
                }
                builder.append(';');
            }
        } catch (Exception error) {
            builder.append("error");
        }
        return builder.toString();
    }

    private static String readSelection() {
        try {
            Path file = AppPaths.launcherRoot().resolve(THEME_FILE);
            if (Files.isRegularFile(file)) {
                String id = Packs.stripBom(Files.readString(file, StandardCharsets.UTF_8)).trim();
                if (!id.isEmpty()) {
                    return id;
                }
            }
        } catch (Exception ignored) {
            // 读不到就用预设
        }
        // 第一次啟動（沒有 theme.txt）：櫻花粉當品牌主色，直接進 sakura
        return INITIAL_ID;
    }

    // ---------- 掃描 ----------

    private static List<Theme> scan() {
        List<Theme> themes = new ArrayList<>();
        themes.add(new Theme(DEFAULT_ID, I18n.t("settings.theme_default"), "", "", "", "",
                "light", "#0E9C8D", List.of(), null, "cover", 0, null, true, null, false,
                List.of()));
        Path root = dir();
        if (!Files.isDirectory(root)) {
            return themes;
        }
        List<Path> dirs;
        try (var stream = Files.list(root)) {
            dirs = stream.filter(Files::isDirectory).sorted().toList();
        } catch (Exception error) {
            SafeLog.warn("扫描主题目录失败：" + error.getClass().getSimpleName());
            return themes;
        }
        for (Path themeDir : dirs) {
            Theme theme = read(themeDir);
            if (theme != null) {
                themes.add(theme);
            }
        }
        return themes;
    }

    /** 讀一個主題資料夾；沒有可用 CSS 就回 null（不列進清單）。 */
    private static Theme read(Path themeDir) {
        String id = themeDir.getFileName().toString();
        List<String> warnings = new ArrayList<>();
        JsonNode manifest = null;
        Properties props = new Properties();
        String name = id;
        String author = "";
        String version = "";
        String description = "";
        String homepage = "";
        String base = "light";
        String accent = "";
        List<String> declaredStyles = null;
        String declaredBackground = null;
        String declaredFit = null;
        double declaredDim = Double.NaN;
        String declaredPreview = null;
        String declaredLogo = null;
        // 預設顯示大字（內建主題也都是 true）；主題作者可以關掉，只留小字
        boolean wordmark = true;

        Path json = themeDir.resolve(MANIFEST_JSON);
        if (Files.isRegularFile(json)) {
            try {
                manifest = Http.mapper().readTree(Files.readString(json, StandardCharsets.UTF_8));
                name = localized(manifest, "name", name);
                author = manifest.path("author").asText("");
                version = manifest.path("version").asText("");
                description = localized(manifest, "description", "");
                homepage = manifest.path("homepage").asText("");
                base = manifest.path("base").asText("light");
                accent = manifest.path("accent").asText("");
                if (manifest.path("styles").isArray()) {
                    declaredStyles = new ArrayList<>();
                    for (JsonNode item : manifest.path("styles")) {
                        String file = item.asText("").trim();
                        if (!file.isBlank()) {
                            declaredStyles.add(file);
                        }
                    }
                }
                declaredBackground = manifest.path("background").asText(null);
                declaredFit = manifest.path("backgroundFit").asText(null);
                if (manifest.path("backgroundDim").isNumber()) {
                    declaredDim = manifest.path("backgroundDim").asDouble();
                }
                declaredPreview = manifest.path("preview").asText(null);
                declaredLogo = manifest.path("logo").asText(null);
                wordmark = manifest.path("wordmark").asBoolean(true);
            } catch (Exception error) {
                warnings.add("theme.json 解析失败：" + error.getClass().getSimpleName());
            }
        }
        Path legacy = themeDir.resolve(MANIFEST_PROPS);
        if (Files.isRegularFile(legacy)) {
            try {
                props.load(new StringReader(Packs.stripBom(
                        Files.readString(legacy, StandardCharsets.UTF_8))));
                if (manifest == null) {
                    String declared = props.getProperty("name");
                    if (declared != null && !declared.isBlank()) {
                        name = declared.trim();
                    }
                    author = props.getProperty("author", "").trim();
                    version = props.getProperty("version", "").trim();
                }
            } catch (Exception ignored) {
                // 舊格式壞掉不影響主題本身
            }
        }

        List<Path> styles = resolveStyles(themeDir, declaredStyles, warnings);
        if (styles.isEmpty()) {
            // 有 CSS 或有 manifest 但全被安全檢查擋掉 → 留在清單裡（設定頁才看得到原因）；
            // 連 CSS 與 manifest 都沒有 → 只是個不相關的資料夾，不算主題
            boolean looksLikeTheme = hasAnyCss(themeDir)
                    || Files.isRegularFile(themeDir.resolve(MANIFEST_JSON))
                    || Files.isRegularFile(themeDir.resolve(MANIFEST_PROPS));
            if (!looksLikeTheme) {
                return null;
            }
            warnings.add("没有可用的 CSS（都被安全检查拒绝或档案不存在）");
        }
        if (warnings.size() > 3) {
            warnings = new ArrayList<>(warnings.subList(0, 3));
            warnings.add("……还有更多警告");
        }
        Path background = resolveBackground(themeDir, declaredBackground, warnings);
        Path preview = resolvePreview(themeDir, declaredPreview);
        Path logo = resolveLogo(themeDir, declaredLogo);
        String fit = declaredFit == null || declaredFit.isBlank() ? "cover" : declaredFit.trim()
                .toLowerCase(Locale.ROOT);
        if (!List.of("cover", "contain", "repeat", "center").contains(fit)) {
            warnings.add("backgroundFit 不合法（" + fit + "），改用 cover");
            fit = "cover";
        }
        double dim = Double.isNaN(declaredDim) ? ("dark".equalsIgnoreCase(base) ? 0.25 : 0.0)
                : Math.max(0, Math.min(1, declaredDim));
        return new Theme(id, name, author, version, description, homepage, base, accent,
                List.copyOf(styles), background, fit, dim, logo, wordmark, preview, true,
                List.copyOf(warnings));
    }

    /**
     * 主題自帶的 logo：{@code theme.json} 的 {@code "logo"} 優先，否則找 {@code logo.png/jpg/webp}。
     *
     * <p>找不到就回 null，介面會回退到啟動器內建的櫻花方塊（不顯示破圖）。
     */
    private static Path resolveLogo(Path themeDir, String declared) {
        if (declared != null && !declared.isBlank()) {
            Path path = safeResolve(themeDir, declared);
            return path != null && Files.isRegularFile(path) ? path : null;
        }
        for (String extension : IMAGE_EXTENSIONS) {
            Path path = themeDir.resolve("logo." + extension);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        return null;
    }

    /** 資料夾裡有沒有任何 .css（用來區分「壞掉的主題」與「不相關的資料夾」）。 */
    private static boolean hasAnyCss(Path themeDir) {
        try (var stream = Files.list(themeDir)) {
            return stream.anyMatch(path -> Files.isRegularFile(path)
                    && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".css"));
        } catch (Exception error) {
            return false;
        }
    }

    /** CSS 清單：宣告的 styles 優先，否則 {@code theme.css} 第一 + 其餘依檔名。 */
    private static List<Path> resolveStyles(Path themeDir, List<String> declared, List<String> warnings) {
        List<Path> result = new ArrayList<>();
        if (declared != null) {
            for (String file : declared) {
                Path path = safeResolve(themeDir, file);
                if (path == null) {
                    warnings.add("styles 里的路径不合法：" + file);
                    continue;
                }
                if (!Files.isRegularFile(path)) {
                    warnings.add("styles 里的档案不存在：" + file);
                    continue;
                }
                addCss(path, result, warnings);
            }
            return result;
        }
        List<Path> css = new ArrayList<>();
        try (var stream = Files.list(themeDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".css"))
                    .forEach(css::add);
        } catch (Exception error) {
            return result;
        }
        css.sort((left, right) -> {
            String a = left.getFileName().toString();
            String b = right.getFileName().toString();
            if (MAIN_CSS.equals(a)) {
                return -1;
            }
            if (MAIN_CSS.equals(b)) {
                return 1;
            }
            return a.compareToIgnoreCase(b);
        });
        for (Path path : css) {
            addCss(path, result, warnings);
        }
        return result;
    }

    private static void addCss(Path path, List<Path> target, List<String> warnings) {
        if (!ThemeCssGuard.allowed(path)) {
            warnings.add("CSS 被安全检查拒绝：" + path.getFileName());
            return;
        }
        target.add(path);
    }

    /** 不允許跳出主題資料夾。 */
    private static Path safeResolve(Path themeDir, String relative) {
        try {
            Path resolved = themeDir.resolve(relative).normalize();
            return resolved.startsWith(themeDir) ? resolved : null;
        } catch (Exception error) {
            return null;
        }
    }

    private static Path resolveBackground(Path themeDir, String declared, List<String> warnings) {
        if (declared != null && !declared.isBlank()) {
            Path path = safeResolve(themeDir, declared);
            if (path == null || !Files.isRegularFile(path)) {
                warnings.add("background 指定了不存在的档案：" + declared);
                return null;
            }
            return tooLarge(path, warnings) ? null : path;
        }
        for (String extension : IMAGE_EXTENSIONS) {
            Path path = themeDir.resolve("background." + extension);
            if (Files.isRegularFile(path)) {
                return tooLarge(path, warnings) ? null : path;
            }
        }
        return null;
    }

    private static boolean tooLarge(Path path, List<String> warnings) {
        try {
            if (Files.size(path) > MAX_IMAGE_BYTES) {
                warnings.add("背景图过大（上限 16 MB）：" + path.getFileName());
                return true;
            }
        } catch (Exception ignored) {
            // 读不到大小就交给图片解码器去判断
        }
        return false;
    }

    private static Path resolvePreview(Path themeDir, String declared) {
        if (declared != null && !declared.isBlank()) {
            Path path = safeResolve(themeDir, declared);
            return path != null && Files.isRegularFile(path) ? path : null;
        }
        for (String extension : IMAGE_EXTENSIONS) {
            Path path = themeDir.resolve("preview." + extension);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        return null;
    }

    /**
     * 讀 theme.json 裡的字串欄位；也接受「多語言物件」：
     * {@code "name": {"zh_CN": "夜色", "zh_TW": "夜色", "en_US": "Night"}}。
     */
    private static String localized(JsonNode manifest, String field, String fallback) {
        JsonNode node = manifest.path(field);
        if (node.isTextual()) {
            String text = node.asText("");
            return text.isBlank() ? fallback : text;
        }
        if (node.isObject()) {
            String tag = I18n.currentLocaleTag();
            LinkedHashSet<String> candidates = new LinkedHashSet<>();
            if (tag != null && !tag.isBlank()) {
                candidates.add(tag);
                int cut = tag.indexOf('_');
                if (cut > 0) {
                    candidates.add(tag.substring(0, cut));
                }
            }
            candidates.add("zh_CN");
            candidates.add("en_US");
            for (String candidate : candidates) {
                String value = node.path(candidate).asText("");
                if (!value.isBlank()) {
                    return value;
                }
            }
            var iterator = node.fields();
            while (iterator.hasNext()) {
                String value = iterator.next().getValue().asText("");
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return fallback;
    }
}
