package com.brisklauncher.mcl;

import com.brisklauncher.mcl.auth.AccountList;
import com.brisklauncher.mcl.crash.CrashReportExporter;
import com.brisklauncher.mcl.crash.CrashRuleEngine;
import com.brisklauncher.mcl.download.HttpDownloader;
import com.brisklauncher.mcl.download.MirrorResolver;
import com.brisklauncher.mcl.download.remote.LoaderMetaClient;
import com.brisklauncher.mcl.download.remote.ModrinthClient;
import com.brisklauncher.mcl.download.remote.MojangApi;
import com.brisklauncher.mcl.game.GameDirectoryStore;
import com.brisklauncher.mcl.game.GameRepository;
import com.brisklauncher.mcl.game.GameScanner;
import com.brisklauncher.mcl.game.install.ModLoaderInstaller;
import com.brisklauncher.mcl.game.install.VersionInstaller;
import com.brisklauncher.mcl.game.launch.GameLauncher;
import com.brisklauncher.mcl.game.mod.ModManager;
import com.brisklauncher.mcl.i18n.I18n;
import com.brisklauncher.mcl.java.JavaManager;
import com.brisklauncher.mcl.modpack.MrpackExporter;
import com.brisklauncher.mcl.modpack.MrpackInstaller;
import com.brisklauncher.mcl.net.Http;
import com.brisklauncher.mcl.online.FrpService;
import com.brisklauncher.mcl.online.OnlineState;
import com.brisklauncher.mcl.online.TerracottaService;
import com.brisklauncher.mcl.security.SafeLog;
import com.brisklauncher.mcl.setting.LauncherSettings;
import com.brisklauncher.mcl.setting.SettingsStore;
import com.brisklauncher.mcl.setting.UiStateStore;
import com.brisklauncher.mcl.skin.SkinManager;
import com.brisklauncher.mcl.util.LauncherLock;

/**
 * 应用级服务容器（HMCL 风格的门面集合）。
 *
 * <p>把「跟设置相关」的服务（下载器、镜像、安装器、启动器）打包成一个 bundle：
 * 设置一改就整体重建，避免半个旧镜像半个新镜像的诡异状态。
 * UI 只拿这里暴露的服务，不自己 new 这些对象。
 *
 * <p>进程内只保留一份长生命周期服务（账号、皮肤、Mod、崩溃规则等），
 * 避免每次切页重建；{@link #shutdown()} 负责释放下载池、运行中进程与实例锁。
 */
public final class MclContext {

    private final SettingsStore settingsStore = new SettingsStore();

    private final AccountList accounts = new AccountList();
    private final SkinManager skins = new SkinManager();
    private final ModManager mods = new ModManager();
    private final CrashRuleEngine crashRules = new CrashRuleEngine();
    private final CrashReportExporter crashExporter = new CrashReportExporter();
    private final MrpackExporter modpackExporter = new MrpackExporter();
    private final GameDirectoryStore rootStore = new GameDirectoryStore();
    private final UiStateStore uiState = new UiStateStore();
    private final GameScanner scanner = new GameScanner();
    private final FrpService frp = new FrpService();
    private final TerracottaService terracotta = new TerracottaService();
    private final OnlineState onlineState = new OnlineState();
    private final ModrinthClient modrinth = new ModrinthClient();
    private final JavaManager javaManager = new JavaManager();
    private final LoaderMetaClient loaderMeta = new LoaderMetaClient();
    private final com.brisklauncher.mcl.plugin.PluginManager plugins =
            new com.brisklauncher.mcl.plugin.PluginManager(this);

    private final LauncherLock lock;
    private volatile Bundle bundle;
    /**
     * 实例清单变更的监听（启动页、版本页刷新用）。
     *
     * <p>「图标变更」只是其中一种触发；安装版本 / 导入整合包 / 删除 / 改名 / 外部启动器改了
     * {@code versions/} 都要让清单即时更新（使用者回报「版本列表在变化时没有实时更新」）。
     */
    private final java.util.List<Runnable> instancesListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    /** 實例清單監看的背景執行緒（每 1.5 秒比對一次 versions/ 的快照）。 */
    private volatile Thread instancesWatcher;
    private volatile boolean watcherRunning = true;

    /** 訂閱「實例清單有變」；回傳取消訂閱。 */
    public Runnable addInstancesChangedListener(Runnable listener) {
        instancesListeners.add(listener);
        return () -> instancesListeners.remove(listener);
    }

    /** 舊名字：圖標變更其實也是「實例清單有變」，直接沿用同一組監聽。 */
    public Runnable addIconChangeListener(Runnable listener) {
        return addInstancesChangedListener(listener);
    }

    /** 實例清單有變（安裝/導入/刪除/改名/圖標/外部變更）→ 通知各頁刷新。 */
    public void notifyInstancesChanged() {
        Runnable dispatch = () -> {
            for (Runnable listener : instancesListeners) {
                try {
                    listener.run();
                } catch (Exception ignored) {
                    // 某个页面刷新失败不影响其它页面
                }
            }
        };
        if (javafx.application.Platform.isFxApplicationThread()) {
            dispatch.run();
        } else {
            javafx.application.Platform.runLater(dispatch);
        }
    }

    /** 圖標被改掉（設定/清除/從截圖設定）後通知界面刷新。 */
    public void notifyInstanceIconChanged() {
        notifyInstancesChanged();
    }

    /**
     * 背景监看所有游戏目录的 {@code versions/}：资料夹增减、版本 JSON 的时间/大小变化都算变更。
     *
     * <p>用轮询而不是 {@code WatchService}：Windows 上对「整个目录被删掉再建」的通知常常漏，
     * 而且这里的目录规模很小（一两百个版本），1.5 秒比对一次的字串成本可以忽略。
     */
    private void startInstancesWatcher() {
        Thread thread = new Thread(() -> {
            String previous = "";
            while (watcherRunning) {
                try {
                    Thread.sleep(1500L);
                } catch (InterruptedException interrupted) {
                    return;
                }
                if (!watcherRunning) {
                    return;
                }
                String snapshot = instancesSignature();
                if (!snapshot.equals(previous)) {
                    if (!previous.isEmpty()) {
                        com.brisklauncher.mcl.security.SafeLog.info("[list] 侦测到版本变更，刷新列表");
                        notifyInstancesChanged();
                    }
                    previous = snapshot;
                }
            }
        }, "mcl-instances-watcher");
        thread.setDaemon(true);
        thread.start();
        instancesWatcher = thread;
    }

    /** 目前所有根目錄底下 versions/ 的快照（名稱 + json 的長度/時間）。 */
    private String instancesSignature() {
        StringBuilder sb = new StringBuilder();
        try {
            for (com.brisklauncher.mcl.game.GameDirectory root : rootStore().load()) {
                java.nio.file.Path versions = root.versionsDir();
                sb.append(root.path()).append('|');
                if (!java.nio.file.Files.isDirectory(versions)) {
                    sb.append("missing;");
                    continue;
                }
                java.util.List<String> names = new java.util.ArrayList<>();
                try (var stream = java.nio.file.Files.list(versions)) {
                    stream.filter(java.nio.file.Files::isDirectory)
                            .forEach(path -> names.add(path.getFileName().toString()));
                }
                names.sort(String::compareTo);
                for (String name : names) {
                    sb.append(name);
                    java.nio.file.Path json = versions.resolve(name).resolve(name + ".json");
                    if (java.nio.file.Files.isRegularFile(json)) {
                        sb.append(':').append(json.toFile().length())
                                .append(':').append(json.toFile().lastModified());
                    }
                    sb.append(';');
                }
            }
        } catch (Exception ignored) {
            return "";
        }
        return sb.toString();
    }

    /** 设置相关的服务集合。 */
    private record Bundle(LauncherSettings settings,
                          HttpDownloader downloader,
                          MirrorResolver mirrors,
                          MojangApi mojang,
                          VersionInstaller versionInstaller,
                          ModLoaderInstaller modLoaderInstaller,
                          GameRepository gameRepository,
                          GameLauncher gameLauncher,
                          MrpackInstaller modpackInstaller) {
    }

    public MclContext() {
        // 语言包 / 主题包目录：首次运行建立并写入结构说明
        MclApplication.trace("ctx: modpackExporter");
        modpackExporter.setModrinthClient(modrinth);
        MclApplication.trace("ctx: migrateHeavyDirs");
        com.brisklauncher.mcl.AppPaths.migrateHeavyDirs();
        // 安全审计 H-02：data/ 与 plugins/native 也要加固（安装目录常是公共/可移动位置）
        MclApplication.trace("ctx: hardenDataDirs");
        com.brisklauncher.mcl.AppPaths.hardenDataDirs();
        MclApplication.trace("ctx: migrateOldLogs");
        com.brisklauncher.mcl.AppPaths.migrateOldLogs();
        com.brisklauncher.mcl.AppPaths.migrateAccountsFile();
        // 遗留的 *.tmp（move 失败留下的）在背景清掉，别让 data/cache 越滚越大
        MclApplication.trace("ctx: cleanStaleTempFiles");
        com.brisklauncher.mcl.AppPaths.cleanStaleTempFiles();
        // 安全审计 L-03：frpc.toml 里有明文 token，上次异常退出可能留下残留
        frp.cleanStaleConfig();
        MclApplication.trace("ctx: Packs.ensure");
        com.brisklauncher.mcl.setting.Packs.ensure();
        MclApplication.trace("ctx: LauncherLock");
        this.lock = LauncherLock.acquire(AppPaths.launcherRoot());
        MclApplication.trace("ctx: settings.load");
        LauncherSettings settings = settingsStore.load();
        // 注意：不要把「解析后的客户端 ID」写回 settings，
        // 否则玩家在设置页清空后，重启又会被源码里的值填回来。
        // 解析发生在真正登录时（MicrosoftAuthProvider#effectiveClientId）。
        applyGlobalSettings(settings);
        if (!settings.language().equals(I18n.currentLocaleTag())) {
            I18n.setLocale(resolveLanguage(settings));
        }
        MclApplication.trace("ctx: rebuild bundle");
        rebuild(settings);
        gameRepository().ensureDefaultRoot();
        // 版本列表即时更新：背景比对 versions/ 的变化（外部启动器改动也吃得到）
        startInstancesWatcher();
        MclApplication.trace("ctx: 完成");
    }

    /**
     * 背景更新崩溃规则（網路操作，不該跟啟動搶 CPU）。
     *
     * <p>以前放在建構子裡，會跟「建主介面」同時跑；現在由 {@code MclApplication}
     * 在主介面出現之後再呼叫。
     */
    public void updateCrashRulesInBackground() {
        try {
            if (settingsStore.load().crashRuleRemoteUpdate()) {
                Thread.ofVirtual().name("mcl-crash-rules").start(crashRules::updateRemote);
            }
        } catch (Throwable error) {
            com.brisklauncher.mcl.security.SafeLog.warn(
                    "崩溃规则更新启动失败：" + error.getClass().getSimpleName());
        }
    }

    private synchronized void rebuild(LauncherSettings settings) {
        Bundle old = bundle;
        if (old != null) {
            old.downloader().close();
            old.gameLauncher().shutdown();
        }

        HttpDownloader downloader = new HttpDownloader(
                settings.concurrency(), settings.retries(), settings.timeoutSeconds());
        MirrorResolver mirrors = new MirrorResolver(settings);
        MojangApi mojang = new MojangApi(mirrors, settings.timeoutSeconds());
        VersionInstaller versionInstaller = new VersionInstaller(mojang, mirrors, downloader);
        ModLoaderInstaller modLoaderInstaller = new ModLoaderInstaller(
                versionInstaller, mojang, loaderMeta, javaManager, downloader, mirrors);
        GameRepository gameRepository = new GameRepository(rootStore, scanner, versionInstaller,
                modLoaderInstaller, new com.brisklauncher.mcl.game.InstanceSettingsStore(), mojang,
                this::settings, this::isVersionRunning);
        GameLauncher gameLauncher = new GameLauncher(this::settings, versionInstaller,
                javaManager, downloader);
        MrpackInstaller modpackInstaller = new MrpackInstaller(gameRepository, downloader, this::settings);

        bundle = new Bundle(settings, downloader, mirrors, mojang, versionInstaller,
                modLoaderInstaller, gameRepository, gameLauncher, modpackInstaller);
    }

    private static void applyGlobalSettings(LauncherSettings settings) {
        System.setProperty("mcl.proxy", settings.proxy());
        // 安全审计 M-05：insecureTls 会关掉**所有下载**的 TLS 验证；
        // 以前只要改 launcher.json 就能置位（绕过 UI 的风险确认）。
        // 现在把它当成「需要授权的开关」：acks.json 里没有确认纪录就强制关闭。
        boolean insecure = settings.insecureTls();
        if (insecure && !com.brisklauncher.mcl.security.Acks.has("insecureTls")) {
            com.brisklauncher.mcl.security.SafeLog.warn(
                    "launcher.json 里打开了「跳过 TLS 验证」，但没有确认记录，已强制关闭");
            insecure = false;
        }
        System.setProperty("mcl.tls.insecure", String.valueOf(insecure));
        Http.configure(settings.proxy(), insecure);
    }

    /** 同一資料目錄已有另一個啟動器在跑（拿不到單實例鎖）。 */
    public boolean alreadyRunning() {
        return lock == null;
    }

    /** 自動跟隨系統語言（zh_CN / en_US）；使用者關掉就跟設定值走。 */
    /**
     * 执行期一律使用设定档里的语言。
     *
     * <p>「跟随系统语言」只在**第一次启动**（`launcher.json` 还不存在）时由
     * {@link com.brisklauncher.mcl.setting.SettingsStore} 决定一次并写入设定；
     * 之后不再读系统语系，避免手动选了语言又被盖回去。
     */
    public static String resolveLanguage(LauncherSettings settings) {
        return settings == null ? "zh_CN" : settings.language();
    }

    public LauncherSettings settings() {
        return bundle.settings();
    }

    public void saveSettings(LauncherSettings settings) {
        settingsStore.save(settings);
        applyGlobalSettings(settings);
        if (!settings.language().equals(I18n.currentLocaleTag())) {
            I18n.setLocale(resolveLanguage(settings));
        }
        rebuild(settings);
    }

    /**
     * 语言切换：立即持久化并通知 UI 重建，但不重建下载器/启动器
     * （避免只是换个语言就把正在进行的下载池关掉）。
     */
    public synchronized void updateLanguage(String tag) {
        LauncherSettings current = bundle.settings();
        if (tag == null || tag.isBlank() || tag.equals(current.language())) {
            return;
        }
        LauncherSettings updated = current.withLanguage(tag);
        settingsStore.save(updated);
        Bundle old = bundle;
        bundle = new Bundle(updated, old.downloader(), old.mirrors(), old.mojang(),
                old.versionInstaller(), old.modLoaderInstaller(), old.gameRepository(),
                old.gameLauncher(), old.modpackInstaller());
        I18n.setLocale(tag);
    }

    public SettingsStore settingsStore() {
        return settingsStore;
    }

    public HttpDownloader downloader() {
        return bundle.downloader();
    }

    public MirrorResolver mirrors() {
        return bundle.mirrors();
    }

    public MojangApi mojang() {
        return bundle.mojang();
    }

    public VersionInstaller versionInstaller() {
        return bundle.versionInstaller();
    }

    public ModLoaderInstaller modLoaderInstaller() {
        return bundle.modLoaderInstaller();
    }

    /** 实例/版本列表的唯一入口。 */
    public GameRepository gameRepository() {
        return bundle.gameRepository();
    }

    public GameLauncher gameLauncher() {
        return bundle.gameLauncher();
    }

    /** 這個版本是不是正在跑（刪除/改名前的檢查用）。 */
    public boolean isVersionRunning(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            return false;
        }
        Bundle current = bundle;
        if (current == null) {
            return false;
        }
        for (com.brisklauncher.mcl.game.launch.RunningGames.RunningGame game
                : current.gameLauncher().runningGames().list()) {
            if (versionId.equals(game.versionId())) {
                return true;
            }
        }
        return false;
    }

    public MrpackInstaller modpackInstaller() {
        return bundle.modpackInstaller();
    }

    public MrpackExporter modpackExporter() {
        return modpackExporter;
    }

    public AccountList accounts() {
        return accounts;
    }

    public SkinManager skins() {
        return skins;
    }

    public ModManager mods() {
        return mods;
    }

    public CrashRuleEngine crashRules() {
        return crashRules;
    }

    public CrashReportExporter crashExporter() {
        return crashExporter;
    }

    public GameDirectoryStore rootStore() {
        return rootStore;
    }

    public UiStateStore uiState() {
        return uiState;
    }

    public GameScanner scanner() {
        return scanner;
    }

    public FrpService frp() {
        return frp;
    }

    public TerracottaService terracotta() {
        return terracotta;
    }

    public OnlineState onlineState() {
        return onlineState;
    }

    public ModrinthClient modrinth() {
        return modrinth;
    }

    public JavaManager javaManager() {
        return javaManager;
    }

    public LoaderMetaClient loaderMeta() {
        return loaderMeta;
    }

    /** 「工具」頁面的插件倉庫（內建 + data/plugins/*.jar）。 */
    public com.brisklauncher.mcl.plugin.PluginManager plugins() {
        return plugins;
    }

    /**
     * 给实例装 Fabric API（Modrinth 上的 fabric-api 项目，slug 可直接用作项目 id）。
     *
     * <p>装 Fabric 加载器时顺手装上是绝大多数玩家的预期——没有它很多 Mod 直接崩。
     *
     * @return 落盘后的文件路径；该 MC 版本没有可用文件时回传空
     */
    public java.util.Optional<java.nio.file.Path> installFabricApi(
            com.brisklauncher.mcl.game.GameInstance instance) throws Exception {
        java.util.List<com.brisklauncher.mcl.download.remote.ModrinthClient.VersionFile> files =
                modrinth.versions("fabric-api", java.util.List.of("fabric"),
                        java.util.List.of(instance.mcVersion()));
        if (files.isEmpty()) {
            return java.util.Optional.empty();
        }
        com.brisklauncher.mcl.download.remote.ModrinthClient.VersionFile file = files.get(0);
        java.nio.file.Path dir = instance.gameDir().resolve("mods");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Path target = dir.resolve(
                java.nio.file.Path.of(file.fileName()).getFileName().toString());
        downloader().downloadOne(new com.brisklauncher.mcl.download.DownloadTask(
                mirrors().resolve(file.downloadUrl()), target, file.sha1(), file.size()));
        return java.util.Optional.of(target);
    }

    /** Fabric API 在指定 MC 版本下的可用版本（供用户挑选）。 */
    public java.util.List<com.brisklauncher.mcl.download.remote.ModrinthClient.VersionFile> fabricApiVersions(
            String mcVersion) {
        try {
            return modrinth.versions("fabric-api", java.util.List.of("fabric"),
                    java.util.List.of(mcVersion));
        } catch (Exception error) {
            SafeLog.warn("获取 Fabric API 版本失败：" + error.getClass().getSimpleName());
            return java.util.List.of();
        }
    }

    /** 安装用户选定的那一版 Fabric API；versionId 为空时用最新版。 */
    public java.util.Optional<java.nio.file.Path> installFabricApi(
            com.brisklauncher.mcl.game.GameInstance instance, String versionId) throws Exception {
        java.util.List<com.brisklauncher.mcl.download.remote.ModrinthClient.VersionFile> files =
                modrinth.versions("fabric-api", java.util.List.of("fabric"),
                        java.util.List.of(instance.mcVersion()));
        com.brisklauncher.mcl.download.remote.ModrinthClient.VersionFile file = null;
        for (var candidate : files) {
            if (versionId == null || versionId.isBlank() || candidate.versionId().equals(versionId)) {
                file = candidate;
                break;
            }
        }
        if (file == null && !files.isEmpty()) {
            file = files.get(0);
        }
        if (file == null) {
            return java.util.Optional.empty();
        }
        java.nio.file.Path dir = instance.gameDir().resolve("mods");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Path target = dir.resolve(
                java.nio.file.Path.of(file.fileName()).getFileName().toString());
        downloader().downloadOne(new com.brisklauncher.mcl.download.DownloadTask(
                mirrors().resolve(file.downloadUrl()), target, file.sha1(), file.size()));
        return java.util.Optional.of(target);
    }
    public void shutdown() {
        SafeLog.info("启动器退出");
        watcherRunning = false;
        Thread watcher = instancesWatcher;
        if (watcher != null) {
            watcher.interrupt();
        }
        // 插件必须在服务、下载器、实例锁都还活着的时候卸载：onUnload 里可能还要用日志 / HTTP
        try {
            plugins.unloadAll();
        } catch (Throwable error) {
            SafeLog.warn("卸载插件失败：" + error.getClass().getSimpleName());
        }
        try {
            frp.stop();
        } catch (Exception ignored) {
            // 退出流程尽力而为
        }
        terracotta.stop();
        // 注意：这里**不要** stopAll()。关闭启动器不应结束正在运行的游戏，
        // 游戏进程会独立继续运行（RunningGames 只负责监听退出）。
        bundle.gameLauncher().runningGames().detachAll();
        bundle.downloader().close();
        bundle.gameLauncher().shutdown();
        if (lock != null) {
            lock.close();
        }
    }
}
