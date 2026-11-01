package com.brisklauncher.mcl.ui;

import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

/**
 * 无边框窗口（StageStyle.TRANSPARENT）没有系统边框，这里补上"拖边调整大小"。
 *
 * <p>在场景根节点上监听鼠标，靠近边缘 6px 内改变光标并在拖动时调整窗口大小；
 * 中间区域交给各页面自己的点击逻辑处理。
 */
public final class WindowResizer {

    private static final double EDGE = 6;

    private WindowResizer() {
    }

    public static void install(Stage stage) {
        Scene scene = stage.getScene();
        if (scene == null) {
            return;
        }
        double[] drag = new double[5]; // startX, startY, width, height, x, y -> 用 5..5 不匹配，改用数组 6
        double[] state = new double[6];
        int[] mode = new int[1]; // 0 none, 1 W, 2 E, 3 N, 4 S
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, event -> {
            if (mode[0] != 0) {
                return;
            }
            double x = event.getSceneX();
            double y = event.getSceneY();
            double w = scene.getWidth();
            double h = scene.getHeight();
            Cursor cursor = Cursor.DEFAULT;
            if (x <= EDGE) {
                cursor = Cursor.H_RESIZE;
            } else if (x >= w - EDGE) {
                cursor = Cursor.H_RESIZE;
            }
            if (y <= EDGE) {
                cursor = cursor == Cursor.H_RESIZE ? Cursor.NW_RESIZE : Cursor.V_RESIZE;
            } else if (y >= h - EDGE) {
                cursor = cursor == Cursor.H_RESIZE ? Cursor.SW_RESIZE : Cursor.V_RESIZE;
            }
            if (scene.getCursor() != cursor) {
                scene.setCursor(cursor);
            }
        });
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            double x = event.getSceneX();
            double y = event.getSceneY();
            double w = scene.getWidth();
            double h = scene.getHeight();
            int hit = 0;
            if (x <= EDGE) {
                hit = 1;
            } else if (x >= w - EDGE) {
                hit = 2;
            } else if (y <= EDGE) {
                hit = 3;
            } else if (y >= h - EDGE) {
                hit = 4;
            }
            if (hit == 0) {
                return;
            }
            mode[0] = hit;
            state[0] = event.getScreenX();
            state[1] = event.getScreenY();
            state[2] = stage.getWidth();
            state[3] = stage.getHeight();
            state[4] = stage.getX();
            state[5] = stage.getY();
            event.consume();
        });
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (mode[0] == 0) {
                return;
            }
            double dx = event.getScreenX() - state[0];
            double dy = event.getScreenY() - state[1];
            double minW = Math.max(640, stage.getMinWidth());
            double minH = Math.max(480, stage.getMinHeight());
            switch (mode[0]) {
                case 1 -> {
                    double newW = Math.max(minW, state[2] - dx);
                    stage.setX(state[4] + (state[2] - newW));
                    stage.setWidth(newW);
                }
                case 2 -> stage.setWidth(Math.max(minW, state[2] + dx));
                case 3 -> {
                    double newH = Math.max(minH, state[3] - dy);
                    stage.setY(state[5] + (state[3] - newH));
                    stage.setHeight(newH);
                }
                case 4 -> stage.setHeight(Math.max(minH, state[3] + dy));
                default -> {
                }
            }
            event.consume();
        });
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> mode[0] = 0);
    }
}