package com.brisklauncher.mcl.crash;

import com.brisklauncher.mcl.security.SafeLog;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 启动时间线：从「启动器启动的那一瞬间」到「游戏崩溃」，逐条记下关键事件。
 *
 * <p>PCL 的崩溃分析给的是「原因 + 建议」，但有时候玩家更需要的是「事情是按什么顺序发生的」：
 * 是 Java 没起来？是文件校验失败？是加载到一半 Mod 炸了？是窗口出来之后显卡崩了？
 * 这条时间线把那条路径画出来（{@code ui/CrashTimelineView}）。
 *
 * <p>设计取舍：
 * <ul>
 *   <li>只保留**一次启动**的事件，新的启动会清空（避免两次启动混在一起）；</li>
 *   <li>启动器启动时刻单独记住，即使玩家开了十分钟才启动游戏，轴上也看得到这段空白；</li>
 *   <li>另外会从 {@code latest.log} 吸收「本次启动之后」的关键行（ERROR/异常/加载完成…），
 *       时间戳优先采用日志自己印的时间；</li>
 *   <li>容量上限 200 条、吸收日志上限 40 条，避免把几万行日志塞进界面。</li>
 * </ul>
 */
public final class LaunchTimeline {

    /** 事件級別（影響軸上圓點的顏色）。 */
    public enum Level {
        INFO, OK, WARN, ERROR
    }

    /** 一條事件。 */
    public record Event(long epochMillis, Level level, String title, String detail) {
    }

    private static final int MAX_EVENTS = 200;
    private static final int MAX_LOG_EVENTS = 40;
    private static final DateTimeFormatter HH_MM_SS =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 日誌行：[12:34:56] [Render thread/INFO]: ... */
    private static final Pattern LOG_LINE = Pattern.compile(
            "^\\[(\\d{2}:\\d{2}:\\d{2})\\] \\[([^\\]]+)\\]: ?(.*)$");

    /** 值得畫到軸上的日誌關鍵字（不區分大小寫）。 */
    private static final List<String> INTERESTING = List.of(
            "setting user", "backend library", "openal initialized", "sound engine started",
            "reloading resourcemanager", "created:", "loaded ", "loading ", "mod launcher",
            "fabric loader", "forge mod loading", "mixin",
            "exception", "error", "caused by", "crash", "failed", "unable to",
            "outofmemory", "unsatisfiedlink", "access violation", "stopping!");

    private static final List<Event> EVENTS = new ArrayList<>();
    private static long launcherStartMillis = System.currentTimeMillis();
    private static String instanceId = "";
    private static long launchStartedMillis;

    private LaunchTimeline() {
    }

    /** 啟動器剛起來時呼叫一次（只記錄，不再畫到軸上）。 */
    public static synchronized void launcherStarted() {
        launcherStartMillis = System.currentTimeMillis();
    }

    public static synchronized long launcherStartMillis() {
        return launcherStartMillis;
    }

    /** 這次啟動的開始時刻（沒有啟動過時回退到啟動器啟動時刻）。 */
    public static synchronized long launchStartedMillis() {
        return launchStartedMillis > 0 ? launchStartedMillis : launcherStartMillis;
    }

    public static synchronized String instanceId() {
        return instanceId;
    }

    /**
     * 开始一次新的启动。
     *
     * <p>轴的起点是「这个游戏版本开始启动」，不是启动器启动——
     * 玩家可能开著启动器很久才进游戏，那段空白对排查崩溃没有意义。
     */
    public static synchronized void beginLaunch(String versionId) {
        beginLaunchAt(versionId, System.currentTimeMillis(), System.currentTimeMillis());
    }

    /**
     * 指定起点时间的启动开始（开发期造时间线用）。
     *
     * @param launcherEpoch 保留参数（只记下来，不上轴）
     * @param launchEpoch   游戏版本开始启动的时刻（轴的起点）
     */
    public static synchronized void beginLaunchAt(String versionId, long launcherEpoch,
                                                  long launchEpoch) {
        EVENTS.clear();
        instanceId = versionId == null ? "" : versionId;
        launcherStartMillis = launcherEpoch;
        launchStartedMillis = launchEpoch;
        addAt(launchEpoch, Level.INFO, "开始启动 " + instanceId, null);
    }

    public static synchronized void add(Level level, String title) {
        add(level, title, null);
    }

    public static synchronized void add(Level level, String title, String detail) {
        if (title == null || title.isBlank()) {
            return;
        }
        // 连续重复的事件折叠掉（例如同一个阶段被回报两次）
        if (!EVENTS.isEmpty()) {
            Event last = EVENTS.get(EVENTS.size() - 1);
            if (last.title().equals(title)) {
                return;
            }
        }
        if (EVENTS.size() >= MAX_EVENTS) {
            EVENTS.remove(0); // 丢掉最旧的一条，保留最近的现场
        }
        EVENTS.add(new Event(System.currentTimeMillis(), level, title,
                detail == null || detail.isBlank() ? null : detail));
    }

    public static synchronized void info(String title, String detail) {
        add(Level.INFO, title, detail);
    }

    public static synchronized void ok(String title, String detail) {
        add(Level.OK, title, detail);
    }

    public static synchronized void warn(String title, String detail) {
        add(Level.WARN, title, detail);
    }

    public static synchronized void error(String title, String detail) {
        add(Level.ERROR, title, detail);
    }

    /** 指定時間的插入（開發期造時間線/回歸測試用，正常流程不呼叫）。 */
    public static synchronized void addAt(long epochMillis, Level level, String title, String detail) {
        EVENTS.add(new Event(epochMillis, level, title,
                detail == null || detail.isBlank() ? null : detail));
    }

    public static synchronized List<Event> events() {
        // 一律按时间排序：日志吸收进来的事件、以及偶发的时钟回跳，都不该让轴线顺序错乱
        List<Event> sorted = new ArrayList<>(EVENTS);
        sorted.sort(java.util.Comparator.comparingLong(Event::epochMillis));
        return List.copyOf(sorted);
    }

    public static synchronized void clear() {
        EVENTS.clear();
        launchStartedMillis = 0L;
        instanceId = "";
    }

    // ---------- 从日志吸收关键行 ----------

    /**
     * 把 {@code latest.log} 里「本次启动之后」的关键行并进时间线。
     *
     * <p>日志时间只有 {@code HH:mm:ss}，用档案最后修改日期补出完整时间；
     * 若补出来的时间比现在晚（跨午夜），就回退一天。
     */
    public static synchronized void absorbLog(Path logFile, long sinceMillis) {
        if (logFile == null || !Files.isRegularFile(logFile)) {
            return;
        }
        try {
            String text = readTail(logFile, 192 * 1024);
            long fileModified = Files.getLastModifiedTime(logFile).toMillis();
            LocalDate date = Instant.ofEpochMilli(fileModified)
                    .atZone(ZoneId.systemDefault()).toLocalDate();
            List<Event> found = new ArrayList<>();
            for (String line : text.split("\\R")) {
                Matcher matcher = LOG_LINE.matcher(line);
                if (!matcher.matches()) {
                    continue;
                }
                String rawTime = matcher.group(1);
                String source = matcher.group(2);
                String message = matcher.group(3);
                if (!interesting(source, message)) {
                    continue;
                }
                long epoch = toEpoch(date, rawTime);
                if (epoch > System.currentTimeMillis()) {
                    epoch = toEpoch(date.minusDays(1), rawTime);
                }
                if (sinceMillis > 0 && epoch + 2000L < sinceMillis) {
                    continue;
                }
                Level level = levelOf(source, message);
                found.add(new Event(epoch, level, shorten(message, 120), source));
            }
            if (found.isEmpty()) {
                return;
            }
            // 只取最后 N 条：最早的往往是启动讯息，最新的才接近崩溃点
            if (found.size() > MAX_LOG_EVENTS) {
                found = found.subList(found.size() - MAX_LOG_EVENTS, found.size());
            }
            // 并入后按时间排序（游戏日志事件的时间精度只有秒，但整体顺序仍然正确）
            List<Event> merged = new ArrayList<>(EVENTS);
            merged.addAll(found);
            merged.sort(java.util.Comparator.comparingLong(Event::epochMillis));
            EVENTS.clear();
            EVENTS.addAll(merged);
            if (EVENTS.size() > MAX_EVENTS) {
                EVENTS.subList(0, EVENTS.size() - MAX_EVENTS).clear();
            }
        } catch (Exception error) {
            SafeLog.warn("整理启动时间线失败：" + error.getClass().getSimpleName());
        }
    }

    private static boolean interesting(String source, String message) {
        String lower = (source + " " + message).toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("/error") || lower.contains("/fatal")) {
            return true;
        }
        for (String keyword : INTERESTING) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static Level levelOf(String source, String message) {
        String lower = (source + " " + message).toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("/fatal") || lower.contains("/error")
                || lower.contains("outofmemory") || lower.contains("access violation")) {
            return Level.ERROR;
        }
        if (lower.contains("exception") || lower.contains("failed")
                || lower.contains("unable to") || lower.contains("/warn")) {
            return Level.WARN;
        }
        return Level.INFO;
    }

    private static String messageLevel(String source) {
        int slash = source.lastIndexOf('/');
        return slash >= 0 ? source.substring(slash + 1) : source;
    }

    private static String shorten(String text, int max) {
        String clean = text.replace('\t', ' ').trim();
        return clean.length() <= max ? clean : clean.substring(0, max) + "…";
    }

    private static long toEpoch(LocalDate date, String hhmmss) {
        try {
            LocalTime time = LocalTime.parse(hhmmss);
            return date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception error) {
            return System.currentTimeMillis();
        }
    }

    private static String readTail(Path file, int maxBytes) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long length = raf.length();
            long from = Math.max(0, length - maxBytes);
            raf.seek(from);
            byte[] buffer = new byte[(int) (length - from)];
            raf.readFully(buffer);
            return new String(buffer, StandardCharsets.UTF_8);
        }
    }

    /** 給「複製到剪貼板」用的純文字版時間線。 */
    public static synchronized String toPlainText() {
        if (EVENTS.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        long base = launcherStartMillis;
        for (Event event : EVENTS) {
            sb.append(HH_MM_SS.format(Instant.ofEpochMilli(event.epochMillis())
                            .atZone(ZoneId.systemDefault())))
                    .append("  +")
                    .append(String.format(java.util.Locale.ROOT, "%.2fs", (event.epochMillis() - base) / 1000.0))
                    .append("  ")
                    .append(event.title());
            if (event.detail() != null) {
                sb.append("  [").append(event.detail()).append(']');
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
