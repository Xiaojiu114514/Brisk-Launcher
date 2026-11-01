package com.brisklauncher.mcl.modpack;

import com.brisklauncher.mcl.MclContext;
import com.brisklauncher.mcl.download.DownloadTask;
import com.brisklauncher.mcl.download.HashUtils;
import com.brisklauncher.mcl.download.remote.ModrinthClient;
import com.brisklauncher.mcl.game.GameInstance;
import com.brisklauncher.mcl.game.LoaderInfo;
import com.brisklauncher.mcl.game.install.InstallProgress;
import com.brisklauncher.mcl.security.SafeLog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 「Mod 一键升级到另一个游戏版本」。
 *
 * <p>典型场景：1.20.1 的整合包玩腻了想升到 1.21.1。
 * 旧做法是开新版本、一个个去搜、一个个点下载；这里一次算完：
 * <ol>
 *   <li>把来源实例的每个 mod 用 SHA-1 反查回 Modrinth 专案；</li>
 *   <li>按「目标 MC 版本 + 目标加载器」找该专案的最新相容档；</li>
 *   <li>能升的列出来、不能升的也列出来（不假装成功）；</li>
 *   <li>确认后统一下载到目标实例的 mods/（可以是既有实例，也可以是新建的）。</li>
 * </ol>
 */
public final class ModUpgradeService {

    /** 一個 mod 的升級判定。 */
    public record Entry(String fileName, String projectId, String title, String currentVersion,
                        ModrinthClient.VersionFile target, String status) {

        public boolean upgradable() {
            return target != null;
        }

        /** ok / no_compatible / unknown。 */
        public String statusKey() {
            return status;
        }
    }

    /** 分析結果。 */
    public record Plan(String sourceId, String targetMc, String loaderKey, List<Entry> entries) {

        public List<Entry> upgradable() {
            return entries.stream().filter(Entry::upgradable).toList();
        }

        public List<Entry> skipped() {
            return entries.stream().filter(entry -> !entry.upgradable()).toList();
        }

        public long unknownCount() {
            return entries.stream().filter(entry -> "unknown".equals(entry.status())).count();
        }
    }

    /** 執行結果。 */
    public record Result(int installed, List<String> failures) {
    }

    private ModUpgradeService() {
    }

    /** 加載器代號（Modrinth 的 loader 名稱；原版回傳空字串）。 */
    public static String loaderKey(LoaderInfo.LoaderType type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case FABRIC -> "fabric";
            case QUILT -> "quilt";
            case FORGE -> "forge";
            case NEOFORGE -> "neoforge";
            default -> "";
        };
    }

    /**
     * 分析：哪些 mod 能升到目标版本（会联网，呼叫方放背景执行绪）。
     */
    public static Plan analyze(MclContext context, GameInstance source, String targetMc,
                               String loaderKey, Consumer<String> progress) {
        List<com.brisklauncher.mcl.game.mod.ModManager.ContentFile> files =
                context.mods().list(source, com.brisklauncher.mcl.game.mod.ModManager.Kind.MODS);
        List<com.brisklauncher.mcl.game.mod.ModManager.ContentFile> jars = files.stream()
                .filter(file -> file.name().toLowerCase(Locale.ROOT).endsWith(".jar"))
                .toList();

        // 1) 并行反查每个 jar 对应的 Modrinth 专案（原本一个一个查，20+ 个 mod 要等很久）
        Map<String, ModrinthClient.FoundFile> found = new java.util.concurrent.ConcurrentHashMap<>();
        runParallel(jars, 8, jar -> {
            if (progress != null) {
                progress.accept(jar.name());
            }
            try {
                ModrinthClient.FoundFile result = context.modrinth().byFileHash(
                        HashUtils.sha1(jar.path()));
                if (result != null) {
                    found.put(jar.name(), result);
                }
            } catch (Exception error) {
                SafeLog.warn("升级分析：查不到 " + jar.name() + "（"
                        + error.getClass().getSimpleName() + "）");
            }
        });

        // 2) 并行查目标版本（每个专案一次请求），并在本地「严格」过滤：
        //    镜像/API 不一定真的照 game_versions 过滤，这里自己再看一次档案标签，
        //    不符合的直接丢掉 —— 这是「装了 1.21.1 的 mod」的最后一道防线。
        Map<String, ModrinthClient.VersionFile> targets = new java.util.concurrent.ConcurrentHashMap<>();
        Map<String, String> projectOf = new LinkedHashMap<>();
        for (var jar : jars) {
            ModrinthClient.FoundFile result = found.get(jar.name());
            if (result == null || result.projectId() == null || result.projectId().isBlank()) {
                continue;
            }
            projectOf.put(jar.name(), result.projectId());
        }
        runParallel(new ArrayList<>(projectOf.entrySet()), 8, item -> {
            String projectId = item.getValue();
            try {
                targets.put(projectId, pickForTarget(context, projectId, targetMc, loaderKey));
            } catch (Exception error) {
                SafeLog.warn("升级分析：取 " + projectId + " 的版本失败（"
                        + error.getClass().getSimpleName() + "）");
            }
        });

        // 3) 一次批次取专案名称（原本每个 mod 各发一次请求）
        Map<String, String> titles = new LinkedHashMap<>();
        if (!projectOf.isEmpty()) {
            try {
                for (ModrinthClient.Project project : context.modrinth()
                        .projects(new ArrayList<>(new java.util.LinkedHashSet<>(projectOf.values())))) {
                    titles.put(project.id(), project.title());
                }
            } catch (Exception error) {
                SafeLog.warn("升级分析：取专案名称失败（" + error.getClass().getSimpleName() + "）");
            }
        }

        List<Entry> entries = new ArrayList<>();
        for (var jar : jars) {
            ModrinthClient.FoundFile result = found.get(jar.name());
            String projectId = result == null ? "" : result.projectId();
            if (projectId == null || projectId.isBlank()) {
                entries.add(new Entry(jar.name(), "", jar.name(), "", null, "unknown"));
                continue;
            }
            String title = titles.getOrDefault(projectId, jar.name());
            String currentVersion = result.file().versionNumber();
            ModrinthClient.VersionFile target = targets.get(projectId);
            entries.add(new Entry(jar.name(), projectId, title, currentVersion, target,
                    target == null ? "no_compatible" : "ok"));
        }
        return new Plan(source.id(), targetMc, loaderKey, List.copyOf(entries));
    }

    /** 在目標版本/加載器裡挑最新的一個檔案；不符合的回傳 null。 */
    private static ModrinthClient.VersionFile pickForTarget(MclContext context, String projectId,
                                                            String targetMc, String loaderKey) {
        List<ModrinthClient.VersionFile> candidates = context.modrinth().versions(
                projectId,
                loaderKey == null || loaderKey.isBlank() ? List.of() : List.of(loaderKey),
                List.of(targetMc));
        List<ModrinthClient.VersionFile> matched = filterCompatible(candidates, targetMc, loaderKey);
        if (matched.isEmpty() && loaderKey != null && !loaderKey.isBlank()) {
            // 有些专案不填 loaders，放宽加载器再查一次（仍然要求版本标签符合）
            matched = filterCompatible(
                    context.modrinth().versions(projectId, List.of(), List.of(targetMc)),
                    targetMc, null);
        }
        if (matched.isEmpty()) {
            return null;
        }
        return matched.get(0);
    }

    /**
     * 本地过滤 + 依发布时间排序（新到旧）。
     *
     * <p>镜像站有时不理 {@code game_versions} 参数，直接把全部版本丢回来；
     * 只看「API 有帮我过滤」就会拿到 1.21.1 的档案。这里对每个候选再看一次标签。
     */
    static List<ModrinthClient.VersionFile> filterCompatible(
            List<ModrinthClient.VersionFile> candidates, String targetMc, String loaderKey) {
        List<ModrinthClient.VersionFile> matched = new ArrayList<>();
        for (ModrinthClient.VersionFile file : candidates) {
            if (!file.gameVersions().isEmpty() && !file.gameVersions().contains(targetMc)) {
                continue;
            }
            if (loaderKey != null && !loaderKey.isBlank()
                    && !file.loaders().isEmpty() && !file.loaders().contains(loaderKey)) {
                continue;
            }
            matched.add(file);
        }
        matched.sort((a, b) -> {
            String left = a.datePublished() == null ? "" : a.datePublished();
            String right = b.datePublished() == null ? "" : b.datePublished();
            return right.compareTo(left);
        });
        return matched;
    }

    /** 簡單的併行工具：固定併發數，任一項失敗不影響其它項。 */
    private static <T> void runParallel(List<T> items, int concurrency, Consumer<T> action) {
        if (items.isEmpty()) {
            return;
        }
        int workers = Math.max(1, Math.min(concurrency, items.size()));
        java.util.concurrent.atomic.AtomicInteger cursor = new java.util.concurrent.atomic.AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int index = 0; index < workers; index++) {
            Thread thread = Thread.ofVirtual().name("mcl-upgrade-" + index).unstarted(() -> {
                while (true) {
                    int position = cursor.getAndIncrement();
                    if (position >= items.size()) {
                        return;
                    }
                    try {
                        action.accept(items.get(position));
                    } catch (Exception ignored) {
                        // 单一项目失败不影响其它
                    }
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 检查某个升级目标档案是否真的对得上计划的目标版本 / 加载器。
     *
     * @return 对不上时回传中文原因，对得上回传 {@code null}
     */
    static String mismatch(Entry entry, Plan plan) {
        if (entry == null || plan == null || entry.target() == null) {
            return "没有可下载的目标档案";
        }
        var file = entry.target();
        if (!file.gameVersions().isEmpty() && !file.gameVersions().contains(plan.targetMc())) {
            return "版本不符（该档案只支援 " + String.join(",", file.gameVersions())
                    + "，目标是 " + plan.targetMc() + "）";
        }
        if (plan.loaderKey() != null && !plan.loaderKey().isBlank()
                && !file.loaders().isEmpty() && !file.loaders().contains(plan.loaderKey())) {
            return "加载器不符（该档案只有 " + String.join(",", file.loaders()) + "）";
        }
        return null;
    }

    /**
     * 把计划里可升级的 mod 下载到目标实例的 mods/。
     */
    public static Result apply(MclContext context, GameInstance target, Plan plan,
                               Consumer<InstallProgress> progress) {
        Path modsDir = target.gameDir().resolve("mods");
        try {
            Files.createDirectories(modsDir);
        } catch (Exception error) {
            throw new IllegalStateException("建立目标 mods 目录失败：" + error.getMessage(), error);
        }
        List<Entry> upgradable = plan.upgradable();
        int installed = 0;
        List<String> failures = new ArrayList<>();
        List<DownloadTask> tasks = new ArrayList<>();
         for (Entry entry : upgradable) {
            String mismatch = mismatch(entry, plan);
            if (mismatch != null) {
                failures.add(entry.fileName() + "：" + mismatch);
                continue;
            }
            Path destination = modsDir.resolve(Path.of(entry.target().fileName()).getFileName());
            tasks.add(new DownloadTask(List.of(java.net.URI.create(
                    entry.target().downloadUrl())), destination, entry.target().sha1(),
                    entry.target().size()));

        }
        // 一次交给下载器：它自己有并发（设定页的「下载并发数」），不要再一个一个排队
        if (!tasks.isEmpty()) {
            progress.accept(new InstallProgress(InstallProgress.Stage.ASSETS, 0.0,
                    "下载 " + tasks.size() + " 个 Mod"));
            try {
                context.downloader().downloadAll(tasks, snapshot -> {
                });
                installed = tasks.size();
            } catch (Exception error) {
                failures.add(String.valueOf(error.getMessage()));
            }
        }
        for (Entry entry : upgradable) {
            String mismatch = mismatch(entry, plan);
            if (mismatch != null && !failures.contains(entry.target().fileName() + "：" + mismatch)) {
                failures.add(entry.fileName() + "：" + mismatch);
            }
        }
        progress.accept(new InstallProgress(InstallProgress.Stage.DONE, 1.0, target.id()));
        SafeLog.info("Mod 升级完成：" + installed + " 个装进 " + target.id());
        return new Result(installed, List.copyOf(failures));
    }

    /**
     * 确保目标实例存在：已经有同 id 就直接用；否则按目标版本 + 加载器新建一个。
     *
     * <p>新建时走既有的 {@code GameRepository.installVanilla / installLoader}（与下载页同一条路径），
     * 所以原版档、libraries、natives 都会被正常补齐。
     */
    public static GameInstance ensureTarget(MclContext context,
                                            com.brisklauncher.mcl.game.GameDirectory root,
                                            String targetMc, LoaderInfo.LoaderType type,
                                            String instanceName, Consumer<InstallProgress> progress) {
        var repository = context.gameRepository();
        if (instanceName != null && !instanceName.isBlank()) {
            var existing = repository.find(root, instanceName);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        GameInstance created;
        if (type == null || type == LoaderInfo.LoaderType.VANILLA) {
            created = repository.installVanilla(root, targetMc, progress);
        } else {
            String loaderVersion = pickLoaderVersion(context, type, targetMc);
            created = repository.installLoader(root, type, targetMc, loaderVersion, progress);
        }
        if (instanceName != null && !instanceName.isBlank() && !instanceName.equals(created.id())) {
            created = repository.rename(created, instanceName);
        }
        return created;
    }

    /** 取目標版本下最新的加載器版本（例如 Fabric 0.19.x）。 */
    public static String pickLoaderVersion(MclContext context, LoaderInfo.LoaderType type,
                                           String targetMc) {
        try {
            var loaders = context.gameRepository().availableLoaders(type, targetMc);
            if (!loaders.isEmpty()) {
                return loaders.get(0).version();
            }
        } catch (Exception error) {
            SafeLog.warn("取加载器版本失败：" + error.getClass().getSimpleName());
        }
        return "";
    }

    /** 給界面用的：目標實例候選（同 MC 版本 + 同加載器類型）。 */
    public static List<GameInstance> candidates(MclContext context, String targetMc,
                                                LoaderInfo.LoaderType type) {
        Map<String, GameInstance> result = new LinkedHashMap<>();
        for (var root : context.gameRepository().roots()) {
            for (GameInstance instance : context.gameRepository().versions(root)) {
                if (!instance.mcVersion().equals(targetMc)) {
                    continue;
                }
                boolean vanilla = instance.loader() == null || instance.loader().isVanilla();
                if (type == null || type == LoaderInfo.LoaderType.VANILLA) {
                    if (vanilla) {
                        result.putIfAbsent(instance.id(), instance);
                    }
                } else if (!vanilla && instance.loader().type() == type) {
                    result.putIfAbsent(instance.id(), instance);
                }
            }
        }
        return new ArrayList<>(result.values());
    }
}
