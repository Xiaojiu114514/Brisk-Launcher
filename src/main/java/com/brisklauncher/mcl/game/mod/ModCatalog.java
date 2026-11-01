package com.brisklauncher.mcl.game.mod;

import com.brisklauncher.mcl.download.remote.ModrinthClient;
import com.brisklauncher.mcl.net.Http;
import com.brisklauncher.mcl.security.SafeLog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 已安装内容（mod/资源包/光影/存档）的「目录」：把本地档案对回 Modrinth 项目，
 * 并算出是否有更新。
 *
 * <p>本地 jar 只带档名，只有 SHA-1 能可靠对回项目（Modrinth 的
 * {@code /version_file/{sha1}}）；解析结果会快取，切换分类或重开页面不重复查。
 */
public final class ModCatalog {

    /** 一個已安裝檔案及其遠端來源（遠端資訊可能為 null：非 Modrinth 或尚未解析）。 */
    public record Entry(Path path, String fileName, boolean enabled, long size,
                        String sha1, String projectId, String title, String iconUrl,
                        String currentVersion, String latestVersion, String latestUrl) {

        /** 能更新：有遠端版本、且版本號和本地不一致。 */
        public boolean hasUpdate() {
            return latestUrl != null && !latestUrl.isBlank()
                    && (currentVersion == null || currentVersion.isBlank()
                    || !currentVersion.equals(latestVersion));
        }
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final ExecutorService POOL = Executors.newFixedThreadPool(8, runnable -> {
        Thread thread = new Thread(runnable, "mcl-mod-catalog");
        thread.setDaemon(true);
        return thread;
    });

    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();
    /** 正在解析的檔案（避免同一個 mod 被排進佇列多次）。 */
    private static final java.util.Set<String> IN_FLIGHT =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private record CacheEntry(String projectId, String title, String iconUrl,
                              String currentVersion, String latestVersion, String latestUrl) {
    }

    private final ModrinthClient modrinth;

    public ModCatalog(ModrinthClient modrinth) {
        this.modrinth = modrinth;
    }

    /** 先把本地檔案立刻列出來（UI 馬上能顯示），遠端資訊由 {@link #resolve} 補。 */
    public Entry local(Path path, boolean enabled) {
        String name = path.getFileName().toString();
        long size;
        try {
            size = Files.size(path);
        } catch (Exception ignored) {
            size = 0L;
        }
        CacheEntry cached = CACHE.get(cacheKey(name));
        if (cached == null) {
            return new Entry(path, name, enabled, size, null, null, null, null, null, null, null);
        }
        return new Entry(path, name, enabled, size, null, cached.projectId(), cached.title(),
                cached.iconUrl(), cached.currentVersion(), cached.latestVersion(), cached.latestUrl());
    }

    /**
     * 背景解析：算 SHA-1 → 反查项目 → 查当前 loader/MC 版本下的最新档。
     * 完成后在 {@code onResolved} 回呼（JavaFX 线程由呼叫方处理）。
     */
    /**
     * 批次解析：算 SHA-1 → 一次 {@code POST /version_files} 反查 → 一次 {@code /projects} 取名称/图标。
     *
     * <p>126 个 mod 以前是 126 × 2 次请求（还会被镜像限流，失败就刷一屏警告）；
     * 现在固定 **2～3 次请求**（每 100 个 hash 一批），而且完全不查「有没有新版本」。
     */
    public void resolveAll(java.util.Collection<Entry> entries, String loader, String mcVersion,
                           Consumer<Entry> onResolved) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        java.util.List<Entry> pending = new java.util.ArrayList<>();
        for (Entry entry : entries) {
            if (entry == null) {
                continue;
            }
            if (CACHE.containsKey(cacheKey(entry.fileName()))) {
                if (onResolved != null) {
                    onResolved.accept(withCache(entry, CACHE.get(cacheKey(entry.fileName()))));
                }
                continue;
            }
            pending.add(entry);
        }
        if (pending.isEmpty()) {
            return;
        }
        POOL.execute(() -> {
            try {
                // 1) 并行算 SHA-1（有快取，第二次打开几乎是 0 成本）
                java.util.Map<String, String> sha1OfFile = new java.util.concurrent.ConcurrentHashMap<>();
                com.brisklauncher.mcl.util.Parallel.forEach(pending, 8, entry -> {
                    try {
                        sha1OfFile.put(entry.fileName(), sha1Of(entry.path()));
                    } catch (Exception ignored) {
                        // 单一档案失败不影响其它
                    }
                });

                // 2) 一次批量反查（每 100 个一批）
                java.util.Map<String, ModrinthClient.FoundFile> found =
                        modrinth.versionFilesByHash(sha1OfFile.values());

                // 3) 一次批量取专案名称/图标
                java.util.Set<String> ids = new java.util.LinkedHashSet<>();
                for (ModrinthClient.FoundFile file : found.values()) {
                    if (file.projectId() != null && !file.projectId().isBlank()) {
                        ids.add(file.projectId());
                    }
                }
                java.util.Map<String, ModrinthClient.Project> projects = new java.util.LinkedHashMap<>();
                if (!ids.isEmpty()) {
                    try {
                        for (ModrinthClient.Project project : modrinth.projects(new java.util.ArrayList<>(ids))) {
                            projects.put(project.id(), project);
                        }
                    } catch (Exception error) {
                        SafeLog.warn("取 Mod 专案资讯失败（" + error.getClass().getSimpleName() + "）");
                    }
                }

                // 4) 写快取 + 回呼
                for (Entry entry : pending) {
                    String sha1 = sha1OfFile.get(entry.fileName());
                    ModrinthClient.FoundFile hit = sha1 == null ? null : found.get(sha1);
                    CacheEntry cached;
                    if (hit == null) {
                        cached = new CacheEntry(null, null, null, null, null, null);
                    } else {
                        ModrinthClient.Project project = projects.get(hit.projectId());
                        cached = new CacheEntry(hit.projectId(),
                                project == null ? null : project.title(),
                                project == null ? null : project.iconUrl(),
                                hit.file().versionNumber(), null, null);
                    }
                    CACHE.put(cacheKey(entry.fileName()), cached);
                    if (onResolved != null) {
                        onResolved.accept(withCache(entry, cached));
                    }
                }
            } catch (Exception error) {
                SafeLog.warn("批次解析内容来源失败（" + error.getClass().getSimpleName() + "）");
            }
        });
    }

    /** 單筆解析（保留給少數呼叫方）。 */
    public void resolve(Entry entry, String loader, String mcVersion, Consumer<Entry> onResolved) {
        resolveAll(java.util.List.of(entry), loader, mcVersion, onResolved);
    }

    /**
     * 检查更新（**显式呼叫**，不要在打开列表时自动跑）。
     *
     * <p>每个专案要问一次「目标 loader/MC 的最新档」，126 个 mod 就是 126 次请求，
     * 所以做成按钮：玩家想更新时才查，失败也只汇总一行日志。
     */
    public void checkUpdates(java.util.Collection<Entry> entries, String loader, String mcVersion,
                             Consumer<Entry> onResolved) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        POOL.execute(() -> {
            int failures = 0;
            for (Entry entry : entries) {
                CacheEntry cached = CACHE.get(cacheKey(entry.fileName()));
                if (cached == null || cached.projectId() == null || cached.projectId().isBlank()) {
                    continue;
                }
                try {
                    ModrinthClient.VersionFile latest =
                            modrinth.bestFile(cached.projectId(), loader, mcVersion);
                    CacheEntry updated = new CacheEntry(cached.projectId(), cached.title(),
                            cached.iconUrl(), cached.currentVersion(),
                            latest.versionNumber(), latest.downloadUrl());
                    CACHE.put(cacheKey(entry.fileName()), updated);
                    if (onResolved != null) {
                        onResolved.accept(withCache(entry, updated));
                    }
                } catch (Exception error) {
                    failures++;
                }
            }
            if (failures > 0) {
                SafeLog.warn("有 " + failures + " 个 Mod 查不到更新（网路或镜像问题），不影响本地使用");
            }
        });
    }

    private static Entry withCache(Entry entry, CacheEntry cached) {
        return new Entry(entry.path(), entry.fileName(), entry.enabled(), entry.size(), entry.sha1(),
                cached.projectId(), cached.title(), cached.iconUrl(),
                cached.currentVersion(), cached.latestVersion(), cached.latestUrl());
    }

    /**
     * 下载最新版并替换本地档案（保留原有的启用/禁用状态）。
     *
     * @return 新档案路径
     */
    public Path installLatest(Entry entry) throws Exception {
        if (entry.latestUrl() == null || entry.latestUrl().isBlank()) {
            throw new IllegalStateException("没有可用的下载地址");
        }
        byte[] bytes = Http.getBytes(java.net.URI.create(entry.latestUrl()), TIMEOUT);
        Path target = entry.path();
        Path temp = target.resolveSibling(target.getFileName() + ".part");
        Files.write(temp, bytes);
        // 新档名可能不同：先写 .part，再按新名字落盘，最后清掉旧档
        Path renamed = target.resolveSibling(newFileName(entry.latestUrl(), target.getFileName().toString()));
        Files.move(temp, renamed, StandardCopyOption.REPLACE_EXISTING);
        if (!renamed.equals(target)) {
            Files.deleteIfExists(target);
            Path disabled = target.resolveSibling(target.getFileName() + ".disabled");
            Files.deleteIfExists(disabled);
        }
        if (!entry.enabled() && Files.isRegularFile(renamed)) {
            Files.move(renamed, renamed.resolveSibling(renamed.getFileName() + ".disabled"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        CACHE.remove(cacheKey(entry.fileName()));
        return renamed;
    }

    private static String newFileName(String url, String fallback) {
        try {
            String path = java.net.URI.create(url).getPath();
            String name = path.substring(path.lastIndexOf('/') + 1);
            return name.isBlank() ? fallback : java.net.URLDecoder.decode(name, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String cacheKey(String fileName) {
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".disabled") ? lower.substring(0, lower.length() - ".disabled".length()) : lower;
    }

    private static String sha1Of(Path file) throws Exception {
        // 带快取：126 个 jar 不用每次刷新都重算好几百 MB
        return com.brisklauncher.mcl.util.FileHashes.sha1(file);
    }
}
