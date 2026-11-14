package com.brisklauncher.mcl.download.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.brisklauncher.mcl.net.Http;
import com.brisklauncher.mcl.net.HttpException;
import com.brisklauncher.mcl.security.SafeLog;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Modrinth 客户端（搜索 / 版本文件 / 下载地址）。
 *
 * <p>注意点（踩过的坑）：
 * <ul>
 *   <li>UA 必须带联系信息，否则被 403。</li>
 *   <li>空格必须编码成 {@code %20}，{@code +} 会被当成字面加号。</li>
 *   <li>facets 的每个分组是 AND，组内是 OR。</li>
 *   <li>国内直连 api.modrinth.com 经常超时，默认先走社区镜像再回退官方。</li>
 * </ul>
 */
public final class ModrinthClient {

    /** 專案資訊（名稱/圖標）的小快取：同一個 mod 不用每次重新問。 */
    private static final java.util.Map<String, Project> PROJECT_CACHE =
            java.util.Collections.synchronizedMap(
                    new java.util.LinkedHashMap<String, Project>(64, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(
                                java.util.Map.Entry<String, Project> eldest) {
                            return size() > 512;
                        }
                    });

    public static final String OFFICIAL_BASE = "https://api.modrinth.com/v2";
    /**
     * 来源顺序：**官方优先，镜像只做后备**。
     *
     * <p>第 142 轮曾经把镜像放第一位（理由是「国内官方常连不上」），结果是
     * {@code mod.mcimirror.top} 一被限流回 567 就整页没资料；实测官方 {@code api.modrinth.com}
     * 在这里是通的（搜寻约 1.1 秒）。所以改成官方先试，镜像只在官方失败时接手。
     */
    public static final List<String> DEFAULT_BASES = List.of(
            OFFICIAL_BASE,
            "https://mod.mcimirror.top/modrinth/v2");

    private static final String USER_AGENT =
            "BriskLauncher/1.1.4 (contact: github.com/brisklauncher/brisk-launcher)";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    public record Project(String id, String slug, String title, String description,
                          String author, long downloads, String iconUrl,
                          String projectType, String clientSide, String serverSide,
                          List<String> categories, List<String> gameVersions) {
    }

    public record SearchResult(List<Project> projects, int totalHits, String errorMessage) {
        public boolean isSuccess() {
            return errorMessage == null;
        }
    }

    /** 某个项目下的一个具体版本文件。 */
    public record VersionFile(String versionId, String name, String versionNumber,
                              String datePublished, String downloadUrl, String fileName,
                              String sha1, long size, List<String> gameVersions,
                              List<String> loaders, List<Dependency> dependencies) {

        public String displayName() {
            return name == null || name.isBlank() ? versionNumber : name;
        }
    }

    /** 版本的前置依赖；type 为 required / optional / incompatible / embedded。 */
    public record Dependency(String projectId, String versionId, String type, String fileName) {
    }

    /** 本地文件在 Modrinth 上的来源：所属项目 + 具体版本。 */
    public record FoundFile(String projectId, VersionFile file) {
    }


    // ---------- 限流 / 冷却 / 快取（安全审计 v3 A-13 之外的可用性修复，第 142 轮） ----------

    /**
     * 镜像的「冷却期」。
     *
     * <p>为什么需要：{@code mod.mcimirror.top} 在被自打爆时回 **HTTP 567**（限流），
     * 而我们的请求是「一个 UI 动作打十几次」——不冷却就会一直撞，还把官方也拖慢。
     * 命中 429/5xx 后，这个 host 在 120 秒内排到最后（不是完全不用，官方也挂时还能试）。
     */
    private static final java.util.Map<String, Long> COOLDOWN =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long COOLDOWN_MS = 20_000L;

    /** 請求快取：同一 path 在 TTL 內只打一次（版本清單一次 1.2MB，重打很傷）。 */
    private static final java.util.Map<String, CachedJson> CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    private record CachedJson(JsonNode node, long at) {
    }

    /** 全域併發閘門：最多 3 個 Modrinth 請求同時在飛。 */
    private static final java.util.concurrent.Semaphore GATE =
            new java.util.concurrent.Semaphore(3, true);

    /** 最近一次成功的來源（被限流時立刻換人，別一直重試同一個）。 */
    private static volatile String lastGoodBase;

    /** 健康優先的來源順序：最近成功的排第一，冷卻中的排到最後。 */
    private List<String> orderedBases() {
        long now = System.currentTimeMillis();
        List<String> healthy = new ArrayList<>();
        List<String> cooling = new ArrayList<>();
        for (String base : bases) {
            Long until = COOLDOWN.get(base);
            if (until != null && until > now) {
                cooling.add(base);
            } else {
                healthy.add(base);
            }
        }
        // 上次成功的来源提前（AUTO 的黏著性：不要每次都在两边跳）
        String sticky = lastGoodBase;
        if (sticky != null && healthy.remove(sticky)) {
            healthy.add(0, sticky);
        }
        healthy.addAll(cooling);
        return healthy;
    }

    /** 是否屬於「被打爆 / 限流」的狀態碼。 */
    private static boolean isThrottled(String message) {
        if (message == null) {
            return false;
        }
        return message.matches(".*HTTP (429|5\\d\\d).*");
    }

    private JsonNode fetchJson(String base, String path) {
        String key = base + path;
        CachedJson cached = CACHE.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.at() < CACHE_TTL_MS) {
            return cached.node();
        }
        Long until = COOLDOWN.get(base);
        if (until != null && until > now) {
            // 冷却中：快速失败，让回圈去下一个来源（总比等 20 秒逾时好）
            throw new HttpException("HTTP 567（" + Http.hostOf(URI.create(base)) + " 限流冷却中）");
        }
        boolean acquired = false;
        try {
            GATE.acquire();
            acquired = true;
            // 注意：这里必须是真正的 HTTP 请求。第 142 轮批量替换时把本行也换成了 fetchJson，
            // 造成无限递归 → 所有 Modrinth 数据变空（§10.6 记录了这次事故）。
            JsonNode node = Http.getJson(URI.create(base + path), TIMEOUT);
            CACHE.put(key, new CachedJson(node, System.currentTimeMillis()));
            saveDiskCache(path, node);
            COOLDOWN.remove(base);
            lastGoodBase = base;
            return node;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new HttpException("请求被中断");
        } catch (HttpException error) {
            if (isThrottled(error.getMessage())) {
                // 567/429/5xx：**立刻**换下一个来源（官方优先、镜像后备），不要重试同一个。
                // 以前是「退避 600ms 再试同源」，镜像被限流时等于每次都先撞一次 567。
                COOLDOWN.put(base, System.currentTimeMillis() + COOLDOWN_MS);
                if (base.equals(lastGoodBase)) {
                    lastGoodBase = null;
                }
                SafeLog.info("[modrinth] " + Http.hostOf(URI.create(base))
                        + " 限流（" + error.getMessage() + "），改用下一个来源");
            }
            throw error;
        } finally {
            if (acquired) {
                GATE.release();
            }
        }
    }

    // ---------- 本地快取（网络全挂时至少还有上一次的资料） ----------

    private static java.nio.file.Path diskCacheFile(String path) {
        String hash = Integer.toHexString(path.hashCode());
        return com.brisklauncher.mcl.AppPaths.dataDir().resolve("cache")
                .resolve("modrinth").resolve(hash + ".json");
    }

    private static void saveDiskCache(String path, JsonNode node) {
        try {
            java.nio.file.Path file = diskCacheFile(path);
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.write(file, Http.mapper().writeValueAsBytes(node));
        } catch (Exception ignored) {
            // 快取失败不影响功能
        }
    }

    /** 读上一次成功的响应（可能过期，但总比空白页好）。 */
    private static JsonNode staleJson(String path) {
        try {
            java.nio.file.Path file = diskCacheFile(path);
            if (!java.nio.file.Files.isRegularFile(file)) {
                return null;
            }
            return Http.mapper().readTree(file.toFile());
        } catch (Exception error) {
            return null;
        }
    }

    private final List<String> bases;

    public ModrinthClient() {
        this(DEFAULT_BASES);
    }

    public ModrinthClient(List<String> bases) {
        this.bases = List.copyOf(bases);
    }

    // ---------- 搜索 ----------

    public CompletableFuture<SearchResult> searchAsync(String query, String projectType,
                                                       List<String> loaders, List<String> gameVersions,
                                                       List<String> environments) {
        return CompletableFuture.supplyAsync(
                () -> search(query, projectType, loaders, gameVersions, environments));
    }

    public SearchResult search(String query, String projectType, List<String> loaders,
                              List<String> gameVersions, List<String> environments) {
        String path = buildSearchPath(query, projectType, loaders, gameVersions, environments, null, 0);
        String lastError = null;
        for (String base : orderedBases()) {
            try {
                JsonNode root = fetchJson(base, path);
                return parseSearch(root);
            } catch (Exception e) {
                lastError = Http.describe(e);
                SafeLog.warn("Modrinth 镜像失败 [" + Http.hostOf(URI.create(base)) + "]：" + lastError);
            }
        }
        // 网络全挂时用上一次成功的响应（可能过期，但总比空白页好）
        JsonNode stale = staleJson(path);
        if (stale != null) {
            SafeLog.warn("[modrinth] 网络不可用，使用本地快取的搜索结果");
            return parseSearch(stale);
        }
        return new SearchResult(List.of(), 0, "无法连接 Modrinth：" + (lastError == null ? "未知原因" : lastError));
    }

    private static String buildSearchPath(String query, String projectType, List<String> loaders,
                                          List<String> gameVersions, List<String> environments,
                                          String sortIndex, int offset) {
        StringBuilder path = new StringBuilder("/search?limit=20");
        if (offset > 0) {
            path.append("&offset=").append(offset);
        }
        if (query != null && !query.isBlank()) {
            path.append("&query=").append(encode(query.trim()));
        }
        if (sortIndex != null && !sortIndex.isBlank()) {
            path.append("&index=").append(encode(sortIndex));
        }
        List<String> groups = new ArrayList<>();
        if (projectType != null && !projectType.isBlank()) {
            groups.add(facet("project_type", List.of(projectType)));
        }
        if (loaders != null && !loaders.isEmpty()) {
            groups.add(facet("categories", loaders));
        }
        if (gameVersions != null && !gameVersions.isEmpty()) {
            groups.add(facet("versions", gameVersions));
        }
        if (environments != null && !environments.isEmpty()) {
            List<String> fields = new ArrayList<>();
            for (String env : environments) {
                if ("client".equals(env)) {
                    fields.add("client_side:required");
                } else if ("server".equals(env)) {
                    fields.add("server_side:required");
                }
            }
            if (!fields.isEmpty()) {
                groups.add("[" + String.join(",", fields.stream().map(f -> "\"" + f + "\"").toList()) + "]");
            }
        }
        if (!groups.isEmpty()) {
            path.append("&facets=").append(encode("[" + String.join(",", groups) + "]"));
        }
        return path.toString();
    }

    private static String facet(String field, List<String> values) {
        List<String> items = new ArrayList<>();
        for (String value : values) {
            items.add("\"" + field + ":" + value + "\"");
        }
        return "[" + String.join(",", items) + "]";
    }

    private static SearchResult parseSearch(JsonNode root) {
        List<Project> projects = new ArrayList<>();
        for (JsonNode hit : root.path("hits")) {
            projects.add(parseProject(hit));
        }
        return new SearchResult(projects, root.path("total_hits").asInt(projects.size()), null);
    }

    private static Project parseProject(JsonNode node) {
        String id = node.path("project_id").asText("");
        if (id.isEmpty()) {
            id = node.path("id").asText("");
        }
        return new Project(
                id,
                node.path("slug").asText(""),
                node.path("title").asText(""),
                node.path("description").asText(""),
                node.path("author").asText(""),
                node.path("downloads").asLong(0),
                node.path("icon_url").asText(null),
                node.path("project_type").asText(""),
                node.path("client_side").asText(""),
                node.path("server_side").asText(""),
                toStringList(node.path("categories")),
                toStringList(node.path("versions")));
    }

    /** 带排序的搜索：项目页打开时 query 为空 + index=downloads 即热门列表。 */
    public SearchResult search(String query, String projectType, List<String> loaders,
                               List<String> gameVersions, List<String> environments,
                               String sortIndex) {
        return search(query, projectType, loaders, gameVersions, environments, sortIndex, 0);
    }

    /** 带排序与分页偏移的搜索。 */
    public SearchResult search(String query, String projectType, List<String> loaders,
                               List<String> gameVersions, List<String> environments,
                               String sortIndex, int offset) {
        String path = buildSearchPath(query, projectType, loaders, gameVersions, environments,
                sortIndex, Math.max(0, offset));
        String lastError = null;
        for (String base : orderedBases()) {
            try {
                JsonNode root = fetchJson(base, path);
                return parseSearch(root);
            } catch (Exception e) {
                lastError = Http.describe(e);
                SafeLog.warn("Modrinth 镜像失败 [" + Http.hostOf(URI.create(base)) + "]：" + lastError);
            }
        }
        // 网络全挂时用上一次成功的响应（可能过期，但总比空白页好）
        JsonNode stale = staleJson(path);
        if (stale != null) {
            SafeLog.warn("[modrinth] 网络不可用，使用本地快取的搜索结果");
            return parseSearch(stale);
        }
        return new SearchResult(List.of(), 0, "无法连接 Modrinth：" + (lastError == null ? "未知原因" : lastError));
    }

    /** 搜索页未输入关键词时的热门列表。 */
    public SearchResult popular(String projectType) {
        String path = buildSearchPath("", projectType, List.of(), List.of(), List.of(), "downloads", 0);
        String lastError = null;
        for (String base : orderedBases()) {
            try {
                JsonNode root = fetchJson(base, path);
                return parseSearch(root);
            } catch (Exception e) {
                lastError = Http.describe(e);
            }
        }
        // 网络全挂时用上一次成功的响应（可能过期，但总比空白页好）
        JsonNode stale = staleJson(path);
        if (stale != null) {
            SafeLog.warn("[modrinth] 网络不可用，使用本地快取的搜索结果");
            return parseSearch(stale);
        }
        return new SearchResult(List.of(), 0, "无法连接 Modrinth：" + (lastError == null ? "未知原因" : lastError));
    }

    // ---------- 版本文件 ----------

    public List<VersionFile> versions(String projectId, List<String> loaders, List<String> gameVersions) {
        StringBuilder path = new StringBuilder("/project/").append(projectId).append("/version");
        List<String> params = new ArrayList<>();
        if (loaders != null && !loaders.isEmpty()) {
            params.add("loaders=" + encode(toJsonArray(loaders)));
        }
        if (gameVersions != null && !gameVersions.isEmpty()) {
            params.add("game_versions=" + encode(toJsonArray(gameVersions)));
        }
        if (!params.isEmpty()) {
            path.append('?').append(String.join("&", params));
        }

        HttpException last = null;
        // 健康优先（冷却中的镜像排到最后），官方永远是保底。
        List<String> order = orderedBases();
        for (String base : order) {
            try {
                JsonNode root = fetchJson(base, path.toString());
                return parseVersions(root);
            } catch (HttpException e) {
                last = e;
                // 冷却 / 快取 / 并发都由 fetchJson 处理，这里只换下一个来源
            }
        }
        JsonNode staleVersions = staleJson(path.toString());
        if (staleVersions != null) {
            SafeLog.warn("[modrinth] 网络不可用，使用本地快取的版本清单");
            return parseVersions(staleVersions);
        }
        throw last == null ? new HttpException("无法获取项目版本列表") : last;
    }

    /** 取第一个匹配（Modrinth 默认按发布时间倒序）。 */
    public VersionFile bestFile(String projectId, String loader, String gameVersion) {
        List<String> loaders = loader == null || loader.isBlank() ? List.of() : List.of(loader);
        List<String> versions = gameVersion == null || gameVersion.isBlank() ? List.of() : List.of(gameVersion);
        List<VersionFile> files = versions(projectId, loaders, versions);
        if (files.isEmpty() && !loaders.isEmpty()) {
            files = versions(projectId, List.of(), versions);
        }
        if (files.isEmpty()) {
            throw new HttpException("该模组没有兼容当前版本的文件");
        }
        return files.get(0);
    }

    private static List<VersionFile> parseVersions(JsonNode root) {
        List<VersionFile> result = new ArrayList<>();
        for (JsonNode node : root) {
            JsonNode primary = null;
            for (JsonNode file : node.path("files")) {
                if (file.path("primary").asBoolean(false)) {
                    primary = file;
                    break;
                }
                if (primary == null) {
                    primary = file;
                }
            }
            if (primary == null) {
                continue;
            }
            String url = primary.path("url").asText("");
            if (url.isEmpty()) {
                continue;
            }
            // 直接用镜像给的 sha1。
            // 曾经（第 135 轮）为了「不让镜像同时决定地址和哈希」而逐个版本再向官方 API 取一次 sha1，
            // 结果：① 国内连不上 api.modrinth.com，每个文件一条 WARNING 刷屏
            //      ② 列一次版本清单要几十个请求，明显变慢
            // 审计 v2 已把这条降为 B 类（镜像方可控 = 已能改我下发的字节，无法承诺），
            // 所以改回单次请求；完整性由下载时的 sha1 校验 + Modrinth 官方地址回退兜底。
            result.add(new VersionFile(
                    node.path("id").asText(""),
                    node.path("name").asText(""),
                    node.path("version_number").asText(""),
                    node.path("date_published").asText(""),
                    url,
                    primary.path("filename").asText(""),
                    primary.path("hashes").path("sha1").asText(""),
                    primary.path("size").asLong(0),
                    toStringList(node.path("game_versions")),
                    toStringList(node.path("loaders")),
                    parseDependencies(node.path("dependencies"))));
        }
        return result;
    }

    private static List<Dependency> parseDependencies(JsonNode array) {
        List<Dependency> dependencies = new ArrayList<>();
        if (!array.isArray()) {
            return dependencies;
        }
        for (JsonNode node : array) {
            String projectId = node.path("project_id").asText("");
            String versionId = node.path("version_id").asText("");
            if (projectId.isEmpty() && versionId.isEmpty()) {
                continue;
            }
            dependencies.add(new Dependency(projectId, versionId,
                    node.path("dependency_type").asText("required"),
                    node.path("file_name").asText("")));
        }
        return dependencies;
    }

    /**
     * 用本地文件的 SHA-1 反查它在 Modrinth 上的来源（项目 + 版本）。
     *
     * <p>「内容管理」里的图示、名称、更新检查都靠这个：玩家下载的 jar 本身
     * 只带文件名，只有哈希能可靠对回项目。
     *
     * @return 找不到（非 Modrinth 来源 / 未发布）时回传 {@code null}
     */
    public FoundFile byFileHash(String sha1) {
        if (sha1 == null || sha1.isBlank()) {
            return null;
        }
        for (String base : orderedBases()) {
            try {
                JsonNode node = fetchJson(base, "/version_file/" + sha1 + "?algorithm=sha1");
                if (node == null || !node.isObject()) {
                    continue;
                }
                JsonNode primary = null;
                for (JsonNode file : node.path("files")) {
                    if (file.path("primary").asBoolean(false)) {
                        primary = file;
                        break;
                    }
                    if (primary == null) {
                        primary = file;
                    }
                }
                if (primary == null) {
                    return null;
                }
                String url = primary.path("url").asText("");
                if (url.isEmpty()) {
                    return null;
                }
                VersionFile version = new VersionFile(
                        node.path("id").asText(""),
                        node.path("name").asText(""),
                        node.path("version_number").asText(""),
                        node.path("date_published").asText(""),
                        url,
                        primary.path("filename").asText(""),
                        primary.path("hashes").path("sha1").asText(""),
                        primary.path("size").asLong(0),
                        toStringList(node.path("game_versions")),
                        toStringList(node.path("loaders")),
                        parseDependencies(node.path("dependencies")));
                return new FoundFile(node.path("project_id").asText(""), version);
            } catch (HttpException ignored) {
                // 404 = 不是 Modrinth 来源；换镜像再试一次
            }
        }
        return null;
    }

    /**
     * 批量按 SHA-1 反查版本（Modrinth 的 {@code POST /version_files}）。
     *
     * <p>126 个 mod 就是 126 次请求，太慢也容易被镜像限流；
     * 这个端点一次可以问一百多个 hash，回传「hash → 版本」。
     *
     * @return sha1 → 找到的档案（找不到的不在 map 里）
     */
    public java.util.Map<String, FoundFile> versionFilesByHash(java.util.Collection<String> sha1s) {
        java.util.Map<String, FoundFile> result = new java.util.LinkedHashMap<>();
        if (sha1s == null || sha1s.isEmpty()) {
            return result;
        }
        List<String> hashes = new ArrayList<>(new java.util.LinkedHashSet<>(sha1s));
        // 一次最多 100 个，避免 URL/请求过大
        for (int from = 0; from < hashes.size(); from += 100) {
            List<String> chunk = hashes.subList(from, Math.min(from + 100, hashes.size()));
            String body = "{\"hashes\":" + toJsonArray(chunk) + ",\"algorithm\":\"sha1\"}";
            for (String base : orderedBases()) {
                try {
                    JsonNode root = com.brisklauncher.mcl.net.Http.postJson(
                            URI.create(base + "/version_files"), body, TIMEOUT);
                    if (root == null || !root.isObject()) {
                        continue;
                    }
                    var fields = root.fields();
                    while (fields.hasNext()) {
                        var entry = fields.next();
                        JsonNode node = entry.getValue();
                        JsonNode primary = null;
                        for (JsonNode file : node.path("files")) {
                            if (file.path("primary").asBoolean(false)) {
                                primary = file;
                                break;
                            }
                            if (primary == null) {
                                primary = file;
                            }
                        }
                        if (primary == null) {
                            continue;
                        }
                        VersionFile version = new VersionFile(
                                node.path("id").asText(""),
                                node.path("name").asText(""),
                                node.path("version_number").asText(""),
                                node.path("date_published").asText(""),
                                primary.path("url").asText(""),
                                primary.path("filename").asText(""),
                                primary.path("hashes").path("sha1").asText(""),
                                primary.path("size").asLong(0),
                                toStringList(node.path("game_versions")),
                                toStringList(node.path("loaders")),
                                parseDependencies(node.path("dependencies")));
                        result.put(entry.getKey(), new FoundFile(
                                node.path("project_id").asText(""), version));
                    }
                    break;
                } catch (Exception ignored) {
                    // 换下一个镜像
                }
            }
        }
        return result;
    }

    /** 按项目 id 批量取项目信息（用于显示前置 mod 的名字）。 */
    public List<Project> projects(List<String> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) {
            return List.of();
        }
        List<String> unique = new ArrayList<>(new java.util.LinkedHashSet<>(projectIds));
        List<Project> result = new ArrayList<>();
        // 先吃快取
        List<String> pending = new ArrayList<>();
        synchronized (PROJECT_CACHE) {
            for (String id : unique) {
                Project cached = PROJECT_CACHE.get(id);
                if (cached != null) {
                    result.add(cached);
                } else {
                    pending.add(id);
                }
            }
        }
        if (pending.isEmpty()) {
            return result;
        }
        // 一次 63 个 id 的查询在镜像上会卡 20 秒；拆成 20 个一批、并行 4 条
        List<List<String>> chunks = new ArrayList<>();
        for (int from = 0; from < pending.size(); from += 20) {
            chunks.add(pending.subList(from, Math.min(from + 20, pending.size())));
        }
        java.util.Map<String, Project> fetched = new java.util.concurrent.ConcurrentHashMap<>();
        com.brisklauncher.mcl.util.Parallel.forEach(chunks, 4, chunk -> {
            String path = "/projects?ids=" + encode(toJsonArray(chunk));
            for (String base : orderedBases()) {
                try {
                    JsonNode root = fetchJson(base, path);
                    for (JsonNode node : root) {
                        Project project = parseProject(node);
                        fetched.put(project.id(), project);
                    }
                    return;
                } catch (HttpException ignored) {
                    // 换下一个镜像
                }
            }
        });
        if (!fetched.isEmpty()) {
            synchronized (PROJECT_CACHE) {
                PROJECT_CACHE.putAll(fetched);
        }
        }
        result.addAll(fetched.values());
        return result;
    }

    /** 按 Modrinth 版本 id 精确取文件（依赖指定了版本时使用）。 */
    public VersionFile versionById(String versionId) {
        String path = "/version/" + encode(versionId);
        HttpException last = null;
        for (String base : orderedBases()) {
            try {
                JsonNode root = fetchJson(base, path);
                List<VersionFile> files = parseVersions(
                        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode().add(root));
                if (!files.isEmpty()) {
                    return files.get(0);
                }
            } catch (HttpException e) {
                last = e;
            }
        }
        // 网络全挂时用上一次成功的响应（可能过期，但总比报错好）
        JsonNode stale = staleJson(path);
        if (stale != null) {
            List<VersionFile> files = parseVersions(
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode().add(stale));
            if (!files.isEmpty()) {
                SafeLog.warn("[modrinth] 网络不可用，使用本地快取的版本文件");
                return files.get(0);
            }
        }
        throw last == null ? new HttpException("无法获取指定版本文件") : last;
    }

    // ---------- 工具 ----------

    private static List<String> toStringList(JsonNode array) {
        List<String> result = new ArrayList<>();
        for (JsonNode node : array) {
            result.add(node.asText(""));
        }
        return result;
    }

    private static String toJsonArray(List<String> values) {
        List<String> quoted = new ArrayList<>();
        for (String value : values) {
            quoted.add("\"" + value + "\"");
        }
        return "[" + String.join(",", quoted) + "]";
    }

    private static String encode(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
