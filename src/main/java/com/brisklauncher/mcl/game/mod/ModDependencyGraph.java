package com.brisklauncher.mcl.game.mod;

import com.brisklauncher.mcl.MclContext;
import com.brisklauncher.mcl.download.HashUtils;
import com.brisklauncher.mcl.download.remote.ModrinthClient;
import com.brisklauncher.mcl.game.GameInstance;
import com.brisklauncher.mcl.security.SafeLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 已安装 Mod 的**前置依赖关系图**。
 *
 * <p>做法（与 ModCatalog 同一套反查）：本地 jar 只有档名，算 SHA-1 → Modrinth
 * {@code /version_file/{sha1}} 拿到「专案 + 该版本」→ 读该版本的 {@code dependencies}
 * （只取 required）→ 建图。
 *
 * <p>图上的节点有三种状态：
 * <ul>
 *   <li>{@code INSTALLED}：本地装了，且能对回 Modrinth 专案；</li>
 *   <li>{@code MISSING}：被某个 Mod 依赖、但本地没有 → 界面上标红；</li>
 *   <li>{@code UNKNOWN}：本地装了但对不回 Modrinth（自己编的、第三方站下载的）。</li>
 * </ul>
 *
 * <p>纯资料层：不做布局、不碰界面（布局在 {@code ui/ModDependencyGraphDialog}）。
 */
public final class ModDependencyGraph {

    /** 節點狀態。 */
    public enum State {
        INSTALLED, MISSING, UNKNOWN
    }

    /**
     * 一个 Mod（或缺失的前置）。
     *
     * @param key       图内唯一键（有专案 id 用 {@code p:<id>}，否则 {@code f:<档名>}）
     * @param title     显示名称（Modrinth 专案名，取不到就用档名）
     * @param projectId Modrinth 专案 id（未知来源为空）
     * @param fileName  本地档名（缺失时为依赖宣告的档名，可能为空）
     * @param state     状态
     * @param version   本地版本号（未知时为空）
     */
    public record Node(String key, String title, String projectId, String fileName,
                       State state, String version) {
        public boolean installed() {
            return state == State.INSTALLED;
        }

        public boolean missing() {
            return state == State.MISSING;
        }
    }

    /** 一條「from 依賴 to」的邊。 */
    public record Edge(String from, String to) {
    }

    /** 建好的圖。 */
    public record Graph(String instanceId, List<Node> nodes, List<Edge> edges,
                        int truncated, String note) {

        public long missingCount() {
            return nodes.stream().filter(Node::missing).count();
        }

        public long unknownCount() {
            return nodes.stream().filter(node -> node.state() == State.UNKNOWN).count();
        }

        public long installedCount() {
            return nodes.stream().filter(Node::installed).count();
        }

        public Node node(String key) {
            for (Node node : nodes) {
                if (node.key().equals(key)) {
                    return node;
                }
            }
            return null;
        }
    }

    /** byFileHash 的記憶體快取：同一個 jar 重開依賴圖不用再打一次 Modrinth。 */
    private static final int MAX_LOOKUP_CACHE = 512;

    private static final java.util.Map<String, ModrinthClient.FoundFile> LOOKUP_CACHE =
            java.util.Collections.synchronizedMap(
                    new java.util.LinkedHashMap<String, ModrinthClient.FoundFile>(64, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(
                                java.util.Map.Entry<String, ModrinthClient.FoundFile> eldest) {
                            return size() > MAX_LOOKUP_CACHE;
                        }
                    });

    private static void rememberLookup(String sha1, ModrinthClient.FoundFile found) {
        LOOKUP_CACHE.put(sha1, found);
    }

    /** 一次最多分析多少個 Mod（再多的話界面放不下，也查不完）。 */
    /** 安全上限：正常整合包不會超過（126 個 mod 全部分析）。 */
    private static final int MAX_MODS = 600;

    private ModDependencyGraph() {
    }

    /**
     * 建图（会联网查 Modrinth；呼叫方应该放在背景执行绪）。
     *
     * @param progress 每分析完一个 Mod 回报一次（可为 null）
     */
    public static Graph build(MclContext context, GameInstance entry, Consumer<String> progress) {
        List<ModManager.ContentFile> files = context.mods().list(entry, ModManager.Kind.MODS);
        List<ModManager.ContentFile> jars = files.stream()
                .filter(file -> file.name().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
                .toList();
        // 全部分析（126 个 mod 用批次查询也是 2～3 次请求）；
        // 只有极端情况（>600 个 jar）才截断，避免图大到没意义。
        int truncated = Math.max(0, jars.size() - MAX_MODS);
        if (jars.size() > MAX_MODS) {
            jars = jars.subList(0, MAX_MODS);
        }

        Map<String, Node> nodes = new LinkedHashMap<>();
        Map<String, Set<String>> requires = new LinkedHashMap<>();
        Map<String, String> projectOfKey = new LinkedHashMap<>();
        Set<String> projectIds = new LinkedHashSet<>();
        // 本地 jar 内的 metadata（modid / 依赖）：对不上 Modrinth 的 Mod 也能画出依赖
        Map<String, ModJarMeta> metas = new LinkedHashMap<>();
        Map<String, String> modIdToKey = new LinkedHashMap<>();

        // 1) 并行算 SHA-1（有快取，第二次几乎 0 成本）
        Map<String, String> sha1ByFile = new java.util.concurrent.ConcurrentHashMap<>();
        com.brisklauncher.mcl.util.Parallel.forEach(jars, 8, file -> {
            if (progress != null) {
                progress.accept(file.name());
            }
            try {
                sha1ByFile.put(file.name(),
                        com.brisklauncher.mcl.util.FileHashes.sha1(file.path()));
            } catch (Exception ignored) {
                // 单一档案失败不影响其它
            }
        });

        // 2) 一次批量反查（POST /version_files）——60 个 mod 只要 1～2 次请求，
        //    以前是一个 jar 一次 /version_file，慢到像卡住。
        if (progress != null) {
            progress.accept("正在批量查询 Modrinth（" + sha1ByFile.size() + " 个档案）…");
        }
        Map<String, ModrinthClient.FoundFile> foundByFile =
                new java.util.concurrent.ConcurrentHashMap<>();
        try {
            Map<String, ModrinthClient.FoundFile> batch =
                    context.modrinth().versionFilesByHash(sha1ByFile.values());
            for (var item : sha1ByFile.entrySet()) {
                ModrinthClient.FoundFile hit = batch.get(item.getValue());
                if (hit != null) {
                    foundByFile.put(item.getKey(), hit);
                    rememberLookup(item.getValue(), hit);
                }
            }
        } catch (Exception error) {
            SafeLog.warn("前置依赖图：批量反查失败（" + error.getClass().getSimpleName()
                    + "），改用逐个查询");
            com.brisklauncher.mcl.util.Parallel.forEach(jars, 8, file -> {
                String sha1 = sha1ByFile.get(file.name());
                if (sha1 == null) {
                    return;
                }
                try {
                    ModrinthClient.FoundFile hit = context.modrinth().byFileHash(sha1);
                    if (hit != null) {
                        foundByFile.put(file.name(), hit);
                    }
                } catch (Exception ignored) {
                    // 查不到就当未知来源
                }
            });
        }

        // 3) 并行读 jar metadata（modid / 依赖）
        if (progress != null) {
            progress.accept("正在读取 Mod 资讯…");
        }
        Map<String, ModJarMeta> metaByFile = new java.util.concurrent.ConcurrentHashMap<>();
        com.brisklauncher.mcl.util.Parallel.forEach(jars, 8, file -> {
            try {
                ModJarMeta meta = ModJarMeta.read(file.path());
                if (meta != null) {
                    metaByFile.put(file.name(), meta);
                }
            } catch (Exception ignored) {
                // metadata 读不到不影响依赖图
            }
        });

        for (ModManager.ContentFile file : jars) {
            ModrinthClient.FoundFile found = foundByFile.get(file.name());
            String projectId = found == null ? "" : found.projectId();
            String key = projectId.isBlank() ? "f:" + file.name() : "p:" + projectId;
            String version = found == null ? "" : found.file().versionNumber();
            Node node = new Node(key,
                    found == null ? file.name() : file.name(),
                    projectId, file.name(),
                    projectId.isBlank() ? State.UNKNOWN : State.INSTALLED,
                    version);
            nodes.putIfAbsent(key, node);
            if (!projectId.isBlank()) {
                projectIds.add(projectId);
                projectOfKey.put(key, projectId);
                Set<String> deps = new LinkedHashSet<>();
                for (ModrinthClient.Dependency dependency : found.file().dependencies()) {
                    if ("required".equals(dependency.type()) && !dependency.projectId().isBlank()) {
                        deps.add(dependency.projectId());
                    }
                }
                requires.put(key, deps);
                // dependency 的专案也要抓标题
                projectIds.addAll(deps);
            }
            ModJarMeta meta = metaByFile.get(file.name());
            if (meta != null && !meta.modId().isBlank()) {
                metas.put(key, meta);
                modIdToKey.putIfAbsent(meta.modId(), key);
            }
        }

        // 缺失的前置节点：被依赖但本地没有
        for (Map.Entry<String, Set<String>> item : requires.entrySet()) {
            for (String depProject : item.getValue()) {
                String depKey = "p:" + depProject;
                nodes.computeIfAbsent(depKey, ignored -> new Node(depKey, depProject, depProject,
                        "", State.MISSING, ""));
            }
        }

        // 对不上 Modrinth 的 Mod：从 jar 内的 fabric.mod.json / mods.toml 补依赖关系
        List<Edge> metaEdges = new ArrayList<>();
        for (Map.Entry<String, ModJarMeta> item : metas.entrySet()) {
            String key = item.getKey();
            Node node = nodes.get(key);
            if (node == null || node.state() != State.UNKNOWN) {
                continue;
            }
            for (String dependency : item.getValue().depends()) {
                String targetKey = modIdToKey.get(dependency);
                if (targetKey == null) {
                    String missingKey = "m:" + dependency;
                    nodes.computeIfAbsent(missingKey, ignored ->
                            new Node(missingKey, dependency, "", "", State.MISSING, ""));
                    targetKey = missingKey;
                }
                if (!targetKey.equals(key)) {
                    metaEdges.add(new Edge(key, targetKey));
                }
            }
        }

        // 批次取专案名（一次请求）
        Map<String, String> titles = new LinkedHashMap<>();
        if (!projectIds.isEmpty()) {
            try {
                for (ModrinthClient.Project project : context.modrinth()
                        .projects(new ArrayList<>(projectIds))) {
                    titles.put(project.id(), project.title());
                }
            } catch (Exception error) {
                SafeLog.warn("前置依赖图：取专案名称失败（" + error.getClass().getSimpleName() + "）");
            }
        }

        if (progress != null) {
            progress.accept("正在整理 " + projectIds.size() + " 个专案名称…");
        }
        List<Node> finalNodes = new ArrayList<>();
        for (Node node : nodes.values()) {
            String title = node.projectId().isBlank() ? null : titles.get(node.projectId());
            finalNodes.add(new Node(node.key(),
                    title == null || title.isBlank() ? node.title() : title,
                    node.projectId(), node.fileName(), node.state(), node.version()));
        }
        finalNodes.sort((a, b) -> {
            if (a.state() != b.state()) {
                return Integer.compare(rank(a.state()), rank(b.state()));
            }
            return String.CASE_INSENSITIVE_ORDER.compare(a.title(), b.title());
        });

        List<Edge> edges = new ArrayList<>(metaEdges);
        Set<String> seen = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> item : requires.entrySet()) {
            for (String depProject : item.getValue()) {
                String to = "p:" + depProject;
                if (seen.add(item.getKey() + "->" + to)) {
                    edges.add(new Edge(item.getKey(), to));
                }
            }
        }

        String note = truncated > 0
                ? "Mod 数量超过 " + MAX_MODS + "，只分析了前 " + MAX_MODS + " 个（还有 " + truncated + " 个未显示）"
                : "";
        return new Graph(entry.id(), List.copyOf(finalNodes), List.copyOf(edges), truncated, note);
    }

    private static int rank(State state) {
        return switch (state) {
            case MISSING -> 0;
            case INSTALLED -> 1;
            default -> 2;
        };
    }
}
