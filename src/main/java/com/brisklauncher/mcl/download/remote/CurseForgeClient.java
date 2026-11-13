package com.brisklauncher.mcl.download.remote;

import com.brisklauncher.mcl.security.SafeLog;
import com.brisklauncher.mcl.util.CurseForgeKey;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CurseForge 搜索（可选下载源）。
 *
 * <p>两个 API 端点：
 * <ul>
 *   <li><b>官方</b> {@code https://api.curseforge.com/v1}：需要 {@code x-api-key}。
 *       key 的来源优先级：{@code -Dmcl.curseforge.key} → 设置页填的 key（加密存
 *       {@code launcher.json}）→ 构建期 {@code config/secrets.properties} → 环境变量。</li>
 *   <li><b>镜像</b> {@code https://mod.mcimirror.top/curseforge/v1}：与 Modrinth 镜像同一家
 *       （MCIM），返回 CurseForge 兼容 JSON，<b>不需要 key</b>。</li>
 * </ul>
 *
 * <p>端点顺序由设置页「下载源」决定：
 * <ul>
 *   <li>优先镜像（AUTO）/ 仅镜像（旧存档 BMCLAPI）→ 镜像先跑，失败再回退官方；</li>
 *   <li>优先官方（OFFICIAL）/ 自定义（CUSTOM）→ 官方先跑，失败再回退镜像。</li>
 * </ul>
 * 没有 key 时官方端点直接跳过（不是报错），镜像照常能用；只有「官方没有 key + 镜像也失败」
 * 才回 {@code noKey()} 让界面提示 API 为空。
 *
 * <p>下载地址：文件走 {@code edge/mediafilez.forgecdn.net}，镜像的文件地址是
 * {@code https://mod.mcimirror.top/files/{id/1000}/{id%1000}/{name}}，两个地址一起交给
 * 下载器按顺序回退（见 {@link #downloadCandidates(String, boolean)}）。
 */
public final class CurseForgeClient {

    private static final String OFFICIAL_BASE = "https://api.curseforge.com/v1";
    private static final String MIRROR_BASE = "https://mod.mcimirror.top/curseforge/v1";
    /** {@code -Dmcl.curseforge.mirror=none} 关闭镜像（离线 mock 测试用）。 */
    private static final String MIRROR_DISABLED = "none";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    /** 429 冷却 60s；网络 / 5xx 失败冷却 30s——避免对着一个已经挂掉的端点反复打。 */
    private static final long COOLDOWN_MS = 60_000L;
    private static final long FAILURE_COOLDOWN_MS = 30_000L;
    private static final long CACHE_TTL_MS = 5 * 60_000L;
    private static final int CACHE_LIMIT = 128;

    /** 429 冷却按端点分开记：官方被限流不该把镜像也停掉。 */
    private static final ConcurrentHashMap<String, Long> COOLDOWN = new ConcurrentHashMap<>();
    /** 连不上 / 5xx 的端点，短时间内不再尝试（含镜像）。 */
    private static final ConcurrentHashMap<String, Long> FAILED = new ConcurrentHashMap<>();

    /** 搜索结果缓存：同一条件的重复触发（双击、切回栏目）不再打网络。 */
    private static final ConcurrentHashMap<String, CacheEntry<SearchResult>> SEARCH_CACHE =
            new ConcurrentHashMap<>();
    /** bestFile 缓存：快速点选同一条目时不再重复请求文件清单。 */
    private static final ConcurrentHashMap<String, CacheEntry<FileLookup>> FILE_CACHE =
            new ConcurrentHashMap<>();
    /** bestFile 最终结果（含「确实没有文件」），避免每次重选都镜像+官方各打一次。 */
    private static final ConcurrentHashMap<String, CacheEntry<FileLookup>> FILE_RESULT_CACHE =
            new ConcurrentHashMap<>();

    private record CacheEntry<T>(T value, long at) {
    }

    /**
     * 发出请求：走 {@link com.brisklauncher.mcl.net.Http} 的共享 client
     * （代理 / 跳过 TLS 校验设置都在这边生效），TLS 握手失败时按项目惯例改用 TLS 1.2 重试一次。
     */
    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        HttpResponse<String> response;
        try {
            response = com.brisklauncher.mcl.net.Http.client().send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.io.IOException error) {
            if (!com.brisklauncher.mcl.net.Http.isSslProblem(error)) {
                throw error;
            }
            SafeLog.warn("TLS 握手失败，改用 TLS 1.2 重试（"
                    + com.brisklauncher.mcl.net.Http.hostOf(request.uri()) + "）");
            response = com.brisklauncher.mcl.net.Http.tls12Client().send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        return response;
    }

    private static <T> T cached(ConcurrentHashMap<String, CacheEntry<T>> cache, String key) {
        CacheEntry<T> entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() - entry.at() > CACHE_TTL_MS) {
            cache.remove(key);
            return null;
        }
        return entry.value();
    }

    private static <T> void remember(ConcurrentHashMap<String, CacheEntry<T>> cache,
                                     String key, T value) {
        if (cache.size() >= CACHE_LIMIT) {
            cache.clear();
        }
        cache.put(key, new CacheEntry<>(value, System.currentTimeMillis()));
    }

    /** 一个 API 端点。{@code official} 为 true 时才带 {@code x-api-key}。 */
    private record Endpoint(String base, boolean official) {
        String host() {
            try {
                String host = URI.create(base).getHost();
                return host == null ? base : host;
            } catch (Exception ignored) {
                return base;
            }
        }

        String hostKey() {
            try {
                URI uri = URI.create(base);
                return uri.getHost() == null ? base : uri.getHost().toLowerCase(Locale.ROOT);
            } catch (Exception ignored) {
                return base;
            }
        }
    }

    /** 与 Modrinth 的项目类型对齐。CurseForge classId：6 mods / 12 资源包 / 4471 整合包 / 6552 光影。 */
    private static int classId(String type) {
        return switch (type == null ? "" : type) {
            case "modpack" -> 4471;
            case "resourcepack" -> 12;
            case "shader" -> 6552;
            default -> 6;
        };
    }

    public record Project(String id, String slug, String name, String summary,
                          String iconUrl, long downloads, List<String> categories) {
        public String title() {
            return name == null ? "" : name;
        }
    }

    /**
     * 搜索结果。
     *
     * @param viaMirror 是否由镜像端点返回（界面据此标「镜像」）
     */
    public record SearchResult(List<Project> projects, int totalHits, String errorMessage,
                               boolean viaMirror) {
        public boolean isSuccess() {
            return errorMessage == null;
        }
    }

    /** CurseForge 的一个文件（下载用）。{@code gameVersions} 里会带 Client / Server / 加载器。 */
    public record FileEntry(String fileId, String fileName, String downloadUrl, long size,
                            List<String> gameVersions) {
    }

    private CurseForgeClient() {
    }

    // ---------- 端点与顺序 ----------

    private static String trimSlash(String value) {
        return value.replaceAll("/+$", "");
    }

    /** 官方地址；{@code -Dmcl.curseforge.api=...} 可覆写（开发 / 自架代理；key 会发到该地址）。 */
    private static String officialBase() {
        String override = System.getProperty("mcl.curseforge.api");
        return override == null || override.isBlank()
                ? OFFICIAL_BASE : trimSlash(override.trim());
    }

    /** 镜像地址；{@code -Dmcl.curseforge.mirror=none} 或空字串可关闭。 */
    private static String mirrorBase() {
        String override = System.getProperty("mcl.curseforge.mirror");
        if (override != null) {
            String value = override.trim();
            if (value.isEmpty() || MIRROR_DISABLED.equalsIgnoreCase(value)) {
                return "";
            }
            return trimSlash(value);
        }
        return MIRROR_BASE;
    }

    /** 官方端点是否值得尝试：有 key，或者显式指定了自架代理（可能不需要 key）。 */
    private static boolean officialUsable() {
        return CurseForgeKey.available() || System.getProperty("mcl.curseforge.api") != null;
    }

    /**
     * 按设置页「下载源」排好的端点顺序。
     *
     * @param mirrorFirst true = 镜像优先（优先镜像 / 仅镜像），false = 官方优先（优先官方 / 自定义）
     */
    private static List<Endpoint> endpoints(boolean mirrorFirst) {
        String mirror = mirrorBase();
        boolean official = officialUsable();
        // 自架代理可能与镜像同一地址：去重，避免一次搜索打两遍同一个端点
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        List<Endpoint> order = new ArrayList<>(2);
        if (mirrorFirst) {
            if (!mirror.isEmpty() && seen.add(mirror)) {
                order.add(new Endpoint(mirror, false));
            }
            String officialBase = officialBase();
            if (official && seen.add(officialBase)) {
                order.add(new Endpoint(officialBase, true));
            }
        } else {
            String officialBase = officialBase();
            if (official && seen.add(officialBase)) {
                order.add(new Endpoint(officialBase, true));
            }
            if (!mirror.isEmpty() && seen.add(mirror)) {
                order.add(new Endpoint(mirror, false));
            }
        }
        return order;
    }

    private static boolean cooling(Endpoint endpoint) {
        Long until = COOLDOWN.get(endpoint.hostKey());
        return until != null && until > System.currentTimeMillis();
    }

    private static void coolDown(Endpoint endpoint) {
        COOLDOWN.put(endpoint.hostKey(), System.currentTimeMillis() + COOLDOWN_MS);
    }

    private static boolean failed(Endpoint endpoint) {
        Long until = FAILED.get(endpoint.hostKey());
        return until != null && until > System.currentTimeMillis();
    }

    private static void penalize(Endpoint endpoint) {
        FAILED.put(endpoint.hostKey(), System.currentTimeMillis() + FAILURE_COOLDOWN_MS);
    }

    private static void succeeded(Endpoint endpoint) {
        COOLDOWN.remove(endpoint.hostKey());
        FAILED.remove(endpoint.hostKey());
    }

    /** 设置页换了 key / 改了镜像后，清掉冷却与缓存，下一次搜索立刻用新配置。 */
    public static void resetState() {
        COOLDOWN.clear();
        FAILED.clear();
        SEARCH_CACHE.clear();
        FILE_CACHE.clear();
        FILE_RESULT_CACHE.clear();
    }

    /** 端点现在能不能用（冷却中 / 刚失败过就跳过）。 */
    private static boolean usable(Endpoint endpoint) {
        return !cooling(endpoint) && !failed(endpoint);
    }

    // ---------- 搜索 ----------

    /** 未配置 key 且镜像也不可用时的固定结果，界面据此提示「API 为空」。 */
    public static SearchResult noKey() {
        return new SearchResult(List.of(), 0, "API 为空", false);
    }

    /**
     * 搜索。
     *
     * @param mirrorFirst true = 镜像优先（设置页「优先镜像 / 仅镜像」），false = 官方优先
     */
    public static SearchResult search(String query, String projectType, String gameVersion,
                                      String loader, int offset, boolean mirrorFirst) {
        List<Endpoint> endpoints = endpoints(mirrorFirst);
        if (endpoints.isEmpty()) {
            SafeLog.info("[curseforge] 没有可用的 API 端点（无 key 且镜像被关闭）");
            return noKey();
        }
        if (!officialUsable()) {
            SafeLog.info("[curseforge] 未配置 API key，官方端点跳过（镜像不需要 key）");
        }
        String lastError = null;
        for (Endpoint endpoint : endpoints) {
            if (!usable(endpoint)) {
                // 刚被限流 / 连不上：这轮直接跳过，别把失败端点再打一遍
                lastError = "来源暂时不可用，请稍后再试";
                continue;
            }
            SearchResult result = searchOnce(endpoint, query, projectType, gameVersion, loader, offset);
            if (result.isSuccess()) {
                succeeded(endpoint);
                return result;
            }
            lastError = result.errorMessage();
            SafeLog.info("[curseforge] " + endpoint.host() + " 搜索失败（" + lastError
                    + "），尝试下一个来源");
        }
        return new SearchResult(List.of(), 0, lastError == null ? "API 为空" : lastError, false);
    }

    private static SearchResult searchOnce(Endpoint endpoint, String query, String projectType,
                                           String gameVersion, String loader, int offset) {
        if (cooling(endpoint)) {
            return new SearchResult(List.of(), 0, "请求过于频繁，请稍后再试", false);
        }
        try {
            StringBuilder url = new StringBuilder(endpoint.base()).append("/mods/search?gameId=432");
            url.append("&classId=").append(classId(projectType));
            url.append("&pageSize=20&index=").append(Math.max(0, offset));
            url.append("&sortField=2&sortOrder=desc");
            if (query != null && !query.isBlank()) {
                url.append("&searchFilter=").append(URLEncoder.encode(query, StandardCharsets.UTF_8));
            }
            if (gameVersion != null && !gameVersion.isBlank()) {
                url.append("&gameVersion=").append(URLEncoder.encode(gameVersion, StandardCharsets.UTF_8));
            }
            String loaderId = loaderId(loader);
            if (loaderId != null) {
                url.append("&modLoaderType=").append(loaderId);
            }
            String requestUrl = url.toString();
            SearchResult cached = cached(SEARCH_CACHE, requestUrl);
            if (cached != null) {
                // 同一条件的重复触发（双击 / 切回栏目）直接用上次结果
                return cached;
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(requestUrl))
                    .header("Accept", "application/json")
                    .header("User-Agent", "BriskLauncher/1.1.3 (CurseForge)")
                    .timeout(TIMEOUT)
                    .GET();
            if (endpoint.official()) {
                // 注意：key 只发给官方；镜像不需要 key，也不应该拿到它
                String key = CurseForgeKey.resolve();
                if (key != null) {
                    builder.header("x-api-key", key);
                }
            }
            HttpResponse<String> response = send(builder.build());
            if (response.statusCode() == 429) {
                coolDown(endpoint);
                return new SearchResult(List.of(), 0, "请求过于频繁，请稍后再试", false);
            }
            if (response.statusCode() != 200) {
                if (response.statusCode() == 401 || response.statusCode() == 403
                        || response.statusCode() >= 500) {
                    penalize(endpoint);
                }
                return new SearchResult(List.of(), 0, "CurseForge 返回 HTTP "
                        + response.statusCode() + "（" + endpoint.host() + "）", false);
            }
            var root = com.brisklauncher.mcl.net.Http.mapper().readTree(response.body());
            var data = root.path("data");
            List<Project> projects = new ArrayList<>();
            if (data.isArray()) {
                for (var node : data) {
                    projects.add(new Project(
                            node.path("id").asText(""),
                            node.path("slug").asText(""),
                            node.path("name").asText(""),
                            node.path("summary").asText(""),
                            node.path("logo").path("thumbnailUrl").asText(""),
                            node.path("downloadCount").asLong(0),
                            List.of()));
                }
            }
            int total = root.path("pagination").path("totalCount").asInt(projects.size());
            SearchResult result = new SearchResult(projects, total, null, !endpoint.official());
            remember(SEARCH_CACHE, requestUrl, result);
            return result;
        } catch (Exception error) {
            // 连不上 / 超时 / 解析失败：30 秒内不再尝试这个端点
            penalize(endpoint);
            SafeLog.warn("[curseforge] " + endpoint.host() + " 搜索异常："
                    + error.getClass().getSimpleName());
            return new SearchResult(List.of(), 0, "CurseForge 请求失败："
                    + error.getClass().getSimpleName(), false);
        }
    }

    // ---------- 文件 ----------

    /** 一次文件查询的结果：{@code answered=false} 表示网络 / HTTP 失败，可以换端点再试。 */
    private record FileLookup(FileEntry entry, boolean answered) {
    }

    /**
     * 取某个 Mod 在指定 MC 版本 / 加载器下最新的可用文件。
     *
     * @param mirrorFirst 与 {@link #search} 相同的端点顺序
     */
    public static FileEntry bestFile(String modId, String gameVersion, String loader,
                                     boolean mirrorFirst) {
        if (modId == null || modId.isBlank()) {
            return null;
        }
        String resultKey = (mirrorFirst ? "m1|" : "m0|") + modId + "|" + gameVersion + "|" + loader;
        FileLookup memo = cached(FILE_RESULT_CACHE, resultKey);
        if (memo != null) {
            return memo.entry();
        }
        for (Endpoint endpoint : endpoints(mirrorFirst)) {
            if (!usable(endpoint)) {
                // 冷却中 / 刚失败：跳过，避免对着挂掉的端点连续打
                continue;
            }
            FileLookup lookup = bestFileOnce(endpoint, modId, gameVersion, loader);
            if (lookup.entry() != null) {
                succeeded(endpoint);
                remember(FILE_RESULT_CACHE, resultKey, lookup);
                return lookup.entry();
            }
            if (lookup.answered()) {
                // 端点正常响应但没文件：换端点也不会变多
                remember(FILE_RESULT_CACHE, resultKey, lookup);
                return null;
            }
            SafeLog.info("[curseforge] " + endpoint.host() + " 取文件失败，尝试下一个来源");
        }
        return null;
    }

    private static FileLookup bestFileOnce(Endpoint endpoint, String modId, String gameVersion,
                                           String loader) {
        if (cooling(endpoint)) {
            return new FileLookup(null, false);
        }
        try {
            StringBuilder url = new StringBuilder(endpoint.base())
                    .append("/mods/").append(modId).append("/files?pageSize=50");
            if (gameVersion != null && !gameVersion.isBlank()) {
                url.append("&gameVersion=").append(URLEncoder.encode(gameVersion, StandardCharsets.UTF_8));
            }
            String loaderId = loaderId(loader);
            if (loaderId != null) {
                url.append("&modLoaderType=").append(loaderId);
            }
            String requestUrl = url.toString();
            FileLookup memo = cached(FILE_CACHE, requestUrl);
            if (memo != null) {
                return memo;
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(requestUrl))
                    .header("Accept", "application/json")
                    .header("User-Agent", "BriskLauncher/1.1.3 (CurseForge)")
                    .timeout(TIMEOUT)
                    .GET();
            if (endpoint.official()) {
                String key = CurseForgeKey.resolve();
                if (key != null) {
                    builder.header("x-api-key", key);
                }
            }
            HttpResponse<String> response = send(builder.build());
            if (response.statusCode() == 429) {
                coolDown(endpoint);
                return new FileLookup(null, false);
            }
            if (response.statusCode() != 200) {
                if (response.statusCode() == 401 || response.statusCode() == 403
                        || response.statusCode() >= 500) {
                    penalize(endpoint);
                }
                SafeLog.warn("[curseforge] " + endpoint.host() + " 取文件失败：HTTP "
                        + response.statusCode());
                return new FileLookup(null, false);
            }
            var data = com.brisklauncher.mcl.net.Http.mapper().readTree(response.body()).path("data");
            if (!data.isArray() || data.isEmpty()) {
                // 200 但没数据：可能是镜像缓存缺口，允许换官方再试一次
                return new FileLookup(null, false);
            }
            // 数据已按上传时间倒序：取第一个有文件名的（作者禁用第三方下载时 downloadUrl 为空，
            // 由界面提示去网页下载，这里不偷偷换旧版本）
            FileLookup result = new FileLookup(null, true);
            for (var node : data) {
                String name = node.path("fileName").asText("");
                String download = node.path("downloadUrl").asText("");
                long size = node.path("fileLength").asLong(0);
                if (name.isEmpty()) {
                    continue;
                }
                result = new FileLookup(new FileEntry(node.path("id").asText(""), name, download, size,
                        toStringList(node.path("gameVersions"))), true);
                break;
            }
            remember(FILE_CACHE, requestUrl, result);
            return result;
        } catch (Exception error) {
            penalize(endpoint);
            SafeLog.warn("[curseforge] " + endpoint.host() + " 取文件异常："
                    + error.getClass().getSimpleName());
            return new FileLookup(null, false);
        }
    }

    /**
     * 一个 CurseForge 文件的候选下载地址（官方 CDN + MCIM 镜像），顺序按设置页下载源。
     *
     * <p>镜像地址是从 forgecdn 的 {@code /files/{id1}/{id2}/{name}} 路径反推的：同一份文件，
     * 只是换了域名。不是 forgecdn 的地址（作者外链）没有镜像，只回官方地址。
     *
     * @return 空列表表示「不是 CurseForge CDN 地址」或镜像已关闭，调用方走原有逻辑
     */
    public static List<URI> downloadCandidates(String downloadUrl, boolean mirrorFirst) {
        if (downloadUrl == null || downloadUrl.isBlank()) {
            return List.of();
        }
        URI official;
        try {
            official = URI.create(downloadUrl);
        } catch (Exception ignored) {
            return List.of();
        }
        String host = official.getHost() == null
                ? "" : official.getHost().toLowerCase(Locale.ROOT);
        if (!host.endsWith("forgecdn.net")) {
            return List.of();
        }
        URI mirror = mirrorFileUri(official);
        List<URI> result = new ArrayList<>(2);
        if (mirrorFirst) {
            if (mirror != null) {
                result.add(mirror);
            }
            result.add(official);
        } else {
            result.add(official);
            if (mirror != null) {
                result.add(mirror);
            }
        }
        return result;
    }

    /** 由 forgecdn 路径反推 MCIM 镜像文件地址；算不出来就回 null。 */
    private static URI mirrorFileUri(URI official) {
        String mirror = mirrorBase();
        if (mirror.isEmpty()) {
            return null;
        }
        try {
            String path = official.getRawPath();
            if (path == null || !path.startsWith("/files/")) {
                return null;
            }
            URI base = URI.create(mirror);
            String authority = base.getRawAuthority();
            if (authority == null || authority.isBlank()) {
                return null;
            }
            return URI.create(base.getScheme() + "://" + authority + "/files"
                    + path.substring("/files".length()));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static List<String> toStringList(com.fasterxml.jackson.databind.JsonNode array) {
        List<String> result = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (var node : array) {
                result.add(node.asText(""));
            }
        }
        return result;
    }

    private static String loaderId(String loader) {
        return switch (loader == null ? "" : loader.toLowerCase(Locale.ROOT)) {
            case "forge" -> "1";
            case "fabric" -> "4";
            case "quilt" -> "5";
            case "neoforge" -> "6";
            default -> null;
        };
    }
}
