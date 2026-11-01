package com.brisklauncher.mcl.game;

import com.brisklauncher.mcl.game.install.VersionInstaller;
import com.brisklauncher.mcl.game.install.InstallException;
import com.brisklauncher.mcl.game.install.InstallProgress;
import com.brisklauncher.mcl.game.install.ModLoaderInstaller;
import com.brisklauncher.mcl.download.remote.LoaderMetaClient;
import com.brisklauncher.mcl.download.remote.MojangApi;
import com.brisklauncher.mcl.security.SafeLog;
import com.brisklauncher.mcl.setting.LauncherSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 版本服务门面：UI 只跟这一层打交道，不直接拼路径、不直接发 HTTP。
 * 对应开发方案 2.1 的“应用服务层 · GameRepository”。
 */
public final class GameRepository {

    private final GameDirectoryStore rootStore;
    private final GameScanner scanner;
    private final VersionInstaller installer;
    private final ModLoaderInstaller loaderInstaller;
    private final InstanceSettingsStore settingsStore;
    private final MojangApi mojang;
    private final Supplier<LauncherSettings> settingsSupplier;
    /** 這個版本是不是正在跑（由 MclContext 提供，避免遊戲層依賴啟動器層）。 */
    private final java.util.function.Predicate<String> runningCheck;

    public GameRepository(GameDirectoryStore rootStore, GameScanner scanner,
                          VersionInstaller installer, ModLoaderInstaller loaderInstaller,
                          InstanceSettingsStore settingsStore, MojangApi mojang,
                          Supplier<LauncherSettings> settingsSupplier) {
        this(rootStore, scanner, installer, loaderInstaller, settingsStore, mojang,
                settingsSupplier, versionId -> false);
    }

    public GameRepository(GameDirectoryStore rootStore, GameScanner scanner,
                          VersionInstaller installer, ModLoaderInstaller loaderInstaller,
                          InstanceSettingsStore settingsStore, MojangApi mojang,
                          Supplier<LauncherSettings> settingsSupplier,
                          java.util.function.Predicate<String> runningCheck) {
        this.rootStore = rootStore;
        this.scanner = scanner;
        this.installer = installer;
        this.loaderInstaller = loaderInstaller;
        this.settingsStore = settingsStore;
        this.mojang = mojang;
        this.settingsSupplier = settingsSupplier;
        this.runningCheck = runningCheck == null ? versionId -> false : runningCheck;
    }

    // ---------- 版本文件夹 ----------

    public List<GameDirectory> roots() {
        return rootStore.load();
    }

    public void addRoot(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        List<GameDirectory> roots = new ArrayList<>(rootStore.load());
        if (roots.stream().anyMatch(root -> root.path().equals(normalized))) {
            return;
        }
        roots.add(new GameDirectory(normalized, null));
        rootStore.save(roots);
    }

    public void removeRoot(GameDirectory root) {
        List<GameDirectory> roots = new ArrayList<>(rootStore.load());
        roots.removeIf(item -> item.path().equals(root.path()));
        rootStore.save(roots);
    }

    /** 首次使用时自动发现官方启动器目录（参考 HMCL 的自动扫描）。 */
    public void ensureDefaultRoot() {
        if (!rootStore.load().isEmpty()) {
            return;
        }
        String appData = System.getenv("APPDATA");
        if (appData != null) {
            Path official = Path.of(appData, ".minecraft");
            if (official.toFile().isDirectory()) {
                addRoot(official);
            }
        }
    }

    // ---------- 实例 ----------

    public List<GameInstance> versions(GameDirectory root) {
        return scanner.scan(root);
    }

    public Optional<GameInstance> find(GameDirectory root, String id) {
        return scanner.scanSingle(root, id);
    }

    /**
     * 安装原版。若同 id 已存在且文件完整，直接返回，不重复下载。
     */
    public GameInstance installVanilla(GameDirectory root, String mcVersion,
                                       Consumer<InstallProgress> progress) {
        Optional<GameInstance> existing = scanner.scanSingle(root, mcVersion);
        if (existing.isPresent()) {
            GameInstance entry = existing.get();
            // PCL 的做法：已存在的版本只补缺失文件，**不重写 version JSON**。
            // 重写 JSON 会把加载器信息（Fabric/Forge 的 libraries、mainClass）冲掉。
            if (installer.hasValidJson(entry) && !installer.looksComplete(entry)) {
                installer.repair(entry, progress);
            }
            ensureDefaultSettings(entry);
            return entry;
        }
        MojangApi.VersionMeta meta = findMeta(mcVersion);
        GameInstance installed = installer.install(root, meta, progress);
        ensureDefaultSettings(installed);
        return installed;
    }

    /**
     * 新安装的实例默认开启版本隔离（模组/存档落在 {@code versions/<id>}）。
     *
     * <p>已有 instance.json 不动，避免覆盖用户的选择；这是“新装版本”的默认值，
     * 与 PCL/HMCL 的行为一致。
     */
    private void ensureDefaultSettings(GameInstance entry) {
        try {
            InstanceSettings defaults = InstanceSettings.defaults(
                    settingsSupplier.get().defaultMemoryMb(), true);
            boolean fresh = !java.nio.file.Files.isRegularFile(settingsStore.fileFor(entry));
            if (fresh) {
                entry.isolated(true);
                settingsStore.save(entry, defaults);
                // 第一次安装的实例：把游戏语言预设成简体中文（已有 options.txt 语言设定则不动）
                GameOptions.ensureSimplifiedChinese(entry);
            }
            entry.isolated(true);
            // 版本隔离的实例要有完整的目录骨架（mods/saves/logs/natives…），
            // 否则下载完只有 jar+json，装模组时连 mods 都找不到（PCL/HMCL 都会建）。
            ensureInstanceLayout(entry);
        } catch (Exception error) {
            SafeLog.warn("写入实例默认设置失败（不影响启动）");
        }
    }

    /** 建立隔离实例的标准目录骨架。 */
    private void ensureInstanceLayout(GameInstance entry) {
        Path gameDir = entry.gameDir();
        for (String name : List.of("mods", "resourcepacks", "shaderpacks", "saves",
                "logs", "natives", "config", "crash-reports")) {
            try {
                java.nio.file.Files.createDirectories(gameDir.resolve(name));
            } catch (Exception error) {
                SafeLog.warn("创建实例目录失败：" + name);
            }
        }
        LoaderInfo loader = entry.loader();
        if (loader != null && (loader.type() == LoaderInfo.LoaderType.FABRIC
                || loader.type() == LoaderInfo.LoaderType.QUILT)) {
            try {
                java.nio.file.Files.createDirectories(gameDir.resolve(".fabric"));
            } catch (Exception ignored) {
                // .fabric 只是 Fabric 的缓存目录，建不出来不影响启动
            }
        }
    }

    /** 先查缓存清单；找不到再强制刷新一次（新快照可能比缓存新）。 */
    private MojangApi.VersionMeta findMeta(String mcVersion) {
        Optional<MojangApi.VersionMeta> meta = mojang.versions().stream()
                .filter(version -> version.id().equals(mcVersion))
                .findFirst();
        if (meta.isEmpty()) {
            meta = mojang.versions(true).stream()
                    .filter(version -> version.id().equals(mcVersion))
                    .findFirst();
        }
        return meta.orElseThrow(() -> new InstallException("版本清单中没有 " + mcVersion));
    }

    public GameInstance installLoader(GameDirectory root, LoaderInfo.LoaderType type,
                                      String mcVersion, String loaderVersion,
                                      Consumer<InstallProgress> progress) {
        if (type == null || type == LoaderInfo.LoaderType.VANILLA) {
            return installVanilla(root, mcVersion, progress);
        }
        if (loaderVersion == null || loaderVersion.isBlank()) {
            throw new InstallException("请选择一个 " + type.displayName() + " 版本");
        }
        GameInstance installed = switch (type) {
            case FABRIC -> loaderInstaller.installFabric(root, mcVersion, loaderVersion, progress);
            case QUILT -> throw new InstallException("已不再支援 Quilt，请改用 Fabric");
            case FORGE -> loaderInstaller.installForge(root, mcVersion, loaderVersion, progress);
            case NEOFORGE -> loaderInstaller.installNeoForge(root, mcVersion, loaderVersion, progress);
            case OPTIFINE -> loaderInstaller.installOptiFine(root, mcVersion, loaderVersion, progress);
            case VANILLA -> installVanilla(root, mcVersion, progress);
        };
        ensureDefaultSettings(installed);
        // 记录加载器，之后「校验并补全」能按记录重装（PCL 的做法）
        LoaderRecord.save(installed, new LoaderInfo(type, loaderVersion));
        return installed;
    }

    public List<LoaderMetaClient.LoaderVersion> availableLoaders(LoaderInfo.LoaderType type, String mcVersion) {
        return loaderInstaller.availableLoaders(type, mcVersion);
    }

    /** 补全/校验文件（修复残缺实例）。 */
    public void repair(GameInstance entry, Consumer<InstallProgress> progress) {
        // 之前被旧版「校验并补全」抹掉加载器信息的情况：按 loader.json 记录重新装回来
        LoaderInfo recorded = LoaderRecord.load(entry);
        boolean vanillaNow = entry.loader() == null || entry.loader().isVanilla();
        if (recorded != null && vanillaNow) {
            SafeLog.info("实例记录过加载器 " + recorded.type().name() + "，补全时重新安装");
            try {
                LoaderInfo.LoaderType type = recorded.type();
                String version = recorded.version();
                switch (type) {
                    case FABRIC -> loaderInstaller.installFabric(entry.root(), entry.mcVersion(), version, progress);
                    case QUILT -> { /* 已移除 Quilt 支援 */ }
                    case FORGE -> loaderInstaller.installForge(entry.root(), entry.mcVersion(), version, progress);
                    case NEOFORGE -> loaderInstaller.installNeoForge(entry.root(), entry.mcVersion(), version, progress);
                    default -> {
                        // OPTIFINE 需要手动安装
                    }
                }
                // 重装后实例对象可能过期，重新读一次
                entry = scanner.scanSingle(entry.root(), entry.id()).orElse(entry);
            } catch (Exception error) {
                SafeLog.warn("补全时重装加载器失败：" + error.getClass().getSimpleName());
            }
        }
        installer.repair(entry, progress);
    }

    public boolean looksComplete(GameInstance entry) {
        return installer.looksComplete(entry);
    }

    /** 缺失文件清单（最多 12 条），用于诊断“需要补全”到底缺什么。 */
    public java.util.List<String> missingFiles(GameInstance entry) {
        return installer.missingFiles(entry);
    }

    public boolean hasValidJson(GameInstance entry) {
        return installer.hasValidJson(entry);
    }

    /**
     * 删除实例；调用方必须先做二次确认与存档提示。
     *
     * <p>正在运行的实例直接拒绝：游戏进程占着 jar/日志，删到一半会「提示删除失败，
     * 但列表里已经消失」（版本 JSON 被删掉→扫描不到，剩下的文件还锁着）。
     * 与 PCL / HMCL 一致：先关游戏再删版本。
     */
    public void delete(GameInstance entry) {
        if (runningCheck.test(entry.id())) {
            throw new InstallException(com.brisklauncher.mcl.i18n.I18n.t(
                    "version.delete.running", entry.id()));
        }
        loaderInstaller.deleteInstance(entry);
    }

    /**
     * 重命名实例（版本文件夹、版本 JSON、客户端 jar、实例设置一起改）。
     *
     * <p>回传的 {@link GameInstance} 是**同一个物件**，但它的 {@code id} 与 {@code versionDir}
     * 都已更新成新路径；呼叫端不需要重新扫描（之前 versionDir 是 final，改名后还指著旧资料夹）。
     *
     * @return 改名后的实例
     */
    public GameInstance rename(GameInstance entry, String newId) {
        String id = newId == null ? "" : newId.trim();
        if (id.isBlank() || id.equals(entry.id())) {
            return entry;
        }
        // 路径安全：挡掉 / \ .. 磁碟符与 Windows 保留字元（版本名允许空白，例如「1.21.1-Fabric 0.19.5」）
        id = com.brisklauncher.mcl.security.PathSafety.requirePathSegment(id);
        Path versionsDir = entry.root().versionsDir();
        Path from = entry.versionDir() != null ? entry.versionDir() : versionsDir.resolve(entry.id());
        if (!Files.isDirectory(from)) {
            // 旧物件（例如改名后又被拿来改名）：回到「以目前 id 找资料夹」再试一次
            from = versionsDir.resolve(entry.id());
        }
        if (!Files.isDirectory(from)) {
            throw new IllegalStateException("找不到版本文件夹：" + entry.id());
        }
        Path to = versionsDir.resolve(id);
        if (Files.exists(to) && !to.toAbsolutePath().normalize()
                .equals(from.toAbsolutePath().normalize())) {
            throw new IllegalStateException("同名版本已存在：" + id);
        }
        if (to.toAbsolutePath().normalize().equals(from.toAbsolutePath().normalize())) {
            return entry;
        }
        try {
            Files.move(from, to);
            Path oldJson = to.resolve(entry.id() + ".json");
            Path newJson = to.resolve(id + ".json");
            if (Files.isRegularFile(oldJson)) {
                Files.move(oldJson, newJson);
            }
            Path oldJar = to.resolve(entry.id() + ".jar");
            Path newJar = to.resolve(id + ".jar");
            if (Files.isRegularFile(oldJar)) {
                Files.move(oldJar, newJar);
            }
            if (Files.isRegularFile(newJson)) {
                com.fasterxml.jackson.databind.node.ObjectNode node =
                        (com.fasterxml.jackson.databind.node.ObjectNode) com.brisklauncher.mcl.net.Http.mapper()
                                .readTree(Files.readString(newJson));
                node.put("id", id);
                Files.writeString(newJson, com.brisklauncher.mcl.net.Http.mapper()
                        .writerWithDefaultPrettyPrinter().writeValueAsString(node));
            }
            // 实例设置跟着换名（旧文件直接删掉，避免残留）
            Path oldSettings = settingsStore.fileFor(entry);
            InstanceSettings settings = settingsStore.load(entry, settingsSupplier.get().defaultMemoryMb());
            String oldId = entry.id();
            entry.id(id);
            entry.versionDir(to);
            settingsStore.save(entry, settings);
            Files.deleteIfExists(oldSettings);
            com.brisklauncher.mcl.security.SafeLog.info("[rename] " + oldId + " → " + id);
            return entry;
        } catch (Exception error) {
            throw new IllegalStateException("重命名失败：" + error.getMessage(), error);
        }
    }

    public boolean hasSaves(GameInstance entry) {
        return loaderInstaller.hasSaves(entry);
    }

    // ---------- 实例设置 ----------

    public InstanceSettings settingsFor(GameInstance entry) {
        LauncherSettings global = settingsSupplier.get();
        InstanceSettings settings = settingsStore.load(entry, global.defaultMemoryMb());
        entry.isolated(settings.isolated());
        return settings;
    }

    public void saveSettings(GameInstance entry, InstanceSettings settings) {
        entry.isolated(true);
        settingsStore.save(entry, settings.isolated() ? settings : new InstanceSettings(
                settings.memoryMb(), settings.javaPath(), settings.jvmArgs(), true,
                settings.width(), settings.height()));
    }

    public InstanceSettingsStore settingsStore() {
        return settingsStore;
    }
}
