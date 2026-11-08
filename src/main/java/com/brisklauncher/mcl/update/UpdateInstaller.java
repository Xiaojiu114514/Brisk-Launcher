package com.brisklauncher.mcl.update;

import com.brisklauncher.mcl.AppPaths;
import com.brisklauncher.mcl.download.HttpDownloader;
import com.brisklauncher.mcl.game.install.InstallProgress;
import com.brisklauncher.mcl.security.PathSafety;
import com.brisklauncher.mcl.security.SafeLog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 启动器自动覆盖更新。
 *
 * <p>Windows 不允许执行中的程式替换自己的 exe / jar，所以流程拆成两段：
 * <ol>
 *   <li><b>本行程</b>：下载更新包（{@link UpdateService#download}，含 SHA-256 校验）→
 *       解压到 {@code <data>/updates/staging/<version>/}（防 zip-slip、限额）→
 *       写出 {@code apply-update.ps1} → 用 PowerShell 启动它 → 结束启动器；</li>
 *   <li><b>脚本</b>：等本行程结束 → 备份旧档 → 覆盖 → 镜像 {@code lib/}、{@code app/}
 *       （避免 module-path 上出现两个同名模组）→ 失败就回滚 → 重新启动启动器。</li>
 * </ol>
 *
 * <p>{@code data/} 永远不参与覆盖：那里放的是玩家资料、快取、JDK 与外挂。
 * 结果写在 {@code <data>/updates/last-result.txt}，下次启动由 {@link #consumeResult()} 取走。
 */
public final class UpdateInstaller {

    /** 更新包上限：解压后总量 3 GB、单档 512 MB、2 万个档案。 */
    private static final long MAX_ENTRY_BYTES = 512L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 3L * 1024 * 1024 * 1024;
    private static final int MAX_ENTRIES = 20000;
    /** 入口脚本 / lib 目录的辨识名（和 AppPaths.looksLikeLauncherDir 对齐）。 */
    private static final List<String> ENTRY_FILES = List.of(
            "launch.ps1", "Brisk Launcher.exe", "Brisk Launcher.vbs", "BriskLauncher.exe",
            "bin/brisk-launcher.bat", "bin/brisk-launcher");

    /** 已解压、等待覆盖的更新。 */
    public record Staged(String version, Path root, Path packageFile) {
    }

    /** 上一次自动更新的结果（由 PowerShell 脚本写出）。 */
    public record ApplyResult(boolean ok, String version, String message) {
    }

    /** 重新启动的方式：{@code ps1} / {@code vbs} 由脚本组参数，其它直接执行档案。 */
    public record RestartCommand(String kind, String file) {
    }

    private UpdateInstaller() {
    }

    // ---------- 能力判断 ----------

    /** 目前能不能自动覆盖更新（开发模式 / 安装目录不可写 → false，只能开下载页）。 */
    public static boolean supported() {
        Path dir = AppPaths.installDir();
        return dir != null && isWritable(dir);
    }

    private static boolean isWritable(Path dir) {
        try {
            Path probe = Files.createTempFile(dir, ".mcl-update-probe", ".tmp");
            Files.deleteIfExists(probe);
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    /** 重新启动的指令（exe / launch.ps1 / vbs）；没有可用的就 null。 */
    public static RestartCommand restartCommand(Path installDir) {
        if (installDir == null) {
            return null;
        }
        // 单 exe 发行优先（否则会去点不存在的旧入口）
        Path single = installDir.resolve("BriskLauncher.exe");
        if (Files.isRegularFile(single)) {
            return new RestartCommand("exe", single.toString());
        }
        Path exe = installDir.resolve("Brisk Launcher.exe");
        if (Files.isRegularFile(exe)) {
            return new RestartCommand("exe", exe.toString());
        }
        Path script = installDir.resolve("launch.ps1");
        if (Files.isRegularFile(script)) {
            return new RestartCommand("ps1", script.toString());
        }
        Path vbs = installDir.resolve("Brisk Launcher.vbs");
        if (Files.isRegularFile(vbs)) {
            return new RestartCommand("vbs", vbs.toString());
        }
        return null;
    }

    // ---------- 下载 + 解压 ----------

    /**
     * 下载并解压更新包，回传「已经可以覆盖」的暂存结果（不会动到安装目录）。
     *
     * @throws IOException 没有可用 zip、下载失败、套件结构不认识、被取消
     */
    public static Staged stage(UpdateService.Release release, HttpDownloader downloader,
                               Consumer<InstallProgress> progress) throws Exception {
        if (release == null || release.version() == null || release.version().isBlank()) {
            throw new IOException("更新资讯不完整，无法自动更新");
        }
        Consumer<InstallProgress> sink = progress == null ? item -> { } : progress;
        UpdateService.Asset asset = UpdateService.pickDesktopPackage(release);
        if (asset == null) {
            throw new IOException("这个版本没有提供可自动覆盖的更新包（exe / zip）");
        }
        sink.accept(new InstallProgress(InstallProgress.Stage.UPDATE_DOWNLOAD, 0, asset.name()));
        Path zip = UpdateService.download(asset, downloader, ratio -> sink.accept(
                new InstallProgress(InstallProgress.Stage.UPDATE_DOWNLOAD,
                        clamp(ratio), asset.name())));
        checkCancelled();

        Path staging = stagingDir(release.version());
        deleteTree(staging);
        Files.createDirectories(staging);
        sink.accept(new InstallProgress(InstallProgress.Stage.UPDATE_EXTRACT, 0, asset.name()));
        Path root;
        if (asset.name() != null && asset.name().toLowerCase(Locale.ROOT).endsWith(".exe")) {
            // 单 exe 资产：不用解压，直接当成「只有一个文件的安装目录」
            Path filesDir = staging.resolve("files");
            Files.createDirectories(filesDir);
            Files.copy(zip, filesDir.resolve("BriskLauncher.exe"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            sink.accept(new InstallProgress(InstallProgress.Stage.UPDATE_EXTRACT, 1, asset.name()));
            root = filesDir;
        } else {
            root = extract(zip, staging, ratio -> sink.accept(
                    new InstallProgress(InstallProgress.Stage.UPDATE_EXTRACT, clamp(ratio), asset.name())));
        }
        // 更新包类型必须和当前安装方式一致（portable 的 lib/*.jar vs exe 的 runtime/），
        // 否则镜像 lib/ 会把另一边删空。
        String installedLayout = layoutOf(AppPaths.installDir());
        String stagedLayout = layoutOf(root);
        if (installedLayout != null && stagedLayout != null && !installedLayout.equals(stagedLayout)) {
            deleteTree(staging);
            throw new IOException("更新包和当前安装方式不符（" + installedLayout + " / "
                    + stagedLayout + "），已取消自动覆盖，请打开下载页手动更新");
        }
        sink.accept(new InstallProgress(InstallProgress.Stage.UPDATE_EXTRACT, 1, release.version()));
        // 让下次启动知道「这个版本已经下载过」，也方便失败后排查
        writeState("staged.txt", release.version() + " | " + Instant.now() + " | " + zip.getFileName());
        SafeLog.info("[update] 更新包已就绪：" + root + "（" + zip.getFileName() + "）");
        return new Staged(release.version(), root, zip);
    }

    /**
     * 把更新包解压到 {@code stagingDir/files}，回传真正的启动器根目录。
     *
     * <p>安全：每个 entry 都走 {@link PathSafety#resolveUnder}（拒绝绝对路径 / {@code ..}），
     * 也限制单档、总量、档案数，避免 zip 炸弹；{@code data/} 一律跳过。
     */
    public static Path extract(Path zipFile, Path stagingDir, Consumer<Double> progress) throws Exception {
        Path filesDir = stagingDir.resolve("files");
        Files.createDirectories(filesDir);
        Consumer<Double> sink = progress == null ? ratio -> { } : progress;
        sink.accept(0.0);

        int entries = 0;
        long totalBytes = 0;
        long doneBytes = 0;
        long plannedBytes = 0;
        Set<String> seen = new HashSet<>();
        try (ZipFile zip = new ZipFile(zipFile.toFile(), StandardCharsets.UTF_8)) {
            // 先估一次总大小（有些 zip 没有 size，退回用档案数算进度）
            var entriesEnum = zip.entries();
            List<ZipEntry> list = new ArrayList<>();
            while (entriesEnum.hasMoreElements()) {
                ZipEntry entry = entriesEnum.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entryName(entry);
                if (name == null) {
                    continue;
                }
                if (entry.getSize() > MAX_ENTRY_BYTES) {
                    throw new SecurityException("更新包内单一档案过大：" + entry.getName());
                }
                if (entry.getSize() > 0) {
                    plannedBytes += entry.getSize();
                }
                list.add(entry);
            }
            int total = Math.max(1, list.size());
            int index = 0;
            for (ZipEntry entry : list) {
                checkCancelled();
                String name = entryName(entry);
                if (name == null) {
                    index++;
                    continue;
                }
                if (!seen.add(name)) {
                    throw new SecurityException("更新包内有重复档案：" + name);
                }
                entries++;
                if (entries > MAX_ENTRIES) {
                    throw new SecurityException("更新包档案数量超过上限");
                }
                Path target = PathSafety.resolveUnder(filesDir, name);
                Files.createDirectories(target.getParent());
                long written = 0;
                try (InputStream in = zip.getInputStream(entry);
                     OutputStream out = Files.newOutputStream(target,
                             StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        checkCancelled();
                        written += read;
                        totalBytes += read;
                        if (written > MAX_ENTRY_BYTES || totalBytes > MAX_TOTAL_BYTES) {
                            throw new SecurityException("更新包解压后超过大小上限");
                        }
                        out.write(buffer, 0, read);
                    }
                }
                doneBytes += written;
                index++;
                double ratio = plannedBytes > 0
                        ? Math.min(1.0, (double) doneBytes / plannedBytes)
                        : Math.min(1.0, (double) index / total);
                sink.accept(ratio);
            }
        } catch (Exception error) {
            // 暂存解坏了就不要留著，避免之后覆盖到半套档案
            deleteTree(stagingDir);
            throw error;
        }

        if (entries == 0) {
            deleteTree(stagingDir);
            throw new IOException("更新包是空的");
        }
        Path root = detectAppRoot(filesDir);
        if (root == null) {
            deleteTree(stagingDir);
            throw new IOException("更新包结构不认识（找不到 lib/ 或 app/ 与入口脚本）");
        }
        // 更新包外面通常包一层资料夹（Brisk Launcher/），所以 data/ 的检查要在
        // 找到根目录之后再做一次：玩家资料不论在哪一层都不覆盖。
        deleteTree(root.resolve("data"));
        sink.accept(1.0);
        return root;
    }

    /** 在解压结果里找「启动器根目录」：自己，或唯一/第一个符合的子目录。 */
    public static Path detectAppRoot(Path filesDir) {
        if (looksLikeAppRoot(filesDir)) {
            return filesDir;
        }
        if (!Files.isDirectory(filesDir)) {
            return null;
        }
        try (var stream = Files.list(filesDir)) {
            List<Path> children = stream.filter(Files::isDirectory).toList();
            for (Path child : children) {
                if (looksLikeAppRoot(child)) {
                    return child;
                }
            }
        } catch (Exception ignored) {
            // 读不到就当作不认识
        }
        return null;
    }

    /** 这个目录是不是一份启动器（有 lib 的 jar，或 jpackage 的 app/ + runtime/，而且有入口）。 */
    public static boolean looksLikeAppRoot(Path dir) {
        return layoutOf(dir) != null;
    }

    /**
     * 安装方式：{@code portable} = installDist（lib/*.jar + launch.ps1）、
     * {@code exe} = jpackage app-image（exe + app/ + runtime/）；不是启动器就 null。
     *
     * <p>更新包必须跟目前安装方式一致：把 exe 版套到可携版上会镜像掉 {@code lib/}，
     * 反过来也会破坏 exe 版的 runtime，两边都让启动器起不来。
     */
    public static String layoutOf(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return null;
        }
        boolean portable = Files.isRegularFile(dir.resolve("launch.ps1"))
                && hasJar(dir.resolve("lib"));
        boolean jpackageApp = Files.isRegularFile(dir.resolve("Brisk Launcher.exe"))
                && Files.isDirectory(dir.resolve("app"))
                && Files.isDirectory(dir.resolve("runtime"));
        // 单 exe 发行：只有 BriskLauncher.exe（jar 在第一次启动时释放到 runtime/lib/，
        // 所以更新包里只有 exe 也算合法结构）
        boolean single = Files.isRegularFile(dir.resolve("BriskLauncher.exe"));
        int kinds = (portable ? 1 : 0) + (jpackageApp ? 1 : 0) + (single ? 1 : 0);
        if (kinds != 1) {
            // 三个都不是，或同时成立（混合安装）都不算可更新
            return null;
        }
        return portable ? "portable" : (jpackageApp ? "exe" : "single");
    }

    private static boolean hasJar(Path dir) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (var stream = Files.list(dir)) {
            return stream.anyMatch(file -> file.getFileName().toString()
                    .toLowerCase(Locale.ROOT).endsWith(".jar"));
        } catch (Exception error) {
            return false;
        }
    }

    /** 清掉 zip 的目录前缀与不该覆盖的档案；回传 null 表示跳过。 */
    private static String entryName(ZipEntry entry) {
        String name = entry.getName();
        if (name == null) {
            return null;
        }
        name = name.replace('\\', '/').trim();
        while (name.startsWith("/")) {
            name = name.substring(1);
        }
        if (name.isEmpty() || name.endsWith("/")) {
            return null;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.startsWith("__macosx/") || lower.equals(".ds_store")) {
            return null;
        }
        // 玩家资料：就算更新包里有也绝对不覆盖
        if (lower.equals("data") || lower.startsWith("data/")) {
            return null;
        }
        if (name.length() > 512) {
            throw new SecurityException("更新包内路径过长");
        }
        return name;
    }

    // ---------- 套用 ----------

    /** 套用更新并结束启动器（由呼叫方在之后呼叫 {@code Platform.exit()}）。 */
    public static void applyAndRestart(Staged staged) throws IOException {
        Path installDir = AppPaths.installDir();
        if (installDir == null) {
            throw new IOException("找不到启动器安装目录，无法自动覆盖更新");
        }
        if (!isWritable(installDir)) {
            throw new IOException("安装目录不可写：" + installDir);
        }
        String installedLayout = layoutOf(installDir);
        String stagedLayout = layoutOf(staged.root());
        if (installedLayout != null && stagedLayout != null && !installedLayout.equals(stagedLayout)) {
            throw new IOException("更新包和当前安装方式不符（" + installedLayout + " / "
                    + stagedLayout + "），已取消自动覆盖");
        }
        launchApply(installDir, AppPaths.dataDir(), staged.root(), staged.version(),
                restartCommand(installDir), ProcessHandle.current().pid());
    }

    /**
     * 写出并启动覆盖脚本（测试也走这条路，所以安装目录 / 资料目录都是参数）。
     *
     * @param restart 重新启动方式，null = 不重启
     * @param waitPid 要等它结束的行程（正式流程是自己的 pid）
     */
    public static Process launchApply(Path installDir, Path dataDir, Path stagedRoot, String version,
                                      RestartCommand restart, long waitPid) throws IOException {
        Path updates = dataDir.resolve("updates");
        Files.createDirectories(updates);
        Path script = updates.resolve("apply-update.ps1");
        Files.writeString(script, loadScript(), StandardCharsets.UTF_8);

        String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(java.time.ZoneId.systemDefault()).format(Instant.now());
        Path backup = updates.resolve("backup-" + stamp);
        Path log = updates.resolve("last-update.log");
        Path status = updates.resolve("last-result.txt");

        List<String> command = new ArrayList<>(List.of(
                "powershell.exe", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
                "-File", script.toString(),
                "-LauncherPid", String.valueOf(Math.max(0, waitPid)),
                "-Source", stagedRoot.toAbsolutePath().toString(),
                "-Target", installDir.toAbsolutePath().toString(),
                "-Backup", backup.toString(),
                "-Version", version == null ? "" : version,
                "-Log", log.toString(),
                "-StatusFile", status.toString(),
                "-InstalledFile", updates.resolve("installed.txt").toString()));
        if (restart != null && restart.file() != null && !restart.file().isBlank()) {
            // 档案路径单独传：PowerShell 的 -File 只保证「单一引号参数」完整，
            // 不要把整串参数塞进同一个值（实测会被空白截断）。
            command.add("-RestartKind");
            command.add(restart.kind() == null ? "exe" : restart.kind());
            command.add("-RestartFile");
            command.add(restart.file());
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(updates.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        // 之前的失败记录先清掉，这次的结果由脚本重新写（必须在启动脚本之前）
        Files.deleteIfExists(status);
        SafeLog.info("[update] 准备覆盖：" + stagedRoot + " → " + installDir + "（备份 " + backup + "）");
        return builder.start();
    }

    // ---------- 结果 / 状态 ----------

    /** 取走上一次自动更新的结果（没有就 null）。 */
    public static ApplyResult consumeResult() {
        Path status = AppPaths.dataDir().resolve("updates").resolve("last-result.txt");
        if (!Files.isRegularFile(status)) {
            return null;
        }
        try {
            String text = Files.readString(status, StandardCharsets.UTF_8)
                    .replace("\uFEFF", "").trim();
            Files.deleteIfExists(status);
            if (text.isEmpty()) {
                return null;
            }
            String[] parts = text.split("\\|", 3);
            boolean ok = "OK".equalsIgnoreCase(parts[0].trim());
            String version = parts.length > 1 ? parts[1].trim() : "";
            String message = parts.length > 2 ? parts[2].trim() : "";
            if (!ok && !version.isBlank()) {
                // 同一个版本先不要每次启动都重新下载一次
                writeState("failed-" + safeName(version) + ".txt",
                        message + "\n" + Instant.now());
            }
            return new ApplyResult(ok, version, message);
        } catch (Exception error) {
            SafeLog.warn("读取更新结果失败：" + error.getClass().getSimpleName());
            return null;
        }
    }

    /** 这个版本是不是上次自动更新失败过（避免每次启动都重试同一个坏包）。 */
    public static boolean isKnownFailure(String version) {
        if (version == null || version.isBlank()) {
            return false;
        }
        Path marker = AppPaths.dataDir().resolve("updates")
                .resolve("failed-" + safeName(version) + ".txt");
        return Files.isRegularFile(marker);
    }

    /** 已经成功覆盖过的版本（用来避免「新版本没更新 CURRENT_VERSION」造成无限重启）。 */
    public static String appliedVersion() {
        Path file = AppPaths.dataDir().resolve("updates").resolve("installed.txt");
        if (!Files.isRegularFile(file)) {
            return "";
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8)
                    .replace("\uFEFF", "").trim();
        } catch (Exception error) {
            return "";
        }
    }

    /** 清掉旧的暂存 / 备份（启动时背景跑，不阻塞 UI）。 */
    public static void cleanStale() {
        Thread.ofVirtual().name("mcl-update-clean").start(() -> {
            Path updates = AppPaths.dataDir().resolve("updates");
            if (!Files.isDirectory(updates)) {
                return;
            }
            long now = System.currentTimeMillis();
            Path staging = updates.resolve("staging");
            try (var stream = Files.list(staging)) {
                for (Path dir : stream.toList()) {
                    if (!Files.isDirectory(dir)) {
                        continue;
                    }
                    try {
                        if (now - Files.getLastModifiedTime(dir).toMillis() > 7L * 24 * 3600_000) {
                            deleteTree(dir);
                        }
                    } catch (Exception ignored) {
                        // 单一目录失败不影响其它
                    }
                }
            } catch (Exception ignored) {
                // 没有暂存目录
            }
            try (var stream = Files.list(updates)) {
                List<Path> backups = stream
                        .filter(file -> Files.isDirectory(file)
                                && file.getFileName().toString().startsWith("backup-"))
                        .sorted((a, b) -> Long.compare(lastModified(b), lastModified(a)))
                        .toList();
                for (int index = 2; index < backups.size(); index++) {
                    deleteTree(backups.get(index));
                }
            } catch (Exception ignored) {
                // 忽略
            }
        });
    }

    // ---------- 工具 ----------

    private static String loadScript() throws IOException {
        try (InputStream in = UpdateInstaller.class.getResourceAsStream("/scripts/apply-update.ps1")) {
            if (in == null) {
                throw new IOException("更新脚本资源缺失（scripts/apply-update.ps1）");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Path stagingDir(String version) {
        return AppPaths.dataDir().resolve("updates").resolve("staging").resolve(safeName(version));
    }

    private static String safeName(String version) {
        String value = version == null ? "" : version.trim();
        value = value.replaceAll("[^0-9A-Za-z._+-]", "_");
        if (value.isEmpty() || value.equals(".") || value.equals("..")) {
            return "update";
        }
        return value.length() > 64 ? value.substring(0, 64) : value;
    }

    private static void writeState(String name, String text) {
        try {
            Path dir = AppPaths.dataDir().resolve("updates");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(name), text, StandardCharsets.UTF_8);
        } catch (Exception error) {
            SafeLog.warn("写入更新状态失败：" + name);
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception error) {
            return 0;
        }
    }

    private static void checkCancelled() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("更新已取消");
        }
    }

    private static double clamp(double value) {
        // 负数代表「不确定长度」（更新包没给 size）→ 原样传给界面，让它显示转圈而不是卡在 0%
        if (value < 0) {
            return -1;
        }
        return Math.max(0, Math.min(1, value));
    }

    private static void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            List<Path> paths = stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList();
            for (Path path : paths) {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // 被占用就留给下次清理
                }
            }
        } catch (Exception ignored) {
            // 尽力而为
        }
    }
}
