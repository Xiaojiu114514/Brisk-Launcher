package com.brisklauncher.mcl.download.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.brisklauncher.mcl.AppPaths;
import com.brisklauncher.mcl.net.Http;
import com.brisklauncher.mcl.net.HttpException;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * authlib-injector 元数据（第三方皮肤/认证支持）。
 * 只从官方源拉取并校验 SHA-256，符合开发方案的安全清单。
 */
public final class AuthlibInjectorApi {

    public record Artifact(String version, URI url, String checksum) {
    }

    private static final URI LATEST = URI.create("https://authlib-injector.yushi.moe/artifact/latest.json");

    public Artifact latest() {
        try {
            JsonNode node = Http.getJson(LATEST, Duration.ofSeconds(20));
            String url = node.path("download_url").asText("");
            if (url.isEmpty()) {
                throw new HttpException("authlib-injector 下载地址为空");
            }
            return new Artifact(
                    node.path("version").asText("latest"),
                    URI.create(url),
                    node.path("checksum").asText(""));
        } catch (HttpException e) {
            throw e;
        } catch (Exception e) {
            throw new HttpException("获取 authlib-injector 信息失败：" + Http.describe(e), e);
        }
    }

    public Path cachedJar(String version) {
        return AppPaths.cacheDir().resolve("authlib-injector-" + version + ".jar");
    }

    public boolean isCached(Path jar) {
        return Files.isRegularFile(jar);
    }
}
