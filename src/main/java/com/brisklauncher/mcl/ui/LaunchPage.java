package com.brisklauncher.mcl.ui;

import com.brisklauncher.mcl.MclContext;
import com.brisklauncher.mcl.auth.api.AccountType;
import com.brisklauncher.mcl.auth.api.GameSession;
import com.brisklauncher.mcl.auth.AccountStore;
import com.brisklauncher.mcl.game.GameInstance;
import com.brisklauncher.mcl.game.GameDirectory;
import com.brisklauncher.mcl.game.InstanceSettings;
import com.brisklauncher.mcl.crash.CrashAnalysis;
import com.brisklauncher.mcl.i18n.I18n;
import com.brisklauncher.mcl.game.launch.RunningGames;
import com.brisklauncher.mcl.modpack.ModpackIndex;
import com.brisklauncher.mcl.security.SafeLog;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.awt.Desktop;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 启动页。
 *
 * <p>左侧是多版本文件夹与实例列表，右侧是皮肤衣橱 + 当前实例 + 启动按钮。
 */
public final class LaunchPage extends HBox implements MclPage {

    private final MclContext context;
    private final StartupOverlay overlay;
    private final Consumer<String> navigate;
    private final Consumer<String> openModSearch;

    private final ComboBox<GameDirectory> rootSelector = new ComboBox<>();
    private final ListView<GameInstance> instanceList = new ListView<>();
    private final StackPane rightPane = new StackPane();
    private final SkinLibraryPane skinPane;
    private final VBox javaPanel;
    private final Label instanceLabel = new Label(I18n.t("launch.instance.none"));
    private final Label accountLabel = new Label(I18n.t("account.no_current"));
    private final Label metaLabel = new Label("—");
    private final Button launchButton = new Button(I18n.t("launch.button.start"));
    /** 启动按钮旁的快捷版本管理按钮（选中实例后启用）。 */
    private Button quickSettingsButton;
    private final Label runningLabel = new Label();
    private final Button stopAllButton = new Button(I18n.t("running.stop_all"));
    private final Label statusLabel = new Label();
    private final Button repairButton = new Button(I18n.t("launch.repair"));
    private final Button patchButton = new Button(I18n.t("launch.skin.download"));
    private VBox runningBox;
    private final Map<String, Boolean> completeness = new HashMap<>();

    private GameInstance selected;
    private Timeline poller;
    private Runnable exitUnsubscribe;
    private Runnable accountUnsubscribe;
    private Runnable iconUnsubscribe;
    /** 本次启动的时间戳：崩溃分析只看这之后的日志，避免翻出旧崩溃。 */
    private long lastLaunchStartedAt;
    private boolean released;

    public LaunchPage(MclContext context, StartupOverlay overlay, Consumer<String> navigate,
                      Consumer<String> openModSearch) {
        this.context = context;
        this.overlay = overlay;
        this.navigate = navigate;
        this.openModSearch = openModSearch;
        this.skinPane = new SkinLibraryPane(context);

        getStyleClass().add("page");
        setPadding(new Insets(18));
        setSpacing(18);

        VBox left = buildLeft();
        javaPanel = buildJavaPanel();
        rightPane.getChildren().add(javaPanel);

        ScrollPane rightScroll = new ScrollPane(rightPane);
        rightScroll.setFitToWidth(true);
        rightScroll.getStyleClass().add("edge-to-edge");
        rightScroll.setStyle("-fx-background-color: transparent;");
        HBox.setHgrow(rightScroll, Priority.ALWAYS);

        getChildren().addAll(left, rightScroll);

        exitUnsubscribe = context.gameLauncher().runningGames().addExitListener(info ->
                Platform.runLater(() -> handleGameExitSafe(info)));
        accountUnsubscribe = context.accounts().addChangeListener(() ->
                Platform.runLater(this::updateSkinTarget));
        iconUnsubscribe = context.addIconChangeListener(() -> Platform.runLater(this::reload));
        // 首次填資料交給 onShown：MclApplication 會在「主窗口畫出來之後」的下一個脈衝呼叫，
        // 這樣視窗出現不必等掃完 versions/ 與實例設定（實測省 0.5～0.8 秒）。
        // 开发期：启动页一出现就自动启动目前选中的实例（回归验证启动链路/时间线用）
        if (System.getProperty("mcl.dev.autolaunch") != null) {
            Platform.runLater(this::launchSelected);
        }
    }

    /** 監聽器在背景線程觸發，這裡再包一層以防頁面已被丟棄。 */
    private void handleGameExitSafe(RunningGames.ExitInfo info) {
        if (!released) {
            handleGameExit(info);
        }
    }

    // ---------- 左侧：实例列表 ----------

    /**
     * 下拉里显示的路径尾巴：前面用 … 代替。
     *
     * <p>如果最后一层目录名跟显示名一样（{@code minecraft} 对 {@code ...\minecraft}），
     * 就只显示上一层，避免「minecraft · …\xxx\minecraft」这种重复 + 截断。
     */
    private static String shortPath(java.nio.file.Path path, String displayName) {
        if (path == null) {
            return "";
        }
        java.nio.file.Path file = path.getFileName();
        java.nio.file.Path parent = path.getParent();
        java.nio.file.Path parentName = parent == null ? null : parent.getFileName();
        String leaf = file == null ? "" : file.toString();
        String up = parentName == null ? "" : parentName.toString();
        if (!leaf.isEmpty() && leaf.equalsIgnoreCase(displayName == null ? "" : displayName)) {
            return up.isEmpty() ? "…" : "…\\" + up;
        }
        if (up.isEmpty()) {
            return leaf;
        }
        return "…\\" + up + "\\" + leaf;
    }

    private VBox buildLeft() {
        VBox left = new VBox(10);
        left.setPrefWidth(330);
        left.setMinWidth(300);

        rootSelector.setMaxWidth(Double.MAX_VALUE);
        javafx.util.Callback<javafx.scene.control.ListView<GameDirectory>,
                ListCell<GameDirectory>> rootCells = view -> new ListCell<>() {
            @Override
            protected void updateItem(GameDirectory root, boolean empty) {
                super.updateItem(root, empty);
                // 名称后面带上对应路径：玩家经常有好几个 .minecraft，光看名字分不出来。
                // 完整路径太长会被截成 "E:\code\Bri…"，所以这里只留最后两段（tooltip 里是完整路径）。
                setText(empty || root == null ? null
                        : root.displayName() + "  ·  " + shortPath(root.path(), root.displayName()));
                setTooltip(empty || root == null ? null : new Tooltip(root.path().toString()));
                // 大版本文件夹图标（可自订，见 GameIcons；没有就显示彩色资料夹占位图）
                setGraphic(empty || root == null ? null : VersionIconView.of(root, 20));
            }
        };
        rootSelector.setCellFactory(rootCells);
        rootSelector.setButtonCell(rootCells.call(null));
        rootSelector.valueProperty().addListener((obs, oldValue, value) -> {
            if (value != null) {
                // 记住当前大版本文件夹：下载页/版本页的默认目标跟着它走
                context.uiState().rememberRoot(value.path());
                refreshInstances(value);
            }
        });

        Button addRoot = new Button("＋");
        addRoot.getStyleClass().add("icon-button");
        addRoot.setTooltip(new Tooltip(I18n.t("version.add_root")));
        addRoot.setOnAction(e -> addRoot());

        Button refresh = new Button("⟳");
        refresh.getStyleClass().add("icon-button");
        refresh.setTooltip(new Tooltip(I18n.t("common.refresh")));
        refresh.setOnAction(e -> reload());

        HBox rootRow = new HBox(6, rootSelector, addRoot, refresh);
        HBox.setHgrow(rootSelector, Priority.ALWAYS);

        instanceList.setCellFactory(view -> new InstanceCell());
        instanceList.setPlaceholder(UiKit.catHint(I18n.t("launch.empty_instances")));
        VBox.setVgrow(instanceList, Priority.ALWAYS);
        instanceList.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldValue, value) -> selectInstance(value));

        Button installNew = new Button(I18n.t("launch.install_new"));
        installNew.setOnAction(e -> navigate.accept("download"));
        Button importPack = new Button(I18n.t("launch.import_modpack"));
        importPack.setOnAction(e -> importModpack());
        javafx.scene.layout.FlowPane tools = new javafx.scene.layout.FlowPane(6, 6);
        tools.getChildren().addAll(UiKit.noShrink(installNew), UiKit.noShrink(importPack));

        launchButton.getStyleClass().add("primary-button");
        launchButton.setDisable(true);
        launchButton.setMaxWidth(Double.MAX_VALUE);
        launchButton.setOnAction(e -> launchSelected());

        statusLabel.getStyleClass().add("cat-hint");
        statusLabel.setWrapText(true);

        left.getChildren().addAll(
                UiKit.title(I18n.t("launch.title")),
                rootRow, instanceList, tools, statusLabel);
        return left;
    }

    private final class InstanceCell extends ListCell<GameInstance> {
        private final Label name = new Label();
        private final Label meta = new Label();
        private final Label badge = new Label();
        private final StackPane icon = new StackPane();
        private final HBox row;

        private InstanceCell() {
            name.getStyleClass().add("entry-id");
            meta.getStyleClass().add("instance-meta");
            badge.getStyleClass().add("cat-hint");
            name.setMaxWidth(190);
            meta.setMaxWidth(190);
            meta.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
            VBox box = new VBox(2, name, meta);
            icon.setMinSize(34, 34);
            icon.setPrefSize(34, 34);
            icon.setMaxSize(34, 34);
            row = new HBox(9, icon, box, badge);
            row.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(box, Priority.ALWAYS);
            setPrefWidth(0);
        }

        @Override
        protected void updateItem(GameInstance entry, boolean empty) {
            super.updateItem(entry, empty);
            if (empty || entry == null) {
                setGraphic(null);
                return;
            }
            name.setText(entry.id());
            meta.setText(entry.subtitle());
            // 版本图标：自订图片 → versions/<id>/icon.png；没有就用首字母占位块
            icon.getChildren().setAll(VersionIconView.of(entry, 34));
            Boolean complete = completeness.get(entry.id());
            if (complete != null && !complete) {
                badge.setText("⚠ " + I18n.t("launch.needs_repair"));
            } else {
                badge.setText("");
            }
            setGraphic(row);
        }
    }

    // ---------- 右侧：Java 面板 ----------

    private VBox buildJavaPanel() {
        VBox panel = new VBox(14);
        panel.setPadding(new Insets(6, 12, 16, 12));
        panel.setAlignment(Pos.TOP_CENTER);
        HBox.setHgrow(panel, Priority.ALWAYS);

        Button refreshSkin = new Button(I18n.t("launch.skin.refresh"));
        refreshSkin.setOnAction(e -> {
            skinPane.refresh();
            statusLabel.setText(I18n.t("skin.refreshed"));
        });
        patchButton.setOnAction(e -> openSkinPatch());
        // 卡片只有 360 宽：用 FlowPane 自动换行，避免“下载皮肤补丁”被压成省略号
        javafx.scene.layout.FlowPane skinTools = new javafx.scene.layout.FlowPane(8, 8);
        skinTools.getChildren().addAll(UiKit.noShrink(refreshSkin), UiKit.noShrink(patchButton));

        VBox skinBox = new VBox(8, skinPane, skinTools);
        skinBox.getStyleClass().add("card");
        skinBox.setPadding(new Insets(14));
        skinBox.setMaxWidth(380);
        skinBox.setPrefWidth(360);
        // 启动按钮右边放一个快捷「版本管理」，选中实例后可直接开设置弹窗
        Button quickSettings = UiKit.noShrink(new Button("⚙"));
        quickSettings.getStyleClass().add("icon-button");
        // 与「启动游戏」按钮同高，避免一高一矮
        quickSettings.setPrefHeight(44);
        quickSettings.setMinHeight(44);
        quickSettings.setPrefWidth(52);
        quickSettings.setStyle("-fx-font-size: 18px;");
        quickSettings.setTooltip(new Tooltip(I18n.t("version.settings.title")));
        quickSettings.setDisable(true);
        quickSettings.setOnAction(e -> {
            if (selected != null) {
                VersionSettingsDialog.show(getScene().getWindow(), context, selected,
                        () -> refreshInstances(selected.root()));
            }
        });
        this.quickSettingsButton = quickSettings;
        javafx.scene.layout.FlowPane launchRow = new javafx.scene.layout.FlowPane(8, 8);
        launchRow.getChildren().addAll(UiKit.noShrink(launchButton), quickSettings);
        launchButton.setPrefWidth(360);
        launchButton.setPrefHeight(44);

        VBox currentBox = new VBox(10,
                UiKit.title(I18n.t("launch.current.title")),
                instanceLabel, metaLabel, accountLabel,
                launchRow, statusLabel);
        instanceLabel.getStyleClass().add("instance-name");
        accountLabel.getStyleClass().add("cat-hint");
        currentBox.getStyleClass().add("card");
        currentBox.setPadding(new Insets(14));
        currentBox.setMaxWidth(560);
        currentBox.setPrefWidth(520);

        runningLabel.getStyleClass().add("instance-meta");
        stopAllButton.getStyleClass().addAll("button", "window-close");
        stopAllButton.setOnAction(e -> MCLDialog.confirm(getScene().getWindow(),
                I18n.t("running.stop_all"), I18n.t("running.confirm_stop_all"), () -> {
                    context.gameLauncher().runningGames().stopAll();
                    updateRunningCard();
                }));
        VBox runningBoxLocal = new VBox(8, UiKit.title(I18n.t("running.title")),
                runningLabel, stopAllButton);
        runningBoxLocal.getStyleClass().add("card");
        runningBoxLocal.setPadding(new Insets(14));
        runningBoxLocal.setMaxWidth(560);
        runningBoxLocal.setVisible(false);
        runningBoxLocal.managedProperty().bind(runningBoxLocal.visibleProperty());
        this.runningBox = runningBoxLocal;

        VBox right = new VBox(10, skinBox, currentBox, runningBoxLocal);
        right.setAlignment(Pos.TOP_CENTER);
        right.setMaxWidth(600);
        HBox.setHgrow(right, Priority.ALWAYS);

        panel.getChildren().add(right);
        return panel;
    }

    private void updateRunningCard() {
        List<RunningGames.RunningGame> games = context.gameLauncher().runningGames().list();
        if (runningBox != null) {
            runningBox.setVisible(!games.isEmpty());
        }
        if (!games.isEmpty()) {
            RunningGames.RunningGame game = games.get(0);
            runningLabel.setText(game.versionId() + " · PID " + game.pid() + " · " + game.uptime());
        }
    }

    private void startRunningPolling() {
        if (poller != null) {
            poller.play();
            return;
        }
        poller = new Timeline(new KeyFrame(Duration.seconds(2), e -> updateRunningCard()));
        poller.setCycleCount(Animation.INDEFINITE);
        poller.play();
    }

    /** 非主动停止且异常退出（或日志显示崩溃）时弹出带分析的提示。 */
    private void handleGameExit(RunningGames.ExitInfo info) {
        if (info.stoppedByUser()) {
            return;
        }
        GameInstance entry = selected != null && selected.id().equals(info.versionId())
                ? selected : findEntry(info.versionId());
        if (entry == null) {
            return;
        }
        CrashAnalysis analysis;
        try {
            analysis = context.crashRules().analyze(entry, lastLaunchStartedAt);
        } catch (Exception error) {
            SafeLog.warn("分析游戏崩溃失败：" + error.getClass().getSimpleName());
            return;
        }
        boolean abnormal = info.exitCode() != 0 || (analysis != null && analysis.crashReport() != null);
        if (!abnormal) {
            com.brisklauncher.mcl.crash.LaunchTimeline.ok("游戏正常退出", "退出码 0");
            return;
        }
        // 崩溃时间线：先补「异常退出」，再把日志里本次启动后的关键行并进来
        com.brisklauncher.mcl.crash.LaunchTimeline.error("游戏异常退出",
                "退出码 " + info.exitCode()
                        + (analysis != null && analysis.crashReport() != null
                        ? " · 已生成崩溃报告" : ""));
        com.brisklauncher.mcl.crash.LaunchTimeline.absorbLog(
                entry.gameDir().resolve("logs").resolve("latest.log"), lastLaunchStartedAt);
        javafx.stage.Window owner = getScene() == null ? null : getScene().getWindow();
        if (owner == null) {
            owner = javafx.stage.Window.getWindows().stream()
                    .filter(javafx.stage.Window::isShowing).findFirst().orElse(null);
        }
        if (owner == null) {
            return;
        }
        javafx.stage.Window finalOwner = owner;
        MCLDialog.crash(finalOwner, analysis, () -> {
            try {
                var path = context.crashExporter().export(entry, analysis);
                DesktopActions.openFolder(path.getParent());
            } catch (Exception error) {
                MCLDialog.error(finalOwner, I18n.t("crash.title"), error.getMessage());
            }
        });
    }

    private GameInstance findEntry(String versionId) {
        if (versionId == null) {
            return null;
        }
        for (GameDirectory root : context.gameRepository().roots()) {
            for (GameInstance entry : context.gameRepository().versions(root)) {
                if (versionId.equals(entry.id())) {
                    return entry;
                }
            }
        }
        return null;
    }

    // ---------- 数据刷新 ----------

    @Override
    public void onShown() {
        startRunningPolling();
        reload();
        updateSkinTarget();
    }

    /** 切走時停掉 2 秒輪詢：頁面被快取時不應該繼續吃 CPU。 */
    @Override
    public void onHidden() {
        if (poller != null) {
            poller.stop();
        }
    }

    /** 頁面被丟棄：斷開進程監聽、停輪詢、釋放 3D 與列表引用。 */
    @Override
    public void onReleased() {
        released = true;
        if (poller != null) {
            poller.stop();
            poller = null;
        }
        if (exitUnsubscribe != null) {
            exitUnsubscribe.run();
            exitUnsubscribe = null;
        }
        if (accountUnsubscribe != null) {
            accountUnsubscribe.run();
            accountUnsubscribe = null;
        }
        if (iconUnsubscribe != null) {
            iconUnsubscribe.run();
            iconUnsubscribe = null;
        }
        currentLaunch = null;
        completeness.clear();
        selected = null;
        instanceList.setItems(FXCollections.observableArrayList());
        rootSelector.setItems(FXCollections.observableArrayList());
        skinPane.release();
    }

    public void reload() {
        List<GameDirectory> roots = context.gameRepository().roots();
        GameDirectory previous = rootSelector.getValue();
        rootSelector.setItems(FXCollections.observableArrayList(roots));
        GameDirectory target = roots.stream()
                .filter(root -> previous != null && root.path().equals(previous.path()))
                .findFirst()
                .orElseGet(() -> {
                    String remembered = context.uiState().lastRoot();
                    return roots.stream()
                            .filter(root -> root.path().toString().equals(remembered))
                            .findFirst().orElse(roots.get(0));
                });
        rootSelector.setValue(target);
        if (target == previous) {
            refreshInstances(target);
        }
    }

    private void refreshInstances(GameDirectory root) {
        com.brisklauncher.mcl.security.SafeLog.info("[list] refreshInstances " + root.path());
        List<GameInstance> entries = context.gameRepository().versions(root);
        completeness.clear();
        for (GameInstance entry : entries) {
            completeness.put(entry.id(), context.gameRepository().looksComplete(entry));
        }
        String previousId = selected == null ? storedVersionId(root) : selected.id();
        instanceList.setItems(FXCollections.observableArrayList(entries));
        if (entries.isEmpty()) {
            statusLabel.setText(I18n.t("launch.empty_instances"));
            return;
        }
        GameInstance restore = entries.stream()
                .filter(entry -> entry.id().equals(previousId))
                .findFirst().orElse(entries.get(0));
        instanceList.getSelectionModel().select(restore);
    }

    private String storedVersionId(GameDirectory root) {
        return context.uiState().lastVersion(root.path());
    }

    private void selectInstance(GameInstance entry) {
        selected = entry;
        if (entry == null) {
            instanceLabel.setText(I18n.t("launch.instance.none"));
            metaLabel.setText("—");
            launchButton.setDisable(true);
            skinPane.setTarget(null, "", false);
            return;
        }
        context.uiState().rememberVersion(entry.root().path(), entry.id());
        InstanceSettings settings = context.gameRepository().settingsFor(entry);
        instanceLabel.setText(entry.id());
        metaLabel.setText(entry.subtitle() + " · " + settings.memoryMb() + " MB");
        launchButton.setDisable(false);
        if (quickSettingsButton != null) {
            quickSettingsButton.setDisable(false);
        }
        statusLabel.setText("");
        updateSkinTarget();
    }

    private void updateSkinTarget() {
        AccountStore.Account current = context.accounts().current().orElse(null);
        boolean offline = current == null || current.type() == AccountType.OFFLINE;
        // 离线账号共用本地皮肤；在线账号使用服务器材质，不自动覆盖
        skinPane.setTarget(selected, current == null ? "" : current.name(),
                offline && context.skins().hasSkin());
        skinPane.refresh();
        // 只有微软正版账号自带皮肤服务；离线/第三方（LittleSkin 等）都需要 CustomSkinLoader
        boolean needsSkinPatch = current == null || current.type() != AccountType.MICROSOFT;
        patchButton.setVisible(needsSkinPatch);
        patchButton.setManaged(needsSkinPatch);
        accountLabel.setText(current == null
                ? I18n.t("account.no_current")
                : current.name() + " · " + I18n.t("account.tab." + current.type().name().toLowerCase(java.util.Locale.ROOT)));
    }

    // ---------- 非 Java 游戏 ----------

    // ---------- 启动 ----------

    private com.brisklauncher.mcl.game.launch.LaunchHandle currentLaunch;

    private void launchSelected() {
        if (selected == null) {
            return;
        }
        if (context.onlineState().locked()) {
            MCLDialog.info(getScene().getWindow(), I18n.t("launch.title"),
                    I18n.t("online.p2p.lock_hint"));
            return;
        }
        if (context.accounts().current().isEmpty()) {
            MCLDialog.info(getScene().getWindow(), I18n.t("launch.title"),
                    I18n.t("launch.no_account"));
            return;
        }
        if (currentLaunch != null && !currentLaunch.future().isDone()) {
            MCLDialog.info(getScene().getWindow(), I18n.t("launch.title"),
                    I18n.t("launch.already_starting"));
            return;
        }
        GameInstance entry = selected;
        lastLaunchStartedAt = System.currentTimeMillis();
        com.brisklauncher.mcl.crash.LaunchTimeline.beginLaunch(entry.id());
        overlay.begin(entry.id(), () -> cancelLaunch(entry));
        overlay.updateProgress(com.brisklauncher.mcl.game.launch.LaunchProgress.PREPARING);
        com.brisklauncher.mcl.crash.LaunchTimeline.info("显示启动遮罩",
                entry.subtitle() + " · " + context.accounts().current()
                        .map(AccountStore.Account::name).orElse("未登入"));

        GameSession session;
        try {
            String uuid = context.accounts().currentUuid();
            session = uuid == null
                    ? context.accounts().fallbackSession()
                    : context.accounts().resolveSessionForLaunch(uuid);
            com.brisklauncher.mcl.crash.LaunchTimeline.ok("账号就绪", session.username());
        } catch (Exception error) {
            com.brisklauncher.mcl.crash.LaunchTimeline.error("解析账号失败", error.getMessage());
            overlay.dismiss();
            MCLDialog.error(getScene().getWindow(), I18n.t("crash.launch_failed"), error.getMessage());
            return;
        }

        com.brisklauncher.mcl.game.launch.LaunchProgress[] lastStage =
                {com.brisklauncher.mcl.game.launch.LaunchProgress.PREPARING};
        com.brisklauncher.mcl.game.launch.LaunchHandle handle =
                context.gameLauncher().launchAsync(entry, session,
                        progress -> Platform.runLater(() -> {
                            overlay.updateProgress(progress);
                            // 时间线：只记阶段变化，避免同一阶段刷屏
                            if (progress != lastStage[0]) {
                                lastStage[0] = progress;
                                com.brisklauncher.mcl.crash.LaunchTimeline.info(
                                        StartupOverlay.stageText(progress), null);
                            }
                        }));
        currentLaunch = handle;
        handle.future().whenComplete((process, error) -> Platform.runLater(() -> {
            session.wipe();
            if (currentLaunch == handle) {
                currentLaunch = null;
            }
            if (error == null) {
                com.brisklauncher.mcl.crash.LaunchTimeline.ok("游戏窗口已出现，启动完成",
                        process == null ? null : "PID " + process.pid());
                overlay.succeed(() -> { });
                statusLabel.setText(I18n.t("launch.started", entry.id()));
                updateRunningCard();
                return;
            }
            if (handle.isCancelled() || isCancelled(error)) {
                com.brisklauncher.mcl.crash.LaunchTimeline.warn("启动已取消", null);
                overlay.showCancelled();
                overlay.dismiss();
                statusLabel.setText(I18n.t("launch.cancelled"));
                updateRunningCard();
                return;
            }
            Throwable root = error;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            String message = root.getMessage() == null ? "未知错误" : root.getMessage();
            com.brisklauncher.mcl.crash.LaunchTimeline.error("启动失败", firstLine(message));
            com.brisklauncher.mcl.crash.LaunchTimeline.absorbLog(
                    entry.gameDir().resolve("logs").resolve("latest.log"), lastLaunchStartedAt);
            overlay.dismiss();
            CrashAnalysis analysis = context.crashRules().analyze(entry, lastLaunchStartedAt);
            MCLDialog.launchFailed(getScene().getWindow(), message, analysis,
                    () -> exportCrash(entry, analysis));
        }));
    }

    /** 時間線只放第一行，完整堆疊留給日誌區。 */
    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int newline = message.indexOf('\n');
        String line = newline > 0 ? message.substring(0, newline) : message;
        return line.length() > 160 ? line.substring(0, 160) + "…" : line;
    }

    private void cancelLaunch(GameInstance entry) {
        if (currentLaunch != null) {
            currentLaunch.cancel();
        }
        context.gameLauncher().runningGames().stopByVersion(entry.id());
        overlay.showCancelled();
        statusLabel.setText(I18n.t("launch.cancelling"));
    }

    private static boolean isCancelled(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 8) {
            if (current instanceof com.brisklauncher.mcl.game.launch.LaunchCancelledException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void exportCrash(GameInstance entry, CrashAnalysis analysis) {
        Path[] exported = new Path[1];
        TaskDialog.runVoid(getScene().getWindow(), I18n.t("crash.exporting"), progress -> {
            Path file = context.crashExporter().export(entry, analysis);
            exported[0] = file;
            progress.accept(new com.brisklauncher.mcl.game.install.InstallProgress(
                    com.brisklauncher.mcl.game.install.InstallProgress.Stage.DONE, 1, file.toString()));
            return null;
        }, () -> {
            MCLDialog.info(getScene().getWindow(), I18n.t("crash.exported"),
                    I18n.t("crash.exported_hint"));
            // 导出完顺手打开所在目录（PCL 也是这样）
            if (exported[0] != null && exported[0].getParent() != null) {
                DesktopActions.openFolder(exported[0].getParent());
            }
        });
    }

    // ---------- 整合包 ----------

    private void importModpack() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("launch.import_modpack"));
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Modrinth Modpack (*.mrpack, *.zip)", "*.mrpack", "*.zip"));
        File file = chooser.showOpenDialog(getScene().getWindow());
        if (file == null) {
            return;
        }
        GameDirectory root = rootSelector.getValue();
        if (root == null) {
            MCLDialog.info(getScene().getWindow(), I18n.t("version.title"), I18n.t("version.empty_roots"));
            return;
        }
        TaskDialog.run(getScene().getWindow(), I18n.t("launch.import_modpack"), progress -> {
            ModpackIndex preview = context.modpackInstaller().peek(file.toPath());
            progress.accept(new com.brisklauncher.mcl.game.install.InstallProgress(
                    com.brisklauncher.mcl.game.install.InstallProgress.Stage.RESOLVING, 0.01, preview.displayName()));
            return context.modpackInstaller().install(root, file.toPath(), progress);
        }, entry -> {
            reload();
            instanceList.getItems().stream()
                    .filter(item -> item.id().equals(entry.id()))
                    .findFirst()
                    .ifPresent(item -> instanceList.getSelectionModel().select(item));
            MCLDialog.info(getScene().getWindow(), I18n.t("launch.import_modpack"),
                    I18n.t("launch.import_done", entry.id()));
        });
    }

    // ---------- 杂项 ----------

    private void addRoot() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.t("version.choose_root"));
        File dir = chooser.showDialog(getScene().getWindow());
        if (dir == null) {
            return;
        }
        context.gameRepository().addRoot(dir.toPath());
        reload();
    }

    private void openSkinPatch() {
        MCLDialog.confirm(getScene().getWindow(), I18n.t("launch.skin.download"),
                I18n.t("skin.patch_confirm"), () -> {
                    if (openModSearch != null) {
                        openModSearch.accept("customskinloader");
                    } else {
                        openUrl("https://modrinth.com/mod/customskinloader");
                    }
                });
    }

    private static void openUrl(String url) {
        DesktopActions.openUrl(url);
    }

    private static void openFolder(Path path) {
        DesktopActions.openFolder(path);
    }
}
