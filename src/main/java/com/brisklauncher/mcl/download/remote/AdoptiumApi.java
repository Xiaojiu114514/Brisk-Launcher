package com.brisklauncher.mcl.download.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.brisklauncher.mcl.net.Http;
import com.brisklauncher.mcl.net.HttpException;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;

/**
 * Adoptium（Eclipse Temurin）JDK 元数据。
 *
 * <p>这里**只下 JDK、不下 JRE**：JDK 同时满足「启动器本体 / 游戏 / 安装器」的需求，
 * 而且 Adoptium 的 JDK 包与清华镜像的目录结构一一对应，可以拿包名直接拼镜像地址。
 *
 * <p>下载顺序由 {@code JavaManager} 决定：Oracle JDK 优先，本类只当备援
 * （清華 TUNA 镜像 → 官方 GitHub）。
 */
public final class AdoptiumApi {

    public record JdkAsset(int major, String releaseName, String version, URI link,
                           String checksum, long size, String packageName) {
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    public JdkAsset latest(int major) {
        String os = osName();
        String arch = archName();
        String url = "https://api.adoptium.net/v3/assets/latest/" + major
                + "/hotspot?architecture=" + arch
                + "&image_type=jdk&os=" + os
                + "&vendor=eclipse";
        try {
            JsonNode root = Http.getJson(URI.create(url), TIMEOUT);
            if (!root.isArray() || root.isEmpty()) {
                throw new HttpException("Adoptium 没有 Java " + major + " 的 JDK 下载包");
            }
            JsonNode entry = root.get(0);
            JsonNode pkg = entry.path("binary").path("package");
            String link = pkg.path("link").asText("");
            if (link.isEmpty()) {
                throw new HttpException("Adoptium 返回的下载地址为空");
            }
            URI uri = URI.create(link);
            String name = pkg.path("name").asText("");
            if (name.isBlank()) {
                name = fileNameOf(uri);
            }
            return new JdkAsset(
                    major,
                    entry.path("release_name").asText("jdk-" + major),
                    entry.path("version").path("semver").asText(""),
                    uri,
                    pkg.path("checksum").asText(""),
                    pkg.path("size").asLong(0),
                    name);
        } catch (HttpException e) {
            throw e;
        } catch (Exception e) {
            throw new HttpException("获取 Java " + major + " 下载信息失败：" + Http.describe(e), e);
        }
    }

    /**
     * 清华 TUNA 的 Adoptium 镜像地址。
     *
     * <p>目录结构：{@code /Adoptium/<主版本>/jdk/<arch>/<os>/<包名>}，包名与 Adoptium API 的
     * {@code package.name} 完全一致。镜像同步会比官方晚几天，所以调用方必须把它排在官方**前面**
     * 但允许 404 后回退。
     */
    public URI tunaMirror(JdkAsset asset) {
        return URI.create("https://mirrors.tuna.tsinghua.edu.cn/Adoptium/"
                + asset.major() + "/jdk/" + archName() + "/" + osName() + "/" + asset.packageName());
    }

    private static String fileNameOf(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String osName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac")) {
            return "mac";
        }
        return "linux";
    }

    private static String archName() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return arch.contains("aarch64") || arch.contains("arm64") ? "aarch64" : "x64";
    }
}
