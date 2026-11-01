package com.brisklauncher.mcl;

import com.brisklauncher.mcl.i18n.I18n;
import com.brisklauncher.mcl.ui.AccountPage;
import com.brisklauncher.mcl.ui.DownloadPage;
import com.brisklauncher.mcl.ui.LaunchPage;
import com.brisklauncher.mcl.ui.MCLDialog;
import com.brisklauncher.mcl.ui.MclPage;
import com.brisklauncher.mcl.ui.OnlinePage;
import com.brisklauncher.mcl.ui.SettingsPage;
import com.brisklauncher.mcl.ui.StartupOverlay;
import com.brisklauncher.mcl.ui.TopBar;
import com.brisklauncher.mcl.ui.VersionPage;
import com.brisklauncher.mcl.ui.animation.UiTransitions;
import com.brisklauncher.mcl.util.Lazy;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * MCL 入口：顶部标题栏 + 页面切换 + 启动遮罩 + 语言热切换。
 *
 * <p>服务的创建与生命周期都交给 {@link MclContext}；页面只依赖它。
 *
 * <p>页面用 {@link Lazy} 延迟创建，离开时只保留启动页，其余页面走
 * {@code onHidden → onReleased} 彻底释放（监听器、轮询、图片缓存、3D 资源）。
 * 这样反复切页不会再累积旧页面，也不再需要触发全局 GC。
 */
public final class MclApplication extends Application {

    /** 目前執行的實例（給彈窗發起「跳頁 + 搜索」用，例如前置依賴圖的氣泡點擊）。 */
    private static MclApplication instance;

    private MclContext context;

    private Stage stage;
    private Scene scene;
    private StackPane sceneRoot;
    /** 主題背景圖的獨立底層（圓角由 -fx-background-radius 處理）。 */
    private javafx.scene.layout.Region backdrop;
    private StackPane centerLayer;

    private final Map<String, Lazy<Node>> pageCache = new LinkedHashMap<>();
    private final Map<String, Supplier<Node>> pageSuppliers = new LinkedHashMap<>();
    private DownloadPage downloadPage;
    private StartupOverlay startupOverlay;
    private TopBar topBar;

    private String currentPageKey = "launch";
    private boolean transitioning;
    private static java.nio.file.Path screenshotPath;
    private static String languageOverride;
    private static String languageSwitch;
    private static String pageOverride;
    private static String dialogOverride;
    private static String searchOverride;

    @Override
    public void start(Stage stage) {
        // 崩溃时间线的起点：启动器启动的那一瞬间
        com.brisklauncher.mcl.crash.LaunchTimeline.launcherStarted();
        instance = this;
        this.stage = stage;
        this.context = new MclContext();
        if (context.alreadyRunning()) {
            MCLDialog.error(null, "Brisk Launcher", I18n.t("launcher.already_running"));
            context.shutdown();
            Platform.exit();
            return;
        }
        if (languageOverride != null && !languageOverride.isBlank()) {
            I18n.setLocale(languageOverride);
        } else if (context != null) {
            // 自动跟随系统语言（设定页可关）；没有 --language= 覆盖时生效
            I18n.setLocale(com.brisklauncher.mcl.MclContext.resolveLanguage(context.settings()));
        }
        if (pageOverride != null && !pageOverride.isBlank()) {
            currentPageKey = pageOverride;
        }

        this.sceneRoot = new StackPane();
        buildUi();

        scene = new Scene(sceneRoot, 1180, 760);
        applyThemeBackground();
        scene.getStylesheets().add(Objects.requireNonNull(
                getClass().getResource("/styles/mcl-theme.css")).toExternalForm());
        // 透明 Scene + 圆角背景 = 无边框窗口也能有舒服的圆角
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);

        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setTitle("Brisk Launcher");
        stage.setMinWidth(980);
        stage.setMinHeight(640);
        stage.setScene(scene);
        stage.setResizable(true);
        com.brisklauncher.mcl.ui.WindowResizer.install(stage);
        // 拖拽导入（整合包 / mod / 资源包 / 光影 / 存档）
        com.brisklauncher.mcl.ui.DropImporter.install(scene, context);

        stage.setOnCloseRequest(event -> {
            event.consume();
            UiTransitions.fadeOut(sceneRoot, Duration.millis(160), () -> {
                releaseAllPages();
                if (topBar != null) {
                    topBar.dispose();
                }
                // 先关掉所有弹窗（设置/确认/进度），否则在模态窗还开着时 Platform.exit()
                // 会让 JavaFX 的 glass 在退出阶段崩掉（EXCEPTION_ACCESS_VIOLATION）。
                closeExtraWindows();
                context.shutdown();
                Platform.exit();
            });
        });

        stage.iconifiedProperty().addListener((obs, wasIconified, isIconified) -> {
            if (!isIconified) {
                UiTransitions.fadeIn(sceneRoot, Duration.millis(200));
            }
        });

        I18n.addListener(() -> Platform.runLater(this::rebuildUi));
        stage.show();
        applyStyle(sceneRoot);
        if (languageSwitch != null && !languageSwitch.isBlank()) {
            scheduleLanguageSwitch(languageSwitch);
        }
        if (dialogOverride != null && !dialogOverride.isBlank()) {
            Platform.runLater(() -> openDevDialog(dialogOverride));
        }
        if (searchOverride != null && !searchOverride.isBlank()) {
            Platform.runLater(() -> {
                showPage("download");
                if (downloadPage != null) {
                    downloadPage.searchMod(searchOverride);
                }
            });
        }
        if (screenshotPath != null) {
            captureLater(screenshotPath);
        }
        checkUpdatesOnStartup();
        // 安全审计 v2 A-05：首次启动让使用者看见「会联网的三件事」
        // （截图/自动化时用 -Dmcl.dev.nonotice=1 跳过，避免盖住要拍的页面）
        if (System.getProperty("mcl.dev.nonotice") == null) {
            com.brisklauncher.mcl.ui.NetworkNoticeDialog.showOnce(stage, context.settings(),
                    updated -> context.saveSettings(updated));
        }
        // 开发期预览（忽略「已确认」记录），用于截图检查风格
        if (System.getProperty("mcl.dev.networknotice") != null) {
            Platform.runLater(() -> com.brisklauncher.mcl.ui.NetworkNoticeDialog.showForced(
                    stage, context.settings(), updated -> context.saveSettings(updated)));
        }
        // 安全审计 v2 A-09：清掉「凭据管理器里有、accounts.json 里没有」的孤儿令牌
        // 截图模式下不弹（否则会盖住要拍的画面）
        if (screenshotPath == null) {
            checkOrphanCredentials(stage);
        }
        // 开发期：每 3 秒切一页，用来观察「切页是否漏记忆体」（-Dmcl.dev.pagesweep=1）
        if (System.getProperty("mcl.dev.pagesweep") != null) {
            Thread sweep = new Thread(() -> {
                String[] order = {"download", "version", "online", "account", "tools",
                        "settings", "launch"};
                for (int round = 0; round < 3; round++) {
                    for (String key : order) {
                        Platform.runLater(() -> showPage(key));
                        try {
                            Thread.sleep(3000);
                        } catch (InterruptedException interrupted) {
                            return;
                        }
                    }
                }
                com.brisklauncher.mcl.security.SafeLog.info("pagesweep 完成");
            }, "mcl-pagesweep");
            sweep.setDaemon(true);
            sweep.start();
        }
        // heap-guard：堆上限太大就提醒一下（发行版会限制在 192MB）
        long maxHeapMb = java.lang.management.ManagementFactory.getMemoryMXBean()
                .getHeapMemoryUsage().getMax() / (1024 * 1024);
        if (maxHeapMb > 1024) {
            com.brisklauncher.mcl.security.SafeLog.warn("启动器的堆上限是 " + maxHeapMb
                    + " MB（非发行版启动方式），建议用 build/install 里的脚本，"
                    + "它会加 -Xmx192m；否则 Windows 上看到的记忆体会很夸张。");
        }
        // 诊断：每 5 秒印一次记忆体（-Dmcl.dev.memlog=1）
        if (System.getProperty("mcl.dev.memlog") != null) {
            Thread memlog = new Thread(() -> {
                while (true) {
                    var memory = java.lang.management.ManagementFactory.getMemoryMXBean();
                    long heap = memory.getHeapMemoryUsage().getUsed() / (1024 * 1024);
                    long max = memory.getHeapMemoryUsage().getMax() / (1024 * 1024);
                    long nonHeap = memory.getNonHeapMemoryUsage().getUsed() / (1024 * 1024);
                    com.brisklauncher.mcl.security.SafeLog.info("[mem] heap=" + heap + "/" + max
                            + "MB nonheap=" + nonHeap + "MB threads=" + Thread.activeCount());
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException interrupted) {
                        return;
                    }
                }
            }, "mcl-memlog");
            memlog.setDaemon(true);
            memlog.start();
        }
        // 开发期：直接打开下载页的某个栏目（截图/回归验证用）
        String devCategory = System.getProperty("mcl.dev.downloadcategory");
        if (devCategory != null && !devCategory.isBlank()) {
            Platform.runLater(() -> navigateToDownloadCategory(devCategory.trim()));
        }
        // 开发期：对指定实例做一次重命名，验证 GameRepository.rename 的完整路径
        // （-Dmcl.dev.rename=<新名称>，配合 -Dmcl.dev.instance=<目前 id>）
        String devRename = System.getProperty("mcl.dev.rename");
        if (devRename != null && !devRename.isBlank()) {
            Platform.runLater(() -> devRenameCheck(devRename.trim()));
        }
        // 开发期：直接跑一次整合包导入，用来截「成功/失败」弹窗
        // （-Dmcl.dev.importpack=<.mrpack 路径>）
        String devImport = System.getProperty("mcl.dev.importpack");
        if (devImport != null && !devImport.isBlank()) {
            Platform.runLater(() -> devImportPack(devImport.trim()));
        }
        // 开发期：开一个「假」长任务进度框，验证模态（主窗口是否被锁住）
        if (System.getProperty("mcl.dev.taskdemo") != null) {
            Platform.runLater(this::devTaskDemo);
        }
    }

    /** 開發期：跑一個 20 秒的假任務，用來確認進度框是模態、主窗口點不到。 */
    private void devTaskDemo() {
        var dialogWindow = stage;
        com.brisklauncher.mcl.ui.TaskDialog.runVoid(dialogWindow, "任务演示",
                progress -> {
                    for (int step = 0; step <= 40; step++) {
                        progress.accept(new com.brisklauncher.mcl.game.install.InstallProgress(
                                com.brisklauncher.mcl.game.install.InstallProgress.Stage.LIBRARIES,
                                step / 40.0, "第 " + step + " / 40 步"));
                        try {
                            Thread.sleep(400L);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    return null;
                }, () -> com.brisklauncher.mcl.security.SafeLog.info("[taskdemo] 完成"));
    }

    /** 開發期：跑一次整合包導入（含進度框與失敗提示），供截圖/回歸驗證。 */
    private void devImportPack(String path) {
        var roots = context.gameRepository().roots();
        if (roots.isEmpty()) {
            com.brisklauncher.mcl.security.SafeLog.warn("[import-dev] 没有任何游戏目录");
            return;
        }
        var root = roots.get(0);
        com.brisklauncher.mcl.security.SafeLog.info("[import-dev] 导入 " + path);
        com.brisklauncher.mcl.ui.TaskDialog.run(stage, I18n.t("version.import_modpack"), progress ->
                        context.modpackInstaller().install(root, java.nio.file.Path.of(path),
                                "开发测试整合包", progress),
                entry -> com.brisklauncher.mcl.security.SafeLog.info("[import-dev] 成功 " + entry.id()));
    }

    /**
     * 开发期：重命名回归检查。
     *
     * <p>会印出「改名后 id / versionDir / json 是否存在 / 旧资料夹是否还在」，
     * 这几项正好覆盖之前「改名后 versionDir 还指向旧资料夹」的 bug。
     */
    private void devRenameCheck(String newName) {
        var roots = context.gameRepository().roots();
        if (roots.isEmpty()) {
            com.brisklauncher.mcl.security.SafeLog.warn("[rename-dev] 没有任何游戏目录");
            return;
        }
        var instances = context.gameRepository().versions(roots.get(0));
        String wanted = System.getProperty("mcl.dev.instance");
        var target = instances.stream()
                .filter(item -> wanted == null || wanted.isBlank() || wanted.equals(item.id()))
                .findFirst().orElse(null);
        if (target == null) {
            com.brisklauncher.mcl.security.SafeLog.warn("[rename-dev] 找不到实例：" + wanted);
            return;
        }
        String before = target.id();
        String beforeDir = String.valueOf(target.versionDir());
        try {
            var renamed = context.gameRepository().rename(target, newName);
            com.brisklauncher.mcl.security.SafeLog.info("[rename-dev] " + before + " → " + renamed.id());
            com.brisklauncher.mcl.security.SafeLog.info("[rename-dev] versionDir=" + renamed.versionDir()
                    + "（原 " + beforeDir + "）");
            com.brisklauncher.mcl.security.SafeLog.info("[rename-dev] json="
                    + java.nio.file.Files.isRegularFile(renamed.jsonFile())
                    + " jar=" + java.nio.file.Files.isRegularFile(renamed.jarFile())
                    + " 旧资料夹还在=" + java.nio.file.Files.exists(java.nio.file.Path.of(beforeDir))
                    + " 隔离=" + renamed.looksIsolated());
        } catch (Exception error) {
            com.brisklauncher.mcl.security.SafeLog.warn("[rename-dev] 失败："
                    + error.getClass().getSimpleName() + " · " + error.getMessage());
        }
    }

    /**
     * 启动时检查更新（只在设定页填了仓库地址、且开启自动更新时）。
     *
     * <p>只读 release 资讯，发现新版本就问要不要打开下载页；不偷偷替换启动器档案。
     */
    /**
     * 孤儿令牌清理（安全审计 v2 A-09）。
     *
     * <p>`accounts.json` 写失败时令牌可能留在凭据管理器里，账号页看不到也删不掉。
     * 这里在背景线程枚举「MCL/account/*」，与 `accounts.json` 比对，发现残留就问一次。
     */
    private void checkOrphanCredentials(javafx.stage.Stage owner) {
        Thread.ofVirtual().name("mcl-orphan-creds").start(() -> {
            try {
                var accounts = context.accounts();
                java.util.List<String> orphans = accounts.orphanTokenTargets();
                if (orphans.isEmpty()) {
                    return;
                }
                String message = com.brisklauncher.mcl.i18n.I18n.t("account.orphan.message",
                        String.valueOf(orphans.size()));
                boolean clear = com.brisklauncher.mcl.ui.MCLDialog.confirmBlocking(owner,
                        com.brisklauncher.mcl.i18n.I18n.t("account.orphan.title"), message);
                if (!clear) {
                    return;
                }
                int removed = accounts.purgeTokenTargets(orphans);
                com.brisklauncher.mcl.security.SafeLog.info("已清除 " + removed + " 个孤儿令牌");
            } catch (Throwable error) {
                com.brisklauncher.mcl.security.SafeLog.warn(
                        "孤儿令牌检查跳过：" + error.getClass().getSimpleName());
            }
        });
    }

    private void checkUpdatesOnStartup() {
        var settings = context.settings();
        if (!settings.autoUpdate() || !settings.updateConfigured()) {
            return;
        }
        Thread.ofVirtual().name("mcl-update-check").start(() -> {
            var result = com.brisklauncher.mcl.update.UpdateService.check(settings);
            if (!result.ok() || !result.updateAvailable()) {
                return;
            }
            var release = result.release();
            String notes = release.notes() == null ? "" : release.notes();
            if (notes.length() > 400) {
                notes = notes.substring(0, 400) + "…";
            }
            String body = I18n.t("update.available_body", release.version(),
                    com.brisklauncher.mcl.update.UpdateService.CURRENT_VERSION, notes);
            Platform.runLater(() -> {
                if (stage == null || !stage.isShowing()) {
                    return;
                }
                MCLDialog.confirm(stage, I18n.t("update.available_title"), body,
                        () -> com.brisklauncher.mcl.ui.DesktopActions.openUrl(release.pageUrl()));
            });
        });
    }

    /** 關掉除主視窗以外的所有視窗（彈窗/對話框），退出前呼叫。 */
    private void closeExtraWindows() {
        for (javafx.stage.Window window : new ArrayList<>(javafx.stage.Window.getWindows())) {
            if (window == stage || !window.isShowing()) {
                continue;
            }
            try {
                window.hide();
            } catch (Exception ignored) {
                // 退出流程尽力而为
            }
        }
    }

    /** 開發期：直接打開某個彈窗，方便截圖/核對佈局（--dialog=settings）。 */
    private void openDevDialog(String key) {
        var roots = context.gameRepository().roots();
        if (roots.isEmpty()) {
            return;
        }
        var instances = context.gameRepository().versions(roots.get(0));
        if (instances.isEmpty()) {
            return;
        }
        // 开发期：指定要打开哪个实例（-Dmcl.dev.instance=<版本 id>）
        String wanted = System.getProperty("mcl.dev.instance");
        if (wanted != null && !wanted.isBlank()) {
            var match = instances.stream().filter(item -> wanted.equals(item.id())).findFirst();
            if (match.isPresent()) {
                instances = java.util.List.of(match.get());
            }
        }
        String devInstall = System.getProperty("mcl.dev.installver");
        if (devInstall != null && !devInstall.isBlank()) {
            java.nio.file.Path dir = java.nio.file.Paths.get(System.getProperty("mcl.home", "."), "devtest");
            var root = new com.brisklauncher.mcl.game.GameDirectory(dir, "devtest");
            var meta = context.mojang().versions().stream()
                    .filter(item -> devInstall.equalsIgnoreCase(item.id())).findFirst().orElseThrow();
            context.versionInstaller().install(root, meta, item ->
                    com.brisklauncher.mcl.security.SafeLog.info("[dev-install] " + item.stage()
                            + " " + Math.round(item.progress() * 100) + "% " + item.detail()));
            com.brisklauncher.mcl.security.SafeLog.info("[dev-install] 完成，缺失档案："
                    + context.versionInstaller().missingFiles(
                            context.scanner().scan(root).stream()
                                    .filter(e -> devInstall.equalsIgnoreCase(e.id())).findFirst().orElseThrow()));
            javafx.application.Platform.exit();
            return;
        }
        if ("install".equals(key)) {
            com.brisklauncher.mcl.ui.InstallVersionDialog.show(stage, context);
        } else if ("settings".equals(key)) {
            com.brisklauncher.mcl.ui.VersionSettingsDialog.show(stage, context, instances.get(0), null);
        } else if ("screenshots".equals(key)) {
            com.brisklauncher.mcl.ui.ScreenshotDialog.show(stage, context, instances.get(0));
        } else if ("deps".equals(key)) {
            com.brisklauncher.mcl.ui.ModDependencyGraphDialog.show(stage, context, instances.get(0));
        } else if ("config".equals(key)) {
            devConfigPreview(instances.get(0));
        } else if ("serverpack".equals(key)) {
            com.brisklauncher.mcl.ui.ServerPackDialog.show(stage, context, instances.get(0));
        } else if ("upgrade".equals(key)) {
            com.brisklauncher.mcl.ui.ModUpgradeDialog.show(stage, context, instances.get(0));
        } else if ("crash".equals(key)) {
            devCrashPreview(instances.get(0));
        } else if ("progress".equals(key)) {
            devProgressPreview();
        }
    }

    /**
     * 开发期：用假的阶段进度打开多进度条视窗，核对「下载中 / 安装中」徽章与各栏宽度。
     *
     * <p>时间配置：每个阶段 10 步 × 120ms。搭配 {@code -Dmcl.dev.screenshotDelay}
     * 可以在不同阶段截图（3000 = 下载中、6600 = 安装中）。
     */
    private void devProgressPreview() {
        com.brisklauncher.mcl.game.install.InstallProgress.Stage[] stages = {
                com.brisklauncher.mcl.game.install.InstallProgress.Stage.RESOLVING,
                com.brisklauncher.mcl.game.install.InstallProgress.Stage.VERSION_JSON,
                com.brisklauncher.mcl.game.install.InstallProgress.Stage.CLIENT_JAR,
                com.brisklauncher.mcl.game.install.InstallProgress.Stage.LIBRARIES,
                com.brisklauncher.mcl.game.install.InstallProgress.Stage.ASSETS,
                com.brisklauncher.mcl.game.install.InstallProgress.Stage.JAVA,
                com.brisklauncher.mcl.game.install.InstallProgress.Stage.LOADER,
                com.brisklauncher.mcl.game.install.InstallProgress.Stage.FINISHING};
        String[] details = {
                "解析版本资讯", "写入 1.21.1.json", "1/1 · client-1.21.1.jar · 18.4 MB/s",
                "43/78 · sodium-fabric-0.6.13.jar · 6.2 MB/s",
                "1821/3911 · 0f/3c/0f3c9f2a1b4c5d6e7f8091a2b3c4d5e6f708192a · 3.7 MB/s",
                "Java 21 · Temurin 21.0.4", "下载 fabric-loader-0.19.5", "建立版本隔离目录"};
        com.brisklauncher.mcl.ui.MultiProgressDialog.run(stage, "开发预览 · 安装进度",
                progress -> {
                    for (int s = 0; s < stages.length; s++) {
                        for (int step = 1; step <= 10; step++) {
                            progress.accept(new com.brisklauncher.mcl.game.install.InstallProgress(
                                    stages[s], step / 10.0, details[s]));
                            Thread.sleep(120);
                        }
                    }
                }, () -> {
                });
    }

    /**
     * 开发期：打开设定档编辑器。
     *
     * <p>{@code -Dmcl.dev.configpath=<绝对路径>} 直接开那个档；
     * 否则找该实例 {@code config/} 下第一个可编辑的设定档。
     */
    private void devConfigPreview(com.brisklauncher.mcl.game.GameInstance entry) {
        String explicit = System.getProperty("mcl.dev.configpath");
        if (explicit != null && !explicit.isBlank()) {
            java.nio.file.Path path = java.nio.file.Path.of(explicit.trim());
            if (java.nio.file.Files.isRegularFile(path)) {
                com.brisklauncher.mcl.ui.ConfigEditorDialog.show(stage, path);
                return;
            }
        }
        java.nio.file.Path dir = entry.gameDir().resolve("config");
        java.util.List<java.nio.file.Path> files = new java.util.ArrayList<>();
        if (java.nio.file.Files.isDirectory(dir)) {
            try (var stream = java.nio.file.Files.list(dir)) {
                stream.filter(java.nio.file.Files::isRegularFile)
                        .filter(path -> {
                            String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                            return name.endsWith(".json") || name.endsWith(".toml")
                                    || name.endsWith(".properties") || name.endsWith(".cfg");
                        })
                        .sorted()
                        .forEach(files::add);
            } catch (Exception ignored) {
                // 读不到就当没有
            }
        }
        if (files.isEmpty()) {
            com.brisklauncher.mcl.ui.MCLDialog.info(stage, I18n.t("config.title"),
                    "config 目录下没有可编辑的设定档（可用 -Dmcl.dev.configpath 指定）");
            return;
        }
        com.brisklauncher.mcl.ui.ConfigEditorDialog.show(stage, files.get(0));
    }

    /**
     * 开发期：造一条「启动 → 崩溃」的时间线并打开崩溃分析弹窗（截图/回归验证用）。
     *
     * <p>会读取该实例 {@code logs/latest.log} 里的真实内容做规则匹配，
     * 所以验证的是真正的分析链路，不是写死的假数据。
     */
    private void devCrashPreview(com.brisklauncher.mcl.game.GameInstance entry) {
        // 开发期：直接分析指定的日志档（截图/回归验证「缺少前置」用）
        String devLog = System.getProperty("mcl.dev.crashlog");
        if (devLog != null && !devLog.isBlank()) {
            try {
                String text = java.nio.file.Files.readString(java.nio.file.Path.of(devLog.trim()));
                var analysis = context.crashRules().analyzeHaystack(text,
                        entry.gameDir(), null, true);
                MCLDialog.crash(stage, analysis, () -> { });
            } catch (Exception error) {
                com.brisklauncher.mcl.security.SafeLog.warn("dev crashlog 读取失败："
                        + error.getMessage());
            }
            return;
        }
        long now = System.currentTimeMillis();
        // 用日志第一行的时间当参照，让「启动器启动 → 崩溃」的时间轴跟日志时间对得上
        long logStart = now - 34_000L;
        try {
            java.nio.file.Path log = entry.gameDir().resolve("logs").resolve("latest.log");
            if (java.nio.file.Files.isRegularFile(log)) {
                String firstLine = java.nio.file.Files.readAllLines(log).stream()
                        .findFirst().orElse("");
                java.util.regex.Matcher matcher = java.util.regex.Pattern
                        .compile("^\\[(\\d{2}:\\d{2}:\\d{2})\\]").matcher(firstLine);
                if (matcher.find()) {
                    java.time.LocalDate today = java.time.LocalDate.now();
                    logStart = java.time.LocalDateTime.of(today,
                                    java.time.LocalTime.parse(matcher.group(1)))
                            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                }
            }
        } catch (Exception ignored) {
            // 读不到就用「现在 - 34 秒」
        }
        com.brisklauncher.mcl.crash.LaunchTimeline.beginLaunchAt(entry.id(),
                logStart, logStart + 400L);
        long base = logStart;
        long[] steps = {300, 500, 900, 1600, 2400, 3100, 4000, 5200, 9000,
                15000, 18000, 21000, 22500, 23000};
        String[] titles = {"账号就绪", "显示启动遮罩",
                "准备启动环境", "校验游戏文件", "补全缺失文件", "检查 Java 运行时",
                "组装启动参数", "启动游戏进程", "检测到游戏窗口", "正在加载 Mod",
                "NoClassDefFoundError（类缺失）", "游戏异常退出", "读取崩溃报告", "分析完成"};
        com.brisklauncher.mcl.crash.LaunchTimeline.Level[] levels = {
                com.brisklauncher.mcl.crash.LaunchTimeline.Level.INFO,
                com.brisklauncher.mcl.crash.LaunchTimeline.Level.OK,
                com.brisklauncher.mcl.crash.LaunchTimeline.Level.INFO,
                com.brisklauncher.mcl.crash.LaunchTimeline.Level.INFO,
                com.brisklauncher.mcl.crash.LaunchTimeline.Level.INFO,
                com.brisklauncher.mcl.crash.LaunchTimeline.Level.INFO,
                com.brisklauncher.mcl.crash.LaunchTimeline.Level.INFO,
                com.brisklauncher.mcl.crash.LaunchTimeline.Level.INFO,
                com.brisklauncher.mcl.crash.LaunchTimeline.Level.INFO,
                com.brisklauncher.mcl.crash.LaunchTimeline.Level.OK,
                com.brisklauncher.mcl.crash.LaunchTimeline.Level.INFO,
                com.brisklauncher.mcl.crash.LaunchTimeline.Level.ERROR,
                com.brisklauncher.mcl.crash.LaunchTimeline.Level.ERROR,
                com.brisklauncher.mcl.crash.LaunchTimeline.Level.WARN,
                com.brisklauncher.mcl.crash.LaunchTimeline.Level.INFO};
        for (int index = 0; index < steps.length; index++) {
            com.brisklauncher.mcl.crash.LaunchTimeline.addAt(base + steps[index], levels[index],
                    titles[index], null);
        }
        com.brisklauncher.mcl.crash.LaunchTimeline.absorbLog(
                entry.gameDir().resolve("logs").resolve("latest.log"), logStart - 1000L);
        var analysis = context.crashRules().analyze(entry);
        com.brisklauncher.mcl.ui.MCLDialog.crash(stage, analysis, null);
    }

    /** 開發期驗證語言熱切換：延遲觸發真正的設定寫入 + UI 重建流程。 */
    private void scheduleLanguageSwitch(String tag) {
        javafx.animation.PauseTransition delay =
                new javafx.animation.PauseTransition(Duration.millis(2500));
        delay.setOnFinished(event -> context.updateLanguage(tag));
        delay.play();
    }

    /** 开发期截图（验证圆角/布局/多语言），不影响正常启动。 */
    private void captureLater(java.nio.file.Path file) {
        long delayMillis = 6000L;
        String custom = System.getProperty("mcl.dev.screenshotDelay");
        if (custom != null && !custom.isBlank()) {
            try {
                delayMillis = Math.max(1000L, Long.parseLong(custom.trim()));
            } catch (NumberFormatException ignored) {
                // 解析不了就用预设 6 秒
            }
        }
        javafx.animation.PauseTransition delay =
                new javafx.animation.PauseTransition(Duration.millis(delayMillis));
        delay.setOnFinished(event -> {
            try {
                // 有弹窗时拍弹窗：弹窗是独立 Stage，主视窗的 snapshot 拍不到它
                javafx.scene.Scene targetScene = scene;
                for (javafx.stage.Window window : javafx.stage.Window.getWindows()) {
                    if (window.isShowing() && window != stage && window.getScene() != null) {
                        targetScene = window.getScene();
                    }
                }
                javafx.scene.image.WritableImage image = new javafx.scene.image.WritableImage(
                        Math.max(1, (int) targetScene.getWidth()),
                        Math.max(1, (int) targetScene.getHeight()));
                targetScene.snapshot(image);
                java.awt.image.BufferedImage buffered = new java.awt.image.BufferedImage(
                        (int) image.getWidth(), (int) image.getHeight(),
                        java.awt.image.BufferedImage.TYPE_INT_ARGB);
                var reader = image.getPixelReader();
                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        buffered.setRGB(x, y, reader.getArgb(x, y));
                    }
                }
                java.nio.file.Files.createDirectories(file.getParent());
                javax.imageio.ImageIO.write(buffered, "png", file.toFile());
                System.out.println("[screenshot] " + file);
            } catch (Exception e) {
                System.out.println("[screenshot] failed: " + e);
            } finally {
                closeExtraWindows();
                Platform.exit();
            }
        });
        delay.play();
    }

    /** 主题包给了 background.* 就铺到底层（页面卡片仍不透明，不影响可读性）。 */
    private void applyThemeBackground() {
        if (sceneRoot == null) {
            return;
        }
        applyBackdrop(sceneRoot);
    }

    /**
     * 把主题背景铺到窗口最底层，并把窗口内容裁成圆角。
     *
     * <p>静态方法：设置页切换主题时直接调用（热重载），不需要重启。
     *
     * @param root 主容器（scene 的 root，StackPane）
     */
    public static void applyBackdrop(javafx.scene.layout.StackPane root) {
        if (root == null) {
            return;
        }
        var background = com.brisklauncher.mcl.setting.Packs.currentThemeBackground();
        // 找一个已有的底图层（用 styleClass 标记，重建 UI 后也能复用）
        javafx.scene.layout.Region backdrop = null;
        for (javafx.scene.Node node : root.getChildren()) {
            if (node.getStyleClass().contains("mcl-backdrop")) {
                backdrop = (javafx.scene.layout.Region) node;
                break;
            }
        }
        if (background == null) {
            if (backdrop != null) {
                backdrop.setVisible(false);
                backdrop.setClip(null);
            }
            root.setClip(null);
            for (javafx.scene.Node node : root.getChildren()) {
                if (node.getStyleClass().contains("root-pane")) {
                    // 回到默认主题：恢复内容层自己的背景与去掉圆角裁剪
                    node.setStyle("");
                    node.setClip(null);
                }
            }
            return;
        }
        if (backdrop == null) {
            backdrop = new javafx.scene.layout.Region();
            backdrop.getStyleClass().add("mcl-backdrop");
            backdrop.setMouseTransparent(true);
            root.getChildren().add(0, backdrop);
        }
        backdrop.setVisible(true);
        backdrop.setStyle("-fx-background-image: url('" + background.toUri() + "');"
                + " -fx-background-size: cover; -fx-background-position: center;");
        // 最外层容器也裁圆角：只裁内容层/底图的话，四角仍会露出底下的填充色（肉色）。
        javafx.scene.shape.Rectangle rootClip = new javafx.scene.shape.Rectangle();
        rootClip.setArcWidth(44);
        rootClip.setArcHeight(44);
        rootClip.widthProperty().bind(root.layoutBoundsProperty().map(javafx.geometry.Bounds::getWidth));
        rootClip.heightProperty().bind(root.layoutBoundsProperty().map(javafx.geometry.Bounds::getHeight));
        root.setClip(rootClip);

        // 背景图本身也要裁圆角：-fx-background-radius 只圆化底色，不会裁掉图片，
        // 所以四角会露出直角（之前的截图就是这样）。这里直接给底图层加圆角 clip。
        javafx.scene.shape.Rectangle backdropClip = new javafx.scene.shape.Rectangle();
        backdropClip.setArcWidth(44);
        backdropClip.setArcHeight(44);
        backdropClip.widthProperty().bind(backdrop.layoutBoundsProperty()
                .map(javafx.geometry.Bounds::getWidth));
        backdropClip.heightProperty().bind(backdrop.layoutBoundsProperty()
                .map(javafx.geometry.Bounds::getHeight));
        backdrop.setClip(backdropClip);
        // 内层的不透明背景要变透明（否则底图被整块盖住），并把内容裁成圆角：
        // 顶栏是渐变填充，只把底图做成圆角没用，圆角要裁在内容层上。
        for (javafx.scene.Node node : root.getChildren()) {
            if (!node.getStyleClass().contains("root-pane")) {
                continue;
            }
            node.setStyle("-fx-background-color: transparent;");
            javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
            clip.setArcWidth(44);
            clip.setArcHeight(44);
            clip.widthProperty().bind(node.layoutBoundsProperty().map(javafx.geometry.Bounds::getWidth));
            clip.heightProperty().bind(node.layoutBoundsProperty().map(javafx.geometry.Bounds::getHeight));
            node.setClip(clip);
        }
    }

    /** 切换主题后热重载样式与背景（不重启）。 */
    public static void refreshTheme(javafx.scene.Scene scene) {
        if (scene == null) {
            return;
        }
        scene.getStylesheets().removeIf(sheet -> sheet.contains("/themes/"));
        com.brisklauncher.mcl.ui.UiKit.applyTheme(scene);
        if (scene.getRoot() instanceof javafx.scene.layout.StackPane root) {
            applyBackdrop(root);
        }
    }

    private void buildUi() {
        startupOverlay = new StartupOverlay();

        // 页面懒加载：启动时只创建启动页，下载/版本/联机/账户/设置首次进入时才构建
        pageSuppliers.put("launch", () -> new LaunchPage(context, startupOverlay,
                this::showPage, slug -> {
                    showPage("download");
                    if (downloadPage != null) {
                        downloadPage.searchMod(slug);
                    }
                }));
        pageSuppliers.put("download", () -> {
            downloadPage = new DownloadPage(context);
            return downloadPage;
        });
        pageSuppliers.put("version", () -> new VersionPage(context));
        pageSuppliers.put("online", () -> new OnlinePage(context));
        pageSuppliers.put("account", () -> new AccountPage(context));
        pageSuppliers.put("tools", () -> new com.brisklauncher.mcl.ui.ToolsPage(context));
        pageSuppliers.put("settings", () -> new SettingsPage(context));

        centerLayer = new StackPane();
        Node initialPage = page(currentPageKey);
        if (initialPage == null) {
            initialPage = page("launch");
        }
        centerLayer.getChildren().addAll(initialPage, startupOverlay);
        if (initialPage instanceof MclPage page) {
            page.onShown();
        }

        topBar = new TopBar(stage, currentPageKey, this::showPage, context);
        topBar.setOnMinimizeRequested(() -> UiTransitions.fadeOut(
                sceneRoot, Duration.millis(150), () -> {
                    stage.setIconified(true);
                    sceneRoot.setOpacity(1);
                }));
        topBar.setOnCloseRequested(() -> {
            if (stage.getScene() != null) {
                stage.fireEvent(new javafx.stage.WindowEvent(stage,
                        javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST));
            }
        });

        BorderPane root = new BorderPane();
        root.setTop(topBar);
        root.setCenter(centerLayer);
        root.getStyleClass().add("root-pane");

        sceneRoot.getChildren().setAll(root);
        com.brisklauncher.mcl.ui.WindowResizer.install(stage);
        transitioning = false;
    }

    /** 语言切换：先释放旧顶栏与旧页面，再按新语言重建，避免两套 UI 同时存在。 */
    private void rebuildUi() {
        if (topBar != null) {
            topBar.dispose();
            topBar = null;
        }
        releaseAllPages();
        buildUi();
    }

    private void releaseAllPages() {
        for (String key : new ArrayList<>(pageCache.keySet())) {
            releasePage(key);
        }
        if (centerLayer != null) {
            centerLayer.getChildren().clear();
        }
        if (startupOverlay != null) {
            startupOverlay.dispose();
        }
        downloadPage = null;
        startupOverlay = null;
    }

    private void releasePage(String key) {
        Lazy<Node> lazy = pageCache.remove(key);
        if (lazy == null) {
            return;
        }
        Node node = lazy.peek();
        if (node instanceof MclPage page) {
            page.onReleased();
        }
        lazy.clear();
        if ("download".equals(key)) {
            downloadPage = null;
        }
    }

    private Node showPage(String key) {
        if (transitioning) {
            return currentPage();
        }
        Node next = page(key);
        if (next == null || next == currentPage()) {
            return currentPage();
        }
        transitioning = true;
        Node current = currentPage();
        topBar.select(key);
        if (current instanceof MclPage page) {
            page.onHidden();
        }
        if (next instanceof MclPage page) {
            page.onShown();
        }
        UiTransitions.crossFade(current, next, Duration.millis(120), Duration.millis(200), () -> {
            if (current != null) {
                centerLayer.getChildren().remove(current);
            }
            centerLayer.getChildren().add(0, next);
            String oldKey = currentPageKey;
            currentPageKey = key;
            // 只缓存启动页；其它页面离开后立即释放，避免切页内存越堆越高
            if (oldKey != null && !oldKey.equals(key) && !"launch".equals(oldKey)) {
                releasePage(oldKey);
            }
            transitioning = false;
        });
        return next;
    }

    private Node page(String key) {
        Lazy<Node> cached = pageCache.get(key);
        if (cached == null) {
            Supplier<Node> supplier = pageSuppliers.get(key);
            if (supplier == null) {
                return null;
            }
            cached = Lazy.of(supplier);
            pageCache.put(key, cached);
        }
        return cached.get();
    }

    private Node currentPage() {
        if (centerLayer == null || centerLayer.getChildren().isEmpty()) {
            return null;
        }
        Node first = centerLayer.getChildren().get(0);
        return first == startupOverlay && centerLayer.getChildren().size() > 1
                ? centerLayer.getChildren().get(1)
                : first;
    }

    @Override
    public void stop() {
        instance = null;
        if (context != null) {
            context.shutdown();
        }
    }

    /** 跳到「設置」頁（微軟登錄彈窗里的「打開設置填 Client ID」用）。 */
    public static void navigateToSettings() {
        MclApplication app = instance;
        if (app == null) {
            return;
        }
        Platform.runLater(() -> app.showPage("settings"));
    }

    /** 跳到「下載」頁的某個欄目（例如 mod）。 */
    public static void navigateToDownloadCategory(String categoryKey) {
        MclApplication app = instance;
        if (app == null) {
            return;
        }
        Platform.runLater(() -> {
            app.showPage("download");
            if (app.downloadPage != null) {
                app.downloadPage.openCategory(categoryKey);
            } else {
                Platform.runLater(() -> {
                    if (app.downloadPage != null) {
                        app.downloadPage.openCategory(categoryKey);
                    }
                });
            }
        });
    }

    /**
     * 从任意弹窗跳到「下载」页并自动搜索（前置依赖图的气泡点击、缺失前置一键下载都用它）。
     *
     * <p>必须在 FX 执行绪外呼叫也不会出问题：内部自己排到 FX 执行绪。
     */
    public static void navigateToDownloadSearch(String query) {
        navigateToDownloadSearch(query, null);
    }

    /**
     * 同上，并指定要下载到哪个实例（前置依赖图点某个缺失前置时，目标就是那个实例）。
     */
    public static void navigateToDownloadSearch(String query, String instanceId) {
        MclApplication app = instance;
        if (app == null) {
            return;
        }
        Platform.runLater(() -> {
            app.showPage("download");
            if (app.downloadPage != null) {
                app.downloadPage.searchMod(query, instanceId);
            } else {
                Platform.runLater(() -> {
                    if (app.downloadPage != null) {
                        app.downloadPage.searchMod(query, instanceId);
                    }
                });
            }
        });
    }

    private static void applyStyle(StackPane root) {
        root.getStyleClass().add("app-root");
    }

    public static void main(String[] args) {
        // 必须在 JNA 加载前把原生库目录指到可写位置（jlink 镜像里 java.io.tmpdir 可能不可写）
        com.brisklauncher.mcl.security.NativeSetup.prepare();
        // JavaFX 默认把 prism/glass 原生库解压到 %USERPROFILE%\.openjfx；
        // 改到启动器自己的 native 目录，避免在 C 盘留下缓存或被杀软/权限拦截。
        System.setProperty("javafx.cachedir",
                com.brisklauncher.mcl.AppPaths.launcherRoot().resolve("native")
                        .resolve("javafx").toString());
        for (String arg : args == null ? new String[0] : args) {
            if (arg.startsWith("--screenshot=")) {
                screenshotPath = java.nio.file.Path.of(arg.substring("--screenshot=".length()));
            } else if (arg.startsWith("--lang=")) {
                languageOverride = arg.substring("--lang=".length());
            } else if (arg.startsWith("--lang-switch=")) {
                languageSwitch = arg.substring("--lang-switch=".length());
            } else if (arg.startsWith("--page=")) {
                pageOverride = arg.substring("--page=".length());
            } else if (arg.startsWith("--dialog=")) {
                dialogOverride = arg.substring("--dialog=".length());
            } else if (arg.startsWith("--search=")) {
                searchOverride = arg.substring("--search=".length());
            }
        }
        if (languageOverride != null && !languageOverride.isBlank()) {
            I18n.setLocale(languageOverride);
        }
        if (args != null && args.length > 0 && "--credentials-test".equals(args[0])) {
            System.exit(com.brisklauncher.mcl.security.CredentialCheck.run());
        }
        // 允许用户显式指定代理；TLS 校验默认开启（insecureTls 需在 launcher.json 里显式打开）
        System.setProperty("java.net.preferIPv4Stack", "true");
        launch(args);
    }
}
