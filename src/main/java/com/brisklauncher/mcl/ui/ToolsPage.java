package com.brisklauncher.mcl.ui;

import com.brisklauncher.mcl.MclContext;
import com.brisklauncher.mcl.i18n.I18n;
import com.brisklauncher.mcl.plugin.PluginManager;
import com.brisklauncher.mcl.plugin.api.LauncherPage;
import com.brisklauncher.mcl.plugin.api.PluginApi;
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
 * 面板按 id 快取，切来切去不会重建；页面被丢弃、使用者按「刷新」、停用/移除插件时
 * 统一 {@link com.brisklauncher.mcl.plugin.api.LauncherPlugin#onUnload()}。
 *
 * <p>插件只在启动时扫一次（{@link PluginManager#load()}），本页切来切去<b>不会</b>重扫 jar，
 * 所以不会再出现「每次进工具页都重算 SHA-256」的卡顿。
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
    private final Runnable unloadHook = this::disposePanels;

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
        Button openData = new Button(I18n.t("tools.open_data"));
        openData.setOnAction(e -> DesktopActions.openFolder(
                com.brisklauncher.mcl.AppPaths.pluginDataRoot()));
        Button refresh = new Button(I18n.t("common.refresh"));
        refresh.setOnAction(e -> {
            disposePanels();
            plugins.reload();
            reload();
        });
        status.getStyleClass().add("cat-hint");
        HBox toolbar = new HBox(8, importPlugin, openDir, openData, refresh, status);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        list.setCellFactory(view -> new PluginCell());
        list.setPrefWidth(320);
        list.setMinWidth(240);
        list.getSelectionModel().selectedItemProperty().addListener(
                (obs, was, now) -> select(now));
        list.setPlaceholder(UiKit.catHint(I18n.t("tools.empty")));

        enabledBox.selectedProperty().addListener((obs, was, now) -> {
            PluginManager.Entry entry = list.getSelectionModel().getSelectedItem();
            if (entry == null || entry.failed()) {
                return;
            }
            boolean enabled = Boolean.TRUE.equals(now);
            if (enabled == plugins.isEnabled(entry.id())) {
                return;
            }
            // 真的卸载 / 重新载入，而不是只把面板藏起来
            disposePanels();
            plugins.setEnabled(entry.id(), enabled);
            plugins.reload();
            reload();
        });
        removeButton.setOnAction(e -> {
            PluginManager.Entry entry = list.getSelectionModel().getSelectedItem();
            if (entry == null || entry.builtin()) {
                return;
            }
            MCLDialog.confirm(getScene().getWindow(), I18n.t("tools.remove"),
                    I18n.t("tools.remove_confirm", entry.name()), () -> {
                        disposePanels();
                        plugins.remove(entry);
                        reload();
                    });
        });
        HBox switches = new HBox(10, enabledBox, removeButton);
        switches.setAlignment(Pos.CENTER_LEFT);

        VBox left = new VBox(8, UiKit.title(I18n.t("tools.plugins")), list, switches);
        HBox.setHgrow(list, Priority.ALWAYS);
        VBox.setVgrow(list, Priority.ALWAYS);
        left.setMinWidth(260);

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
        // 插件卸载（刷新 / 停用 / 移除）前先把面板收干净，否则插件资源不会释放
        plugins.addBeforeUnloadListener(unloadHook);
        reload();
    }

    @Override
    public void onShown() {
        // 插件实例在启动时已载入，这里只重建列表（不重扫 jar、不重算摘要）
        reload();
    }

    @Override
    public void onReleased() {
        disposePanels();
        plugins.removeBeforeUnloadListener(unloadHook);
        list.setItems(javafx.collections.FXCollections.observableArrayList());
    }

    private void disposePanels() {
        for (Map.Entry<String, Node> entry : panels.entrySet()) {
            PluginManager.Entry descriptor = plugins.find(entry.getKey());
            if (descriptor != null && descriptor.plugin() != null) {
                try {
                    descriptor.plugin().onUnload();
                } catch (Exception ignored) {
                    // 插件自己的清理失败不影响页面
                }
            }
        }
        panels.clear();
    }

    private void reload() {
        // 记住当前选中的插件 id：刷新 / 停用 / 导入之后不要把使用者甩回第一项
        PluginManager.Entry previous = list.getSelectionModel().getSelectedItem();
        var entries = plugins.entries();
        list.setItems(javafx.collections.FXCollections.observableArrayList(entries));
        status.setText(I18n.t("tools.count", entries.stream().filter(entry -> !entry.failed()).count(),
                entries.stream().filter(PluginManager.Entry::failed).count()));
        if (entries.isEmpty()) {
            content.getChildren().setAll(UiKit.catHint(I18n.t("tools.empty")));
            return;
        }
        // 开发期：指定要打开的插件（截图/回归验证用）
        String devTool = System.getProperty("mcl.dev.tool");
        if (devTool != null && !devTool.isBlank()) {
            list.getItems().stream()
                    .filter(entry -> devTool.equals(entry.id()))
                    .findFirst()
                    .ifPresentOrElse(entry -> list.getSelectionModel().select(entry),
                            () -> list.getSelectionModel().selectFirst());
            return;
        }
        if (previous != null) {
            list.getItems().stream()
                    .filter(entry -> entry.id().equals(previous.id()))
                    .findFirst()
                    .ifPresentOrElse(entry -> list.getSelectionModel().select(entry),
                            () -> list.getSelectionModel().selectFirst());
        } else {
            list.getSelectionModel().selectFirst();
        }
    }

    private void select(PluginManager.Entry entry) {
        if (entry == null) {
            return;
        }
        enabledBox.setSelected(plugins.isEnabled(entry.id()));
        enabledBox.setDisable(entry.failed());
        removeButton.setDisable(entry.builtin() || entry.failed());
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
        if (!entry.hasPanel()) {
            // 只贡献导航页的插件：没有工具面板，给他看这个插件提供了什么
            VBox box = new VBox(8, UiKit.title(entry.name()),
                    UiKit.catHint(I18n.t("tools.no_panel")));
            if (!entry.pages().isEmpty()) {
                StringBuilder titles = new StringBuilder();
                for (LauncherPage page : entry.pages()) {
                    if (titles.length() > 0) {
                        titles.append(" · ");
                    }
                    titles.append(page.icon().isBlank() ? "" : page.icon() + " ").append(page.title());
                }
                box.getChildren().add(UiKit.catHint(I18n.t("tools.pages", titles.toString())));
            }
            box.getChildren().add(UiKit.catHint(I18n.t("tools.plugin_meta",
                    entry.version().isBlank() ? "-" : entry.version(),
                    entry.author().isBlank() ? "-" : entry.author(),
                    PluginApi.VERSION)));
            content.getChildren().setAll(box);
            return;
        }
        Node panel = panels.get(entry.id());
        if (panel == null) {
            try {
                panel = entry.plugin().createPanel(entry.context());
            } catch (Throwable error) {
                panel = errorPane(entry, error);
            }
            if (panel == null) {
                panel = UiKit.catHint(I18n.t("tools.no_panel"));
            }
            if (panel instanceof ScrollPane scroll) {
                scroll.setFitToWidth(true);
                scroll.getStyleClass().add("edge-to-edge");
            }
            panels.put(entry.id(), panel);
        }
        content.getChildren().setAll(panel);
    }

    private Node errorPane(PluginManager.Entry entry, Throwable error) {
        return new VBox(8, new Label("⚠ " + entry.name()),
                UiKit.catHint(String.valueOf(error)));
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
            disposePanels();
            plugins.install(file.toPath());
            reload();
            MCLDialog.info(getScene().getWindow(), I18n.t("tools.import"),
                    I18n.t("tools.imported", file.getName()));
        } catch (Exception error) {
            MCLDialog.error(getScene().getWindow(), I18n.t("tools.import"),
                    String.valueOf(error.getMessage()));
        }
    }

    /** 列表单元：图标 + 名称 + 说明 + 来源标签；说明被截断时用 tooltip 显示全文。 */
    private final class PluginCell extends ListCell<PluginManager.Entry> {
        private final Label icon = new Label();
        private final Label name = new Label();
        private final Label desc = new Label();
        private final Label badge = new Label();
        private final HBox row;
        private final javafx.scene.control.Tooltip tip = new javafx.scene.control.Tooltip();

        private PluginCell() {
            icon.setStyle("-fx-font-size: 20px;");
            // emoji 图示不给最小宽度会被 HBox 压成 "…"
            icon.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
            name.getStyleClass().add("entry-id");
            desc.getStyleClass().add("cat-hint");
            desc.setMaxWidth(200);
            badge.getStyleClass().add("tag");
            badge.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
            VBox text = new VBox(1, name, desc);
            HBox.setHgrow(text, Priority.ALWAYS);
            row = new HBox(8, icon, text, badge);
            row.setAlignment(Pos.CENTER_LEFT);
            setPrefWidth(0);
            // 插件说明经常很长，卡片放不下：整段放进 tooltip，悬停即可看全
            tip.setWrapText(true);
            tip.setMaxWidth(460);
            tip.setShowDelay(javafx.util.Duration.millis(350));
            tip.setHideDelay(javafx.util.Duration.millis(80));
        }

        @Override
        protected void updateItem(PluginManager.Entry entry, boolean empty) {
            super.updateItem(entry, empty);
            if (empty || entry == null) {
                setGraphic(null);
                setTooltip(null);
                return;
            }
            icon.setText(entry.icon());
            name.setText(entry.name());
            String description = entry.description();
            if ((description == null || description.isBlank()) && !entry.pages().isEmpty()) {
                description = I18n.t("tools.page_count", entry.pages().size());
            }
            desc.setText(description);
            setTooltip(tooltipFor(entry, description));
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

        /** 悬停提示：失败显示原因，否则显示完整说明（有版本/作者就补一行）。 */
        private javafx.scene.control.Tooltip tooltipFor(PluginManager.Entry entry, String description) {
            if (entry.failed()) {
                tip.setText(entry.name() + "\n" + entry.error());
                return tip;
            }
            StringBuilder text = new StringBuilder(
                    description == null || description.isBlank() ? entry.name() : description);
            if (!entry.version().isBlank() || !entry.author().isBlank()) {
                text.append('\n').append(I18n.t("tools.plugin_meta",
                        entry.version().isBlank() ? "-" : entry.version(),
                        entry.author().isBlank() ? "-" : entry.author(),
                        PluginApi.VERSION));
            }
            tip.setText(text.toString());
            return tip;
        }
    }
}
