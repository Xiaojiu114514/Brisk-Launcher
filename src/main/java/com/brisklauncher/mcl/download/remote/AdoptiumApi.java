package com.brisklauncher.mcl.download.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.brisklauncher.mcl.net.Http;
import com.brisklauncher.mcl.net.HttpException;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;

/**
 * Adoptium（Eclipse Temurin）JRE 元数据。
 * 缺失 Java 时自动下载到 {@code <launcher>/jdk/}，与 PCL2/HMCL 的行为一致。
 */
public final class AdoptiumApi {

    public record JreAsset(int major, String releaseName, String version,
                           URI link, String checksum, long size) {
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    public JreAsset latest(int major) {
        String os = osName();
        String arch = archName();
        String url = "https://api.adoptium.net/v3/assets/latest/" + major
                + "/hotspot?architecture=" + arch
                + "&image_type=jre&os=" + os
                + "&vendor=eclipse";
        try {
            JsonNode root = Http.getJson(URI.create(url), TIMEOUT);
            if (!root.isArray() || root.isEmpty()) {
                throw new HttpException("Adoptium 没有 Java " + major + " 的下载包");
            }
            JsonNode entry = root.get(0);
            JsonNode pkg = entry.path("binary").path("package");
            String link = pkg.path("link").asText("");
            if (link.isEmpty()) {
                throw new HttpException("Adoptium 返回的下载地址为空");
            }
            return new JreAsset(
                    major,
                    entry.path("release_name").asText("jdk-" + major),
                    entry.path("version").path("semver").asText(""),
                    URI.create(link),
                    pkg.path("checksum").asText(""),
                    pkg.path("size").asLong(0));
        } catch (HttpException e) {
            throw e;
        } catch (Exception e) {
            throw new HttpException("获取 Java " + major + " 下载信息失败：" + Http.describe(e), e);
        }
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
