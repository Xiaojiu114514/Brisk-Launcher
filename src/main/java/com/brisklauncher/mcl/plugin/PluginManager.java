package com.brisklauncher.mcl.plugin;

import com.brisklauncher.mcl.AppPaths;
import com.brisklauncher.mcl.MclContext;
import com.brisklauncher.mcl.net.Http;
import com.brisklauncher.mcl.plugin.builtin.PlayerSkinPlugin;
import com.brisklauncher.mcl.plugin.builtin.RgbTextPlugin;
import com.brisklauncher.mcl.plugin.builtin.SchematicMaterialsPlugin;
import com.brisklauncher.mcl.plugin.builtin.ServerModCheckPlugin;
import com.brisklauncher.mcl.plugin.builtin.ServerPingPlugin;
import com.brisklauncher.mcl.plugin.builtin.SparkReportPlugin;
import com.brisklauncher.mcl.security.SafeLog;
import javafx.stage.Window;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 插件仓库：内建插件 + {@code <data>/plugins/*.jar} 外部插件。
 *
 * <p>外部插件格式（最小公约数）：
 * <pre>
 * your-plugin.jar
 * ├── plugin.json      {"id":"x","name":"显示名","class":"com.example.MyPlugin","description":"..."}
 * └── com/example/MyPlugin.class   ← 实现 com.brisklauncher.mcl.plugin.LauncherPlugin
 * </pre>
 *
 * <p>不用 {@code ServiceLoader}：模组化之后 {@code uses} 宣告会把启动器和插件版本绑死，
 * 而 {@code plugin.json} 指定主类更直观，也方便玩家自己改。
 */
public final class PluginManager {

    /** 一個已註冊的插件（載入失敗時 error 非空，UI 會顯示原因）。 */
    public record Entry(LauncherPlugin plugin, String id, String name, String description,
                        String icon, boolean builtin, Path jar, String error) {

        public boolean failed() {
            return error != null && !error.isBlank();
        }
    }

    private static final String MANIFEST = "plugin.json";

    private final MclContext context;
    private final List<Entry> builtins = new ArrayList<>();
    private final List<Entry> external = new ArrayList<>();
    private final Map<String, URLClassLoader> loaders = new HashMap<>();
    private final Set<String> disabled = new LinkedHashSet<>();
    private final PluginHost host;
    private boolean loaded;

    public PluginManager(MclContext context) {
        this.context = context;
        this.host = new PluginHost(context);
    }

    public PluginContext host() {
        return host;
    }

    public Path dir() {
        return AppPaths.dataDir().resolve("plugins");
    }

    /** 首次使用建立目錄與格式說明。 */
    public void ensure() {
        try {
            Files.createDirectories(dir());
            Path readme = dir().resolve("README.txt");
            if (!Files.isRegularFile(readme)) {
                Files.writeString(readme, """
                        Brisk Launcher 插件目錄
                        ============================
                        把插件 jar 放到本目錄，重開「工具」頁面即可看到。

                        jar 內部結構：
                          plugin.json   {"id":"my-plugin","name":"我的工具","class":"com.example.MyPlugin","description":"说明"}
                          其余 class    實現 com.brisklauncher.mcl.plugin.LauncherPlugin

                        插件只能拿到 PluginContext（資料目錄、HTTP GET、打開資料夾…），
                        不依賴啟動器內部類，所以啟動器升級後插件通常不用重編譯。
                        """, StandardCharsets.UTF_8);
            }
            loadDisabled();
        } catch (Exception error) {
            SafeLog.warn("建立插件目录失败：" + error.getClass().getSimpleName());
        }
    }

    /** 註冊內建插件並掃描外部 jar（只做一次；UI 刷新可呼叫 {@link #reload()}）。 */
    public synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        ensure();
        registerBuiltin(new RgbTextPlugin());
        registerBuiltin(new ServerPingPlugin());
        registerBuiltin(new PlayerSkinPlugin());
        registerBuiltin(new SparkReportPlugin());
        registerBuiltin(new ServerModCheckPlugin());
        registerBuiltin(new SchematicMaterialsPlugin());
        loadExternal();
    }

    public synchronized void reload() {
        loaded = false;
        builtins.clear();
        external.clear();
        closeLoaders();
        load();
    }

    private void registerBuiltin(LauncherPlugin plugin) {
        builtins.add(new Entry(plugin, plugin.id(), plugin.name(), plugin.description(),
                plugin.icon(), true, null, null));
    }

    /** 全部插件（內建在前，外部在後）。 */
    public synchronized List<Entry> entries() {
        load();
        List<Entry> result = new ArrayList<>(builtins);
        result.addAll(external);
        return result;
    }

    public synchronized Entry find(String id) {
        for (Entry entry : entries()) {
            if (entry.id().equals(id)) {
                return entry;
            }
        }
        return null;
    }

    public synchronized boolean isEnabled(String id) {
        return !disabled.contains(id);
    }

    public synchronized void setEnabled(String id, boolean enabled) {
        if (enabled) {
            disabled.remove(id);
        } else {
            disabled.add(id);
        }
        saveDisabled();
    }

    // ---------- 外部插件 ----------

    private void loadExternal() {
        Path dir = dir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        List<Path> jars = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .forEach(jars::add);
        } catch (Exception error) {
            SafeLog.warn("扫描插件目录失败：" + error.getClass().getSimpleName());
            return;
        }
        for (Path jar : jars) {
            Entry entry = loadJar(jar);
            if (entry != null) {
                external.add(entry);
            }
        }
    }

    private Entry loadJar(Path jar) {
        String fallbackName = jar.getFileName().toString().replaceAll("(?i)\\.jar$", "");
        try {
            String manifest = readManifest(jar);
            if (manifest == null) {
                return failedEntry(jar, fallbackName, "缺少 plugin.json");
            }
            var node = Http.mapper().readTree(manifest);
            String id = node.path("id").asText("");
            String name = node.path("name").asText(fallbackName);
            String className = node.path("class").asText("");
            String description = node.path("description").asText("");
            if (id.isBlank()) {
                id = fallbackName;
            }
            if (className.isBlank()) {
                return failedEntry(jar, name, "plugin.json 缺少 class 栏位");
            }
            // 第三方插件由使用者自行安装、自行承担风险；本启动器不对其行为负责。
            // 我们仍做两件属于「自己责任」的事：
            //   1) disabled.txt 的插件不加载、不执行（按 jar 档名或 SHA-256 判断，避免自报 id 绕过）；
            //   2) 首次载入外部插件时弹一次风险说明，确认后写入 acks.json，之后不再打扰。
            if (isDisabled(jar, id)) {
                return failedEntry(jar, name, "已在 disabled.txt 中禁用");
            }
            if (!com.brisklauncher.mcl.security.Acks.has("plugins") && !askPluginRisk(name)) {
                return failedEntry(jar, name, "使用者取消载入第三方插件");
            }
            // 安全审计 H-02（残留项）：acks.json 现在在私密目录，预写绕过门禁的根因已解决；
            // 但「确认过的插件被换成另一个 jar」仍要重新确认 —— 用 SHA-256 记录首次载入的摘要。
            if (!confirmPluginDigest(jar, id, name)) {
                return failedEntry(jar, name, "插件内容已变更，使用者未确认");
            }
            // 第三方插件属于「使用者自行安装、自行承担风险」：照常载入，只提示一次。
            // 启动器只负责自己代码的漏洞；插件的恶意行为不在我们的控制范围。
            if (System.getProperty("mcl.plugins.quiet") == null) {
                SafeLog.warn("载入第三方插件（风险自负）：" + name);
            }
            URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()},
                    LauncherPlugin.class.getClassLoader());
            Class<?> type = Class.forName(className, true, loader);
            if (!LauncherPlugin.class.isAssignableFrom(type)) {
                loader.close();
                return failedEntry(jar, name, "主类没有实现 LauncherPlugin");
            }
            LauncherPlugin plugin = (LauncherPlugin) type.getDeclaredConstructor().newInstance();
            loaders.put(id, loader);
            SafeLog.info("已载入外部插件：" + name);
            return new Entry(plugin, id, blank(name, plugin.name()), blank(description, plugin.description()),
                    plugin.icon(), false, jar, null);
        } catch (Throwable error) {
            return failedEntry(jar, fallbackName, error.getClass().getSimpleName() + ": "
                    + (error.getMessage() == null ? "" : error.getMessage()));
        }
    }

    private static Entry failedEntry(Path jar, String name, String error) {
        return new Entry(null, jar.getFileName().toString(), name, "", "⚠", false, jar, error);
    }

    private static String blank(String value, String fallback) {
        return value == null || value.isBlank() ? (fallback == null ? "" : fallback) : value;
    }

    private String readManifest(Path jar) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            var entry = zip.getEntry(MANIFEST);
            if (entry == null) {
                return null;
            }
            try (InputStream input = zip.getInputStream(entry)) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception error) {
            return null;
        }
    }

    /** 安裝一個外部插件 jar（複製到插件目錄並重新掃描）。 */
    public synchronized void install(Path jar) throws Exception {
        if (jar == null || !Files.isRegularFile(jar)) {
            throw new IllegalArgumentException("插件档案不存在");
        }
        Files.createDirectories(dir());
        Path target = dir().resolve(jar.getFileName().toString());
        Files.copy(jar, target, StandardCopyOption.REPLACE_EXISTING);
        reload();
    }

    /** 移除外部插件（送回收站，誤刪可復原）。 */
    public synchronized void remove(Entry entry) {
        if (entry == null || entry.builtin() || entry.jar() == null) {
            return;
        }
        URLClassLoader loader = loaders.remove(entry.id());
        if (loader != null) {
            try {
                loader.close();
            } catch (Exception ignored) {
                // 关不掉也不影响（行程退出时会释放）
            }
        }
        com.brisklauncher.mcl.util.RecycleBin.delete(entry.jar());
        reload();
    }

    private void closeLoaders() {
        for (URLClassLoader loader : loaders.values()) {
            try {
                loader.close();
            } catch (Exception ignored) {
                // 尽力而为
            }
        }
        loaders.clear();
    }

    // ---------- 启用状态 ----------

    /** 首次載入外部插件時的一次性風險說明（阻塞式；取消則不載入）。 */
    private boolean askPluginRisk(String name) {
        final boolean[] ok = {false};
        Runnable show = () -> {
            try {
                ok[0] = com.brisklauncher.mcl.ui.MCLDialog.confirmBlocking(null,
                        com.brisklauncher.mcl.i18n.I18n.t("plugin.risk.title"),
                        com.brisklauncher.mcl.i18n.I18n.t("plugin.risk.body", name));
            } catch (Exception error) {
                ok[0] = false;
            }
        };
        if (javafx.application.Platform.isFxApplicationThread()) {
            show.run();
        } else {
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            javafx.application.Platform.runLater(() -> {
                try {
                    show.run();
                } finally {
                    latch.countDown();
                }
            });
            try {
                latch.await(60, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        if (ok[0]) {
            com.brisklauncher.mcl.security.Acks.record("plugins");
        }
        return ok[0];
    }

    /**
     * 插件摘要确认（安全审计 H-02 残留项）。
     *
     * <p>{@code plugin-trust.json} 记著每个插件**首次载入**时的 SHA-256：
     * 摘要没变 → 直接放行（一次确认长期有效的语义才成立）；
     * 摘要变了 → 当成「有人换掉了你确认过的插件」，重新问一次。
     * 档案放在私密目录，与 {@code acks.json} 同级。
     */
    private boolean confirmPluginDigest(Path jar, String id, String name) {
        String sha256;
        try {
            sha256 = com.brisklauncher.mcl.download.HashUtils.hash(jar, "SHA-256");
        } catch (Exception error) {
            // 算不出摘要就不要挡（读不了 jar 的插件本来也载不进来）
            return true;
        }
        Path trustFile = AppPaths.launcherRoot().resolve("plugin-trust.json");
        com.fasterxml.jackson.databind.node.ObjectNode trust;
        try {
            trust = Files.isRegularFile(trustFile)
                    ? (com.fasterxml.jackson.databind.node.ObjectNode) Http.mapper().readTree(trustFile.toFile())
                    : Http.mapper().createObjectNode();
        } catch (Exception error) {
            trust = Http.mapper().createObjectNode();
        }
        String known = trust.path(id).asText("");
        if (sha256.equals(known)) {
            return true;
        }
        boolean changed = !known.isBlank();
        if (changed) {
            SafeLog.warn("[plugin] 插件摘要变了（可能被替换）：" + name);
            if (!askPluginRisk(name + "（内容已变更）")) {
                return false;
            }
        }
        trust.put(id, sha256);
        try {
            Files.write(trustFile, Http.mapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(trust));
            com.brisklauncher.mcl.security.FilePerms.ensurePrivateFile(trustFile);
        } catch (Exception error) {
            SafeLog.warn("[plugin] 写入 plugin-trust.json 失败：" + error.getClass().getSimpleName());
        }
        return true;
    }

    /** 禁用判斷：jar 檔名 或 SHA-256（避免插件自報 id 繞過）。 */
    private boolean isDisabled(Path jar, String id) {
        String fileName = jar.getFileName().toString();
        if (disabled.contains(id) || disabled.contains(fileName)) {
            return true;
        }
        try {
            String sha256 = com.brisklauncher.mcl.download.HashUtils.hash(jar, "SHA-256");
            return disabled.contains(sha256);
        } catch (Exception ignored) {
            return false;
        }
    }

    private Path disabledFile() {
        return dir().resolve("disabled.txt");
    }

    private void loadDisabled() {
        disabled.clear();
        Path file = disabledFile();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    disabled.add(line.trim());
                }
            }
        } catch (Exception ignored) {
            // 读不到就全部当启用
        }
    }

    private void saveDisabled() {
        try {
            Files.createDirectories(dir());
            Files.write(disabledFile(), disabled, StandardCharsets.UTF_8);
        } catch (Exception error) {
            SafeLog.warn("保存插件启用状态失败");
        }
    }

    // ---------- 给插件用的上下文 ----------

    /** {@link PluginContext} 的唯一實現：把啟動器能力收窄給插件。 */
    private static final class PluginHost implements PluginContext {

        private static final Duration TIMEOUT = Duration.ofSeconds(20);
        private final MclContext context;

        private PluginHost(MclContext context) {
            this.context = context;
        }

        @Override
        public Path dataDir() {
            return AppPaths.dataDir();
        }

        @Override
        public Path launcherRoot() {
            return AppPaths.launcherRoot();
        }

        @Override
        public String launcherVersion() {
            return "1.0.0";
        }

        @Override
        public Window ownerWindow() {
            // 主视窗 = 当前显示中、不是弹窗的那一个；取不到就给 null（对话框会自己找）
            for (Window window : Window.getWindows()) {
                if (window.isShowing() && window instanceof javafx.stage.Stage stage
                        && stage.getModality() == javafx.stage.Modality.NONE) {
                    return window;
                }
            }
            return Window.getWindows().stream().filter(Window::isShowing).findFirst().orElse(null);
        }

        @Override
        public void openUrl(String url) {
            com.brisklauncher.mcl.ui.DesktopActions.openUrl(url);
        }

        @Override
        public void openFolder(Path path) {
            com.brisklauncher.mcl.ui.DesktopActions.openFolder(path);
        }

        @Override
        public byte[] httpGet(String url) throws Exception {
            return Http.getBytes(URI.create(url), TIMEOUT);
        }

        @Override
        public String httpGetText(String url) throws Exception {
            return Http.getString(URI.create(url), TIMEOUT);
        }

        @Override
        public Path selectedInstanceGameDir() {
            var instance = selectedInstance();
            return instance == null ? null : instance.gameDir();
        }

        @Override
        public String selectedInstanceLabel() {
            var instance = selectedInstance();
            return instance == null ? "" : instance.id();
        }

        @Override
        public java.util.List<InstanceInfo> instances() {
            java.util.List<InstanceInfo> result = new java.util.ArrayList<>();
            try {
                for (var root : context.gameRepository().roots()) {
                    for (var instance : context.gameRepository().versions(root)) {
                        result.add(new InstanceInfo(instance.id(), instance.mcVersion(),
                                instance.gameDir()));
                    }
                }
            } catch (Exception ignored) {
                // 扫描失败就回空列表
            }
            return result;
        }

        /** 記住的上次選中實例：先看 ui-state 的大版本文件夾 + 實例 id，找不到就用第一個。 */
        private com.brisklauncher.mcl.game.GameInstance selectedInstance() {
            try {
                var repository = context.gameRepository();
                var roots = repository.roots();
                if (roots.isEmpty()) {
                    return null;
                }
                String rememberedRoot = context.uiState().lastRoot();
                var root = roots.stream()
                        .filter(item -> item.path().toString().equals(rememberedRoot))
                        .findFirst().orElse(roots.get(0));
                var instances = repository.versions(root);
                if (instances.isEmpty()) {
                    return null;
                }
                String rememberedId = context.uiState().lastVersion(root.path());
                return instances.stream()
                        .filter(item -> item.id().equals(rememberedId))
                        .findFirst().orElse(instances.get(0));
            } catch (Exception error) {
                return null;
            }
        }

        @Override
        public void info(String title, String message) {
            com.brisklauncher.mcl.ui.MCLDialog.info(ownerWindow(), title, message);
        }

        @Override
        public void error(String title, String message) {
            com.brisklauncher.mcl.ui.MCLDialog.error(ownerWindow(), title, message);
        }
    }
}
