package com.brisklauncher.mcl.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.brisklauncher.mcl.AppPaths;
import com.brisklauncher.mcl.security.SafeLog;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Terracotta（陶瓦联机）进程与本地 HTTP API。
 *
 * <p>Terracotta 是独立二进制，启动参数 {@code --hmcl <port-file>} 会在不打开浏览器的情况下
 * 把本地 HTTP 端口写进文件；随后通过 {@code /state} 系列接口创建/加入房间。房间码
 * （{@code U/XXXX-XXXX-XXXX-XXXX}）是协议级共享的，因此和同样使用 Terracotta 的 HMCL
 * 玩家可以互相加入，只要 Terracotta 版本协议兼容。
 *
 * <p>我们只通过它公开的进程间 HTTP API 交互，并在 UI 中标注 Terracotta 版权，
 * 符合其 AGPL 例外条款。
 */
public final class TerracottaService {

    public static final String VERSION_HINT = "v0.4+";

    public record Profile(String name, String vendor, String kind) {
    }

    public record State(String state, String room, String url, String difficulty,
                        int exceptionType, List<Profile> profiles) {

        public boolean hostOk() {
            return "host-ok".equals(state);
        }

        public boolean guestOk() {
            return "guest-ok".equals(state);
        }

        public boolean exception() {
            return "exception".equals(state);
        }

        public List<String> names() {
            List<String> result = new ArrayList<>();
            for (Profile profile : profiles) {
                result.add(profile.name());
            }
            return result;
        }

        public static State waiting() {
            return new State("waiting", "", "", "", -1, List.of());
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile int port;
    private volatile boolean starting;

    public Path directory() {
        return AppPaths.onlineDir().resolve("terracotta");
    }

    public Path logFile() {
        return directory().resolve("terracotta.log");
    }

    public Optional<Path> findBinary() {
        Path configured = directory().resolve("terracotta.path");
        if (Files.isRegularFile(configured)) {
            try {
                String value = Files.readString(configured, StandardCharsets.UTF_8).trim();
                if (!value.isEmpty()) {
                    Path path = Path.of(value);
                    if (!path.isAbsolute()) {
                        path = directory().resolve(value);
                    }
                    if (Files.isRegularFile(path)) {
                        return Optional.of(path);
                    }
                }
            } catch (Exception ignored) {
                // 继续自动探测
            }
        }
        Path bundled = ensureBundled();
        if (bundled != null) {
            return Optional.of(bundled);
        }
        List<Path> dirs = new ArrayList<>();
        dirs.add(directory());
        Path appDir = AppPaths.appDir();
        if (appDir != null) {
            Path besideLauncher = appDir.resolve("online").resolve("terracotta");
            if (!besideLauncher.equals(directory())) {
                dirs.add(besideLauncher);
            }
        }
        for (Path dir : dirs) {
            Path configuredPath = dir.resolve("terracotta.path");
            if (Files.isRegularFile(configuredPath)) {
                try {
                    String value = Files.readString(configuredPath, StandardCharsets.UTF_8).trim();
                    if (!value.isEmpty()) {
                        Path path = Path.of(value);
                        if (!path.isAbsolute()) {
                            path = dir.resolve(value);
                        }
                        if (Files.isRegularFile(path)) {
                            return Optional.of(path);
                        }
                    }
                } catch (Exception ignored) {
                    // 继续自动探测
                }
            }
            for (String name : List.of("terracotta.exe", "terracotta")) {
                Path candidate = dir.resolve(name);
                if (Files.isRegularFile(candidate)) {
                    return Optional.of(candidate);
                }
            }
            try (Stream<Path> stream = Files.list(dir)) {
                Optional<Path> found = stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                                .endsWith(".exe"))
                        .sorted()
                        .findFirst();
                if (found.isPresent()) {
                    return found;
                }
            } catch (IOException ignored) {
                // 目录不存在
            }
        }
        return Optional.empty();
    }

    public void setBinary(Path binary) {
        try {
            Files.createDirectories(directory());
            Files.writeString(directory().resolve("terracotta.path"),
                    binary.toAbsolutePath().normalize().toString(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            SafeLog.warn("保存 Terracotta 路径失败");
        }
    }

    /**
     * 安装包内置的 Terracotta + VC 运行库释放到数据目录。
     * 这样用户不需要自己准备 exe，也不会去写 Program Files / C 盘。
     */
    private Path ensureBundled() {
        try {
            Path dir = directory();
            Files.createDirectories(dir);
            Path target = dir.resolve("terracotta.exe");
            if (!Files.isRegularFile(target) || Files.size(target) < 1_000_000) {
                copyResource("/bin/terracotta-0.4.2-windows-x86_64.exe", target);
            }
            Path vc = dir.resolve("VCRUNTIME140.dll");
            if (!Files.isRegularFile(vc) || Files.size(vc) < 90_000) {
                copyResource("/bin/VCRUNTIME140.DLL", vc);
            }
            return Files.isRegularFile(target) ? target : null;
        } catch (Exception error) {
            SafeLog.warn("释放内置 Terracotta 失败：" + error.getMessage());
            return null;
        }
    }

    private void copyResource(String resource, Path target) throws IOException {
        try (var in = getClass().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("安装包缺少资源 " + resource);
            }
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public synchronized int ensureStarted() throws IOException, InterruptedException {
        if (port > 0 && ping()) {
            return port;
        }
        if (starting) {
            // 另一个线程正在启动，等它把端口写好
            for (int i = 0; i < 100 && port == 0; i++) {
                Thread.sleep(100);
            }
            if (port > 0) {
                return port;
            }
            throw new IOException("Terracotta 正在启动，请稍后重试");
        }
        Path binary = findBinary().orElseThrow(() -> new IOException(
                "未找到 Terracotta 程序。请下载后放到：" + directory()));
        Path dir = directory();
        Files.createDirectories(dir);
        Path temp = dir.resolve("tmp");
        Files.createDirectories(temp);
        Path portFile = dir.resolve("port.json");
        Files.deleteIfExists(portFile);

        starting = true;
        try {
            ProcessBuilder builder = new ProcessBuilder(binary.toString(), "--hmcl", portFile.toString());
            builder.directory(dir.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile().toFile()));
            // Terracotta 默认把 machine-id/日志写到 %TEMP%\terracotta；改到启动器数据目录，避免写 C 盘
            builder.environment().put("TMP", temp.toString());
            builder.environment().put("TEMP", temp.toString());
            builder.environment().put("TMPDIR", temp.toString());
            Process process = builder.start();

            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            JsonNode portNode = null;
            while (System.nanoTime() < deadline) {
                if (Files.isRegularFile(portFile)) {
                    try {
                        String text = Files.readString(portFile, StandardCharsets.UTF_8);
                        JsonNode node = mapper.readTree(text);
                        if (node.has("port") && node.get("port").asInt() > 0) {
                            portNode = node;
                            break;
                        }
                    } catch (Exception ignored) {
                        // 文件可能正在写，稍后再读
                    }
                }
                if (!process.isAlive() && process.exitValue() != 0) {
                    break;
                }
                Thread.sleep(120);
            }
            if (portNode == null) {
                throw new IOException("Terracotta 启动超时。请查看日志：" + logFile());
            }
            port = portNode.get("port").asInt();
            // 等 HTTP 真正可用
            for (int i = 0; i < 50 && !ping(); i++) {
                Thread.sleep(100);
            }
            if (!ping()) {
                throw new IOException("Terracotta 已启动但 HTTP API 不可用，端口 " + port);
            }
            SafeLog.info("Terracotta 已启动，端口 " + port);
            return port;
        } finally {
            starting = false;
        }
    }

    public boolean isRunning() {
        return port > 0 && ping();
    }

    public int port() {
        return port;
    }

    public State state() throws IOException, InterruptedException {
        if (port <= 0) {
            return State.waiting();
        }
        return parseState(get("/state"));
    }

    public void setWaiting() throws IOException, InterruptedException {
        ensureStarted();
        get("/state/ide");
    }

    public void host(String room, String player) throws IOException, InterruptedException {
        ensureStarted();
        String query = "?room=" + encode(room == null ? "" : room)
                + "&player=" + encode(player == null ? "" : player);
        get("/state/scanning" + query);
    }

    public void guest(String room, String player) throws IOException, InterruptedException {
        ensureStarted();
        String query = "?room=" + encode(room == null ? "" : room)
                + "&player=" + encode(player == null ? "" : player);
        get("/state/guesting" + query);
    }

    public void stop() {
        int current = port;
        if (current > 0) {
            try {
                get("/panic?peaceful=true");
            } catch (Exception ignored) {
                // 进程可能已经退出
            }
        }
        port = 0;
    }

    public Path portFile() {
        return directory().resolve("port.json");
    }

    // ---------- HTTP ----------

    private boolean ping() {
        try {
            return !get("/meta").isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private String get(String path) throws IOException, InterruptedException {
        int current = port;
        if (current <= 0) {
            throw new IOException("Terracotta 未启动");
        }
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + current + path))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Terracotta HTTP " + response.statusCode());
        }
        return response.body() == null ? "" : response.body();
    }

    private State parseState(String body) throws IOException {
        JsonNode node = mapper.readTree(body);
        List<Profile> profiles = new ArrayList<>();
        JsonNode array = node.path("profiles");
        if (array.isArray()) {
            for (JsonNode item : array) {
                profiles.add(new Profile(item.path("name").asText(""),
                        item.path("vendor").asText(""),
                        item.path("kind").asText("")));
            }
        }
        return new State(node.path("state").asText("waiting"),
                node.path("room").asText(""),
                node.path("url").asText(""),
                node.path("difficulty").asText(""),
                node.path("type").asInt(-1),
                profiles);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
