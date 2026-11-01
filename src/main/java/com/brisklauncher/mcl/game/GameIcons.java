package com.brisklauncher.mcl.game;

import com.brisklauncher.mcl.security.SafeLog;
import javafx.scene.image.Image;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 版本图标与大版本文件夹图标的解析/存放。
 *
 * <p>存放位置刻意选「实例自己的目录」里（{@code versions/<id>/icon.png}）：
 * 换启动器、备份实例、导出整合包都跟著走，不需要在启动器资料目录里再维护一份映射表。
 *
 * <p>同时兼容其它启动器已经放好的图标（PCL 的 {@code PCL/logo.png}、HMCL 的 {@code icon.png}），
 * 打开别人的目录也能直接看到图标。
 */
public final class GameIcons {

    /** 實例圖標候選檔名（依優先度）。 */
    private static final List<String> INSTANCE_CANDIDATES = List.of(
            "icon.png", "icon.jpg", "icon.jpeg", "icon.webp",
            "PCL/logo.png", "logo.png", "pack.png");

    /** 大版本文件夾圖標候選檔名。 */
    private static final List<String> ROOT_CANDIDATES = List.of(
            "icon.png", "icon.jpg", "icon.jpeg", "icon.webp", "mcl-icon.png");

    /** 換圖標時要清掉的舊檔（避免同時存在 icon.png 與 icon.jpg，載入結果看心情）。 */
    private static final List<String> REMOVABLE = List.of(
            "icon.png", "icon.jpg", "icon.jpeg", "icon.webp", "mcl-icon.png");

    private static final int MAX_CACHE = 96;
    private static final Map<String, Image> CACHE = new ConcurrentHashMap<>();

    private GameIcons() {
    }

    // ---------- 查询 ----------

    /** 實例的自訂圖標；沒有回傳 empty。 */
    public static Optional<Path> iconOf(GameInstance entry) {
        if (entry == null) {
            return Optional.empty();
        }
        return firstExisting(entry.versionDir(), INSTANCE_CANDIDATES);
    }

    /** 大版本文件夾的自訂圖標。 */
    public static Optional<Path> iconOf(GameDirectory root) {
        if (root == null) {
            return Optional.empty();
        }
        return firstExisting(root.path(), ROOT_CANDIDATES);
    }

    private static Optional<Path> firstExisting(Path base, List<String> names) {
        for (String name : names) {
            Path candidate = base.resolve(name);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * 读取图标（带快取；同一个档案只解码一次）。
     *
     * @return 读不到或格式不支援时回传 null，呼叫方自己画占位图
     */
    public static Image image(Path file, double size) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        String key = file.toAbsolutePath() + "|" + lastModified(file) + "|" + (int) size;
        Image cached = CACHE.get(key);
        if (cached != null) {
            return cached.isError() ? null : cached;
        }
        try {
            // 安全审计 v2 A-06 / v3 A-12：解码统一走 GuardedImages（内部先过 ImageGuard）
            int side = (int) Math.round(size);
            Image image = com.brisklauncher.mcl.security.GuardedImages.fromFile(
                    file, side, side, true, false);
            if (image == null || image.isError()) {
                return null;
            }
            if (CACHE.size() > MAX_CACHE) {
                CACHE.clear();
            }
            CACHE.put(key, image);
            return image;
        } catch (Exception error) {
            SafeLog.warn("读取版本图标失败：" + file.getFileName());
            return null;
        }
    }

    private static long lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (Exception error) {
            return 0L;
        }
    }

    // ---------- 设定 / 清除 ----------

    /** 把一張圖片設成實例圖標（複製進實例目錄）。 */
    public static Path setIcon(GameInstance entry, Path source) throws Exception {
        if (entry == null || source == null || !Files.isRegularFile(source)) {
            throw new IllegalArgumentException("请选择一个图片档");
        }
        String extension = extensionOf(source);
        if (!List.of("png", "jpg", "jpeg", "webp").contains(extension)) {
            throw new IllegalArgumentException("只支援 PNG / JPG / WEBP 图片");
        }
        Path dir = entry.versionDir();
        Files.createDirectories(dir);
        removeExisting(dir);
        Path target = dir.resolve("icon." + extension);
        Files.copy(source, target);
        invalidate();
        return target;
    }

    /** 把一張圖片設成大版本文件夾圖標。 */
    public static Path setIcon(GameDirectory root, Path source) throws Exception {
        if (root == null || source == null || !Files.isRegularFile(source)) {
            throw new IllegalArgumentException("请选择一个图片档");
        }
        String extension = extensionOf(source);
        if (!List.of("png", "jpg", "jpeg", "webp").contains(extension)) {
            throw new IllegalArgumentException("只支援 PNG / JPG / WEBP 图片");
        }
        Files.createDirectories(root.path());
        removeExisting(root.path());
        Path target = root.path().resolve("icon." + extension);
        Files.copy(source, target);
        invalidate();
        return target;
    }

    public static void clearIcon(GameInstance entry) throws Exception {
        if (entry != null) {
            removeExisting(entry.versionDir());
            invalidate();
        }
    }

    public static void clearIcon(GameDirectory root) throws Exception {
        if (root != null) {
            removeExisting(root.path());
            invalidate();
        }
    }

    private static void removeExisting(Path dir) throws Exception {
        for (String name : REMOVABLE) {
            Path path = dir.resolve(name);
            if (Files.isRegularFile(path)) {
                Files.delete(path);
            }
        }
    }

    private static void invalidate() {
        CACHE.clear();
    }

    private static String extensionOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
