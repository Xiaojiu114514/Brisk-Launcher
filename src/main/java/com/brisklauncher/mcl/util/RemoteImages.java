package com.brisklauncher.mcl.util;

import com.brisklauncher.mcl.net.Http;
import com.brisklauncher.mcl.security.SafeLog;
import javafx.application.Platform;
import javafx.scene.image.Image;
import org.glavo.webp.WebPImage;
import org.glavo.webp.WebPImageLoadOptions;
import org.glavo.webp.javafx.WebPFXImage;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 远端图示载入。
 *
 * <p>以前直接用 {@code new Image(url)}，等于让 JavaFX 自己开一条「没有 User-Agent、
 * 没有镜像」的连线去 {@code cdn.modrinth.com}；国内网路下多半卡住或直接失败，
 * 于是列表全是空白格（HMCL/PCL 却能显示，因为它们统一走自己的 HTTP 层 + 镜像）。
 *
 * <p>这里改成：走 {@link Http}（带 UA、可走代理）取位元组 → 再解码成 Image，
 * 并对 {@code cdn.modrinth.com} 套用与 HMCL 相同的镜像规则（{@code mod.mcimirror.top}）。
 * 失败的地址会被记住，同一张图不会反复重试拖慢列表。
 */
public final class RemoteImages {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String CDN_HOST = "cdn.modrinth.com";
    private static final String CDN_MIRROR = "https://mod.mcimirror.top";

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "mcl-icon");
        thread.setDaemon(true);
        return thread;
    });

    private static final Set<String> FAILED = Collections.newSetFromMap(
            new LinkedHashMap<String, Boolean>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Boolean> eldest) {
                    return size() > 128;
                }
            });

    private RemoteImages() {
    }

    /** 清掉「載入失敗」記錄：換網路/換鏡像後可以重新嘗試同一批圖。 */
    public static void resetFailed() {
        FAILED.clear();
    }

    /**
     * 非同步载入图示；成功时在 JavaFX 线程回呼。
     *
     * @param url      远端地址
     * @param width    目标宽度
     * @param height   目标高度
     * @param onLoaded 成功回呼（JavaFX 线程）
     */
    public static void load(String url, int width, int height, Consumer<Image> onLoaded) {
        load(url, width, height, onLoaded, null);
    }

    /**
     * 非同步载入图示（可选失败回呼）。
     *
     * <p>{@code onFailed} 很重要：呼叫端若用「同一个 URL 只下载一次」的去重表，
     * 没有失败回呼就会永远留著等待中的监听（而且之后同一张图不会再重试）。
     */
    public static void load(String url, int width, int height, Consumer<Image> onLoaded,
                            Consumer<Exception> onFailed) {
        if (url == null || url.isBlank() || FAILED.contains(url)) {
            if (onFailed != null) {
                onFailed.accept(new IllegalStateException("远端图示不可用：" + url));
            }
            return;
        }
        List<URI> candidates = candidates(url);
        POOL.execute(() -> {
            try {
                byte[] bytes = Http.getBytesAny(candidates, TIMEOUT);
                Image image = decode(bytes, width, height);
                if (image.isError() || image.getWidth() <= 0) {
                    throw new IllegalStateException("图片解码失败");
                }
                Platform.runLater(() -> onLoaded.accept(image));
            } catch (Exception error) {
                FAILED.add(url);
                SafeLog.warn("图示载入失败：" + url + "（" + error.getClass().getSimpleName() + "）");
                if (onFailed != null) {
                    Platform.runLater(() -> onFailed.accept(error));
                }
            }
        });
    }

    /**
     * 解码图片位元组。
     *
     * <p>Modrinth 的图示现在都是 WebP（结尾 {@code _96.webp}），JavaFX 内建解码器不支援，
     * 直接 {@code new Image(...)} 会得到 {@code isError()}；HMCL 也是靠引入 jwebp 解决。
     */
    private static Image decode(byte[] bytes, int width, int height) {
        if (isWebP(bytes)) {
            try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
                return new WebPFXImage(WebPImage.read(input,
                        new WebPImageLoadOptions(width, height, true, true)));
            } catch (Exception error) {
                throw new IllegalStateException("WebP 解码失败", error);
            }
        }
        // 安全审计 v3 A-12：远端字节统一走尺寸守卫
        return com.brisklauncher.mcl.security.GuardedImages
                .fromBytes(bytes, width, height, true, true);
    }

    private static boolean isWebP(byte[] bytes) {
        return bytes.length > 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    }

    /**
     * 官方地址 + 镜像地址；**官方优先，镜像只做后备**。
     *
     * <p>第 142 轮把镜像排前面（「国内成功率更高」），结果每一个模组图示都先撞
     * {@code mod.mcimirror.top} 的 **HTTP 567** 限流，日志被刷满还拖慢列表。
     * 实测官方 CDN 直连可用，所以改成官方先试。
     */
    static List<URI> candidates(String url) {
        List<URI> list = new ArrayList<>(2);
        try {
            URI original = URI.create(url);
            String host = original.getHost() == null ? "" : original.getHost().toLowerCase(java.util.Locale.ROOT);
            list.add(original);
            if (host.equals(CDN_HOST) || host.endsWith("." + CDN_HOST)) {
                String path = original.getRawPath() == null ? "" : original.getRawPath();
                String query = original.getRawQuery();
                list.add(URI.create(CDN_MIRROR + path + (query == null ? "" : "?" + query)));
            }
        } catch (Exception ignored) {
            // 位址非法时直接放弃，不再重试
        }
        return list;
    }
}
