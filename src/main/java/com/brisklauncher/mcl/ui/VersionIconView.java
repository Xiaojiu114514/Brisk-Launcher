package com.brisklauncher.mcl.ui;

import com.brisklauncher.mcl.game.GameDirectory;
import com.brisklauncher.mcl.game.GameIcons;
import com.brisklauncher.mcl.game.GameInstance;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

import java.nio.file.Path;
import java.util.Locale;

/**
 * 版本/文件夹图标的显示节点。
 *
 * <p>有自订图标就显示图片（圆角裁切）；没有就用「首字母 + 依 id 上色」的占位块，
 * 比统一一个方块更容易区分，也不需要额外资源档。
 */
public final class VersionIconView {

    private VersionIconView() {
    }

    public static Node of(GameInstance entry, double size) {
        Path icon = GameIcons.iconOf(entry).orElse(null);
        return build(icon, entry == null ? "" : entry.id(),
                entry == null ? "?" : initial(entry.id()), size);
    }

    public static Node of(GameDirectory root, double size) {
        Path icon = GameIcons.iconOf(root).orElse(null);
        return build(icon, root == null ? "" : root.path().toString(), "📁", size);
    }

    /** 泛用版：給基岩版等不是 {@code GameInstance} 的條目用。 */
    public static Node generic(Path icon, String seed, String glyph, double size) {
        return build(icon, seed, glyph, size);
    }

    private static Node build(Path icon, String seed, String glyph, double size) {
        StackPane box = new StackPane();
        box.setMinSize(size, size);
        box.setPrefSize(size, size);
        box.setMaxSize(size, size);
        box.setAlignment(Pos.CENTER);

        Image image = GameIcons.image(icon, size * 4);
        if (image != null) {
            ImageView view = new ImageView(image);
            view.setFitWidth(size);
            view.setFitHeight(size);
            view.setPreserveRatio(false);
            // 非正方形图片中心裁成正方形（cover），否则宽图会变成细长条、几乎看不到内容
            double width = image.getWidth();
            double height = image.getHeight();
            double slice = Math.min(width, height);
            if (width > 0 && height > 0) {
                view.setViewport(new Rectangle2D((width - slice) / 2, (height - slice) / 2,
                        slice, slice));
            }
            Rectangle clip = new Rectangle(size, size);
            clip.setArcWidth(size * 0.45);
            clip.setArcHeight(size * 0.45);
            view.setClip(clip);
            box.getChildren().add(view);
            return box;
        }

        Color[] palette = {
                Color.web("#F0A24C"), Color.web("#E07B96"), Color.web("#7FB069"),
                Color.web("#5B9BD5"), Color.web("#A97BD5"), Color.web("#E0A83C"),
                Color.web("#4FA6A0"), Color.web("#C97B5B")
        };
        int hash = seed == null ? 0 : Math.abs(seed.toLowerCase(Locale.ROOT).hashCode());
        Color color = palette[hash % palette.length];
        box.setStyle("-fx-background-color: " + toHex(color) + ";"
                + " -fx-background-radius: " + (int) Math.round(size * 0.28) + ";");
        Label label = new Label(glyph);
        label.setStyle("-fx-text-fill: white; -fx-font-weight: bold;"
                + " -fx-font-size: " + Math.max(11, Math.round(size * 0.44)) + "px;");
        box.getChildren().add(label);
        return box;
    }

    /** 取第一個「看得見」的字元當佔位圖示（中文/英文/數字都可以）。 */
    private static String initial(String id) {
        if (id == null || id.isBlank()) {
            return "?";
        }
        for (int i = 0; i < id.length(); i++) {
            char ch = id.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                return String.valueOf(Character.toUpperCase(ch));
            }
        }
        int first = id.codePointAt(0);
        return new String(Character.toChars(first));
    }

    private static String toHex(Color color) {
        return String.format("#%02X%02X%02X",
                (int) Math.round(color.getRed() * 255),
                (int) Math.round(color.getGreen() * 255),
                (int) Math.round(color.getBlue() * 255));
    }

    /** 選圖標用的檔案選擇器（版本/文件夾共用）。 */
    public static javafx.stage.FileChooser picker() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(com.brisklauncher.mcl.i18n.I18n.t("version.icon.pick"));
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
                "Image (*.png, *.jpg, *.jpeg, *.webp)", "*.png", "*.jpg", "*.jpeg", "*.webp"));
        return chooser;
    }
}
