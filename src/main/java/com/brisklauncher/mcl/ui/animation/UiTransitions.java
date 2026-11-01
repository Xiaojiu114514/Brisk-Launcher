package com.brisklauncher.mcl.ui.animation;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.scene.Node;
import javafx.util.Duration;

/** 集中管理 UI 過渡動畫，避免散落在各處。 */
public final class UiTransitions {

    private UiTransitions() {
    }

    public static void fadeIn(Node node, Duration duration) {
        node.setOpacity(0);
        node.setVisible(true);
        FadeTransition ft = new FadeTransition(duration, node);
        ft.setFromValue(0);
        ft.setToValue(1);
        ft.setInterpolator(Interpolator.EASE_OUT);
        ft.play();
    }

    public static void fadeOut(Node node, Duration duration, Runnable onDone) {
        FadeTransition ft = new FadeTransition(duration, node);
        ft.setFromValue(node.getOpacity());
        ft.setToValue(0);
        ft.setInterpolator(Interpolator.EASE_IN);
        ft.setOnFinished(e -> {
            node.setVisible(false);
            if (onDone != null) {
                onDone.run();
            }
        });
        ft.play();
    }

    /** 內容頁從下方輕微上浮 + 淡入。 */
    public static void slideInUp(Node node, Duration duration) {
        double distance = 14;
        node.setTranslateY(distance);
        node.setOpacity(0);
        node.setVisible(true);

        TranslateTransition tt = new TranslateTransition(duration, node);
        tt.setFromY(distance);
        tt.setToY(0);
        tt.setInterpolator(Interpolator.EASE_OUT);

        FadeTransition ft = new FadeTransition(duration, node);
        ft.setFromValue(0);
        ft.setToValue(1);
        ft.setInterpolator(Interpolator.EASE_OUT);

        tt.play();
        ft.play();
    }

    /** 舊頁面淡出 → 新頁面淡入 + 上浮。 */
    /** 頁面淡出（略往下沉）→ 新頁面淡入 + 從下方浮上來。 */
    public static void crossFade(Node oldNode, Node newNode, Duration out, Duration in, Runnable swap) {
        if (oldNode == null) {
            swap.run();
            slideInUp(newNode, in);
            return;
        }
        Timeline exit = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(oldNode.opacityProperty(), oldNode.getOpacity()),
                        new KeyValue(oldNode.translateYProperty(), 0.0)),
                new KeyFrame(out,
                        new KeyValue(oldNode.opacityProperty(), 0.0, Interpolator.EASE_IN),
                        new KeyValue(oldNode.translateYProperty(), 10.0, Interpolator.EASE_IN)));
        exit.setOnFinished(e -> {
            oldNode.setTranslateY(0);
            swap.run();
            slideInUp(newNode, in);
        });
        exit.play();
    }

    /** 彈窗「彈出來」：縮放 0.94 → 1 + 淡入（比單純淡入更有輕快感）。 */
    public static void popIn(javafx.stage.Stage stage, Node root) {
        if (root == null) {
            return;
        }
        root.setOpacity(0);
        root.setScaleX(0.94);
        root.setScaleY(0.94);
        Interpolator spring = Interpolator.SPLINE(0.2, 0.9, 0.2, 1);
        Timeline timeline = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(root.opacityProperty(), 0.0),
                        new KeyValue(root.scaleXProperty(), 0.94),
                        new KeyValue(root.scaleYProperty(), 0.94)),
                new KeyFrame(Duration.millis(180),
                        new KeyValue(root.opacityProperty(), 1.0, spring),
                        new KeyValue(root.scaleXProperty(), 1.0, spring),
                        new KeyValue(root.scaleYProperty(), 1.0, spring)));
        timeline.setOnFinished(e -> {
            root.setScaleX(1);
            root.setScaleY(1);
            root.setOpacity(1);
        });
        timeline.play();
    }
}
