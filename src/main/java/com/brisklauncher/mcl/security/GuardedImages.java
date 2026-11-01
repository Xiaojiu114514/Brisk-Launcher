package com.brisklauncher.mcl.security;

import javafx.scene.image.Image;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;

/**
 * 图片解码的**唯一入口**（安全审计 v3 A-12）。
 *
 * <p>为什么要收敛：{@link ImageGuard} 存在的第一轮只接了三个呼叫点，
 * 剩下 6 处仍然各自 {@code new Image(...)}——其中 favicon 那一处的内容
 * 完全由「你 ping 的伺服器」决定，等于防护形同虚设。
 *
 * <p><b>新增任何 {@code new Image(...)} 都必须走这里</b>，否则这套防护会继续被漏掉。
 */
public final class GuardedImages {

    private GuardedImages() {
    }

    /**
     * 从位元组解码（网路 / jar 内资源用）。
     *
     * @return 解码后的图片；尺寸异常或解码失败回 {@code null}（呼叫方显示占位图）
     */
    public static Image fromBytes(byte[] bytes, int width, int height) {
        return fromBytes(bytes, width, height, true, true);
    }

    public static Image fromBytes(byte[] bytes, int width, int height,
                                  boolean smooth, boolean background) {
        if (bytes == null || bytes.length == 0 || !ImageGuard.acceptable(bytes)) {
            return null;
        }
        Image image = new Image(new ByteArrayInputStream(bytes), width, height, smooth, background);
        return image.isError() ? null : image;
    }

    /** 從本機檔案解碼（圖示 / 皮膚 / 截圖用）。 */
    public static Image fromFile(Path file, int width, int height,
                                 boolean smooth, boolean background) {
        if (file == null || !ImageGuard.acceptable(file)) {
            return null;
        }
        Image image = new Image(file.toUri().toString(), width, height, true, smooth, background);
        return image.isError() ? null : image;
    }

    /** 保留原始尺寸的檔案版（皮膚預覽那種「不要縮放」的場景）。 */
    public static Image fromFileOriginal(Path file) {
        if (file == null || !ImageGuard.acceptable(file)) {
            return null;
        }
        Image image = new Image(file.toUri().toString(), false);
        return image.isError() ? null : image;
    }

    /**
     * 从 InputStream 解码（jar 内建资源、解压后的位元组流）。
     *
     * <p>先把位元组读出来（有上限）再交给 {@link Image}，避免 JavaFX
     * 对串流边读边按声明尺寸分配缓冲。
     */
    public static Image fromStream(java.io.InputStream stream, int width, int height) {
        if (stream == null) {
            return null;
        }
        try {
            byte[] bytes = InputBudget.readBoundedStream(stream, InputBudget.MAX_UNKNOWN_IMAGE_BYTES);
            // 安全审计 v3 F-2：走了入口也必须做尺寸检查（以前只有 bytes 版有）
            if (bytes == null || !ImageGuard.acceptable(bytes)) {
                return null;
            }
            Image image = new Image(new ByteArrayInputStream(bytes), width, height, true, true);
            return image.isError() ? null : image;
        } catch (java.io.IOException error) {
            return null;
        }
    }

    /** 保留原始尺寸的串流版。 */
    public static Image fromStreamOriginal(java.io.InputStream stream) {
        if (stream == null) {
            return null;
        }
        try {
            byte[] bytes = InputBudget.readBoundedStream(stream, InputBudget.MAX_UNKNOWN_IMAGE_BYTES);
            if (bytes == null || !ImageGuard.acceptable(bytes)) {
                return null;
            }
            Image image = new Image(new ByteArrayInputStream(bytes));
            return image.isError() ? null : image;
        } catch (java.io.IOException error) {
            return null;
        }
    }
}
