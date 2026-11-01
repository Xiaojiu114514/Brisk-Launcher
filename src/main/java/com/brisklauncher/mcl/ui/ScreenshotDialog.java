package com.brisklauncher.mcl.ui;

import com.brisklauncher.mcl.MclContext;
import com.brisklauncher.mcl.game.GameIcons;
import com.brisklauncher.mcl.game.GameInstance;
import com.brisklauncher.mcl.i18n.I18n;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 截图管理：把 {@code <版本目录>/screenshots} 里的截图做成缩图墙。
 *
 * <p>游戏内按 F2 的截图直接躺在这里，但玩家要嘛在资源管理器里翻、要嘛开游戏找。
 * 这里提供：缩图预览、点击打开、在文件夹中显示、删除（进回收站）、
 * **把某张截图直接设成版本图标**（跟版本图标功能打通）。
 *
 * <p>{@link #createPanel} 给「版本设置 → 截图」页签直接用，{@link #show} 是独立弹窗。
 */
public final class ScreenshotDialog {

    private static final double TILE_WIDTH = 200;
    private static final double TILE_HEIGHT = 113;
    private static final DateTimeFormatter TIME = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private ScreenshotDialog() {
    }

    public static Path folderOf(GameInstance entry) {
        return entry.gameDir().resolve("screenshots");
    }

    /** 縮圖牆面板（可嵌進頁籤或彈窗）。 */
    public static Node createPanel(MclContext context, GameInstance entry) {
        TilePane tiles = new TilePane();
        tiles.setHgap(10);
        tiles.setVgap(10);
        tiles.setPrefColumns(6);

        ScrollPane scroll = new ScrollPane(tiles);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("edge-to-edge");
        scroll.setStyle("-fx-background-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Label count = new Label();
        count.getStyleClass().add("cat-hint");

        Runnable[] reload = new Runnable[1];
        reload[0] = () -> {
            List<Path> files = listScreenshots(entry);
            tiles.getChildren().clear();
            count.setText(I18n.t("screenshot.count", files.size()));
            if (files.isEmpty()) {
                tiles.getChildren().add(UiKit.catHint(I18n.t("screenshot.empty")));
                return;
            }
            for (Path file : files) {
                tiles.getChildren().add(tile(context, entry, file, () -> reload[0].run()));
            }
        };

        Button openDir = new Button(I18n.t("screenshot.open_dir"));
        openDir.setOnAction(e -> DesktopActions.openFolder(folderOf(entry)));
        Button refresh = new Button(I18n.t("screenshot.refresh"));
        refresh.setOnAction(e -> reload[0].run());
        Button iconFromLatest = new Button(I18n.t("screenshot.icon_from_latest"));
        iconFromLatest.setDisable(listScreenshots(entry).isEmpty());
        iconFromLatest.setOnAction(e -> {
            List<Path> files = listScreenshots(entry);
            if (files.isEmpty()) {
                return;
            }
            try {
                GameIcons.setIcon(entry, files.get(0));
                context.notifyInstanceIconChanged();
                MCLDialog.info(null, I18n.t("screenshot.set_icon"), I18n.t("screenshot.icon_set"));
            } catch (Exception error) {
                MCLDialog.error(null, I18n.t("screenshot.set_icon"),
                        I18n.t("version.icon.failed", String.valueOf(error.getMessage())));
            }
        });
        HBox toolbar = new HBox(8, openDir, refresh, iconFromLatest, count);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(10, toolbar, scroll);
        box.setPadding(new Insets(4));
        reload[0].run();
        return box;
    }

    /** 獨立彈窗。 */
    public static void show(Window owner, MclContext context, GameInstance entry) {
        Stage dialog = new Stage();
        dialog.initOwner(owner);
        dialog.initModality(Modality.NONE);
        dialog.initStyle(StageStyle.TRANSPARENT);
        dialog.setTitle(I18n.t("screenshot.title"));

        Label title = new Label("📷 " + I18n.t("screenshot.title") + " · " + entry.id());
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #14453F;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = UiKit.closeButtonStyled();
        close.setOnAction(e -> dialog.close());
        HBox header = new HBox(6, title, spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(16));
        root.setTop(header);
        root.setCenter(createPanel(context, entry));
        BorderPane.setMargin(root.getCenter(), new Insets(10, 0, 0, 0));
        root.setStyle("-fx-background-color: #F4FCFA; -fx-background-radius: 12;"
                + " -fx-border-color: #BFE9E2; -fx-border-radius: 12;"
                + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 15, 0, 0, 5);");

        Scene scene = new Scene(root, 900, 620);
        UiKit.applyTheme(scene);
        scene.setFill(Color.TRANSPARENT);
        dialog.setScene(scene);
        UiKit.escToClose(scene, dialog);
        UiKit.makeDraggable(dialog, header);
        UiKit.popIn(dialog, root);
        dialog.show();
    }

    private static List<Path> listScreenshots(GameInstance entry) {
        Path dir = folderOf(entry);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> result = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg");
                    })
                    .forEach(result::add);
        } catch (Exception ignored) {
            // 目录读不到就当没有截图
        }
        result.sort(Comparator.comparingLong(ScreenshotDialog::lastModified).reversed());
        return result;
    }

    private static long lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (Exception error) {
            return 0L;
        }
    }

    /** 一張截圖的卡片：縮圖 + 檔名 + 時間，右鍵有操作選單。 */
    private static Node tile(MclContext context, GameInstance entry, Path file, Runnable onChanged) {
        StackPane preview = new StackPane();
        preview.setMinSize(TILE_WIDTH, TILE_HEIGHT);
        preview.setPrefSize(TILE_WIDTH, TILE_HEIGHT);
        preview.setMaxSize(TILE_WIDTH, TILE_HEIGHT);
        preview.setStyle("-fx-background-color: #E4F8F4; -fx-background-radius: 10;");
        preview.getChildren().add(thumbnail(file));

        Label name = new Label(file.getFileName().toString());
        name.setStyle("-fx-font-size: 11px; -fx-text-fill: #14453F;");
        name.setMaxWidth(TILE_WIDTH);
        name.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        Label time = new Label(TIME.format(Instant.ofEpochMilli(lastModified(file))
                .atZone(ZoneId.systemDefault())));
        time.getStyleClass().add("cat-hint");

        VBox card = new VBox(3, preview, name, time);
        card.setPadding(new Insets(4));
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("skin-tile");
        card.setOnMouseClicked(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                open(file);
            }
        });

        ContextMenu menu = new ContextMenu();
        MenuItem openItem = new MenuItem(I18n.t("screenshot.open"));
        openItem.setOnAction(e -> open(file));
        MenuItem showItem = new MenuItem(I18n.t("screenshot.show_in_folder"));
        showItem.setOnAction(e -> DesktopActions.openFolder(file.getParent()));
        MenuItem iconItem = new MenuItem(I18n.t("screenshot.set_icon"));
        iconItem.setOnAction(e -> {
            try {
                GameIcons.setIcon(entry, file);
                context.notifyInstanceIconChanged();
                MCLDialog.info(null, I18n.t("screenshot.set_icon"), I18n.t("screenshot.icon_set"));
                onChanged.run();
            } catch (Exception error) {
                MCLDialog.error(null, I18n.t("screenshot.set_icon"),
                        I18n.t("version.icon.failed", String.valueOf(error.getMessage())));
            }
        });
        MenuItem deleteItem = new MenuItem(I18n.t("screenshot.delete"));
        deleteItem.setOnAction(e -> MCLDialog.confirm(null, I18n.t("screenshot.delete"),
                I18n.t("screenshot.delete_confirm", file.getFileName().toString()), () -> {
                    if (com.brisklauncher.mcl.util.RecycleBin.delete(file)) {
                        onChanged.run();
                    } else {
                        MCLDialog.error(null, I18n.t("screenshot.delete"),
                                I18n.t("screenshot.delete_failed"));
                    }
                }));
        menu.getItems().addAll(openItem, showItem, new SeparatorMenuItem(), iconItem, deleteItem);
        card.setOnContextMenuRequested(e -> menu.show(card, e.getScreenX(), e.getScreenY()));
        return card;
    }

    private static void open(Path file) {
        if (!DesktopActions.openFile(file)) {
            MCLDialog.error(null, I18n.t("screenshot.open"),
                    I18n.t("screenshot.open_failed", file.toString()));
        }
    }

    /**
     * 缩图：等比裁切（cover）填满卡片比例，避免 16:9 与 4:3 混在一起时变形。
     * 图片用后台解码，几十张 4K 截图也不会卡住界面。
     */
    private static Node thumbnail(Path file) {
        Image image = com.brisklauncher.mcl.security.GuardedImages
                .fromFile(file, 512, 0, true, true);
        ImageView view = new ImageView(image);
        view.setFitWidth(TILE_WIDTH);
        view.setFitHeight(TILE_HEIGHT);
        view.setPreserveRatio(false);
        Runnable applyViewport = () -> {
            double width = image.getWidth();
            double height = image.getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }
            double targetRatio = TILE_WIDTH / TILE_HEIGHT;
            double sourceRatio = width / height;
            if (sourceRatio > targetRatio) {
                double slice = height * targetRatio;
                view.setViewport(new Rectangle2D((width - slice) / 2, 0, slice, height));
            } else {
                double slice = width / targetRatio;
                view.setViewport(new Rectangle2D(0, (height - slice) / 2, width, slice));
            }
        };
        if (image.getProgress() >= 1.0) {
            applyViewport.run();
        } else {
            image.progressProperty().addListener((obs, was, now) -> {
                if (now != null && now.doubleValue() >= 1.0) {
                    applyViewport.run();
                }
            });
        }
        Rectangle clip = new Rectangle(TILE_WIDTH, TILE_HEIGHT);
        clip.setArcWidth(14);
        clip.setArcHeight(14);
        view.setClip(clip);
        return view;
    }
}
