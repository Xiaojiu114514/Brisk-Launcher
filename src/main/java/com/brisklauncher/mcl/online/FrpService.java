package com.brisklauncher.mcl.online;

import com.brisklauncher.mcl.AppPaths;
import com.brisklauncher.mcl.i18n.I18n;
import com.brisklauncher.mcl.security.FilePerms;
import com.brisklauncher.mcl.security.SafeLog;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自定义 FRP 隧道。
 *
 * <p>开发方案 4.9.2 要求 frpc 从 v0.52.0 起使用 {@code .toml} 配置，
 * 因此这里不再用命令行参数拼 INI，而是生成 toml 后执行 {@code frpc -c}。
 *
 * <p>第 192 轮起的行为：
 * <ul>
 *   <li>工作目录固定在 {@code <data>/online/frp}（与 {@code data/online/terracotta} 同一层；
 *       旧版在 {@code <data>/frp}，启动时会自动整包搬过去）；</li>
 *   <li>配置**持久化且自动读取**：进页面时扫描工作目录里的 {@code *.toml} / {@code *.ini}，
 *       依修改时间取新的（时间戳相同时 {@code frpc.toml} / {@code frpc.ini} 优先），解析后直接填回输入框；</li>
 *   <li>每次启动一条独立日志 {@code <data>/online/frp/logs/yyyy-MM-dd-HHmmss.log}；</li>
 *   <li>会话日誌同时留在内存缓冲（超过上限时从日誌档补齐），并用 {@code sessionId + 行号} 标记，
 *       页面重建后能整段还原「当前这次」的日誌，不会和上一次串在一起。</li>
 * </ul>
 */
public final class FrpService implements AutoCloseable {

    /** 日誌檔名：与启动器自身的日誌同一个习惯（{@code yyyy-MM-dd-HHmmss.log}）。 */
    private static final DateTimeFormatter LOG_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");
    private static final DateTimeFormatter LINE_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter HEADER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 内存里最多保留多少行（超过时 {@link #snapshot()} 从日誌档补齐，日誌檔本身不截断）。 */
    private static final int MAX_BUFFERED_LINES = 4000;
    /** 工作目录里的配置优先顺序；其它 {@code *.toml} / {@code *.ini} 按修改时间从新到旧。 */
    private static final List<String> CONFIG_FILE_NAMES = List.of("frpc.toml", "frpc.ini");
    /** toml 的 {@code key = value}（已去掉行首空白）。 */
    private static final Pattern TOML_ENTRY = Pattern.compile("^([A-Za-z0-9_.-]+)\\s*=\\s*(.*)$");
    private static final Pattern TOML_INT = Pattern.compile("^(-?\\d+)");

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

    /** 一行日誌：{@code sessionId} 讓 UI 分辨「這是不是當前會話」，{@code index} 讓 UI 只補沒顯示過的行。 */
    public record LogLine(long sessionId, int index, String text) {
    }

    /** 目前會話的日誌快照；頁面重建後用它整段還原。 */
    public record Snapshot(long sessionId, int baseIndex, List<String> lines) {

        public int nextIndex() {
            return baseIndex + lines.size();
        }
    }

    private final Path workDir = AppPaths.onlineDir().resolve("frp");
    /** 第 192 轮前的旧目录；只用于一次性搬迁与读取兜底。 */
    private final Path legacyWorkDir = AppPaths.dataDir().resolve("frp");
    private final Path logsDir = workDir.resolve("logs");

    private final Object logLock = new Object();
    /** 當前會話的日誌（只保留最近 {@link #MAX_BUFFERED_LINES} 行）。 */
    private final ArrayDeque<LogLine> sessionLines = new ArrayDeque<>();

    private volatile long sessionId;
    private volatile Process process;
    private volatile Thread logPump;
    private volatile Path sessionLogFile;
    private volatile Consumer<LogLine> logListener;
    private volatile boolean stopping;

    /** 這兩項只在 {@link #logLock} 內讀寫。 */
    private BufferedWriter logWriter;
    private int baseIndex;
    private int nextIndex;

    /**
     * 舊版把 FRP 工作目錄放在 {@code <data>/frp}；第 192 轮搬到 {@code <data>/online/frp}。
     * 只在新目錄不存在、舊目錄存在時整包搬一次（同一磁碟是 O(1)），避免玩家的
     * {@code frpc.exe} / 配置 / 日誌留在舊路徑。
     */
    private void migrateLegacyDir() {
        try {
            if (Files.exists(workDir) || !Files.isDirectory(legacyWorkDir)) {
                return;
            }
            Files.createDirectories(workDir.getParent());
            Files.move(legacyWorkDir, workDir);
            SafeLog.info("[frp] 已迁移旧目录：" + legacyWorkDir + " -> " + workDir);
        } catch (Exception error) {
            SafeLog.warn("[frp] 迁移旧 frp 目录失败（继续使用新目录）："
                    + error.getClass().getSimpleName());
        }
    }

    public Path workDir() {
        return workDir;
    }

    public Path logsDir() {
        return logsDir;
    }

    public Path configFile() {
        return workDir.resolve("frpc.toml");
    }

    /** 當前（或最近一次）會話的日誌檔；還沒啟動過任何會話時為 {@code null}。 */
    public Path logFile() {
        return sessionLogFile;
    }

    public long currentSessionId() {
        return sessionId;
    }

    /**
     * 目前會話的日誌快照（整段顯示用）。
     *
     * @param includeTrimmed {@code true} 時，若記憶體緩衝已截斷（超過上限），從當次日誌檔補齊；
     *                       {@code false} 只取記憶體緩衝 —— 頁面輪詢用，避免每 1.5 秒重讀大檔。
     */
    public Snapshot snapshot(boolean includeTrimmed) {
        synchronized (logLock) {
            List<String> texts = new ArrayList<>(sessionLines.size());
            for (LogLine line : sessionLines) {
                texts.add(line.text());
            }
            Path file = sessionLogFile;
            if (includeTrimmed && baseIndex > 0 && file != null) {
                try {
                    List<String> onDisk = Files.readAllLines(file, StandardCharsets.UTF_8);
                    // 檔案至少要有「已發出的行數」那麼多，才代表它比記憶體緩衝完整
                    if (onDisk.size() >= nextIndex) {
                        return new Snapshot(sessionId, 0, List.copyOf(onDisk));
                    }
                } catch (Exception ignored) {
                    // 讀不到就退回記憶體緩衝
                }
            }
            return new Snapshot(sessionId, baseIndex, List.copyOf(texts));
        }
    }

    /** 註冊「有新的日誌行」的回調（同一時間只有一個頁面在顯示）。 */
    public void setLogListener(Consumer<LogLine> listener) {
        this.logListener = listener;
    }

    /** 只在回調還是自己那一個時解除，避免新頁面的監聽被舊頁面清掉。 */
    public void clearLogListener(Consumer<LogLine> listener) {
        if (listener != null && logListener == listener) {
            logListener = null;
        }
    }

    public Path writeConfig(FrpConfig config) throws IOException {
        Files.createDirectories(workDir);
        Path file = configFile();
        Files.writeString(file, config.toToml(), StandardCharsets.UTF_8);
        // 安全審計 L-03：配置裡有明文 token —— 寫完立刻收緊權限。
        // 第 191 輪起不再「用完即刪」（要留著自動填入），靠 ACL + 本機才讀得到來降低風險。
        FilePerms.ensurePrivateFile(file);
        return file;
    }

    /**
     * 讀取工作目錄裡的配置（進頁面時填回輸入框）。
     *
     * <p>第 192 轮起不再只認 {@code frpc.toml}：資料夾內任何 {@code *.toml} / {@code *.ini}
     * 都會嘗試，依修改時間取新的（同時間戳時 {@code frpc.toml} / {@code frpc.ini} 優先），
     * 這樣玩家直接把服務商給的配置丟進資料夾就能自動填入。
     */
    public Optional<FrpConfig> loadSavedConfig() {
        for (Path file : configCandidates()) {
            try {
                Optional<FrpConfig> parsed = parseConfig(file.getFileName().toString(),
                        Files.readString(file, StandardCharsets.UTF_8));
                if (parsed.isPresent()) {
                    SafeLog.info("[frp] 已读取配置：" + file);
                    return parsed;
                }
            } catch (Exception error) {
                SafeLog.warn("[frp] 读取配置失败 " + file.getFileName() + "："
                        + error.getClass().getSimpleName());
            }
        }
        return Optional.empty();
    }

    /**
     * 工作目錄（含舊目錄兜底）裡的配置檔，依修改時間從新到舊；
     * 時間戳相同時 {@code frpc.toml} / {@code frpc.ini} 優先。
     */
    private List<Path> configCandidates() {
        List<Path> files = new ArrayList<>();
        addOtherConfigs(files, workDir);
        addOtherConfigs(files, legacyWorkDir);
        files.sort(Comparator.comparingLong(FrpService::lastModified).reversed()
                .thenComparingInt(FrpService::configRank));
        return files;
    }

    private static void addOtherConfigs(List<Path> files, Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var entries = Files.list(dir)) {
            entries.filter(Files::isRegularFile)
                    .filter(FrpService::looksLikeConfig)
                    .forEach(files::add);
        } catch (Exception error) {
            SafeLog.warn("[frp] 扫描配置目錄失败：" + error.getClass().getSimpleName());
        }
    }

    /** 時間戳相同時的排序：{@code frpc.toml} → {@code frpc.ini} → 其它檔名。 */
    private static int configRank(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int index = CONFIG_FILE_NAMES.indexOf(name);
        return index < 0 ? CONFIG_FILE_NAMES.size() : index;
    }

    private static boolean looksLikeConfig(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".toml") || name.endsWith(".ini");
    }

    private static long lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (Exception error) {
            return 0L;
        }
    }

    /**
     * 啟動時處理上次留下的配置：先把舊版目錄搬進 {@code data/online/frp}，再保留並收緊權限。
     *
     * <p>舊實作是「直接刪掉」（安全審計 L-03），但那樣每次開頁面都得重打伺服器與 token。
     * 現在改成**保留並收緊權限**：內容由玩家自己確認過（含手動丟進資料夾的配置），
     * 且 {@link #writeConfig} 每次寫入都會重設 ACL。
     */
    public void secureSavedConfig() {
        migrateLegacyDir();
        try {
            // 配置裡可能有明文 token（含玩家丟進來的 frpc.ini），全部收緊權限
            for (Path file : configCandidates()) {
                FilePerms.ensurePrivateFile(file);
            }
            Path main = configFile();
            if (Files.isRegularFile(main)) {
                SafeLog.info("[frp] 已保留既有配置：" + main);
            }
        } catch (Exception error) {
            SafeLog.warn("[frp] 检查既有配置失败：" + error.getClass().getSimpleName());
        }
    }

    /** 依副檔名解析配置：{@code .ini} 走 {@link #parseIni(String)}，其它走 {@link #parseToml(String)}。 */
    public static Optional<FrpConfig> parseConfig(String fileName, String text) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return name.endsWith(".ini") ? parseIni(text) : parseToml(text);
    }

    /**
     * 解析 frp 舊版 {@code .ini}（frp v0.51 及更早，多數服務商給的模板）。
     *
     * <p>只認 {@code [common]} 的 {@code server_addr / server_port / token} 與**第一組**
     * proxy 段的 {@code local_port / remote_port}；解析不出 {@code server_addr} 就當作沒有可用配置。
     */
    public static Optional<FrpConfig> parseIni(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String serverAddr = "";
        int serverPort = 0;
        String token = "";
        int localPort = 0;
        int remotePort = 0;
        boolean inProxy = false;
        boolean proxySeen = false;
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                String section = line.substring(1, line.length() - 1).trim().toLowerCase(Locale.ROOT);
                if ("common".equals(section)) {
                    inProxy = false;
                } else if (inProxy || proxySeen) {
                    // 只看第一組 proxy，後面的段不覆蓋前面的值
                    inProxy = false;
                } else {
                    inProxy = true;
                    proxySeen = true;
                }
                continue;
            }
            int equals = line.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = line.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            String value = stripIniComment(line.substring(equals + 1));
            switch (key) {
                case "server_addr" -> serverAddr = value;
                case "server_port" -> serverPort = tomlInt(value, serverPort);
                case "token", "auth_token" -> token = value;
                case "local_port" -> {
                    if (inProxy) {
                        localPort = tomlInt(value, localPort);
                    }
                }
                case "remote_port" -> {
                    if (inProxy) {
                        remotePort = tomlInt(value, remotePort);
                    }
                }
                default -> {
                    // type / local_ip / 其它鍵不影響輸入框
                }
            }
        }
        if (serverAddr.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new FrpConfig(serverAddr, serverPort, token, localPort, remotePort));
    }

    /** INI 的值：去掉行尾註解（只認空白後面的 {@code #} / {@code ;}）與成對引號。 */
    private static String stripIniComment(String raw) {
        String value = raw == null ? "" : raw.trim();
        for (int i = 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c == '#' || c == ';') && Character.isWhitespace(value.charAt(i - 1))) {
                value = value.substring(0, i).trim();
                break;
            }
        }
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' || first == '\'') && first == last) {
                value = value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    /**
     * 解析 {@code frpc.toml}（只認我們自己寫的那幾個鍵，容忍手改與註解）。
     *
     * <p>解析不出 {@code serverAddr} 就當作「沒有可用配置」，交回空值，
     * 免得把半截內容填進輸入框反而讓玩家誤判。
     */
    public static Optional<FrpConfig> parseToml(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String serverAddr = "";
        int serverPort = 0;
        String token = "";
        int localPort = 0;
        int remotePort = 0;
        boolean inProxies = false;
        boolean proxySeen = false;
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[")) {
                boolean proxies = line.replace(" ", "").equalsIgnoreCase("[[proxies]]");
                if (proxies && proxySeen) {
                    // 只看第一組 [[proxies]]，後面的不覆蓋前面的值
                    break;
                }
                inProxies = proxies;
                proxySeen |= proxies;
                continue;
            }
            Matcher entry = TOML_ENTRY.matcher(line);
            if (!entry.matches()) {
                continue;
            }
            String key = entry.group(1);
            String value = entry.group(2);
            switch (key) {
                case "serverAddr" -> serverAddr = tomlString(value);
                case "serverPort" -> serverPort = tomlInt(value, serverPort);
                case "auth.token", "token" -> token = tomlString(value);
                case "localPort" -> {
                    if (inProxies) {
                        localPort = tomlInt(value, localPort);
                    }
                }
                case "remotePort" -> {
                    if (inProxies) {
                        remotePort = tomlInt(value, remotePort);
                    }
                }
                default -> {
                    // 其它鍵（log.*、type、localIP…）不影響輸入框
                }
            }
        }
        if (serverAddr.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new FrpConfig(serverAddr, serverPort, token, localPort, remotePort));
    }

    private static String tomlString(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.startsWith("\"")) {
            StringBuilder out = new StringBuilder();
            for (int i = 1; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '\\' && i + 1 < value.length()) {
                    char next = value.charAt(++i);
                    out.append(switch (next) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        default -> next;
                    });
                    continue;
                }
                if (c == '"') {
                    return out.toString();
                }
                out.append(c);
            }
            return out.toString();
        }
        if (value.startsWith("'")) {
            int end = value.indexOf('\'', 1);
            return end > 0 ? value.substring(1, end) : value.substring(1);
        }
        int comment = value.indexOf('#');
        return (comment >= 0 ? value.substring(0, comment) : value).trim();
    }

    private static int tomlInt(String raw, int fallback) {
        Matcher matcher = TOML_INT.matcher(raw == null ? "" : raw.trim());
        if (!matcher.find()) {
            return fallback;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    /** 內置 frpc.exe 釋放到 {@code <data>/online/frp/frpc.exe}；也可用設置裡的自訂路徑。 */
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

    public synchronized Process start(FrpConfig config, String customPath) throws IOException {
        if (isRunning()) {
            throw new IOException("FRP 隧道已经在运行");
        }
        Path executable = resolveExecutable(customPath);
        Path toml = writeConfig(config);
        beginSession(config);

        ProcessBuilder builder = new ProcessBuilder(executable.toString(), "-c", toml.toString())
                .directory(workDir.toFile())
                .redirectErrorStream(true);
        Process started;
        try {
            started = builder.start();
        } catch (IOException error) {
            emit(I18n.t("online.frp.log.start_err") + ": " + SafeLog.sanitize(String.valueOf(error.getMessage())));
            closeSession();
            throw error;
        }
        this.process = started;
        emit(I18n.t("online.frp.log.started", started.pid()));
        logPump = Thread.ofVirtual().name("mcl-frp-log").start(() -> pump(started));
        SafeLog.info("FRP 隧道已启动（PID " + started.pid() + "）");
        Path log = sessionLogFile;
        if (log != null) {
            SafeLog.info("FRP 日誌：" + log);
        }
        return started;
    }

    private void pump(Process started) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                emit(line);
            }
        } catch (IOException error) {
            if (!stopping) {
                emit(I18n.t("online.frp.log.read_err"));
            }
        }
        int code = -1;
        try {
            code = started.waitFor();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        synchronized (this) {
            if (this.process == started) {
                this.process = null;
            }
        }
        // 玩家按「停止」時 stop() 已經寫過「已停止」；這裡只補「自己結束」的情況
        if (!stopping) {
            emit(I18n.t("online.frp.log.exited", code));
            SafeLog.info("FRP 隧道已退出（退出码 " + code + "）");
        }
        closeSession();
    }

    public synchronized void stop() {
        Process current = process;
        if (current == null || !current.isAlive()) {
            // 已經自己結束了（pump 會寫「已退出」），這裡只收尾
            process = null;
            closeSession();
            return;
        }
        stopping = true;
        try {
            current.descendants().forEach(ProcessHandle::destroyForcibly);
            current.destroyForcibly();
            current.waitFor(3, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } finally {
            process = null;
        }
        emit(I18n.t("online.frp.log.stopped"));
        closeSession();
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

    // ---------- 日誌 ----------

    /** 開一條新的會話日誌檔，並把上一次的記憶體緩衝清掉。 */
    private void beginSession(FrpConfig config) {
        // 新會話「不是玩家手動停止的」；stop() 之後一直保持 true，避免 pump 再補一條「已退出」
        stopping = false;
        synchronized (logLock) {
            sessionId++;
            sessionLines.clear();
            baseIndex = 0;
            nextIndex = 0;
            closeWriter();
            sessionLogFile = null;
            try {
                Files.createDirectories(logsDir);
                Path file = newLogFile();
                logWriter = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
                sessionLogFile = file;
            } catch (IOException error) {
                SafeLog.warn("[frp] 建立日誌檔失敗（本次只顯示在介面）："
                        + error.getClass().getSimpleName());
            }
        }
        emit(I18n.t("online.frp.log.session", LocalDateTime.now().format(HEADER_TIME)));
        Path file = sessionLogFile;
        if (file != null) {
            emit(I18n.t("online.frp.log.file", file.toString()));
        }
        emit(I18n.t("online.frp.log.target", config.serverAddr() + ":" + config.serverPort(),
                config.localPort(), config.remotePort()));
    }

    /** {@code <data>/online/frp/logs/yyyy-MM-dd-HHmmss.log}；同一秒重複啟動時加序號，不覆蓋。 */
    private Path newLogFile() {
        String stamp = LocalDateTime.now().format(LOG_STAMP);
        Path candidate = logsDir.resolve(stamp + ".log");
        for (int i = 2; Files.exists(candidate) && i < 1000; i++) {
            candidate = logsDir.resolve(stamp + "-" + i + ".log");
        }
        return candidate;
    }

    /** 一行日誌：寫記憶體緩衝 + 寫檔 + 通知 UI（檔案與介面永遠同一份內容）。 */
    private void emit(String raw) {
        LogLine line;
        synchronized (logLock) {
            String text = "[" + LocalTime.now().format(LINE_TIME) + "] " + SafeLog.sanitize(raw);
            line = new LogLine(sessionId, nextIndex++, text);
            sessionLines.addLast(line);
            while (sessionLines.size() > MAX_BUFFERED_LINES) {
                LogLine dropped = sessionLines.removeFirst();
                baseIndex = dropped.index() + 1;
            }
            BufferedWriter writer = logWriter;
            if (writer != null) {
                try {
                    writer.write(text);
                    writer.newLine();
                    writer.flush();
                } catch (IOException error) {
                    closeWriter();
                }
            }
        }
        Consumer<LogLine> listener = logListener;
        if (listener != null) {
            try {
                listener.accept(line);
            } catch (Throwable ignored) {
                // 頁面已被釋放：日誌仍在緩衝與檔案裡，重新進頁面會整段還原
            }
        }
    }

    private void closeSession() {
        synchronized (logLock) {
            closeWriter();
        }
    }

    /** 呼叫端必須持有 {@link #logLock}。 */
    private void closeWriter() {
        BufferedWriter writer = logWriter;
        logWriter = null;
        if (writer == null) {
            return;
        }
        try {
            writer.flush();
        } catch (IOException ignored) {
            // 進程被強殺時串流已關
        }
        try {
            writer.close();
        } catch (IOException ignored) {
            // 同上
        }
    }

    /** 本地端口是否已被監聽（判定「遊戲是否已開局域網」）。 */
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
