package com.brisklauncher.mcl.ui;

import com.brisklauncher.mcl.MclContext;
import com.brisklauncher.mcl.download.remote.LoaderMetaClient;
import com.brisklauncher.mcl.download.remote.ModrinthClient;
import com.brisklauncher.mcl.game.GameDirectory;
import com.brisklauncher.mcl.game.install.InstallProgress;
import com.brisklauncher.mcl.i18n.I18n;
import com.brisklauncher.mcl.security.SafeLog;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 「安装新版本」视窗：版本已在下载页选好，这里只确认 <b>安装目录</b>、
 * <b>Mod 加载器</b>（含版本清单）与 Fabric 系的 <b>Fabric API</b>。
 *
 * <p>样式与「版本设置」一致：透明 Stage + 圆角卡片 + 自订标题列。
 */
public final class InstallVersionDialog {

    private static final String NONE = "—";

    private InstallVersionDialog() {
    }

    public static void show(Window owner, MclContext context) {
        show(owner, context, "");
    }

    public static void show(Window owner, MclContext context, String mcVersion) {
        show(owner, context, mcVersion, null);
    }

    /** @param preferredRoot 當前選中版本所屬的遊戲目錄（預設就選它） */
    public static void show(Window owner, MclContext context, String mcVersion, GameDirectory preferredRoot) {
        String versionInput = mcVersion == null ? "" : mcVersion.trim();
        // 开发期：截图/回归用（-Dmcl.dev.dialogver=<mc> -Dmcl.dev.dialogloader=NeoForge）
        String devVersion = System.getProperty("mcl.dev.dialogver");
        if (versionInput.isBlank() && devVersion != null && !devVersion.isBlank()) {
            versionInput = devVersion.trim();
        }
        final String version = versionInput;
        Stage dialog = new Stage();
        dialog.initOwner(owner);
        dialog.initModality(Modality.NONE);
        dialog.initStyle(StageStyle.TRANSPARENT);
        dialog.setTitle(I18n.t("install.dialog.title"));

        Label title = new Label(I18n.t("install.dialog.title") + (version.isBlank() ? "" : " · " + version));
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: -brisk-text;");
        Button close = UiKit.closeButtonStyled();
        close.getStyleClass().addAll("window-button", "window-close");
        close.setOnAction(e -> dialog.close());
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox header = new HBox(8, title, headerSpacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        // ---------- 安装目录 ----------
        List<GameDirectory> roots = context.rootStore().load();
        ComboBox<GameDirectory> targetCombo = new ComboBox<>();
        targetCombo.getItems().setAll(roots);
        targetCombo.setCellFactory(list -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(GameDirectory item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null
                        : (item.displayName() == null || item.displayName().isBlank()
                        ? item.path().toString() : item.displayName() + "  ·  " + item.path()));
            }
        });
        targetCombo.setButtonCell(targetCombo.getCellFactory().call(null));
        if (preferredRoot != null) {
            GameDirectory match = null;
            for (GameDirectory candidate : roots) {
                if (candidate.path().toAbsolutePath().normalize()
                        .equals(preferredRoot.path().toAbsolutePath().normalize())) {
                    match = candidate;
                    break;
                }
            }
            targetCombo.getSelectionModel().select(match != null ? match : preferredRoot);
            if (match == null) {
                targetCombo.getItems().add(0, preferredRoot);
            }
            for (GameDirectory candidate : roots) {
            }
        } else if (!roots.isEmpty()) {
            targetCombo.getSelectionModel().select(0);
        }
        targetCombo.setMaxWidth(Double.MAX_VALUE);
        Button browse = new Button(I18n.t("install.dialog.browse"));
        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle(I18n.t("install.dialog.browse"));
            GameDirectory current = targetCombo.getValue();
            if (current != null && Files.isDirectory(current.path())) {
                chooser.setInitialDirectory(current.path().toFile());
            }
            java.io.File picked = chooser.showDialog(dialog);
            if (picked != null) {
                GameDirectory added = new GameDirectory(picked.toPath(), picked.getName());
                targetCombo.getItems().add(added);
                targetCombo.getSelectionModel().select(added);
            }
        });
        javafx.scene.control.TextField nameField = new javafx.scene.control.TextField(
                version.isBlank() ? "" : version);
        nameField.setPromptText(I18n.t("install.dialog.name_hint"));
        nameField.setMaxWidth(Double.MAX_VALUE);
        // 只有「使用者自己打字」才算编辑；程式自动填入的预设值不算（否则切换加载器后名称不会变）
        final boolean[] nameEdited = {false};
        final String[] programmatic = {nameField.getText() == null ? "" : nameField.getText()};
        nameField.textProperty().addListener((obs, old, now) -> {
            String value = now == null ? "" : now;
            if (!value.equals(programmatic[0])) {
                nameEdited[0] = true;   // 真的被使用者改过
            }
        });
        java.util.function.Consumer<String> setDefaultName = value -> {
            programmatic[0] = value == null ? "" : value;
            nameField.setText(programmatic[0]);
        };

        HBox targetRow = new HBox(8, targetCombo, browse);
        HBox.setHgrow(targetCombo, Priority.ALWAYS);

        // ---------- Mod 加载器 ----------
        ComboBox<String> loaderCombo = new ComboBox<>();
        loaderCombo.getItems().addAll(NONE, "Forge", "NeoForge", "Fabric");
        loaderCombo.getSelectionModel().select(0);
        loaderCombo.setMaxWidth(Double.MAX_VALUE);
        // 开发期：直接选中某个加载器（截图/回归用）；实际选中在 listener 之后才做，
        // 否则 valueProperty 的变更会被漏掉、版本清单不会载入
        final String devLoader = System.getProperty("mcl.dev.dialogloader");

        ComboBox<LoaderMetaClient.LoaderVersion> loaderVersionCombo = new ComboBox<>();
        loaderVersionCombo.setMaxWidth(Double.MAX_VALUE);
        // 只显示版本号（+ 稳定标记），不要把整个 record 印出来
        javafx.util.Callback<javafx.scene.control.ListView<LoaderMetaClient.LoaderVersion>,
                javafx.scene.control.ListCell<LoaderMetaClient.LoaderVersion>> loaderCell =
                list -> new javafx.scene.control.ListCell<>() {
                    @Override
                    protected void updateItem(LoaderMetaClient.LoaderVersion item, boolean empty) {
                        super.updateItem(item, empty);
                        if (empty || item == null) {
                            setText(null);
                            return;
                        }
                        // 只显示版本号：beta 也一样列出来，但不再打 "(beta)" 字样
                        setText(item.version());
                    }
                };
        loaderVersionCombo.setCellFactory(loaderCell);
        loaderVersionCombo.setButtonCell(loaderCell.call(null));
        loaderVersionCombo.setDisable(true);
        // 提示语不再写「留空 = 最新」：留空只代表「还没选」，不代表会去装最新。
        // 真的没取到清单时下面会显示明确的错误提示（见 loadLoaderVersions）。
        loaderVersionCombo.setPromptText(I18n.t("install.dialog.loader_version"));

        // ---------- Fabric API ----------
        CheckBox optifine = new CheckBox(I18n.t("install.dialog.optifine"));
        optifine.setDisable(true);
        ComboBox<LoaderMetaClient.LoaderVersion> optifineCombo = new ComboBox<>();
        optifineCombo.setMaxWidth(Double.MAX_VALUE);
        optifineCombo.setDisable(true);
        javafx.util.Callback<javafx.scene.control.ListView<LoaderMetaClient.LoaderVersion>,
                javafx.scene.control.ListCell<LoaderMetaClient.LoaderVersion>> optifineCell =
                list -> new javafx.scene.control.ListCell<>() {
                    @Override
                    protected void updateItem(LoaderMetaClient.LoaderVersion item, boolean empty) {
                        super.updateItem(item, empty);
                        setText(empty || item == null ? null : item.version());
                    }
                };
        optifineCombo.setCellFactory(optifineCell);
        optifineCombo.setButtonCell(optifineCell.call(null));

        CheckBox fabricApi = new CheckBox(I18n.t("install.dialog.fabric_api"));
        fabricApi.setDisable(true);
        ComboBox<ModrinthClient.VersionFile> fabricApiCombo = new ComboBox<>();
        fabricApiCombo.setMaxWidth(Double.MAX_VALUE);
        // 安全：VersionFile 没有 toString，直接放进 ComboBox 会显示整条 record（含下载连结）
        javafx.util.Callback<javafx.scene.control.ListView<ModrinthClient.VersionFile>,
                javafx.scene.control.ListCell<ModrinthClient.VersionFile>> apiCell =
                list -> new javafx.scene.control.ListCell<>() {
                    @Override
                    protected void updateItem(ModrinthClient.VersionFile item, boolean empty) {
                        super.updateItem(item, empty);
                        if (empty || item == null) {
                            setText(null);
                            return;
                        }
                        String number = item.versionNumber() == null || item.versionNumber().isBlank()
                                ? item.name() : item.versionNumber();
                        setText(number);
                    }
                };
        fabricApiCombo.setCellFactory(apiCell);
        fabricApiCombo.setButtonCell(apiCell.call(null));
        fabricApiCombo.setDisable(true);

        Label loaderNote = new Label();
        loaderNote.getStyleClass().add("cat-hint");
        loaderNote.setWrapText(true);
        loaderNote.setManaged(false);
        loaderNote.setVisible(false);
        Label status = UiKit.catHint(I18n.t("install.dialog.hint"));

        Runnable loadLoaderVersions = () -> {
            String selected = loaderCombo.getValue();
            boolean vanilla = selected == null || NONE.equals(selected);
            loaderVersionCombo.setDisable(vanilla);
            loaderVersionCombo.setEditable(false);
            loaderVersionCombo.getItems().clear();
            boolean forgeLike = "Forge".equals(selected);
            optifine.setDisable(!forgeLike);
            if (!forgeLike) {
                optifine.setSelected(false);
                optifineCombo.setDisable(true);
                optifineCombo.getItems().clear();
            }
            boolean fabricLike = "Fabric".equals(selected);
            fabricApi.setDisable(!fabricLike);
            // Fabric 系预设就勾上 Fabric API（几乎所有 Fabric 整合包都需要它）
            fabricApi.setSelected(fabricLike);
            if (vanilla) {
                return;
            }
            String key = selected.toLowerCase(Locale.ROOT);
            // 预设名称跟著加载器变（使用者自己改过就不覆盖）
            if (!nameEdited[0]) {
                String def = "Forge".equals(selected) || "NeoForge".equals(selected)
                        || "Fabric".equals(selected)
                        ? version + "-" + selected : version;
                programmatic[0] = def;
                nameField.setText(def);
            }
            Thread worker = new Thread(() -> {
                List<LoaderMetaClient.LoaderVersion> versions;
                try {
                    versions = switch (key) {
                        case "forge" -> context.loaderMeta().forgeVersions(version);
                        case "neoforge" -> context.loaderMeta().neoforgeVersions(version);
                        case "optifine" -> context.loaderMeta().optifineVersions(version);
                        default -> context.loaderMeta().fabricLoaders(version);
                    };
                } catch (Exception error) {
                    SafeLog.warn("[loader] " + selected + " 清单失败：" + error);
                    versions = List.of();
                }
                // 新的在前（这里直接拿 loaderMeta 的原始顺序，之前没排序）
                List<LoaderMetaClient.LoaderVersion> loaded = new ArrayList<>(versions);
                loaded.sort((a, b) -> LoaderMetaClient.compareVersionsDescending(b.version(), a.version()));
                SafeLog.info("[loader] " + selected + " 版本清单：" + loaded.size() + " 笔");
                Platform.runLater(() -> {
                    loaderVersionCombo.getItems().setAll(loaded);
                    if (!loaded.isEmpty()) {
                        loaderVersionCombo.getSelectionModel().select(0);
                        loaderNote.setManaged(false);
                        loaderNote.setVisible(false);
                        loaderVersionCombo.setPromptText(I18n.t("install.dialog.loader_version"));
                        return;
                    }
                    // 真的取不到清单（不是「留空代表最新」）：
                    // 提示要重试，并把下拉的提示语也改成「没取到」，不要误导成默认装最新。
                    loaderVersionCombo.setPromptText(I18n.t("install.dialog.loader_version_empty"));
                    if ("OptiFine".equalsIgnoreCase(selected)) {
                        loaderNote.setText(I18n.t("install.dialog.optifine_missing"));
                    } else if ("neoforge".equals(key)) {
                        // NeoForge 没有 1.20.1（也没有更早）的版本 → 说清楚要用 Forge
                        loaderNote.setText(I18n.t("install.dialog.neoforge_unsupported"));
                    } else {
                        loaderNote.setText(I18n.t("install.dialog.loader_list_failed"));
                    }
                    loaderNote.setManaged(true);
                    loaderNote.setVisible(true);
                });
            }, "brisk-loader-versions");
            worker.setDaemon(true);
            worker.start();
        };
        loaderCombo.valueProperty().addListener((obs, old, now) -> loadLoaderVersions.run());
        if (devLoader != null && loaderCombo.getItems().contains(devLoader.trim())) {
            loaderCombo.getSelectionModel().select(devLoader.trim());
        }

        optifine.selectedProperty().addListener((obs, old, now) -> {
            optifineCombo.setDisable(!now);
            if (!now || !optifineCombo.getItems().isEmpty()) {
                return;
            }
            Thread worker = new Thread(() -> {
                java.util.List<LoaderMetaClient.LoaderVersion> files;
                try {
                    files = context.loaderMeta().optifineVersions(version);
                } catch (Exception error) {
                    SafeLog.warn("[loader] OptiFine 清单失败：" + error);
                    files = java.util.List.of();
                }
                java.util.List<LoaderMetaClient.LoaderVersion> loaded = files;
                Platform.runLater(() -> {
                    optifineCombo.getItems().setAll(loaded);
                    if (!loaded.isEmpty()) {
                        optifineCombo.getSelectionModel().select(0);
                    } else {
                        optifine.setSelected(false);
                        optifine.setDisable(true);
                        loaderNote.setManaged(true);
                        loaderNote.setVisible(true);
                        loaderNote.setText(I18n.t("install.dialog.optifine_missing"));
                    }
                });
            }, "brisk-optifine");
            worker.setDaemon(true);
            worker.start();
        });

        fabricApi.selectedProperty().addListener((obs, old, now) -> {
            fabricApiCombo.setDisable(!now);
            if (!now || !fabricApiCombo.getItems().isEmpty()) {
                return;
            }
            status.setText(I18n.t("install.dialog.fabric_api_loading"));
            Thread worker = new Thread(() -> {
                List<ModrinthClient.VersionFile> files;
                try {
                    files = context.fabricApiVersions(version);
                } catch (Exception error) {
                    files = List.of();
                }
                List<ModrinthClient.VersionFile> loaded = files;
                Platform.runLater(() -> {
                    fabricApiCombo.getItems().setAll(loaded);
                    if (!loaded.isEmpty()) {
                        fabricApiCombo.getSelectionModel().select(0);
                    }
                    status.setText(I18n.t("install.dialog.hint"));
                });
            }, "brisk-fabric-api");
            worker.setDaemon(true);
            worker.start();
        });

        Button start = new Button(I18n.t("install.dialog.start"));
        start.getStyleClass().add("primary-button");
        start.setDefaultButton(true);
        Button cancel = new Button(I18n.t("dialog.cancel"));
        cancel.setOnAction(e -> dialog.close());
        Region actionSpacer = new Region();
        HBox.setHgrow(actionSpacer, Priority.ALWAYS);
        HBox actions = new HBox(8, actionSpacer, cancel, start);
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(10,
                header,
                UiKit.title(I18n.t("install.dialog.section.target")), targetRow,
                UiKit.title(I18n.t("install.dialog.section.name")), nameField,
                UiKit.title(I18n.t("install.dialog.section.loader")), loaderCombo, loaderVersionCombo,
                loaderNote,
                fabricApi, fabricApiCombo, optifine, optifineCombo,
                status, actions);
        root.setPadding(new Insets(16));
        root.setStyle("-fx-background-color: -brisk-bg; -fx-background-radius: 12;"
                + " -fx-border-color: -brisk-border; -fx-border-radius: 12;");
        root.setPrefWidth(520);

        start.setOnAction(e -> {
            GameDirectory target = targetCombo.getValue();
            if (target == null || version.isBlank()) {
                MCLDialog.error(dialog, I18n.t("install.dialog.title"), I18n.t("install.dialog.need_input"));
                return;
            }
            String loader = loaderCombo.getValue();
            boolean vanilla = loader == null || NONE.equals(loader);
            // 选了加载器就必须有版本（留空不允许开始）
            if (!vanilla && loaderVersionCombo.getSelectionModel().getSelectedItem() == null) {
                MCLDialog.error(dialog, I18n.t("install.dialog.title"),
                        I18n.t("install.dialog.need_loader_version"));
                return;
            }
            LoaderMetaClient.LoaderVersion loaderVersion = loaderVersionCombo.getValue();
            ModrinthClient.VersionFile apiFile = fabricApi.isSelected() ? fabricApiCombo.getValue() : null;
            LoaderMetaClient.LoaderVersion optifineVersion = optifine.isSelected()
                    ? optifineCombo.getValue() : null;
            String instanceName = nameField.getText() == null ? "" : nameField.getText().trim();
            if (instanceName.isBlank()) {
                instanceName = version;
            }
            try {
                // 参考布局：<游戏根>/versions/<名称>/<名称>.json|jar，libraries/assets 留在游戏根
                com.brisklauncher.mcl.security.PathSafety.requirePathSegment(instanceName);
            } catch (Exception bad) {
                MCLDialog.error(dialog, I18n.t("install.dialog.title"), bad.getMessage());
                return;
            }
            dialog.close();
            install(context, owner, target, version, vanilla ? "" : loader,
                    loaderVersion != null ? loaderVersion.version() : loaderVersionCombo.getEditor().getText(), apiFile,
                    optifineVersion == null ? "" : optifineVersion.version(),
                    instanceName);
        });

        javafx.scene.Scene scene = new javafx.scene.Scene(root);
        UiKit.applyTheme(scene);
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        dialog.setScene(scene);
        UiKit.makeDraggable(dialog, header);
        UiKit.escToClose(scene, dialog);
        dialog.setOnShown(e -> UiKit.popIn(dialog, root));
        dialog.show();
    }

    /** 執行安裝：原版 →（可選）加載器 →（可選）Fabric API。 */
    private static void install(MclContext context, Window owner, GameDirectory target,
                                String mcVersion, String loaderName, String loaderVersion,
                                ModrinthClient.VersionFile fabricApiFile, String optifineVersion, String instanceName) {
        MultiProgressDialog.run(owner, I18n.t("install.dialog.title"), progress -> {
            var meta = context.mojang().versions().stream()
                    .filter(item -> mcVersion.equalsIgnoreCase(item.id()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            I18n.t("install.dialog.version_not_found", mcVersion)));
            com.brisklauncher.mcl.game.GameInstance result =
            context.versionInstaller().install(target, meta, progress);
            if (loaderName == null || loaderName.isBlank()) {
                var finalEntry = renameIfNeeded(context, result, instanceName);
                installFabricApi(context, finalEntry, fabricApiFile);
                return;
            }
            String effectiveLoaderVersion = loaderVersion;
            if (effectiveLoaderVersion == null || effectiveLoaderVersion.isBlank()) {
                effectiveLoaderVersion = latestLoaderVersion(context, loaderName, mcVersion);
                SafeLog.info("[install] 加载器版本未指定，使用最新：" + effectiveLoaderVersion);
            }
            switch (loaderName.toLowerCase(java.util.Locale.ROOT)) {
                case "forge" -> result = context.modLoaderInstaller()
                        .installForge(target, mcVersion, effectiveLoaderVersion, progress);
                case "neoforge" -> result = context.modLoaderInstaller()
                        .installNeoForge(target, mcVersion, effectiveLoaderVersion, progress);
                case "fabric" -> result = context.modLoaderInstaller()
                        .installFabric(target, mcVersion, effectiveLoaderVersion, progress);
                default -> {
                    // 没有对应的加载器
                }
            }
            var loadedEntry = renameIfNeeded(context, result, instanceName);
            removeVanillaLeftover(context, loadedEntry, mcVersion);
            if (optifineVersion != null && !optifineVersion.isBlank()) {
                try {
                    context.modLoaderInstaller().installOptiFineInto(loadedEntry, mcVersion, optifineVersion);
                } catch (Exception error) {
                    SafeLog.warn("OptiFine 安装失败：" + error.getClass().getSimpleName());
                }
            }
            installFabricApi(context, loadedEntry, fabricApiFile);
        }, () -> MCLDialog.info(null, I18n.t("install.dialog.title"),
                I18n.t("install.dialog.done")));
    }

    /** 取該加載器在指定 MC 版本下的最新版（使用者沒選時用）。 */
    private static String latestLoaderVersion(MclContext context, String loaderName, String mcVersion) {
        try {
            var versions = switch (loaderName.toLowerCase(java.util.Locale.ROOT)) {
                case "forge" -> context.loaderMeta().forgeVersions(mcVersion);
                case "neoforge" -> context.loaderMeta().neoforgeVersions(mcVersion);
                case "quilt" -> context.loaderMeta().quiltLoaders(mcVersion);
                default -> context.loaderMeta().fabricLoaders(mcVersion);
            };
            if (!versions.isEmpty()) {
                return versions.get(0).version();
            }
        } catch (Exception error) {
            SafeLog.warn("取最新加载器版本失败：" + error.getClass().getSimpleName());
        }
        throw new IllegalStateException("找不到可用的 " + loaderName + " 版本（MC " + mcVersion + "）");
    }

    /** 把最終實例改名成使用者指定的名稱（版本資料夾名）。 */
    private static com.brisklauncher.mcl.game.GameInstance renameIfNeeded(
            MclContext context, com.brisklauncher.mcl.game.GameInstance entry, String name) {
        if (entry == null) {
            return null;
        }
        com.brisklauncher.mcl.game.GameInstance result = entry;
        if (name != null && !name.isBlank() && !name.equalsIgnoreCase(entry.id())) {
            try {
                result = context.gameRepository().rename(entry, name);
                SafeLog.info("[install] 已改名为 " + result.id());
                final String newId = result.id();
                final com.brisklauncher.mcl.game.GameInstance renamedEntry = result;
                result = context.scanner().scan(entry.root()).stream()
                        .filter(item -> item.id().equalsIgnoreCase(newId))
                        .findFirst()
                        .orElseGet(() -> new com.brisklauncher.mcl.game.GameInstance(
                                renamedEntry.root(), newId,
                                renamedEntry.root().versionsDir().resolve(newId)));
                SafeLog.info("[install] 实例资料夹：" + result.versionDir());
            } catch (Exception error) {
                SafeLog.warn("改名失败（保留 " + entry.id() + "）：" + error.getClass().getSimpleName());
            }
        }
        return ensureIsolated(result);
    }

    /** 版本隔離：建立 mods / config / saves，並寫入遊戲語言。 */
    private static com.brisklauncher.mcl.game.GameInstance ensureIsolated(
            com.brisklauncher.mcl.game.GameInstance entry) {
        if (entry == null) {
            return null;
        }
        for (String name : new String[]{"mods", "config", "saves"}) {
            try {
                java.nio.file.Files.createDirectories(entry.versionDir().resolve(name));
            } catch (Exception error) {
                SafeLog.warn("建立 " + name + " 目录失败：" + error.getClass().getSimpleName());
            }
        }
        try {
            java.nio.file.Path options = entry.versionDir().resolve("options.txt");
            if (!java.nio.file.Files.isRegularFile(options)) {
                String ui = com.brisklauncher.mcl.i18n.I18n.currentLocaleTag();
                String gameLang = switch (ui == null ? "" : ui) {
                    case "zh_TW" -> "zh_tw";
                    case "zh_HK" -> "zh_hk";
                    case "en_US" -> "en_us";
                    default -> "zh_cn";
                };
                java.nio.file.Files.writeString(options, "lang:" + gameLang + "\n",
                        java.nio.charset.StandardCharsets.UTF_8);
                SafeLog.info("[install] 游戏语言设为 " + gameLang);
            }
        } catch (Exception ignored) {
            // 写不了不影响安装
        }
        entry.isolated(true);
        SafeLog.info("[install] 已启用版本隔离：" + entry.id());
        return entry;
    }

    /** 安裝完成後清掉改名留下的空殼或原版資料夾。 */
    private static void removeVanillaLeftover(MclContext context,
                                              com.brisklauncher.mcl.game.GameInstance finalEntry,
                                              String mcVersion) {
        if (finalEntry == null || mcVersion == null || mcVersion.isBlank()) {
            return;
        }
        try {
            java.nio.file.Path leftover = finalEntry.root().versionsDir()
                    .resolve(com.brisklauncher.mcl.security.PathSafety.requirePathSegment(mcVersion));
            java.nio.file.Path finalDir = finalEntry.versionDir().toAbsolutePath().normalize();
            java.nio.file.Path ownJson = finalDir.resolve(finalEntry.id() + ".json");
            if (leftover.toAbsolutePath().normalize().equals(finalDir)
                    && java.nio.file.Files.isRegularFile(ownJson)) {
                SafeLog.info("[install] 原版资料夹就是最终实例，不需清理");
                return;
            }
            if (!java.nio.file.Files.isDirectory(leftover)) {
                SafeLog.info("[install] 没有多余的原版资料夹");
                return;
            }
            boolean hasJson;
            try (var files = java.nio.file.Files.list(leftover)) {
                hasJson = files.anyMatch(p -> p.getFileName().toString()
                        .toLowerCase(java.util.Locale.ROOT).endsWith(".json"));
            }
            if (!hasJson) {
                deleteRecursively(leftover);
                SafeLog.info("[install] 已移除改名后留下的空壳资料夹：" + mcVersion);
                return;
            }
            if (com.brisklauncher.mcl.util.RecycleBin.delete(leftover)) {
                SafeLog.info("[install] 已清理多余的原版资料夹（回收站）：" + mcVersion);
            } else {
                deleteRecursively(leftover);
                SafeLog.info("[install] 已清理多余的原版资料夹（直接删除）：" + mcVersion);
            }
        } catch (Exception error) {
            SafeLog.warn("清理原版资料夹失败：" + error.getClass().getSimpleName()
                    + " · " + error.getMessage());
        }
    }

    private static void deleteRecursively(java.nio.file.Path dir) throws java.io.IOException {
        if (!java.nio.file.Files.isDirectory(dir)) {
            java.nio.file.Files.deleteIfExists(dir);
            return;
        }
        try (var stream = java.nio.file.Files.walk(dir)) {
            for (java.nio.file.Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                java.nio.file.Files.deleteIfExists(path);
            }
        }
    }

    /** Fabric API 放進該實例的 mods/。 */
    private static void installFabricApi(MclContext context,
                                         com.brisklauncher.mcl.game.GameInstance entry,
                                         ModrinthClient.VersionFile file) {
        if (file == null || entry == null) {
            return;
        }
        try {
            context.installFabricApi(entry, file.versionId());
        } catch (Exception error) {
            SafeLog.warn("安装 Fabric API 失败：" + error.getClass().getSimpleName());
        }
    }
}
