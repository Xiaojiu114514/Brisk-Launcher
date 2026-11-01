package com.brisklauncher.mcl.ui;

import com.brisklauncher.mcl.config.ConfigDocument;
import com.brisklauncher.mcl.i18n.I18n;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 设定档编辑器：**文本 / 可视化**两种模式（照 Cloth Config 那种「一节一节改」的体验）。
 *
 * <ul>
 *   <li>文本：整档编辑，适合改注解、贴整段设定；</li>
 *   <li>可视化：把 JSON 的每个物件、TOML 的每个 {@code [section]}、properties 的键前缀
 *       各变成一节，栏位依型别给 CheckBox / 数字框 / 文字框 / 阵列框；</li>
 *   <li>储存前自动备份（{@code .mcl-backup-<时间>}），并用暂存档原子替换。</li>
 * </ul>
 */
public final class ConfigEditorDialog {

    private ConfigEditorDialog() {
    }

    public static void show(Window owner, Path file) {
        ConfigDocument document;
        try {
            document = ConfigDocument.load(file);
        } catch (Exception error) {
            MCLDialog.error(owner, I18n.t("config.title"),
                    I18n.t("config.load_failed", String.valueOf(error.getMessage())));
            return;
        }

        Stage dialog = new Stage();
        dialog.initOwner(owner);
        dialog.initModality(Modality.NONE);
        dialog.initStyle(StageStyle.TRANSPARENT);
        dialog.setTitle(I18n.t("config.title"));

        Label title = new Label("⚙ " + I18n.t("config.title") + " · " + file.getFileName());
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #14453F;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = UiKit.closeButtonStyled();
        close.setOnAction(e -> dialog.close());
        HBox header = new HBox(8, title, spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        Label pathLabel = new Label(file.toString());
        pathLabel.getStyleClass().add("cat-hint");
        pathLabel.setWrapText(true);
        Label formatTag = new Label(document.format().name()
                + (document.visualSupported() ? "" : " · " + I18n.t("config.visual_unsupported")));
        formatTag.getStyleClass().add("tag");
        formatTag.setMinWidth(Region.USE_PREF_SIZE);
        HBox pathRow = new HBox(8, pathLabel, formatTag);
        pathRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(pathLabel, Priority.ALWAYS);

        // ---- 模式切换 ----
        ToggleGroup modeGroup = new ToggleGroup();
        ToggleButton textMode = new ToggleButton(I18n.t("config.mode.text"));
        ToggleButton visualMode = new ToggleButton(I18n.t("config.mode.visual"));
        textMode.getStyleClass().add("pill-toggle");
        visualMode.getStyleClass().add("pill-toggle");
        textMode.setToggleGroup(modeGroup);
        visualMode.setToggleGroup(modeGroup);
        textMode.setSelected(true);
        visualMode.setDisable(!document.visualSupported());
        if (!document.visualSupported()) {
            visualMode.setTooltip(new Tooltip(document.visualError() == null
                    ? I18n.t("config.visual_unsupported")
                    : document.visualError()));
        }

        // ---- 文本模式 ----
        TextArea area = new TextArea(document.text());
        area.setWrapText(false);
        area.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 12px;");
        VBox textPane = new VBox(area);
        VBox.setVgrow(area, Priority.ALWAYS);

        // ---- 可视化模式 ----
        VBox visualBox = new VBox(8);
        visualBox.setPadding(new Insets(4));
        ScrollPane visualScroll = new ScrollPane(visualBox);
        visualScroll.setFitToWidth(true);
        visualScroll.getStyleClass().add("edge-to-edge");
        StackPane visualPane = new StackPane(visualScroll);

        Label status = new Label();
        status.getStyleClass().add("cat-hint");
        status.setWrapText(true);

        StackPane content = new StackPane(textPane);
        VBox.setVgrow(content, Priority.ALWAYS);

        Runnable rebuildVisual = () -> {
            visualBox.getChildren().clear();
            if (!document.visualSupported()) {
                visualBox.getChildren().add(UiKit.catHint(document.visualError() == null
                        ? I18n.t("config.visual_unsupported") : document.visualError()));
                return;
            }
            if (document.sections().isEmpty()) {
                visualBox.getChildren().add(UiKit.catHint(I18n.t("config.no_fields")));
                return;
            }
            int index = 0;
            for (ConfigDocument.Section section : document.sections()) {
                GridPane grid = new GridPane();
                grid.setHgap(12);
                grid.setVgap(8);
                grid.setPadding(new Insets(10, 4, 10, 4));
                int row = 0;
                for (ConfigDocument.Field field : section.fields()) {
                    Label key = new Label(field.key());
                    key.setStyle("-fx-font-weight: bold; -fx-text-fill: #14453F;");
                    key.setMinWidth(180);
                    if (field.comment() != null && !field.comment().isBlank()) {
                        Tooltip.install(key, new Tooltip(field.comment()));
                    }
                    Node editor = editorFor(field, document, status);
                    grid.add(key, 0, row);
                    grid.add(editor, 1, row);
                    GridPane.setHgrow(editor, Priority.ALWAYS);
                    row++;
                }
                String name = section.name() == null || section.name().isBlank()
                        ? I18n.t("config.section.general") : section.name();
                TitledPane pane = new TitledPane(name + "  (" + section.fields().size() + ")", grid);
                pane.setExpanded(index == 0);
                visualBox.getChildren().add(pane);
                index++;
            }
        };

        Runnable switchMode = () -> {
            boolean visual = visualMode.isSelected();
            if (visual) {
                // 文本改过就重新解析，避免可视化看到旧值
                try {
                    document.reloadFromText(area.getText());
                } catch (Exception error) {
                    MCLDialog.error(dialog, I18n.t("config.title"),
                            I18n.t("config.load_failed", String.valueOf(error.getMessage())));
                    textMode.setSelected(true);
                    return;
                }
                rebuildVisual.run();
                content.getChildren().setAll(visualPane);
            } else {
                // 回到文本模式：把可视化改过的值反映到文字（JSON 会重新排版）
                if (document.visualSupported()) {
                    try {
                        area.setText(document.render());
                    } catch (Exception ignored) {
                        // 产生失败就维持原文
                    }
                }
                content.getChildren().setAll(textPane);
            }
        };
        textMode.setOnAction(e -> switchMode.run());
        visualMode.setOnAction(e -> switchMode.run());

        // ---- 底部 ----
        Button reload = new Button(I18n.t("config.reload"));
        reload.setOnAction(e -> {
            try {
                ConfigDocument fresh = ConfigDocument.load(file);
                area.setText(fresh.text());
                document.reloadFromText(fresh.text());
                rebuildVisual.run();
                status.setText(I18n.t("config.reloaded"));
            } catch (Exception error) {
                MCLDialog.error(dialog, I18n.t("config.title"),
                        I18n.t("config.load_failed", String.valueOf(error.getMessage())));
            }
        });
        Button openFolder = new Button(I18n.t("config.open_folder"));
        openFolder.setOnAction(e -> DesktopActions.openFolder(file.getParent()));
        Button saveAs = new Button(I18n.t("config.save_as"));
        saveAs.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.t("config.save_as"));
            chooser.setInitialFileName(file.getFileName().toString());
            var target = chooser.showSaveDialog(dialog);
            if (target == null) {
                return;
            }
            try {
                ConfigDocument.writeText(target.toPath(), area.getText());
                status.setText(I18n.t("config.saved_to", target.getAbsolutePath()));
            } catch (Exception error) {
                MCLDialog.error(dialog, I18n.t("config.save_as"),
                        String.valueOf(error.getMessage()));
            }
        });
        Button save = new Button(I18n.t("config.save"));
        save.getStyleClass().add("primary-button");
        save.setOnAction(e -> {
            try {
                Path backup;
                if (visualMode.isSelected() && document.visualSupported()) {
                    backup = document.save();
                    area.setText(document.render());
                    rebuildVisual.run();
                } else {
                    backup = ConfigDocument.writeText(file, area.getText());
                    document.reloadFromText(area.getText());
                }
                status.setText(backup == null
                        ? I18n.t("config.saved", file.getFileName().toString())
                        : I18n.t("config.saved_backup", file.getFileName().toString(),
                        backup.getFileName().toString()));
            } catch (Exception error) {
                MCLDialog.error(dialog, I18n.t("config.save"),
                        String.valueOf(error.getMessage()));
            }
        });
        HBox footer = new HBox(8, reload, openFolder, saveAs, status, spacer, save);
        footer.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(status, Priority.ALWAYS);

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(16));
        root.setTop(new VBox(8, header, pathRow, new HBox(8, textMode, visualMode)));
        BorderPane.setMargin(root.getTop(), new Insets(0, 0, 8, 0));
        root.setCenter(content);
        BorderPane.setMargin(content, new Insets(0, 0, 8, 0));
        root.setBottom(footer);
        root.setStyle("-fx-background-color: #F4FCFA; -fx-background-radius: 12;"
                + " -fx-border-color: #BFE9E2; -fx-border-radius: 12;"
                + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 15, 0, 0, 5);");
        // 用主题样式覆盖 TextArea / TitledPane / ScrollPane 的系统灰底
        root.getStyleClass().add("config-shell");

        Scene scene = new Scene(root, 820, 640);
        UiKit.applyTheme(scene);
        scene.setFill(Color.TRANSPARENT);
        dialog.setScene(scene);
        UiKit.escToClose(scene, dialog);
        rebuildVisual.run();
        UiKit.makeDraggable(dialog, header);
        UiKit.popIn(dialog, root);
        dialog.show();

        // 开发期：直接切到可视化模式（截图/回归验证用）
        String devMode = System.getProperty("mcl.dev.configmode");
        if (devMode != null && devMode.toLowerCase(Locale.ROOT).startsWith("v")
                && document.visualSupported()) {
            visualMode.setSelected(true);
            switchMode.run();
        }
    }

    /** 依欄位型別產生編輯控件。 */
    private static Node editorFor(ConfigDocument.Field field, ConfigDocument document, Label status) {
        switch (field.type()) {
            case BOOLEAN -> {
                CheckBox box = new CheckBox();
                box.setSelected("true".equalsIgnoreCase(field.value()));
                box.selectedProperty().addListener((obs, was, now) -> {
                    document.setValue(field, String.valueOf(now));
                    status.setText(I18n.t("config.dirty"));
                });
                return box;
            }
            case NUMBER -> {
                TextField input = new TextField(field.value());
                input.setPrefWidth(140);
                input.textProperty().addListener((obs, was, now) -> {
                    if (now != null && now.matches("[-+]?\\d*\\.?\\d*([eE][-+]?\\d+)?")) {
                        document.setValue(field, now);
                        status.setText(I18n.t("config.dirty"));
                    }
                });
                return input;
            }
            case ARRAY -> {
                TextField input = new TextField(field.value());
                input.setStyle("-fx-font-family: 'Consolas', monospace;");
                Tooltip.install(input, new Tooltip(I18n.t("config.array_hint")));
                input.textProperty().addListener((obs, was, now) -> {
                    document.setValue(field, now);
                    status.setText(I18n.t("config.dirty"));
                });
                return input;
            }
            case STRING -> {
                TextField input = new TextField(field.value());
                input.textProperty().addListener((obs, was, now) -> {
                    document.setValue(field, now);
                    status.setText(I18n.t("config.dirty"));
                });
                return input;
            }
            default -> {
                TextField input = new TextField(field.value());
                input.setEditable(false);
                input.setDisable(true);
                return input;
            }
        }
    }

    /** 給版本設置的 config 列表用：判斷是不是設定檔（可編輯）。 */
    public static boolean looksEditable(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return false;
        }
        return ConfigDocument.detect(file) != ConfigDocument.Format.TEXT
                || file.getFileName().toString().toLowerCase(Locale.ROOT).matches(
                ".*\\.(txt|cfg|conf|ini|properties|toml|json)$");
    }
}
