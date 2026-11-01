package com.brisklauncher.mcl;

import com.brisklauncher.mcl.security.SafeLog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;

/**
 * 启动器数据根目录。
 *
 * <p>优先级：
 * <ol>
 *   <li>{@code -Dmcl.home=<path>}（测试/高级用户）；</li>
 *   <li>jlink / jpackage 应用镜像旁的 {@code data/}（便携模式，避免往 C 盘写）；</li>
 *   <li>{@code %USERPROFILE%\BriskLauncher}（旧行为，兼容已有数据）。</li>
 * </ol>
 * 首次使用便携目录时，会把旧位置的小体积配置（accounts / versionroots / ui-state /
 * launcher.json / skin）复制过来，缓存与 jdk 不复制，避免意外占空间。
 */
public final class AppPaths {

    private static final String APP_NAME = "BriskLauncher";
    private static volatile Path cachedRoot;

    private AppPaths() {
    }

    public static Path launcherRoot() {
        Path root = cachedRoot;
        if (root != null) {
            return root;
        }
        synchronized (AppPaths.class) {
            if (cachedRoot == null) {
                cachedRoot = resolveRoot();
            }
            return cachedRoot;
        }
    }

    private static Path resolveRoot() {
        String home = System.getProperty("mcl.home");
        if (home != null && !home.isBlank()) {
            return normalize(Paths.get(home));
        }
        // 安全审计 M6：帐号 / launcher.json / secret.key **永远**在 %LOCALAPPDATA%，
        // 不再跟安装目录（U 盘、公共目录）绑在一起。
        Path privateRoot = privateRoot();
        migratePortableSecrets(privateRoot);
        com.brisklauncher.mcl.security.FilePerms.ensurePrivate(privateRoot);
        return privateRoot;
    }

    /** 遷移後重置安全相關設定（P1-4）。 */
    private static void resetSecuritySettings() {
        try {
            com.brisklauncher.mcl.setting.SettingsStore store =
                    new com.brisklauncher.mcl.setting.SettingsStore();
            var current = store.load();
            store.save(current.withInsecureTls(false).withMicrosoftClientId(""));
            com.brisklauncher.mcl.security.SafeLog.info("迁移后已重置安全相关设定");
        } catch (Exception error) {
            com.brisklauncher.mcl.security.SafeLog.warn("重置安全设定失败："
                    + error.getClass().getSimpleName());
        }
    }

    /** 私密數據目錄（%LOCALAPPDATA%\BriskLauncher）。 */
    private static Path privateRoot() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return normalize(Paths.get(localAppData, APP_NAME));
        }
        return normalize(Paths.get(System.getProperty("user.home", "."), APP_NAME));
    }

    /** 舊便携模式把 accounts/launcher.json 放在安裝目錄 → 一次性搬到私密目錄。 */
    private static void migratePortableSecrets(Path privateRoot) {
        Path portable = portableRoot();
        if (portable == null) {
            return;
        }
        try {
            if (portable.toAbsolutePath().normalize().equals(privateRoot)) {
                return;
            }
        } catch (Exception ignored) {
            return;
        }
        for (String name : new String[]{"accounts.json", "launcher.json", "secret.key"}) {
            Path from = portable.resolve(name);
            Path to = privateRoot.resolve(name);
            if (!java.nio.file.Files.isRegularFile(from) || java.nio.file.Files.exists(to)) {
                continue;
            }
            try {
                java.nio.file.Files.createDirectories(privateRoot);
                java.nio.file.Files.copy(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                // 安全审计 L-02：注释与日志都说「搬移」，旧实现只复制不删，
                // 结果旧目录（可能在 U 盘/公共目录）长期残留 accounts.json / secret.key。
                try {
                    java.nio.file.Files.deleteIfExists(from);
                    com.brisklauncher.mcl.security.SafeLog.info("已把 " + name + " 复制到私密目录并删除源文件");
                } catch (Exception deleteError) {
                    com.brisklauncher.mcl.security.SafeLog.warn("已复制 " + name
                            + "，但删除旧文件失败，请手动删除：" + from);
                }
                if ("launcher.json".equals(name)) {
                    resetSecuritySettings();
                }
            } catch (Exception error) {
                com.brisklauncher.mcl.security.SafeLog.warn("搬移 " + name + " 失败："
                        + error.getClass().getSimpleName());
            }
        }
    }

    private static Path normalize(Path path) {
        try {
            Files.createDirectories(path);
        } catch (Exception ignored) {
            // 交给上层在真正写入时报错
        }
        return path.toAbsolutePath().normalize();
    }

    /** 应用镜像目录（mcl.bat / jpackage exe 所在目录）；普通 mvn 运行为 null。 */
    public static Path appDir() {
        return detectAppDir();
    }

    /**
     * 「可搬移的大档案」统一放这里：启动器目录下的 {@code data/}。
     *
     * <p>原则：帐号（accounts.json）、实例设定、launcher.json、**密钥、确认记录、日志** 这些小而私密的
     * 留在 {@link #launcherRoot()}（%LOCALAPPDATA%）；快取、原生解压、联机程式、下载的 JDK、
     * 语言包、主题包都放到这里，避免把 C 盘塞满。
     *
     * <p>安全审计 H-02：{@code data/} 通常位于安装目录（U 盘 / 公共目录 / 共享文件夹），
     * 任何人都能写；而 {@code plugins/*.jar} 又会被加载执行 —— 所以这里也要加固，
     * 并且密钥 / 确认记录 / 日志一律不放这儿。
     */
    public static Path dataDir() {
        Path app = detectAppDir();
        Path base = app != null ? app
                : Paths.get(System.getProperty("user.dir", "."));
        return normalize(base.resolve("data"));
    }

    /** 私密小文件：密钥（仅当前用户可读）。 */
    public static Path secretFile() {
        return launcherRoot().resolve("secret.key");
    }

    /** 私密小文件：风险确认记录（放在私密目录后才具备「一次确认长期有效」的语义）。 */
    public static Path acksFile() {
        return launcherRoot().resolve("acks.json");
    }

    /** 日志目录：含玩家名与服务器地址，放私密目录。 */
    public static Path logsDir() {
        return launcherRoot().resolve("logs");
    }

    /**
     * 加固「会被执行或含私密内容」的目录（安全审计 H-02）。
     *
     * <p>{@code data/} 本身、{@code data/plugins}（会被加载执行）、{@code data/native}（DLL 释放点）。
     * 在 FAT32/exFAT 的便携 U 盘上 ACL 设置必然失败 —— 这时会记录告警，
     * 由 {@link #privateDirsHardened()} 让上层决定是否提示用户。
     */
    public static void hardenDataDirs() {
        java.util.List<Path> targets = java.util.List.of(
                dataDir(), dataDir().resolve("plugins"), dataDir().resolve("native"));
        // 放到背景执行：icacls 每次要 spawn 一个进程（100~300ms），
        // 在 MclContext 构造函数里同步做完会让启动卡好几秒（实测）。
        // 加固晚 1 秒没有安全损失，启动感受却是天差地远。
        Thread.ofVirtual().name("mcl-harden").start(() -> {
            boolean all = true;
            for (Path target : targets) {
                try {
                    all &= com.brisklauncher.mcl.security.FilePerms.ensurePrivate(target);
                } catch (Throwable error) {
                    all = false;
                }
            }
            dirsHardened = all;
            if (!all) {
                SafeLog.warn("数据目录无法设置为仅当前用户可访问（便携盘/公共目录？）");
            }
        });
    }

    private static volatile boolean dirsHardened = true;

    /** 私密/数据目录是否都成功加固（false 时 UI 应提示「不建议在公共电脑使用」）。 */
    public static boolean privateDirsHardened() {
        return dirsHardened;
    }

    /** 把舊位置（launcherRoot 下）的大目錄搬到 data/，只搬一次。 */
    public static void migrateHeavyDirs() {
        // 注意：logs 不在这里 —— 安全审计 H-02/M-01 之后日志改放私密目录，
        // 反而要把旧的 data/logs 搬进 launcherRoot/logs（见 migrateOldLogs）。
        for (String name : new String[]{"cache", "native", "online", "jdk", "lang", "themes"}) {
            Path from = launcherRoot().resolve(name);
            Path to = dataDir().resolve(name);
            if (!Files.isDirectory(from) || Files.exists(to)) {
                continue;
            }
            try {
                Files.createDirectories(to.getParent());
                Files.move(from, to);
                SafeLog.info("已搬移资料目录：" + name + " → " + to);
            } catch (Exception error) {
                SafeLog.warn("搬移 " + name + " 失败（将继续使用旧位置）：" + error.getClass().getSimpleName());
            }
        }
    }

    /**
     * 把旧版本留在 {@code data/logs} 的日志搬进私密目录 {@code launcherRoot/logs}。
     *
     * <p>安全审计 H-02/M-01：日志含玩家名、实例路径、服务器地址，不该待在人人可写的安装目录。
     */
    public static void migrateOldLogs() {
        Path legacy = dataDir().resolve("logs");
        Path target = logsDir();
        if (!Files.isDirectory(legacy)) {
            return;
        }
        try {
            Files.createDirectories(target);
            try (var stream = Files.list(legacy)) {
                for (Path file : stream.toList()) {
                    Path destination = target.resolve(file.getFileName().toString());
                    if (Files.isRegularFile(file) && !Files.exists(destination)) {
                        Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            com.brisklauncher.mcl.security.FilePerms.ensurePrivate(target);
            SafeLog.info("已把旧日志复制到私密目录：" + target);
        } catch (Exception error) {
            SafeLog.warn("迁移旧日志失败：" + error.getClass().getSimpleName());
        }
    }

    /**
     * 清掉遗留的临时文件（{@code data/cache/*.tmp} 之类）。
     *
     * <p>为什么会有：{@code Files.createTempFile} + {@code Files.move(REPLACE_EXISTING)} 在 Windows 上
     * 如果目标正被别的句柄占用，move 会失败而临时文件就留下来了（实测攒了 91 个、24MB）。
     * 这里在启动时（背景执行）清理一小时以前的临时文件。
     */
    public static void cleanStaleTempFiles() {
        Thread.ofVirtual().name("mcl-temp-clean").start(() -> {
            long cutoff = System.currentTimeMillis() - 3600_000L;
            int removed = 0;
            long freed = 0;
            for (Path dir : java.util.List.of(dataDir(), dataDir().resolve("cache"))) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                try (var stream = Files.list(dir)) {
                    for (Path file : stream.toList()) {
                        String name = file.getFileName().toString();
                        if (!name.endsWith(".tmp") || !Files.isRegularFile(file)) {
                            continue;
                        }
                        try {
                            if (Files.getLastModifiedTime(file).toMillis() > cutoff) {
                                continue;
                            }
                            long size = Files.size(file);
                            if (Files.deleteIfExists(file)) {
                                removed++;
                                freed += size;
                            }
                        } catch (Exception ignored) {
                            // 单个文件失败不影响其它
                        }
                    }
                } catch (Exception ignored) {
                    // 目录读不到就跳过
                }
            }
            if (removed > 0) {
                SafeLog.info("已清理 " + removed + " 个遗留临时文件（"
                        + (freed / 1024) + " KB）");
            }
        });
    }

    /** 应用镜像旁边的 data/；不可写时退回 LOCALAPPDATA。 */
    private static Path portableRoot() {
        Path appDir = detectAppDir();
        if (appDir != null) {
            Path data = appDir.resolve("data");
            if (isWritable(data)) {
                // 便携目录是全新的（没有 launcher.json / accounts.json），
                // 而用户以前的数据在 LOCALAPPDATA 里 → 继续用旧目录，
                // 否则会「读不到账号」。exe 版踩过这个坑。
                boolean fresh = !Files.isRegularFile(data.resolve("launcher.json"))
                        && !Files.isRegularFile(data.resolve("accounts.json"));
                String local = System.getenv("LOCALAPPDATA");
                if (fresh && local != null && !local.isBlank()) {
                    Path legacy = Paths.get(local, APP_NAME);
                    if (Files.isRegularFile(legacy.resolve("launcher.json"))
                            || Files.isRegularFile(legacy.resolve("accounts.json"))) {
                        return legacy.toAbsolutePath().normalize();
                    }
                }
                return data.toAbsolutePath().normalize();
            }
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            Path fallback = Paths.get(localAppData, APP_NAME);
            if (isWritable(fallback)) {
                return fallback.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private static Path detectAppDir() {
        try {
            String jpackagePath = System.getProperty("jpackage.app-path");
            if (jpackagePath != null && !jpackagePath.isBlank()) {
                Path exe = Paths.get(jpackagePath);
                return exe.getParent();
            }
            String command = ProcessHandle.current().info().command().orElse("");
            if (command.isBlank()) {
                return null;
            }
            Path java = Paths.get(command);
            Path bin = java.getParent();
            if (bin == null || !"bin".equalsIgnoreCase(String.valueOf(bin.getFileName()))) {
                return null;
            }
            Path candidate = bin.getParent();
            if (candidate == null) {
                return null;
            }
            // jlink/fx image 一定有 bin/mcl(.bat)；普通 JDK 没有
            if (Files.isRegularFile(candidate.resolve("bin").resolve("mcl.bat"))
                    || Files.isRegularFile(candidate.resolve("bin").resolve("mcl"))) {
                return candidate;
            }
        } catch (Exception ignored) {
            // 普通开发运行
        }
        return null;
    }

    private static boolean isWritable(Path dir) {
        try {
            Files.createDirectories(dir);
            Path probe = Files.createTempFile(dir, ".mcl-write", ".tmp");
            Files.deleteIfExists(probe);
            return true;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    /** 只迁移配置类小文件，不迁移 cache/jdk/logs。 */
    private static void migrateLegacyIfNeeded(Path target) {
        try {
            if (Files.exists(target.resolve("launcher.json"))) {
                return;
            }
            Path legacy = Paths.get(System.getProperty("user.home", "."), APP_NAME);
            if (!Files.isDirectory(legacy)) {
                return;
            }
            for (String name : List.of("launcher.json", "accounts.json",
                    "versionroots.json", "ui-state.json", "games.json")) {
                Path source = legacy.resolve(name);
                Path destination = target.resolve(name);
                if (Files.isRegularFile(source) && !Files.exists(destination)) {
                    Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Path legacySkin = legacy.resolve("skin");
            Path targetSkin = target.resolve("skin");
            if (Files.isDirectory(legacySkin) && !Files.exists(targetSkin)) {
                Files.createDirectories(targetSkin);
                try (var stream = Files.list(legacySkin)) {
                    for (Path file : stream.toList()) {
                        if (Files.isRegularFile(file)) {
                            Files.copy(file, targetSkin.resolve(file.getFileName()),
                                    StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            }
            SafeLog.info("已迁移旧配置到便携目录：" + target);
        } catch (Exception error) {
            SafeLog.warn("迁移旧配置失败（不影响启动）");
        }
    }

    public static Path instancesDir()   { return launcherRoot().resolve("instances"); }
    public static Path jdkDir()         { return dataDir().resolve("jdk"); }
    public static Path cacheDir()       { return dataDir().resolve("cache"); }
    public static Path themesDir()      { return dataDir().resolve("themes"); }
    public static Path launcherConfig() { return launcherRoot().resolve("launcher.json"); }
    public static Path onlineDir()      { return dataDir().resolve("online"); }
    public static Path easytierDir()    { return onlineDir().resolve("easytier"); }
}
