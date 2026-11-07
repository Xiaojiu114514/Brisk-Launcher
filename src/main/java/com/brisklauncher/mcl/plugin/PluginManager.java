package com.brisklauncher.mcl.plugin;

import com.brisklauncher.mcl.AppPaths;
import com.brisklauncher.mcl.MclContext;
import com.brisklauncher.mcl.auth.api.AuthProvider;
import com.brisklauncher.mcl.auth.impl.AuthProviderRegistry;
import com.brisklauncher.mcl.net.Http;
import com.brisklauncher.mcl.plugin.api.LauncherPage;
import com.brisklauncher.mcl.plugin.api.LauncherPlugin;
import com.brisklauncher.mcl.plugin.api.PluginApi;
import com.brisklauncher.mcl.plugin.api.PluginContext;
import com.brisklauncher.mcl.plugin.builtin.PlayerSkinPlugin;
import com.brisklauncher.mcl.plugin.builtin.RgbTextPlugin;
import com.brisklauncher.mcl.plugin.builtin.SchematicMaterialsPlugin;
import com.brisklauncher.mcl.plugin.builtin.ServerModCheckPlugin;
import com.brisklauncher.mcl.plugin.builtin.ServerPingPlugin;
import com.brisklauncher.mcl.plugin.builtin.SparkReportPlugin;
import com.brisklauncher.mcl.security.SafeLog;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 插件倉庫：內建插件 + {@code <data>/plugins/*.jar} 外部插件。
 *
 * <p>外部插件格式（最小公約數）：
 * <pre>
 * your-plugin.jar
 * ├── plugin.json                       {"id":"x","name":"…","class":"com.example.MyPlugin", …}
 * ├── com/example/MyPlugin.class        ← 實現 com.brisklauncher.mcl.plugin.api.LauncherPlugin
 * ├── i18n/messages_zh_CN.properties     （可選，插件自己的語言包）
 * └── META-INF/services/…AuthProvider    （可選，註冊認證實現）
 * </pre>
 *
 * <p><b>效能</b>：jar 只在啟動時掃一次（{@link #load()}），不隨工具頁切換重掃；
 * 每支 jar 的 SHA-256 只算一次（依檔案大小 + 修改時間快取），禁用檢查與信任檢查共用同一份摘要。
 *
 * <p>不使用 {@code ServiceLoader} 當插件主入口：{@code plugin.json} 指定主類更直觀、
 * 也方便玩家自己改；但認證實現仍走 {@code META-INF/services}（見 {@link #registerAuthServices}）。
 */
public final class PluginManager {

    /** 一個已註冊的插件（載入失敗時 error 非空，UI 會顯示原因）。 */
    public record Entry(LauncherPlugin plugin, PluginContext context, String id, String name,
                        String description, String icon, String version, String author,
                        boolean builtin, Path jar, String error, boolean hasPanel,
                        List<LauncherPage> pages) {

        public boolean failed() {
            return error != null && !error.isBlank();
        }
    }

    /** 一個插件貢獻的導航頁。 */
    public record PageRef(Entry entry, LauncherPage page) {
    }

    /** 解析出來的 plugin.json（載入前的中間產物）。 */
    private record Spec(Path jar, String id, String name, String description, String className,
                        String version, String author, String homepage, String icon, int apiVersion,
                        List<String> requires, String digest, List<String> locales, String blocked) {

        Spec blocked(String reason) {
            return new Spec(jar, id, name, description, className, version, author, homepage, icon,
                    apiVersion, requires, digest, locales, reason);
        }
    }

    /** 摘要快取（同一支 jar 只算一次 SHA-256）。 */
    private record Digest(long size, long modified, String sha) {
    }

    private final MclContext context;
    private final List<Entry> builtins = new ArrayList<>();
    private final List<Entry> external = new ArrayList<>();
    private final Map<String, URLClassLoader> loaders = new HashMap<>();
    private final Map<Path, Digest> digestCache = new HashMap<>();
    private final Set<String> disabled = new LinkedHashSet<>();
    private final Set<String> authProviderIds = new TreeSet<>();
    private final PluginServicesRegistry services = new PluginServicesRegistry();
    private final List<Runnable> beforeUnload = new CopyOnWriteArrayList<>();
    private boolean loaded;

    public PluginManager(MclContext context) {
        this.context = context;
    }

    public Path dir() {
        return AppPaths.dataDir().resolve("plugins");
    }

    /** 這個插件的可寫資料夾（{@code data/plugin-data/<id>}，已建立）。 */
    public Path pluginDataDir(String pluginId) {
        Path dir = AppPaths.pluginDataRoot().resolve(sanitizeId(pluginId));
        try {
            Files.createDirectories(dir);
        } catch (Exception error) {
            SafeLog.warn("[plugin] 建立插件资料夹失败：" + pluginId);
        }
        return dir;
    }

    // ---------- 生命週期 ----------

    /** 首次使用建立目錄與格式說明（已存在就跳過）。 */
    public void ensure() {
        try {
            Files.createDirectories(dir());
            Files.createDirectories(AppPaths.pluginDataRoot());
            Path readme = dir().resolve("README.txt");
            if (!Files.isRegularFile(readme)) {
                Files.writeString(readme, """
                        Brisk Launcher 插件目录
                        ========================
                        把插件 jar 放进本目录，然后在「工具」页点「刷新」即可看到（不用重启）。
                        插件自己的配置与存档会写到 data/plugin-data/<插件id>/。

                        jar 内部结构（最简）：
                          plugin.json   {"id":"my-plugin","name":"我的插件","class":"com.example.MyPlugin",
                                         "version":"1.0.0","apiVersion":2}
                          其余 class     实现 com.brisklauncher.mcl.plugin.api.LauncherPlugin

                        可选：
                          i18n/messages_zh_CN.properties …  插件自己的语言包
                          META-INF/services/com.brisklauncher.mcl.auth.api.AuthProvider
                                                         注册认证实现（会出现在账号页）

                        启动器只通过 PluginContext 开放能力（资料目录、HTTP、日志、插件服务…），
                        私有目录（账号 / 密钥）不对插件开放。详见 PLUGIN_DEV.md。
                        """, StandardCharsets.UTF_8);
            }
            loadDisabled();
        } catch (Exception error) {
            SafeLog.warn("建立插件目录失败：" + error.getClass().getSimpleName());
        }
    }

    /** 註冊內建插件並掃描外部 jar（只做一次；使用者按「刷新」才 {@link #reload()}）。 */
    public synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        ensure();
        registerBuiltins();
        loadExternal();
    }

    /** 全部卸載（呼叫 {@code onUnload}、註銷認證實現、清服務、關 classloader）後重新掃描。 */
    public synchronized void reload() {
        unloadAll();
        load();
    }

    /**
     * 卸載所有插件。
     *
     * <p>會先跑「面板回收」監聽（UI 藉此 {@code dispose()} 已建立的面板），
     * 再依相反順序呼叫 {@code onUnload()}。
     */
    public synchronized void unloadAll() {
        for (Runnable listener : beforeUnload) {
            try {
                listener.run();
            } catch (Exception error) {
                SafeLog.warn("[plugin] 面板回收失败：" + error.getClass().getSimpleName());
            }
        }
        List<Entry> all = new ArrayList<>(external);
        all.addAll(builtins);
        for (int index = all.size() - 1; index >= 0; index--) {
            Entry entry = all.get(index);
            if (entry.plugin() == null) {
                continue;
            }
            try {
                entry.plugin().onUnload();
            } catch (Throwable error) {
                SafeLog.warn("[plugin] 卸载插件失败：" + entry.name() + " → "
                        + error.getClass().getSimpleName());
            }
            services.clear(entry.id());
        }
        for (String id : new ArrayList<>(authProviderIds)) {
            AuthProviderRegistry.unregister(id);
        }
        authProviderIds.clear();
        closeLoaders();
        builtins.clear();
        external.clear();
        loaded = false;
    }

    /** UI 在插件卸載前註冊「先把面板收乾淨」的動作。 */
    public void addBeforeUnloadListener(Runnable listener) {
        if (listener != null) {
            beforeUnload.add(listener);
        }
    }

    public void removeBeforeUnloadListener(Runnable listener) {
        beforeUnload.remove(listener);
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

    /** 所有插件貢獻的導航頁（順序：內建 → 外部，欄位順序照插件回傳的順序）。 */
    public synchronized List<PageRef> pages() {
        List<PageRef> result = new ArrayList<>();
        for (Entry entry : entries()) {
            if (entry.failed()) {
                continue;
            }
            for (LauncherPage page : entry.pages()) {
                result.add(new PageRef(entry, page));
            }
        }
        return result;
    }

    /** 導航頁在 {@code MclApplication} 裡用的 key。 */
    public static String pageKey(String pluginId, String pageId) {
        return "p:" + pluginId + ":" + pageId;
    }

    public boolean isEnabled(String id) {
        return !disabled.contains(id);
    }

    public synchronized void setEnabled(String id, boolean enabled) {
        if (enabled) {
            disabled.remove(id);
            // 停用時會同時寫入「原樣 id」與「清洗後 id」，啟用時兩份都要清掉
            disabled.remove(sanitizeId(id));
        } else {
            disabled.add(sanitizeId(id));
            disabled.add(id);
        }
        saveDisabled();
    }

    /** 目前登記了哪些插件服務（除錯用）。 */
    public List<String> serviceNames() {
        return services.names();
    }

    // ---------- 內建插件 ----------

    private void registerBuiltins() {
        registerBuiltin(new RgbTextPlugin());
        registerBuiltin(new ServerPingPlugin());
        registerBuiltin(new PlayerSkinPlugin());
        registerBuiltin(new SparkReportPlugin());
        registerBuiltin(new ServerModCheckPlugin());
        registerBuiltin(new SchematicMaterialsPlugin());
    }

    private void registerBuiltin(LauncherPlugin plugin) {
        String id = sanitizeId(plugin.id());
        Path dataDir = pluginDataDir(id);
        ClassLoader loader = LauncherPlugin.class.getClassLoader();
        PluginHostImpl host = new PluginHostImpl(context, id, dataDir, loader, List.of(),
                services, this::registerAuthProvider);
        try {
            plugin.onLoad(host);
        } catch (Throwable error) {
            SafeLog.warn("[plugin] 内建插件初始化失败：" + id + " → " + error.getClass().getSimpleName());
        }
        builtins.add(new Entry(plugin, host, id, plugin.name(), plugin.description(), plugin.icon(),
                blank(plugin.version()), "", true, null, null, hasPanel(plugin), safePages(plugin)));
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
        if (jars.isEmpty()) {
            return;
        }
        List<Spec> specs = new ArrayList<>();
        for (Path jar : jars) {
            specs.add(readSpec(jar));
        }
        specs = orderByRequires(specs);
        for (Spec spec : specs) {
            if (spec.blocked() != null) {
                external.add(failedEntry(spec.jar(), spec.name(), spec.blocked()));
                continue;
            }
            Entry entry = loadJar(spec);
            if (entry != null) {
                external.add(entry);
            }
        }
    }

    /** 讀 plugin.json + 算摘要 + 做「能不能載入」的前置判斷（不碰 classloader）。 */
    private Spec readSpec(Path jar) {
        String fallbackName = jar.getFileName().toString().replaceAll("(?i)\\.jar$", "");
        String fallbackId = sanitizeId(fallbackName);
        try {
            PluginManifest.Data data = PluginManifest.read(jar);
            String id = data.id().isBlank() ? fallbackId : data.id();
            String name = data.name().isBlank() ? fallbackName : data.name();
            String digest = digest(jar);
            Spec spec = new Spec(jar, id, name, data.description(), data.className(), data.version(),
                    data.author(), data.homepage(), data.icon(), data.apiVersion(), data.requires(),
                    digest, data.localeTags(), null);
            if (data.apiVersion() > PluginApi.VERSION) {
                return spec.blocked("需要更新版本的启动器（插件要求 API " + data.apiVersion()
                        + "，本启动器是 " + PluginApi.VERSION + "）");
            }
            if (isDisabled(jar, id, digest)) {
                return spec.blocked("已在 disabled.txt 中禁用");
            }
            return spec;
        } catch (IllegalArgumentException bad) {
            return new Spec(jar, fallbackId, fallbackName, "", "", "", "", "", "", 0, List.of(),
                    null, List.of(), bad.getMessage());
        } catch (Exception error) {
            return new Spec(jar, fallbackId, fallbackName, "", "", "", "", "", "", 0, List.of(),
                    null, List.of(), "plugin.json 解析失败：" + error.getClass().getSimpleName());
        }
    }

    /** 依 {@code requires} 排序，並把缺依賴 / 循環依賴 / id 重複的項目標成失敗。 */
    private List<Spec> orderByRequires(List<Spec> specs) {
        Map<String, Spec> byId = new LinkedHashMap<>();
        List<Spec> result = new ArrayList<>();
        Set<String> builtinIds = new HashSet<>();
        for (Entry entry : builtins) {
            builtinIds.add(entry.id());
        }
        for (Spec spec : specs) {
            if (spec.blocked() != null) {
                result.add(spec);
                continue;
            }
            if (builtinIds.contains(spec.id())) {
                result.add(spec.blocked("id 与内建插件重复：" + spec.id()));
                continue;
            }
            Spec previous = byId.putIfAbsent(spec.id(), spec);
            if (previous != null) {
                result.add(spec.blocked("id 与另一支插件重复：" + spec.id() + "（"
                        + previous.jar().getFileName() + "）"));
            }
        }
        List<Spec> pending = new ArrayList<>(byId.values());
        Set<String> known = new HashSet<>(builtinIds);
        boolean progress = true;
        while (!pending.isEmpty() && progress) {
            progress = false;
            var iterator = pending.iterator();
            while (iterator.hasNext()) {
                Spec spec = iterator.next();
                if (!known.containsAll(spec.requires())) {
                    continue;
                }
                result.add(spec);
                known.add(spec.id());
                iterator.remove();
                progress = true;
            }
        }
        List<String> available = new ArrayList<>(known);
        for (Spec spec : pending) {
            List<String> missing = new ArrayList<>();
            for (String required : spec.requires()) {
                if (!available.contains(required)) {
                    missing.add(required);
                }
            }
            result.add(missing.isEmpty()
                    ? spec.blocked("依赖循环：" + String.join(" → ", spec.requires()))
                    : spec.blocked("缺少依赖插件：" + String.join(", ", missing)));
        }
        return result;
    }

    private Entry loadJar(Spec spec) {
        Path jar = spec.jar();
        try {
            // 第三方插件由使用者自行安装、自行承担风险；启动器只做两件属于「自己责任」的事：
            //   1) disabled.txt 的插件不加载、不执行（按 id / jar 档名 / SHA-256 判断）；
            //   2) 首次载入外部插件时弹一次风险说明（acks.json），摘要变了再问一次。
            if (!com.brisklauncher.mcl.security.Acks.has("plugins") && !askPluginRisk(spec.name())) {
                return failedEntry(jar, spec.name(), "使用者取消载入第三方插件");
            }
            if (!confirmPluginDigest(jar, spec.id(), spec.name(), spec.digest())) {
                return failedEntry(jar, spec.name(), "插件内容已变更，使用者未确认");
            }
            URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()},
                    LauncherPlugin.class.getClassLoader());
            Class<?> type = Class.forName(spec.className(), true, loader);
            if (!LauncherPlugin.class.isAssignableFrom(type)) {
                loader.close();
                return failedEntry(jar, spec.name(),
                        "主类没有实现 LauncherPlugin（com.brisklauncher.mcl.plugin.api）");
            }
            LauncherPlugin plugin = (LauncherPlugin) type.getDeclaredConstructor().newInstance();
            Path dataDir = pluginDataDir(spec.id());
            PluginHostImpl host = new PluginHostImpl(context, spec.id(), dataDir, loader,
                    spec.locales(), services, this::registerAuthProvider);
            try {
                plugin.onLoad(host);
            } catch (Throwable error) {
                SafeLog.warn("[plugin] onLoad 失败：" + spec.name() + " → "
                        + error.getClass().getSimpleName() + ": " + error.getMessage());
            }
            loaders.put(spec.id(), loader);
            registerAuthServices(spec.id(), loader);
            List<LauncherPage> pages = safePages(plugin);
            String name = blank(plugin.name(), spec.name());
            if (System.getProperty("mcl.plugins.quiet") == null) {
                SafeLog.info("已载入外部插件：" + name + "（" + spec.id() + "）");
            }
            return new Entry(plugin, host, spec.id(), name,
                    blank(plugin.description(), spec.description()),
                    blank(plugin.icon(), blank(spec.icon(), "🧩")),
                    blank(plugin.version(), spec.version()),
                    spec.author(), false, jar, null, hasPanel(plugin), pages);
        } catch (Throwable error) {
            return failedEntry(jar, spec.name(), describe(error));
        }
    }

    /** 把例外壓成一行可顯示的訊息（含第一個堆疊位置，方便插件作者定位）。 */
    private static String describe(Throwable error) {
        StringBuilder text = new StringBuilder(error.getClass().getSimpleName());
        if (error.getMessage() != null && !error.getMessage().isBlank()) {
            text.append(": ").append(error.getMessage());
        }
        StackTraceElement[] frames = error.getStackTrace();
        if (frames.length > 0) {
            text.append(" （").append(frames[0].getClassName()).append(".")
                    .append(frames[0].getMethodName()).append(":")
                    .append(frames[0].getLineNumber()).append("）");
        }
        SafeLog.warn("[plugin] 载入失败：" + text);
        return text.toString();
    }

    /** 掃描插件 jar 裡的 {@code META-INF/services/…AuthProvider} 並註冊。 */
    private void registerAuthServices(String pluginId, ClassLoader loader) {
        try {
            for (AuthProvider provider : ServiceLoader.load(AuthProvider.class, loader)) {
                registerAuthProvider(pluginId, provider);
            }
        } catch (Throwable error) {
            SafeLog.warn("[plugin] 认证实现加载失败：" + pluginId + " → "
                    + error.getClass().getSimpleName());
        }
    }

    private void registerAuthProvider(String pluginId, AuthProvider provider) {
        if (provider == null) {
            return;
        }
        try {
            AuthProviderRegistry.register(provider);
            authProviderIds.add(AuthProviderRegistry.idOf(provider));
            SafeLog.info("[plugin] 已注册认证实现：" + AuthProviderRegistry.idOf(provider)
                    + "（" + pluginId + "）");
        } catch (Throwable error) {
            SafeLog.warn("[plugin] 注册认证实现失败：" + error.getClass().getSimpleName());
        }
    }

    private static Entry failedEntry(Path jar, String name, String error) {
        String id = sanitizeId(jar.getFileName().toString().replaceAll("(?i)\\.jar$", ""));
        return new Entry(null, null, id, name, "", "⚠", "", "", false, jar, error, false, List.of());
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }

    private static String blank(String value, String fallback) {
        return value == null || value.isBlank() ? (fallback == null ? "" : fallback) : value;
    }

    /** 對外宣稱的 id 只留 {@code [A-Za-z0-9._-]}：它會被拿去當資料夾名稱。 */
    static String sanitizeId(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        StringBuilder builder = new StringBuilder(trimmed.length());
        for (int index = 0; index < trimmed.length(); index++) {
            char current = trimmed.charAt(index);
            boolean allowed = (current >= 'a' && current <= 'z') || (current >= 'A' && current <= 'Z')
                    || (current >= '0' && current <= '9') || current == '.' || current == '_'
                    || current == '-';
            builder.append(allowed ? current : '-');
        }
        String value = builder.toString();
        // 避免 "." / ".." 這種會跳出資料夾的名字
        if (value.isBlank() || ".".equals(value) || "..".equals(value)) {
            return "";
        }
        return value;
    }

    /** 主类有没有覆写 createPanel（沒覆写 = 这个插件不提供工具面板）。 */
    private static boolean hasPanel(LauncherPlugin plugin) {
        try {
            return plugin.getClass().getMethod("createPanel", PluginContext.class)
                    .getDeclaringClass() != LauncherPlugin.class;
        } catch (NoSuchMethodException absent) {
            return false;
        } catch (Throwable unresolved) {
            // 反射解析 javafx.scene.Node 需要 JavaFX Toolkit（未初始化时会抛）。
            // 判断不出来就当「有面板」：面板回传 null 时 UI 会显示“没有工具面板”，不会出错。
            return true;
        }
    }

    private static List<LauncherPage> safePages(LauncherPlugin plugin) {
        try {
            List<LauncherPage> declared = plugin.pages();
            if (declared == null || declared.isEmpty()) {
                return List.of();
            }
            List<LauncherPage> result = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (LauncherPage page : declared) {
                if (page == null || page.id() == null) {
                    continue;
                }
                String id = sanitizeId(page.id());
                if (id.isBlank() || !seen.add(id)) {
                    continue;
                }
                result.add(page);
            }
            return List.copyOf(result);
        } catch (Throwable error) {
            SafeLog.warn("[plugin] 读取插件页面失败：" + error.getClass().getSimpleName());
            return List.of();
        }
    }

    // ---------- 安裝 / 移除 ----------

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
        unloadAll();
        com.brisklauncher.mcl.util.RecycleBin.delete(entry.jar());
        load();
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

    // ---------- 摘要 / 信任 ----------

    private String digest(Path jar) {
        try {
            long size = Files.size(jar);
            long modified = Files.getLastModifiedTime(jar).toMillis();
            Digest cached = digestCache.get(jar);
            if (cached != null && cached.size() == size && cached.modified() == modified) {
                return cached.sha();
            }
            String sha = com.brisklauncher.mcl.download.HashUtils.hash(jar, "SHA-256");
            digestCache.put(jar, new Digest(size, modified, sha));
            return sha;
        } catch (Exception error) {
            return "";
        }
    }

    /**
     * 插件摘要确认。
     *
     * <p>{@code plugin-trust.json} 记著每个插件<b>首次载入</b>时的 SHA-256：
     * 摘要没变 → 直接放行；摘要变了 → 当成「有人换掉了你确认过的插件」，重新问一次。
     * 档案放在私密目录，与 {@code acks.json} 同级。
     */
    private boolean confirmPluginDigest(Path jar, String id, String name, String sha256) {
        if (sha256 == null || sha256.isBlank()) {
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
            try {
                javafx.application.Platform.runLater(() -> {
                    try {
                        show.run();
                    } finally {
                        latch.countDown();
                    }
                });
                latch.await(60, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Throwable noToolkit) {
                // 没有 JavaFX Toolkit（无头环境 / 单元测试）时问不了使用者：按「未确认」处理
                SafeLog.warn("[plugin] 无法显示风险确认窗口，已拒绝载入第三方插件："
                        + noToolkit.getClass().getSimpleName());
                return false;
            }
        }
        if (ok[0]) {
            com.brisklauncher.mcl.security.Acks.record("plugins");
        }
        return ok[0];
    }

    // ---------- 啟用狀態 ----------

    /** 禁用判斷：id、jar 檔名或 SHA-256（避免插件自報 id 繞過）。 */
    private boolean isDisabled(Path jar, String id, String digest) {
        String fileName = jar.getFileName().toString();
        if (disabled.contains(id) || disabled.contains(fileName)) {
            return true;
        }
        return digest != null && !digest.isBlank() && disabled.contains(digest);
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
            Files.write(disabledFile(), new LinkedHashSet<>(new TreeSet<>(disabled)), StandardCharsets.UTF_8);
        } catch (Exception error) {
            SafeLog.warn("保存插件启用状态失败");
        }
    }
}
