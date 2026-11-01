package com.brisklauncher.mcl.util;

import javafx.scene.image.Image;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 有上限的图片快取（LRU + 位元组预算）。
 *
 * <p>下载页的图示、皮肤库缩略图都是「同一张图反复出现在不同 cell」，
 * 没有快取就会重复解码；没有上限又会把整页图示留在记忆体里。
 * 这里用条目数 + 估算位元组双重上限，页面关闭时呼叫 {@link #clear()} 立即释放。
 *
 * <p>只在 JavaFX 线程使用。
 */
public final class ImageCache {

    private final int maxEntries;
    private final long maxBytes;
    private final Map<String, Image> images;
    private long bytes;

    public ImageCache(int maxEntries, long maxBytes) {
        this.maxEntries = Math.max(1, maxEntries);
        this.maxBytes = Math.max(0, maxBytes);
        this.images = new LinkedHashMap<>(16, 0.75f, true);
    }

    /** 遠端圖示的預設上限：40 張 / 8MB。 */
    public static ImageCache forIcons() {
        return new ImageCache(40, 8L * 1024 * 1024);
    }

    public Image get(String key) {
        return key == null ? null : images.get(key);
    }

    public Image put(String key, Image image) {
        if (key == null || image == null) {
            return image;
        }
        Image previous = images.put(key, image);
        if (previous != null) {
            bytes -= bytesOf(previous);
        }
        bytes += bytesOf(image);
        trim();
        return image;
    }

    public Image computeIfAbsent(String key, Supplier<Image> loader) {
        Image cached = get(key);
        if (cached != null) {
            return cached;
        }
        Image created = loader.get();
        return created == null ? null : put(key, created);
    }

    public int size() {
        return images.size();
    }

    /** 頁面卸載時呼叫：清空條目並釋放引用，讓 JavaFX 圖像能被回收。 */
    public void clear() {
        images.clear();
        bytes = 0;
    }

    private void trim() {
        var iterator = images.entrySet().iterator();
        while (iterator.hasNext() && (images.size() > maxEntries || bytes > maxBytes)) {
            Map.Entry<String, Image> entry = iterator.next();
            bytes -= bytesOf(entry.getValue());
            iterator.remove();
        }
    }

    /** 估算像素佔用（ARGB 4 位元組/像素）；未知尺寸按 16KB 計。 */
    private static long bytesOf(Image image) {
        if (image == null) {
            return 0;
        }
        double width = image.getWidth();
        double height = image.getHeight();
        if (width <= 0 || height <= 0) {
            return 16L * 1024;
        }
        return (long) (width * height * 4);
    }
}
