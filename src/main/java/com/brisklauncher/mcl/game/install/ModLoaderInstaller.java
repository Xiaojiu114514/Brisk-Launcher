package com.brisklauncher.mcl.game.install;

import com.brisklauncher.mcl.AppPaths;
import com.brisklauncher.mcl.download.DownloadTask;
import com.brisklauncher.mcl.download.HttpDownloader;
import com.brisklauncher.mcl.download.MirrorResolver;
import com.brisklauncher.mcl.download.remote.LoaderMetaClient;
import com.brisklauncher.mcl.download.remote.MojangApi;
import com.brisklauncher.mcl.game.GameDirectory;
import com.brisklauncher.mcl.game.GameInstance;
import com.brisklauncher.mcl.game.GameScanner;
import com.brisklauncher.mcl.game.JavaVersionMapper;
import com.brisklauncher.mcl.game.LoaderInfo;
import com.brisklauncher.mcl.java.JavaManager;
import com.brisklauncher.mcl.java.JdkInfo;
import com.brisklauncher.mcl.net.Http;
import com.brisklauncher.mcl.security.PathSafety;
import com.brisklauncher.mcl.security.SafeLog;
import com.brisklauncher.mcl.util.RecycleBin;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 加载器安装。
 *
 * <table>
 *   <tr><th>加载器</th><th>方式</th></tr>
 *   <tr><td>Fabric</td><td>Meta API 取 profile JSON（自带 inheritsFrom），合并进原版版本目录</td></tr>
 *   <tr><td>Forge / NeoForge</td><td>下载官方安装器 jar，用合适的 Java 执行 {@code --installClient}，
 *       再把生成的版本目录合并进原版实例</td></tr>
 *   <tr><td>OptiFine</td><td>只把 jar 放进 {@code mods/}（必须搭配 Forge）</td></tr>
 * </table>
 *
 * <p>重要约定：
 * <ul>
 *   <li>「就地安装」：加载器不新建版本文件夹，而是把 profile 合并进原版实例（PCL / HMCL 行为）；</li>
 *   <li>{@code libraries} / {@code assets} 永远在游戏根目录共用，不在版本文件夹里；</li>
 *   <li>安装器用的 Java 由 {@link JavaManager#ensureFor} 保证：本机有就用，
 *       没有就自动补齐（Forge/NeoForge 对 Java 版本很敏感，这一步缺失会「卡住」或直接失败）。</li>
 * </ul>
 */
public final class ModLoaderInstaller {

    private final VersionInstaller versionInstaller;
    private final MojangApi mojang;
    private final LoaderMetaClient meta;
    private final JavaManager javaManager;
    private final HttpDownloader downloader;
    private final MirrorResolver mirrors;
    private final GameScanner scanner = new GameScanner();
    private final ObjectMapper mapper = Http.mapper();

    public ModLoaderInstaller(VersionInstaller versionInstaller, MojangApi mojang, LoaderMetaClient meta,
                              JavaManager javaManager, HttpDownloader downloader, MirrorResolver mirrors) {
        this.versionInstaller = versionInstaller;
        this.mojang = mojang;
        this.meta = meta;
        this.javaManager = javaManager;
        this.downloader = downloader;
        this.mirrors = mirrors;
    }

    // ---------- Fabric ----------

    public GameInstance installFabric(GameDirectory root, String mcVersion, String loaderVersion,
                                      Consumer<InstallProgress> progress) {
        return installProfileBased(root, mcVersion, loaderVersion, LoaderInfo.LoaderType.FABRIC, progress);
    }

    private GameInstance installProfileBased(GameDirectory root, String mcVersion, String loaderVersion,
                                             LoaderInfo.LoaderType type,
                                             Consumer<InstallProgress> progress) {
        progress.accept(new InstallProgress(InstallProgress.Stage.LOADER, 0.02,
                type.displayName() + " " + loaderVersion));
        GameInstance target = ensureVanilla(root, mcVersion, progress);
        JsonNode profile;
        try {
            profile = meta.fabricProfile(mcVersion, loaderVersion);
        } catch (Exception error) {
            throw new InstallException(type.displayName() + " 配置文件获取失败：" + error.getMessage(), error);
        }
        if (profile.isObject() && !profile.path("mainClass").asText("").isEmpty()) {
            return applyLoaderPatch(target, profile, type, loaderVersion, progress);
        }
        throw new InstallException(type.displayName() + " 返回的配置无效");
    }

    // ---------- 安装器预下载（与游戏本体下载并行） ----------

    /** 提前把 Forge/NeoForge 安装器下到正式安装用的缓存路径，安装时直接命中。 */
    public void prefetchInstaller(String loaderName, String mcVersion, String loaderVersion) {
        try {
            String key = loaderName == null ? "" : loaderName.toLowerCase(Locale.ROOT);
            URI url;
            Path target;
            if ("forge".equals(key)) {
                url = meta.forgeInstaller(mcVersion, loaderVersion);
                target = AppPaths.cacheDir().resolve("installers")
                        .resolve("forge-" + mcVersion + "-" + loaderVersion + "-installer.jar");
            } else if ("neoforge".equals(key)) {
                url = meta.neoforgeInstaller(loaderVersion);
                target = AppPaths.cacheDir().resolve("installers")
                        .resolve("neoforge-" + loaderVersion + "-installer.jar");
            } else {
                return;
            }
            String sha1 = fetchInstallerSha1(url, loaderName + " " + loaderVersion);
            downloader.downloadOne(new DownloadTask(mirrors.resolve(url.toString()), target, sha1, -1L));
            SafeLog.info("[install] 加载器安装器已预先下载：" + target.getFileName());
        } catch (Exception error) {
            SafeLog.warn("[install] 预先下载加载器安装器失败（正式安装时会重试）："
                    + error.getClass().getSimpleName());
        }
    }

    // ---------- 安装器校验值 ----------

    /**
     * 取安装器的 {@code .sha1}：先试目前 URL，再试官方 maven（BMCLAPI 镜像不提供 .sha1）。
     *
     * <p>取不到时的策略（**这里刻意偏离审计方案，理由见 {@code 漏洞修补.md} 的「修补偏离说明」**）：
     * <ul>
     *   <li><b>官方源 / 内置镜像（BMCLAPI）</b>：跳过校验直接下载。国内官方源经常整个连不上，
     *       而镜像本来就不提供 .sha1；为了一张取不到的校验值把安装卡死，玩家只会以为「启动器坏了」。</li>
     *   <li><b>使用者自己填的第三方镜像</b>：必须弹一次风险确认（写入 acks），
     *       因为这时「谁给的 jar」已经不可控。</li>
     * </ul>
     *
     * @return 40 位 hex；{@code null} 表示「来源可信但拿不到校验值」→ 不校验继续
     */
    private String fetchInstallerSha1(URI installerUrl, String what) {
        String base = installerUrl.toString();
        String[] candidates = {
                base + ".sha1",
                base.replace("https://bmclapi2.bangbang93.com/maven/net/neoforged/neoforge/",
                        "https://maven.neoforged.net/releases/net/neoforged/neoforge/") + ".sha1",
                base.replace("https://bmclapi2.bangbang93.com/maven/net/minecraftforge/",
                        "https://maven.minecraftforge.net/") + ".sha1"};
        for (String candidate : candidates) {
            try {
                String text = Http.getString(URI.create(candidate), Duration.ofSeconds(30)).trim();
                String sha1 = text.split("\\s+")[0].toLowerCase(Locale.ROOT);
                if (PathSafety.isHexSha1(sha1)) {
                    return sha1;
                }
            } catch (Exception ignored) {
                // 换下一个来源
            }
        }
        if (allowUnverifiedInstaller(installerUrl, what)) {
            SafeLog.warn("[install] " + what + " 安装器没有可用的 .sha1（来源：" + hostOf(installerUrl)
                    + "），按来源策略跳过校验继续安装");
            return null;
        }
        throw new InstallException(com.brisklauncher.mcl.i18n.I18n.t(
                "install.sha1_missing", what));
    }

    private static String hostOf(URI uri) {
        String host = uri == null ? null : uri.getHost();
        return host == null ? "?" : host.toLowerCase(Locale.ROOT);
    }

    /** 内置来源（官方 maven / BMCLAPI 镜像）—— 拿不到校验值也不阻塞。 */
    private static boolean isTrustedLoaderSource(URI uri) {
        String host = hostOf(uri);
        return host.endsWith("bangbang93.com")
                || host.equals("maven.neoforged.net")
                || host.equals("maven.minecraftforge.net")
                || host.equals("files.minecraftforge.net")
                || host.equals("bmclapi.bangbang93.com");
    }

    /**
     * 拿不到校验值时是否允许继续。
     *
     * <p>官方 / 内置镜像：直接允许（见方法注释里的理由）。
     * 第三方自定义镜像：问一次「继续下载并执行未校验的安装器？」，同意后记住。
     */
    private boolean allowUnverifiedInstaller(URI installerUrl, String what) {
        if (isTrustedLoaderSource(installerUrl)) {
            return true;
        }
        // 安全审计 v3 A-15：确认要绑定「具体是哪个镜像主机」。
        // 玩家同意过 mirror-a 之后换成 mirror-b，必须重新问 —— 一次同意不能跨对象复用。
        String host = hostOf(installerUrl);
        String ackKey = com.brisklauncher.mcl.security.Acks.scopedKey("loaderNoChecksum", host);
        if (com.brisklauncher.mcl.security.Acks.has(ackKey)) {
            return true;
        }
        boolean proceed;
        try {
            proceed = com.brisklauncher.mcl.ui.MCLDialog.confirmBlocking(null,
                    com.brisklauncher.mcl.i18n.I18n.t("install.sha1_unverified_title"),
                    com.brisklauncher.mcl.i18n.I18n.t("install.sha1_unverified_body",
                            what, hostOf(installerUrl)));
        } catch (Throwable error) {
            // 没有 UI（无窗口 / 自动化）时不能假装使用者同意了 → 按拒绝处理，给可读报错
            SafeLog.warn("[install] 无法弹出风险确认框，按中止处理："
                    + error.getClass().getSimpleName());
            return false;
        }
        if (proceed) {
            com.brisklauncher.mcl.security.Acks.record(ackKey);
            SafeLog.warn("[install] 使用者确认在无校验的情况下安装 " + what + "（来源 " + host + "）");
        }
        return proceed;
    }

    // ---------- Forge ----------

    public GameInstance installForge(GameDirectory root, String mcVersion, String forgeVersion,
                                     Consumer<InstallProgress> progress) {
        progress.accept(new InstallProgress(InstallProgress.Stage.LOADER, 0.02, "Forge " + forgeVersion));
        GameInstance target = ensureVanilla(root, mcVersion, progress);
        ensureProfilesStub(root);

        Path installerJar = AppPaths.cacheDir().resolve("installers")
                .resolve("forge-" + mcVersion + "-" + forgeVersion + "-installer.jar");
        URI installerUrl = meta.forgeInstaller(mcVersion, forgeVersion);
        // 安全审计 M5：安装器是要 `java -jar` 执行的，必须先取 .sha1 并校验
        String sha1 = fetchInstallerSha1(installerUrl, "Forge " + forgeVersion);
        progress.accept(new InstallProgress(InstallProgress.Stage.LOADER, 0.1, "Forge " + forgeVersion));
        downloader.downloadOne(new DownloadTask(mirrors.resolve(installerUrl.toString()),
                installerJar, sha1, -1L));

        Set<String> before = installedIds(root);
        runInstallerWithRetry(root, installerJar, JavaVersionMapper.forMinecraft(mcVersion),
                progress, "Forge", progress2 -> { });
        GameInstance generated = detectInstalled(root, before, "forge", "neoforge", progress);
        GameInstance entry = applyLoaderPatch(target, readJson(generated.jsonFile()),
                LoaderInfo.LoaderType.FORGE, forgeVersion, progress);
        // 安装器生成的版本目录已经合并进原版，扔回收站（不直接删）
        RecycleBin.delete(generated.versionDir());
        return entry;
    }

    // ---------- NeoForge ----------

    public GameInstance installNeoForge(GameDirectory root, String mcVersion, String neoforgeVersion,
                                        Consumer<InstallProgress> progress) {
        progress.accept(new InstallProgress(InstallProgress.Stage.LOADER, 0.02, "NeoForge " + neoforgeVersion));
        GameInstance target = ensureVanilla(root, mcVersion, progress);
        ensureProfilesStub(root);

        Path installerJar = AppPaths.cacheDir().resolve("installers")
                .resolve("neoforge-" + neoforgeVersion + "-installer.jar");
        URI installerUrl = meta.neoforgeInstaller(neoforgeVersion);
        String sha1 = fetchInstallerSha1(installerUrl, "NeoForge " + neoforgeVersion);
        progress.accept(new InstallProgress(InstallProgress.Stage.LOADER, 0.1,
                "NeoForge " + neoforgeVersion));
        downloader.downloadOne(new DownloadTask(mirrors.resolve(installerUrl.toString()),
                installerJar, sha1, -1L));

        Set<String> before = installedIds(root);
        runInstallerWithRetry(root, installerJar, JavaVersionMapper.forMinecraft(mcVersion),
                progress, "NeoForge", progress2 -> { });
        GameInstance generated = detectInstalled(root, before, "neoforge", null, progress);
        GameInstance entry = applyLoaderPatch(target, readJson(generated.jsonFile()),
                LoaderInfo.LoaderType.NEOFORGE, neoforgeVersion, progress);
        RecycleBin.delete(generated.versionDir());
        return entry;
    }

    // ---------- 合并 profile ----------

    /** 把加載器 profile 合并进原版版本 JSON，并补全缺失文件。 */
    private GameInstance applyLoaderPatch(GameInstance target, JsonNode patch, LoaderInfo.LoaderType type,
                                          String loaderVersion, Consumer<InstallProgress> progress) {
        progress.accept(new InstallProgress(InstallProgress.Stage.LOADER, 0.2,
                type.displayName() + " " + loaderVersion));
        ObjectNode base = vanillaBase(target);
        ObjectNode merged = mergeJson(base, patch);
        merged.put("id", target.id());
        String mainClass = patch.path("mainClass").asText("");
        if (!mainClass.isBlank()) {
            merged.put("mainClass", mainClass);
        }
        // 自足版本：不再继承原版（PCL / HMCL 的行为）
        merged.remove("inheritsFrom");
        writeJson(target.jsonFile(), merged);

        GameInstance entry = scanner.scanSingle(target.root(), target.id())
                .orElseThrow(() -> new InstallException("写入加载器配置后无法识别实例"));
        entry.loader(new LoaderInfo(type, loaderVersion));
        versionInstaller.repair(entry, progress);
        progress.accept(new InstallProgress(InstallProgress.Stage.DONE, 1.0, entry.id()));
        return entry;
    }

    /** 取「干净的原版版本 JSON」：已经合并过加载器的，重新向 Mojang 要一份。 */
    private ObjectNode vanillaBase(GameInstance target) {
        ObjectNode current = readJson(target.jsonFile());
        if (!current.has("inheritsFrom") && !hasLoaderLibraries(current)) {
            return current;
        }
        Optional<MojangApi.VersionMeta> meta = mojang.versions().stream()
                .filter(version -> version.id().equals(target.id()))
                .findFirst();
        if (meta.isPresent()) {
            try {
                JsonNode fresh = mojang.versionJson(meta.get());
                if (fresh != null && fresh.isObject()) {
                    return (ObjectNode) fresh;
                }
            } catch (Exception error) {
                SafeLog.warn("重新读取原版版本 JSON 失败，改用现有文件");
            }
        }
        throw new InstallException("无法获取原版版本信息，请先在「版本」页删除该实例并重新安装，再装加载器");
    }

    /** 合并 libraries（同 name 后写覆盖前写）与 arguments（原样追加，不能去重）。 */
    private ObjectNode mergeJson(ObjectNode base, JsonNode patch) {
        ObjectNode merged = base.deepCopy();
        merged.remove("inheritsFrom");
        Map<String, JsonNode> byName = new LinkedHashMap<>();
        for (JsonNode library : base.path("libraries")) {
            if (!isLoaderLibrary(library)) {
                byName.put(library.path("name").asText(""), library);
            }
        }
        for (JsonNode library : patch.path("libraries")) {
            byName.put(library.path("name").asText(""), library);
        }
        ArrayNode libraries = mapper.createArrayNode();
        byName.values().forEach(libraries::add);
        merged.set("libraries", libraries);
        mergeArguments(merged, patch);
        if (patch.hasNonNull("minecraftArguments") && !merged.hasNonNull("minecraftArguments")) {
            merged.set("minecraftArguments", patch.get("minecraftArguments"));
        }
        if (patch.has("javaVersion")) {
            merged.set("javaVersion", patch.get("javaVersion"));
        }
        if (patch.has("minimumLauncherVersion")) {
            merged.set("minimumLauncherVersion", patch.get("minimumLauncherVersion"));
        }
        return merged;
    }

    /**
     * 合并 {@code arguments.game / arguments.jvm}。
     *
     * <p>**不能按字串去重**：Forge 会重复同一个 {@code --add-opens} 但值不同，
     * 去掉重复会让 JVM 把 value 当主类（Could not find or load main class）。
     */
    private void mergeArguments(ObjectNode merged, JsonNode patch) {
        JsonNode patchArguments = patch.path("arguments");
        if (!patchArguments.isObject()) {
            return;
        }
        ObjectNode target = merged.path("arguments").isObject()
                ? (ObjectNode) merged.get("arguments") : merged.putObject("arguments");
        for (String section : new String[]{"game", "jvm"}) {
            JsonNode extra = patchArguments.path(section);
            if (!extra.isArray()) {
                continue;
            }
            ArrayNode list = target.path(section).isArray()
                    ? (ArrayNode) target.get(section) : target.putArray(section);
            for (JsonNode item : extra) {
                list.add(item);
            }
        }
    }

    private static boolean hasLoaderLibraries(ObjectNode json) {
        for (JsonNode library : json.path("libraries")) {
            if (isLoaderLibrary(library)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLoaderLibrary(JsonNode library) {
        String name = library.path("name").asText("").toLowerCase(Locale.ROOT);
        return name.startsWith("net.fabricmc:") || name.startsWith("org.quiltmc:")
                || name.startsWith("net.minecraftforge:") || name.startsWith("net.neoforged:")
                || name.startsWith("cpw.mods:") || name.startsWith("optifine:");
    }

    private ObjectNode readJson(Path file) {
        try {
            return (ObjectNode) mapper.readTree(file.toFile());
        } catch (IOException error) {
            throw new InstallException("读取版本 JSON 失败：" + error.getMessage(), error);
        }
    }

    private void writeJson(Path file, ObjectNode node) {
        try {
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(node);
            Path temp = Files.createTempFile(file.getParent(), "loader-", ".tmp");
            Files.write(temp, bytes);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            throw new InstallException("写入版本 JSON 失败：" + error.getMessage(), error);
        }
    }

    // ---------- OptiFine ----------

    /** 只把 OptiFine 的 jar 放進該實例的 {@code mods/}（必須搭配 Forge 才有意義）。 */
    public void installOptiFineInto(GameInstance target, String mcVersion, String optifineVersion)
            throws Exception {
        String url = meta.optifineInstallerUrl(mcVersion, optifineVersion);
        Path mods = target.gameDir().resolve("mods");
        Files.createDirectories(mods);
        // 安全审计 v3 F-4：optifineVersion 来自镜像回来的 type+patch，未净化。
        // 直接拼进文件名时，"../../x" 会让 mods.resolve() 逐层吃掉前缀 → 写到 mods/ 之外。
        String safeVersion = PathSafety.safeFileName(
                String.valueOf(optifineVersion).replace('/', '_').replace('\\', '_'));
        String safeMc = PathSafety.safeFileName(String.valueOf(mcVersion).replace('/', '_')
                .replace('\\', '_'));
        String fileName = "OptiFine_" + safeMc + "_" + safeVersion + ".jar";
        Path destination = mods.resolve(fileName);
        if (!destination.normalize().startsWith(mods.normalize())) {
            throw new InstallException("OptiFine 版本号非法，已中止：" + optifineVersion);
        }
        downloader.downloadOne(new DownloadTask(mirrors.resolve(url), destination, null, -1L));
        SafeLog.info("OptiFine 已放入 mods：" + fileName + "（需要 Forge 才生效）");
    }

    public GameInstance installOptiFine(GameDirectory root, String mcVersion, String optifineVersion,
                                        Consumer<InstallProgress> progress) {
        progress.accept(new InstallProgress(InstallProgress.Stage.LOADER, 0.05,
                "OptiFine " + optifineVersion));
        GameInstance target = ensureVanilla(root, mcVersion, progress);
        try {
            installOptiFineInto(target, mcVersion, optifineVersion);
        } catch (Exception error) {
            throw new InstallException("OptiFine 下载失败：" + error.getMessage(), error);
        }
        progress.accept(new InstallProgress(InstallProgress.Stage.DONE, 1.0, "OptiFine"));
        return target;
    }

    // ---------- 原版实例 ----------

    /** 確保原版實例存在且檔案齊全（加載器要合併進「這個原版實例」）。 */
    private GameInstance ensureVanilla(GameDirectory root, String mcVersion,
                                       Consumer<InstallProgress> progress) {
        Optional<GameInstance> existing = scanner.scanSingle(root, mcVersion);
        if (existing.isPresent() && versionInstaller.looksComplete(existing.get())) {
            return existing.get();
        }
        Optional<MojangApi.VersionMeta> meta = mojang.versions().stream()
                .filter(version -> version.id().equals(mcVersion))
                .findFirst();
        if (meta.isEmpty()) {
            meta = mojang.versions(true).stream()
                    .filter(version -> version.id().equals(mcVersion))
                    .findFirst();
        }
        MojangApi.VersionMeta metaInfo = meta.orElseThrow(
                () -> new InstallException("Mojang 版本清单里没有 " + mcVersion));
        return versionInstaller.install(root, metaInfo, progress);
    }

    /** Forge / NeoForge 安裝器要求遊戲根目錄有 {@code launcher_profiles.json}，沒有就補一個空的。 */
    private static void ensureProfilesStub(GameDirectory root) {
        Path profiles = root.path().resolve("launcher_profiles.json");
        if (Files.isRegularFile(profiles, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.createDirectories(root.path());
            Files.writeString(profiles,
                    "{\n  \"profiles\": {},\n  \"settings\": {},\n  \"version\": 3\n}\n",
                    StandardCharsets.UTF_8);
        } catch (IOException error) {
            SafeLog.warn("创建 launcher_profiles.json 失败，安装器可能报错");
        }
    }

    // ---------- 执行安装器 ----------

    /**
     * 加载器安装器失败时的做法（参考 PCL / HMCL）：
     * 1) **Java 16+ 的安装器走「无界面驱动」**，用启动器自己的 JVM 执行
     *    （我们的 driver class 是 Java 21 位元组码；用 Java 17 跑会 UnsupportedClassVersionError，
     *     实测 Forge 1.20.1 就是这样失败的）。
     *    安装器本身在 Java 21 上没问题，游戏执行期用哪个 Java 由版本 JSON 决定，跟这里无关。
     * 2) **Java 8/11 的老安装器（1.12.2 等）只能走 {@code -jar --installClient}**，
     *    所以先确认那个 Java 存在：本机有就用（精确或更高），没有就沿用自动补齐；
     * 3) 失败自动重试；若错误看起来是 **Java 版本不符**，老路径会自动换更高的 Java 再试；
     * 4) 仍失败才把「安装器日志位置 + 常见原因」丢给使用者，而不是只给一句「失败」。
     */
    private void runInstallerWithRetry(GameDirectory root, Path installerJar, int javaMajor,
                                       Consumer<InstallProgress> progress, String label,
                                       Consumer<InstallProgress> progressHook) {
        int java = Math.max(javaMajor, 8);
        boolean driverUsable = javaMajor >= 16;
        if (driverUsable) {
            SafeLog.info("[loader] " + label + " 安装器将在启动器自身的 Java "
                    + Runtime.version().feature() + " 上以无界面驱动执行（不需要另外找 Java "
                    + javaMajor + "）");
        } else {
            ensureInstallerJava(java, label, progress);
        }
        Exception last = null;
        for (int round = 1; round <= 3; round++) {
            try {
                runInstaller(root, installerJar, java, progress, label, line -> { }, driverUsable);
                return;
            } catch (Exception error) {
                last = error;
                SafeLog.warn(label + " 安装器第 " + round + " 轮失败（Java " + java + "）："
                        + oneLine(error.getMessage()));
                if (!driverUsable && looksLikeJavaProblem(error.getMessage()) && round < 3) {
                    int next = nextJavaMajor(java);
                    if (next > 0) {
                        SafeLog.warn("[loader] 看起来是 Java 版本不符，" + label
                                + " 安装器改用 Java " + next + " 再试一次");
                        try {
                            ensureInstallerJava(next, label, progress);
                            java = next;
                            continue;
                        } catch (Exception ensureFailed) {
                            SafeLog.warn("[loader] 准备 Java " + next + " 失败："
                                    + oneLine(ensureFailed.getMessage()));
                        }
                    }
                }
                if (round < 3) {
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        Path logFile = installerJar.resolveSibling(installerJar.getFileName() + ".log");
        throw new InstallException(label + " 安装器执行失败（已重试）："
                + (last == null ? "" : last.getMessage())
                + "\n\n常见原因与处理：\n"
                + "· Java 版本不符：本启动器已自动尝试 Java " + java + "，若仍失败请在"
                + "「设定 → Java 与记忆体」手动指定该版本需要的 Java\n"
                + "· 档案被占用：关闭其他启动器 / 游戏，或重开启动器\n"
                + "· 网路中断：安装器会自行下载依赖库，请确认网路可连到 Maven 仓库\n"
                + "· 安装器日志：" + logFile + "\n"
                + "（安装器原始日志通常在同目录的 *.jar.log）");
    }

    /** 安裝器用的 Java：本機有就用，沒有就自動補齊（沿用 JavaManager.ensureFor）。 */
    private void ensureInstallerJava(int major, String label, Consumer<InstallProgress> progress) {
        Optional<JdkInfo> existing = javaManager.findBest(major);
        if (existing.isPresent()) {
            JdkInfo jdk = existing.get();
            SafeLog.info("[loader] " + label + " 安装器使用 Java " + jdk.majorVersion()
                    + "（" + jdk.home()
                    + (jdk.majorVersion() > major ? "，比需要的 " + major + " 更高" : "") + "）");
            return;
        }
        SafeLog.info("[loader] 本机没有可用的 Java " + major + "，" + label
                + " 安装器需要先补齐 → 开始自动下载");
        try {
            JdkInfo installed = javaManager.ensureFor(major, downloader, mirrors, progress);
            SafeLog.info("[loader] 已补齐 Java " + installed.majorVersion()
                    + "（" + installed.home() + "）");
        } catch (Exception error) {
            throw new InstallException(label + " 安装器需要 Java " + major
                    + "，但自动补齐失败：" + oneLine(error.getMessage())
                    + "\n\n可在「设定 → Java 与记忆体」手动指定 Java，"
                    + "或确认网路可连到 Adoptium（adoptium.net）。", error);
        }
    }

    /** 錯誤訊息看起來是「Java 版本不符」嗎？（安裝器用新的 class 檔案格式跑在舊 Java 上） */
    static boolean looksLikeJavaProblem(String message) {
        if (message == null) {
            return false;
        }
        String text = message.toLowerCase(Locale.ROOT);
        return text.contains("unsupportedclassversionerror")
                || text.contains("unsupported class file major version")
                || text.contains("class file version")
                || text.contains("has been compiled by a more recent version")
                || text.contains("requires java");
    }

    /** 下一個可以試的 Java 主版本（只往上、只挑 LTS）。 */
    static int nextJavaMajor(int current) {
        int[] lts = {17, 21, 25};
        for (int candidate : lts) {
            if (candidate > current) {
                return candidate;
            }
        }
        return 0;
    }

    private static String oneLine(String message) {
        if (message == null) {
            return "";
        }
        String text = message.replace('\r', ' ').replace('\n', ' ').trim();
        return text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }

    /**
     * 执行安装器本体。
     *
     * <p>三件事让它不会「假卡死」：
     * <ul>
     *   <li>关掉子进程的 stdin：某些安装器会停下来等「是否继续」，无 console 时会永远等下去；</li>
     *   <li>看门狗：每 60 秒记录一次「仍在执行 / 几秒没有新输出」，日志就不会看起来像冻住；</li>
     *   <li>超时 20 分钟后强杀，并把最后 1500 字输出附在错误讯息里。</li>
     * </ul>
     */
    private void runInstaller(GameDirectory root, Path installerJar, int javaMajor,
                              Consumer<InstallProgress> progress, String label,
                              Consumer<String> lineConsumer, boolean driverUsable) {
        if (!Files.isRegularFile(installerJar, LinkOption.NOFOLLOW_LINKS)) {
            throw new InstallException(label + " 安装器下载失败");
        }
        JdkInfo jdk;
        if (driverUsable) {
            // 驱动是 Java 21 位元组码 → 用启动器自己的 JVM（不会 UnsupportedClassVersionError）
            jdk = selfJdk();
        } else {
            try {
                jdk = javaManager.ensureFor(javaMajor, downloader, mirrors, progress);
            } catch (Exception error) {
                throw new InstallException("安装 " + label + " 需要一个可用的 Java " + javaMajor
                        + "：" + error.getMessage(), error);
            }
        }
        SafeLog.info("[loader] " + label + " 安装器 → Java " + jdk.majorVersion()
                + "（" + jdk.javaExecutable() + "）");

        progress.accept(new InstallProgress(InstallProgress.Stage.LOADER, 0.25, label));
        // 先把 install_profile 里的依赖库用「我们自己的下载器」抓好（走 BMCLAPI 镜像、多执行绪）。
        // 这是关键：这个版本的安装器**完全不理会 install profile 的 mirror 栏位**，
        // 依赖库一律从 maven.neoforged.net / files.minecraftforge.net 下载（国内必然超时/卡死）。
        // 预先抓好之后，安装器只会做「File exists: Checksum validated」然后跳过。
        prefetchProfileLibraries(installerJar, root, progress, label);
        if (driverUsable) {
            // official 映射表（mojmaps）：安装器的 DOWNLOAD_MOJMAPS 处理器会去 Mojang 下载，
            // 但紧接著的 MERGE_MAPPING 需要这个档案；我们先自己抓好并**移除**那个处理器，
            // 否则不是卡在 Mojang 就是 "Right does not exist"（两者都实测过）。
            boolean mojmapsReady = prefetchMojmaps(installerJar, root, progress, label);
            // 再走「无界面驱动」（对标 PCL）：headless + 指定镜像（mojmaps 没抓好就保留处理器）。
            int driverExit = runInstallerViaDriver(root, installerJar, jdk, progress, label,
                    lineConsumer, mojmapsReady);
            if (driverExit == 0) {
                progress.accept(new InstallProgress(InstallProgress.Stage.LOADER, 0.75, label));
                return;
            }
            if (driverExit != 2) {
                throw new InstallException(label + " 安装器失败（驱动退出码 " + driverExit
                        + "），详见控制台/日志");
            }
            // 退出码 2 = 这个安装器不是 SimpleInstaller 结构（很旧或第三方打包）→ 退回 -jar
            SafeLog.warn("[loader] " + label + " 安装器不支援无界面驱动，改用 -jar --installClient");
        }
        int legacyExit = runInstallerLegacy(root, installerJar, jdk, progress, label, lineConsumer);
        if (legacyExit != 0) {
            throw new InstallException(label + " 安装器退出码 " + legacyExit + "（-jar 方式）");
        }
        progress.accept(new InstallProgress(InstallProgress.Stage.LOADER, 0.75, label));
    }

    /** 啟動器自己正在跑的 JVM（驅動必須用 Java 16+，我們的 class 是 21）。 */
    private static JdkInfo selfJdk() {
        Path home = Path.of(System.getProperty("java.home", ""));
        int major = Runtime.version().feature();
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path executable = home.resolve("bin").resolve(windows ? "java.exe" : "java");
        return new JdkInfo(home, String.valueOf(major), major, false, 64);
    }

    /**
     * 依 {@code install_profile.json} + 内嵌 {@code version.json} 预先下载安装器的依赖库。
     *
     * <p><b>两份清单都要</b>：install_profile 的 libraries 是「安装器的工具链」，
     * 内嵌 version.json（{@code profile.json} 栏位指向它）的 libraries 是「加载器执行期」；
     * 安装器会**两份都下载**（实测：只预抓第一份时，安装器卡在
     * {@code cpw.mods:bootstraplauncher:2.0.2} / {@code net.neoforged:mergetool:2.0.0:api} 失败）。
     *
     * <p>目标目录是 {@code <游戏根>/libraries/<maven 路径>}，与安装器／启动时共用同一份。
     * 失败不中断（交给安装器自己再试），但会记 warn。
     */
    private void prefetchProfileLibraries(Path installerJar, GameDirectory root,
                                          Consumer<InstallProgress> progress, String label) {
        List<JsonNode> libraries = readInstallerLibraries(installerJar);
        if (libraries.isEmpty()) {
            return;
        }
        Path librariesDir = root.path().resolve("libraries");
        List<DownloadTask> tasks = new ArrayList<>();
        int skipped = 0;
        for (JsonNode library : libraries) {
            JsonNode artifact = library.path("downloads").path("artifact");
            String path = artifact.path("path").asText("");
            String url = artifact.path("url").asText("");
            String sha1 = artifact.path("sha1").asText("");
            if (path.isBlank()) {
                path = mavenPath(library.path("name").asText(""));
            }
            if (path.isBlank()) {
                continue;
            }
            if (url.isBlank()) {
                url = mavenBaseFor(library.path("name").asText("")) + path;
            }
            if (url.isBlank()) {
                continue;
            }
            try {
                Path destination = com.brisklauncher.mcl.security.PathSafety
                        .resolveUnder(librariesDir, path);
                if (Files.isRegularFile(destination) && !sha1.isBlank()
                        && sha1.equalsIgnoreCase(com.brisklauncher.mcl.util.FileHashes.sha1(destination))) {
                    skipped++;
                    continue;
                }
                tasks.add(new DownloadTask(mirrors.mirrorFirst(url), destination,
                        sha1.isBlank() ? null : sha1, -1));
            } catch (Exception ignored) {
                // 单一依赖库的资料有问题就跳过，交给安装器自己处理
            }
        }
        if (tasks.isEmpty()) {
            SafeLog.info("[loader] " + label + " 依赖库已齐全（" + skipped + " 个已存在），直接执行安装器");
            return;
        }
        SafeLog.info("[loader] " + label + " 预先下载安装器依赖库：" + tasks.size() + " 个（已存在 "
                + skipped + " 个），走镜像避免安装器卡在官方 maven");
        progress.accept(new InstallProgress(InstallProgress.Stage.LOADER, 0.18,
                label + " · 依赖库 " + tasks.size() + " 个"));
        try {
            downloader.downloadAll(tasks, snapshot -> progress.accept(new InstallProgress(
                    InstallProgress.Stage.LOADER, 0.18 + 0.3 * snapshot.ratio(),
                    label + " · 依赖库 " + snapshot.completed() + "/" + snapshot.total())));
        } catch (Exception error) {
            SafeLog.warn("[loader] " + label + " 依赖库预先下载有失败（交给安装器重试）："
                    + error.getClass().getSimpleName());
        }
    }

    /**
     * 预先抓好「官方映射表（mojmaps）」。
     *
     * <p>为甚么要自己做：NeoForge 的 install profile 有两个处理器依赖它——
     * {@code DOWNLOAD_MOJMAPS}（去 Mojang 下载官方映射）与 {@code MERGE_MAPPING}
     * （把 MCP 映射与官方映射合并）。Mojang 的 {@code piston-data} 在国内常连不上，
     * 而 PCL 的作法是「直接移除 DOWNLOAD_MOJMAPS」——但那样 MERGE_MAPPING 就会
     * {@code Right does not exist}（实测）。所以这里先把映射档抓到
     * {@code {MOJMAPS}} 的预期路径，再让驱动移除那个下载处理器。
     *
     * @return 映射档是否已就位（false 时呼叫端要保留 DOWNLOAD_MOJMAPS）
     */
    private boolean prefetchMojmaps(Path installerJar, GameDirectory root,
                                    Consumer<InstallProgress> progress, String label) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(installerJar.toFile())) {
            java.util.zip.ZipEntry entry = zip.getEntry("install_profile.json");
            if (entry == null) {
                return false;
            }
            JsonNode profile;
            try (var input = zip.getInputStream(entry)) {
                profile = mapper.readTree(input.readAllBytes());
            }
            String mcVersion = profile.path("minecraft").asText("");
            String target = profile.path("data").path("MOJMAPS").path("client").asText("");
            if (mcVersion.isBlank() || target.isBlank()) {
                SafeLog.info("[loader] " + label + " 安装器不需要官方映射表");
                return false;
            }
            String relative = mavenPath(stripBrackets(target));
            if (relative.isBlank()) {
                return false;
            }
            Path destination = com.brisklauncher.mcl.security.PathSafety
                    .resolveUnder(root.path().resolve("libraries"), relative);
            if (Files.isRegularFile(destination) && Files.size(destination) > 1024) {
                SafeLog.info("[loader] " + label + " 官方映射表已存在：" + destination.getFileName());
                return true;
            }
            Path versionJson = root.path().resolve("versions").resolve(mcVersion)
                    .resolve(mcVersion + ".json");
            if (!Files.isRegularFile(versionJson)) {
                SafeLog.warn("[loader] 找不到原版版本 JSON，无法预取映射表：" + versionJson);
                return false;
            }
            JsonNode mappings = mapper.readTree(versionJson.toFile())
                    .path("downloads").path("client_mappings");
            String url = mappings.path("url").asText("");
            String sha1 = mappings.path("sha1").asText("");
            if (url.isBlank()) {
                SafeLog.warn("[loader] 版本 JSON 没有 client_mappings，无法预取映射表");
                return false;
            }
            long size = mappings.path("size").asLong(-1);
            SafeLog.info("[loader] " + label + " 预先下载官方映射表（" + mcVersion + "）→ "
                    + destination.getFileName());
            progress.accept(new InstallProgress(InstallProgress.Stage.LOADER, 0.42,
                    label + " · 官方映射表"));
            downloader.downloadOne(new DownloadTask(mirrors.mirrorFirst(url), destination,
                    sha1.isBlank() ? null : sha1, size));
            return Files.isRegularFile(destination) && Files.size(destination) > 1024;
        } catch (Exception error) {
            SafeLog.warn("[loader] 预取官方映射表失败（改让安装器自己下载）："
                    + error.getClass().getSimpleName() + " · " + error.getMessage());
            return false;
        }
    }

    /** {@code [net.minecraft:client:1.21.1-xxx:mappings@txt]} → {@code net.minecraft:client:...}。 */
    private static String stripBrackets(String value) {
        String text = value.trim();
        if (text.startsWith("[")) {
            text = text.substring(1);
        }
        if (text.endsWith("]")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    /** 讀安裝器 jar 內的 {@code install_profile.json}；沒有就回 null。 */
    private JsonNode readInstallProfile(Path installerJar) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(installerJar.toFile())) {
            java.util.zip.ZipEntry entry = zip.getEntry("install_profile.json");
            if (entry == null) {
                return null;
            }
            try (var input = zip.getInputStream(entry)) {
                return mapper.readTree(input.readAllBytes());
            }
        } catch (Exception error) {
            SafeLog.warn("[loader] 读取 install_profile.json 失败：" + error.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 收集安装器需要的全部依赖库节点：
     * {@code install_profile.json} 的 {@code libraries} ＋ 内嵌 {@code version.json} 的 {@code libraries}。
     */
    private List<JsonNode> readInstallerLibraries(Path installerJar) {
        List<JsonNode> result = new ArrayList<>();
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(installerJar.toFile())) {
            java.util.zip.ZipEntry profileEntry = zip.getEntry("install_profile.json");
            if (profileEntry == null) {
                return result;
            }
            JsonNode profile;
            try (var input = zip.getInputStream(profileEntry)) {
                profile = mapper.readTree(input.readAllBytes());
            }
            for (JsonNode library : profile.path("libraries")) {
                result.add(library);
            }
            // install_profile 的 "json" 栏位指向内嵌的版本 JSON（例如 /version.json）
            String embedded = profile.path("json").asText("");
            if (!embedded.isBlank()) {
                String name = embedded.startsWith("/") ? embedded.substring(1) : embedded;
                java.util.zip.ZipEntry versionEntry = zip.getEntry(name);
                if (versionEntry != null) {
                    try (var input = zip.getInputStream(versionEntry)) {
                        JsonNode version = mapper.readTree(input.readAllBytes());
                        for (JsonNode library : version.path("libraries")) {
                            result.add(library);
                        }
                    }
                } else {
                    SafeLog.warn("[loader] 安装器内找不到 " + name + "（内嵌版本 JSON）");
                }
            }
        } catch (Exception error) {
            SafeLog.warn("[loader] 读取安装器依赖库清单失败：" + error.getClass().getSimpleName());
        }
        return result;
    }

    /** {@code net.neoforged:foo:1.2.3:classifier@ext} → maven 路徑。 */
    private static String mavenPath(String coordinates) {
        if (coordinates == null || coordinates.isBlank()) {
            return "";
        }
        String raw = coordinates;
        String extension = "jar";
        int at = raw.indexOf('@');
        if (at > 0) {
            extension = raw.substring(at + 1);
            raw = raw.substring(0, at);
        }
        String[] parts = raw.split(":");
        if (parts.length < 3) {
            return "";
        }
        String group = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts[2];
        String classifier = parts.length > 3 ? "-" + parts[3] : "";
        return group + "/" + artifact + "/" + version + "/"
                + artifact + "-" + version + classifier + "." + extension;
    }

    /** 依 group 猜 maven 根（NeoForge / Forge）。 */
    private static String mavenBaseFor(String coordinates) {
        String raw = coordinates == null ? "" : coordinates.toLowerCase(Locale.ROOT);
        if (raw.startsWith("net.neoforged") || raw.startsWith("cpw.mods")) {
            return "https://maven.neoforged.net/releases/";
        }
        if (raw.startsWith("net.minecraftforge")) {
            return "https://maven.minecraftforge.net/";
        }
        if (raw.startsWith("org.ow2.asm") || raw.startsWith("com.google")
                || raw.startsWith("org.apache")) {
            return "https://maven.minecraftforge.net/";
        }
        return "https://maven.neoforged.net/releases/";
    }

    /**
     * 用 {@link ForgeInstallDriver} 执行安装器（子进程）。
     *
     * @return 子进程退出码（0 成功 / 1 安装器回报失败 / 2 不支援驱动 / 3 其它例外 / -1 无法启动）
     */
    private int runInstallerViaDriver(GameDirectory root, Path installerJar, JdkInfo jdk,
                                      Consumer<InstallProgress> progress, String label,
                                      Consumer<String> lineConsumer, boolean dropMojmaps) {
        String ownClasses = ownClasspath();
        if (ownClasses == null || ownClasses.isBlank()) {
            SafeLog.warn("[loader] 找不到本启动器的 classpath，无法使用无界面驱动");
            return 2;
        }
        List<String> command = new ArrayList<>();
        command.add(jdk.javaExecutable().toString());
        if (jdk.majorVersion() >= 9) {
            // Forge 安装器在 Java 9+ 会反射到 bootstraplauncher；模组不存在时 JVM 只警告不报错
            command.add("--add-exports");
            command.add("cpw.mods.bootstraplauncher/cpw.mods.bootstraplauncher=ALL-UNNAMED");
        }
        command.add("-Djava.awt.headless=true");
        command.add("-cp");
        command.add(ownClasses + File.pathSeparator + installerJar.toAbsolutePath());
        command.add("com.brisklauncher.mcl.game.install.ForgeInstallDriver");
        command.add(root.path().toAbsolutePath().toString());
        command.add(mirrors.installerMavenMirror());
        // mojmaps 已由我们抓好 → 让驱动移除 DOWNLOAD_MOJMAPS；没抓好就保留它自己去下载
        command.add(dropMojmaps ? "drop-mojmaps" : "keep-mojmaps");
        SafeLog.info("[loader] " + label + " 安装器以无界面驱动执行（镜像："
                + (mirrors.installerMavenMirror().isBlank() ? "官方" : mirrors.installerMavenMirror())
                + "，mojmaps：" + (dropMojmaps ? "已预取" : "交给安装器") + "）");
        return runProcess(command, root, progress, label, lineConsumer, 20);
    }

    /** 舊方式：{@code java -jar installer.jar --installClient}（只在驅動不支援時用）。 */
    private int runInstallerLegacy(GameDirectory root, Path installerJar, JdkInfo jdk,
                                   Consumer<InstallProgress> progress, String label,
                                   Consumer<String> lineConsumer) {
        List<String> command = List.of(
                jdk.javaExecutable().toString(),
                "-Djava.awt.headless=true",
                "-jar", installerJar.toString(),
                "--installClient");
        return runProcess(command, root, progress, label, lineConsumer, 20);
    }

    /** 本啟動器自己的 classpath（installDist 是 lib/*.jar，開發時是 target/classes）。 */
    private static String ownClasspath() {
        try {
            java.security.CodeSource source = ForgeInstallDriver.class
                    .getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            return Path.of(source.getLocation().toURI()).toAbsolutePath().toString();
        } catch (Exception error) {
            return null;
        }
    }

    /**
     * 跑子进程并收集输出。
     *
     * @param timeoutMinutes 硬性上限（预设 20 分钟）
     * @return 退出码（-1 表示无法启动）
     */
    private int runProcess(List<String> command, GameDirectory root,
                           Consumer<InstallProgress> progress, String label,
                           Consumer<String> lineConsumer, int timeoutMinutes) {
        progress.accept(new InstallProgress(InstallProgress.Stage.LOADER, 0.3, label));
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(root.path().toFile())
                .redirectErrorStream(true);
        try {
            Process process = builder.start();
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
                // 关不掉就算了，不影响主流程
            }
            StringBuilder tail = new StringBuilder();
            long[] lastOutputAt = {System.currentTimeMillis()};
            Thread reader = Thread.ofVirtual().start(() -> {
                try (BufferedReader bufferedReader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = bufferedReader.readLine()) != null) {
                        if (!line.isBlank()) {
                            lineConsumer.accept(line);
                            lastOutputAt[0] = System.currentTimeMillis();
                            synchronized (tail) {
                                tail.append(line).append('\n');
                                if (tail.length() > 8000) {
                                    tail.delete(0, tail.length() - 8000);
                                }
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // 进程结束时流会关闭，属正常
                }
            });
            Thread watchdog = Thread.ofVirtual().start(() -> {
                while (process.isAlive()) {
                    try {
                        Thread.sleep(60_000);
                    } catch (InterruptedException interrupted) {
                        return;
                    }
                    if (!process.isAlive()) {
                        return;
                    }
                    long idle = (System.currentTimeMillis() - lastOutputAt[0]) / 1000;
                    SafeLog.info("[loader] " + label + " 安装器仍在执行（" + idle + " 秒没有新输出）");
                    progress.accept(new InstallProgress(InstallProgress.Stage.LOADER, 0.5,
                            label + " · 执行中 " + idle + "s"));
                }
            });

            boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
            if (!finished) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new InstallException(label + " 安装器超时（" + timeoutMinutes + " 分钟）");
            }
            watchdog.interrupt();
            reader.join(2000);
            int exit = process.exitValue();
            if (exit != 0) {
                SafeLog.warn("[loader] " + label + " 安装器退出码 " + exit + "，输出结尾：\n"
                        + tailText(tail));
            }
            return exit;
        } catch (IOException error) {
            throw new InstallException("无法启动 " + label + " 安装器：" + error.getMessage(), error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new InstallException(label + " 安装被中断", error);
        }
    }

    private static String tailText(StringBuilder tail) {
        synchronized (tail) {
            String text = tail.toString().trim();
            return text.length() > 1500 ? text.substring(text.length() - 1500) : text;
        }
    }

    /**
     * 安装器通常自己写版本目录。优先找「本次新增」的目录；
     * 找不到新增（安装器复用了同名版本）时退回「包含关键字且最新」。
     */
    private GameInstance detectInstalled(GameDirectory root, Set<String> before, String keyword,
                                         String excludeKeyword, Consumer<InstallProgress> progress) {
        List<GameInstance> entries = scanner.scan(root);
        List<GameInstance> created = new ArrayList<>();
        List<GameInstance> matches = new ArrayList<>();
        for (GameInstance entry : entries) {
            String id = entry.id().toLowerCase(Locale.ROOT);
            if (!id.contains(keyword) || (excludeKeyword != null && id.contains(excludeKeyword))) {
                continue;
            }
            if (!before.contains(entry.id())) {
                created.add(entry);
            }
            matches.add(entry);
        }
        if (!created.isEmpty()) {
            created.sort((a, b) -> Long.compare(lastModified(b.jsonFile()), lastModified(a.jsonFile())));
            return created.get(0);
        }
        if (matches.isEmpty()) {
            throw new InstallException("安装器执行成功，但没有找到生成的版本目录");
        }
        matches.sort((a, b) -> Long.compare(lastModified(b.jsonFile()), lastModified(a.jsonFile())));
        SafeLog.warn("安装器没有新增版本目录，回退到最新的 " + matches.get(0).id());
        return matches.get(0);
    }

    private static long lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    // ---------- 查询 / 删除 ----------

    /** 某個 MC 版本可用的加載器版本清單（NeoForge 1.20.1 之類不支援的版本回空清單）。 */
    public List<LoaderMetaClient.LoaderVersion> availableLoaders(LoaderInfo.LoaderType type,
                                                                 String mcVersion) {
        return switch (type) {
            case FABRIC -> meta.fabricLoaders(mcVersion);
            case QUILT -> meta.quiltLoaders(mcVersion);
            case FORGE -> meta.forgeVersions(mcVersion);
            case NEOFORGE -> meta.neoforgeVersions(mcVersion);
            case OPTIFINE, VANILLA -> List.of();
        };
    }

    /** 刪除實例（走資源回收筒）；拒絕越界路徑。 */
    /**
     * 删除实例（整个版本资料夹丢回收站）。
     *
     * <p>采「先改名、再回收」两阶段：
     * <ol>
     *   <li>把 {@code versions/<id>} 改名成 {@code versions/<id>.deleting-<时间>}——
     *       改名只是目录项操作，成功就是「这个版本已经不在了」；</li>
     *   <li>再丢回收站。若因为档案被占用而失败，<b>把名字改回去</b>并报错，
     *       这样版本列表不会出现「提示删除失败、但版本已经消失」的鬼状态。</li>
     * </ol>
     */
    public void deleteInstance(GameInstance entry) {
        Path dir = entry.versionDir();
        if (!dir.startsWith(entry.root().versionsDir())) {
            throw new InstallException("拒绝删除版本目录之外的路径");
        }
        if (entry.root().path().equals(dir)) {
            throw new InstallException("拒绝删除游戏根目录");
        }
        if (!Files.isDirectory(dir)) {
            return;   // 已经不在了：视为成功
        }
        Path staging = dir.resolveSibling(dir.getFileName() + ".deleting-"
                + System.currentTimeMillis());
        try {
            Files.move(dir, staging, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception moveError) {
            // 连改名都不行（另一个启动器正在用、权限不足…）：什么都没动，直接报错
            throw new InstallException(com.brisklauncher.mcl.i18n.I18n.t(
                    "version.delete.busy", dir.getFileName().toString(),
                    moveError.getClass().getSimpleName()));
        }
        if (RecycleBin.delete(staging)) {
            SafeLog.info("[delete] 已把版本 " + entry.id() + " 丢进回收站");
            return;
        }
        // 回收站删不掉（档案被占用）：还原成原名，避免「列表消失但档案还在」
        try {
            Files.move(staging, dir, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            throw new InstallException(com.brisklauncher.mcl.i18n.I18n.t(
                    "version.delete.busy", entry.id(), "files in use"));
        } catch (InstallException already) {
            throw already;
        } catch (Exception restoreError) {
            SafeLog.warn("[delete] 还原失败，残留目录：" + staging);
            throw new InstallException(com.brisklauncher.mcl.i18n.I18n.t(
                    "version.delete.partial", entry.id(), staging.toString()));
        }
    }

    public boolean hasSaves(GameInstance entry) {
        Path saves = entry.gameDir().resolve("saves");
        if (!Files.isDirectory(saves, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (Stream<Path> stream = Files.list(saves)) {
            return stream.findAny().isPresent();
        } catch (IOException error) {
            return false;
        }
    }

    public Set<String> installedIds(GameDirectory root) {
        Set<String> ids = new LinkedHashSet<>();
        for (GameInstance entry : scanner.scan(root)) {
            ids.add(entry.id());
        }
        return ids;
    }
}
