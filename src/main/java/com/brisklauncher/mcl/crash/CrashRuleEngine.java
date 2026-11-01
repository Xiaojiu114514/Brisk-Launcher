package com.brisklauncher.mcl.crash;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.brisklauncher.mcl.AppPaths;
import com.brisklauncher.mcl.game.GameInstance;
import com.brisklauncher.mcl.net.Http;
import com.brisklauncher.mcl.security.SafeLog;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * 崩溃规则引擎：唯一分析入口，规则与 UI 解耦（开发方案 4.10 / 9）。
 *
 * <p>内置一套默认规则；若开启远程更新，则从 Raw 地址拉取 JSON 合并（失败静默回退，
 * 只下载规则、绝不上传日志）。分析只读本地日志，不联网。
 */
public final class CrashRuleEngine {

    /** 远程规则地址（可替换为自己的仓库；拉不到就只用内置规则）。 */
    public static final URI REMOTE_RULES = URI.create(
            "https://raw.githubusercontent.com/Xiaojiu114514/Brisk-Launcher/main/crash-rules.json");

    private static final int TAIL_BYTES = 256 * 1024;

    private static final List<CrashRule> DEFAULT_RULES = List.of(
            new CrashRule("OOM", "java\\.lang\\.OutOfMemoryError|GC overhead limit exceeded",
                    "memory", "内存不足（OutOfMemoryError）",
                    "在版本设置里提高最大内存（建议 4096 MB 以上），或减少同时加载的 Mod"),
            new CrashRule("UNSUPPORTED_CLASS", "UnsupportedClassVersionError",
                    "java", "Java 版本过低，无法加载该版本或 Mod",
                    "在版本设置里换用与游戏版本匹配的 Java（1.17+ 用 17，1.20.5+ 用 21）"),
            new CrashRule("MIXIN_CONFLICT", "Mixin apply failed|InvalidMixinException|MixinTransformerError",
                    "mod", "Mixin 注入失败，通常是 Mod 冲突或加载器版本不匹配",
                    "逐个禁用最近安装的 Mod；确认 Fabric/Forge 版本与 Mod 要求一致"),
            new CrashRule("NATIVE_LIB",
                    "Failed to load a library|Failed to load a native library|"
                    + "Could not initialize class org\\.lwjgl|UnsatisfiedLinkError|"
                    + "no lwjgl in java\\.library\\.path|GLFW error",
                    "files", "原生库（LWJGL / natives）加载失败",
                    "用启动器重新做一次文件校验补全（会重新解压 natives）；"
                    + "确认杀毒软件没有隔离 dll；确保使用 64 位 Java"),
            new CrashRule("MISSING_DEPENDENCY",
                    "NoSuchMethodError|NoClassDefFoundError|ClassNotFoundException",
                    "mod", "缺少依赖或依赖版本不匹配（NoClassDefFoundError / NoSuchMethodError）",
                    "检查 Mod 是否缺少前置（如 Fabric API、Architectury），并确认依赖版本"),
            new CrashRule("MISSING_MOD_DEP",
                    "Missing or unsupported mandatory dependencies"
                    + "|requires (?:any version of )?(?:mod )?[A-Za-z0-9_\\-]+"
                    + "|Incompatible mod set|ModResolutionException|FormattedException"
                    + "|Which is missing|is not installed|Depends on [A-Za-z0-9_\\-]+",
                    "mod", "缺少前置 Mod（或前置版本不对）",
                    "日志会写出缺少的 Mod 名；到「下载」页搜寻安装即可"
                    + "（Fabric 端常见 Fabric API / Architectury，Forge 端是对应的库 Mod）"),
            new CrashRule("DUPLICATE_MOD", "Duplicate mods found|duplicate mod",
                    "mod", "同一个 Mod 被安装了多个版本",
                    "删除 mods 目录中重复的旧版本，只保留一个"),
            new CrashRule("MOD_LOADING", "ModLoadingException|ModLoadingCrashException|Failed to create mod|"
                    + "The mod .* has failed to load|LoadErrors",
                    "mod", "Mod 加载阶段崩溃",
                    "查看崩溃报告里 FML 列出的具体 Mod 名，禁用该 Mod 后重试"),
            new CrashRule("OPTIFINE", "OptiFine|optifine",
                    "mod", "OptiFine 与当前加载器/其它优化 Mod 冲突",
                    "改用 Sodium/Embeddium 等替代优化 Mod，或只保留 OptiFine"),
            new CrashRule("ZIP_CORRUPT", "zip END header not found|invalid LOC header|ZipException|"
                    + "Unexpected end of ZLIB input stream",
                    "files", "jar/zip 文件损坏（常见于不完整下载）",
                    "在“版本”页对该实例执行“校验并补全文件”，启动器会重新下载损坏的文件"),
            new CrashRule("FILE_MISSING", "NoSuchFileException|FileNotFoundException",
                    "files", "缺少运行所需的文件",
                    "对实例执行“校验并补全文件”；若仍失败，重新安装该版本"),
            new CrashRule("GL_DRIVER", "Pixel format not accelerated|The driver does not appear to support|"
                    + "GLFW error|Failed to create window|WGL",
                    "graphics", "显卡驱动或 OpenGL 不受支持",
                    "更新显卡驱动；笔记本切换到独立显卡；必要时在游戏设置里改用窗口模式"),
            new CrashRule("JVM_FATAL", "# A fatal error has been detected by the Java Runtime Environment|"
                    + "EXCEPTION_ACCESS_VIOLATION|SIGSEGV",
                    "java", "Java 虚拟机崩溃（原生层错误）",
                    "更新/更换 Java 版本；检查是否安装了不兼容的原生库 Mod"),
            new CrashRule("AUTH_FAILED", "AuthenticationUnavailableException|InvalidCredentialsException|"
                    + "Failed to verify username",
                    "network", "认证服务器校验失败",
                    "检查网络；第三方账号请确认认证服务器地址正确且未过期"),
            new CrashRule("MEMORY_JVM", "Could not reserve enough space for .* object heap|"
                    + "Invalid maximum heap size",
                    "memory", "启动器设置的内存超出系统可用范围",
                    "降低最大内存，或安装 64 位 Java"),
            new CrashRule("NO_MAIN_CLASS", "Could not find or load main class|UnsupportedClassVersionError",
                    "files", "主类加载失败，实例文件不完整或 Java 不匹配",
                    "对该实例执行“校验并补全文件”，并检查 Java 版本"),
            new CrashRule("MOD_JAVA_VERSION", "requires Java|requires at least Java|"
                    + "This mod requires Java",
                    "java", "某个 Mod 要求更高的 Java 版本",
                    "按提示切换该实例的 Java 版本"),
            new CrashRule("JVM_ARGS", "Unrecognized VM option|Improperly specified VM option|"
                    + "Could not create the Java Virtual Machine|Invalid initial heap size",
                    "java", "启动参数里的 JVM 选项不被当前 Java 支持",
                    "打开版本设置 → Java 与内存，删掉不认识的 JVM 参数；或换用与版本匹配的 Java"),
            new CrashRule("DISK_FULL", "There is not enough space on the disk|No space left on device|"
                    + "not enough space|磁盘空间不足",
                    "files", "磁盘空间不足，文件写不进去",
                    "清理游戏目录所在磁盘（建议至少留 5 GB），或把大版本文件夹移到其它分区"),
            new CrashRule("PERMISSION", "AccessDeniedException|Access is denied|存取被拒|"
                    + "Permission denied|拒绝访问",
                    "files", "文件被占用或被安全软件拦截，写入失败",
                    "关掉正在运行的同类启动器/游戏；把游戏目录加入杀毒软件信任区；"
                    + "必要时用管理员身份运行，或把目录换到非系统盘"),
            new CrashRule("WORLD_CORRUPT", "Chunk file at|Region file|corrupted chunk|"
                    + "Chunk file .* is missing|Failed to load chunk|region/",
                    "world", "世界存档损坏或缺块",
                    "先备份 saves 目录；用存档修复工具修 region，或删掉损坏维度的 region 文件"
                    + "（会丢那个区域的地形，但世界还能进）"),
            new CrashRule("CONNECTION", "Connection refused|UnknownHostException|ConnectException|"
                    + "Connection reset|Failed to connect|connect timed out|SocketTimeoutException",
                    "network", "连不上认证服务器或游戏服务器",
                    "检查网络/代理设置；换 DNS（如 223.5.5.5）；第三方登录请确认认证服务器地址正确"),
            new CrashRule("TLS_CERT", "SSLHandshakeException|PKIX path building failed|"
                    + "CertPathValidatorException|certificate_unknown",
                    "network", "TLS 证书校验失败（系统时间不对或代理在做中间人）",
                    "先校准系统时间；关闭抓包/加速代理；确有必要时在设置里临时勾选跳过 TLS 校验"),
            new CrashRule("MOD_MC_VERSION", "requires minecraft|is not compatible with|"
                    + "requires minecraft version|Mod .* requires minecraft",
                    "mod", "Mod 与当前游戏版本不匹配",
                    "确认 Mod 支持的 MC 版本；加载器与游戏版本要对得上；移除不兼容的 Mod"),
            new CrashRule("LOADER_MISMATCH", "Incompatible mod set|Incompatible mods found|"
                    + "requires fabric loader|requires forge|requires neoforge|"
                    + "Unsupported mod|mod file .* requires",
                    "mod", "加载器版本与 Mod 要求不一致",
                    "按提示升级/降级 Fabric、Forge、NeoForge 或 Quilt；一次只改一项，别混装多个加载器"),
            new CrashRule("RESOURCE_PACK", "Failed to load resource pack|Unable to parse pack|"
                    + "pack\\.mcmeta|Resource pack .* failed|Couldn't load pack",
                    "resource", "资源包格式错误或与版本不匹配",
                    "移除最近加入的资源包（resourcepacks），尤其是低版本搬运的包"),
            new CrashRule("SHADER", "Failed to compile shader|Shader compilation|shadersmod|"
                    + "Iris|OptiFine shader",
                    "graphics", "光影包编译失败",
                    "换一个光影或降低画质选项；更新显卡驱动；确认 Iris/Sodium 版本与 MC 匹配"),
            new CrashRule("AUDIO", "Failed to open audio device|OpenAL|AL lib|alcOpenDevice|"
                    + "SoundSystem",
                    "audio", "音效设备打不开（多数只影响声音，不一定会崩）",
                    "检查耳机/音响与声音驱动；关掉独占音频的程序；必要时删掉 options.txt 里的音量设置重生成"),
            new CrashRule("JSON_CORRUPT", "JsonParseException|JsonSyntaxException|"
                    + "Unexpected character|MalformedJson|Unexpected end-of-input",
                    "files", "某个 JSON 文件格式损坏（版本 JSON / 配置 / 存档数据）",
                    "对实例做一次“校验并补全”；配置文件坏了先备份再删掉，让游戏重新生成"),
            new CrashRule("DEP_MISSING", "Missing or unsupported mandatory dependencies|"
                    + "mandatory dependency|missing dependency|which is missing",
                    "mod", "Mod 缺少必需的前置",
                    "在“内容管理 → 前置依赖”里按树状图补齐红色缺项；或重新用“连同前置安装”装一次"),
            new CrashRule("GL_VERSION", "requires OpenGL|OpenGL version|GLFW error 65542|"
                    + "GL 3\\.2|Requires OpenGL",
                    "graphics", "显卡/驱动不支持游戏要求的 OpenGL 版本",
                    "更新显卡驱动；确认没在用核显跑；老显卡可用兼容性更好的版本或第三方渲染后端")
    );

    private final List<CrashRule> rules = new ArrayList<>(DEFAULT_RULES);
    private final ObjectMapper mapper = Http.mapper();

    /** 規則上限（P1-2）：避免遠端規則被塞爆或塞入超長正則吃 CPU。 */
    private static final int MAX_RULES = 200;
    private static final int MAX_PATTERN_LENGTH = 512;
    /** 編譯後的正則快取（同一個 pattern 只編譯一次）。 */
    private static final java.util.Map<String, java.util.regex.Pattern> PATTERN_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static java.util.regex.Pattern patternOf(String regex) {
        if (regex == null || regex.isBlank() || regex.length() > MAX_PATTERN_LENGTH) {
            return null;
        }
        return PATTERN_CACHE.computeIfAbsent(regex, key -> {
            try {
                return java.util.regex.Pattern.compile(key, java.util.regex.Pattern.CASE_INSENSITIVE);
            } catch (Exception error) {
                return null;
            }
        });
    }

    public CrashRuleEngine() {
        loadCachedRemoteRules();
    }

    public List<CrashRule> rules() {
        return List.copyOf(rules);
    }

    // ---------- 分析 ----------

    public CrashAnalysis analyze(GameInstance entry) {
        return analyze(entry, 0L);
    }

    /**
     * 只分析「这次启动之后」的日志。
     *
     * <p>不传 since 时（导出日志等场景）行为与以前一致；启动失败/异常退出的分析
     * 一定要传启动时间，否则会把上一次甚至几天前的旧崩溃当成这次的原因，
     * 玩家会看到完全对不上的“可能原因”（实测踩过）。
     */
    public CrashAnalysis analyze(GameInstance entry, long sinceMillis) {
        Path gameDir = entry.gameDir();
        Path latestLog = gameDir.resolve("logs").resolve("latest.log");
        Path debugLog = gameDir.resolve("logs").resolve("debug.log");
        Path sessionLog = newestSessionLog(entry.id());
        Path crashReport = newestCrashReport(gameDir);
        if (sinceMillis > 0) {
            crashReport = freshOrNull(crashReport, sinceMillis);
            latestLog = freshOrNull(latestLog, sinceMillis);
            debugLog = freshOrNull(debugLog, sinceMillis);
            sessionLog = freshOrNull(sessionLog, sinceMillis);
        }
        String log = latestLog == null ? "" : readTail(latestLog, TAIL_BYTES);
        if (log.isBlank() && debugLog != null) {
            log = readTail(debugLog, TAIL_BYTES);
        }
        String crashText = crashReport == null ? "" : readTail(crashReport, TAIL_BYTES);
        // 只取“这个实例”的会话日志，避免把别的实例的输出算进来
        String sessionText = sessionLog == null ? "" : readTail(sessionLog, 96 * 1024);
        String haystack = sessionText + "\n" + log + "\n" + crashText;
        if (haystack.isBlank()) {
            return CrashAnalysis.empty();
        }


        // 纯原版（没装任何 Mod、也没加载器）不该被提示“缺少 Fabric API”这类东西：
        // NoClassDefFoundError 在原版里几乎都是文件残缺 / natives 没加载起来。
        boolean modded = isModded(entry, gameDir);
        return analyzeHaystack(haystack, gameDir, crashReport, modded);
    }

    /**
     * 直接分析一段日志文字（探针与「贴上日志」功能用；不需要真实实例）。
     */
    public CrashAnalysis analyzeHaystack(String haystack, Path gameDir, Path crashReport,
                                  boolean moddedEntry) {
        boolean modded = moddedEntry;
        List<CrashAnalysis.Match> matches = new ArrayList<>();
        for (CrashRule rule : rules) {
            if (rule.pattern().isBlank()) {
                continue;
            }
            try {
                java.util.regex.Pattern compiled = patternOf(rule.pattern());
        if (compiled == null) {
            continue;   // 超长/非法正则直接跳过
        }
        Matcher matcher = compiled
                        .matcher(haystack);
                if (matcher.find()) {
                    matches.add(new CrashAnalysis.Match(rule, evidenceAround(haystack, matcher.start())));
                }
            } catch (PatternSyntaxException e) {
                SafeLog.warn("崩溃规则正则非法：" + rule.id());
            }
        }
        if (!modded) {
            // 先改写再删除：MISSING_DEPENDENCY 在纯原版其实是「档案不完整」，
            // 如果先 removeIf 就会连这条一起被删掉（顺序反了什么都不剩）。
            matches.replaceAll(match -> "MISSING_DEPENDENCY".equals(match.rule().id())
                    ? new CrashAnalysis.Match(new CrashRule(match.rule().id(), match.rule().pattern(),
                    "files", "缺少类（NoClassDefFoundError）——通常是文件不完整或原生库没加载起来",
                    "先对该实例做一次文件校验补全；纯原版实例一般不是 Mod 依赖问题"), match.evidence())
                    : match);
            matches.removeIf(match -> "mod".equals(match.rule().category()));
        }
        // 从日志抓「具体缺少哪个前置 Mod」
        List<String> missingMods = findMissingMods(haystack);

        // 日志里有明显的 Mod 载入错误时，Mod 规则的可信度最高；
        // 这种情况下显卡/驱动类提示多半是误报（使用者回馈：错的原因排在第一位）
        boolean modEvidence = modEvidence(haystack);
        matches.sort(java.util.Comparator.comparingInt(
                match -> priority(match.rule(), modEvidence)));

        List<String> suspects = modded ? findSuspectMods(gameDir, haystack) : List.of();
        String tail = tailLines(haystack, 25);
        return new CrashAnalysis(matches, missingMods, suspects, tail, crashReport);
    }

    /** 没有加载器、mods 目录里也没有 jar，就算纯原版。 */
    private static boolean isModded(GameInstance entry, Path gameDir) {
        if (entry.loader() != null && !entry.loader().isVanilla()) {
            return true;
        }
        Path mods = gameDir.resolve("mods");
        if (!Files.isDirectory(mods)) {
            return false;
        }
        try (Stream<Path> stream = Files.list(mods)) {
            return stream.anyMatch(file -> file.getFileName().toString()
                    .toLowerCase(Locale.ROOT).endsWith(".jar"));
        } catch (IOException ignored) {
            return false;
        }
    }

    /** 日誌裡是否有明確的 Mod 載入/依賴錯誤。 */
    private static boolean modEvidence(String haystack) {
        if (haystack == null) {
            return false;
        }
        String lower = haystack.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("mod id:")
                || lower.contains("missing or unsupported mandatory dependencies")
                || lower.contains("incompatible mod set")
                || lower.contains("modresolutionexception")
                || lower.contains("which is missing")
                || lower.contains("a mod crashed on startup")
                || lower.contains("formattedexception")
                || lower.contains("requires version")
                || lower.contains("requires any version of");
    }

    /**
     * 从日志抓「缺少的前置 Mod」的 id。
     *
     * <p>各家格式不一样，这里把常见的都收进来：
     * <ul>
     *   <li>Forge/NeoForge：{@code Mod ID: 'xxx', Requested by: ...}</li>
     *   <li>Fabric：{@code Mod 'sodium-extra' requires version 0.5.0 or later of 'sodium', which is missing!}</li>
     *   <li>NeoForge：{@code Mod 'create' requires 'flywheel'}</li>
     * </ul>
     */
    private static java.util.List<String> findMissingMods(String haystack) {
        if (haystack == null || haystack.isBlank()) {
            return java.util.List.of();
        }
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        // 只看崩溃前的最后 200 行，避免把整份日志的杂讯都抓进来
        String tail = tailLines(haystack, 200);
        collect(tail, "Mod ID: '([A-Za-z0-9_.\\-]+)'", ids);
        collect(tail, "requires (?:any )?version[^,]*? of '([A-Za-z0-9_.\\-]+)'", ids);
        collect(tail, "requires '([A-Za-z0-9_.\\-]+)'", ids);
        collect(tail, "(?:Missing|missing) (?:mod |dependency )?'([A-Za-z0-9_.\\-]+)'", ids);
        ids.removeIf(id -> id.isBlank()
                || id.equalsIgnoreCase("minecraft")
                || id.equalsIgnoreCase("forge")
                || id.equalsIgnoreCase("neoforge")
                || id.equalsIgnoreCase("fabricloader")
                || id.equalsIgnoreCase("fabric")
                || id.equalsIgnoreCase("java")
                || id.equalsIgnoreCase("quilt_loader"));
        return ids.stream().limit(8).toList();
    }

    private static void collect(String haystack, String regex, java.util.Set<String> into) {
        try {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile(regex, java.util.regex.Pattern.CASE_INSENSITIVE).matcher(haystack);
            while (matcher.find() && into.size() < 8) {
                String value = matcher.group(1).trim();
                if (!value.isBlank()) {
                    into.add(value);
                }
            }
        } catch (Exception ignored) {
            // 正则写错不影响其它规则
        }
    }

    /** 可信度排序：mod 載入錯誤（且有日誌證據）排最前；有 mod 證據時顯卡類往後排。 */
    private static int priority(CrashRule rule, boolean modEvidence) {
        String id = rule.id();
        if (modEvidence) {
            if (id.equals("MISSING_MOD_DEP") || id.equals("MISSING_DEPENDENCY")
                    || id.equals("MOD_LOADING") || id.equals("MIXIN_CONFLICT")
                    || id.equals("DUPLICATE_MOD")) {
                return -2;
            }
            if ("graphics".equals(rule.category())) {
                return 6;
            }
        }
        return severity(rule.category());
    }

    /** 排序权重：越靠前越可能是真正原因（原生库/文件 → Java/内存 → 显卡 → 网络/存档 → Mod）。 */
    private static int severity(String category) {
        return switch (category) {
            case "files" -> 0;
            case "java", "memory", "system" -> 1;
            case "graphics" -> 2;
            case "network", "world" -> 3;
            case "mod", "resource" -> 4;
            case "audio" -> 6;
            default -> 5;
        };
    }

    /** 文件比 since 旧就当作不存在（避免把历史日志当成本次崩溃）。 */
    private static Path freshOrNull(Path file, long sinceMillis) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            long modified = Files.getLastModifiedTime(file).toMillis();
            // 给 2 秒容差：日志写入与进程启动的时间可能有毫秒级偏差
            return modified + 2000L >= sinceMillis ? file : null;
        } catch (Exception error) {
            return file;
        }
    }
    private static String evidenceAround(String text, int index) {
        int start = Math.max(0, index - 60);
        int end = Math.min(text.length(), index + 120);
        return text.substring(start, end).replace('\n', ' ').trim();
    }

    /** 用 mods 目录里的文件名去日志里找线索，命中即视为“疑似相关 Mod”。 */
    private static List<String> findSuspectMods(Path gameDir, String haystack) {
        Path mods = gameDir.resolve("mods");
        if (!Files.isDirectory(mods)) {
            return List.of();
        }
        Set<String> suspects = new LinkedHashSet<>();
        String lower = haystack.toLowerCase(Locale.ROOT);
        try (Stream<Path> stream = Files.list(mods)) {
            stream.filter(Files::isRegularFile).forEach(file -> {
                String name = file.getFileName().toString();
                if (!name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    return;
                }
                String base = name.substring(0, name.length() - 4)
                        .replaceAll("[-_](\\d+\\.)+\\d+.*$", "")
                        .toLowerCase(Locale.ROOT);
                if (base.length() >= 3 && lower.contains(base)) {
                    suspects.add(name);
                }
            });
        } catch (IOException ignored) {
            // 目录读不到就不猜
        }
        return suspects.stream().limit(8).toList();
    }

    private static String tailLines(String text, int count) {
        String[] lines = text.split("\\R");
        int from = Math.max(0, lines.length - count);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        return SafeLog.sanitize(sb.toString().trim());
    }

    private static String readTail(Path file, int maxBytes) {
        if (file == null || !Files.isRegularFile(file)) {
            return "";
        }
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long length = raf.length();
            long from = Math.max(0, length - maxBytes);
            raf.seek(from);
            byte[] buffer = new byte[(int) (length - from)];
            raf.readFully(buffer);
            return new String(buffer, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static Path newestSessionLog(String versionId) {
        Path logs = AppPaths.logsDir();
        if (!Files.isDirectory(logs)) {
            return null;
        }
        String prefix = "session-" + versionId + "-";
        try (Stream<Path> stream = Files.list(logs)) {
            return stream.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().startsWith(prefix))
                    .max(Comparator.comparingLong(CrashRuleEngine::lastModified))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static Path newestCrashReport(Path gameDir) {        Path dir = gameDir.resolve("crash-reports");
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .max(Comparator.comparingLong(CrashRuleEngine::lastModified))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static long lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    // ---------- 远程规则 ----------

    /** 远端规则的最小更新间隔（安全审计 v2 A-05：不再每次启动都请求一次）。 */
    private static final long REMOTE_INTERVAL_MILLIS = 24L * 3600 * 1000;

    /**
     * 拉取远程规则并与内置规则合并；失败静默（不影响离线使用）。
     *
     * <p>安全审计 v2 A-05：以前**每次启动**都会向 GitHub 拉一次规则（暴露 IP / 版本 / UA）。
     * 现在 24 小时内只拉一次，期间直接用本地缓存；手动点「立即更新」会绕过这个限制。
     */
    public boolean updateRemote() {
        return updateRemote(false);
    }

    /** @param force true 时忽略 24 小时限频（设置页的「立即更新」用）。 */
    public boolean updateRemote(boolean force) {
        if (!force && !remoteDue()) {
            SafeLog.info("[crash] 远端规则 24 小时内已更新过，使用本地缓存");
            return false;
        }
        try {
            byte[] body = Http.getBytes(REMOTE_RULES, Duration.ofSeconds(15));
            JsonNode root = mapper.readTree(body);
            mergeRules(root);
            saveCache(body);
            markRemoteUpdated();
            return true;
        } catch (Exception e) {
            SafeLog.warn("崩溃规则远程更新失败，继续使用内置规则");
            return false;
        }
    }

    private Path remoteStampFile() {
        return AppPaths.dataDir().resolve("crash-rules.last");
    }

    private boolean remoteDue() {
        try {
            Path stamp = remoteStampFile();
            if (!Files.isRegularFile(stamp)) {
                return true;
            }
            long last = Long.parseLong(Files.readString(stamp).trim());
            return System.currentTimeMillis() - last >= REMOTE_INTERVAL_MILLIS;
        } catch (Exception error) {
            return true;
        }
    }

    private void markRemoteUpdated() {
        try {
            Path stamp = remoteStampFile();
            Files.createDirectories(stamp.getParent());
            Files.writeString(stamp, String.valueOf(System.currentTimeMillis()),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 记不下来只会导致下次再拉一次，不影响功能
        }
    }

    private void mergeRules(JsonNode root) {
        JsonNode array = root.path("rules");
        if (!array.isArray()) {
            return;
        }
        List<CrashRule> merged = new ArrayList<>(DEFAULT_RULES);
        int accepted = 0;
        int rejected = 0;
        for (JsonNode node : array) {
            String id = node.path("id").asText("");
            String pattern = node.path("pattern").asText("");
            // 安全审计 M-08：远端规则没有任何 schema 校验，可被用来投放
            // ①误导性文案 ②ReDoS 正则 ③超量规则。这里逐条挡。
            if (!id.matches("[A-Za-z0-9_-]{1,64}") || pattern.isBlank()
                    || pattern.length() > MAX_PATTERN_LENGTH
                    || patternOf(pattern) == null || looksBacktracking(pattern)
                    || accepted >= MAX_RULES) {
                rejected++;
                continue;
            }
            CrashRule rule = new CrashRule(
                    id,
                    pattern,
                    truncate(node.path("category").asText("unknown"), 32),
                    truncate(node.path("explanation").asText(""), 300),
                    truncate(node.path("suggestion").asText(""), 300));
            merged.removeIf(existing -> existing.id().equals(rule.id()));
            merged.add(rule);
            accepted++;
        }
        if (rejected > 0) {
            SafeLog.warn("[crash] 远程崩溃规则有 " + rejected + " 条未通过校验，已忽略");
        }
        rules.clear();
        rules.addAll(merged);
    }

    /**
     * 粗糙但有效的 ReDoS 防护：拒绝「分组内的量词又套一层量词」的写法，
     * 例如 {@code (a+)+$}、{@code (\w*)*}——这类是回溯爆炸的经典形态。
     */
    private static boolean looksBacktracking(String pattern) {
        return pattern.matches(".*\\([^)]*[+*]\\)[+*].*")
                || pattern.matches(".*\\([^)]*\\{[0-9,]+\\}\\)[+*].*");
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private Path cacheFile() {
        return AppPaths.launcherRoot().resolve("crash-rules.json");
    }

    private void saveCache(byte[] body) {
        try {
            Files.createDirectories(cacheFile().getParent());
            Files.write(cacheFile(), body);
        } catch (IOException e) {
            SafeLog.warn("缓存崩溃规则失败");
        }
    }

    private void loadCachedRemoteRules() {
        Path cache = cacheFile();
        if (!Files.isRegularFile(cache)) {
            return;
        }
        try {
            mergeRules(mapper.readTree(cache.toFile()));
        } catch (Exception e) {
            SafeLog.warn("崩溃规则缓存损坏，使用内置规则");
        }
    }

    /** 导出当前规则（调试用）。 */
    public void exportRules(Path file) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("version", 3);
        ArrayNode array = root.putArray("rules");
        for (CrashRule rule : rules) {
            ObjectNode node = array.addObject();
            node.put("id", rule.id());
            node.put("pattern", rule.pattern());
            node.put("category", rule.category());
            node.put("explanation", rule.explanation());
            node.put("suggestion", rule.suggestion());
        }
        Files.write(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
    }
}
