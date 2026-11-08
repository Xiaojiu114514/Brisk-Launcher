package com.brisklauncher.mcl;

import com.brisklauncher.mcl.i18n.I18n;
import com.brisklauncher.mcl.ui.AccountPage;
import com.brisklauncher.mcl.ui.DownloadPage;
import com.brisklauncher.mcl.ui.LaunchPage;
import com.brisklauncher.mcl.ui.LaunchSplash;
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
import java.util.List;
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

    static {
        // ImageIO 默认把解码缓存写到 java.io.tmpdir；%TEMP% 不可写（受限目录/安全软件）
        // 会抛 AccessDeniedException: ...\Temp\imageio*.tmp。改成纯内存缓存。
        javax.imageio.ImageIO.setUseCache(false);
        // %TEMP% 不可写时把 java.io.tmpdir 换到 data/tmp（和 HMCL 一样不依赖系统临时目录）
        com.brisklauncher.mcl.security.TempGuard.ensureWritable();
    }


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
    /** 启动动画（主界面就绪后置为 null）。 */
    private LaunchSplash splash;

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
        trace("start()：JavaFX 就绪");
        // 崩溃时间线的起点：启动器启动的那一瞬间
        com.brisklauncher.mcl.crash.LaunchTimeline.launcherStarted();
        instance = this;
        this.stage = stage;

        // 1) 先把启动动画显示出来：双击后立刻有反馈（动画马上跑），重活全部挪到背景线程
        showSplash(stage);

        // 2) 背景初始化（MclContext / 插件扫描）：做完再回 FX 线程建主界面
        Thread.ofVirtual().name("mcl-startup").start(this::initializeInBackground);
    }

    /** 启动动画：小窗 + 居中，动画立刻开始跑（只用形狀 + 时间轴，不碰网络与磁盘大IO）。 */
    private void showSplash(Stage stage) {
        trace("splash: 建 nodes");
        this.sceneRoot = new StackPane();
        this.sceneRoot.getStyleClass().add("app-root");
        this.splash = new LaunchSplash();
        this.sceneRoot.getChildren().setAll(splash);
        trace("splash: LaunchSplash 建好");

        // 380×240 留出阴影与圆角的余量（太小会把卡片下缘裁掉）
        this.scene = new Scene(sceneRoot, 380, 240);
        this.scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        trace("splash: Scene 建好");
        try {
            // 基础样式：提供 -brisk-* 设计变量
            var resource = MclApplication.class.getResource("/styles/mcl-theme.css");
            if (resource != null) {
                this.scene.getStylesheets().add(resource.toExternalForm());
            }
        } catch (Throwable ignored) {
            // 样式失败也要能看到窗口
        }
        trace("splash: 基础样式就绪");
        // 玩家自订主题（含第三方）：启动动画也要跟着换色，不能只有主介面换。
        // 用轻量版（只读 theme.txt + 列 *.css + 安全扫描），完整扫描会多花 ~0.6 秒。
        try {
            int applied = 0;
            for (String custom : com.brisklauncher.mcl.setting.ThemePacks.startupCssUris()) {
                if (!this.scene.getStylesheets().contains(custom)) {
                    this.scene.getStylesheets().add(custom);
                    applied++;
                }
            }
            trace("splash: 主题样式就绪（" + applied + " 份）");
        } catch (Throwable error) {
            // 主题坏掉不该挡住启动动画
            trace("splash: 主题样式失败 " + error.getClass().getSimpleName());
        }

        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setTitle("Brisk Launcher");
        stage.setScene(this.scene);
        stage.setResizable(false);
        stage.centerOnScreen();
        trace("splash: 准备 show()");
        stage.show();
        trace("splash: show() 返回");
        splash.play();
        closeNativeSplash();
        trace("启动动画已显示");
    }

    /**
     * 關掉 JVM {@code -splash:} 的原生啟動圖。
     *
     * <p>啟動腳本（VBS / launch.ps1 / jpackage）會用 {@code -splash:splash.gif} 讓 JVM 在
     * JavaFX 起來之前就先顯示一張圖；那張圖不歸 JavaFX 管，JavaFX 視窗出現後要自己關，
     * 否則會一直蓋在畫面上。沒有 {@code -splash}（例如從 IDE 啟動）就是 no-op。
     */
    private static void closeNativeSplash() {
        try {
            java.awt.SplashScreen nativeSplash = java.awt.SplashScreen.getSplashScreen();
            if (nativeSplash != null) {
                nativeSplash.close();
                trace("native splash: 已关闭");
            } else {
                trace("native splash: 无（没有 -splash 参数）");
            }
        } catch (Throwable ignored) {
            // AWT splash 不存在 / 关不掉都不影响启动
        }
    }

    /** 背景线程：这里做的每件事都不需要 FX 线程。 */
    private void initializeInBackground() {
        try {
            trace("构造 MclContext");
            MclContext created = new MclContext();
            trace("MclContext 完成");
            boolean running = created.alreadyRunning();
            if (!running) {
                // 插件：启动时扫一次（读 jar 头 + 每支 jar 一次 SHA-256），导航页要在建顶栏前就位
                try {
                    created.plugins().load();
                } catch (Throwable error) {
                    com.brisklauncher.mcl.security.SafeLog.warn(
                            "插件加载失败：" + error.getClass().getSimpleName());
                }
                trace("插件扫描完成");
            }

            Platform.runLater(() -> onStartupReady(created, running));
        } catch (Throwable error) {
            com.brisklauncher.mcl.security.SafeLog.warn("启动初始化失败："
                    + error.getClass().getSimpleName() + " " + error.getMessage());
            Platform.runLater(() -> onStartupFailed(error));
        }
    }

    /** 回到 FX 线程：建主界面、把启动动画换成真正的主界面。 */
    private void onStartupReady(MclContext created, boolean running) {
        trace("回到 FX 线程");
        if (running) {
            if (splash != null) {
                splash.stop();
            }
            MCLDialog.error(null, "Brisk Launcher", I18n.t("launcher.already_running"));
            created.shutdown();
            Platform.exit();
            return;
        }
        this.context = created;
        if (languageOverride != null && !languageOverride.isBlank()) {
            I18n.setLocale(languageOverride);
        } else {
            // 自动跟随系统语言（设定页可关）；没有 --language= 覆盖时生效
            I18n.setLocale(MclContext.resolveLanguage(context.settings()));
        }
        if (pageOverride != null && !pageOverride.isBlank()) {
            currentPageKey = pageOverride;
        }

        buildUi();
        trace("buildUi 完成");

        applyThemeBackground();
        // 预载样式 + 玩家自订主题（以前只加基础样式，重开启动器会「忘记」主题，要重新选一次）
        com.brisklauncher.mcl.ui.UiKit.applyTheme(scene);
        // 透明 Scene + 圆角背景 = 无边框窗口也能有舒服的圆角
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);

        stage.setResizable(true);
        stage.setMinWidth(980);
        stage.setMinHeight(640);
        // 拖拽导入（整合包 / mod / 资源包 / 光影 / 存档）
        com.brisklauncher.mcl.ui.DropImporter.install(scene, context);

        stage.setOnCloseRequest(event -> {
            event.consume();
            UiTransitions.fadeOut(sceneRoot, Duration.millis(160), () -> {
                if (splash != null) {
                    splash.stop();
                    splash = null;
                }
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

        // 主界面就位：放大到设计尺寸（启动动画一直居中），再把启动动画淡出
        stage.setWidth(1180);
        stage.setHeight(760);
        stage.centerOnScreen();
        if (splash != null) {
            sceneRoot.getChildren().add(splash);
            splash.fadeOut(() -> {
                sceneRoot.getChildren().remove(splash);
                splash = null;
            });
        }
        applyStyle(sceneRoot);
        trace("主界面可见");
        showInitialPageAfterPulse();
        // 原生启动图：按目前主题重画 splash.gif（GIF 量化要几百毫秒，等主界面出来再做）
        com.brisklauncher.mcl.ui.NativeSplashService.ensureUpToDateAsync();
        trace("原生启动图已排程");
        // 崩溃规则远端更新（网络）：等主界面出现之后再跑，别跟启动抢资源
        try {
            context.updateCrashRulesInBackground();
        } catch (Throwable ignored) {
            // 更新失败不影响启动
        }
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
     * 孤儿令牌清理（安全审计 v2 A-09）。
     *
     * <p>`accounts.json` 写失败时令牌可能留在凭据管理器里，账号页看不到也删不掉。
     * 这里在背景线程枚举「MCL/account/*」，与 `accounts.json` 比对，发现残留就问一次。
     */
    private void checkOrphanCredentials(javafx.stage.Stage owner) {
        Thread.ofVirtual().name("mcl-orphan-creds").start(() -> {
            try {
                var accounts = context.accounts();
                // 这个数据目录里一个账号都没有时不要判定孤儿：凭据管理器是按 Windows 用户存的，
                // 别的安装（旧的文件夹版 / 另一份便携 exe）的凭据会被误判成「残留」，
                // 清掉就等于让那份安装的账号掉登录（第 166 轮踩过）。
                if (accounts.listAll().isEmpty()) {
                    return;
                }
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

    /**
     * 启动时检查更新（只在开启「自动检查更新」且仓库可用时）。
     *
     * <p>发现新版本后：开了「自动下载并覆盖更新」就直接下载 + 覆盖 + 重启；
     * 没开就先确认。开发模式或安装目录不可写时不会假装更新，只会提示开下载页。
     */
    private void checkUpdatesOnStartup() {
        // 上一次自动更新留下的结果（脚本写在 <data>/updates/last-result.txt）：
        // 失败要让玩家知道；成功只记日志，不再多弹一个视窗。
        com.brisklauncher.mcl.update.UpdateInstaller.ApplyResult previous =
                com.brisklauncher.mcl.update.UpdateInstaller.consumeResult();
        if (previous != null && !previous.ok()) {
            Platform.runLater(() -> {
                if (stage == null || !stage.isShowing()) {
                    return;
                }
                MCLDialog.error(stage, I18n.t("update.section"),
                        I18n.t("update.apply_failed", previous.version(),
                                previous.message() == null ? "" : previous.message()));
            });
        }
        com.brisklauncher.mcl.update.UpdateInstaller.cleanStale();

        var settings = context.settings();
        if (!settings.autoUpdate() || !settings.updateConfigured()) {
            return;
        }
        boolean autoInstall = settings.autoInstallUpdate();
        Thread.ofVirtual().name("mcl-update-check").start(() -> {
            var result = com.brisklauncher.mcl.update.UpdateService.check(settings);
            if (!result.ok() || !result.updateAvailable()) {
                return;
            }
            var release = result.release();
            if (release.version().equals(com.brisklauncher.mcl.update.UpdateInstaller.appliedVersion())) {
                // 这个版本已经成功覆盖过，但远端还是「比 CURRENT_VERSION 新」——
                // 通常是发版时忘了改 CURRENT_VERSION。再更新一次只会无限重启，所以略过。
                com.brisklauncher.mcl.security.SafeLog.warn("[update] " + release.version()
                        + " 已套用，但 CURRENT_VERSION 仍是 "
                        + com.brisklauncher.mcl.update.UpdateService.CURRENT_VERSION + "，略过自动更新");
                return;
            }
            Platform.runLater(() -> {
                if (stage == null || !stage.isShowing()) {
                    return;
                }
                com.brisklauncher.mcl.ui.UpdateFlow.offer(stage, context, release, !autoInstall);
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
        var theme = com.brisklauncher.mcl.setting.ThemePacks.current();
        var background = theme.background();
        // 找一个已有的底图层（用 styleClass 标记，重建 UI 后也能复用）
        javafx.scene.layout.Region backdrop = findLayer(root, "mcl-backdrop");
        javafx.scene.layout.Region dimLayer = findLayer(root, "mcl-backdrop-dim");
        if (background == null) {
            if (backdrop != null) {
                backdrop.setVisible(false);
                backdrop.setClip(null);
            }
            if (dimLayer != null) {
                dimLayer.setVisible(false);
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
        String fit = theme.backgroundFit();
        String size = switch (fit == null ? "cover" : fit) {
            case "contain" -> "contain";
            case "center" -> "auto";
            default -> "cover";
        };
        backdrop.setStyle("-fx-background-image: url('" + background.toUri() + "');"
                + " -fx-background-size: " + size + ";"
                + " -fx-background-position: center;"
                + ("repeat".equals(fit) ? " -fx-background-repeat: repeat;" : " -fx-background-repeat: no-repeat;"));
        // 背景压暗：theme.json 的 backgroundDim（0–1）。画在底图之上、内容之下。
        double dim = theme.backgroundDim();
        if (dim > 0.001) {
            if (dimLayer == null) {
                dimLayer = new javafx.scene.layout.Region();
                dimLayer.getStyleClass().add("mcl-backdrop-dim");
                dimLayer.setMouseTransparent(true);
                root.getChildren().add(1, dimLayer);
            }
            dimLayer.setVisible(true);
            dimLayer.setStyle("-fx-background-color: rgba(0,0,0," + Math.min(1, dim) + ");");
        } else if (dimLayer != null) {
            dimLayer.setVisible(false);
        }
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

    /** 按 styleClass 找底图 / 压暗层（重建 UI 后也能复用同一层）。 */
    private static javafx.scene.layout.Region findLayer(javafx.scene.layout.StackPane root,
                                                       String styleClass) {
        for (javafx.scene.Node node : root.getChildren()) {
            if (node.getStyleClass().contains(styleClass) && node instanceof javafx.scene.layout.Region region) {
                return region;
            }
        }
        return null;
    }

    /** 切换主题后热重载样式与背景（不重启）。 */
    public static void refreshTheme(javafx.scene.Scene scene) {
        if (scene == null) {
            return;
        }
        // 丢缓存：使用者可能刚把档案丢进 data/themes/，按「重新扫描」就要看到
        com.brisklauncher.mcl.setting.ThemePacks.invalidate();
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

        // 插件贡献的导航页：顶栏插在「工具」与「设置」之间
        List<com.brisklauncher.mcl.ui.TopBar.NavItem> pluginNav = new ArrayList<>();
        try {
            for (var ref : context.plugins().pages()) {
                String key = com.brisklauncher.mcl.ui.PluginPage.keyOf(ref.entry(), ref.page());
                pageSuppliers.put(key,
                        () -> new com.brisklauncher.mcl.ui.PluginPage(ref.entry(), ref.page()));
                String icon = ref.page().icon();
                String label = icon == null || icon.isBlank()
                        ? ref.page().title() : icon + " " + ref.page().title();
                pluginNav.add(new com.brisklauncher.mcl.ui.TopBar.NavItem(key, label));
            }
        } catch (Throwable error) {
            com.brisklauncher.mcl.security.SafeLog.warn(
                    "插件导航页注册失败：" + error.getClass().getSimpleName());
        }

        centerLayer = new StackPane();
        trace("buildUi: centerLayer");
        Node initialPage = page(currentPageKey);
        trace("buildUi: 首屏页面（" + currentPageKey + "）");
        if (initialPage == null) {
            initialPage = page("launch");
        }
        centerLayer.getChildren().addAll(initialPage, startupOverlay);
        // 注意：首屏的 onShown 不在這裡呼叫 —— 延到「視窗真的畫出來之後」的下一個脈衝，
        // 否則掃實例 / 載皮膚那 0.5～0.8 秒會卡在「視窗還沒出現」之前（見 showInitialPageAfterPulse）。

        topBar = new TopBar(stage, currentPageKey, this::showPage, context, pluginNav);
        trace("buildUi: TopBar");
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
        showInitialPageAfterPulse();
    }

    /**
     * 首屏的 {@code onShown} 延到下一個脈衝再跑。
     *
     * <p>啟動頁要掃 {@code versions/}、讀實例設定、準備皮膚預覽，實測 0.5～0.8 秒；
     * 以前它卡在 {@code buildUi()} 裡，等於「視窗遲遲不出現」。現在先讓窗口畫出來
     * （清單暫時是空的），下一個脈衝再填資料 —— 使用者看到的是「視窗先出現、內容馬上補上」。
     */
    private void showInitialPageAfterPulse() {
        Platform.runLater(() -> {
            Node current = currentPage();
            if (current instanceof MclPage page) {
                try {
                    page.onShown();
                } catch (Throwable error) {
                    com.brisklauncher.mcl.security.SafeLog.warn(
                            "首屏初始化失败：" + error.getClass().getSimpleName());
                }
            }
            trace("首屏 onShown 完成");
        });
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
        if (!root.getStyleClass().contains("app-root")) {
            root.getStyleClass().add("app-root");
        }
    }

    /**
     * 启动耗时追踪：{@code -Dmcl.startup.trace=1} 时把各阶段打到控制台
     * （毫秒，自 JVM 启动算起），用来判断「启动慢」到底慢在哪一段。
     */
    static void trace(String what) {
        long ms = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime();
        String line = "[startup +" + ms + "ms] " + what;
        // 单 exe（winexe）没有控制台，System.out 看不到；启动卡死时正是最需要这份轨迹，
        // 所以一律写进 data/logs/launcher.log。
        com.brisklauncher.mcl.security.SafeLog.info(line);
    }

    /** 背景初始化失败：停在启动动画上把原因说清楚，然后退出（不要留一个空白窗口）。 */
    private void onStartupFailed(Throwable error) {
        if (splash != null) {
            splash.stop();
        }
        String detail = error.getClass().getSimpleName()
                + (error.getMessage() == null ? "" : "：" + error.getMessage());
        MCLDialog.error(null, "Brisk Launcher", I18n.t("splash.failed", detail));
        Platform.exit();
    }

    public static void main(String[] args) {
        // 第一行就打点：启动卡死时至少能确认「JVM 已经跑到 main」，
        // 从而区分是 JavaFX 初始化卡住、还是根本没起来（引导器/解压阶段）。
        com.brisklauncher.mcl.security.SafeLog.info("[startup] main() 进入");
        trace("main() 入口");
        // 必须在 JNA 加载前把原生库目录指到可写位置（jlink 镜像里 java.io.tmpdir 可能不可写）
        com.brisklauncher.mcl.security.NativeSetup.prepare();
        trace("NativeSetup 完成");
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
        trace("准备 launch()");
        launch(args);
    }
}
