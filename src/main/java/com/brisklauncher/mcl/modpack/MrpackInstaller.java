package com.brisklauncher.mcl.modpack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.brisklauncher.mcl.game.LoaderInfo;
import com.brisklauncher.mcl.game.GameInstance;
import com.brisklauncher.mcl.game.GameDirectory;
import com.brisklauncher.mcl.game.GameScanner;
import com.brisklauncher.mcl.game.GameRepository;
import com.brisklauncher.mcl.game.InstanceSettings;
import com.brisklauncher.mcl.game.InstanceSettingsStore;
import com.brisklauncher.mcl.download.DownloadTask;
import com.brisklauncher.mcl.download.HttpDownloader;
import com.brisklauncher.mcl.download.MirrorResolver;
import com.brisklauncher.mcl.game.install.InstallException;
import com.brisklauncher.mcl.game.install.InstallProgress;
import com.brisklauncher.mcl.net.Http;
import com.brisklauncher.mcl.security.SafeLog;
import com.brisklauncher.mcl.setting.LauncherSettings;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * mrpack 导入：解析 → 装原版 → 装加载器 → 建实例 → 下载 files → 解压 overrides。
 *
 * <p>实例是“继承自加载器版本”的一个新版本目录，因此：
 * <ul>
 *   <li>整合包之间互不干扰（各自 mods/存档/配置）；</li>
 *   <li>共用同一份原版与加载器库，不重复占空间；</li>
 *   <li>删除整合包不会破坏原版安装。</li>
 * </ul>
 */
public final class MrpackInstaller {

    private final MrpackReader reader = new MrpackReader();
    private final GameRepository versions;
    private final GameScanner scanner = new GameScanner();
    private final HttpDownloader downloader;
    private final Supplier<LauncherSettings> settingsSupplier;
    private final InstanceSettingsStore settingsStore = new InstanceSettingsStore();
    private final ObjectMapper mapper = Http.mapper();

    public MrpackInstaller(GameRepository versions, HttpDownloader downloader,
                           Supplier<LauncherSettings> settingsSupplier) {
        this.versions = versions;
        this.downloader = downloader;
        this.settingsSupplier = settingsSupplier;
    }

    public ModpackIndex peek(Path mrpackFile) throws IOException {
        return reader.read(mrpackFile);
    }

    public GameInstance install(GameDirectory root, Path mrpackFile, Consumer<InstallProgress> progress) {
        return install(root, mrpackFile, null, progress);
    }

    /** @param customName 用户可编辑的实例名；为空时使用整合包自带名称。 */
    public GameInstance install(GameDirectory root, Path mrpackFile, String customName,
                                Consumer<InstallProgress> progress) {
        ModpackIndex index;
        try {
            index = reader.read(mrpackFile);
        } catch (IOException e) {
            // 直接抛 e 的话弹窗上只会看到一个路径（FileSystemException 的 message 就是档名），
            // 玩家根本不知道发生什么事。这里包一层看得懂的说明。
            throw new InstallException(com.brisklauncher.mcl.i18n.I18n.t(
                    "modpack.error.read_failed", mrpackFile.getFileName().toString(),
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
        }

        progress.accept(new InstallProgress(InstallProgress.Stage.RESOLVING, 0.01, index.displayName()));
        String mcVersion = index.mcVersion();
        // 有些包（包含本启动器旧版导出的）dependencies.minecraft 写的是「实例名」而不是版本号，
        // 直接拿去安装会变成 "找不到版本 自然之需YSM兼容整合"。这里先挡下来说清楚。
        // 注意：错误讯息一律走 I18n——写死在 Java 里会出现「简中界面弹出繁体字」。
        if (mcVersion == null || !mcVersion.matches("\\d+\\.\\d+(\\.\\d+)?")) {
            String raw = mcVersion == null ? "" : mcVersion.trim();
            throw new InstallException(raw.isBlank()
                    ? com.brisklauncher.mcl.i18n.I18n.t("modpack.error.mc_version_missing")
                    : com.brisklauncher.mcl.i18n.I18n.t("modpack.error.mc_version_invalid", raw));
        }
        LoaderInfo.LoaderType type = parseLoaderType(index.loaderType());

        // 记录原版版本资料夹是不是本来就有：只有「这次导入才装出来的」才能清掉，
        // 否则会把玩家原本的原版版本一起删掉（使用者回报的「导入后多出一个 1.21.11」）。
        boolean baseExisted = Files.isDirectory(root.versionsDir().resolve(mcVersion));

        GameInstance base;
        try {
            base = versions.installLoader(root, type, mcVersion, index.loaderVersion(),
                    snapshot -> progress.accept(new InstallProgress(InstallProgress.Stage.LOADER,
                            0.02 + snapshot.progress() * 0.6, snapshot.detail())));
        } catch (InstallException e) {
            throw e;
        } catch (Exception e) {
            throw new InstallException("安装整合包依赖失败：" + e.getMessage(), e);
        }

        // 默认实例名就用整合包显示名（它通常已含版本号，再拼一次会变成 26.3-1.0.0-1.0.0）
        String instanceId = uniqueId(root, sanitize(
                customName == null || customName.isBlank()
                        ? index.displayName()
                        : customName));
        GameInstance entry = createInstance(root, instanceId, base);
        // 让实例「自带」原版+加载器的版本资讯，导入完就不需要那个多出来的原版资料夹
        entry = makeSelfContained(root, entry, base);

        // 整合包实例默认开启隔离，全部内容写进自己的目录
        InstanceSettings settings = new InstanceSettings(
                settingsSupplier.get().defaultMemoryMb(), "", List.of(), true, 0, 0);
        settingsStore.save(entry, settings);
        entry.isolated(true);

        Path gameDir;
        try {
            gameDir = entry.gameDir();
            Files.createDirectories(gameDir.resolve("mods"));
        } catch (IOException e) {
            throw new InstallException("创建实例目录失败：" + e.getMessage(), e);
        }

        downloadFiles(index, gameDir, progress);
        extractOverrides(mrpackFile, gameDir, progress);
        // 整合包没带 options.txt 时补上简体中文；带了语言设定就尊重整合包
        com.brisklauncher.mcl.game.GameOptions.ensureSimplifiedChinese(entry);

        // 收尾：如果那个原版版本资料夹是「这次导入才装出来的」、而且没有其它实例在用它，
        // 就丢回收站——版本列表里只留整合包实例本身（与 PCL / HMCL 的导入结果一致）。
        if (!baseExisted) {
            dropBaseVersionIfUnused(root, mcVersion, instanceId);
        }

        progress.accept(new InstallProgress(InstallProgress.Stage.DONE, 1.0, instanceId));
        SafeLog.info("整合包导入完成：" + index.displayName());
        return entry;
    }

    // ---------- 实例 ----------

    private GameInstance createInstance(GameDirectory root, String instanceId, GameInstance base) {
        Path versionDir = root.versionsDir().resolve(instanceId);
        try {
            Files.createDirectories(versionDir);
            ObjectNode node = mapper.createObjectNode();
            node.put("id", instanceId);
            node.put("inheritsFrom", base.id());
            node.put("type", "release");
            // 用基础版本的发布日，而不是「导入时间」：否则新导入的旧版本会排到列表最前
            node.put("releaseTime", base.releaseTime().isBlank() ? Instant.now().toString() : base.releaseTime());
            if (!base.mainClass().isBlank()) {
                node.put("mainClass", base.mainClass());
            }
            node.putArray("libraries");

            Path json = versionDir.resolve(instanceId + ".json");
            Path temp = Files.createTempFile(versionDir, "instance-", ".tmp");
            Files.write(temp, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(node));
            Files.move(temp, json, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new InstallException("创建整合包实例失败：" + e.getMessage(), e);
        }
        return scanner.scanSingle(root, instanceId)
                .orElseThrow(() -> new InstallException("整合包实例创建后无法识别"));
    }

    /**
     * 把整合包实例变成「自足版本」：吃下原版/加载器版本的 JSON 与客户端 jar。
     *
     * <p>原本的做法是 {@code inheritsFrom} 指向 {@code versions/<mcVersion>}，于是导入后
     * 版本列表会同时出现「1.21.11」和「推荐模组包」两个资料夹（使用者回报）。
     * 这里把父版本的内容整并进实例自己的 JSON（去掉 inheritsFrom）并复制 client jar，
     * 之后那个原版资料夹就能安全清掉。
     */
    private GameInstance makeSelfContained(GameDirectory root, GameInstance entry, GameInstance base) {
        Path baseJson = base.jsonFile();
        Path baseDir = base.versionDir();
        Path instanceDir = entry.versionDir();
        try {
            if (!Files.isRegularFile(baseJson)) {
                return entry;
            }
            ObjectNode merged = (ObjectNode) mapper.readTree(baseJson.toFile());
            merged.put("id", entry.id());
            // 保留整合包实例原本的发布时间（排序用），其余（libraries/arguments/mainClass…）沿用父版本
            if (!entry.releaseTime().isBlank()) {
                merged.put("releaseTime", entry.releaseTime());
            }
            Path json = instanceDir.resolve(entry.id() + ".json");
            Path temp = Files.createTempFile(instanceDir, "instance-", ".tmp");
            Files.write(temp, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(merged));
            Files.move(temp, json, StandardCopyOption.REPLACE_EXISTING);

            // client jar 也要跟著搬过来，否则没有 inheritsFrom 之后就找不到主程序 jar
            Path baseJar = baseDir.resolve(base.id() + ".jar");
            Path instanceJar = instanceDir.resolve(entry.id() + ".jar");
            if (Files.isRegularFile(baseJar) && !Files.isRegularFile(instanceJar)) {
                Files.copy(baseJar, instanceJar, StandardCopyOption.REPLACE_EXISTING);
            }
            return scanner.scanSingle(root, entry.id()).orElse(entry);
        } catch (Exception error) {
            // 失败就退回原本的 inheritsFrom 形式（能跑，只是版本列表会多一个原版）
            SafeLog.warn("[modpack] 合并父版本失败（保留 inheritsFrom）："
                    + error.getClass().getSimpleName());
            return entry;
        }
    }

    /** 沒有其它實例引用這個原版版本時，把它丟回收站（導入整合包專用）。 */
    private void dropBaseVersionIfUnused(GameDirectory root, String baseId, String instanceId) {
        try {
            for (GameInstance other : scanner.scan(root)) {
                if (other.id().equals(instanceId) || other.id().equals(baseId)) {
                    continue;
                }
                Path json = other.jsonFile();
                if (!Files.isRegularFile(json)) {
                    continue;
                }
                String inherits = mapper.readTree(json.toFile()).path("inheritsFrom").asText("");
                if (baseId.equals(inherits)) {
                    SafeLog.info("[modpack] 原版版本 " + baseId + " 仍被 " + other.id() + " 使用，保留");
                    return;
                }
            }
            Path dir = root.versionsDir().resolve(baseId);
            if (Files.isDirectory(dir) && com.brisklauncher.mcl.util.RecycleBin.delete(dir)) {
                SafeLog.info("[modpack] 已把多出来的原版版本 " + baseId + " 丢进回收站");
            }
        } catch (Exception error) {
            SafeLog.warn("[modpack] 清理原版版本失败：" + error.getClass().getSimpleName());
        }
    }

    private String uniqueId(GameDirectory root, String base) {
        String id = base.isBlank() ? "modpack" : base;
        Path versionsDir = root.versionsDir();
        String candidate = id;
        int suffix = 2;
        while (Files.exists(versionsDir.resolve(candidate))) {
            candidate = id + "-" + suffix;
            suffix++;
            if (suffix > 999) {
                throw new InstallException("无法为整合包生成唯一实例名");
            }
        }
        return candidate;
    }

    private static String sanitize(String raw) {
        String text = raw == null ? "modpack" : raw.trim();
        text = text.replaceAll("[\\\\/:*?\"<>|]", "-")
                .replaceAll("\\s+", "-")
                .replaceAll("-{2,}", "-");
        if (text.length() > 48) {
            text = text.substring(0, 48);
        }
        return text.replaceAll("^-|-$", "");
    }

    private static LoaderInfo.LoaderType parseLoaderType(String raw) {
        if (raw == null) {
            return LoaderInfo.LoaderType.VANILLA;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "fabric" -> LoaderInfo.LoaderType.FABRIC;
            case "quilt" -> LoaderInfo.LoaderType.QUILT;
            case "forge" -> LoaderInfo.LoaderType.FORGE;
            case "neoforge" -> LoaderInfo.LoaderType.NEOFORGE;
            default -> LoaderInfo.LoaderType.VANILLA;
        };
    }

    // ---------- 文件 ----------

    private void downloadFiles(ModpackIndex index, Path gameDir, Consumer<InstallProgress> progress) {
        MirrorResolver mirrors = new MirrorResolver(settingsSupplier.get());
        List<DownloadTask> tasks = new ArrayList<>();
        for (ModpackIndex.FileEntry file : index.files()) {
            if (!file.clientSide() || file.downloads().isEmpty()) {
                continue;
            }
            Path target;
            try {
                target = MrpackPathValidator.resolveSafe(gameDir, file.path());
            } catch (SecurityException e) {
                throw new InstallException("整合包文件路径非法，已中止：" + e.getMessage(), e);
            }
            String sha1 = file.sha1() == null || file.sha1().isBlank() ? null : file.sha1();
            tasks.add(new DownloadTask(mirrors.resolve(file.downloads().get(0)), target, sha1, file.size()));
        }

        if (tasks.isEmpty()) {
            progress.accept(new InstallProgress(InstallProgress.Stage.ASSETS, 0.65, "0"));
            return;
        }
        try {
            downloader.downloadAll(tasks, snapshot -> progress.accept(new InstallProgress(
                    InstallProgress.Stage.ASSETS,
                    0.62 + snapshot.ratio() * 0.3,
                    snapshot.completed() + "/" + snapshot.total())));
        } catch (RuntimeException e) {
            throw new InstallException("整合包文件下载失败：" + e.getMessage(), e);
        }
    }

    /**
     * 这些路径由启动器生成（版本 JSON、客户端 jar、natives），
     * 整合包的 overrides 一律不准覆盖——否则 mainClass/libraries 就归它控制（见安全审计 M4）。
     */
    private static boolean isReserved(Path gameDir, String relative) {
        String id = gameDir.getFileName() == null ? "" : gameDir.getFileName().toString();
        String r = relative.replace('\\', '/');
        if (!id.isEmpty() && (r.equalsIgnoreCase(id + ".json") || r.equalsIgnoreCase(id + ".jar"))) {
            return true;
        }
        // 安全审计 v3 F-1：实例图标是「启动器渲染版本列表时解码的资产」，
        // 不能让整合包替换（否则一张特制 JPEG 就能让每次启动都 OOM）。
        for (String icon : new String[]{"icon.png", "icon.jpg", "icon.jpeg", "icon.webp",
                "icon.gif", "icon.bmp"}) {
            if (r.equalsIgnoreCase(icon)) {
                return true;
            }
        }
        return r.equalsIgnoreCase("natives") || r.startsWith("natives/")
                || r.equalsIgnoreCase(".mcl-natives");
    }

    private void extractOverrides(Path mrpackFile, Path gameDir, Consumer<InstallProgress> progress) {
        progress.accept(new InstallProgress(InstallProgress.Stage.FINISHING, 0.93, "overrides"));
        int count = 0;
        long extractedBytes = 0;
        try (ZipFile zip = new ZipFile(mrpackFile.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String relative = MrpackReader.stripOverridePrefix(entry.getName());
                if (relative == null || relative.isBlank()) {
                    continue;
                }
                // 安全审计 M4：整合包不能覆盖「启动器自己生成的」版本 JSON / natives
                if (isReserved(gameDir, relative)) {
                    continue;
                }
                try {
                    com.brisklauncher.mcl.security.InputBudget.safeArchiveEntryName(relative);
                } catch (SecurityException badName) {
                    throw new InstallException(badName.getMessage());
                }
                Path target = MrpackPathValidator.resolveSafe(gameDir, relative);
                // 安全审计 v2 A-10：解压总量也要有上限（zip 炸弹会把磁盘写满）
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(entry);
                     java.io.OutputStream out = Files.newOutputStream(target)) {
                    extractedBytes += com.brisklauncher.mcl.security.InputBudget.copyBounded(
                            in, out, com.brisklauncher.mcl.security.InputBudget.MAX_ARCHIVE_TOTAL
                                    - extractedBytes);
                } catch (IOException tooBig) {
                    try {
                        Files.deleteIfExists(target);
                    } catch (Exception ignored) {
                        // 尽力而为
                    }
                    throw tooBig;
                }
                count++;
            }
        } catch (IOException e) {
            throw new InstallException("解压整合包覆盖层失败：" + e.getMessage(), e);
        }
        progress.accept(new InstallProgress(InstallProgress.Stage.FINISHING, 0.99,
                String.valueOf(count)));
    }
}
