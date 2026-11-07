package com.brisklauncher.mcl.ui;

import com.brisklauncher.mcl.MclContext;
import com.brisklauncher.mcl.game.GameIcons;
import com.brisklauncher.mcl.game.GameInstance;
import com.brisklauncher.mcl.game.GameDirectory;
import com.brisklauncher.mcl.i18n.I18n;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.awt.Desktop;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 版本管理页：按“大版本文件夹 → 实例”分组展示，提供设置、补全、打开、导出、删除与导入。
 * 对应开发方案 5.1 / 5.5。
 */
public final class VersionPage extends VBox implements MclPage {

    private final MclContext context;
    private final VBox listContainer = new VBox(12);
    private final Map<Path, Boolean> expanded = new HashMap<>();
    private final Label status = new Label();
    private Runnable iconUnsubscribe;

    public VersionPage(MclContext context) {
        this.context = context;
        getStyleClass().add("page");
        setPadding(new Insets(18));
        setSpacing(12);

        Button addRoot = new Button(I18n.t("version.add_root"));
        addRoot.getStyleClass().add("primary-button");
        addRoot.setOnAction(e -> addRoot());

        Button importPack = new Button(I18n.t("version.import_modpack"));
        importPack.setOnAction(e -> importModpack());

        Button refresh = new Button(I18n.t("common.refresh"));
        refresh.setOnAction(e -> reload());


        status.getStyleClass().add("cat-hint");
        HBox toolbar = new HBox(8, addRoot, importPack, refresh, status);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        ScrollPane scroll = new ScrollPane(listContainer);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("edge-to-edge");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        getChildren().addAll(UiKit.title(I18n.t("version.title") + " ✦"), toolbar, scroll);
        iconUnsubscribe = context.addIconChangeListener(this::reload);
        reload();
    }

    @Override
    public void onReleased() {
        if (iconUnsubscribe != null) {
            iconUnsubscribe.run();
            iconUnsubscribe = null;
        }
    }

    @Override
    public void onShown() {
        reload();
    }

    public void reload() {
        com.brisklauncher.mcl.security.SafeLog.info("[list] VersionPage.reload");
        listContainer.getChildren().clear();
        List<GameDirectory> roots = context.gameRepository().roots();
        if (roots.isEmpty()) {
            listContainer.getChildren().add(UiKit.catHint(I18n.t("version.empty_roots")));
            return;
        }
        int total = 0;
        for (GameDirectory root : roots) {
            List<GameInstance> entries = context.gameRepository().versions(root);
            total += entries.size();
            listContainer.getChildren().add(buildGroup(root, entries));
        }
        status.setText(I18n.t("version.total", roots.size(), total));
    }

    private VBox buildGroup(GameDirectory root, List<GameInstance> entries) {
        // 默认只展开“当前选中的大版本文件夹”，其余折叠，避免一屏铺满几十个实例
        boolean isExpanded = expanded.computeIfAbsent(root.path(), path -> isCurrentRoot(root));
        expanded.put(root.path(), isExpanded);

        Label arrow = new Label(isExpanded ? "▼" : "▶");
        arrow.getStyleClass().add("tree-arrow");

        Label name = new Label(root.displayName());
        name.getStyleClass().add("tree-root-name");
        Label pathLabel = new Label(root.path().toString() + "   (" + entries.size() + ")");
        pathLabel.getStyleClass().addAll("cat-hint", "tree-path");

        VBox titleBox = new VBox(2, name, pathLabel);
        HBox.setHgrow(titleBox, Priority.ALWAYS);

        javafx.scene.Node rootIcon = VersionIconView.of(root, 30);

        Button pickIcon = new Button("🖼");
        pickIcon.getStyleClass().add("icon-button");
        pickIcon.setTooltip(new Tooltip(I18n.t("version.root_icon")));
        pickIcon.setOnAction(e -> pickRootIcon(root));

        Button open = new Button("📁");
        open.getStyleClass().add("icon-button");
        open.setTooltip(new Tooltip(I18n.t("version.open_folder")));
        open.setOnAction(e -> openFolder(root.path()));

        Button rename = new Button("✏");
        rename.getStyleClass().add("icon-button");
        rename.setTooltip(new Tooltip(I18n.t("version.root_rename")));
        rename.setOnAction(e -> MCLDialog.prompt(getScene().getWindow(),
                I18n.t("version.root_rename"), I18n.t("version.root_rename_hint"),
                root.displayName(), input -> null,
                input -> {
                    context.gameRepository().renameRoot(root, input);
                    reload();
                }));

        Button remove = new Button("🗑");
        remove.getStyleClass().add("icon-button");
        remove.setTooltip(new Tooltip(I18n.t("version.remove_root")));
        remove.setOnAction(e -> MCLDialog.confirm(getScene().getWindow(),
                I18n.t("version.remove_root"), I18n.t("version.remove_root_confirm", root.displayName()),
                () -> {
                    context.gameRepository().removeRoot(root);
                    reload();
                }));

        HBox header = new HBox(8, arrow, rootIcon, titleBox, pickIcon, rename, open, remove);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(8, 10, 8, 10));
        header.getStyleClass().add("tree-root-header");

        VBox entriesBox = new VBox(6);
        entriesBox.setPadding(new Insets(6, 0, 6, 16));
        for (GameInstance entry : entries) {
            entriesBox.getChildren().add(buildEntryCard(entry));
        }
        entriesBox.setVisible(isExpanded);
        entriesBox.setManaged(isExpanded);

        header.setOnMouseClicked(e -> {
            if (e.getTarget() instanceof Button) {
                return;
            }
            boolean nowExpanded = !expanded.getOrDefault(root.path(), false);
            expanded.put(root.path(), nowExpanded);
            arrow.setText(nowExpanded ? "▼" : "▶");
            entriesBox.setVisible(nowExpanded);
            entriesBox.setManaged(nowExpanded);
        });

        VBox group = new VBox(header, entriesBox);
        group.getStyleClass().add("tree-root-group");
        // 开发期：用截图检查「大版本文件夹」的悬停底色（-Dmcl.dev.hoverroot=1）
        if (System.getProperty("mcl.dev.hoverroot") != null) {
            header.pseudoClassStateChanged(
                    javafx.css.PseudoClass.getPseudoClass("hover"), true);
        }
        return group;
    }

    /** 當前使用的大版本文件夾（啟動頁最後選中的那個）。 */
    private boolean isCurrentRoot(GameDirectory root) {
        String remembered = context.uiState().lastRoot();
        return !remembered.isBlank() && root.path().toString().equals(remembered);
    }

    private HBox buildEntryCard(GameInstance entry) {
        Label id = new Label(entry.id());
        id.getStyleClass().add("entry-id");
        boolean complete = context.gameRepository().looksComplete(entry);
        Label meta = new Label(entry.subtitle() + "   " + (complete ? "✔" : "⚠ " + I18n.t("launch.needs_repair")));
        meta.getStyleClass().add("instance-meta");

        VBox text = new VBox(2, id, meta);
        HBox.setHgrow(text, Priority.ALWAYS);

        Button settings = new Button("⚙");
        settings.getStyleClass().add("icon-button");
        settings.setTooltip(new Tooltip(I18n.t("version.settings.title")));
        settings.setOnAction(e -> VersionSettingsDialog.show(getScene().getWindow(), context, entry, this::reload));

        Button open = new Button("📁");
        open.getStyleClass().add("icon-button");
        open.setTooltip(new Tooltip(I18n.t("version.open_folder")));
        open.setOnAction(e -> openFolder(entry.versionDir()));

        Button delete = new Button("🗑");
        delete.getStyleClass().add("icon-button");
        delete.setTooltip(new Tooltip(I18n.t("common.delete")));
        delete.setOnAction(e -> {
            // 正在安装/补全的版本不给删：就算进度框已经锁住主窗口，也要防别的路径进来
            if (com.brisklauncher.mcl.game.install.InstallLocks.isActive(
                    com.brisklauncher.mcl.game.install.InstallLocks.key(
                            entry.root().path().toString(), entry.id()))) {
                MCLDialog.info(getScene().getWindow(), I18n.t("common.delete"),
                        I18n.t("install.locked_delete", entry.id()));
                return;
            }
            boolean hasSaves = context.gameRepository().hasSaves(entry);
            String message = hasSaves
                    ? I18n.t("version.settings.delete_with_saves")
                    : I18n.t("version.settings.delete_confirm", entry.id());
            MCLDialog.confirm(getScene().getWindow(), I18n.t("common.delete"), message, () -> {
                try {
                    context.gameRepository().delete(entry);
                } catch (Exception error) {
                    MCLDialog.error(getScene().getWindow(), I18n.t("common.delete"), error.getMessage());
                } finally {
                    // 不论成功或失败都刷新：失败时版本还在（两阶段删除会把名字改回去），
                    // 列表必须跟磁碟一致，否则会出现「提示删除失败但版本消失了」。
                    reload();
                }
            });
        });

        // 卡片上只留三个按钮：版本设置 / 打开版本文件夹 / 删除。
        // 其余（重命名、校验、导出整合包、截图、服务端包、Mod 升级、图标）都在「版本设置」弹窗里，
        // 按钮太多反而看不清、也容易点错。
        HBox card = new HBox(8, VersionIconView.of(entry, 36), text, settings, open, delete);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setPadding(new Insets(8, 10, 8, 10));
        card.getStyleClass().add("entry-card");
        return card;
    }

    private void pickRootIcon(GameDirectory root) {
        File file = VersionIconView.picker().showOpenDialog(getScene().getWindow());
        if (file == null) {
            return;
        }
        try {
            GameIcons.setIcon(root, file.toPath());
            reload();
        } catch (Exception error) {
            MCLDialog.error(getScene().getWindow(), I18n.t("version.root_icon"),
                    I18n.t("version.icon.failed", String.valueOf(error.getMessage())));
        }
    }

    // ---------- 操作 ----------

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

    private void importModpack() {
        List<GameDirectory> roots = context.gameRepository().roots();
        if (roots.isEmpty()) {
            MCLDialog.info(getScene().getWindow(), I18n.t("version.title"), I18n.t("version.empty_roots"));
            return;
        }
        // 顺序：先选目标大版本文件夹（现有）→ 再选文件（现有）→ 最后确认版本名（默认整合包名，可改）
        MCLDialog.choose(getScene().getWindow(), I18n.t("version.import_modpack"),
                I18n.t("version.target_root_hint"), roots,
                root -> root.displayName() + "  (" + root.path() + ")",
                root -> pickModpackFile(root));
    }

    private void pickModpackFile(GameDirectory root) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("version.import_modpack"));
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Modrinth Modpack (*.mrpack, *.zip)", "*.mrpack", "*.zip"));
        File file = chooser.showOpenDialog(getScene().getWindow());
        if (file == null) {
            return;
        }
        importWithName(root, file.toPath(), defaultPackName(file.toPath()));
    }

    private void importWithName(GameDirectory root, Path file, String defaultName) {
        MCLDialog.prompt(getScene().getWindow(), I18n.t("version.import_modpack"),
                I18n.t("version.import_name_hint"), defaultName, name ->
                        TaskDialog.run(getScene().getWindow(), I18n.t("version.import_modpack"), progress ->
                                        context.modpackInstaller().install(root, file, name, progress),
                                entry -> {
                                    // 通知所有页面（启动页的实例列表也要立刻看到新整合包）
                                    context.notifyInstancesChanged();
                                    reload();
                                    MCLDialog.info(getScene().getWindow(), I18n.t("version.import_modpack"),
                                            I18n.t("launch.import_done", entry.id()));
                                }));
    }

    /** 默认实例名：整合包里登记的显示名，读不出来就用文件名。 */
    private String defaultPackName(Path file) {
        try {
            var index = context.modpackInstaller().peek(file);
            String name = index.displayName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        } catch (Exception ignored) {
            // 读不出来时退回文件名
        }
        String fileName = file.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static void openFolder(Path path) {
        DesktopActions.openFolder(path);
    }
}
