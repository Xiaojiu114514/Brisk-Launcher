package com.brisklauncher.mcl.java;

import com.brisklauncher.mcl.AppPaths;
import com.brisklauncher.mcl.download.DownloadTask;
import com.brisklauncher.mcl.download.HashUtils;
import com.brisklauncher.mcl.download.HttpDownloader;
import com.brisklauncher.mcl.download.MirrorResolver;
import com.brisklauncher.mcl.game.install.InstallProgress;
import com.brisklauncher.mcl.game.launch.LaunchException;
import com.brisklauncher.mcl.i18n.I18n;
import com.brisklauncher.mcl.download.remote.AdoptiumApi;
import com.brisklauncher.mcl.download.remote.CorrettoJdkApi;
import com.brisklauncher.mcl.download.remote.OracleJdkApi;
import com.brisklauncher.mcl.security.SafeLog;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Java 运行时管理：扫描系统 JDK、管理托管目录、缺失时自动下载 JDK。
 *
 * <p>扫描顺序与 HMCL 类似：{@code JAVA_HOME} → 启动器自带 → 系统常见安装目录。
 * 版本判定优先读 {@code release} 文件，失败再退回目录名解析。
 *
 * <p>自动下载**只下 JDK、不下 JRE**：Oracle JDK（21 / 25）优先，Amazon Corretto 当通用备援
 * （8 / 11 / 16 / 17 / 21 / 25，稳定 latest + 官方 SHA-256），最后才是 Adoptium JDK
 * （清华 TUNA 镜像 → 官方 GitHub）。每个来源都做 SHA-256 校验。
 */
public final class JavaManager {

    private static final String[] WINDOWS_JDK_ROOTS = {
            "C:\\Program Files\\Java",
            "C:\\Program Files\\Eclipse Adoptium",
            "C:\\Program Files\\Microsoft",
            "C:\\Program Files\\Zulu",
            "C:\\Program Files\\BellSoft",
            "C:\\Program Files\\Amazon Corretto",
            "C:\\Program Files (x86)\\Java"
    };

    private final Path jdkRoot;
    private final AdoptiumApi adoptium = new AdoptiumApi();
    private final OracleJdkApi oracle = new OracleJdkApi();
    private final CorrettoJdkApi corretto = new CorrettoJdkApi();

    /** 每个主版本一把锁：避免两个入口同时下载／解压同一个 {@code jdk/<major>}。 */
    private static final java.util.concurrent.ConcurrentHashMap<Integer, Object> INSTALL_LOCKS =
            new java.util.concurrent.ConcurrentHashMap<>();

    public JavaManager() {
        this(AppPaths.jdkDir());
    }

    public JavaManager(Path jdkRoot) {
        this.jdkRoot = jdkRoot;
    }

    public Path jdkRoot() {
        return jdkRoot;
    }

    // ---------- 扫描 ----------

    public List<JdkInfo> scanAll() {
        Set<Path> homes = new LinkedHashSet<>();

        // 启动器托管的 JDK 优先
        if (Files.isDirectory(jdkRoot)) {
            try (Stream<Path> stream = Files.list(jdkRoot)) {
                stream.filter(Files::isDirectory).forEach(dir -> resolveHome(dir).ifPresent(homes::add));
            } catch (IOException e) {
                SafeLog.warn("扫描托管 JDK 目录失败");
            }
        }

        envHome("JAVA_HOME").ifPresent(homes::add);

        String javaHome = System.getProperty("java.home", "");
        if (!javaHome.isBlank()) {
            resolveHome(Path.of(javaHome)).ifPresent(homes::add);
        }

        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            for (String root : WINDOWS_JDK_ROOTS) {
                Path dir = Path.of(root);
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                try (Stream<Path> stream = Files.list(dir)) {
                    stream.filter(Files::isDirectory).forEach(child -> resolveHome(child).ifPresent(homes::add));
                } catch (IOException ignored) {
                    // 权限不足的目录直接跳过
                }
            }
        }

        List<JdkInfo> result = new ArrayList<>();
        for (Path home : homes) {
            int major = detectMajorVersion(home);
            if (major > 0) {
                boolean managed = home.toAbsolutePath().normalize().startsWith(
                        jdkRoot.toAbsolutePath().normalize());
                result.add(new JdkInfo(home, String.valueOf(major), major, managed, detectBits(home)));
            }
        }
        result.sort((a, b) -> {
            int byMajor = Integer.compare(a.majorVersion(), b.majorVersion());
            return byMajor != 0 ? byMajor : Integer.compare(b.bits(), a.bits());
        });
        return result;
    }

    public List<JdkInfo> findAll() {
        return scanAll();
    }

    /** 精确匹配主版本。 */
    public Optional<JdkInfo> findFor(int requiredMajor) {
        return scanAll().stream().filter(info -> info.majorVersion() == requiredMajor).findFirst();
    }

    /**
     * 精确匹配优先；同版本优先 64 位。
     *
     * <p>只有 {@code requiredMajor >= 17} 时才允许用更高的 Java 顶替（17 → 21 是安全的）；
     * Java 8 / 11 必须精确匹配，否则旧版本 Minecraft 会直接崩在类加载阶段。
     */
    public Optional<JdkInfo> findBest(int requiredMajor) {
        List<JdkInfo> all = scanAll();
        Optional<JdkInfo> exact = all.stream()
                .filter(info -> info.majorVersion() == requiredMajor)
                .max((a, b) -> Integer.compare(a.bits(), b.bits()));
        if (exact.isPresent()) {
            return exact;
        }
        if (requiredMajor < 17) {
            return Optional.empty();
        }
        return all.stream()
                .filter(info -> info.majorVersion() > requiredMajor)
                .min((a, b) -> {
                    int byMajor = Integer.compare(a.majorVersion(), b.majorVersion());
                    return byMajor != 0 ? byMajor : Integer.compare(b.bits(), a.bits());
                });
    }

    /** 为某个 MC 版本挑 Java：优先版本 JSON 里写的精确主版本。 */
    public JdkInfo resolve(int requiredMajor, String customPath) {
        if (customPath != null && !customPath.isBlank()) {
            Path path = Path.of(customPath);
            if (Files.isRegularFile(path)) {
                Path home = path.getParent() != null && path.getParent().getParent() != null
                        ? path.getParent().getParent()
                        : path;
                int major = detectMajorVersion(home);
                return new JdkInfo(home, major > 0 ? String.valueOf(major) : "custom",
                        major > 0 ? major : requiredMajor, false, detectBits(home));
            }
            SafeLog.warn("自定义 Java 路径无效，回退自动选择");
        }
        return findBest(requiredMajor)
                .orElseThrow(() -> new LaunchException("未找到 Java " + requiredMajor
                        + "，请在设置中下载或手动指定 Java 路径"));
    }

    // ---------- 下载 ----------

    /**
     * 确保存在指定主版本的 Java；不存在则自动下载 JDK 到 {@code <launcher>/jdk/}。
     *
     * <p>来源顺序：**Oracle JDK（21 / 25）→ Amazon Corretto（8 / 11 / 16 / 17 / 21 / 25）
     * → Adoptium JDK（清华 TUNA 镜像 → 官方 GitHub）**。
     * 每个来源下载完都做 SHA-256 校验，不符就删档换下一个来源；全部失败时把每个来源的原因
     * 一起报给用户，不再只留一句「下载失败」。
     */
    public JdkInfo ensureFor(int major, HttpDownloader downloader, MirrorResolver mirrors,
                             Consumer<InstallProgress> progress) {
        // 同一个 major 的「检查 → 下载 → 解压」必须原子：否则两个入口同时补 Java 时，
        // 会各自下载、共用 <target>.extracting 临时目录。锁内再 findBest 一次，
        // 第二个请求会直接拿到第一个刚装好的 JDK，不会重复下载。
        Object lock = INSTALL_LOCKS.computeIfAbsent(major, key -> new Object());
        synchronized (lock) {
            Optional<JdkInfo> existing = findBest(major);
            if (existing.isPresent()) {
                return existing.get();
            }
            return downloadMissing(major, downloader, mirrors, progress);
        }
    }

    /** 真正下载缺失的 Java；调用方已持有该 major 的 {@link #INSTALL_LOCKS} 锁。 */
    private JdkInfo downloadMissing(int major, HttpDownloader downloader, MirrorResolver mirrors,
                                    Consumer<InstallProgress> progress) {
        progress.accept(new InstallProgress(InstallProgress.Stage.JAVA, 0.02, "Java " + major));

        List<String> failures = new ArrayList<>();

        // 1) Oracle JDK：21 用 21.0.12（最后一个 NFTC 且可直连的版本），25 用 latest。
        if (oracle.supports(major)) {
            try {
                OracleJdkApi.JdkAsset asset = oracle.resolve(major);
                return installPackage(major, new JavaPackage(
                        asset.label(),
                        fileNameOf(asset.link()),
                        List.of(asset.link()),
                        asset.sha256(),
                        asset.size()), downloader, progress);
            } catch (Exception e) {
                failures.add("Oracle：" + oneLine(e.getMessage()));
                SafeLog.warn("Oracle JDK " + major + " 下载失败，改用 Amazon Corretto："
                        + oneLine(e.getMessage()));
            }
        }

        // 2) Amazon Corretto：稳定 latest + 官方 latest_sha256，覆盖 8 / 11 / 16 / 17 / 21 / 25。
        if (corretto.supports(major)) {
            try {
                CorrettoJdkApi.JdkAsset asset = corretto.resolve(major);
                return installPackage(major, new JavaPackage(
                        asset.label(),
                        fileNameOf(asset.link()),
                        List.of(asset.link()),
                        asset.sha256(),
                        asset.size()), downloader, progress);
            } catch (Exception e) {
                failures.add("Corretto：" + oneLine(e.getMessage()));
                SafeLog.warn("Amazon Corretto " + major + " 下载失败，改用 Adoptium JDK："
                        + oneLine(e.getMessage()));
            }
        }

        // 3) Adoptium JDK：镜像优先、官方保底。
        try {
            AdoptiumApi.JdkAsset asset = adoptium.latest(major);
            List<URI> urls = new ArrayList<>();
            try {
                urls.add(adoptium.tunaMirror(asset));
            } catch (RuntimeException e) {
                SafeLog.warn("生成 Adoptium 镜像地址失败：" + oneLine(e.getMessage()));
            }
            urls.addAll(mirrors.resolve(asset.link().toString()));
            return installPackage(major, new JavaPackage(
                    "Adoptium JDK " + (asset.version().isBlank() ? major : asset.version()),
                    asset.packageName(),
                    urls,
                    asset.checksum(),
                    asset.size()), downloader, progress);
        } catch (Exception e) {
            failures.add("Adoptium：" + oneLine(e.getMessage()));
            SafeLog.warn("Adoptium JDK " + major + " 下载失败：" + oneLine(e.getMessage()));
        }

        String detail = failures.isEmpty() ? "没有可用的下载来源" : String.join("；", failures);
        SafeLog.warn("Java " + major + " 自动下载失败：" + detail);
        throw new LaunchException(I18n.t("java.download_failed", major, detail)
                + "\n\n" + I18n.t("java.download_hint"));
    }

    /** 下载 → SHA-256 校验 → 解压到 {@code jdk/<major>}。失败直接抛出，由 {@link #ensureFor} 换来源。 */
    private JdkInfo installPackage(int major, JavaPackage pkg, HttpDownloader downloader,
                                   Consumer<InstallProgress> progress) throws IOException {
        if (pkg.urls().isEmpty()) {
            throw new IOException("没有可用的下载地址");
        }
        Path cacheDir = AppPaths.cacheDir().resolve("java");
        Path archive = cacheDir.resolve(pkg.fileName());
        Path target = jdkRoot.resolve(String.valueOf(major));
        long expected = pkg.size();

        // 快取判斷只看 provider 當下給的 SHA-256（不是檔名、大小或「檔案存在」）：
        // 相符才重用；不符就刪掉重下；下載完 verifyChecksum 再驗一次。
        // 呼叫 downloadOne 前 archive 必定不存在（上面剛刪掉或本來就沒有），
        // 所以 DownloadTask.alreadySatisfied() 那個「只比大小」的捷徑在這條路上不會生效。
        if (Files.isRegularFile(archive) && !matches(archive, pkg.sha256())) {
            SafeLog.warn("Java 安装包与校验值不符，删除重下：" + pkg.fileName());
            Files.deleteIfExists(archive);
        }

        if (!Files.isRegularFile(archive)) {
            Files.createDirectories(cacheDir);
            SafeLog.info("下载 Java " + major + "（" + pkg.label() + "）：" + pkg.urls());
            DownloadTask task = new DownloadTask(pkg.urls(), archive, null, expected);
            long[] reported = {-1L};
            downloader.downloadOne(task, done -> {
                long step = 2L * 1024 * 1024;
                if (reported[0] >= 0 && done - reported[0] < step && (expected <= 0 || done < expected)) {
                    return;
                }
                reported[0] = done;
                double value = expected > 0 ? 0.08 + 0.5 * Math.min(1.0, done / (double) expected) : 0.2;
                String detail = "Java " + major + "  " + mb(done)
                        + (expected > 0 ? " / " + mb(expected) : "");
                progress.accept(new InstallProgress(InstallProgress.Stage.JAVA, value, detail));
            });
        }

        verifyChecksum(archive, pkg.sha256());
        progress.accept(new InstallProgress(InstallProgress.Stage.JAVA, 0.6, "Java " + major));

        Path extracted = extract(archive, target);
        int detected = detectMajorVersion(extracted);
        String version = releaseVersion(extracted);
        if (version.isBlank()) {
            version = String.valueOf(detected > 0 ? detected : major);
        }
        progress.accept(new InstallProgress(InstallProgress.Stage.JAVA, 0.98, "Java " + major));
        SafeLog.info("已安装 Java " + (detected > 0 ? detected : major)
                + "（" + pkg.label() + "）到 jdk/" + major);
        return new JdkInfo(extracted, version,
                detected > 0 ? detected : major, true, detectBits(extracted));
    }

    /** 一个可下载的 Java 安装包：来源标签 + 候选地址 + SHA-256 + 大小（-1 = 未知）。 */
    private record JavaPackage(String label, String fileName, List<URI> urls,
                               String sha256, long size) {
        private JavaPackage {
            urls = List.copyOf(urls);
            if (urls.isEmpty()) {
                throw new IllegalArgumentException("下载任务缺少地址");
            }
        }
    }

    private static String fileNameOf(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String oneLine(String message) {
        if (message == null || message.isBlank()) {
            return "未知错误";
        }
        String text = message.replace('\r', ' ').replace('\n', ' ').trim();
        return text.length() > 160 ? text.substring(0, 160) + "…" : text;
    }

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }

    private static boolean matches(Path file, String expectedSha256) {
        if (expectedSha256 == null || expectedSha256.isBlank()) {
            return false;
        }
        try {
            return expectedSha256.equalsIgnoreCase(HashUtils.hash(file, "SHA-256"));
        } catch (IOException e) {
            return false;
        }
    }

    private static void verifyChecksum(Path archive, String expectedSha256) throws IOException {
        if (expectedSha256 == null || expectedSha256.isBlank()) {
            // 缺校验值就不装：宁可换来源／报错，也不静默安装（M-07 同一条红线）。
            Files.deleteIfExists(archive);
            throw new IOException("下载包没有可用的 SHA-256，已丢弃");
        }
        String actual = HashUtils.hash(archive, "SHA-256");
        if (!expectedSha256.equalsIgnoreCase(actual)) {
            Files.deleteIfExists(archive);
            throw new IOException("Java 安装包 SHA-256 校验失败");
        }
    }

    /** 从 {@code release} 读 {@code JAVA_VERSION}（给界面显示用）；读不到回传空字串。 */
    private static String releaseVersion(Path home) {
        Path release = home.resolve("release");
        if (Files.isRegularFile(release)) {
            try {
                for (String line : Files.readAllLines(release)) {
                    if (line.startsWith("JAVA_VERSION=")) {
                        String value = line.substring("JAVA_VERSION=".length())
                                .replace("\"", "").trim();
                        if (!value.isBlank()) {
                            return value;
                        }
                    }
                }
            } catch (IOException ignored) {
                // 退回主版本号
            }
        }
        return "";
    }

    /** 解压并“剥掉”压缩包里的顶层目录，保证 {@code <target>/bin/java.exe} 存在。 */
    private static Path extract(Path archive, Path target) throws IOException {
        Path temp = target.getParent().resolve(target.getFileName() + ".extracting");
        deleteRecursively(temp);
        Files.createDirectories(temp);
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path resolved = temp.resolve(entry.getName()).normalize();
                if (!resolved.startsWith(temp)) {
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                } else {
                    Files.createDirectories(resolved.getParent());
                    try (var out = Files.newOutputStream(resolved)) {
                        zip.transferTo(out);
                    }
                }
            }
        }

        Path home = temp;
        try (Stream<Path> stream = Files.list(temp)) {
            List<Path> children = stream.toList();
            if (children.size() == 1 && Files.isDirectory(children.get(0))) {
                home = children.get(0);
            }
        }

        deleteRecursively(target);
        try {
            Files.move(home, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.createDirectories(target);
            Files.move(home, target, StandardCopyOption.REPLACE_EXISTING);
        }
        if (home != temp) {
            deleteRecursively(temp);
        }
        return target;
    }

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (IOException ignored) {
                    // 尽力而为
                }
            });
        } catch (IOException ignored) {
            // 尽力而为
        }
    }

    // ---------- 版本探测 ----------

    /**
     * 从 {@code release} 文件读 JAVA_VERSION；没有就解析目录名。
     * 解析失败返回 -1，由上层决定是否忽略该 JDK。
     */
    int detectMajorVersion(Path home) {
        Path release = home.resolve("release");
        if (Files.isRegularFile(release)) {
            try {
                for (String line : Files.readAllLines(release)) {
                    if (line.startsWith("JAVA_VERSION=")) {
                        String raw = line.substring("JAVA_VERSION=".length()).replace("\"", "").trim();
                        String[] parts = raw.split("\\.");
                        int major = Integer.parseInt(parts[0]);
                        if (major == 1 && parts.length > 1) {
                            return Integer.parseInt(parts[1]);
                        }
                        return major;
                    }
                }
            } catch (IOException | NumberFormatException ignored) {
                // 退回目录名解析
            }
        }
        return majorFromFolderName(home);
    }

    /** 32/64 位判定：优先 release 里的 OS_ARCH，其次目录名，最后按 64 位处理。 */
    int detectBits(Path home) {
        Path release = home.resolve("release");
        if (Files.isRegularFile(release)) {
            try {
                for (String line : Files.readAllLines(release)) {
                    if (line.startsWith("OS_ARCH=")) {
                        String raw = line.substring("OS_ARCH=".length()).replace("\"", "")
                                .trim().toLowerCase(Locale.ROOT);
                        return raw.contains("64") || raw.contains("aarch64") ? 64 : 32;
                    }
                }
            } catch (IOException ignored) {
                // 退回目录名判断
            }
        }
        String text = home.toString().toLowerCase(Locale.ROOT);
        if (text.contains("(x86)") || text.contains("x86\\") || text.contains("i386")) {
            return 32;
        }
        return 64;
    }

    private static int majorFromFolderName(Path home) {
        Path name = home.getFileName();
        if (name == null) {
            return -1;
        }
        String text = name.toString().toLowerCase(Locale.ROOT);
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?:jdk|jre|java|temurin)[-_]?(?:1\\.)?(\\d+)")
                .matcher(text);
        if (matcher.find()) {
            try {
                int major = Integer.parseInt(matcher.group(1));
                return major == 1 ? 8 : major;
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    /** 目录本身是 Java home，或只有一个子目录是 Java home。 */
    private static Optional<Path> resolveHome(Path dir) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String exe = windows ? "java.exe" : "java";
        if (Files.isRegularFile(dir.resolve("bin").resolve(exe))) {
            return Optional.of(dir);
        }
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isDirectory)
                    .filter(child -> Files.isRegularFile(child.resolve("bin").resolve(exe)))
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<Path> envHome(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return resolveHome(Path.of(value));
    }
}
