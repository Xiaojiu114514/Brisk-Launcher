package com.brisklauncher.mcl.game.launch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.brisklauncher.mcl.AppPaths;
import com.brisklauncher.mcl.auth.api.GameSession;
import com.brisklauncher.mcl.game.JavaVersionMapper;
import com.brisklauncher.mcl.game.Libraries;
import com.brisklauncher.mcl.game.Rules;
import com.brisklauncher.mcl.skin.SkinManager;
import com.brisklauncher.mcl.java.JavaManager;
import com.brisklauncher.mcl.java.JdkInfo;
import com.brisklauncher.mcl.game.GameInstance;
import com.brisklauncher.mcl.game.GameVersionMerger;
import com.brisklauncher.mcl.game.InstanceSettings;
import com.brisklauncher.mcl.game.InstanceSettingsStore;
import com.brisklauncher.mcl.download.DownloadTask;
import com.brisklauncher.mcl.download.HashUtils;
import com.brisklauncher.mcl.download.HttpDownloader;
import com.brisklauncher.mcl.download.MirrorResolver;
import com.brisklauncher.mcl.game.install.VersionInstaller;
import com.brisklauncher.mcl.net.Http;
import com.brisklauncher.mcl.download.remote.AuthlibInjectorApi;
import com.brisklauncher.mcl.security.SafeLog;
import com.brisklauncher.mcl.setting.LauncherSettings;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 启动链路。把 PCL2 / HMCL 的做法压缩成一条清晰流水线：
 *
 * <ol>
 *   <li>合并继承链上的版本 JSON；</li>
 *   <li>启动前检查文件完整性，缺失就调用 {@link VersionInstaller#repair} 自动补全；</li>
 *   <li>按版本 JSON 的 {@code javaVersion} 选 Java，缺失则自动下载 Temurin；</li>
 *   <li>按 rules 过滤 libraries，解压 natives（带签名缓存，避免每次启动重复解压）；</li>
 *   <li>替换全部占位符（含 Forge 的 ${library_directory} / ${classpath_separator}）；</li>
 *   <li>第三方账号注入 authlib-injector，离线账号先写 CSL 皮肤；</li>
 *   <li>启动进程、重定向输出到会话日志，2.5 秒内异常退出时把日志尾部作为错误信息。</li>
 * </ol>
 *
 * <p>安全：命令行里含 accessToken，本类不打印命令行，也不把令牌写入任何日志文件。
 */
public final class GameLauncher {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{[^}]+}");
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String LAUNCHER_NAME = "BriskLauncher";
    private static final String LAUNCHER_VERSION = "1.1.3";

    private final Supplier<LauncherSettings> settingsSupplier;
    private final VersionInstaller installer;
    private final JavaManager jreManager;
    private final HttpDownloader downloader;
    private final InstanceSettingsStore versionSettingsStore = new InstanceSettingsStore();
    private final SkinManager skinManager = new SkinManager();
    private final RunningGames runningGames = new RunningGames();
    private final AuthlibInjectorApi authlibInjector = new AuthlibInjectorApi();
    private final ObjectMapper mapper = Http.mapper();
    private final java.util.concurrent.ExecutorService executor =
            Executors.newVirtualThreadPerTaskExecutor();

    public GameLauncher(Supplier<LauncherSettings> settingsSupplier,
                         VersionInstaller installer,
                         JavaManager jreManager,
                         HttpDownloader downloader) {
        this.settingsSupplier = settingsSupplier;
        this.installer = installer;
        this.jreManager = jreManager;
        this.downloader = downloader;
    }

    public RunningGames runningGames() {
        return runningGames;
    }

    public LaunchHandle launchAsync(GameInstance entry, GameSession session,
                                    Consumer<LaunchProgress> progress) {
        LaunchHandle handle = new LaunchHandle(entry.id(), runningGames);
        CompletableFuture<Process> future = CompletableFuture.supplyAsync(() -> {
            try {
                return launch(entry, session, progress, handle);
            } catch (LaunchCancelledException e) {
                throw new CompletionException(e);
            } catch (LaunchException e) {
                throw new CompletionException(e);
            } catch (Exception e) {
                throw new CompletionException(new LaunchException(safeMessage(e), e));
            }
        }, executor);
        handle.attach(future);
        return handle;
    }

    // ---------- 主流程 ----------

    private Process launch(GameInstance entry, GameSession session, Consumer<LaunchProgress> progress,
                           LaunchHandle handle) throws Exception {
        Consumer<LaunchProgress> rawProgress = progress;
        // 每个阶段之前都检查取消标记；下载线程里也会因为抛异常而中止下载
        progress = stage -> {
            if (handle.isCancelled()) {
                throw new LaunchCancelledException();
            }
            rawProgress.accept(stage);
        };

        LauncherSettings global = settingsSupplier.get();
        progress.accept(LaunchProgress.PREPARING);

        InstanceSettings settings = versionSettingsStore.load(entry, global.defaultMemoryMb());
        entry.isolated(settings.isolated());

        if (!installer.hasValidJson(entry)) {
            throw new LaunchException("版本 JSON 缺失或损坏：" + entry.id() + "，请在“版本”页重新补全");
        }
        ObjectNode merged = new GameVersionMerger(entry.root().path()).merge(entry);

        progress.accept(LaunchProgress.CHECKING_FILES);
        if (!installer.looksComplete(entry)) {
            progress.accept(LaunchProgress.INSTALLING);
            final Consumer<LaunchProgress> installProgress = progress;
            try {
                installer.repair(entry, ignored -> installProgress.accept(LaunchProgress.INSTALLING));
            } catch (Exception e) {
                if (handle.isCancelled() || isCancellation(e)) {
                    throw new LaunchCancelledException();
                }
                throw new LaunchException("补全游戏文件失败：" + safeMessage(e), e);
            }
        }

        int requiredJava = requiredJava(merged, entry);
        progress.accept(LaunchProgress.RESOLVING_JAVA);
        JdkInfo jdk = resolveJava(requiredJava, settings, global, progress, handle);

        progress.accept(LaunchProgress.BUILDING_ARGS);
        Path gameDir = entry.gameDir();
        Files.createDirectories(gameDir);
        Path librariesDir = entry.root().librariesDir();
        Path assetsDir = entry.root().assetsDir();
        Path nativesDir = entry.nativesDir();
        Files.createDirectories(nativesDir);

        prepareNatives(entry, merged, librariesDir, nativesDir);

        String assetsIndex = merged.path("assetIndex").path("id")
                .asText(merged.path("assets").asText("legacy"));
        Path loggingConfig = findLoggingConfig(merged, assetsDir);

        // 离线 / 第三方账号：先把全局皮肤写进当前实例（下次启动生效）
        if (session.isOffline() || session.isThirdParty()) {
            SkinManager.ApplyResult result = skinManager.applyToInstance(entry, session.username());
            if (result.status() == SkinManager.Status.CSL_MISSING) {
                SafeLog.info("实例未安装 CustomSkinLoader，跳过皮肤写入");
            }
        }

        Set<String> classpathSet = buildClasspath(entry, merged, librariesDir);
        if (classpathSet.isEmpty()) {
            throw new LaunchException("classpath 为空：实例文件不完整，请重新补全");
        }
        String classpath = String.join(File.pathSeparator, classpathSet);

        Map<String, String> variables = buildVariables(entry, session, merged, gameDir, assetsDir,
                nativesDir, librariesDir, assetsIndex, classpath, settings);

        List<String> command = new ArrayList<>();
        command.add(jdk.javaExecutable().toString());

        List<String> jvmArgs = collectJvmArgs(merged, variables, settings);
        // PCL 风格的记忆体优化（可于设定页关闭）：只加安全旗标，不动堆大小
        com.brisklauncher.mcl.util.MemoryTuning.apply(jvmArgs, settings.memoryMb());
        command.addAll(jvmArgs);

        if (session.isThirdParty()) {
            Path injector = ensureAuthlibInjector();
            command.add("-javaagent:" + injector + "=" + session.endpoint());
            command.add("-Dauthlibinjector.side=client");
        }

        command.add("-Xmx" + settings.memoryMb() + "M");
        command.add("-Dminecraft.launcher.brand=" + LAUNCHER_NAME);
        command.add("-Dminecraft.launcher.version=" + LAUNCHER_VERSION);
        if (!containsPrefix(jvmArgs, "-Djava.library.path=")) {
            command.add("-Djava.library.path=" + nativesDir);
        }
        if (loggingConfig != null && Files.isRegularFile(loggingConfig)
                && !containsPrefix(jvmArgs, "-Dlog4j.configurationFile=")) {
            command.add("-Dlog4j.configurationFile=" + loggingConfig);
        }
        for (String extra : settings.jvmArgs()) {
            if (extra != null && !extra.isBlank()) {
                command.add(extra.trim());
            }
        }

        if (!hasClasspathArgument(jvmArgs)) {
            command.add("-cp");
            command.add(classpath);
        }

        String mainClass = merged.path("mainClass").asText("");
        if (mainClass.isBlank()) {
            throw new LaunchException("版本 JSON 缺少 mainClass");
        }
        command.add(mainClass);
        command.addAll(collectGameArgs(merged, variables, settings));

        if (Boolean.getBoolean("mcl.debug.launch")) {
            // 诊断「classpath / natives 路径」类问题；token 等敏感值一律打码
            // 把脱敏后的启动命令落盘：崩溃导出时一并带上（PCL 也会给启动参数）
        String redacted = redactedCommand(command);
        SafeLog.info("[debug] launch=" + redacted);
        try {
            java.nio.file.Path argFile = com.brisklauncher.mcl.AppPaths.logsDir().resolve("last-launch.txt");
            java.nio.file.Files.createDirectories(argFile.getParent());
            java.nio.file.Files.writeString(argFile, "instance=" + entry.id() + "\n"
                    + "mc=" + entry.mcVersion() + "\n"
                    + "loader=" + entry.loaderDisplay() + "\n"
                    + "java=" + jdk.display() + "\n"
                    + "command=" + redacted + "\n", java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 写不进去不影响启动
        }
        }

        if (handle.isCancelled()) {
            throw new LaunchCancelledException();
        }
        progress.accept(LaunchProgress.LAUNCHING);
        // 安全审计 H-05（令牌出现在游戏命令行）：试过用 Java 的 argfile（@file）隐藏，
        // 但实测 JDK 21 的 argfile **不支援含空白的参数**（引号会被当成内容、反斜线不跳脱空白），
        // 而 classpath / gameDir 常常带空白（C:\Program Files、C:\my games），
        // 硬上会让本来能启动的游戏起不来 —— 所以维持原样，不为了隐藏令牌破坏启动。
        // 现有的缓解：last-launch.txt / 日志都走 redactedCommand（令牌显示为 ***）、
        // StoredSession 不进长生命周期物件、令牌 24 小时后过期。
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(gameDir.toFile())
                .redirectErrorStream(true);
        // 安全审计（appid 泄漏面）：子进程默认继承整个环境变量表，
        // 任何整合包里不受信任的 mod 一行 System.getenv 就能拿到微软客户端 ID。
        // 启动游戏前显式清掉（取 ID 请走 MsClientId，构建内置值不依赖环境变量）。
        builder.environment().remove("BRISK_MS_CLIENT_ID");
        builder.environment().remove("MCL_MS_CLIENT_ID");
        Path sessionLog = AppPaths.logsDir().resolve("session-" + entry.id() + "-"
                + LocalDateTime.now().format(LOG_TIME) + ".log");
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            com.brisklauncher.mcl.crash.LaunchTimeline.error("无法启动游戏进程", e.getMessage());
            throw new LaunchException("无法启动游戏进程：" + e.getMessage(), e);
        }
        com.brisklauncher.mcl.crash.LaunchTimeline.ok("游戏进程已启动",
                "PID " + process.pid() + " · 会话日志 " + sessionLog.getFileName());

        TailBuffer tail = new TailBuffer(160);
        writeSessionHeader(sessionLog, entry, jdk, settings.memoryMb());
        pumpOutput(process, sessionLog, tail);
        runningGames.register(entry.id(), process);

        // 早退检测：Java 起不来（classpath 残缺、mainClass 错误）通常 1 秒内就退出
        if (process.waitFor(2500, TimeUnit.MILLISECONDS)) {
            int exitCode = process.exitValue();
            runningGames.stop(process.pid());
            String details = tail.text();
            com.brisklauncher.mcl.crash.LaunchTimeline.error("进程启动后立即退出",
                    "退出码 " + exitCode);
            throw new LaunchException("游戏启动失败（退出码 " + exitCode + "）"
                    + (details.isBlank() ? "" : "\n" + details));
        }

        // 等游戏窗口真正建起来再关闭加载遮罩（最多 30 秒，超时也放行）
        waitForGameWindow(entry, process, handle, tail, progress);

        progress.accept(LaunchProgress.RUNNING);
        SafeLog.info("游戏已启动：" + entry.id() + "（PID " + process.pid() + "）");
        return process;
    }

    private void waitForGameWindow(GameInstance entry, Process process, LaunchHandle handle,
                                   TailBuffer tail, Consumer<LaunchProgress> progress) throws Exception {
        Path logFile = entry.gameDir().resolve("logs").resolve("latest.log");
        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            if (handle.isCancelled()) {
                runningGames.stopByVersion(entry.id());
                throw new LaunchCancelledException();
            }
            if (!process.isAlive()) {
                int exitCode = process.exitValue();
                runningGames.stop(process.pid());
                String details = tail.text();
                com.brisklauncher.mcl.crash.LaunchTimeline.error("等待窗口期间进程退出",
                        "退出码 " + exitCode);
                throw new LaunchException("游戏异常退出（退出码 " + exitCode + "）"
                        + (details.isBlank() ? "" : "\n" + details));
            }
            if (GameWindowProbe.hasVisibleWindow(process.pid())
                    || logShowsWindowReady(logFile, startedAt)) {
                com.brisklauncher.mcl.crash.LaunchTimeline.ok("检测到游戏窗口", "等待 "
                        + (System.currentTimeMillis() - startedAt) + " ms");
                return;
            }
            Thread.sleep(200L);
        }
        com.brisklauncher.mcl.crash.LaunchTimeline.warn("等待游戏窗口超时", "30 秒内未检测到窗口，仍按运行中处理");
        SafeLog.warn("等待游戏窗口超时，继续按运行中处理");
    }

    /** 通过 latest.log 的早期标记判断“游戏窗口已经出现”，比固定等待更准。 */
    private static boolean logShowsWindowReady(Path logFile, long startedAt) {
        try {
            if (!Files.isRegularFile(logFile)) {
                return false;
            }
            if (Files.getLastModifiedTime(logFile).toMillis() < startedAt - 2000) {
                return false;
            }
            String tail = readTail(logFile, 12 * 1024);
            // 只认“窗口已经出来之后”才会打印的标记。
            // 之前误用了 "LWJGL" / "Setting user"，这两条在窗口出现前就写完，
            // 导致加载遮罩提前消失（玩家看到“已启动”但窗口还没出来）。
            for (String marker : List.of("Created: ", "Reloading ResourceManager",
                    "OpenAL initialized", "Sound engine started", "Backend library: LWJGL")) {
                if (tail.contains(marker)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // 读不到日志就继续等
        }
        return false;
    }

    private static String readTail(Path file, int maxBytes) throws IOException {
        try (var channel = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.READ)) {
            long size = channel.size();
            int length = (int) Math.min(size, maxBytes);
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(length);
            channel.position(size - length);
            channel.read(buffer);
            buffer.flip();
            return new String(buffer.array(), 0, length, StandardCharsets.UTF_8);
        }
    }

    private static boolean isCancellation(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 8) {
            if (current instanceof LaunchCancelledException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    // ---------- Java ----------

    private int requiredJava(ObjectNode merged, GameInstance entry) {
        int fromJson = merged.path("javaVersion").path("majorVersion").asInt(-1);
        if (fromJson > 0) {
            return fromJson;
        }
        if (entry.javaMajor() > 0) {
            return entry.javaMajor();
        }
        return JavaVersionMapper.forMinecraft(entry.mcVersion());
    }

    private JdkInfo resolveJava(int required, InstanceSettings settings, LauncherSettings global,
                                Consumer<LaunchProgress> progress, LaunchHandle handle) {
        if (settings.hasCustomJava()) {
            return jreManager.resolve(required, settings.javaPath());
        }
        Optional<JdkInfo> existing = jreManager.findBest(required);
        if (existing.isPresent()) {
            return existing.get();
        }
        progress.accept(LaunchProgress.INSTALLING_JAVA);
        MirrorResolver mirrors = new MirrorResolver(global);
        try {
            return jreManager.ensureFor(required, downloader, mirrors, ignored -> {
                if (handle.isCancelled()) {
                    throw new LaunchCancelledException();
                }
                progress.accept(LaunchProgress.INSTALLING_JAVA);
            });
        } catch (LaunchCancelledException e) {
            throw e;
        } catch (Exception e) {
            if (handle.isCancelled() || isCancellation(e)) {
                throw new LaunchCancelledException();
            }
            throw new LaunchException(e.getMessage(), e);
        }
    }

    // ---------- classpath ----------

    private Set<String> buildClasspath(GameInstance entry, ObjectNode merged, Path librariesDir) {
        Set<String> entries = new LinkedHashSet<>();
        for (JsonNode library : merged.path("libraries")) {
            if (!Rules.allows(library, Rules.Features.none()) || Libraries.isNativesOnly(library)) {
                continue;
            }
            String relative = Libraries.pathOf(library);
            if (relative == null) {
                continue;
            }
            // 1.19+ 的 natives 是普通 library 条目（路径带 -natives-），必须一起进 classpath：
            // LWJGL 3.3+/3.4 靠 SharedLibraryExtractPath 从 classpath 里的 jar 自行解压，
            // 只把 DLL 解压到 java.library.path 反而会让 26.x 的 NativeLibrariesBootstrap 找不到库。
            Path file = librariesDir.resolve(relative);
            if (Files.isRegularFile(file)) {
                entries.add(file.toString());
            } else {
                SafeLog.warn("缺少库文件：" + relative);
            }
        }
        if (usesBootstrapLauncher(merged)) {
            // Forge / NeoForge 1.17+ 由 BootstrapLauncher 自己装配 minecraft 模块
            // （patched client），原版 client jar 再进 classpath 会 split package。
            SafeLog.info("检测到 BootstrapLauncher，classpath 不包含原版 client jar");
        } else {
            for (Path jar : GameVersionMerger.chainJars(entry.root().path(), entry.id())) {
                entries.add(jar.toString());
            }
        }
        return entries;
    }

    private static boolean usesBootstrapLauncher(ObjectNode merged) {
        String mainClass = merged.path("mainClass").asText("").toLowerCase(Locale.ROOT);
        return mainClass.contains("bootstraplauncher") || mainClass.contains("modlauncher");
    }

    // ---------- natives ----------

    private void prepareNatives(GameInstance entry, ObjectNode merged, Path librariesDir, Path nativesDir)
            throws IOException {
        List<Path> nativeJars = new ArrayList<>();
        for (JsonNode library : merged.path("libraries")) {
            if (!Rules.allows(library, Rules.Features.none())) {
                continue;
            }
            String relative = Libraries.pathOf(library);
            if (relative != null && Libraries.isNativeJar(relative)) {
                Path jar = librariesDir.resolve(relative);
                if (Files.isRegularFile(jar)) {
                    nativeJars.add(jar);
                }
            }
            JsonNode classifiers = library.path("downloads").path("classifiers");
            if (classifiers.isObject()) {
                String key = Libraries.nativeClassifier(library);
                if (key != null) {
                    String path = classifiers.path(key).path("path").asText("");
                    if (!path.isEmpty()) {
                        Path jar = librariesDir.resolve(path);
                        if (Files.isRegularFile(jar)) {
                            nativeJars.add(jar);
                        }
                    }
                }
            }
        }
        if (nativeJars.isEmpty()) {
            return;
        }

        // 版本 JSON 自己声明了 -Djava.library.path 时按它给的子目录解压：
        // 26.x 是 ${natives_directory}/java，1.19~1.21.5 是 ${natives_directory}。
        Path targetDir = declaredNativesDir(merged, nativesDir);
        String signature = signatureOf(nativeJars) + "@" + nativesDir.relativize(targetDir);
        Path marker = nativesDir.resolve(".mcl-natives");
        if (Files.isRegularFile(marker)) {
            String previous = Files.readString(marker, StandardCharsets.UTF_8).trim();
            if (previous.equals(HashUtils.hashText(signature))) {
                return;
            }
        }
        if (!isVersionRunning(entry.id())) {
            clearDirectory(nativesDir);
        }
        Files.createDirectories(targetDir);

        for (Path jar : nativeJars) {
            extractJar(jar, targetDir);
        }
        Files.writeString(marker, HashUtils.hashText(signature), StandardCharsets.UTF_8);
    }

    /**
     * 从 version JSON 的 {@code -Djava.library.path=} 推出 natives 解压目录。
     *
     * <p>只认落在 {@code natives/} 之内的值，避免被版本 JSON 带到游戏目录外面去写文件。
     */
    private static Path declaredNativesDir(ObjectNode merged, Path nativesDir) {
        String declared = findJvmArgValue(merged, "-Djava.library.path=");
        if (declared == null) {
            return nativesDir;
        }
        String placeholder = "${natives_directory}";
        if (!declared.startsWith(placeholder)) {
            return nativesDir;
        }
        String suffix = declared.substring(placeholder.length())
                .replace('\\', '/').replaceAll("^/+", "");
        if (suffix.isEmpty()) {
            return nativesDir;
        }
        Path candidate = nativesDir.resolve(suffix).normalize();
        return candidate.startsWith(nativesDir.normalize()) ? candidate : nativesDir;
    }

    /** 在 arguments.jvm 里找以指定前缀开头的参数值（文本参数与带 rules 的对象参数都看）。 */
    private static String findJvmArgValue(ObjectNode merged, String prefix) {
        for (JsonNode arg : merged.path("arguments").path("jvm")) {
            if (arg.isTextual()) {
                String text = arg.asText();
                if (text.startsWith(prefix)) {
                    return text.substring(prefix.length());
                }
                continue;
            }
            JsonNode value = arg.path("value");
            for (JsonNode item : value.isArray() ? value : List.of(value)) {
                if (item.isTextual() && item.asText().startsWith(prefix)) {
                    return item.asText().substring(prefix.length());
                }
            }
        }
        return null;
    }

    private boolean isVersionRunning(String versionId) {
        return runningGames.list().stream().anyMatch(game -> game.versionId().equals(versionId));
    }

    private static String signatureOf(List<Path> jars) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Path jar : jars) {
            sb.append(jar.getFileName()).append(':').append(Files.size(jar)).append(';');
        }
        return HashUtils.hashText(sb.toString());
    }

    private static void extractJar(Path jar, Path destination) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || name.startsWith("META-INF/")
                        || name.endsWith(".sha1") || name.endsWith(".git")
                        || name.endsWith(".mf") || name.endsWith(".MF")) {
                    continue;
                }
                Path target = destination.resolve(name).normalize();
                if (!target.startsWith(destination)) {
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (OutputStream out = Files.newOutputStream(target)) {
                    zip.transferTo(out);
                }
            }
        }
    }

    private static void clearDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            for (Path item : stream.toList()) {
                if (Files.isDirectory(item)) {
                    clearDirectory(item);
                }
                Files.deleteIfExists(item);
            }
        }
    }

    // ---------- 参数 ----------

    private Map<String, String> buildVariables(GameInstance entry, GameSession session, ObjectNode merged,
                                               Path gameDir, Path assetsDir, Path nativesDir,
                                               Path librariesDir, String assetsIndex, String classpath,
                                               InstanceSettings settings) {
        Map<String, String> vars = new LinkedHashMap<>();
        // 安全审计 M-04：玩家名可能来自第三方服务器（不可信），绝不能含 ${...}
        // 早先用「逐项 replace」时，名字里塞 ${auth_access_token} 就能把令牌顶进 --username。
        String playerName = session.username() == null ? "Player" : session.username();
        if (playerName.contains("${")) {
            SafeLog.warn("[launch] 玩家名含占位符，已清理");
            playerName = playerName.replace("${", "").replace("}", "");
        }
        vars.put("auth_player_name", playerName);
        vars.put("version_name", entry.id());
        vars.put("game_directory", gameDir.toString());
        vars.put("assets_root", assetsDir.toString());
        vars.put("assets_index_name", assetsIndex == null || assetsIndex.isBlank() ? "legacy" : assetsIndex);
        vars.put("auth_uuid", dashedless(session.uuid()));
        vars.put("auth_access_token", new String(session.accessToken()));
        vars.put("auth_session", new String(session.accessToken()));
        vars.put("clientid", "");
        vars.put("auth_xuid", "");
        vars.put("user_type", session.userType() == null ? "legacy" : session.userType());
        vars.put("user_properties", session.userProperties() == null ? "{}" : session.userProperties());
        vars.put("version_type", merged.path("type").asText("release"));
        vars.put("natives_directory", nativesDir.toString());
        vars.put("launcher_name", LAUNCHER_NAME);
        vars.put("launcher_version", LAUNCHER_VERSION);
        vars.put("classpath", classpath);
        vars.put("classpath_separator", File.pathSeparator);
        vars.put("library_directory", librariesDir.toString());
        if (settings.width() > 0 && settings.height() > 0) {
            vars.put("resolution_width", String.valueOf(settings.width()));
            vars.put("resolution_height", String.valueOf(settings.height()));
        } else {
            vars.put("resolution_width", "854");
            vars.put("resolution_height", "480");
        }
        return vars;
    }

    private List<String> collectJvmArgs(ObjectNode merged, Map<String, String> vars, InstanceSettings settings) {
        List<String> result = new ArrayList<>();
        JsonNode jvm = merged.path("arguments").path("jvm");
        if (jvm.isArray() && !jvm.isEmpty()) {
            appendArguments(jvm, result, vars, features(settings));
        } else {
            // 1.12.2 及更早没有 arguments 字段
            result.add("-Djava.library.path=" + vars.get("natives_directory"));
            result.add("-cp");
            result.add(vars.get("classpath"));
        }
        return result;
    }

    private List<String> collectGameArgs(ObjectNode merged, Map<String, String> vars, InstanceSettings settings) {
        List<String> result = new ArrayList<>();
        JsonNode game = merged.path("arguments").path("game");
        if (game.isArray() && !game.isEmpty()) {
            appendArguments(game, result, vars, features(settings));
            return result;
        }
        String legacy = merged.path("minecraftArguments").asText("");
        if (legacy.isBlank()) {
            throw new LaunchException("版本 JSON 既没有 arguments 也没有 minecraftArguments");
        }
        for (String token : legacy.split("\\s+")) {
            if (!token.isBlank()) {
                result.add(expand(token, vars));
            }
        }
        return result;
    }

    private static Rules.Features features(InstanceSettings settings) {
        return new Rules.Features(false, settings.width(), settings.height());
    }

    private static void appendArguments(JsonNode array, List<String> target,
                                        Map<String, String> vars, Rules.Features features) {
        for (JsonNode element : array) {
            if (element.isTextual()) {
                target.add(expand(element.asText(), vars));
                continue;
            }
            if (!element.isObject() || !Rules.allows(element, features)) {
                continue;
            }
            JsonNode value = element.path("value");
            if (value.isTextual()) {
                target.add(expand(value.asText(), vars));
            } else if (value.isArray()) {
                for (JsonNode item : value) {
                    target.add(expand(item.asText(), vars));
                }
            }
        }
    }

    /**
     * 占位符替换（**单次扫描**）；未知占位符清空，避免把 {@code ${xxx}} 传给游戏。
     *
     * <p>安全审计 M-04：旧实现是「按 vars 顺序逐个 replace」，如果某个值里含有字面量
     * {@code ${auth_access_token}}（第三方服务器给的玩家名就可以），后面的轮次会把它换成真令牌
     * —— 令牌进了 {@code --username}，再被游戏写进 latest.log / 崩溃包。
     * 现在一次扫描替换，替换进去的内容不会被当成模板再解析。
     */
    static String expand(String template, Map<String, String> vars) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        java.util.regex.Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length());
        while (matcher.find()) {
            String key = matcher.group().substring(2, matcher.group().length() - 1);
            String value = vars.get(key);
            matcher.appendReplacement(out,
                    java.util.regex.Matcher.quoteReplacement(value == null ? "" : value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static boolean containsPrefix(List<String> args, String prefix) {
        for (String arg : args) {
            if (arg.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasClasspathArgument(List<String> args) {
        for (String arg : args) {
            String lower = arg.toLowerCase(Locale.ROOT);
            if (lower.equals("-cp") || lower.equals("-classpath") || lower.equals("--class-path")
                    || lower.startsWith("-cp=") || lower.startsWith("--class-path=")) {
                return true;
            }
        }
        return false;
    }

    // ---------- 第三方认证 ----------


    private static String redactedCommand(List<String> command) {        List<String> masked = new java.util.ArrayList<>(command.size());
        java.util.Set<String> secretFlags = java.util.Set.of(
                "--accessToken", "--clientid", "--xuid", "--clientId");
        boolean maskNext = false;
        for (String arg : command) {
            if (maskNext) {
                masked.add("***");
                maskNext = false;
                continue;
            }
            if (secretFlags.contains(arg)) {
                masked.add(arg);
                maskNext = true;
                continue;
            }
            masked.add(arg.startsWith("-javaagent:") ? "-javaagent:***" : arg);
        }
        return String.join(" ", masked);
    }

    private Path ensureAuthlibInjector() {
        AuthlibInjectorApi.Artifact artifact = authlibInjector.latest();
        Path jar = authlibInjector.cachedJar(artifact.version());
        // 安全审计 L2：命中快取也要校验（jar 可能被替换），不匹配就删掉重下
        if (Files.isRegularFile(jar) && artifact.checksum() != null
                && !artifact.checksum().isBlank()) {
            try {
                String cached = HashUtils.hash(jar, "SHA-256");
                if (artifact.checksum().equalsIgnoreCase(cached)) {
                    return jar;
                }
                SafeLog.warn("authlib-injector 快取校验失败，重新下载");
                Files.deleteIfExists(jar);
            } catch (Exception error) {
                SafeLog.warn("authlib-injector 校验异常，重新下载："
                        + error.getClass().getSimpleName());
            }
        } else if (Files.isRegularFile(jar)) {
            return jar;
        }
        try {
            MirrorResolver mirrors = new MirrorResolver(settingsSupplier.get());
            downloader.downloadOne(new DownloadTask(
                    mirrors.resolve(artifact.url().toString()), jar, null, -1));
            if (artifact.checksum() != null && !artifact.checksum().isBlank()) {
                String actual = HashUtils.hash(jar, "SHA-256");
                if (!artifact.checksum().equalsIgnoreCase(actual)) {
                    Files.deleteIfExists(jar);
                    throw new LaunchException("authlib-injector 校验失败，已删除损坏文件");
                }
            }
            return jar;
        } catch (IOException e) {
            throw new LaunchException("下载 authlib-injector 失败：" + e.getMessage(), e);
        }
    }

    // ---------- 日志 ----------

    private void pumpOutput(Process process, Path logFile, TailBuffer tail) {
        try {
            Files.createDirectories(logFile.getParent());
        } catch (IOException ignored) {
            // 日志目录建不出来也不影响启动
        }
        Thread.ofVirtual().name("mcl-game-log").start(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                 var writer = openWriter(logFile)) {
                String line;
                int unflushed = 0;
                while ((line = reader.readLine()) != null) {
                    tail.add(line);
                    if (writer != null) {
                        writer.write(line);
                        writer.newLine();
                        // 定期刷盘：进程被强制结束时也能留下已经产生的日志
                        if (++unflushed >= 20) {
                            writer.flush();
                            unflushed = 0;
                        }
                    }
                }
                if (writer != null) {
                    writer.flush();
                }
            } catch (IOException ignored) {
                // 进程结束时流关闭属正常
            }
        });
    }

    /** 会话日志头部：只写启动器与 Java 信息，不含令牌。 */
    private void writeSessionHeader(Path logFile, GameInstance entry, JdkInfo jdk, int memoryMb) {
        String header = "[MCL] " + LAUNCHER_NAME + " " + LAUNCHER_VERSION
                + " | version=" + entry.id()
                + " | loader=" + entry.loaderDisplay()
                + " | java=" + jdk.majorVersion()
                + " | memory=" + memoryMb + "M"
                + " | os=" + System.getProperty("os.name")
                + System.lineSeparator();
        try {
            Files.createDirectories(logFile.getParent());
            Files.writeString(logFile, header, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ignored) {
            // 写不了头部不影响启动
        }
    }

    private static java.io.BufferedWriter openWriter(Path file) {
        try {
            Files.createDirectories(file.getParent());
            return Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            return null;
        }
    }

    /** 只保留最后 N 行，用于早退时的错误提示。 */
    private static final class TailBuffer {
        private final int capacity;
        private final Deque<String> lines = new ArrayDeque<>();

        private TailBuffer(int capacity) {
            this.capacity = capacity;
        }

        synchronized void add(String line) {
            lines.addLast(line);
            while (lines.size() > capacity) {
                lines.removeFirst();
            }
        }

        synchronized String text() {
            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                sb.append(line).append('\n');
            }
            return SafeLog.sanitize(sb.toString().trim());
        }
    }

    // ---------- 文件工具 ----------

    private Path findLoggingConfig(ObjectNode merged, Path assetsDir) {
        JsonNode client = merged.path("logging").path("client");
        String id = client.path("file").path("id").asText("");
        if (id.isEmpty()) {
            return null;
        }
        return assetsDir.resolve("log_configs").resolve(id);
    }

    private static String dashedless(String uuid) {
        return uuid == null ? "" : uuid.replace("-", "");
    }

    private static String safeMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        if (message == null || message.isBlank()) {
            return root.getClass().getSimpleName();
        }
        return SafeLog.sanitize(message.length() > 400 ? message.substring(0, 400) + "…" : message);
    }

    /** 关闭内部线程池（应用退出时调用）。 */
    public void shutdown() {
        executor.shutdownNow();
    }
}
