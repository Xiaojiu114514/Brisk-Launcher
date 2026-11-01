package com.brisklauncher.mcl.modpack;

import com.fasterxml.jackson.databind.JsonNode;
import com.brisklauncher.mcl.MclContext;
import com.brisklauncher.mcl.download.DownloadTask;
import com.brisklauncher.mcl.download.HashUtils;
import com.brisklauncher.mcl.download.remote.ModrinthClient;
import com.brisklauncher.mcl.game.GameInstance;
import com.brisklauncher.mcl.game.LoaderInfo;
import com.brisklauncher.mcl.game.install.InstallProgress;
import com.brisklauncher.mcl.security.SafeLog;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 客户端 / 服务端分离导出：把一个客户端实例转成「可以直接开的服务端整合包」。
 *
 * <p>三件事：
 * <ol>
 *   <li><b>分离</b>：逐个 mod 用 SHA-1 反查 Modrinth，按 {@code server_side} 判断能不能上服务端，
 *       把「仅客户端」的（Sodium、Iris 这类）剔除；对不上的（自制/第三方）预设保留并在说明里标出。</li>
 *   <li><b>打包</b>：mods / config / defaultconfigs / server.properties / eula.txt /
 *       start.bat / start.sh / README.txt，全部写进一个 zip。</li>
 *   <li><b>补核心</b>：原版从 Mojang 的版本 JSON 取 {@code downloads.server.url}；
 *       Fabric 用官方的 {@code /server/jar} 启动器；其它加载器（Forge/NeoForge/Quilt）
 *       没有「一个 jar 就能跑」的形态，改成在 README 里写清楚要跑安装器。</li>
 * </ol>
 *
 * <p>下载核心走 {@code HttpDownloader}（先落暂存档再拷进 zip），避免把 50MB 的 server.jar
 * 整个读进内存——启动器本身只有 192MB 堆。
 */
public final class ServerPackExporter {

    /** 一個 mod 的判定結果。 */
    public record ModVerdict(String fileName, String title, String projectId,
                             String clientSide, String serverSide, boolean keep, String reason) {
        public boolean unknown() {
            return "unknown".equals(reason);
        }

        public boolean clientOnly() {
            return "client_only".equals(reason);
        }
    }

    /** 分析結果。 */
    public record Analysis(String instanceId, List<ModVerdict> mods) {

        public List<ModVerdict> kept() {
            return mods.stream().filter(ModVerdict::keep).toList();
        }

        public List<ModVerdict> excluded() {
            return mods.stream().filter(verdict -> !verdict.keep()).toList();
        }

        public long unknownCount() {
            return mods.stream().filter(ModVerdict::unknown).count();
        }
    }

    /** 導出選項。 */
    public record Options(boolean includeConfig, boolean includeDefaultConfigs, boolean includeEula,
                          boolean includeServerProperties, boolean includeCore, boolean keepUnknown,
                          int memoryMb, int port, String motd, int maxPlayers, boolean onlineMode) {

        public static Options defaults(int memoryMb) {
            return new Options(true, true, true, true, true, true,
                    Math.max(1024, memoryMb), 25565, "Brisk 服务器", 20, true);
        }
    }

    /** 核心資訊。 */
    private record Core(String fileName, URI url, String note) {
    }

    private ServerPackExporter() {
    }

    // ---------- 分析 ----------

    /**
     * 分析哪些 mod 能上服务端（会联网查 Modrinth；呼叫方放背景执行绪）。
     */
    public static Analysis analyze(MclContext context, GameInstance entry,
                                   Consumer<String> progress) {
        List<com.brisklauncher.mcl.game.mod.ModManager.ContentFile> files =
                context.mods().list(entry, com.brisklauncher.mcl.game.mod.ModManager.Kind.MODS);
        List<com.brisklauncher.mcl.game.mod.ModManager.ContentFile> jars = files.stream()
                .filter(file -> file.name().toLowerCase(Locale.ROOT).endsWith(".jar"))
                .toList();

        Map<String, String> projectOfFile = new LinkedHashMap<>();
        Map<String, String> fileNameOfProject = new LinkedHashMap<>();
        for (var jar : jars) {
            if (progress != null) {
                progress.accept(jar.name());
            }
            try {
                ModrinthClient.FoundFile found = context.modrinth()
                        .byFileHash(HashUtils.sha1(jar.path()));
                if (found != null && !found.projectId().isBlank()) {
                    projectOfFile.put(jar.name(), found.projectId());
                    fileNameOfProject.putIfAbsent(found.projectId(), jar.name());
                }
            } catch (Exception error) {
                SafeLog.warn("服务端导出：查不到 " + jar.name() + "（"
                        + error.getClass().getSimpleName() + "）");
            }
        }

        Map<String, ModrinthClient.Project> projects = new LinkedHashMap<>();
        if (!projectOfFile.isEmpty()) {
            try {
                for (ModrinthClient.Project project : context.modrinth()
                        .projects(new ArrayList<>(projectOfFile.values()))) {
                    projects.put(project.id(), project);
                }
            } catch (Exception error) {
                SafeLog.warn("服务端导出：取专案资讯失败（" + error.getClass().getSimpleName() + "）");
            }
        }

        List<ModVerdict> verdicts = new ArrayList<>();
        for (var jar : jars) {
            String projectId = projectOfFile.get(jar.name());
            ModrinthClient.Project project = projectId == null ? null : projects.get(projectId);
            if (project == null) {
                verdicts.add(new ModVerdict(jar.name(), jar.name(), "",
                        "", "", true, "unknown"));
                continue;
            }
            String serverSide = project.serverSide() == null ? "" : project.serverSide();
            String clientSide = project.clientSide() == null ? "" : project.clientSide();
            boolean serverOk = !"unsupported".equalsIgnoreCase(serverSide);
            verdicts.add(new ModVerdict(jar.name(), project.title(), projectId,
                    clientSide, serverSide, serverOk, serverOk ? "server_ok" : "client_only"));
        }
        return new Analysis(entry.id(), List.copyOf(verdicts));
    }

    // ---------- 导出 ----------

    /**
     * 打成可直接开的服务端整合包。
     *
     * @param targetZip 目标 zip（已存在的话会被覆盖）
     * @return 写出的 zip 路径
     */
    public static Path export(MclContext context, GameInstance entry, Analysis analysis,
                              Options options, Path targetZip,
                              Consumer<InstallProgress> progress) throws Exception {
        Path gameDir = entry.gameDir();
        Path parent = targetZip.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        progress.accept(new InstallProgress(InstallProgress.Stage.RESOLVING, 0.02,
                entry.id()));

        // 1) 核心（下载到暂存档，再流式拷进 zip）
        Core core = options.includeCore() ? resolveCore(context, entry) : null;
        Path coreTemp = null;
        if (core != null) {
            progress.accept(new InstallProgress(InstallProgress.Stage.CLIENT_JAR, 0.05,
                    core.fileName()));
            try {
                coreTemp = Files.createTempFile("mcl-server-core-", ".jar");
                // 必须先删掉：下载器把「没有 sha1/大小的已存在档案」视为已完成，
                // 留著 createTempFile 建出的空档会导致核心变成 0 位元组（实测踩过）。
                Files.deleteIfExists(coreTemp);
                context.downloader().downloadAll(List.of(new DownloadTask(
                                List.of(core.url()), coreTemp, null, -1)),
                        snapshot -> { });
                if (!Files.isRegularFile(coreTemp) || Files.size(coreTemp) <= 0) {
                    throw new IOException("核心下载后为空");
                }
            } catch (Exception error) {
                SafeLog.warn("服务端核心下载失败：" + error.getClass().getSimpleName());
                core = null;
                if (coreTemp != null) {
                    Files.deleteIfExists(coreTemp);
                    coreTemp = null;
                }
            }
        }

        List<ModVerdict> kept = analysis.kept().stream()
                .filter(verdict -> options.keepUnknown() || !verdict.unknown())
                .toList();
        String coreName = core == null ? "server.jar" : core.fileName();

        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(
                Files.newOutputStream(targetZip)))) {
            zip.setLevel(java.util.zip.Deflater.BEST_SPEED);

            // 2) 服务端可用的 mods
            int index = 0;
            for (ModVerdict verdict : kept) {
                Path source = gameDir.resolve("mods").resolve(verdict.fileName());
                if (!Files.isRegularFile(source)) {
                    continue;
                }
                putFile(zip, "mods/" + verdict.fileName(), source);
                index++;
                progress.accept(new InstallProgress(InstallProgress.Stage.LIBRARIES,
                        0.1 + 0.35 * index / Math.max(1, kept.size()), verdict.fileName()));
            }

            // 3) config / defaultconfigs（config 里也常有客户端专属设定，说明里会提）
            if (options.includeConfig()) {
                putTree(zip, gameDir.resolve("config"), "config");
            }
            if (options.includeDefaultConfigs()) {
                putTree(zip, gameDir.resolve("defaultconfigs"), "defaultconfigs");
            }

            // 4) eula / server.properties
            if (options.includeEula()) {
                putText(zip, "eula.txt", """
                        # 由 Brisk Launcher 導出
                        # eula=true 表示你已閱讀並同意 Minecraft EULA：
                        # https://aka.ms/MinecraftEULA
                        eula=true
                        """);
            }
            if (options.includeServerProperties()) {
                putText(zip, "server.properties", serverProperties(options));
            }

            // 5) 启动脚本
            putText(zip, "start.bat", startBat(options, coreName));
            putText(zip, "start.sh", startSh(options, coreName));

            // 6) 核心
            if (core != null && coreTemp != null && Files.isRegularFile(coreTemp)) {
                progress.accept(new InstallProgress(InstallProgress.Stage.FINISHING, 0.9,
                        core.fileName()));
                putFile(zip, core.fileName(), coreTemp);
            }

            // 7) 说明
            putText(zip, "README.txt", readme(entry, analysis, kept, options, core));

            progress.accept(new InstallProgress(InstallProgress.Stage.DONE, 1.0,
                    targetZip.getFileName().toString()));
        } finally {
            if (coreTemp != null) {
                try {
                    Files.deleteIfExists(coreTemp);
                } catch (Exception ignored) {
                    // 暂存档清不掉不影响结果
                }
            }
        }
        SafeLog.info("服务端整合包已导出：" + targetZip + "（mods " + kept.size() + " 个）");
        return targetZip;
    }

    // ---------- 核心解析 ----------

    private static Core resolveCore(MclContext context, GameInstance entry) {
        LoaderInfo loader = entry.loader();
        LoaderInfo.LoaderType type = loader == null ? LoaderInfo.LoaderType.VANILLA : loader.type();
        if (type == LoaderInfo.LoaderType.VANILLA) {
            try {
                String cookie = entry.id();
                var meta = context.mojang().versions().stream()
                        .filter(version -> version.id().equals(entry.mcVersion()))
                        .findFirst()
                        .orElse(null);
                java.util.Objects.requireNonNull(meta, "版本清单里找不到 " + entry.mcVersion());
                JsonNode json = context.mojang().versionJson(meta);
                JsonNode server = json.path("downloads").path("server");
                String url = server.path("url").asText("");
                if (url.isBlank()) {
                    SafeLog.warn("这个版本没有官方服务端下载（" + cookie + "）");
                    return null;
                }
                return new Core("server.jar", URI.create(url), "原版服务端");
            } catch (Exception error) {
                SafeLog.warn("取原版服务端失败：" + error.getClass().getSimpleName());
                return null;
            }
        }
        if (type == LoaderInfo.LoaderType.FABRIC) {
            String mc = entry.mcVersion();
            String loaderVersion = loader == null ? "" : loader.version();
            if (loaderVersion == null || loaderVersion.isBlank()) {
                return null;
            }
            // Fabric 官方「服务端启动器」：它会在第一次启动时自己下载 libraries
            URI url = URI.create("https://meta.fabricmc.net/v2/versions/loader/"
                    + mc + "/" + loaderVersion + "/1.0.1/server/jar");
            return new Core("fabric-server-launch.jar", url, "Fabric 服务端启动器 " + loaderVersion);
        }
        return null;
    }

    // ---------- 档案内容 ----------

    private static String serverProperties(Options options) {
        return """
                # 由 Brisk Launcher 導出
                motd=%s
                server-port=%d
                max-players=%d
                online-mode=%s
                difficulty=normal
                gamemode=survival
                pvp=true
                allow-flight=false
                enable-command-block=false
                spawn-protection=16
                view-distance=10
                simulation-distance=10
                level-name=world
                level-seed=
                white-list=false
                enforce-whitelist=false
                """.formatted(options.motd() == null ? "" : options.motd(),
                options.port(), options.maxPlayers(), options.onlineMode());
    }

    private static String startBat(Options options, String coreName) {
        return """
                @echo off
                rem 由 Brisk Launcher 導出的服務端啟動腳本
                rem 需要 64 位 Java 21（或與該版本匹配的 Java）；內存可在下面調整
                title Minecraft Server
                java -Xms%1$dM -Xmx%1$dM -jar %2$s nogui
                echo.
                echo 服務端已停止，按任意鍵關閉窗口
                pause >nul
                """.formatted(options.memoryMb(), coreName);
    }

    private static String startSh(Options options, String coreName) {
        return """
                #!/bin/sh
                # 由 Brisk Launcher 導出的服務端啟動腳本
                java -Xms%1$dM -Xmx%1$dM -jar %2$s nogui
                """.formatted(options.memoryMb(), coreName);
    }

    private static String readme(GameInstance entry, Analysis analysis, List<ModVerdict> kept,
                                 Options options, Core core) {
        StringBuilder sb = new StringBuilder();
        sb.append("Brisk Launcher 导出的服务端整合包\n");
        sb.append("=====================================\n\n");
        sb.append("客户端实例：").append(entry.id()).append('\n');
        sb.append("游戏版本：").append(entry.mcVersion())
                .append(" · 加载器：").append(entry.loaderDisplay()).append('\n');
        sb.append("服务端 mod：").append(kept.size()).append(" 个\n\n");

        sb.append("怎么开\n------\n");
        if (core != null) {
            sb.append("1. 安装 64 位 Java 21（或该版本要求的 Java）\n");
            sb.append("2. Windows 双击 start.bat；Linux/macOS 执行 start.sh\n");
            sb.append("3. 第一次启动会生成世界与 libraries，耐心等它跑完\n");
            sb.append("4. 内存预设 ").append(options.memoryMb()).append(" MB，可在脚本里改 -Xmx\n\n");
        } else {
            sb.append("这个加载器没有「一个 jar 直接跑」的形态，请先补上服务端核心：\n");
            sb.append("- Forge / NeoForge：下载对应版本的 installer，执行\n");
            sb.append("  java -jar forge-installer.jar --installServer\n");
            sb.append("  然后把生成的启动脚本改名或改用 start.bat\n");
            sb.append("- Quilt：用 Quilt 的服务端启动器，或按官方文档手动安装\n");
            sb.append("- 或者重新导出一次并勾选「包含服务端核心」（原版 / Fabric 支援自动补齐）\n\n");
        }

        if (!analysis.excluded().isEmpty()) {
            sb.append("已剔除（仅客户端）\n--------------\n");
            for (ModVerdict verdict : analysis.excluded()) {
                sb.append("- ").append(verdict.fileName());
                if (verdict.title() != null && !verdict.title().isBlank()) {
                    sb.append("（").append(verdict.title()).append("）");
                }
                sb.append('\n');
            }
            sb.append('\n');
        }
        List<ModVerdict> unknown = analysis.mods().stream()
                .filter(ModVerdict::unknown)
                .filter(verdict -> kept.stream().anyMatch(item -> item.fileName().equals(verdict.fileName())))
                .toList();
        if (!unknown.isEmpty()) {
            sb.append("来源不明（已保留，请自行确认是否为客户端专属）\n");
            sb.append("------------------------------------------\n");
            for (ModVerdict verdict : unknown) {
                sb.append("- ").append(verdict.fileName()).append('\n');
            }
            sb.append('\n');
        }
        sb.append("注意\n----\n");
        sb.append("- config/ 是整个复制过来的，里面可能有客户端专属设定（光影、按键绑定等），不影响服务端。\n");
        sb.append("- 存档（world/）没有打包：避免把客户端世界覆盖到别人的伺服器上。\n");
        sb.append("- eula.txt 预设 eula=true，代表你已同意 Minecraft EULA。\n");
        return sb.toString();
    }

    // ---------- zip 工具 ----------

    private static void putText(ZipOutputStream zip, String name, String text) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(System.currentTimeMillis());
        zip.putNextEntry(entry);
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void putFile(ZipOutputStream zip, String name, Path file) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(Files.getLastModifiedTime(file).toMillis());
        zip.putNextEntry(entry);
        try (InputStream input = Files.newInputStream(file)) {
            input.transferTo(zip);
        }
        zip.closeEntry();
    }

    private static void putTree(ZipOutputStream zip, Path dir, String prefix) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            for (Path path : stream.filter(Files::isRegularFile).sorted().toList()) {
                String relative = dir.relativize(path).toString().replace('\\', '/');
                putFile(zip, prefix + "/" + relative, path);
            }
        }
    }

    /** 從別的資料夾複製一份設定檔（給「只導出 config」的場景預留）。 */
    public static void copyIfExists(Path source, Path target) throws IOException {
        if (!Files.isRegularFile(source)) {
            return;
        }
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
