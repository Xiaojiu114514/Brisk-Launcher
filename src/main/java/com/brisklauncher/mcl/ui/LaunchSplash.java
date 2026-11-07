package com.brisklauncher.mcl.ui;

import com.brisklauncher.mcl.i18n.I18n;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.RotateTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeType;
import javafx.util.Duration;

/**
 * 啟動動畫（雙擊後第一眼看到的視窗）。
 *
 * <p>為什麼要有它：JVM 起來、JavaFX Toolkit 初始化、{@code MclContext}（ACL 加固、實例掃描、
 * 下載器建立）這些事情加起來要 1 秒以上，在那段時間裡使用者什麼都看不到，就會以為
 * 「沒反應／啟動變慢」。所以 {@code start()} 一拿到 Stage 就先顯示這個小視窗（動畫立刻跑），
 * 重活丟到背景執行緒，做完再換成真正的主介面。
 *
 * <p><b>刻意做得很輕</b>（啟動速度是這支類別的第一目標）：
 * <ul>
 *   <li>不用 {@code ProgressBar}（第一次載入它的 skin 要幾十毫秒）—— 進度條是兩個 {@link Region}；</li>
 *   <li>不用 emoji 當圖示（會觸發 emoji 字型回退載入）—— 閃電是 {@link SVGPath}；</li>
 *   <li>建構子裡不碰 {@link I18n}：文字由 {@code MclApplication} 在視窗顯示之後才填
 *       （語言包載入 ~40ms 就從「開窗前的路徑」上消失了）。</li>
 * </ul>
 *
 * <p>配色全走 {@code -brisk-*} 設計變數，第三方主題只要覆蓋變數就會跟著換色。
 */
public final class LaunchSplash extends StackPane {

    /** 閃電圖形（Material 風格，24×24 座標系）。 */
    private static final String BOLT_PATH =
            "M13 2 L4.5 13.5 L10 13.5 L9 22 L18 10 L12 10 Z";

    private final Label status = new Label();
    private final Region bar = new Region();
    private final Circle ring = new Circle(26);
    private final SVGPath bolt = new SVGPath();
    private RotateTransition spin;
    private ScaleTransition pulse;
    private Timeline dots;
    private Timeline slider;
    private boolean running;
    private int ticks;

    public LaunchSplash() {
        getStyleClass().add("splash-root");
        setAlignment(Pos.CENTER);

        ring.getStyleClass().add("splash-ring");
        ring.setFill(javafx.scene.paint.Color.TRANSPARENT);
        ring.setStrokeType(StrokeType.INSIDE);
        ring.getStrokeDashArray().setAll(22.0, 14.0);
        ring.setStrokeWidth(3);

        bolt.setContent(BOLT_PATH);
        bolt.getStyleClass().add("splash-bolt");
        bolt.setScaleX(0.9);
        bolt.setScaleY(0.9);
        StackPane mark = new StackPane(ring, bolt);
        mark.setMinSize(64, 64);
        mark.setPrefSize(64, 64);

        Label title = new Label("Brisk Launcher");
        title.getStyleClass().add("splash-title");

        status.getStyleClass().add("splash-status");

        // 進度條：底色軌道 + 一小塊會來回跑的方塊（不用 ProgressBar / skin）
        Region track = new Region();
        track.getStyleClass().add("splash-track");
        track.setPrefSize(180, 4);
        track.setMaxSize(180, 4);
        bar.getStyleClass().add("splash-bar");
        bar.setPrefSize(56, 4);
        bar.setMaxSize(56, 4);
        StackPane barBox = new StackPane(track, bar);
        barBox.setAlignment(Pos.CENTER_LEFT);
        barBox.setPrefSize(180, 4);
        barBox.setMaxSize(180, 4);

        VBox card = new VBox(12, mark, title, status, barBox);
        card.getStyleClass().add("splash-card");
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(22, 30, 22, 30));
        card.setMaxSize(VBox.USE_PREF_SIZE, VBox.USE_PREF_SIZE);

        getChildren().add(card);
    }

    /** 開始跑動畫（重複呼叫無害）。 */
    public void play() {
        if (running) {
            return;
        }
        running = true;

        spin = new RotateTransition(Duration.seconds(2.2), ring);
        spin.setByAngle(360);
        spin.setCycleCount(Animation.INDEFINITE);
        spin.setInterpolator(Interpolator.LINEAR);
        spin.play();

        pulse = new ScaleTransition(Duration.millis(900), bolt);
        pulse.setFromX(0.82);
        pulse.setFromY(0.82);
        pulse.setToX(1.0);
        pulse.setToY(1.0);
        pulse.setCycleCount(Animation.INDEFINITE);
        pulse.setAutoReverse(true);
        pulse.setInterpolator(Interpolator.EASE_BOTH);
        pulse.play();

        slider = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(bar.translateXProperty(), 0)),
                new KeyFrame(Duration.seconds(1.05),
                        new KeyValue(bar.translateXProperty(), 124, Interpolator.EASE_BOTH)));
        slider.setAutoReverse(true);
        slider.setCycleCount(Animation.INDEFINITE);
        slider.play();

        // 狀態列文字後面加點點，讓「還在工作」這件事更明顯（第一次 tick 也會補上文字）
        dots = new Timeline(new KeyFrame(Duration.seconds(0.45), event -> tick()));
        dots.setCycleCount(Animation.INDEFINITE);
        dots.play();
        tick();
    }

    private void tick() {
        ticks++;
        String base = I18n.t("splash.preparing");
        int count = ticks % 4;
        status.setText(count == 0 ? base : base + ".".repeat(count));
    }

    /** 換語言後重新取一次文字（splash 期間通常還沒切換，保險用）。 */
    public void refreshText() {
        String current = status.getText();
        if (current == null || current.isBlank()) {
            status.setText(I18n.t("splash.preparing"));
        }
    }

    /** 給「已經加載到哪一步」之類的短句（可選，不呼叫也不會怎樣）。 */
    public void setStatus(String text) {
        if (text != null && !text.isBlank()) {
            status.setText(text);
        }
    }

    /** 停掉所有動畫（切換到主介面前一定要呼叫，否則時間軸會一直引用舊節點）。 */
    public void stop() {
        running = false;
        if (spin != null) {
            spin.stop();
            spin = null;
        }
        if (pulse != null) {
            pulse.stop();
            pulse = null;
        }
        if (dots != null) {
            dots.stop();
            dots = null;
        }
        if (slider != null) {
            slider.stop();
            slider = null;
        }
    }

    /** 淡出（停動畫 + 透明），完成後回呼。 */
    public void fadeOut(Runnable onDone) {
        stop();
        FadeTransition fade = new FadeTransition(Duration.millis(160), this);
        fade.setFromValue(getOpacity());
        fade.setToValue(0);
        fade.setOnFinished(event -> {
            if (onDone != null) {
                onDone.run();
            }
        });
        fade.play();
    }

    /** 測試 / 除錯用：目前是否在跑動畫。 */
    public boolean running() {
        return running;
    }

    /** 閃一下主色（初始化完成時用）。 */
    public void flash() {
        Timeline timeline = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(bolt.opacityProperty(), 0.35)),
                new KeyFrame(Duration.millis(140), new KeyValue(bolt.opacityProperty(), 1.0)));
        timeline.play();
    }
}
