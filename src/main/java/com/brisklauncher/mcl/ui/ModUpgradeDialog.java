package com.brisklauncher.mcl.ui;

import com.brisklauncher.mcl.MclContext;
import com.brisklauncher.mcl.download.remote.MojangApi;
import com.brisklauncher.mcl.game.GameDirectory;
import com.brisklauncher.mcl.game.GameInstance;
import com.brisklauncher.mcl.game.LoaderInfo;
import com.brisklauncher.mcl.i18n.I18n;
import com.brisklauncher.mcl.modpack.ModUpgradeService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;

/**
 * Mod 一键升级：把来源实例的 Mod 按目标「游戏版本 + 加载器」找最新的相容版，
 * 统一下载到既有或新建的目标实例。
 *
 * <p>重点是**不假装成功**：每个 Mod 的判定（可升 / 没相容版 / 认不出来源）都会列出来，
 * 玩家自己决定要不要继续。
 */
public final class ModUpgradeDialog {

    private ModUpgradeDialog() {
    }

    public static void show(Window owner, MclContext context, GameInstance source) {
        Stage dialog = new Stage();
        dialog.initOwner(owner);
        dialog.initModality(Modality.NONE);
        dialog.initStyle(StageStyle.TRANSPARENT);
        dialog.setTitle(I18n.t("modupgrade.title"));

        Label title = new Label("⬆ " + I18n.t("modupgrade.title") + " · " + source.id());
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: -brisk-text;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = UiKit.closeButtonStyled();
        close.setOnAction(e -> dialog.close());
        HBox header = new HBox(8, title, spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        Label sourceInfo = new Label(source.subtitle() + " · " + source.gameDir());
        sourceInfo.getStyleClass().add("cat-hint");
        sourceInfo.setWrapText(true);

        // ---- 目标游戏版本 ----
        ComboBox<String> mcCombo = new ComboBox<>();
        mcCombo.setEditable(true);
        mcCombo.setPrefWidth(200);
        // ---- 目标加载器 ----
        ComboBox<LoaderInfo.LoaderType> loaderCombo = new ComboBox<>();
        // Quilt 已经不再提供安装（检测仍保留，好让旧实例能被识别），这里不要把它的选项列出来
        loaderCombo.getItems().setAll(java.util.Arrays.stream(LoaderInfo.LoaderType.values())
                .filter(type -> type != LoaderInfo.LoaderType.QUILT)
                .toList());
        loaderCombo.setValue(source.loader() == null
                ? LoaderInfo.LoaderType.VANILLA : source.loader().type());
        loaderCombo.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(LoaderInfo.LoaderType type, boolean empty) {
                super.updateItem(type, empty);
                setText(empty || type == null ? null : type.displayName());
            }
        });
        loaderCombo.setButtonCell(loaderCombo.getCellFactory().call(null));

        HBox targetRow = new HBox(8, UiKit.noShrink(new Label(I18n.t("modupgrade.target_mc"))), mcCombo,
                UiKit.noShrink(new Label(I18n.t("modupgrade.target_loader"))), loaderCombo);
        targetRow.setAlignment(Pos.CENTER_LEFT);

        // ---- 目标实例 ----
        ComboBox<GameInstance> instanceCombo = new ComboBox<>();
        instanceCombo.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(instanceCombo, Priority.ALWAYS);
        instanceCombo.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(GameInstance instance, boolean empty) {
                super.updateItem(instance, empty);
                setText(empty || instance == null ? null
                        : instance.id() + "  (" + instance.subtitle() + ")");
            }
        });
        instanceCombo.setButtonCell(instanceCombo.getCellFactory().call(null));
        CheckBox createNew = new CheckBox(I18n.t("modupgrade.new_instance"));
        TextField newName = new TextField();
        newName.setPromptText(I18n.t("modupgrade.new_instance_name"));
        newName.setPrefWidth(220);
        newName.setDisable(true);
        createNew.selectedProperty().addListener((obs, was, now) -> {
            instanceCombo.setDisable(Boolean.TRUE.equals(now));
            newName.setDisable(!Boolean.TRUE.equals(now));
        });
        HBox targetInstanceRow = new HBox(8, UiKit.noShrink(new Label(I18n.t("modupgrade.target_instance"))),
                instanceCombo, UiKit.noShrink(createNew), newName);
        targetInstanceRow.setAlignment(Pos.CENTER_LEFT);

        Label status = new Label(I18n.t("modupgrade.hint"));
        status.getStyleClass().add("cat-hint");
        status.setWrapText(true);

        ListView<ModUpgradeService.Entry> list = new ListView<>();
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(ModUpgradeService.Entry entry, boolean empty) {
                super.updateItem(entry, empty);
                if (empty || entry == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                String state = switch (entry.statusKey()) {
                    case "ok" -> I18n.t("modupgrade.state.ok", entry.target().versionNumber());
                    case "unknown" -> I18n.t("modupgrade.state.unknown");
                    default -> I18n.t("modupgrade.state.no_compatible");
                };
                Label name = new Label(entry.title());
                name.getStyleClass().add("entry-id");
                Label meta = new Label(entry.currentVersion() == null || entry.currentVersion().isBlank()
                        ? entry.fileName() : entry.fileName() + " · " + entry.currentVersion());
                meta.getStyleClass().add("cat-hint");
                VBox text = new VBox(1, name, meta);
                HBox.setHgrow(text, Priority.ALWAYS);
                Label tag = new Label(state);
                tag.getStyleClass().add("tag");
                tag.setMinWidth(Region.USE_PREF_SIZE);
                tag.setStyle("-fx-text-fill: " + (entry.upgradable() ? "#2E8B62"
                        : ("unknown".equals(entry.statusKey()) ? "#1F7A6E" : "#0F7C72")) + ";");
                HBox row = new HBox(8, text, tag);
                row.setAlignment(Pos.CENTER_LEFT);
                setGraphic(row);
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);
        list.setPlaceholder(UiKit.catHint(I18n.t("modupgrade.empty")));

        // ---- 分析 / 升级 ----
        ModUpgradeService.Plan[] planHolder = new ModUpgradeService.Plan[1];
        Button analyse = new Button(I18n.t("modupgrade.analyse"));
        Button start = new Button(I18n.t("modupgrade.start"));
        start.getStyleClass().add("primary-button");
        start.setDisable(true);

        Runnable refreshCandidates = () -> {
            String mc = targetMcValue(mcCombo);
            if (mc.isEmpty()) {
                instanceCombo.getItems().clear();
                return;
            }
            // 刷新候选时**保留玩家已经选好的目标实例**：
            // 之前每次刷新都 selectFirst()，于是「分析」一按就把目标实例跳回第一个（使用者回报的 bug）。
            GameInstance previous = instanceCombo.getValue();
            String previousId = previous == null ? "" : previous.id();
            String previousRoot = previous == null ? "" : previous.root().path().toString();
            List<GameInstance> candidates = ModUpgradeService.candidates(context, mc,
                    loaderCombo.getValue());
            instanceCombo.getItems().setAll(candidates);
            if (candidates.isEmpty()) {
                instanceCombo.getSelectionModel().clearSelection();
                return;
            }
            GameInstance keep = null;
            for (GameInstance candidate : candidates) {
                if (candidate.id().equals(previousId)
                        && candidate.root().path().toString().equals(previousRoot)) {
                    keep = candidate;
                    break;
                }
            }
            if (keep != null) {
                instanceCombo.getSelectionModel().select(keep);
            } else if (instanceCombo.getValue() == null) {
                instanceCombo.getSelectionModel().selectFirst();
            }
        };

        Runnable runAnalyse = () -> {
            String mc = targetMcValue(mcCombo);
            if (mc.isEmpty()) {
                MCLDialog.info(dialog, I18n.t("modupgrade.title"), I18n.t("modupgrade.need_mc"));
                return;
            }
            String loaderKey = ModUpgradeService.loaderKey(loaderCombo.getValue());
            status.setText(I18n.t("modupgrade.analysing", source.id()));
            start.setDisable(true);
            planHolder[0] = null;
            Thread.ofVirtual().name("mcl-mod-upgrade").start(() -> {
                try {
                    ModUpgradeService.Plan plan = ModUpgradeService.analyze(context, source, mc,
                            loaderKey, name -> Platform.runLater(() ->
                                    status.setText(I18n.t("modupgrade.analysing_file", name))));
                    Platform.runLater(() -> {
                        planHolder[0] = plan;
                        list.setItems(FXCollections.observableArrayList(plan.entries()));
                        status.setText(I18n.t("modupgrade.summary", plan.upgradable().size(),
                                plan.entries().size() - plan.upgradable().size() - plan.unknownCount(),
                                plan.unknownCount())
                                + (mc.equals(source.mcVersion())
                                ? " · " + I18n.t("modupgrade.opt_same_version_warn") : ""));
                        start.setDisable(plan.upgradable().isEmpty());
                    });
                } catch (Throwable error) {
                    Platform.runLater(() -> status.setText(
                            I18n.t("modupgrade.analyse_failed", String.valueOf(error.getMessage()))));
                }
            });
        };

        // 目标版本 / 加载器一改，旧计划立刻作废（否则按「开始」会装错版本的 jar）
        Runnable invalidatePlan = () -> {
            if (planHolder[0] == null) {
                return;
            }
            planHolder[0] = null;
            start.setDisable(true);
            list.getItems().clear();
            status.setText(I18n.t("modupgrade.reanalyse"));
        };

        analyse.setOnAction(e -> {
            refreshCandidates.run();
            runAnalyse.run();
        });
        // 开发期自检（-Dmcl.dev.upgradeselfcheck=1）：先手动选第二个目标实例，
        // 再按「分析」，最后印出仍然选中的是哪一个。
        // 用来回归「分析后目标实例被重置成第一个」这个 bug。
        if (System.getProperty("mcl.dev.upgradeselfcheck") != null) {
            javafx.animation.PauseTransition wait =
                    new javafx.animation.PauseTransition(javafx.util.Duration.seconds(3));
            wait.setOnFinished(event -> {
                if (instanceCombo.getItems().size() > 1) {
                    instanceCombo.getSelectionModel().select(1);
                }
                String picked = instanceCombo.getValue() == null ? "-" : instanceCombo.getValue().id();
                com.brisklauncher.mcl.security.SafeLog.info(
                        "[upgrade-selfcheck] 候选=" + instanceCombo.getItems().size() + " 分析前选中=" + picked);
                analyse.fire();
                javafx.animation.PauseTransition after =
                        new javafx.animation.PauseTransition(javafx.util.Duration.seconds(8));
                after.setOnFinished(done -> com.brisklauncher.mcl.security.SafeLog.info(
                        "[upgrade-selfcheck] 分析后选中=" + (instanceCombo.getValue() == null
                                ? "-" : instanceCombo.getValue().id())
                                + " 计划=" + (planHolder[0] == null ? "null" : planHolder[0].targetMc())));
                after.play();
            });
            wait.play();
        }
        // 可编辑下拉：选单选择与手动输入都要刷新目标实例候选，
        // 否则玩家把版本改成 26.1.2 之后，目标实例列表还停在 1.21.x（使用者回报的 bug）。
        mcCombo.valueProperty().addListener((obs, was, now) -> {
            invalidatePlan.run();
            refreshCandidates.run();
        });
        mcCombo.getEditor().textProperty().addListener((obs, was, now) -> {
            invalidatePlan.run();
            refreshCandidates.run();
        });
        loaderCombo.valueProperty().addListener((obs, was, now) -> {
            invalidatePlan.run();
            refreshCandidates.run();
        });

        start.setOnAction(e -> {
            ModUpgradeService.Plan plan = planHolder[0];
            if (plan == null) {
                return;
            }
            String mc = targetMcValue(mcCombo);
            LoaderInfo.LoaderType type = loaderCombo.getValue();
            GameInstance target = instanceCombo.getValue();
            if (!mc.equals(plan.targetMc())) {
                // 换过目标版本但没有重新分析：直接拒绝，避免装错
                MCLDialog.info(dialog, I18n.t("modupgrade.title"), I18n.t("modupgrade.reanalyse"));
                planHolder[0] = null;
                start.setDisable(true);
                return;
            }
            if (!createNew.isSelected() && target == null) {
                MCLDialog.info(dialog, I18n.t("modupgrade.title"), I18n.t("modupgrade.need_target"));
                return;
            }
            GameDirectory root = source.root();
            String name = newName.getText() == null ? "" : newName.getText().trim();
            TaskDialog.run(dialog, I18n.t("modupgrade.running"), progress -> {
                GameInstance resolved = createNew.isSelected()
                        ? ModUpgradeService.ensureTarget(context, root, mc, type,
                        name.isEmpty() ? mc : name, progress)
                        : target;
                ModUpgradeService.Result result = ModUpgradeService.apply(context, resolved,
                        plan, progress);
                return new Object[]{resolved, result};
            }, payload -> {
                Object[] values = (Object[]) payload;
                GameInstance resolved = (GameInstance) values[0];
                ModUpgradeService.Result result = (ModUpgradeService.Result) values[1];
                MCLDialog.info(dialog, I18n.t("modupgrade.title"),
                        I18n.t("modupgrade.done", result.installed(), resolved.id(),
                                result.failures().isEmpty() ? ""
                                        : " · " + I18n.t("modupgrade.failures",
                                        result.failures().size())));
            });
        });

        // MC 版本清单（背景载入，失败就用文字输入）
        Thread.ofVirtual().name("mcl-mod-upgrade-versions").start(() -> {
            List<String> versions = new ArrayList<>();
            try {
                for (MojangApi.VersionMeta meta : context.mojang().versions()) {
                    if (meta.isRelease()) {
                        versions.add(meta.id());
                    }
                    if (versions.size() >= 80) {
                        break;
                    }
                }
            } catch (Exception ignored) {
                // 离线时手动输入
            }
            Platform.runLater(() -> {
                if (versions.isEmpty()) {
                    mcCombo.getEditor().setText(source.mcVersion());
                    return;
                }
                mcCombo.getItems().setAll(versions);
                // 预设目标 = 来源版本（等于「只更新 Mod」，最安全）；
                // 想跨版本升级自己改下拉；开发期可用 -Dmcl.dev.upgrademc 指定。
                String devTarget = System.getProperty("mcl.dev.upgrademc");
                String preferred = (devTarget != null && !devTarget.isBlank())
                        ? devTarget.trim() : source.mcVersion();
                mcCombo.setValue(preferred);
                refreshCandidates.run();
                // 不要一打开就分析：先让玩家选好目标版本/加载器，再自己按「分析」
                status.setText(I18n.t("modupgrade.idle_hint"));
            });
        });

        HBox analyseRow = new HBox(8, analyse, status);
        analyseRow.setAlignment(Pos.CENTER_LEFT);

        HBox footer = new HBox(8, spacer, start);
        footer.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(10, header, sourceInfo, targetRow, targetInstanceRow, analyseRow,
                list, footer);
        root.setPadding(new Insets(16));
        root.setStyle("-fx-background-color: -brisk-bg; -fx-background-radius: 12;"
                + " -fx-border-color: -brisk-border; -fx-border-radius: 12;"
                + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 15, 0, 0, 5);");

        Scene scene = new Scene(root, 820, 660);
        UiKit.applyTheme(scene);
        scene.setFill(Color.TRANSPARENT);
        dialog.setScene(scene);
        UiKit.escToClose(scene, dialog);
        UiKit.makeDraggable(dialog, header);
        UiKit.popIn(dialog, root);
        dialog.show();
    }

    /** 目標版本：優先用輸入框內容（可編輯下拉），為空才退回選中的值。 */
    private static String targetMcValue(javafx.scene.control.ComboBox<String> combo) {
        String text = combo.getEditor().getText();
        if (text != null && !text.isBlank()) {
            return text.trim();
        }
        return combo.getValue() == null ? "" : combo.getValue().trim();
    }
}
