package com.brisklauncher.mcl.ui;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 主題「真的有沒有套到控件上」的自檢（需要桌面環境，會啟動 JavaFX Toolkit）。
 *
 * <p>為什麼要這支：第 151 輪之前，顏色散在 CSS 與 Java inline 樣式裡，主題只能一條條覆蓋選擇器，
 * 漏掉的控件就會保持預設薄荷綠。現在全部走 {@code -brisk-*} 設計變數，這支測試直接量
 * 「控件實際渲染出來的顏色」，確保每個主題都能真正改到背景、輸入框、ComboBox、按鈕、勾選框、
 * 滾動條與文字色。
 *
 * <p>跑法（先 {@code mvn -o compile}）：
 * <pre>
 *   java -Dprism.order=sw -cp "target\classes;build\install\brisk-launcher\lib\*" \
 *        com.brisklauncher.mcl.ui.ThemeCssSelfTest
 * </pre>
 * 結束碼 0 = 全過。
 */
public final class ThemeCssSelfTest {

    /** 一個主題的期望值（都取自 src/main/resources/themes/<id>/theme.css 的設計變數）。 */
    private record Expect(String id, Path css, String bg, String surface, String text,
                          String accent, String border2) {
    }

    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        Path base = Path.of("src/main/resources/styles/mcl-theme.css");
        if (!Files.isRegularFile(base)) {
            System.out.println("找不到 " + base.toAbsolutePath());
            System.exit(1);
        }
        List<Expect> expectations = new ArrayList<>();
        // 預設主題（不套任何主題 CSS）
        expectations.add(new Expect("default", null,
                "#F4FCFA", "#FFFFFF", "#14453F", "#0E9C8D", "#A8D8D1"));
        expectations.add(new Expect("night", themeCss("night"),
                "#14161D", "#1E222C", "#E4E8F0", "#7C9CFF", "#3E4658"));
        expectations.add(new Expect("sakura", themeCss("sakura"),
                "#FFF7F9", "#FFFFFF", "#7A2C42", "#D94A75", "#EDB2C4"));
        expectations.add(new Expect("contrast", themeCss("contrast"),
                "#FFFFFF", "#FFFFFF", "#000000", "#003C96", "#4D4D4D"));
        Path sample = Path.of("examples/my-theme/theme.css");
        if (Files.isRegularFile(sample)) {
            expectations.add(new Expect("my-theme", sample,
                    "#14101F", "#1E1830", "#EDE8FA", "#B79CFF", "#443A63"));
        }

        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        if (!started.await(30, TimeUnit.SECONDS)) {
            System.out.println("JavaFX 启动超时");
            System.exit(1);
        }
        try {
            for (Expect expect : expectations) {
                CountDownLatch done = new CountDownLatch(1);
                Platform.runLater(() -> {
                    try {
                        checkTheme(base, expect);
                    } finally {
                        done.countDown();
                    }
                });
                done.await(30, TimeUnit.SECONDS);
            }
        } finally {
            Platform.exit();
        }

        System.out.println();
        System.out.println("主题生效自检：" + (checks - failures) + "/" + checks + " 通过");
        if (failures > 0) {
            System.out.println("失败 " + failures + " 项");
            System.exit(1);
        }
    }

    private static Path themeCss(String id) {
        return Path.of("src/main/resources/themes", id, "theme.css");
    }

    /** 建一個跟主視窗一樣的結構：StackPane(app-root) → BorderPane(root-pane) → 各種控件。 */
    private static void checkTheme(Path base, Expect expect) {
        StackPane sceneRoot = new StackPane();
        sceneRoot.getStyleClass().add("app-root");
        BorderPane root = new BorderPane();
        root.getStyleClass().add("root-pane");
        sceneRoot.getChildren().add(root);

        Label logo = new Label("Brisk");
        logo.getStyleClass().add("logo");
        TextField field = new TextField("x");
        ComboBox<String> combo = new ComboBox<>();
        combo.getItems().add("Auto");
        combo.setValue("Auto");
        Spinner<Integer> spinner = new Spinner<>(1, 9, 3);
        CheckBox check = new CheckBox("ok");
        check.setSelected(true);
        Button plain = new Button("plain");
        Button primary = new Button("primary");
        primary.getStyleClass().add("primary-button");
        ScrollBar bar = new ScrollBar();
        VBox box = new VBox(8, logo, field, combo, spinner, check, plain, primary, bar);
        VBox.setVgrow(box, Priority.ALWAYS);
        root.setCenter(box);

        Scene scene = new Scene(sceneRoot, 600, 500);
        scene.getStylesheets().add(base.toUri().toString());
        if (expect.css() != null) {
            scene.getStylesheets().add(expect.css().toUri().toString());
        }
        root.applyCss();
        root.layout();

        check(expect.id() + ": 窗口背景", fill(root), expect.bg());
        check(expect.id() + ": 输入框背景", fill(field), expect.surface());
        check(expect.id() + ": ComboBox 背景", fill(combo), expect.surface());
        check(expect.id() + ": Spinner 背景", fill(spinner), expect.surface());
        check(expect.id() + ": 普通按钮背景", fill(plain), expect.surface());
        check(expect.id() + ": 主按钮是渐层（跟着 accent）",
                fill(primary).startsWith("linear-gradient"), fill(primary));
        check(expect.id() + ": 勾选框文字", text(check), expect.text());
        check(expect.id() + ": 标题文字", text(logo), expect.accent());
        check(expect.id() + ": ComboBox 显示文字", text(combo.lookup(".list-cell")), expect.text());
        check(expect.id() + ": 滚动条滑块", fill(bar.lookup(".thumb")), expect.border2());
    }

    // ---------- 取值 ----------

    private static void check(String label, String actual, String expected) {
        checks++;
        if (actual != null && actual.equalsIgnoreCase(expected)) {
            System.out.println("ok    " + label + "  = " + actual);
        } else {
            failures++;
            System.out.println("FAIL  " + label + "  = " + actual + "（期望 " + expected + "）");
        }
    }

    /** 條件式檢查（例如「是不是漸層」）。 */
    private static void check(String label, boolean ok, String detail) {
        checks++;
        if (ok) {
            System.out.println("ok    " + label + "  = " + detail);
        } else {
            failures++;
            System.out.println("FAIL  " + label + "  = " + detail);
        }
    }

    private static String fill(javafx.scene.Node node) {
        if (!(node instanceof javafx.scene.layout.Region region) || region.getBackground() == null
                || region.getBackground().getFills().isEmpty()) {
            return "none";
        }
        return hex(region.getBackground().getFills().get(0).getFill());
    }

    private static String text(javafx.scene.Node node) {
        if (!(node instanceof javafx.scene.control.Labeled labeled)) {
            return "none";
        }
        return hex(labeled.getTextFill());
    }

    private static String hex(Paint paint) {
        if (paint instanceof Color color) {
            if (color.getOpacity() < 0.999) {
                return String.format("#%02X%02X%02X@%.2f", Math.round(color.getRed() * 255),
                        Math.round(color.getGreen() * 255), Math.round(color.getBlue() * 255),
                        color.getOpacity());
            }
            return String.format("#%02X%02X%02X", Math.round(color.getRed() * 255),
                    Math.round(color.getGreen() * 255), Math.round(color.getBlue() * 255));
        }
        return String.valueOf(paint);
    }

    private ThemeCssSelfTest() {
    }
}
