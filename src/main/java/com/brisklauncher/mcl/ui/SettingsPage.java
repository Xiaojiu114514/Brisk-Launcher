package com.brisklauncher.mcl.ui;

import com.brisklauncher.mcl.MclContext;
import com.brisklauncher.mcl.i18n.I18n;
import com.brisklauncher.mcl.java.JdkInfo;
import com.brisklauncher.mcl.setting.LauncherSettings;
import com.brisklauncher.mcl.setting.Packs;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 设置页（开发方案 5.9）：下载源 / 并发 / 重试 / 超时 / 语言 / 内存 / 代理 /
 * Java 管理 / 崩溃规则更新 / FRP 路径 / 关于。
 *
 * <p>保存后 MclContext 会重建下载器与安装器，新的并发/镜像立即生效。
 */
public final class SettingsPage extends VBox implements MclPage {

    private final MclContext context;

    private final ComboBox<String> source = new ComboBox<>();
    private final TextField customMirror = new TextField();
    private final Spinner<Integer> concurrency = new Spinner<>(1, 32, 4);
    private final Spinner<Integer> retries = new Spinner<>(0, 10, 3);
    private final Spinner<Integer> timeout = new Spinner<>(5, 300, 30);
    private final Spinner<Integer> memory = new Spinner<>(512, 65536, 4096, 256);
    private final ComboBox<String> language = new ComboBox<>();
    private final ComboBox<String> theme = new ComboBox<>();
    /** 主题详情（缩图 + 说明 + 作者/版本 + 警告）。 */
    private final Label themeDesc = new Label();
    private final Label themeMeta = new Label();
    private final Label themeWarnings = new Label();
    private final javafx.scene.image.ImageView themePreview = new javafx.scene.image.ImageView();
    private final VBox themeInfo = new VBox(4);
    private final TextField proxy = new TextField();
    private final CheckBox insecureTls = new CheckBox(I18n.t("settings.insecure_tls"));
    private final CheckBox optimizeMemory = new CheckBox(I18n.t("settings.optimize_memory"));
    private final CheckBox crashRemote = new CheckBox(I18n.t("settings.crash.remote_update"));
    private final CheckBox autoUpdate = new CheckBox(I18n.t("update.auto"));
    private final CheckBox autoInstall = new CheckBox(I18n.t("update.auto_install"));
    private final ComboBox<String> updateChannel = new ComboBox<>();

    private final TextField frpPath = new TextField();
    private final CheckBox autoLanguage = new CheckBox(I18n.t("settings.auto_language"));
    private final TextField microsoftClientId = new PasswordField();
    /** CurseForge API key（遮蔽显示；空 = 走镜像源）。 */
    private final PasswordField curseforgeApiKey = new PasswordField();
    /** 「当前生效的客户端 ID 来自哪里」——避免发版时拿错 ID / 排查登录问题。 */
    private final Label microsoftClientIdSource = new Label();
    private final VBox jdkList = new VBox(4);
    private final Label status = new Label();

    public SettingsPage(MclContext context) {
        this.context = context;
        getStyleClass().add("page");
        setPadding(new Insets(18));
        setSpacing(12);

        for (LauncherSettings.Source value : LauncherSettings.Source.values()) {
            if (!value.selectable()) {
                continue;
            }
            source.getItems().add(value.display());
        }
        for (String tag : I18n.supportedLocales()) {
            language.getItems().add(I18n.displayName(tag));
        }

        source.valueProperty().addListener((obs, oldValue, value) ->
                customMirror.setDisable(!"Custom".equals(value)));

        // 主题包：内建 default + data/themes/*，切换即时生效（格式见 THEME_DEV.md）
        initThemeSection();
        // 语言选择立即生效并持久化（不需要再点保存）
        language.valueProperty().addListener((obs, oldValue, value) -> {
            if (value == null) {
                return;
            }
            for (String tag : I18n.supportedLocales()) {
                if (I18n.displayName(tag).equals(value)) {
                    context.updateLanguage(tag);
                    return;
                }
            }
        });

        Button save = new Button(I18n.t("common.confirm"));
        save.getStyleClass().add("primary-button");
        save.setOnAction(e -> saveSettings());

        status.getStyleClass().add("cat-hint");
        HBox header = new HBox(12, UiKit.title(I18n.t("settings.title") + " ⚡"), save, status);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(14,
                downloadCard(), appearanceCard(), networkCard(), updateCard(), javaCard(),
                otherCard(), aboutCard());
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("edge-to-edge");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        getChildren().addAll(header, scroll);
        loadSettings();
        refreshJdkList();
        // 开发期：直接把设置页滚到某个卡片（截图验证用）
        String devSection = System.getProperty("mcl.dev.settingssection");
        if (devSection != null && !devSection.isBlank()) {
            double ratio = switch (devSection.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "appearance" -> 0.16;
                case "network" -> 0.30;
                case "update" -> 0.46;
                case "java" -> 0.60;
                case "other" -> 0.74;
                case "about" -> 1.0;
                default -> 0.0;
            };
            Platform.runLater(() -> scroll.setVvalue(ratio));
        }
    }

    @Override
    public void onShown() {
        loadSettings();
        refreshJdkList();
    }

    // ---------- 卡片 ----------

    private VBox downloadCard() {
        GridPane grid = grid();
        grid.addRow(0, new Label(I18n.t("settings.mirror")), source);
        grid.addRow(1, new Label(I18n.t("settings.mirror.custom")), customMirror);
        grid.addRow(2, new Label(I18n.t("settings.concurrency")), concurrency);
        grid.addRow(3, new Label(I18n.t("settings.retries")), retries);
        grid.addRow(4, new Label(I18n.t("settings.timeout")), timeout);
        HBox.setHgrow(source, Priority.ALWAYS);
        HBox.setHgrow(customMirror, Priority.ALWAYS);
        return card(I18n.t("settings.section.download"), grid);
    }

    private VBox appearanceCard() {
        GridPane grid = grid();
        // 语言下拉显示「实际生效的语言」（自动跟随时就是系统判断的结果）
        String effectiveLang = com.brisklauncher.mcl.i18n.I18n.currentLocaleTag();
        if (effectiveLang != null && !effectiveLang.isBlank()) {
            language.setValue(com.brisklauncher.mcl.i18n.I18n.displayName(effectiveLang));
        }
        autoLanguage.setSelected(context.settings().autoLanguage());
        // 自动跟随系统语言（预设开）：勾选时语言下拉仅作预览
        autoLanguage.selectedProperty().addListener((obs, old, now) -> language.setDisable(now));
        grid.addRow(0, new Label(I18n.t("settings.language")), language);
        grid.addRow(3, new Label(""), autoLanguage);
        grid.addRow(1, new Label(I18n.t("settings.theme")), theme);
        Button openLang = new Button(I18n.t("settings.open_lang_dir"));
        openLang.setOnAction(e -> openFolder(Packs.langDir()));
        Button openTheme = new Button(I18n.t("settings.open_theme_dir"));
        openTheme.setOnAction(e -> openFolder(Packs.themeDir()));
        Button rescanTheme = new Button(I18n.t("settings.theme_refresh"));
        rescanTheme.setOnAction(e -> {
            com.brisklauncher.mcl.setting.ThemePacks.invalidate();
            reloadThemes();
        });
        HBox actions = new HBox(8, openLang, openTheme, rescanTheme);
        grid.addRow(2, new Label(""), actions);
        HBox.setHgrow(language, Priority.ALWAYS);
        HBox.setHgrow(theme, Priority.ALWAYS);
        Label hint = UiKit.catHint(I18n.t("settings.packs_hint"));
        hint.setWrapText(true);
        return card(I18n.t("settings.section.appearance"),
                new VBox(8, grid, themeInfo, hint));
    }

    // ---------- 主题包 ----------

    /** 填下拉 + 接上「切换 / 说明 / 缩图」逻辑（只需要做一次）。 */
    private void initThemeSection() {
        themeDesc.getStyleClass().add("cat-hint");
        themeDesc.setWrapText(true);
        themeMeta.getStyleClass().add("cat-hint");
        themeWarnings.getStyleClass().add("cat-hint");
        themeWarnings.setWrapText(true);
        themeWarnings.setStyle("-fx-text-fill: -brisk-warning;");
        themePreview.setFitWidth(132);
        themePreview.setFitHeight(74);
        themePreview.setPreserveRatio(true);
        themePreview.setSmooth(true);
        themePreview.setVisible(false);
        themePreview.setManaged(false);
        themeInfo.getChildren().setAll(themePreview, themeDesc, themeMeta, themeWarnings);
        themeInfo.setVisible(false);
        themeInfo.setManaged(false);

        reloadThemes();
        theme.valueProperty().addListener((obs, oldValue, value) -> {
            if (value == null) {
                return;
            }
            for (com.brisklauncher.mcl.setting.ThemePacks.Theme candidate
                    : com.brisklauncher.mcl.setting.ThemePacks.all()) {
                if (candidate.displayName().equals(value)) {
                    com.brisklauncher.mcl.setting.ThemePacks.setCurrent(candidate.id());
                    // 热重载：样式 + 背景 + 圆角一次刷新，不用重启
                    com.brisklauncher.mcl.MclApplication.refreshTheme(getScene());
                    refreshThemeDetails(candidate);
                    // 原生啟動圖也要跟著換（背景畫，下次啟動就是這個主題的顏色）
                    com.brisklauncher.mcl.ui.NativeSplashService.ensureUpToDateAsync();
                    return;
                }
            }
        });
    }

    /** 重新扫一遍主题目录并重建下拉（保留当前选择）。 */
    private void reloadThemes() {
        var themes = com.brisklauncher.mcl.setting.ThemePacks.all();
        theme.getItems().clear();
        for (var item : themes) {
            theme.getItems().add(item.displayName());
        }
        String currentId = com.brisklauncher.mcl.setting.ThemePacks.currentId();
        var current = themes.stream().filter(item -> item.id().equals(currentId))
                .findFirst().orElse(themes.get(0));
        theme.getSelectionModel().select(current.displayName());
        refreshThemeDetails(current);
    }

    /** 缩图（存在才显示）+ 说明 + 作者/版本 + 安全警告。 */
    private void refreshThemeDetails(com.brisklauncher.mcl.setting.ThemePacks.Theme selected) {
        if (selected == null) {
            return;
        }
        boolean builtin = com.brisklauncher.mcl.setting.ThemePacks.DEFAULT_ID.equals(selected.id());
        themeInfo.setVisible(!builtin);
        themeInfo.setManaged(!builtin);
        if (builtin) {
            return;
        }
        String description = selected.description();
        themeDesc.setText(description == null || description.isBlank()
                ? I18n.t("settings.theme_none") : description);
        themeMeta.setText(I18n.t("settings.theme_by",
                selected.author().isBlank() ? "-" : selected.author(),
                selected.version().isBlank() ? "-" : selected.version())
                + (selected.dark() ? " · 🌙" : " · ☀️"));
        if (selected.warnings().isEmpty()) {
            themeWarnings.setVisible(false);
            themeWarnings.setManaged(false);
        } else {
            themeWarnings.setVisible(true);
            themeWarnings.setManaged(true);
            themeWarnings.setText(I18n.t("settings.theme_warnings",
                    String.join("；", selected.warnings())));
        }
        // 缩图按 2 倍显示尺寸解码（132×74 → 264×148），省内存也省时间
        javafx.scene.image.Image preview = selected.preview() == null ? null
                : com.brisklauncher.mcl.security.GuardedImages.fromFile(
                        selected.preview(), 264, 148, true, false);
        if (preview == null) {
            themePreview.setImage(null);
            themePreview.setVisible(false);
            themePreview.setManaged(false);
        } else {
            themePreview.setImage(preview);
            themePreview.setVisible(true);
            themePreview.setManaged(true);
        }
    }
    private VBox networkCard() {
        Label thirdPartyNote = UiKit.catHint(I18n.t("settings.third_party_note"));
        thirdPartyNote.setWrapText(true);
        proxy.setPromptText("127.0.0.1:7890 或 socks5://127.0.0.1:1080");
        microsoftClientId.setPromptText("00000000-0000-0000-0000-000000000000");
        GridPane grid = grid();
        // 标签列保底按文字宽度：ID / key 填满 36+ 字符时，标签不会被挤成「Mic...」
        javafx.scene.layout.ColumnConstraints labelColumn =
                new javafx.scene.layout.ColumnConstraints();
        labelColumn.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        javafx.scene.layout.ColumnConstraints valueColumn =
                new javafx.scene.layout.ColumnConstraints();
        valueColumn.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().setAll(labelColumn, valueColumn);
        grid.addRow(0, new Label(I18n.t("settings.proxy")), proxy);
        // P0-2：勾选时二次确认，避免使用者以为只影响下载
        insecureTls.setOnAction(e -> {
            if (!insecureTls.isSelected()) {
                return;
            }
            insecureTls.setSelected(false);
            MCLDialog.confirm(getScene().getWindow(), I18n.t("settings.insecure_tls"),
                    I18n.t("settings.insecure_tls_warning"), () -> {
                        insecureTls.setSelected(true);
                        com.brisklauncher.mcl.security.Acks.record("insecureTls");
                    });
        });
        grid.addRow(1, new Label(""), insecureTls);
        grid.addRow(2, new Label(I18n.t("settings.microsoft_client_id")), microsoftClientId);
        microsoftClientIdSource.getStyleClass().add("cat-hint");
        microsoftClientIdSource.setWrapText(true);
        grid.addRow(3, new Label(""), microsoftClientIdSource);
        // 边打字边更新「当前生效」，不用先保存
        microsoftClientId.textProperty().addListener((obs, oldValue, newValue) -> refreshClientIdSource());
        Button help = new Button(I18n.t("settings.microsoft_client_help"));
        help.setOnAction(e -> DesktopActions.openUrl("https://minecraft.wiki/w/Microsoft_authentication"));
        Button azure = new Button(I18n.t("settings.microsoft_client_azure"));
        azure.setOnAction(e -> DesktopActions.openUrl(
                "https://portal.azure.com/#view/Microsoft_AAD_RegisteredApps/CreateApplicationBlade"));
        HBox clientHelp = new HBox(8, help, azure);
        grid.addRow(4, new Label(""), clientHelp);
        // CurseForge API key：跟客户端 ID 一样遮蔽显示；留空则 CurseForge 源走镜像
        curseforgeApiKey.setPromptText(I18n.t("settings.curseforge_key_prompt"));
        grid.addRow(5, new Label(I18n.t("settings.curseforge_key")), curseforgeApiKey);
        Label curseforgeHint = UiKit.catHint(I18n.t("settings.curseforge_key_hint"));
        curseforgeHint.setWrapText(true);
        grid.addRow(6, new Label(""), curseforgeHint);
        HBox.setHgrow(proxy, Priority.ALWAYS);
        HBox.setHgrow(microsoftClientId, Priority.ALWAYS);
        HBox.setHgrow(curseforgeApiKey, Priority.ALWAYS);
        Label hint = UiKit.catHint(I18n.t("settings.microsoft_client_hint"));
        hint.setWrapText(true);
        return card(I18n.t("settings.section.network"),
                new VBox(8, grid, hint, thirdPartyNote));
    }

    private VBox javaCard() {
        GridPane grid = grid();
        grid.addRow(0, new Label(I18n.t("settings.default_memory")), memory);
        grid.addRow(1, new Label(""), optimizeMemory);
        Button refresh = new Button(I18n.t("common.refresh"));
        refresh.setOnAction(e -> refreshJdkList());
        Button openDir = new Button(I18n.t("settings.open_jdk_dir"));
        openDir.setOnAction(e -> openFolder(context.javaManager().jdkRoot()));
        Button downloadJava = new Button(I18n.t("settings.download_java"));
        downloadJava.setOnAction(e -> TaskDialog.run(getScene().getWindow(),
                I18n.t("settings.download_java"), progress -> context.javaManager().ensureFor(21,
                        context.downloader(), context.mirrors(), progress),
                jdk -> {
                    refreshJdkList();
                    MCLDialog.info(getScene().getWindow(), I18n.t("settings.section.java"),
                            I18n.t("version.settings.java_installed", jdk.display()));
                }));
        HBox actions = new HBox(8, refresh, openDir, downloadJava);
        grid.addRow(1, new Label(I18n.t("settings.java_list")), actions);
        grid.addRow(2, new Label(""), jdkList);
        return card(I18n.t("settings.section.java"), grid);
    }

    private VBox updateCard() {
        updateChannel.getItems().setAll("GitHub", "Gitee");
        updateChannel.setValue("GitHub");
        GridPane grid = grid();
        grid.addRow(0, new Label(""), autoUpdate);
        grid.addRow(1, new Label(""), autoInstall);
        grid.addRow(2, new Label(I18n.t("update.channel")), updateChannel);
        Button check = new Button(I18n.t("update.check_now"));
        check.setOnAction(e -> checkUpdateNow());
        Label version = new Label(I18n.t("update.current_version",
                com.brisklauncher.mcl.update.UpdateService.CURRENT_VERSION));
        version.getStyleClass().add("cat-hint");
        HBox actions = new HBox(10, check, version);
        actions.setAlignment(Pos.CENTER_LEFT);
        grid.addRow(3, new Label(""), actions);
        Label hint = UiKit.catHint(I18n.t("update.hint"));
        hint.setWrapText(true);
        return card(I18n.t("update.section"), new VBox(8, grid, hint));
    }

    /** 立即檢查更新（背景執行緒，避免卡住設置頁）。 */
    private void checkUpdateNow() {
        LauncherSettings current = collectSettings();
        context.saveSettings(current);
        java.util.function.Consumer<String> show = message ->
                Platform.runLater(() -> MCLDialog.info(getScene().getWindow(),
                        I18n.t("update.section"), message));
        Thread.ofVirtual().name("mcl-update-check").start(() -> {
            if (!current.updateConfigured()) {
                show.accept(I18n.t("update.not_configured"));
                return;
            }
            var result = com.brisklauncher.mcl.update.UpdateService.check(current);
            if (!result.ok()) {
                show.accept(I18n.t("update.check_failed", result.error()));
                return;
            }
            var release = result.release();
            if (!result.updateAvailable()) {
                show.accept(I18n.t("update.up_to_date",
                        com.brisklauncher.mcl.update.UpdateService.CURRENT_VERSION,
                        release.version()));
                return;
            }
            // 有新版：走自动覆盖更新流程（下载 → 覆盖 → 重启），不再只开下载页
            Platform.runLater(() -> com.brisklauncher.mcl.ui.UpdateFlow.offer(
                    getScene().getWindow(), context, release, true));
        });
    }

    private VBox otherCard() {
        GridPane grid = grid();
        grid.addRow(0, new Label(I18n.t("settings.frp_path")), frpPath);
        Button browse = new Button("…");
        browse.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("frpc", "frpc.exe", "*.*"));
            File file = chooser.showOpenDialog(getScene().getWindow());
            if (file != null) {
                frpPath.setText(file.getAbsolutePath());
            }
        });
        grid.add(new HBox(6, frpPath, browse), 1, 0);
        HBox.setHgrow(frpPath, Priority.ALWAYS);

        Button updateRules = new Button(I18n.t("settings.update_crash_rules"));
        updateRules.setOnAction(e -> Thread.ofVirtual().start(() -> {
            boolean ok = context.crashRules().updateRemote(true);
            Platform.runLater(() -> MCLDialog.info(getScene().getWindow(),
                    I18n.t("settings.crash.rules"),
                    ok ? I18n.t("settings.crash.updated") : I18n.t("settings.crash.update_failed")));
        }));
        grid.addRow(1, new Label(I18n.t("settings.crash.remote_update")), crashRemote);
        grid.addRow(2, new Label(""), updateRules);
        return card(I18n.t("settings.section.other"), grid);
    }

    private VBox aboutCard() {
        java.nio.file.Path dataDir = com.brisklauncher.mcl.AppPaths.launcherRoot();
        Button openData = new Button(I18n.t("settings.open_data_dir"));
        openData.setOnAction(e -> openFolder(dataDir));
        Label dataLabel = new Label(I18n.t("settings.data_dir", dataDir.toString()));
        dataLabel.setWrapText(true);
        dataLabel.getStyleClass().add("cat-hint");
        // 记忆体读数：排查「启动器比游戏还吃记忆体」时，先看这里的堆/非堆
        Label memoryLabel = new Label();
        memoryLabel.getStyleClass().add("cat-hint");
        memoryLabel.setWrapText(true);
        Runnable refreshMemory = () -> memoryLabel.setText(memoryText());
        Button refresh = new Button(I18n.t("common.refresh"));
        refresh.setOnAction(e -> refreshMemory.run());
        refreshMemory.run();
        HBox memoryRow = new HBox(8, refresh, memoryLabel);
        memoryRow.setAlignment(Pos.CENTER_LEFT);
        // §4.4 关于页的「第三方内容与免责」：与设定页/工具页用同一套口径，避免两处说法不一致
        Label disclaimer = UiKit.catHint(I18n.t("settings.third_party_disclaimer"));
        // 中英对照表来自 HMCL（GPL-3.0）；本项目同为 GPL-3.0，按协议在此声明来源
        Label credits = UiKit.catHint(I18n.t("settings.credits"));
        disclaimer.setWrapText(true);
        Button github = new Button("GitHub");
        github.setOnAction(e -> com.brisklauncher.mcl.ui.DesktopActions.openUrl("https://github.com/Xiaojiu114514/Brisk-Launcher"));
        Button gitee = new Button("Gitee");
        gitee.setOnAction(e -> com.brisklauncher.mcl.ui.DesktopActions.openUrl("https://gitee.com/xiaojiu114514/Brisk-Launcher"));
        HBox repoRow = new HBox(8, github, gitee);
        repoRow.setAlignment(Pos.CENTER_LEFT);
        return card(I18n.t("settings.about"), new VBox(6,
                // 版本号走 UpdateService.CURRENT_VERSION：这里以前写死 "Brisk Launcher (MCL) v1.0.0"，
                // 留着旧项目名 MCL（使用者截图指出），而且改版本时容易忘记同步。
                new Label("Brisk Launcher v"
                        + com.brisklauncher.mcl.update.UpdateService.CURRENT_VERSION),
                new Label("Java 21 · JavaFX · JPMS"),
                repoRow, dataLabel, openData, memoryRow,
                UiKit.catHint(I18n.t("settings.security")),
                UiKit.title(I18n.t("settings.third_party_section")), disclaimer, credits));
    }

    /** 目前堆/非堆用量（正式發行版用 -Xmx192m 啟動，數字不該無限長大）。 */
    private static String memoryText() {
        var memory = java.lang.management.ManagementFactory.getMemoryMXBean();
        var heap = memory.getHeapMemoryUsage();
        var nonHeap = memory.getNonHeapMemoryUsage();
        long usedMb = heap.getUsed() / (1024 * 1024);
        long maxMb = heap.getMax() <= 0 ? -1 : heap.getMax() / (1024 * 1024);
        long nonHeapMb = nonHeap.getUsed() / (1024 * 1024);
        return "内存：堆 " + usedMb + " / " + (maxMb < 0 ? "∞" : maxMb) + " MB · 非堆 "
                + nonHeapMb + " MB（线程 " + Thread.activeCount() + "）";
    }

    // ---------- 数据 ----------

    private void loadSettings() {
        LauncherSettings settings = context.settings();
        LauncherSettings.Source savedSource = settings.source() == LauncherSettings.Source.BMCLAPI
                ? LauncherSettings.Source.AUTO : settings.source();
        source.getSelectionModel().select(savedSource.display());
        customMirror.setText(settings.customMirror());
        customMirror.setDisable(settings.source() != LauncherSettings.Source.CUSTOM);
        concurrency.getValueFactory().setValue(settings.concurrency());
        retries.getValueFactory().setValue(settings.retries());
        timeout.getValueFactory().setValue(settings.timeoutSeconds());
        memory.getValueFactory().setValue(settings.defaultMemoryMb());
        language.getSelectionModel().select(I18n.displayName(settings.language()));
        proxy.setText(settings.proxy());
        insecureTls.setSelected(settings.insecureTls());
        optimizeMemory.setSelected(com.brisklauncher.mcl.util.MemoryTuning.enabled());
        optimizeMemory.setOnAction(e -> com.brisklauncher.mcl.util.MemoryTuning.setEnabled(
                optimizeMemory.isSelected()));
        crashRemote.setSelected(settings.crashRuleRemoteUpdate());
        frpPath.setText(settings.frpPath());
        autoLanguage.setSelected(settings.autoLanguage());
        microsoftClientId.setText(settings.microsoftClientId());
        refreshClientIdSource();
        curseforgeApiKey.setText(settings.curseforgeApiKey());
        autoUpdate.setSelected(settings.autoUpdate());
        autoInstall.setSelected(settings.autoInstallUpdate());
        updateChannel.setValue("gitee".equalsIgnoreCase(settings.updateChannel()) ? "Gitee" : "GitHub");
    }

    /** 更新「当前生效」提示：ID 是设置页填的、构建内置的、还是环境变量来的。 */
    private void refreshClientIdSource() {
        String mode = com.brisklauncher.mcl.util.MsClientId.source(microsoftClientId.getText());
        microsoftClientIdSource.setText(I18n.t("settings.microsoft_client_source",
                I18n.t("settings.microsoft_client_source." + mode)));
    }

    /** 自訂下載源是高風險設定：首次保存時明確告知「該伺服器能決定你下載到什麼」。 */
    private boolean confirmCustomMirror() {
        com.brisklauncher.mcl.setting.LauncherSettings.Source parsed =
                com.brisklauncher.mcl.setting.LauncherSettings.Source.parse(
                        String.valueOf(source.getSelectionModel().getSelectedItem()));
        if (parsed != com.brisklauncher.mcl.setting.LauncherSettings.Source.CUSTOM) {
            return true;
        }
        String mirror = customMirror.getText() == null ? "" : customMirror.getText().trim();
        String mirrorHost = mirrorHostOf(mirror);
        // 安全审计 v3 A-15：确认绑定到具体镜像主机，换域名要重新确认
        String ackKey = com.brisklauncher.mcl.security.Acks.scopedKey("customMirror", mirrorHost);
        if (mirror.isBlank() || com.brisklauncher.mcl.security.Acks.has(ackKey)) {
            return true;
        }
        final boolean[] ok = {false};
        MCLDialog.confirm(getScene().getWindow(), I18n.t("settings.custom_mirror"),
                I18n.t("settings.custom_mirror_warning", mirror), () -> ok[0] = true);
        if (ok[0]) {
            com.brisklauncher.mcl.security.Acks.record(ackKey);
        }
        return ok[0];
    }

    private void saveSettings() {
        if (!confirmCustomMirror()) {
            return;
        }
        context.saveSettings(collectSettings());
        status.setText(I18n.t("settings.saved"));
    }

    /** 把界面上的值收集成一份設定（儲存與「立即檢查更新」共用）。 */
    private LauncherSettings collectSettings() {
        // 用「显示文字」反查列举，不要用索引：下拉多了「Auto」之后索引全位移了，
        // 之前选 Auto 会被存成 OFFICIAL（这就是「改了又变回去」的原因）。
        LauncherSettings.Source parsedSource = LauncherSettings.Source.parse(
                String.valueOf(source.getSelectionModel().getSelectedItem()));
        // 旧的索引对应已移除（改成上面用显示文字反查）
        String selectedLanguage = "zh_CN";
        for (String tag : I18n.supportedLocales()) {
            if (I18n.displayName(tag).equals(language.getValue())) {
                selectedLanguage = tag;
            }
        }
        return new LauncherSettings(
                parsedSource,
                customMirror.getText(),
                concurrency.getValue(),
                retries.getValue(),
                timeout.getValue(),
                selectedLanguage,
                autoLanguage.isSelected(),
                memory.getValue(),
                crashRemote.isSelected(),
                frpPath.getText(),
                proxy.getText(),
                insecureTls.isSelected(),
                microsoftClientId.getText() == null ? "" : microsoftClientId.getText().trim(),
                autoUpdate.isSelected(),
                autoInstall.isSelected(),
                "Gitee".equalsIgnoreCase(updateChannel.getValue()) ? "gitee" : "github",
                com.brisklauncher.mcl.setting.LauncherSettings.DEFAULT_UPDATE_REPO,
                curseforgeApiKey.getText() == null ? "" : curseforgeApiKey.getText().trim());
    }

    private void refreshJdkList() {
        jdkList.getChildren().clear();
        List<JdkInfo> jdks = context.javaManager().scanAll();
        if (jdks.isEmpty()) {
            jdkList.getChildren().add(UiKit.catHint(I18n.t("settings.no_java")));
            return;
        }
        for (JdkInfo jdk : jdks) {
            Label label = new Label((jdk.managed() ? "✦ " : "💻 ") + jdk.display() + " · " + jdk.home());
            label.getStyleClass().add("instance-meta");
            jdkList.getChildren().add(label);
        }
    }

    // ---------- 布局工具 ----------

    private static GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(10);
        return grid;
    }

    private static VBox card(String title, javafx.scene.Node content) {
        VBox box = new VBox(10, UiKit.title(title), content);
        box.setPadding(new Insets(16));
        box.getStyleClass().add("card");
        return box;
    }

    private static void openFolder(Path path) {
        DesktopActions.openFolder(path);
    }

    /** 从使用者输入的镜像地址里取主机名（用于 acks 指纹）。 */
    private static String mirrorHostOf(String mirror) {
        if (mirror == null || mirror.isBlank()) {
            return "";
        }
        try {
            String value = mirror.trim();
            if (!value.startsWith("http://") && !value.startsWith("https://")) {
                value = "https://" + value;
            }
            String host = java.net.URI.create(value).getHost();
            return host == null ? value : host.toLowerCase(java.util.Locale.ROOT);
        } catch (Exception error) {
            return mirror.trim().toLowerCase(java.util.Locale.ROOT);
        }
    }
}
