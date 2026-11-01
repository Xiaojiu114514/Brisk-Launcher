package com.brisklauncher.mcl.java;

import com.brisklauncher.mcl.AppPaths;
import com.brisklauncher.mcl.download.DownloadTask;
import com.brisklauncher.mcl.download.HashUtils;
import com.brisklauncher.mcl.download.HttpDownloader;
import com.brisklauncher.mcl.download.MirrorResolver;
import com.brisklauncher.mcl.game.install.InstallException;
import com.brisklauncher.mcl.game.install.InstallProgress;
import com.brisklauncher.mcl.game.launch.LaunchException;
import com.brisklauncher.mcl.net.Http;
import com.brisklauncher.mcl.download.remote.AdoptiumApi;
import com.brisklauncher.mcl.security.SafeLog;

import java.io.IOException;
import java.io.InputStream;
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
 * Java 运行时管理：扫描系统 JDK、管理托管目录、缺失时自动下载 Temurin。
 *
 * <p>扫描顺序与 HMCL 类似：{@code JAVA_HOME} → 启动器自带 → 系统常见安装目录。
 * 版本判定优先读 {@code release} 文件，失败再退回目录名解析。
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

        // 启动器托管的 JRE 优先
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
     * 确保存在指定主版本的 Java；不存在则从 Adoptium 下载到 {@code <launcher>/jdk/}。
     */
    public JdkInfo ensureFor(int major, HttpDownloader downloader, MirrorResolver mirrors,
                             Consumer<InstallProgress> progress) {
        Optional<JdkInfo> existing = findBest(major);
        if (existing.isPresent()) {
            return existing.get();
        }
        progress.accept(new InstallProgress(InstallProgress.Stage.JAVA, 0.02, "Java " + major));
        AdoptiumApi.JreAsset asset = adoptium.latest(major);

        Path cacheDir = AppPaths.cacheDir().resolve("java");
        Path archive = cacheDir.resolve(asset.releaseName() + ".zip");
        Path target = jdkRoot.resolve(String.valueOf(major));

        try {
            Files.createDirectories(cacheDir);
            DownloadTask task = new DownloadTask(mirrors.resolve(asset.link().toString()),
                    archive, null, asset.size());
            downloader.downloadOne(task);
            verifyChecksum(archive, asset.checksum());

            progress.accept(new InstallProgress(InstallProgress.Stage.JAVA, 0.6, "Java " + major));
            Path extracted = extract(archive, target);
            int detected = detectMajorVersion(extracted);
            progress.accept(new InstallProgress(InstallProgress.Stage.JAVA, 0.98, "Java " + major));
            SafeLog.info("已安装 Java " + (detected > 0 ? detected : major) + " 到 jdk/" + major);
            return new JdkInfo(extracted, asset.version(), detected > 0 ? detected : major, true,
                    detectBits(extracted));
        } catch (IOException e) {
            throw new LaunchException("下载 Java 失败：" + e.getMessage(), e);
        }
    }

    private static void verifyChecksum(Path archive, String expectedSha256) throws IOException {
        if (expectedSha256 == null || expectedSha256.isBlank()) {
            return;
        }
        String actual = HashUtils.hash(archive, "SHA-256");
        if (!expectedSha256.equalsIgnoreCase(actual)) {
            Files.deleteIfExists(archive);
            throw new IOException("Java 安装包 SHA-256 校验失败");
        }
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

    private static int majorFromFolderName(Path home) {        Path name = home.getFileName();
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
