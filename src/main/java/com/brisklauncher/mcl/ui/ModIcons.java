package com.brisklauncher.mcl.ui;

import com.brisklauncher.mcl.game.mod.ModJarIcon;
import com.brisklauncher.mcl.util.ImageCache;
import javafx.application.Platform;
import javafx.scene.image.Image;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Mod / 资源包 / 光影包的图标快取（从 jar 内的图片读，不再联网）。
 *
 * <p>三件事：
 * <ol>
 *   <li><b>本地读取</b>：交给 {@link ModJarIcon}，完全离线；</li>
 *   <li><b>多执行绪</b>：固定 2 条背景执行绪解压＋解码（太多会同时占用解码缓冲）；</li>
 *   <li><b>去重 + 上限</b>：同一张图只解一次（in-flight 合并），LRU 上限 160 张 / 12MB；</li>
 *   <li><b>先缩图再解码</b>：用 ImageIO 把原图缩到 64px 再交给 JavaFX，
 *       避免「一个 1024×1024 的图标吃掉 4MB 解码缓冲 ×126」的记忆体尖峰。</li>
 * </ol>
 *
 * <p>执行绪规则：缓存只在 JavaFX 执行绪读写；背景执行绪只做 zip 读取与 Image 解码。
 */
public final class ModIcons {

    private static final ImageCache CACHE = new ImageCache(160, 12L * 1024 * 1024);
    /**
     * 远端图示（Modrinth 的 icon_url）快取：只在「档案里没有图标」时才用到，
     * 例如光影包大多不带 pack.png。key 是完整 URL。
     */
    private static final ImageCache REMOTE_CACHE = new ImageCache(160, 12L * 1024 * 1024);
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "brisk-mod-icon");
        thread.setDaemon(true);
        return thread;
    });

    /** 正在解碼的圖：避免同一張被重複解（列表滾動時很常見）。 */
    private static final Map<String, List<Consumer<Image>>> PENDING = new ConcurrentHashMap<>();
    /** 正在下載的遠端圖示（同一張只下載一次）。 */
    private static final Map<String, List<Consumer<Image>>> PENDING_REMOTE = new ConcurrentHashMap<>();

    private ModIcons() {
    }

    /** 快取命中時直接回傳；只在 FX 執行緒呼叫。 */
    public static Image cached(Path file) {
        return file == null ? null : CACHE.get(key(file));
    }

    /**
     * 非同步取图标；拿到后在 JavaFX 执行绪回呼（没有图标时回呼 {@code null}）。
     */
    public static void load(Path file, int size, Consumer<Image> onLoaded) {
        if (file == null) {
            if (onLoaded != null) {
                onLoaded.accept(null);
            }
            return;
        }
        String key = key(file);
        Image hit = CACHE.get(key);
        if (hit != null) {
            onLoaded.accept(hit);
            return;
        }
        PENDING.compute(key, (ignored, listeners) -> {
            List<Consumer<Image>> list = listeners == null ? new ArrayList<>() : listeners;
            list.add(onLoaded);
            if (listeners == null) {
                POOL.execute(() -> decodeAndPublish(file, key, size));
            }
            return list;
        });
    }

    /**
     * 远端图示的后备载入（只在档案里没有图标时才用，例如光影包）。
     *
     * <p>走 {@link com.brisklauncher.mcl.util.RemoteImages}（带 UA、WebP 解码、CDN 镜像），
     * 同一个 URL 只下载一次；结果在 JavaFX 执行绪回呼。
     */
    public static void loadRemote(String url, int size, Consumer<Image> onLoaded) {
        if (url == null || url.isBlank() || onLoaded == null) {
            return;
        }
        Image hit = REMOTE_CACHE.get(url);
        if (hit != null) {
            onLoaded.accept(hit);
            return;
        }
        PENDING_REMOTE.compute(url, (ignored, listeners) -> {
            List<Consumer<Image>> list = listeners == null ? new ArrayList<>() : listeners;
            list.add(onLoaded);
            if (listeners == null) {
                com.brisklauncher.mcl.util.RemoteImages.load(url, size, size, image -> {
                    if (image != null) {
                        REMOTE_CACHE.put(url, image);
                    }
                    publishRemote(url, image);
                }, error -> publishRemote(url, null));
            }
            return list;
        });
    }

    /** 把遠端圖示結果（或失敗的 null）發給所有等待中的監聽，並清掉去重表。 */
    private static void publishRemote(String url, Image image) {
        List<Consumer<Image>> waiting = PENDING_REMOTE.remove(url);
        if (waiting == null) {
            return;
        }
        for (Consumer<Image> listener : waiting) {
            try {
                listener.accept(image);
            } catch (Exception ignored) {
                // 单一 listener 失败不影响其它
            }
        }
    }

    /** 遠端圖示快取命中查詢（含失敗的情況回 null）。 */
    public static Image cachedRemote(String url) {
        return url == null || url.isBlank() ? null : REMOTE_CACHE.get(url);
    }

    /** 預熱一批圖標（列表打開時一次丟進來，內部就是多執行緒）。 */
    public static void preload(List<Path> files, int size) {
        if (files == null || files.isEmpty()) {
            return;
        }
        // load() 自己就会排进 4 条背景执行绪，这里只是「一次把整批排进去」，
        // 不要再用 Parallel 包一层（会多开执行绪却没有好处）。
        for (Path file : files) {
            load(file, size, image -> {
            });
        }
        if (files.size() >= 12) {
            com.brisklauncher.mcl.security.SafeLog.info("图标预热：" + files.size()
                    + " 个档案（2 条背景执行绪，不阻塞列表）");
        }
    }


    private static void decodeAndPublish(Path file, String key, int size) {
        Image image = null;
        try {
            byte[] bytes = ModJarIcon.read(file);
            if (bytes != null && bytes.length > 0
                    && com.brisklauncher.mcl.security.ImageGuard.acceptable(bytes)) {
                Image decoded = com.brisklauncher.mcl.security.GuardedImages
                        .fromBytes(bytes, size, size, true, true);
                if (!decoded.isError() && decoded.getWidth() > 0) {
                    image = decoded;
                }
            }
        } catch (Exception ignored) {
            // 用占位图
        }
        Image result = image;
        Platform.runLater(() -> {
            if (result != null) {
                CACHE.put(key, result);
            }
            List<Consumer<Image>> listeners = PENDING.remove(key);
            if (listeners != null) {
                for (Consumer<Image> listener : listeners) {
                    try {
                        listener.accept(result);
                    } catch (Exception ignored) {
                        // 单一 listener 失败不影响其它
                    }
                }
            }
        });
    }

    private static String key(Path file) {
        return file.toAbsolutePath().normalize().toString();
    }

    /**
     * 清空图标快取。
     *
     * <p>只清 {@code CACHE}，**不清 PENDING**：清 PENDING 会把「还在解码、等著回呼」的
     * 监听一起丢掉，于是重开列表时图标又变回预设图（实测踩过）。
     * 那些监听对应的 row 已经被移除，回呼也只是设定一个没人看的节点，没有副作用。
     */
    public static void clear() {
        CACHE.clear();
        REMOTE_CACHE.clear();
    }
}
