package com.brisklauncher.mcl.modpack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.brisklauncher.mcl.AppPaths;
import com.brisklauncher.mcl.game.LoaderInfo;
import com.brisklauncher.mcl.game.GameInstance;
import com.brisklauncher.mcl.net.Http;
import com.brisklauncher.mcl.security.SafeLog;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * mrpack 导出。
 *
 * <p>实现选择：本地实例的 mod 未必来自 Modrinth，拿不到可信的下载地址与哈希，
 * 因此统一放进 {@code overrides/}（mrpack 规范允许），index 只写依赖信息。
 * 这样导出的包可以 100% 被其它启动器正确导入，不会出现“index 指向不存在的 URL”。
 */
public final class MrpackExporter {

    /** 导出范围（对应开发方案 4.6）。 */
    public record Options(boolean config, boolean mods, boolean resourcePacks,
                          boolean shaderPacks, boolean saves, boolean servers, boolean options) {

        public static Options defaults() {
            return new Options(true, true, true, true, false, true, true);
        }

        public static Options everything() {
            return new Options(true, true, true, true, true, true, true);
        }
    }

    /** 这些目录属于启动器/游戏运行产物，不进入整合包。 */
    private static final Set<String> EXCLUDED_DIRS = Set.of(
            "logs", "crash-reports", "versions", "libraries", "assets", "natives",
            "CustomSkinLoader", "downloads", "screenshots", "debug", "usercache",
            ".mcl-backup", "patched", "mods.toml");

    private final ObjectMapper mapper = Http.mapper();
    /** 可选：用于把「Modrinth 上能找到的 mod」写成下载条目，而不是打包进 overrides。 */
    private com.brisklauncher.mcl.download.remote.ModrinthClient modrinthClient;
    /** 已经写成下载条目的相对路径（这些不再打进 overrides）。 */
    private final java.util.Set<String> downloadPaths = new java.util.HashSet<>();

    public void setModrinthClient(com.brisklauncher.mcl.download.remote.ModrinthClient client) {
        this.modrinthClient = client;
    }

    public Path defaultDestination(GameInstance entry) {
        Path dir = AppPaths.launcherRoot().resolve("modpacks");
        String base = entry.id().replaceAll("[\\\\/:*?\"<>|]", "-");
        Path candidate = dir.resolve(base + ".mrpack");
        int suffix = 1;
        while (Files.exists(candidate)) {
            candidate = dir.resolve(base + " (" + suffix + ").mrpack");
            suffix++;
        }
        return candidate;
    }

    public Path export(GameInstance entry, Path destination, Options options) throws IOException {
        return export(entry, destination, optionsToSelection(entry, options));
    }

    /** 按相对路径前缀导出；用于资源包/光影/存档/特殊 Mod 文件夹的细选。 */
    public Path export(GameInstance entry, Path destination, Set<String> selectedPaths) throws IOException {
        Path gameDir = entry.gameDir();
        if (!Files.isDirectory(gameDir)) {
            throw new IOException("实例目录不存在：" + gameDir);
        }
        Files.createDirectories(destination.getParent());

        Path temp = Files.createTempFile(destination.getParent(), "mrpack-", ".tmp");
        try (OutputStream out = Files.newOutputStream(temp);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            writeIndex(zip, entry);
            writeOverrides(zip, gameDir, entry.id(), selectedPaths);
        }
        Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
        SafeLog.info("已导出整合包：" + destination.getFileName());
        return destination;
    }

    private Set<String> optionsToSelection(GameInstance entry, Options options) {
        Set<String> selected = new java.util.LinkedHashSet<>();
        if (options.mods()) {
            selected.add("mods");
        }
        if (options.config()) {
            selected.add("config");
            selected.add("defaultconfigs");
        }
        if (options.resourcePacks()) {
            selected.add("resourcepacks");
        }
        if (options.shaderPacks()) {
            selected.add("shaderpacks");
        }
        if (options.saves()) {
            selected.add("saves");
        }
        if (options.servers()) {
            selected.add("servers.dat");
        }
        if (options.options()) {
            selected.add("options.txt");
        }
        // 旧行为会带上未被排除的特殊 Mod 数据目录；保留它们
        for (String top : specialTopLevel(entry.gameDir())) {
            selected.add(top);
        }
        return selected;
    }

    private static Set<String> specialTopLevel(Path gameDir) {
        Set<String> result = new java.util.LinkedHashSet<>();
        Set<String> known = Set.of("mods", "config", "defaultconfigs", "resourcepacks",
                "shaderpacks", "saves", "servers.dat", "options.txt");
        try (Stream<Path> stream = Files.list(gameDir)) {
            for (Path path : stream.toList()) {
                String name = path.getFileName().toString();
                if (known.contains(name) || EXCLUDED_DIRS.contains(name)) {
                    continue;
                }
                result.add(name);
            }
        } catch (IOException ignored) {
            // 无法扫描时保持空
        }
        return result;
    }

    /**
     * 导出时确定原版版本号：实例自己认不出来（例如目录名是纯中文包名、
     * 又没有 inheritsFrom）就从版本 JSON 的 libraries 反推。
     *
     * <p>踩过的坑：先前把 {@code entry.mcVersion()}（其实等于目录名）写进
     * {@code dependencies.minecraft}，导出的包在别的启动器上一导入就找不到版本。
     */
    private String resolveMinecraftVersion(GameInstance entry) {
        String direct = entry.mcVersion();
        if (looksLikeVersion(direct)) {
            return direct;
        }
        try {
            java.nio.file.Path json = entry.versionDir().resolve(entry.id() + ".json");
            if (java.nio.file.Files.isRegularFile(json)) {
                String fromLibraries = com.brisklauncher.mcl.game.GameScanner
                        .mcVersionFromLibraries(mapper.readTree(json.toFile()));
                if (looksLikeVersion(fromLibraries)) {
                    SafeLog.info("[export] 由 libraries 推得原版版本：" + fromLibraries);
                    return fromLibraries;
                }
            }
        } catch (Exception error) {
            SafeLog.warn("[export] 推原版版本失败：" + error.getClass().getSimpleName());
        }
        SafeLog.warn("[export] 无法确定原版版本，暂用 " + direct);
        return direct;
    }

    private static boolean looksLikeVersion(String value) {
        return value != null && value.matches("\\d+\\.\\d+(\\.\\d+)?");
    }

    private void writeIndex(ZipOutputStream zip, GameInstance entry) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("formatVersion", 1);
        root.put("game", "minecraft");
        root.put("versionId", "1.0.0");
        root.put("name", entry.id());
        root.put("summary", "由 Brisk Launcher 从实例 " + entry.id() + " 导出");

        ObjectNode dependencies = root.putObject("dependencies");
        dependencies.put("minecraft", resolveMinecraftVersion(entry));
        LoaderInfo loader = entry.loader();
        if (loader != null && !loader.isVanilla() && !"unknown".equals(loader.version())) {
            switch (loader.type()) {
                case FABRIC -> dependencies.put("fabric-loader", loader.version());
                case QUILT -> dependencies.put("quilt-loader", loader.version());
                case FORGE -> dependencies.put("forge", loader.version());
                case NEOFORGE -> dependencies.put("neoforge", loader.version());
                default -> { }
            }
        }
        ArrayNode filesNode = root.putArray("files");
        collectDownloadEntries(entry, filesNode);


        zip.putNextEntry(new ZipEntry("modrinth.index.json"));
        zip.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
        zip.closeEntry();
    }

    /**
     * 把「Modrinth 上能反查到来源」的 mod 写成 index 的 files 下载条目。
     *
     * <p>这样导出的整合包不含这些 jar，导入时按 URL 下载即可（PCL 也是这样精简体积）。
     * 反查用文件 SHA-1（{@code /version_file/{sha1}}），只处理 mods 目录下的 jar/zip。
     */
    private void collectDownloadEntries(GameInstance entry, ArrayNode filesNode) {
        if (modrinthClient == null) {
            return;
        }
        Path gameDir = entry.gameDir();
        Path modsDir = gameDir.resolve("mods");
        if (!Files.isDirectory(modsDir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(modsDir)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                if (!(name.endsWith(".jar") || name.endsWith(".zip")) || name.endsWith(".disabled")) {
                    continue;
                }
                String sha1 = sha1Of(file);
                if (sha1 == null) {
                    continue;
                }
                com.brisklauncher.mcl.download.remote.ModrinthClient.FoundFile found;
                try {
                    found = modrinthClient.byFileHash(sha1);
                } catch (Exception error) {
                    continue;
                }
                if (found == null || found.file().downloadUrl() == null
                        || found.file().downloadUrl().isBlank()) {
                    continue;
                }
                String relative = "mods/" + file.getFileName();
                ObjectNode node = filesNode.addObject();
                node.put("path", relative);
                ObjectNode hashes = node.putObject("hashes");
                hashes.put("sha1", sha1);
                ArrayNode downloads = node.putArray("downloads");
                downloads.add(found.file().downloadUrl());
                node.put("fileSize", Files.size(file));
                downloadPaths.add(relative);
                SafeLog.info("整合包精简：mod 改为下载条目 " + file.getFileName());
            }
        } catch (Exception error) {
            SafeLog.warn("收集下载条目失败：" + error.getClass().getSimpleName());
        }
    }

    private static String sha1Of(Path file) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (Exception error) {
            return null;
        }
    }
    private void writeOverrides(ZipOutputStream zip, Path gameDir, String instanceId,
                                Set<String> selectedPaths) throws IOException {
        try (Stream<Path> stream = Files.walk(gameDir)) {
            List<Path> files = stream.filter(Files::isRegularFile).toList();
            for (Path file : files) {
                String relative = gameDir.relativize(file).toString().replace('\\', '/');
                if (downloadPaths.contains(relative)) {
                    // 已经在 index 里写成下载条目，不再打包（减小体积）
                    continue;
                }
                if (!include(relative, instanceId, selectedPaths)) {
                    continue;
                }
                try {
                    MrpackPathValidator.validate(relative);
                } catch (SecurityException e) {
                    SafeLog.warn("跳过非法导出路径：" + relative);
                    continue;
                }
                zip.putNextEntry(new ZipEntry("overrides/" + relative));
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
    }

    private boolean include(String relative, String instanceId, Set<String> selectedPaths) {
        String normalized = relative.toLowerCase(java.util.Locale.ROOT);
        String top = normalized.contains("/") ? normalized.substring(0, normalized.indexOf('/')) : normalized;
        if (EXCLUDED_DIRS.contains(top)) {
            return false;
        }
        // 实例自己的版本 JSON 与 natives 标记不属于整合包内容
        if (normalized.equals((instanceId + ".json").toLowerCase(java.util.Locale.ROOT))
                || normalized.equals(".mcl-natives")
                || normalized.endsWith(".part")) {
            return false;
        }
        for (String selected : selectedPaths) {
            String prefix = selected.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
            if (prefix.isBlank()) {
                continue;
            }
            if (normalized.equals(prefix) || normalized.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }
}
