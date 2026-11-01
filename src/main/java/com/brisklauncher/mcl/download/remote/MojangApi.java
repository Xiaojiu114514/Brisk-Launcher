package com.brisklauncher.mcl.download.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.brisklauncher.mcl.AppPaths;
import com.brisklauncher.mcl.download.MirrorResolver;
import com.brisklauncher.mcl.net.Http;
import com.brisklauncher.mcl.net.HttpException;
import com.brisklauncher.mcl.security.SafeLog;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Mojang 官方元数据：版本清单、版本 JSON、资源索引。
 *
 * <p>缓存策略参考 HMCL：清单与版本 JSON 都落盘到 {@code <launcher>/cache/}，
 * 网络失败时回退到旧缓存，保证离线也能看到已经下载过的版本。
 */
public final class MojangApi {

    private static final URI MANIFEST =
            URI.create("https://launchermeta.mojang.com/mc/game/version_manifest_v2.json");

    public record VersionMeta(String id, String type, String url, String releaseTime) {
        public boolean isRelease() {
            return "release".equals(type);
        }

        public boolean isSnapshot() {
            return "snapshot".equals(type);
        }

        public String date() {
            return releaseTime == null || releaseTime.length() < 10 ? "" : releaseTime.substring(0, 10);
        }
    }

    private final MirrorResolver mirrors;
    private final Duration timeout;
    private final Path cacheDir;

    public MojangApi(MirrorResolver mirrors, int timeoutSeconds) {
        this.mirrors = mirrors;
        this.timeout = Duration.ofSeconds(Math.max(5, timeoutSeconds));
        this.cacheDir = AppPaths.cacheDir();
    }

    // ---------- 版本清单 ----------

    public List<VersionMeta> versions() {
        return versions(false);
    }

    public List<VersionMeta> versions(boolean refresh) {
        Path cache = cacheDir.resolve("version-manifest.json");
        if (!refresh && Files.isRegularFile(cache)) {
            try {
                return parseManifest(Http.mapper().readTree(cache.toFile()));
            } catch (Exception e) {
                SafeLog.warn("版本清单缓存损坏，重新下载");
            }
        }
        try {
            byte[] body = Http.getBytesAny(mirrors.metadataCandidates(MANIFEST), timeout);
            List<VersionMeta> parsed = parseManifest(Http.mapper().readTree(body));
            // 缓存写失败（只读目录、沙箱）不能影响本次使用
            Path temp = null;
            try {
                Files.createDirectories(cacheDir);
                temp = Files.createTempFile(cacheDir, "manifest-", ".tmp");
                Files.write(temp, body);
                Files.move(temp, cache, StandardCopyOption.REPLACE_EXISTING);
                temp = null;
            } catch (Exception cacheError) {
                SafeLog.warn("版本清单缓存写入失败，不影响本次使用");
            } finally {
                // move 失败时临时文件会留下来（实测攒了几十个），这里兜底删掉
                if (temp != null) {
                    try {
                        Files.deleteIfExists(temp);
                    } catch (Exception ignored) {
                        // 尽力而为
                    }
                }
            }
            return parsed;
        } catch (Exception e) {
            SafeLog.warn("版本清单获取失败：" + Http.describe(e));
            if (Files.isRegularFile(cache)) {
                try {
                    return parseManifest(Http.mapper().readTree(cache.toFile()));
                } catch (Exception ignored) {
                    // 缓存也坏了
                }
            }
            throw new HttpException("无法获取 Minecraft 版本清单：" + Http.describe(e), e);
        }
    }

    private static List<VersionMeta> parseManifest(JsonNode root) {
        List<VersionMeta> result = new ArrayList<>();
        for (JsonNode version : root.path("versions")) {
            String id = version.path("id").asText("");
            String url = version.path("url").asText("");
            if (id.isEmpty() || url.isEmpty()) {
                continue;
            }
            result.add(new VersionMeta(id, version.path("type").asText("unknown"),
                    url, version.path("releaseTime").asText("")));
        }
        return result;
    }

    // ---------- 版本 JSON ----------

    /** 优先读缓存；缓存不存在或损坏时按镜像顺序下载。 */
    public JsonNode versionJson(VersionMeta meta) {
        Path cache = cacheDir.resolve("version-json").resolve(meta.id() + ".json");
        JsonNode cached = readJson(cache);
        if (cached != null) {
            return cached;
        }
        try {
            byte[] body = Http.getBytesAny(mirrors.metadataCandidates(URI.create(meta.url())), timeout);
            JsonNode node = Http.mapper().readTree(body);
            writeBytes(cache, body);
            return node;
        } catch (Exception e) {
            throw new HttpException("获取 " + meta.id() + " 的版本信息失败：" + Http.describe(e), e);
        }
    }

    /** 强制重新下载版本 JSON（修复损坏的本地版本时使用）。 */
    public JsonNode refreshVersionJson(VersionMeta meta) {
        try {
            Files.deleteIfExists(cacheDir.resolve("version-json").resolve(meta.id() + ".json"));
        } catch (Exception ignored) {
            // 删不掉也无所谓，后面会覆盖
        }
        return versionJson(meta);
    }

    public JsonNode versionJson(String versionId, String url) {
        return versionJson(new VersionMeta(versionId, "unknown", url, ""));
    }

    /** 下载资源索引 JSON 并返回内容（写盘由安装器负责）。 */
    public byte[] assetIndexBytes(JsonNode versionJson) {
        JsonNode index = versionJson.path("assetIndex");
        String url = index.path("url").asText("");
        if (url.isEmpty()) {
            return null;
        }
        try {
            return Http.getBytesAny(mirrors.metadataCandidates(URI.create(url)), timeout);
        } catch (Exception e) {
            throw new HttpException("获取资源索引失败：" + Http.describe(e), e);
        }
    }

    // ---------- 工具 ----------

    public Path cacheDir() {
        return cacheDir;
    }

    private JsonNode readJson(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Http.mapper().readTree(file.toFile());
        } catch (Exception e) {
            SafeLog.warn("缓存文件损坏：" + file.getFileName());
            return null;
        }
    }

    private static void writeBytes(Path file, byte[] body) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), "mcl-", ".tmp");
            Files.write(temp, body);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            SafeLog.warn("写入缓存失败：" + file.getFileName());
        }
    }
}
