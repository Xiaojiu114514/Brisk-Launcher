package com.brisklauncher.mcl.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.brisklauncher.mcl.security.SafeLog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 扫描 {@code <root>/versions/} 下的实例。
 *
 * <p>判定规则与 HMCL 相同：目录里必须存在同名 {@code <id>.json} 才算有效版本；
 * 加载器不靠目录名猜，而是解析 libraries（Fabric/Quilt/Forge/NeoForge 的加载器构件
 * 一定会出现在版本 JSON 的 libraries 里），这样重命名过的实例也能识别。
 */
public final class GameScanner {

    private static final Pattern MC_VERSION = Pattern.compile("^(\\d+\\.\\d+(?:\\.\\d+)?)");
    private static final Pattern MC_VERSION_ANYWHERE =
            Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public List<GameInstance> scan(GameDirectory root) {
        Path versionsDir = root.versionsDir();
        if (!Files.isDirectory(versionsDir)) {
            return List.of();
        }
        List<GameInstance> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(versionsDir)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                String id = dir.getFileName().toString();
                Path json = dir.resolve(id + ".json");
                if (!Files.isRegularFile(json)) {
                    return;
                }
                GameInstance entry = new GameInstance(root, id, dir);
                parse(entry, json);
                result.add(entry);
            });
        } catch (IOException e) {
            SafeLog.warn("扫描版本目录失败：" + root.path());
        }
        result.sort(versionOrder());
        return result;
    }

    public Optional<GameInstance> scanSingle(GameDirectory root, String id) {
        Path dir = root.versionsDir().resolve(id);
        Path json = dir.resolve(id + ".json");
        if (!Files.isRegularFile(json)) {
            return Optional.empty();
        }
        GameInstance entry = new GameInstance(root, id, dir);
        parse(entry, json);
        return Optional.of(entry);
    }

    /** 读取并解析版本 JSON；调用方需要完整 JSON 时用这个而不是再读一次磁盘。 */
    public JsonNode readJson(Path jsonFile) throws IOException {
        return MAPPER.readTree(jsonFile.toFile());
    }

    private void parse(GameInstance entry, Path json) {
        try {
            JsonNode node = MAPPER.readTree(json.toFile());
            entry.type(node.path("type").asText("unknown"));
            entry.releaseTime(node.path("releaseTime").asText(
                    node.path("time").asText("")));
            entry.mainClass(node.path("mainClass").asText(""));

            String inherits = node.path("inheritsFrom").asText(null);
            // 判定顺序（与 PCL / HMCL 一致）：inheritsFrom 链 → libraries → 目录名 → 目录名兜底。
            // libraries 排在目录名前面，因为「Roguelike Bio-肉鸽生化[导入包] v1.0」这种 id 会
            // 让人从 id 抓到 1.0（实际是 Forge 1.20.1）。
            String chainVersion = resolveChainMcVersion(entry.root(), entry.id(), inherits);
            String fromLibraries = chainVersion == null ? mcVersionFromLibraries(node) : null;
            String chain = chainVersion != null ? chainVersion : fromLibraries;
            if (chain == null || !MC_VERSION.matcher(chain).find()) {
                // 整合包实例常常 id 就是包名（如 Fabulously Optimized1.21.11）
                Matcher guess = MC_VERSION_ANYWHERE.matcher(entry.id());
                chain = guess.find() ? guess.group(1) : chain;
            }
            if (chain == null || chain.isBlank()) {
                chain = entry.id();
            }
            entry.mcVersion(chain);
            LoaderInfo loader = detectLoader(node, entry.id(), entry.mcVersion());
            if (loader.isVanilla() && inherits != null && !inherits.isBlank()) {
                // 整合包实例没有自己的 libraries，加载器信息在父版本里
                loader = loaderFromParent(entry.root(), inherits, 0);
            }
            entry.loader(loader);

            int javaMajor = node.path("javaVersion").path("majorVersion").asInt(-1);
            if (javaMajor <= 0) {
                // 加载器 profile（Fabric/Quilt）自己不写 javaVersion，去父版本里找；
                // 否则 26.x 这类新版本会被按版本号猜成 Java 17。
                javaMajor = javaVersionFromParent(entry.root(), inherits, 0);
            }
            entry.javaMajor(javaMajor > 0 ? javaMajor : JavaVersionMapper.forMinecraft(entry.mcVersion()));
        } catch (IOException e) {
            SafeLog.warn("解析版本 JSON 失败：" + json.getFileName());
        }
    }

    /** 顺着 inheritsFrom 链找 {@code javaVersion.majorVersion}；找不到回传 -1。 */
    private static int javaVersionFromParent(GameDirectory root, String parentId, int depth) {
        if (parentId == null || parentId.isBlank() || depth > 6) {
            return -1;
        }
        Path json = root.versionsDir().resolve(parentId).resolve(parentId + ".json");
        if (!Files.isRegularFile(json)) {
            return -1;
        }
        try {
            JsonNode node = MAPPER.readTree(json.toFile());
            int major = node.path("javaVersion").path("majorVersion").asInt(-1);
            if (major > 0) {
                return major;
            }
            return javaVersionFromParent(root, node.path("inheritsFrom").asText(""), depth + 1);
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * 顺着 inheritsFrom 链找到真正的 MC 版本号。
     * 例：整合包实例 → fabric-loader-…-1.20.1 → 1.20.1，取最后一级。
     *
     * @return 解析不到就回传 {@code null}（交给 libraries / 目录名继续猜）
     */
    private static String resolveChainMcVersion(GameDirectory root, String id, String inheritsFrom) {
        String current = inheritsFrom;
        int guard = 0;
        while (current != null && !current.isBlank() && guard++ < 8) {
            Path json = root.versionsDir().resolve(current).resolve(current + ".json");
            if (!Files.isRegularFile(json)) {
                break;
            }
            try {
                JsonNode node = MAPPER.readTree(json.toFile());
                String parent = node.path("inheritsFrom").asText("");
                if (!parent.isBlank()) {
                    current = parent;
                    continue;
                }
            } catch (IOException e) {
                break;
            }
            Matcher matcher = MC_VERSION.matcher(current);
            return matcher.find() ? matcher.group(1) : current;
        }
        if (inheritsFrom != null) {
            Matcher matcher = MC_VERSION.matcher(inheritsFrom);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    /**
     * 从 libraries 反推原版（Minecraft）版本号，做法与 PCL / HMCL 一致。
     *
     * <p>为什么需要：整合包实例的 id 常常是纯中文包名、又（从第 125 轮起）没有
     * {@code inheritsFrom}，光靠目录名猜不出 {@code 1.21.11}，排序、副标题、
     * Java 版本判断就全错。
     *
     * <p>可辨识的构件（依可信度排序）：
     * <ul>
     *   <li>{@code net.minecraft:client:1.21.1-20240808.144430}（Forge/NeoForge 生成）</li>
     *   <li>{@code com.mojang:minecraft:1.21.1}（部分启动器）</li>
     *   <li>{@code net.fabricmc:intermediary:1.21.11}（Fabric 的中介映射，版本号就是原版版本）</li>
     *   <li>{@code net.minecraftforge:forge:1.20.1-47.2.0}（旧版 Forge）</li>
     *   <li>{@code net.neoforged:neoforge:21.1.252}（NeoForge 的版本号编码了原版：21.1 → 1.21.1）</li>
     * </ul>
     * 抓不到就回传 {@code null}（呼叫方保留原本的猜法）。
     */
    public static String mcVersionFromLibraries(JsonNode node) {
        String explicit = null;     // net.minecraft:client / com.mojang:minecraft
        String intermediary = null; // net.fabricmc:intermediary / quilt 的 hashed
        String forge = null;        // net.minecraftforge:forge
        String fmlLoader = null;    // net.minecraftforge:fmlloader（1.20.1-47.4.28）
        String neoforge = null;     // net.neoforged:neoforge
        for (JsonNode library : node.path("libraries")) {
            String name = library.path("name").asText("").toLowerCase(Locale.ROOT);
            if (name.startsWith("net.minecraft:client:") || name.startsWith("com.mojang:minecraft:")) {
                explicit = coalesce(explicit, versionTag(name));
            } else if (name.startsWith("net.fabricmc:intermediary:")
                    || name.startsWith("org.quiltmc:hashed:")) {
                intermediary = coalesce(intermediary, versionTag(name));
            } else if (name.startsWith("net.minecraftforge:forge:")) {
                forge = coalesce(forge, versionTag(name));
            } else if (name.startsWith("net.minecraftforge:fmlloader:")) {
                fmlLoader = coalesce(fmlLoader, versionTag(name));
            } else if (name.startsWith("net.neoforged:neoforge:")) {
                neoforge = coalesce(neoforge, versionTag(name));
            }
        }
        for (String candidate : new String[]{explicit, intermediary, forge, fmlLoader}) {
            String resolved = firstMcVersion(candidate);
            if (resolved != null) {
                return resolved;
            }
        }
        return mcFromNeoForge(neoforge);
    }

    private static String coalesce(String current, String value) {
        return current == null || current.isBlank() ? value : current;
    }

    private static String versionTag(String coordinate) {
        String[] parts = coordinate.split(":");
        return parts.length >= 3 ? parts[2] : "";
    }

    private static String firstMcVersion(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher matcher = MC_VERSION_ANYWHERE.matcher(raw);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * NeoForge 的版本号就是原版版本号的编码：{@code 21.1.252} → {@code 1.21.1}、
     * {@code 20.4.237} → {@code 1.20.4}（NeoForge 21+ 才有 1.21.x；20.2 之后才是 1.20.2+）。
     */
    static String mcFromNeoForge(String loaderVersion) {
        if (loaderVersion == null || loaderVersion.isBlank()) {
            return null;
        }
        Matcher matcher = Pattern.compile("^(\\d+)\\.(\\d+)").matcher(loaderVersion);
        if (!matcher.find()) {
            return null;
        }
        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        if (major < 20) {
            return null;
        }
        if (major == 20) {
            return "1.20." + minor;
        }
        return "1." + major + "." + minor;
    }

    private static LoaderInfo loaderFromParent(GameDirectory root, String parentId, int depth) {
        if (depth > 6) {
            return LoaderInfo.vanilla(parentId);
        }
        Path json = root.versionsDir().resolve(parentId).resolve(parentId + ".json");
        if (!Files.isRegularFile(json)) {
            return LoaderInfo.vanilla(parentId);
        }
        try {
            JsonNode node = MAPPER.readTree(json.toFile());
            LoaderInfo loader = detectLoader(node, parentId, parentId);
            if (!loader.isVanilla()) {
                return loader;
            }
            String parent = node.path("inheritsFrom").asText("");
            if (!parent.isBlank()) {
                return loaderFromParent(root, parent, depth + 1);
            }
        } catch (IOException e) {
            return LoaderInfo.vanilla(parentId);
        }
        return LoaderInfo.vanilla(parentId);
    }

    /**
     * 父版本优先。注意 Fabric 的 profile 里 inheritsFrom 是原版版本号；
     * Forge 安装器生成的 JSON 里 inheritsFrom 也是原版版本号，所以这里直接信任。
     */
    static String resolveMcVersion(String id, String inheritsFrom) {
        if (inheritsFrom != null && !inheritsFrom.isBlank()) {
            return inheritsFrom;
        }
        Matcher matcher = MC_VERSION.matcher(id);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return id;
    }

    // ---------- 加载器识别 ----------

    static LoaderInfo detectLoader(JsonNode node, String id, String mcVersion) {
        String fabric = null;
        String quilt = null;
        String forge = null;
        String fmlloader = null;
        String neoforge = null;
        String fancyLoader = null;
        String optifine = null;

        for (JsonNode library : node.path("libraries")) {
            String name = library.path("name").asText("").toLowerCase(Locale.ROOT);
            if (name.startsWith("net.fabricmc:fabric-loader:")) {
                fabric = coalesce(fabric, artifactVersion(name));
            } else if (name.startsWith("org.quiltmc:quilt-loader:")) {
                quilt = coalesce(quilt, artifactVersion(name));
            } else if (name.startsWith("net.neoforged:neoforge:")) {
                neoforge = coalesce(neoforge, artifactVersion(name));
            } else if (name.startsWith("net.neoforged.fancymodloader:loader:")) {
                fancyLoader = coalesce(fancyLoader, artifactVersion(name));
            } else if (name.startsWith("net.minecraftforge:forge:")) {
                forge = coalesce(forge, artifactVersion(name));
            } else if (name.startsWith("net.minecraftforge:fmlloader:")) {
                fmlloader = coalesce(fmlloader, artifactVersion(name));
            } else if (name.startsWith("optifine:optifine")) {
                optifine = coalesce(optifine, artifactVersion(name));
            }
        }

        if (fabric != null) {
            return new LoaderInfo(LoaderInfo.LoaderType.FABRIC, fabric);
        }
        if (quilt != null) {
            return new LoaderInfo(LoaderInfo.LoaderType.QUILT, quilt);
        }
        if (neoforge == null) {
            // PCL 装的 NeoForge 版本 JSON 没有 net.neoforged:neoforge，NeoForge 版本号在 id 里
            // （1.21.1-NeoForge_21.1.235）；再不行才退回 FML 版本（4.0.42）。
            neoforge = neoforgeFromId(id);
            if (neoforge == null) {
                neoforge = fancyLoader;
            }
        }
        if (neoforge != null) {
            return new LoaderInfo(LoaderInfo.LoaderType.NEOFORGE, neoforge);
        }
        if (forge == null) {
            forge = fmlloader;
        }
        if (forge != null) {
            // Forge 的构件是「mc-加载器」（1.20.1-47.4.28），介面上只显示加载器版本
            if (mcVersion != null && forge.startsWith(mcVersion + "-")) {
                forge = forge.substring(mcVersion.length() + 1);
            }
            return new LoaderInfo(LoaderInfo.LoaderType.FORGE, forge);
        }
        if (optifine != null) {
            return new LoaderInfo(LoaderInfo.LoaderType.OPTIFINE, optifine);
        }

        // 有些整合包把加载器打进自定义 libraries，退回按目录名/主类识别
        String mainClass = node.path("mainClass").asText("").toLowerCase(Locale.ROOT);
        String lowerId = id.toLowerCase(Locale.ROOT);
        if (mainClass.contains("knotclient") || lowerId.contains("fabric")) {
            return new LoaderInfo(LoaderInfo.LoaderType.FABRIC, "unknown");
        }
        if (mainClass.contains("quilt") || lowerId.contains("quilt")) {
            return new LoaderInfo(LoaderInfo.LoaderType.QUILT, "unknown");
        }
        if (mainClass.contains("neoforged") || lowerId.contains("neoforge")) {
            return new LoaderInfo(LoaderInfo.LoaderType.NEOFORGE, "unknown");
        }
        if (mainClass.contains("forge") || lowerId.contains("forge")) {
            return new LoaderInfo(LoaderInfo.LoaderType.FORGE, "unknown");
        }
        if (lowerId.contains("optifine")) {
            return new LoaderInfo(LoaderInfo.LoaderType.OPTIFINE, "unknown");
        }
        return LoaderInfo.vanilla(mcVersion);
    }

    private static String artifactVersion(String coordinate) {
        String[] parts = coordinate.split(":");
        return parts.length >= 3 ? parts[2] : "unknown";
    }

    /** 從目錄名抓 NeoForge 版本號（{@code 1.21.1-NeoForge_21.1.235} → {@code 21.1.235}）。 */
    private static String neoforgeFromId(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        Matcher matcher = Pattern.compile("(?i)neoforge[\\s_\\-]*v?(\\d+\\.\\d+(?:\\.\\d+)?)")
                .matcher(id);
        return matcher.find() ? matcher.group(1) : null;
    }

    // ---------- 排序 ----------

    /**
     * 新版本在前：先比「版本号」（26.3 &gt; 1.21.11 &gt; 1.21 &gt; 1.20.1），
     * 同版本号再比发布时间，最后原版优先、id 兜底。
     *
     * <p>不能拿发布时间当主键：整合包实例的 releaseTime 往往是「导入时间」，
     * 会让刚导入的 1.21.1 整合包排到 26.3 前面（实测踩过）。
     */
    private static Comparator<GameInstance> versionOrder() {
        return Comparator
                .comparingInt((GameInstance entry) -> versionWeight(entry.mcVersion())).reversed()
                .thenComparingLong((GameInstance entry) -> -releaseEpoch(entry))
                .thenComparingInt(entry -> entry.loader() == null
                        || entry.loader().type() == LoaderInfo.LoaderType.VANILLA ? 0 : 1)
                .thenComparing((GameInstance entry) -> entry.id());
    }

    /** releaseTime → epoch 毫秒；解析不了就给 0（排到最后）。 */
    private static long releaseEpoch(GameInstance entry) {
        String text = entry.releaseTime();
        if (text == null || text.isBlank()) {
            return 0L;
        }
        try {
            return java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            // 部分整合包的 releaseTime 不是标准格式
        }
        try {
            return java.time.LocalDateTime.parse(text).toInstant(
                    java.time.ZoneOffset.UTC).toEpochMilli();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static int versionWeight(String mcVersion) {
        if (mcVersion == null) {
            return 0;
        }
        Matcher matcher = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?").matcher(mcVersion);
        if (!matcher.find()) {
            return 0;
        }
        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        int patch = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
        return major * 1_000_000 + minor * 1_000 + patch;
    }
}
