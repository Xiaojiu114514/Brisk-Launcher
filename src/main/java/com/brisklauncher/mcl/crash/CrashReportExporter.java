package com.brisklauncher.mcl.crash;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.brisklauncher.mcl.AppPaths;
import com.brisklauncher.mcl.game.GameInstance;
import com.brisklauncher.mcl.game.InstanceSettings;
import com.brisklauncher.mcl.game.InstanceSettingsStore;
import com.brisklauncher.mcl.net.Http;
import com.brisklauncher.mcl.security.SafeLog;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 崩溃日志导出 ZIP（开发方案 4.10）。
 *
 * <p>内容：launcher-session.log、game-latest.log、crash-report.txt、hs_err_pid*.log、
 * instance-meta.json、analysis-result.json。只保留最近 10 份，绝不上传。
 */
public final class CrashReportExporter {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int KEEP = 10;

    private final ObjectMapper mapper = Http.mapper();
    private final InstanceSettingsStore settingsStore = new InstanceSettingsStore();

    public Path export(GameInstance entry, CrashAnalysis analysis) throws IOException {
        Path exportDir = AppPaths.logsDir().resolve("exports");
        Files.createDirectories(exportDir);
        Path target = exportDir.resolve("crash-" + sanitize(entry.id()) + "-"
                + LocalDateTime.now().format(TIME) + ".zip");

        try (OutputStream out = Files.newOutputStream(target);
             ZipOutputStream zip = new ZipOutputStream(out)) {

            addFile(zip, latestSessionLog(), "launcher-session.log");
            addFile(zip, entry.gameDir().resolve("logs").resolve("latest.log"), "game-latest.log");
            addFile(zip, entry.gameDir().resolve("logs").resolve("debug.log"), "game-debug.log");
            addFile(zip, analysis.crashReport(), "crash-report.txt");
            if (analysis.crashReport() == null) {
                addFile(zip, newestCrashReport(entry.gameDir()), "crash-report.txt");
            }
            addFileNamed(zip, AppPaths.logsDir().resolve("last-launch.txt"), "launch-args.txt");
            addHsErrFiles(zip, entry);
            addJson(zip, "instance-meta.json", instanceMeta(entry));
            addJson(zip, "analysis-result.json", analysisJson(analysis));
        }

        pruneOldExports(exportDir);
        SafeLog.info("崩溃日志已导出：" + target.getFileName());
        return target;
    }

    // ---------- JSON ----------

    private ObjectNode instanceMeta(GameInstance entry) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", entry.id());
        // L6：崩溃包会被贴到公开论坛，路径含使用者名 → 只放相对/占位
        node.put("gameDir", "<gameDir>");
        node.put("root", "<root>");
        node.put("mcVersion", entry.mcVersion());
        node.put("loader", entry.loaderDisplay());
        node.put("javaMajor", entry.javaMajor());
        node.put("isolated", entry.isolated());
        try {
            InstanceSettings settings = settingsStore.load(entry, 4096);
            ObjectNode settingsNode = node.putObject("settings");
            settingsNode.put("memoryMb", settings.memoryMb());
            settingsNode.put("javaPath", settings.hasCustomJava() ? settings.javaPath() : "auto");
            ArrayNode args = settingsNode.putArray("jvmArgs");
            // P0-3：崩溃包会被上传到公开论坛，jvmArgs 可能含使用者路径、代理、token → 先脱敏
        settings.jvmArgs().forEach(arg -> args.add(
                com.brisklauncher.mcl.security.SafeLog.sanitize(arg)
                        .replaceAll("(?i)(token|secret|password|passwd|access[_-]?key)=[^\\s]+",
                                "$1=***")));
        } catch (Exception ignored) {
            // 设置读不到时不影响导出
        }
        return node;
    }

    private ObjectNode analysisJson(CrashAnalysis analysis) {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode matches = root.putArray("matches");
        for (CrashAnalysis.Match match : analysis.matches()) {
            ObjectNode node = matches.addObject();
            node.put("id", match.rule().id());
            node.put("category", match.rule().category());
            node.put("explanation", match.rule().explanation());
            node.put("suggestion", match.rule().suggestion());
            node.put("evidence", match.evidence());
        }
        ArrayNode suspects = root.putArray("suspectMods");
        analysis.suspectMods().forEach(suspects::add);
        root.put("logTail", analysis.logTail());
        return root;
    }

    // ---------- ZIP 工具 ----------

    /** 以指定名称写入（源文件名与包内名不同时用）；同样过一遍脱敏（M-01）。 */
    private static void addFileNamed(ZipOutputStream zip, Path file, String name) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        zip.putNextEntry(new ZipEntry(name));
        for (String line : Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8)) {
            zip.write((scrubLine(line) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        zip.closeEntry();
    }
    private void addJson(ZipOutputStream zip, String name, JsonNode node) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(node));
        zip.closeEntry();
    }

    /**
     * 把日志/崩溃报告打进 zip 时做**内容净化**（安全审计 M-01）。
     *
     * <p>以前是原样 {@code Files.copy}：{@code latest.log} 里有玩家名、
     * {@code Setting user: <玩家名>}、伺服器位址，甚至其他 mod 印出来的令牌；
     * 而这个 zip 是设计成要贴到公开论坛的。现在逐行过一遍
     * {@link #scrubLine(String)}：令牌样式打码、绝对使用者路径换成占位符。
     */
    private void addFile(ZipOutputStream zip, Path file, String entryName) {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            zip.putNextEntry(new ZipEntry(entryName));
            for (String line : Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8)) {
                zip.write((scrubLine(line) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            zip.closeEntry();
        } catch (IOException e) {
            SafeLog.warn("打包日志失败：" + entryName);
        }
    }

    /** 令牌樣式：JWT、Bearer、key=value 憑據，以及 {@code --accessToken <值>} 這種命令列形式。 */
    private static final java.util.regex.Pattern TOKEN_LIKE = java.util.regex.Pattern.compile(
            "(?i)(eyJ[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}"
                    + "|bearer\\s+\\S{8,}"
                    // 裸 token= / auth_access_token= 也要打码（审计 M-01 点名的漏网场景）
                    + "|(?:access[_-]?token|refresh[_-]?token|auth[_-]?token|token"
                    + "|client[_-]?secret|secret|password|passwd)"
                    + "\\s*[=:]\\s*\\S+"
                    // hs_err 的 java_command 行是「--accessToken <值>」（空格分隔），
                    // 上面的 [=:] 分支抓不到 —— 审计 v3 A-03 点名的就是这条。
                    + "|--(?:accessToken|authAccessToken|clientid|session|uuid)\\s+\\S+)");

    /** Windows 使用者目錄：{@code C:\Users\alice\...} → {@code <user>\...}。 */
    private static final java.util.regex.Pattern USER_PATH = java.util.regex.Pattern.compile(
            "(?i)([A-Za-z]:\\\\Users\\\\)[^\\\\\\s\"']+");

    /**
     * 玩家名（安全审计 v3 A-03 点名的那一段）。
     *
     * <p>崩溃包是要贴到公开论坛的，而日志里到处都是玩家名：
     * {@code Setting user: Steve}、{@code --username Steve}、{@code [Server thread/INFO]: <Steve> ...}。
     */
    private static final java.util.regex.Pattern PLAYER_NAME = java.util.regex.Pattern.compile(
            "(?i)((?:setting user|username|player name|name)[:=\\s]+['\"]?)([A-Za-z0-9_]{2,16})\\b");

    /** 聊天/日志里的 {@code <Steve> ...}（服务器与单人日志都这样记）。 */
    private static final java.util.regex.Pattern CHAT_NAME = java.util.regex.Pattern.compile(
            "<([A-Za-z0-9_]{2,16})>");

    private static String scrubLine(String line) {
        if (line == null || line.isEmpty()) {
            return "";
        }
        String result = TOKEN_LIKE.matcher(line).replaceAll("***");
        // 先处理 <Name>，再处理路径：否则 USER_PATH 插入的 <user> 会被 CHAT_NAME 再盖一层
        result = CHAT_NAME.matcher(result).replaceAll("<player>");
        result = USER_PATH.matcher(result).replaceAll("$1<user>");
        // 玩家名：保留字段名，只把值换掉（否则整行会变得看不懂）
        result = PLAYER_NAME.matcher(result).replaceAll("$1<player>");
        return result;
    }

    private void addHsErrFiles(ZipOutputStream zip, GameInstance entry) {
        List<Path> dirs = List.of(entry.gameDir(), entry.versionDir(), AppPaths.launcherRoot());
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(Files::isRegularFile)
                        .filter(file -> file.getFileName().toString().startsWith("hs_err_pid"))
                        // 安全审计 v3 A-03：hs_err 里带**完整命令行**（含 --accessToken）、
                        // 使用者名、以及 JVM 的 java_command 行。走 addFile 就会逐行 scrub。
                        .forEach(file -> addFile(zip, file, file.getFileName().toString()));
            } catch (IOException ignored) {
                // 目录不可读时跳过
            }
        }
    }

    private Path latestSessionLog() {
        Path logs = AppPaths.logsDir();
        if (!Files.isDirectory(logs)) {
            return null;
        }
        try (Stream<Path> stream = Files.list(logs)) {
            return stream.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().startsWith("session-"))
                    .max(Comparator.comparingLong(this::lastModified))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private Path newestCrashReport(Path gameDir) {
        Path dir = gameDir.resolve("crash-reports");
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .max(Comparator.comparingLong(this::lastModified))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private void pruneOldExports(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> zips = stream.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".zip"))
                    .sorted(Comparator.comparingLong(this::lastModified).reversed())
                    .toList();
            for (int i = KEEP; i < zips.size(); i++) {
                Files.deleteIfExists(zips.get(i));
            }
        } catch (IOException ignored) {
            // 清理失败不影响导出
        }
    }

    private long lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static String sanitize(String id) {
        return id.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
