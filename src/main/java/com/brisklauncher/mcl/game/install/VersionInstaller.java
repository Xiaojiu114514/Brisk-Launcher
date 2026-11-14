package com.brisklauncher.mcl.game.install;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.brisklauncher.mcl.game.Libraries;
import com.brisklauncher.mcl.game.Rules;
import com.brisklauncher.mcl.game.GameInstance;
import com.brisklauncher.mcl.game.GameVersionMerger;
import com.brisklauncher.mcl.game.GameDirectory;
import com.brisklauncher.mcl.game.GameScanner;
import com.brisklauncher.mcl.download.DownloadProgress;
import com.brisklauncher.mcl.download.DownloadTask;
import com.brisklauncher.mcl.download.HashUtils;
import com.brisklauncher.mcl.download.HttpDownloader;
import com.brisklauncher.mcl.download.MirrorResolver;
import com.brisklauncher.mcl.net.Http;
import com.brisklauncher.mcl.download.remote.MojangApi;
import com.brisklauncher.mcl.security.SafeLog;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 原版安装与文件补全。
 *
 * <p>这是整个启动器的地基，也是原项目最大的缺口（README 里写明的“缺少 Library/Assets 补全”）。
 * 流程与 PCL2/HMCL 一致：
 * <ol>
 *   <li>下载版本 JSON 并落盘到 {@code versions/<id>/<id>.json}；</li>
 *   <li>按 rules 过滤后下载 client jar 与全部 libraries（含 natives 构件）；</li>
 *   <li>下载资源索引与全部资源对象，并写 {@code assets/indexes/<index>.json}；</li>
 *   <li>下载 log4j 配置到 {@code assets/log_configs/}；</li>
 *   <li>老版本额外把对象复制到 {@code assets/virtual/<index>} 或 {@code resources/}。</li>
 * </ol>
 * 所有下载都经过 {@link HttpDownloader}：先校验本地文件，再决定是否产生流量。
 */
public final class VersionInstaller {

    public static final String MOJANG_RESOURCES = "https://resources.download.minecraft.net/";

    private final MojangApi mojang;
    private final MirrorResolver mirrors;
    private final HttpDownloader downloader;
    private final ObjectMapper mapper = Http.mapper();
    private final GameScanner scanner = new GameScanner();

    public VersionInstaller(MojangApi mojang, MirrorResolver mirrors, HttpDownloader downloader) {
        this.mojang = mojang;
        this.mirrors = mirrors;
        this.downloader = downloader;
    }

    // ---------- 安装原版 ----------

    public GameInstance install(GameDirectory root, MojangApi.VersionMeta meta,
                                Consumer<InstallProgress> progress) {
        progress.accept(new InstallProgress(InstallProgress.Stage.RESOLVING, 0.01, meta.id()));

        JsonNode versionJson = mojang.versionJson(meta);
        Path versionDir = root.versionsDir().resolve(
                com.brisklauncher.mcl.security.PathSafety.requirePathSegment(meta.id()));
        try {
            Files.createDirectories(versionDir);
            writeJson(versionDir.resolve(
                    com.brisklauncher.mcl.security.PathSafety.requirePathSegment(meta.id()) + ".json"), versionJson);
        } catch (IOException e) {
            throw new InstallException("写入版本 JSON 失败：" + e.getMessage(), e);
        }
        progress.accept(new InstallProgress(InstallProgress.Stage.VERSION_JSON, 0.05, meta.id()));

        GameInstance entry = scanner.scanSingle(root, meta.id())
                .orElseThrow(() -> new InstallException("版本 JSON 写入后仍无法识别：" + meta.id()));

        // 安装期间锁住这个版本：不允许在「版本设置」里删掉它（见 InstallLocks）
        Runnable unlock = InstallLocks.begin(
                InstallLocks.key(root.path().toString(), entry.id()), entry.id());
        try {
            repair(entry, progress);
        } finally {
            unlock.run();
        }
        progress.accept(new InstallProgress(InstallProgress.Stage.DONE, 1.0, meta.id()));
        return entry;
    }

    // ---------- 补全已存在的版本 ----------

    /**
     * 校验并补全：client jar、libraries、assets、log 配置。
     * 对整合包导入后的残缺实例同样适用（这就是“修好别人下载坏的整合包”的能力）。
     */
    public void repair(GameInstance entry, Consumer<InstallProgress> progress) {
        // 每次补全（含安装、修复、整合包导入）都重新问一次「缺校验值」——但一次只问一个
        beginUnsignedSession();
        // 补全过程也会写档案：同样锁住这个版本，避免被同时删掉
        Runnable unlock = InstallLocks.begin(
                InstallLocks.key(entry.root().path().toString(), entry.id()), entry.id());
        try {
            repairLocked(entry, progress);
        } finally {
            unlock.run();
        }
    }

    private void repairLocked(GameInstance entry, Consumer<InstallProgress> progress) {
        ObjectNode merged;
        try {
            merged = new GameVersionMerger(entry.root().path()).merge(entry);
        } catch (IOException e) {
            throw new InstallException(e.getMessage(), e);
        }

        progress.accept(new InstallProgress(InstallProgress.Stage.RESOLVING, 0.02, entry.id()));

        // 客户端 jar 与依赖库合成**一批**下载：多线程一次拉完，少一个阶段性的等待点
        List<DownloadTask> clientTasks = clientJarTasks(entry);
        List<DownloadTask> libraryTasks = libraryTasks(entry.root().path(), merged);
        List<DownloadTask> batch = new ArrayList<>(clientTasks);
        batch.addAll(libraryTasks);
        SafeLog.info("[install] " + entry.id() + " 客户端=" + clientTasks.size()
                + " 依赖库=" + libraryTasks.size() + " 资源索引=" + merged.path("assetIndex").path("id").asText("(无)"));
        phase(batch, InstallProgress.Stage.LIBRARIES, 0.05, 0.60, progress);

        downloadAssets(entry, merged, progress);
        downloadLogging(entry, merged, progress);

        progress.accept(new InstallProgress(InstallProgress.Stage.FINISHING, 0.99, entry.id()));
    }

    /**
     * 快速判断「缺了必定起不来」的必需文件是否齐全（只看存在性，不做哈希，用于 UI 状态展示）。
     *
     * <p>**刻意不查 libraries**：PCL / 官方安装器装的实例经常出现「版本 JSON 里的库版本
     * 与 libraries 目录里的实际版本对不上」（第 14 / 75 轮：把库缺失算成「需要补全」→
     * 能正常启动的实例永远显示 ⚠）。要补全的完整清单见 {@link #missingFiles}。
     */
    public boolean looksComplete(GameInstance entry) {
        return missingEssentials(entry).isEmpty();
    }

    /** 必需文件：继承链上的 client jar（清单是空壳时也算缺）。 */
    public List<String> missingEssentials(GameInstance entry) {
        List<String> missing = new ArrayList<>();
        try {
            ObjectNode merged = new GameVersionMerger(entry.root().path()).merge(entry);
            List<String> chain = GameVersionMerger.chain(entry.root().path(), entry.id());
            boolean hasJar = false;
            for (String id : chain) {
                if (Files.isRegularFile(entry.root().path().resolve("versions").resolve(com.brisklauncher.mcl.security.PathSafety.requirePathSegment(id))
                        .resolve(id + ".jar"))) {
                    hasJar = true;
                    break;
                }
            }
            if (!hasJar && !merged.path("downloads").path("client").path("url").asText("").isEmpty()) {
                missing.add("versions/" + entry.id() + "/" + entry.id() + ".jar");
            }
            String indexId = ""; // assets 在启动时自动补，不计入“需要补全”
            if (!indexId.isEmpty()
                    && !Files.isRegularFile(entry.root().assetsDir().resolve("indexes").resolve(indexId + ".json"))) {
                missing.add("assets/indexes/" + indexId + ".json");
            }
            // log4j 配置不算「必须存在」：官方启动器/PCL 装的版本目录里通常没有
            // assets/log_configs，缺了游戏照样跑（GameLauncher 会自动跳过）。
            // 这里要是算进缺失列表，别人的实例会永远显示「需要补全」。
        } catch (Exception e) {
            missing.add("版本 JSON 无法解析");
        }
        return missing;
    }

    /**
     * 启动前要补全的文件：必需文件 + 真的有下载地址的 libraries（最多列 12 条）。
     *
     * <p>这份清单只用来「尽力补全」与日志诊断，**不再当作能不能启动的闸门**：
     * 缺库的实例补完就能跑，补不回来的由游戏自己报错（早退检测会带出退出码与日志尾部），
     * 这样既能自愈，又不会把本来能启动的实例挡在门外（第 118 轮的教训）。
     */
    public List<String> missingFiles(GameInstance entry) {
        List<String> essential = missingEssentials(entry);
        if (!essential.isEmpty()) {
            return essential;
        }
        List<String> missing = new ArrayList<>();
        try {
            ObjectNode merged = new GameVersionMerger(entry.root().path()).merge(entry);
            for (JsonNode library : merged.path("libraries")) {
                if (!Rules.allows(library, Rules.Features.none()) || Libraries.isNativesOnly(library)) {
                    continue;
                }
                // 只报「有下载地址」的构件。理由（第 189 轮）：
                // - 加载器（Forge/NeoForge）自己生成的库常常没有 downloads 字段，路径只能按名字硬推，
                //   推错就会「明明能启动却永远报缺」；
                // - 没有 URL 也无从修复，报出来只会让日志噪声变大。
                // 所以：有 URL ⇒ 真的缺 ⇒ 一定补得回来；没 URL ⇒ 不参与判定。
                String artifactUrl = library.path("downloads").path("artifact").path("url").asText("");
                if (artifactUrl.isBlank()) {
                    continue;
                }
                // 路径统一走 Libraries.pathOf（优先 downloads.artifact.path，没有才按 name 推导），
                // 与 libraryTasks() / buildClasspath() 保持同一套规则。
                String relative = Libraries.pathOf(library);
                if (relative == null || relative.isBlank()) {
                    continue;
                }
                if (!Files.isRegularFile(com.brisklauncher.mcl.security.PathSafety.resolveUnder(entry.root().librariesDir(), relative))) {
                    missing.add("libraries/" + relative);
                    if (missing.size() >= 12) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            missing.add("版本 JSON 无法解析");
        }
        return missing;
    }

    // ---------- client jar ----------

    private List<DownloadTask> clientJarTasks(GameInstance entry) {
        Path gameRoot = entry.root().path();
        List<DownloadTask> tasks = new ArrayList<>();
        for (String id : GameVersionMerger.chain(gameRoot, entry.id())) {
            id = com.brisklauncher.mcl.security.PathSafety.requirePathSegment(id);
            Path jsonFile = gameRoot.resolve("versions").resolve(com.brisklauncher.mcl.security.PathSafety.requirePathSegment(id))
                    .resolve(com.brisklauncher.mcl.security.PathSafety.requirePathSegment(id) + ".json");
            if (!Files.isRegularFile(jsonFile)) {
                continue;
            }
            JsonNode client;
            try {
                client = mapper.readTree(jsonFile.toFile()).path("downloads").path("client");
            } catch (IOException e) {
                continue;
            }
            String url = client.path("url").asText("");
            if (url.isEmpty()) {
                continue;
            }
            Path jar = gameRoot.resolve("versions").resolve(com.brisklauncher.mcl.security.PathSafety.requirePathSegment(id))
                    .resolve(com.brisklauncher.mcl.security.PathSafety.requirePathSegment(id) + ".jar");
            tasks.add(new DownloadTask(mirrors.resolve(url), jar,
                    client.path("sha1").asText(null), client.path("size").asLong(-1)));
        }
        return tasks;
    }

    // ---------- libraries ----------


    /**
     * 没有校验值的下载：弹窗让使用者看「档名 + 下载来源」再决定。
     *
     * <p><b>一次安装只问一次</b>：以前是以「档案路径」去重，于是 70 个缺 sha1 的依赖库
     * 会连弹 70 次（使用者回报的「问不停」）。现在改成「每次安装（beginUnsignedSession）
     * 只问一次」，之后同一次安装里所有缺校验值的档案都沿用同一个决定。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean UNSIGNED_CONFIRMED =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 這次安裝已經問過（不論同意或拒絕）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean UNSIGNED_ASKED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 开始一次安装：重设「缺校验值」的询问状态，让每次安装都重新问一次。
     *
     * <p>由 {@link #install} / {@link #repair} 呼叫；加载器安装也会走 repair，所以同样受益。
     */
    public static void beginUnsignedSession() {
        UNSIGNED_ASKED.set(false);
        UNSIGNED_CONFIRMED.set(false);
    }

    private static boolean confirmUnsigned(Path relative, java.net.URI source) {
        // 开发期/自动化专用：没有 JavaFX（探针、headless 测试）时无法弹窗，直接视为同意。
        // 只认显式系统属性，正常启动不会生效。
        if (Boolean.getBoolean("mcl.dev.acceptunsigned")) {
            UNSIGNED_ASKED.set(true);
            UNSIGNED_CONFIRMED.set(true);
            return true;
        }
        if (UNSIGNED_ASKED.get()) {
            // 这次安装已经问过：同意就全部放行，拒绝就已经在第一次抛出例外了
            return UNSIGNED_CONFIRMED.get();
        }
        UNSIGNED_ASKED.set(true);
        String key = relative == null ? "" : relative.toString();
        String url = source == null ? "" : source.toString();
        boolean ok = com.brisklauncher.mcl.ui.MCLDialog.confirmBlocking(null,
                com.brisklauncher.mcl.i18n.I18n.t("install.unsigned.title"),
                com.brisklauncher.mcl.i18n.I18n.t("install.unsigned.body", key, url)
                        + "\n\n" + com.brisklauncher.mcl.i18n.I18n.t("install.unsigned.once"));
        if (!ok) {
            throw new InstallException("使用者取消：缺少校验值的下载（" + key + "）");
        }
        com.brisklauncher.mcl.security.SafeLog.info(
                "[install] 使用者同意略过校验值（本次安装只问一次）");
        UNSIGNED_CONFIRMED.set(true);
        return true;
    }

    /**
     * JavaFX toolkit 有没有起来（headless 探针里没有）。
     *
     * <p>仅供除错参考；正式流程用 {@code -Dmcl.dev.acceptunsigned} 显式控制。
     */
    static boolean hasFxToolkit() {
        try {
            Class<?> impl = Class.forName("com.sun.javafx.application.PlatformImpl");
            java.lang.reflect.Field field = impl.getDeclaredField("initialized");
            field.setAccessible(true);
            return field.getBoolean(null);
        } catch (Exception ignored) {
            return false;
        }
    }

    private List<DownloadTask> libraryTasks(Path gameRoot, JsonNode merged) {
        List<DownloadTask> tasks = new ArrayList<>();
        Path librariesDir = gameRoot.resolve("libraries");
        for (JsonNode library : merged.path("libraries")) {
            if (!Rules.allows(library, Rules.Features.none())) {
                continue;
            }
            addArtifact(tasks, librariesDir, library);

            // 老版本（1.6 ~ 1.12）的 natives 走 classifiers
            JsonNode classifiers = library.path("downloads").path("classifiers");
            if (classifiers.isObject()) {
                String classifierKey = Libraries.nativeClassifier(library);
                if (classifierKey != null) {
                    JsonNode nativeArtifact = classifiers.path(classifierKey);
                    String url = nativeArtifact.path("url").asText("");
                    String path = nativeArtifact.path("path").asText("");
                    if (!url.isEmpty() && !path.isEmpty()) {
                        tasks.add(new DownloadTask(mirrors.resolve(url),
                                com.brisklauncher.mcl.security.PathSafety.resolveUnder(librariesDir, path),
                                nativeArtifact.path("sha1").asText(null), nativeArtifact.path("size").asLong(-1)));
                    }
                }
            }
        }
        return tasks;
    }

    private void addArtifact(List<DownloadTask> tasks, Path librariesDir, JsonNode library) {
        // natives-only 库没有普通构件；按 name 推导出来的是不存在的路径（会 404）
        if (Libraries.isNativesOnly(library)) {
            return;
        }
        String relative = Libraries.pathOf(library);
        if (relative == null) {
            return;
        }
        JsonNode artifact = library.path("downloads").path("artifact");
        String url = artifact.path("url").asText("");
        if (url.isEmpty()) {
            url = Libraries.baseUrl(library) + relative;
        }
        String sha1 = artifact.path("sha1").asText(null);
        long size = artifact.path("size").asLong(-1);
        // P1-3（放宽）：原版清单一定带 sha1；但**加载器 profile / 镜像**常常没有，
        // 那属于上游资料问题，不能因此让整个安装失败 → 记录警告后照常下载。
        if (sha1 == null || sha1.isBlank()) {
            confirmUnsigned(java.nio.file.Path.of(relative), mirrors.resolve(url).isEmpty()
                    ? null : mirrors.resolve(url).get(0));
        }
        tasks.add(new DownloadTask(mirrors.resolve(url),
                com.brisklauncher.mcl.security.PathSafety.resolveUnder(librariesDir, relative), sha1, size));
    }

    // ---------- assets ----------

    private void downloadAssets(GameInstance entry, JsonNode merged, Consumer<InstallProgress> progress) {
        JsonNode indexNode = merged.path("assetIndex");
        String indexId = com.brisklauncher.mcl.security.PathSafety.requireId(
                indexNode.path("id").asText(""));
        String indexUrl = indexNode.path("url").asText("");
        if (indexId.isEmpty()) {
            progress.accept(new InstallProgress(InstallProgress.Stage.ASSETS, 0.55, ""));
            return;
        }

        Path assetsDir = entry.root().assetsDir();
        Path indexFile = assetsDir.resolve("indexes").resolve(indexId + ".json");
        JsonNode index = null;
        if (Files.isRegularFile(indexFile)) {
            try {
                index = mapper.readTree(indexFile.toFile());
            } catch (IOException e) {
                SafeLog.warn("资源索引损坏，重新下载：" + indexId);
            }
        }
        if (index == null) {
            if (indexUrl.isEmpty()) {
                progress.accept(new InstallProgress(InstallProgress.Stage.ASSETS, 0.55, ""));
                return;
            }
            try {
                byte[] bytes = Http.getBytesAny(mirrors.resolve(indexUrl), Duration.ofSeconds(60));
                index = mapper.readTree(bytes);
                Files.createDirectories(indexFile.getParent());
                Path temp = Files.createTempFile(indexFile.getParent(), "index-", ".tmp");
                Files.write(temp, bytes);
                Files.move(temp, indexFile, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                throw new InstallException("下载资源索引失败：" + Http.describe(e), e);
            }
        }

        List<DownloadTask> tasks = new ArrayList<>();
        JsonNode objects = index.path("objects");
        Iterator<Map.Entry<String, JsonNode>> fields = objects.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry2 = fields.next();
            String hash = entry2.getValue().path("hash").asText("");
            long size = entry2.getValue().path("size").asLong(-1);
            hash = com.brisklauncher.mcl.security.PathSafety.requireHash(hash);
            String prefix = hash.substring(0, 2);
            Path target = com.brisklauncher.mcl.security.PathSafety.resolveUnder(
                    assetsDir.resolve("objects"), prefix + "/" + hash);
            tasks.add(new DownloadTask(
                    mirrors.resolve(MOJANG_RESOURCES + prefix + "/" + hash), target, hash, size));
        }
        phase(tasks, InstallProgress.Stage.ASSETS, 0.55, 0.95, progress);

        prepareLegacyAssets(entry.root().path(), assetsDir, indexId, index);
    }

    /** 1.6 及更早：对象需要复制成“真实文件名”的目录结构，游戏才认得。 */
    private void prepareLegacyAssets(Path gameRoot, Path assetsDir, String indexId, JsonNode index) {
        boolean virtual = index.path("virtual").asBoolean(false);
        boolean mapToResources = index.path("map_to_resources").asBoolean(false);
        if (!virtual && !mapToResources) {
            return;
        }
        Path targetRoot = virtual
                ? assetsDir.resolve("virtual").resolve(com.brisklauncher.mcl.security.PathSafety.requireId(indexId))
                : gameRoot.resolve("resources");
        JsonNode objects = index.path("objects");
        int copied = 0;
        for (Iterator<Map.Entry<String, JsonNode>> it = objects.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            String name = entry.getKey();
            String hash = com.brisklauncher.mcl.security.PathSafety.requireHash(
                    entry.getValue().path("hash").asText(""));
            Path source = com.brisklauncher.mcl.security.PathSafety.resolveUnder(
                    assetsDir.resolve("objects"), hash.substring(0, 2) + "/" + hash);
            Path target = com.brisklauncher.mcl.security.PathSafety.resolveUnder(targetRoot, name);
            try {
                if (Files.isRegularFile(target)) {
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                copied++;
            } catch (IOException e) {
                SafeLog.warn("复制老版本资源失败：" + name);
            }
        }
        if (copied > 0) {
            SafeLog.info("已准备 " + copied + " 个老版本资源文件");
        }
    }

    // ---------- log4j 配置 ----------

    private void downloadLogging(GameInstance entry, JsonNode merged, Consumer<InstallProgress> progress) {
        JsonNode client = merged.path("logging").path("client");
        JsonNode file = client.path("file");
        String url = file.path("url").asText("");
        String id = file.path("id").asText("");
        if (url.isEmpty() || id.isEmpty()) {
            progress.accept(new InstallProgress(InstallProgress.Stage.LOGGING, 0.97, ""));
            return;
        }
        Path target = entry.root().assetsDir().resolve("log_configs").resolve(
                com.brisklauncher.mcl.security.PathSafety.requirePathSegment(id));
        DownloadTask task = new DownloadTask(mirrors.resolve(url), target,
                file.path("sha1").asText(null), file.path("size").asLong(-1));
        phase(List.of(task), InstallProgress.Stage.LOGGING, 0.95, 0.98, progress);
    }

    // ---------- 进度桥接 ----------

    private void phase(List<DownloadTask> tasks, InstallProgress.Stage stage,
                       double from, double to, Consumer<InstallProgress> progress) {
        SafeLog.info("[install] 阶段 " + stage + " 任务数=" + tasks.size());
        if (tasks.isEmpty()) {
            progress.accept(new InstallProgress(stage, to, ""));
            return;
        }
        long startTime = System.currentTimeMillis();
        // 速度用「滑动窗口 EMA」，不要用「总字节 / 总耗时」：
        // 后者在批量下载时必然越算越低（早期小文件很快、后面大文件拖长时间），
        // 玩家会以为下载在变慢，其实只是平均值在下降。
        long[] prevBytes = {0};
        long[] prevAt = {startTime};
        double[] ema = {0};
        downloader.downloadAll(tasks, snapshot -> {
            long now = System.currentTimeMillis();
            long bytes = snapshot.bytesDone();
            long deltaBytes = Math.max(0, bytes - prevBytes[0]);
            long deltaMs = now - prevAt[0];
            prevBytes[0] = bytes;
            prevAt[0] = now;
            double instant = deltaMs > 0 ? deltaBytes / 1048576.0 / (deltaMs / 1000.0) : 0;
            if (deltaMs > 0 && instant > 0) {
                // 0.7/0.3 的 EMA：既平滑又能跟上真实变化
                ema[0] = ema[0] > 0 ? ema[0] * 0.7 + instant * 0.3 : instant;
            }
            double speed = ema[0];
            String speedText = speed > 0.02
                    ? String.format(java.util.Locale.ROOT, "%.1f MB/s", speed) : "";
            // 已下载 / 总量（字节）
            long done = snapshot.bytesDone();
            long totalBytes = snapshot.bytesTotal();
            String bytesText = totalBytes > 0
                    ? String.format(java.util.Locale.ROOT, "%.1f / %.1f MB",
                            done / 1048576.0, totalBytes / 1048576.0)
                    : "";
            // 剩余时间：拿不到总量或速度太小时不显示，避免乱跳
            String etaText = "";
            if (totalBytes > done && speed > 0.05) {
                long seconds = Math.max(1, Math.round((totalBytes - done) / 1048576.0 / speed));
                String human = seconds >= 60
                        ? (seconds / 60) + "m " + (seconds % 60) + "s"
                        : seconds + "s";
                etaText = com.brisklauncher.mcl.i18n.I18n.t("task.eta", human);
            }
            java.util.List<String> parts = new java.util.ArrayList<>();
            String detail = detailOf(snapshot);
            if (!detail.isEmpty()) {
                parts.add(detail);
            }
            if (!bytesText.isEmpty()) {
                parts.add(bytesText);
            }
            if (!speedText.isEmpty()) {
                parts.add(speedText);
            }
            if (!etaText.isEmpty()) {
                parts.add(etaText);
            }
            progress.accept(new InstallProgress(stage,
                    from + (to - from) * snapshot.ratio(), String.join(" · ", parts)));
        });
        progress.accept(new InstallProgress(stage, to, String.valueOf(tasks.size())));
    }

    private static String detailOf(DownloadProgress snapshot) {
        if (snapshot.total() <= 0) {
            return "";
        }
        String file = snapshot.currentFile();
        String host = snapshot.host() == null || snapshot.host().isBlank() ? "" : "[" + snapshot.host() + "] ";
        String base = snapshot.completed() + "/" + snapshot.total();
        return file == null || file.isBlank() ? base : base + " · " + host + file;
    }

    // ---------- 工具 ----------

    private void writeJson(Path file, JsonNode node) throws IOException {
        byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(node);
        Path temp = Files.createTempFile(file.getParent(), "version-", ".tmp");
        Files.write(temp, bytes);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 版本 JSON 是否被手工改坏（启动前快速校验用）。 */
    public boolean hasValidJson(GameInstance entry) {
        try {
            if (!Files.isRegularFile(entry.jsonFile())) {
                return false;
            }
            return mapper.readTree(entry.jsonFile().toFile()).isObject();
        } catch (IOException e) {
            return false;
        }
    }

    /** 判断某个 URI 是否已经在本地（安装器用于跳过重复下载）。 */
    public static boolean exists(Path file, String sha1, long size) {
        return HashUtils.matches(file, sha1, size);
    }

    public static URI uri(String url) {
        return URI.create(url);
    }
}
