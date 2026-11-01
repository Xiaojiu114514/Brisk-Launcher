package com.brisklauncher.mcl.ui;

import com.brisklauncher.mcl.MclContext;
import com.brisklauncher.mcl.i18n.I18n;
import com.brisklauncher.mcl.plugin.LauncherPlugin;
import com.brisklauncher.mcl.plugin.PluginManager;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 工具页：插件中心。
 *
 * <p>左边是插件列表（内建 + {@code data/plugins/*.jar}），右边是选中插件的面板。
 * 面板按 id 快取，切来切去不会重建；页面被丢弃时统一 {@link LauncherPlugin#dispose()}。
 */
public final class ToolsPage extends VBox implements MclPage {

    private final MclContext context;
    private final PluginManager plugins;
    private final ListView<PluginManager.Entry> list = new ListView<>();
    private final StackPane content = new StackPane();
    private final Label status = new Label();
    private final Map<String, Node> panels = new HashMap<>();
    private final CheckBox enabledBox = new CheckBox(I18n.t("tools.enabled"));
    private final Button removeButton = new Button(I18n.t("tools.remove"));
    private String currentId;

    public ToolsPage(MclContext context) {
        this.context = context;
        this.plugins = context.plugins();
        getStyleClass().add("page");
        setPadding(new Insets(18));
        setSpacing(12);

        Button importPlugin = new Button(I18n.t("tools.import"));
        importPlugin.getStyleClass().add("primary-button");
        importPlugin.setOnAction(e -> importPlugin());
        Button openDir = new Button(I18n.t("tools.open_dir"));
        openDir.setOnAction(e -> DesktopActions.openFolder(plugins.dir()));
        Button refresh = new Button(I18n.t("common.refresh"));
        refresh.setOnAction(e -> {
            plugins.reload();
            panels.clear();
            reload();
        });
        status.getStyleClass().add("cat-hint");
        HBox toolbar = new HBox(8, importPlugin, openDir, refresh, status);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        list.setCellFactory(view -> new PluginCell());
        list.setPrefWidth(300);
        list.setMinWidth(240);
        list.getSelectionModel().selectedItemProperty().addListener(
                (obs, was, now) -> select(now));
        list.setPlaceholder(UiKit.catHint(I18n.t("tools.empty")));

        enabledBox.selectedProperty().addListener((obs, was, now) -> {
            PluginManager.Entry entry = list.getSelectionModel().getSelectedItem();
            if (entry == null || entry.failed()) {
                return;
            }
            plugins.setEnabled(entry.id(), Boolean.TRUE.equals(now));
            list.refresh();
            select(entry);
        });
        removeButton.setOnAction(e -> {
            PluginManager.Entry entry = list.getSelectionModel().getSelectedItem();
            if (entry == null || entry.builtin()) {
                return;
            }
            MCLDialog.confirm(getScene().getWindow(), I18n.t("tools.remove"),
                    I18n.t("tools.remove_confirm", entry.name()), () -> {
                        panels.remove(entry.id());
                        plugins.remove(entry);
                        reload();
                    });
        });
        HBox switches = new HBox(10, enabledBox, removeButton);
        switches.setAlignment(Pos.CENTER_LEFT);

        VBox left = new VBox(8, UiKit.title(I18n.t("tools.plugins")), list, switches);
        HBox.setHgrow(list, Priority.ALWAYS);
        VBox.setVgrow(list, Priority.ALWAYS);
        left.setMinWidth(250);

        content.getStyleClass().add("card");
        content.setPadding(new Insets(14));
        StackPane.setAlignment(content, Pos.TOP_LEFT);
        HBox.setHgrow(content, Priority.ALWAYS);

        HBox body = new HBox(14, left, content);
        VBox.setVgrow(body, Priority.ALWAYS);

        // §4.4：工具页常驻风险小字（第三方插件不在支援范围）
        Label riskNote = UiKit.catHint(I18n.t("tools.risk_note"));
        riskNote.setWrapText(true);
        getChildren().addAll(
                UiKit.title(I18n.t("tools.title") + " ⚡"),
                UiKit.catHint(I18n.t("tools.subtitle")),
                riskNote,
                toolbar, body);
        reload();
    }

    @Override
    public void onShown() {
        plugins.reload();
        panels.clear();
        reload();
    }

    @Override
    public void onReleased() {
        disposePanels();
        list.setItems(javafx.collections.FXCollections.observableArrayList());
    }

    private void disposePanels() {
        for (Map.Entry<String, Node> entry : panels.entrySet()) {
            PluginManager.Entry descriptor = plugins.find(entry.getKey());
            if (descriptor != null && descriptor.plugin() != null) {
                try {
                    descriptor.plugin().dispose();
                } catch (Exception ignored) {
                    // 插件自己的清理失败不影响页面
                }
            }
        }
        panels.clear();
    }

    private void reload() {
        var entries = plugins.entries();
        list.setItems(javafx.collections.FXCollections.observableArrayList(entries));
        status.setText(I18n.t("tools.count", entries.stream().filter(entry -> !entry.failed()).count(),
                entries.stream().filter(PluginManager.Entry::failed).count()));
        if (!entries.isEmpty()) {
            // 开发期：指定要打开的插件（截图/回归验证用）
            String devTool = System.getProperty("mcl.dev.tool");
            if (devTool != null && !devTool.isBlank()) {
                list.getItems().stream()
                        .filter(entry -> devTool.equals(entry.id()))
                        .findFirst()
                        .ifPresentOrElse(entry -> list.getSelectionModel().select(entry),
                                () -> list.getSelectionModel().selectFirst());
            } else {
                list.getSelectionModel().selectFirst();
            }
        } else {
            content.getChildren().setAll(UiKit.catHint(I18n.t("tools.empty")));
        }
    }

    private void select(PluginManager.Entry entry) {
        if (entry == null) {
            return;
        }
        currentId = entry.id();
        enabledBox.setSelected(plugins.isEnabled(entry.id()));
        enabledBox.setDisable(entry.failed());
        removeButton.setDisable(entry.builtin());
        if (entry.failed()) {
            content.getChildren().setAll(new VBox(8,
                    new Label("⚠ " + entry.name()),
                    UiKit.catHint(entry.error()),
                    UiKit.catHint(I18n.t("tools.failed_hint"))));
            return;
        }
        if (!plugins.isEnabled(entry.id())) {
            content.getChildren().setAll(new VBox(8,
                    UiKit.title(entry.name()),
                    UiKit.catHint(I18n.t("tools.disabled_hint"))));
            return;
        }
        Node panel = panels.get(entry.id());
        if (panel == null) {
            try {
                panel = entry.plugin().createPanel(plugins.host());
            } catch (Throwable error) {
                panel = new VBox(8, new Label("⚠ " + entry.name()),
                        UiKit.catHint(String.valueOf(error)));
            }
            if (panel instanceof ScrollPane scroll) {
                scroll.setFitToWidth(true);
                scroll.getStyleClass().add("edge-to-edge");
            }
            panels.put(entry.id(), panel);
        }
        content.getChildren().setAll(panel);
    }

    private void importPlugin() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("tools.import"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Plugin (*.jar)", "*.jar"));
        File file = chooser.showOpenDialog(getScene().getWindow());
        if (file == null) {
            return;
        }
        try {
            plugins.install(file.toPath());
            panels.clear();
            reload();
            MCLDialog.info(getScene().getWindow(), I18n.t("tools.import"),
                    I18n.t("tools.imported", file.getName()));
        } catch (Exception error) {
            MCLDialog.error(getScene().getWindow(), I18n.t("tools.import"),
                    String.valueOf(error.getMessage()));
        }
    }

    /** 列表單元：圖示 + 名稱 + 說明 + 來源標籤。 */
    private final class PluginCell extends ListCell<PluginManager.Entry> {
        private final Label icon = new Label();
        private final Label name = new Label();
        private final Label desc = new Label();
        private final Label badge = new Label();
        private final HBox row;

        private PluginCell() {
            icon.setStyle("-fx-font-size: 20px;");
            // emoji 图示不给最小宽度会被 HBox 压成 "…"
            icon.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
            name.getStyleClass().add("entry-id");
            desc.getStyleClass().add("cat-hint");
            desc.setMaxWidth(180);
            badge.getStyleClass().add("tag");
            badge.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
            VBox text = new VBox(1, name, desc);
            HBox.setHgrow(text, Priority.ALWAYS);
            row = new HBox(8, icon, text, badge);
            row.setAlignment(Pos.CENTER_LEFT);
            setPrefWidth(0);
        }

        @Override
        protected void updateItem(PluginManager.Entry entry, boolean empty) {
            super.updateItem(entry, empty);
            if (empty || entry == null) {
                setGraphic(null);
                return;
            }
            icon.setText(entry.icon());
            name.setText(entry.name());
            desc.setText(entry.description());
            if (entry.failed()) {
                badge.setText("⚠");
            } else if (entry.builtin()) {
                badge.setText(I18n.t("tools.builtin"));
            } else {
                badge.setText(I18n.t("tools.external_unverified"));
            }
            setOpacity(plugins.isEnabled(entry.id()) || entry.failed() ? 1 : 0.5);
            setGraphic(row);
        }
    }
}
