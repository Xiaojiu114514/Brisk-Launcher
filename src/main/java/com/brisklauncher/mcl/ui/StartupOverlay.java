package com.brisklauncher.mcl.ui;

import com.brisklauncher.mcl.i18n.I18n;
import com.brisklauncher.mcl.game.launch.LaunchProgress;
import com.brisklauncher.mcl.ui.animation.UiTransitions;
import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.RotateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * 启动遮罩层：覆盖整个应用，阻挡一切点击，直到启动完成或失败。
 * 安全：只显示阶段名，不显示参数、令牌、日志。
 */
public final class StartupOverlay extends StackPane {

    private final Label stageLabel = new Label();
    private final Label instanceLabel = new Label();
    private final ProgressBar progressBar = new ProgressBar(0);
    private final RotateTransition pawSpin;
    private final Button cancelButton = new Button(I18n.t("startup.cancel"));
    private Runnable onCancel;

    public StartupOverlay() {
        getStyleClass().add("startup-overlay");
        setVisible(false);
        setOpacity(0);

        Label paw = new Label("✦");
        paw.getStyleClass().add("startup-paw");

        pawSpin = new RotateTransition(Duration.seconds(2.4), paw);
        pawSpin.setByAngle(360);
        pawSpin.setCycleCount(Animation.INDEFINITE);
        pawSpin.setInterpolator(Interpolator.LINEAR);

        instanceLabel.getStyleClass().add("startup-instance");
        stageLabel.getStyleClass().add("startup-stage");

        progressBar.setPrefWidth(320);
        progressBar.getStyleClass().add("startup-progress");

        Button cancel = cancelButton;
        cancel.getStyleClass().add("window-button");
        cancel.setOnAction(e -> requestCancel());

        VBox content = new VBox(14, paw, instanceLabel, stageLabel, progressBar, cancel);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(36));
        content.getStyleClass().add("startup-card");

        StackPane wrapper = new StackPane(content);
        wrapper.setAlignment(Pos.CENTER);
        getChildren().add(wrapper);
    }

    public void begin(String instanceDisplayName) {
        begin(instanceDisplayName, null);
    }

    /** onCancel 非空时，“取消启动”会真正终止安装/启动流程。 */
    public void begin(String instanceDisplayName, Runnable onCancel) {
        this.onCancel = onCancel;
        instanceLabel.setText(instanceDisplayName);
        stageLabel.setText(stageText(LaunchProgress.PREPARING));
        progressBar.setProgress(0);
        cancelButton.setDisable(false);
        cancelButton.setText(I18n.t("startup.cancel"));
        pawSpin.play();

        setVisible(true);
        UiTransitions.fadeIn(this, Duration.millis(220));
    }

    private void requestCancel() {
        cancelButton.setDisable(true);
        stageLabel.setText(I18n.t("startup.cancel_requested"));
        if (onCancel != null) {
            Runnable action = onCancel;
            onCancel = null;
            try {
                action.run();
            } catch (Exception ignored) {
                // 取消失败也不阻塞 UI
            }
        }
    }

    public void showCancelled() {
        stageLabel.setText(I18n.t("startup.cancelled"));
        progressBar.setProgress(0);
        pawSpin.pause();
    }

    public void updateProgress(LaunchProgress progress) {
        stageLabel.setText(stageText(progress));
        progressBar.setProgress(progress.weight());
    }

    public void succeed(Runnable onDone) {
        stageLabel.setText(I18n.t("startup.success") + " ⚡");
        progressBar.setProgress(1);
        pawSpin.pause();
        UiTransitions.fadeOut(this, Duration.millis(260), onDone);
    }

    public void fail(String safeMessage, Runnable onClose) {
        stageLabel.setText(I18n.t("startup.failed") + "：" + safeMessage);
        progressBar.setProgress(0);
        pawSpin.pause();
        // 让玩家有时间看信息，再自动淡出
        javafx.application.Platform.runLater(() ->
                new Thread(() -> {
                    try {
                        Thread.sleep(1600);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    javafx.application.Platform.runLater(() ->
                            UiTransitions.fadeOut(this, Duration.millis(260), onClose));
                }).start());
    }

    /** 立即淡出（失败详情改用弹窗展示时，遮罩不应继续挡着）。 */
    public void dismiss() {
        pawSpin.pause();
        UiTransitions.fadeOut(this, Duration.millis(160), () -> { });
    }

    /**
     * 视窗关闭或 UI 重建时呼叫：停掉无限循环的旋转动画。
     *
     * <p>动画时间轴会强引用旧遮罩节点，重建后不停止就会留一整套旧 UI。
     */
    public void dispose() {
        pawSpin.stop();
    }

    /** 阶段名走语言包，避免 LaunchProgress 内建中文写死。（崩潰時間線也復用它） */
    public static String stageText(LaunchProgress progress) {
        return switch (progress) {
            case PREPARING        -> I18n.t("startup.preparing");
            case CHECKING_FILES   -> I18n.t("startup.checking_files");
            case INSTALLING       -> I18n.t("startup.installing");
            case INSTALLING_JAVA  -> I18n.t("startup.installing_java");
            case RESOLVING_JAVA   -> I18n.t("startup.resolving_java");
            case BUILDING_ARGS    -> I18n.t("startup.building_args");
            case LAUNCHING        -> I18n.t("startup.launching");
            case RUNNING          -> I18n.t("startup.running");
        };
    }
}