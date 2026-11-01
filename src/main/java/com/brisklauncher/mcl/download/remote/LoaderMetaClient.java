package com.brisklauncher.mcl.download.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.brisklauncher.mcl.net.Http;
import com.brisklauncher.mcl.net.HttpException;
import com.brisklauncher.mcl.security.SafeLog;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 加载器元数据：Fabric / Quilt 走官方 Meta API，Forge / NeoForge 走 Maven 元数据。
 *
 * <p>Fabric profile 返回的是一份 {@code inheritsFrom} 版本 JSON，
 * 直接落盘成 {@code versions/<id>/<id>.json} 就能被启动链路识别，这是 HMCL 的做法，
 * 不需要运行安装器。
 */
public final class LoaderMetaClient {
    /**
     * BMCLAPI 清单请求（**只用 HTTPS**）。
     *
     * <p>安全审计 H-03：这里原本在 https 握手失败时**回退到明文 http**，
     * 于是能阻断 TLS 的中间人可以用 http 返回伪造的版本清单（例如给一个「看起来正常」的
     * Forge 版本号），把玩家引导到未校验的下载。清单是供应链的入口，不该有明文回退。
     * 现在：https 失败就直接抛，由上层决定「改用官方源」或明确报错。
     */
    private String bmclapiString(String path) {
        return com.brisklauncher.mcl.net.Http.getString(
                java.net.URI.create(BMCLAPI + path), java.time.Duration.ofSeconds(30));
    }

    /** BMCLAPI 鏡像（國內可直連，覆蓋 Mojang / Forge / NeoForge maven）。 */
    private static final String BMCLAPI = "https://bmclapi2.bangbang93.com";
    /** 備援位址：bmclapi2 的 DNS 有時會解析到連不上的節點（實測 bmclapi2 偶發 NXDOMAIN）。 */
    private static final String BMCLAPI_ALT = "https://bmclapi.bangbang93.com";


    private static final String FABRIC_META = "https://meta.fabricmc.net/v2";
    private static final String QUILT_META = "https://meta.quiltmc.org/v3";
    private static final String FORGE_MAVEN = "https://maven.minecraftforge.net/net/minecraftforge/forge";
    private static final String FORGE_PROMOTIONS =
            "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";
    private static final String NEOFORGE_MAVEN =
            "https://maven.neoforged.net/releases/net/neoforged/neoforge";
    /** 版本清單這種小檔案不需要等 20 秒；等太久只會讓安裝視窗看起來卡住。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    public record LoaderVersion(String version, boolean stable) {
        @Override
        public String toString() {
            return version;
        }
    }

    // ---------- Fabric ----------

    public List<LoaderVersion> fabricLoaders(String mcVersion) {
        JsonNode root = getJson(FABRIC_META + "/versions/loader/" + mcVersion);
        List<LoaderVersion> result = new ArrayList<>();
        for (JsonNode node : root) {
            JsonNode loader = node.path("loader");
            String version = loader.path("version").asText("");
            if (!version.isEmpty()) {
                result.add(new LoaderVersion(version, loader.path("stable").asBoolean(true)));
            }
        }
        if (result.isEmpty()) {
            throw new HttpException("Fabric 没有适用于 " + mcVersion + " 的加载器版本");
        }
        return result;
    }

    public JsonNode fabricProfile(String mcVersion, String loaderVersion) {
        return getJson(FABRIC_META + "/versions/loader/" + mcVersion + "/" + loaderVersion + "/profile/json");
    }

    // ---------- Quilt ----------

    public List<LoaderVersion> quiltLoaders(String mcVersion) {
        JsonNode root = getJson(QUILT_META + "/versions/loader/" + mcVersion);
        List<LoaderVersion> result = new ArrayList<>();
        for (JsonNode node : root) {
            String version = node.path("version").asText("");
            if (!version.isEmpty()) {
                String lower = version.toLowerCase(Locale.ROOT);
                boolean stable = !lower.contains("beta") && !lower.contains("alpha")
                        && !lower.contains("rc") && !lower.contains("pre");
                result.add(new LoaderVersion(version, stable));
            }
        }
        if (result.isEmpty()) {
            throw new HttpException("Quilt 没有适用于 " + mcVersion + " 的加载器版本");
        }
        return result;
    }

    // ---------- Forge ----------

    /** 常见版本用 promotions_slim.json，缺失时回退 Maven 元数据。 */
    /** OptiFine 版本清單（BMCLAPI）：回傳 "HD_U_J3" 這種 type_patch 組合。 */
    public List<LoaderVersion> optifineVersions(String mcVersion) {
        JsonNode root = getJson("https://bmclapi2.bangbang93.com/optifine/" + mcVersion);
        List<LoaderVersion> result = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        if (root != null && root.isArray()) {
            for (JsonNode node : root) {
                String type = node.path("type").asText("");
                String patch = node.path("patch").asText("");
                if (type.isEmpty() || patch.isEmpty()) {
                    continue;
                }
                if (patch.toLowerCase(Locale.ROOT).startsWith("pre")) {
                    continue;
                }
                String id = type + "_" + patch;
                if (seen.add(id)) {
                    result.add(new LoaderVersion(id, true));
                }
            }
        }
        return result;
    }

    /** OptiFine 安裝 jar 的下載位址（BMCLAPI）。 */
    public String optifineInstallerUrl(String mcVersion, String optifineVersion) {
        int cut = optifineVersion.lastIndexOf('_');
        if (cut <= 0) {
            throw new IllegalArgumentException("OptiFine 版本格式应为 TYPE_PATCH，例如 HD_U_J3");
        }
        String type = optifineVersion.substring(0, cut);
        String patch = optifineVersion.substring(cut + 1);
        return "https://bmclapi2.bangbang93.com/optifine/" + mcVersion + "/" + type + "/" + patch;
    }

    public List<LoaderVersion> forgeVersions(String mcVersion) {
        List<LoaderVersion> result = new ArrayList<>();
        // 先试 BMCLAPI（国内可直连；官方 promotions 常被墙）
        // 一样做重试：这条也是「偶发 0 笔」的来源之一
        JsonNode builds = retryJson(List.of(
                BMCLAPI + "/forge/minecraft/" + mcVersion), 3);
        if (builds != null && builds.isArray()) {
            for (JsonNode build : builds) {
                String version = build.path("version").asText("");
                if (version.isEmpty()) {
                    continue;
                }
                String type = build.path("type").asText("");
                result.add(new LoaderVersion(version, "recommended".equalsIgnoreCase(type)));
            }
        }
        if (!result.isEmpty()) {
            return dedupe(result);
        }
        SafeLog.warn("[loader] BMCLAPI Forge 清单为空，改试官方 promotions / maven");
        try {
            JsonNode promotions = getJson(FORGE_PROMOTIONS).path("promos");
            promotions.fieldNames().forEachRemaining(key -> {
                if (key.startsWith(mcVersion + "-")) {
                    String version = promotions.path(key).asText("");
                    boolean stable = key.endsWith("-recommended");
                    if (!version.isEmpty()) {
                        result.add(new LoaderVersion(version, stable));
                    }
                }
            });
        } catch (Exception e) {
            SafeLog.warn("Forge 推荐版本获取失败，回退 Maven 元数据");
        }
        if (result.isEmpty()) {
            String prefix = mcVersion + "-";
            for (LoaderVersion version : mavenWithRetry("forge",
                    List.of(FORGE_MAVEN + "/maven-metadata.xml"), 2)) {
                if (version.version().startsWith(prefix)) {
                    result.add(new LoaderVersion(
                            version.version().substring(prefix.length()), version.stable()));
                }
            }
        }
        return dedupe(result);
    }

    /** 重試抓一份 JSON（失敗回 null，不拋例外）。 */
    private JsonNode retryJson(List<String> urls, int attempts) {
        for (int attempt = 1; attempt <= Math.max(1, attempts); attempt++) {
            for (String url : urls) {
                try {
                    JsonNode node = Http.getJson(URI.create(url), TIMEOUT);
                    if (node != null) {
                        return node;
                    }
                } catch (Exception error) {
                    SafeLog.warn("[loader] 清单请求失败（" + hostOf(url) + "，第 " + attempt + "/"
                            + attempts + " 次）：" + Http.describe(error));
                }
            }
            if (attempt < attempts) {
                try {
                    Thread.sleep(400L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    public URI forgeInstaller(String mcVersion, String forgeVersion) {
        String full = mcVersion + "-" + forgeVersion;
        // 先给 BMCLAPI 镜像（国内可用），MirrorResolver 仍会把官方位址排成备援
        return URI.create(BMCLAPI + "/maven/net/minecraftforge/forge/" + full
                + "/forge-" + full + "-installer.jar");
    }

    // ---------- NeoForge ----------

    public List<LoaderVersion> neoforgeVersions(String mcVersion) {
        // 官方 maven.neoforged.net 在国内**完全解析不了**（DNS 就被拦），所以国内这条链只有镜像可用。
        // 镜像（bmclapi2 / bmclapi）的 DNS 与 TLS 都会偶发失败，策略：
        //   ① 两个镜像域名都试（bmclapi2 会解析到不同节点，个别节点连不上）
        //   ② 每个来源最多试 2 次（含退避）
        //   ③ 成功结果写 10 分钟内存快取 + 本地磁盘快取（下次启动即使网络抽风也有清单）
        List<LoaderVersion> all = mavenWithRetry("neoforge", List.of(
                BMCLAPI + "/maven/net/neoforged/neoforge/maven-metadata.xml",
                BMCLAPI_ALT + "/maven/net/neoforged/neoforge/maven-metadata.xml",
                NEOFORGE_MAVEN + "/maven-metadata.xml"), 2);
        List<LoaderVersion> filtered = filterNeoForge(all, mcVersion);
        if (filtered.isEmpty() && !all.isEmpty()) {
            SafeLog.warn("[loader] NeoForge 清单共 " + all.size() + " 个版本，但没有符合 "
                    + mcVersion + " 的（前几个："
                    + all.subList(0, Math.min(5, all.size())) + "）");
        }
        return filtered;
    }

    public URI neoforgeInstaller(String neoforgeVersion) {
        return URI.create(BMCLAPI + "/maven/net/neoforged/neoforge/" + neoforgeVersion
                + "/neoforge-" + neoforgeVersion + "-installer.jar");
    }

    /**
     * NeoForge 的版本号在 1.20.2 之后改为 {@code <mcMinor>.<mcPatch>.<build>}：
     * 1.21 → 21.0.x，1.21.1 → 21.1.x，1.20.4 → 20.4.x。
     *
     * <p><b>不要放宽前缀</b>：NeoForge 的 maven 里没有 1.20.1（也没有 47.x）的版本，
     * 一旦「找不到就退成 20.」这种宽松比对，1.20.1 会拿到 20.2.x（给 1.20.2 用的）
     * → 装上去直接坏掉。宁可回空清单，由界面明确告诉玩家「这版要用 Forge」。
     */
    private static List<LoaderVersion> filterNeoForge(List<LoaderVersion> all, String mcVersion) {
        if ("1.20.1".equals(mcVersion)) {
            // 保险：若哪天官方真的放上 1.20.1 的分支（47.x），这里能自动认出来
            List<LoaderVersion> legacy = filterByPrefix(all, "47.");
            if (!legacy.isEmpty()) {
                return legacy;
            }
            return List.of();
        }
        String[] parts = mcVersion.split("\\.");
        if (parts.length < 2) {
            return all;
        }
        String prefix = parts[1] + "." + (parts.length > 2 ? parts[2] : "0") + ".";
        return filterByPrefix(all, prefix);
    }

    private static List<LoaderVersion> filterByPrefix(List<LoaderVersion> all, String prefix) {
        List<LoaderVersion> result = new ArrayList<>();
        for (LoaderVersion version : all) {
            if (version.version().startsWith(prefix)) {
                result.add(version);
            }
        }
        return dedupe(result);
    }

    // ---------- Maven 元数据 ----------

    /** 版本清單快取（10 分鐘）：同一個安裝視窗重複開、或切換加載器再切回來都不必重打。 */
    private static final long CATALOG_TTL_MS = 10 * 60 * 1000L;
    private static final java.util.Map<String, CachedCatalog> CATALOG_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private record CachedCatalog(List<LoaderVersion> versions, long at) {
    }

    /**
     * 多来源 + 重试地抓一份 maven-metadata.xml（或任何「一堆 &lt;version&gt; 的清单」）。
     *
     * <p>为什么要重试：BMCLAPI / 官方 maven 在国内会**偶发** TLS 握手失败或回一份错误页，
     * 一次失败就让安装视窗的版本下拉变空白（使用者看到「0 笔」）。这里每个来源最多试
     * {@code attempts} 次，中间退避；解析不到任何版本也视为失败、换下一个来源。
     *
     * @param cacheKey 快取键（不同清单不要共用）
     * @param urls     来源（镜像优先，官方最后）
     */
    private List<LoaderVersion> mavenWithRetry(String cacheKey, List<String> urls, int attempts) {
        long now = System.currentTimeMillis();
        CachedCatalog cached = CATALOG_CACHE.get(cacheKey);
        if (cached != null && now - cached.at() < CATALOG_TTL_MS) {
            return cached.versions();
        }
        for (int attempt = 1; attempt <= Math.max(1, attempts); attempt++) {
            // 同一轮的多个来源**并行**去抢：镜像的 DNS/TLS 在国内会抽风，
            // 串行试下来最坏要等好几个超时（实测首个 NeoForge 清单 6 秒）。
            // 并行的第一个成功结果就用，其余取消。
            List<LoaderVersion> hit = raceSources(urls, attempt, attempts);
            if (hit != null && !hit.isEmpty()) {
                CATALOG_CACHE.put(cacheKey, new CachedCatalog(hit, System.currentTimeMillis()));
                saveCatalog(cacheKey, hit);
                return hit;
            }
            if (attempt < attempts) {
                try {
                    Thread.sleep(400L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return List.of();
                }
            }
        }
        SafeLog.warn("[loader] 版本清单全部来源都失败（" + String.join(" / ", urls.stream()
                .map(LoaderMetaClient::hostOf).toList()) + "），改试本地快取");
        List<LoaderVersion> offline = loadCatalog(cacheKey);
        if (!offline.isEmpty()) {
            SafeLog.info("[loader] 使用本地快取的版本清单：" + offline.size() + " 个版本");
            CATALOG_CACHE.put(cacheKey, new CachedCatalog(offline, System.currentTimeMillis()));
            return offline;
        }
        return List.of();
    }

    /**
     * 并行请求一组来源，返回**第一个**解析成功的清单（失败/空清单都会被忽略）。
     *
     * @return 成功清单；全部失败返回 {@code null}
     */
    private List<LoaderVersion> raceSources(List<String> urls, int attempt, int attempts) {
        if (urls.isEmpty()) {
            return null;
        }
        java.util.concurrent.LinkedBlockingQueue<List<LoaderVersion>> results =
                new java.util.concurrent.LinkedBlockingQueue<>();
        Thread[] workers = new Thread[urls.size()];
        for (int index = 0; index < urls.size(); index++) {
            final String url = urls.get(index);
            workers[index] = Thread.ofVirtual().name("mcl-catalog-" + index).start(() -> {
                String host = hostOf(url);
                try {
                    String text = Http.getString(URI.create(url), TIMEOUT);
                    if (text == null || text.isBlank() || !text.contains("<version>")) {
                        SafeLog.warn("[loader] 清单内容不像 maven-metadata（" + host + "，第 "
                                + attempt + " 次）");
                        return;
                    }
                    List<LoaderVersion> parsed = parseMaven(text, "");
                    if (parsed.isEmpty()) {
                        SafeLog.warn("[loader] 清单解析为空（" + host + "）");
                        return;
                    }
                    SafeLog.info("[loader] 版本清单取得成功：" + host + "，共 " + parsed.size()
                            + " 个版本（第 " + attempt + " 次尝试）");
                    results.offer(parsed);
                } catch (Exception error) {
                    // 并行抢答时，慢的那几个会被主动 interrupt —— 这不是失败，别刷日志
                    boolean cancelled = error instanceof InterruptedException
                            || Thread.currentThread().isInterrupted()
                            || (error.getCause() instanceof InterruptedException);
                    if (!cancelled) {
                        SafeLog.warn("[loader] 清单请求失败（" + host + "，第 " + attempt + "/"
                                + attempts + " 次）：" + Http.describe(error));
                    }
                }
            });
        }
        try {
            // 最多等到一个完整超时周期：并行情况下这已经覆盖了「最慢的那个来源」
            return results.poll(TIMEOUT.toMillis() + 500L, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            for (Thread worker : workers) {
                if (worker != null) {
                    worker.interrupt();
                }
            }
        }
    }

    // ---------- 版本清单的本地快取 ----------
    // 镜像的 DNS 在国内会偶发解析失败（实测 bmclapi2 会指到不存在的节点），
    // 没有本地快取的话，一次网络抽风就等于「版本列表消失了」。

    private static java.nio.file.Path catalogFile(String cacheKey) {
        return com.brisklauncher.mcl.AppPaths.dataDir().resolve("cache")
                .resolve("loader-" + cacheKey + ".json");
    }

    private void saveCatalog(String cacheKey, List<LoaderVersion> versions) {
        try {
            var mapper = com.brisklauncher.mcl.net.Http.mapper();
            var array = mapper.createArrayNode();
            for (LoaderVersion version : versions) {
                array.addObject().put("v", version.version()).put("stable", version.stable());
            }
            java.nio.file.Path file = catalogFile(cacheKey);
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.write(file, mapper.writeValueAsBytes(array));
        } catch (Exception ignored) {
            // 快取失败不影响功能
        }
    }

    private List<LoaderVersion> loadCatalog(String cacheKey) {
        try {
            java.nio.file.Path file = catalogFile(cacheKey);
            if (!java.nio.file.Files.isRegularFile(file)) {
                return List.of();
            }
            var node = com.brisklauncher.mcl.net.Http.mapper().readTree(file.toFile());
            List<LoaderVersion> result = new ArrayList<>();
            for (var item : node) {
                String version = item.path("v").asText("");
                if (!version.isBlank()) {
                    result.add(new LoaderVersion(version, item.path("stable").asBoolean(true)));
                }
            }
            return result;
        } catch (Exception error) {
            return List.of();
        }
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? url : host;
        } catch (Exception ignored) {
            return url;
        }
    }

    /** 解析 maven-metadata.xml 的版本清單（已取得 XML 字串時用）。 */
    private List<LoaderVersion> parseMaven(String xml, String prefix) {
        List<LoaderVersion> result = new ArrayList<>();
        Matcher matcher = Pattern.compile("<version>([^<]+)</version>").matcher(xml);
        while (matcher.find()) {
            String version = matcher.group(1).trim();
            if (version.isEmpty() || !version.startsWith(prefix)) {
                continue;
            }
            String stripped = prefix.isEmpty() ? version : version.substring(prefix.length());
            boolean stable = !stripped.toLowerCase(Locale.ROOT).contains("beta");
            result.add(new LoaderVersion(stripped, stable));
        }
        return result;
    }

    private List<LoaderVersion> mavenVersions(String metadataUrl, String prefix) {
        List<LoaderVersion> result = new ArrayList<>();
        try {
            String xml = Http.getString(URI.create(metadataUrl), TIMEOUT);
            Matcher matcher = Pattern.compile("<version>([^<]+)</version>").matcher(xml);
            while (matcher.find()) {
                String version = matcher.group(1).trim();
                if (version.isEmpty() || !version.startsWith(prefix)) {
                    continue;
                }
                String stripped = prefix.isEmpty() ? version : version.substring(prefix.length());
                boolean stable = !stripped.toLowerCase(Locale.ROOT).contains("beta");
                result.add(new LoaderVersion(stripped, stable));
            }
        } catch (Exception e) {
            SafeLog.warn("Maven 版本列表获取失败：" + Http.describe(e));
        }
        return dedupe(result);
    }

    private static List<LoaderVersion> dedupe(List<LoaderVersion> versions) {
        List<LoaderVersion> result = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (LoaderVersion version : versions) {
            if (!seen.contains(version.version())) {
                seen.add(version.version());
                result.add(version);
            }
        }
        return result;
    }

    private static JsonNode getJson(String url) {
        try {
            return Http.getJson(URI.create(url), TIMEOUT);
        } catch (Exception e) {
            throw new HttpException("获取加载器信息失败：" + Http.describe(e), e);
        }
    }
}
