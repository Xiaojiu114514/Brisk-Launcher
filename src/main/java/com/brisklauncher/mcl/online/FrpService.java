package com.brisklauncher.mcl.online;

import com.brisklauncher.mcl.AppPaths;
import com.brisklauncher.mcl.security.SafeLog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;

/**
 * 自定义 FRP 隧道。
 *
 * <p>开发方案 4.9.2 要求 frpc 从 v0.52.0 起使用 {@code .toml} 配置，
 * 因此这里不再用命令行参数拼 INI，而是生成临时 toml 后执行 {@code frpc -c}。
 */
public final class FrpService implements AutoCloseable {

    public record FrpConfig(String serverAddr, int serverPort, String token,
                            int localPort, int remotePort) {

        public FrpConfig {
            serverAddr = serverAddr == null ? "" : serverAddr.trim();
            token = token == null ? "" : token;
            if (serverPort <= 0) {
                serverPort = 7000;
            }
            if (localPort <= 0) {
                localPort = 25565;
            }
            if (remotePort <= 0) {
                remotePort = localPort;
            }
        }

        public String toToml() {
            String name = "mcl-" + localPort + "-" + remotePort;
            return """
                    serverAddr = "%s"
                    serverPort = %d
                    auth.method = "token"
                    auth.token = "%s"
                    log.level = "info"
                    log.to = "console"

                    [[proxies]]
                    name = "%s"
                    type = "tcp"
                    localIP = "127.0.0.1"
                    localPort = %d
                    remotePort = %d
                    """.formatted(escape(serverAddr), serverPort, escape(token), name, localPort, remotePort);
        }

        /** 寫進 TOML 的字符串：反斜線/引號轉義，並剝掉換行（換行可注入額外 [[proxies]]）。 */
    private static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        raw = raw.replace("\r", "").replace("\n", "");
            return raw.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    private final Path workDir = AppPaths.launcherRoot().resolve("frp");
    private volatile Process process;
    private volatile Thread logPump;

    public Path workDir() {
        return workDir;
    }

    public Path configFile() {
        return workDir.resolve("frpc.toml");
    }

    public Path writeConfig(FrpConfig config) throws IOException {
        Files.createDirectories(workDir);
        Path file = configFile();
        Files.writeString(file, config.toToml(), StandardCharsets.UTF_8);
        // 安全审计 L-03：配置里有明文 token —— 写完立刻收紧权限，
        // 并在启动时清掉上次崩溃/强杀留下的残留（见 cleanStaleConfig）。
        com.brisklauncher.mcl.security.FilePerms.ensurePrivateFile(file);
        return file;
    }

    /**
     * 清掉上次异常退出留下的 {@code frpc.toml}（里面有明文 token）。
     *
     * <p>正常 {@link #stop()} 会删，但崩溃、断电、任务管理器结束进程都删不掉。
     */
    public void cleanStaleConfig() {
        try {
            Path file = configFile();
            if (java.nio.file.Files.isRegularFile(file)) {
                java.nio.file.Files.deleteIfExists(file);
                SafeLog.info("[frp] 已清理上次残留的 frpc.toml");
            }
        } catch (Exception error) {
            SafeLog.warn("[frp] 清理残留配置失败：" + error.getClass().getSimpleName());
        }
    }

    /** 内置 frpc.exe 释放到 {@code <launcher>/frp/frpc.exe}；也可用设置里的自定义路径。 */
    public Path resolveExecutable(String customPath) throws IOException {
        if (customPath != null && !customPath.isBlank()) {
            Path custom = Path.of(customPath.trim());
            if (Files.isRegularFile(custom)) {
                return custom;
            }
            throw new IOException("设置的 frpc 路径不存在：" + custom);
        }
        Path bundled = workDir.resolve("frpc.exe");
        if (Files.isRegularFile(bundled) && Files.size(bundled) > 1_000_000) {
            return bundled;
        }
        Files.createDirectories(workDir);
        try (InputStream in = getClass().getResourceAsStream("/bin/frpc.exe")) {
            if (in == null) {
                throw new IOException("安装包内没有内置 frpc.exe，请在设置里指定 frpc 路径");
            }
            Files.copy(in, bundled, StandardCopyOption.REPLACE_EXISTING);
        }
        return bundled;
    }

    public synchronized Process start(FrpConfig config, String customPath, Consumer<String> onLine)
            throws IOException {
        if (isRunning()) {
            throw new IOException("FRP 隧道已经在运行");
        }
        Path executable = resolveExecutable(customPath);
        Path toml = writeConfig(config);

        ProcessBuilder builder = new ProcessBuilder(executable.toString(), "-c", toml.toString())
                .directory(workDir.toFile())
                .redirectErrorStream(true);
        Process started = builder.start();
        this.process = started;

        logPump = Thread.ofVirtual().name("mcl-frp-log").start(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (onLine != null) {
                        onLine.accept(SafeLog.sanitize(line));
                    }
                }
            } catch (IOException ignored) {
                // 进程退出时流关闭属正常
            }
        });
        SafeLog.info("FRP 隧道已启动（PID " + started.pid() + "）");
        return started;
    }

    public synchronized void stop() {
        // 安全审计 L1：配置里有明文 token，停止时必须删掉
        try {
            java.nio.file.Files.deleteIfExists(configFile());
        } catch (Exception ignored) {
            // 删不掉也不能让 stop 失败
        }
        Process current = process;
        if (current == null) {
            return;
        }
        current.descendants().forEach(ProcessHandle::destroyForcibly);
        current.destroyForcibly();
        try {
            current.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        process = null;
        SafeLog.info("FRP 隧道已停止");
    }

    public boolean isRunning() {
        Process current = process;
        return current != null && current.isAlive();
    }

    public long pid() {
        Process current = process;
        return current == null ? -1 : current.pid();
    }

    /** 本地端口是否已被监听（判定“游戏是否已开局域网”）。 */
    public static boolean isLocalPortOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void close() {
        stop();
    }
}
