package com.brisklauncher.mcl;

import com.brisklauncher.mcl.auth.api.GameSession;
import com.brisklauncher.mcl.game.GameInstance;
import com.brisklauncher.mcl.game.GameDirectory;
import com.brisklauncher.mcl.game.install.InstallProgress;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 端到端测试（开发期用）：安装一个真实版本 → 启动 → 观察进程与日志 → 结束。
 * 用法：{@code -Dmcl.home=... InstallAndLaunchTest <version>}
 */
public final class InstallAndLaunchTest {

    public static void main(String[] args) throws Exception {
        String version = args.length > 0 ? args[0] : "1.7.10";
        Path home = Path.of(System.getProperty("mcl.home",
                Path.of(".").toAbsolutePath().normalize().resolve(".mcl-home").toString()));
        System.setProperty("mcl.home", home.toString());
        com.brisklauncher.mcl.security.NativeSetup.prepare();

        MclContext context = new MclContext();
        GameDirectory root = new GameDirectory(home.resolve("e2e"), "e2e-root");
        Files.createDirectories(root.versionsDir());

        String fabricLoader = valueOf(args, "--fabric=");
        String forgeVersion = valueOf(args, "--forge=");
        String neoForgeVersion = valueOf(args, "--neoforge=");
        boolean skipInstall = java.util.Arrays.asList(args).contains("--no-install");

        System.out.println("[e2e] installing " + version
                + (fabricLoader == null ? "" : " fabric=" + fabricLoader)
                + (forgeVersion == null ? "" : " forge=" + forgeVersion)
                + (neoForgeVersion == null ? "" : " neoforge=" + neoForgeVersion)
                + " into " + root.path());
        long start = System.currentTimeMillis();
        final long[] lastPrint = {0};
        java.util.function.Consumer<InstallProgress> printer = progress -> {
            // 控制台输出节流：PowerShell 管道每行都有成本，打印太多会拖慢下载
            long now = System.currentTimeMillis();
            if (now - lastPrint[0] < 500) {
                return;
            }
            lastPrint[0] = now;
            System.out.printf("[e2e] %-12s %3d%%  %-46s%n", progress.stage(), progress.percent(),
                    shorten(progress.detail()));
        };

        GameInstance entry;
        if (skipInstall) {
            entry = findInstalled(root, version, fabricLoader, forgeVersion, neoForgeVersion);
        } else if (fabricLoader != null) {
            entry = context.gameRepository().installLoader(root,
                    com.brisklauncher.mcl.game.LoaderInfo.LoaderType.FABRIC, version, fabricLoader, printer);
        } else if (forgeVersion != null) {
            entry = context.gameRepository().installLoader(root,
                    com.brisklauncher.mcl.game.LoaderInfo.LoaderType.FORGE, version, forgeVersion, printer);
        } else if (neoForgeVersion != null) {
            entry = context.gameRepository().installLoader(root,
                    com.brisklauncher.mcl.game.LoaderInfo.LoaderType.NEOFORGE, version, neoForgeVersion, printer);
        } else {
            entry = context.gameRepository().installVanilla(root, version, printer);
        }
        System.out.printf("%n[e2e] install done in %.1fs, missing=%s%n",
                (System.currentTimeMillis() - start) / 1000.0,
                context.versionInstaller().missingFiles(entry));

        GameSession session = context.accounts().fallbackSession();
        System.out.println("[e2e] launching as " + session.username());
        Process process = context.gameLauncher()
                .launchAsync(entry, session, stage -> System.out.println("[e2e] stage " + stage))
                .future().get(120, TimeUnit.SECONDS);
        System.out.println("[e2e] pid=" + process.pid() + " alive=" + process.isAlive());

        Thread.sleep(25_000);
        boolean alive = process.isAlive();
        System.out.println("[e2e] after 25s alive=" + alive);

        String log = readLatestLog(home, entry.id());
        System.out.println("[e2e] ---- session log tail ----");
        System.out.println(tail(log, 40));
        System.out.println("[e2e] --------------------------");
        System.out.println("[e2e] latest.log exists="
                + Files.isRegularFile(entry.gameDir().resolve("logs").resolve("latest.log")));

        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        context.shutdown();
        System.exit(alive ? 0 : 2);
    }

    private static String valueOf(String[] args, String prefix) {
        for (String arg : args) {
            if (arg.startsWith(prefix)) {
                return arg.substring(prefix.length());
            }
        }
        return null;
    }

    private static GameInstance findInstalled(GameDirectory root, String version, String fabricLoader,
                                              String forgeVersion, String neoForgeVersion) {
        com.brisklauncher.mcl.game.GameScanner scanner = new com.brisklauncher.mcl.game.GameScanner();
        String wanted;
        if (fabricLoader != null) {
            wanted = "fabric-loader-" + fabricLoader + "-" + version;
        } else if (forgeVersion != null) {
            wanted = version + "-forge-" + forgeVersion;
        } else if (neoForgeVersion != null) {
            wanted = "neoforge-" + neoForgeVersion;
        } else {
            wanted = version;
        }
        return scanner.scanSingle(root, wanted)
                .or(() -> scanner.scan(root).stream()
                        .filter(entry -> entry.id().toLowerCase(java.util.Locale.ROOT)
                                .contains(wanted.toLowerCase(java.util.Locale.ROOT)))
                        .findFirst())
                .orElseThrow(() -> new IllegalStateException("not installed: " + wanted));
    }

    private static String shorten(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 48 ? text.substring(0, 48) : text;
    }

    private static String readLatestLog(Path home, String versionId) {
        Path logs = home.resolve("logs");
        if (!Files.isDirectory(logs)) {
            return "";
        }
        try (Stream<Path> stream = Files.list(logs)) {
            Path latest = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("session-" + versionId))
                    .max(Comparator.comparingLong(path -> path.toFile().lastModified()))
                    .orElse(null);
            if (latest == null) {
                return "";
            }
            return Files.readString(latest, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static String tail(String text, int lines) {
        if (text == null || text.isBlank()) {
            return "(empty)";
        }
        List<String> all = text.lines().toList();
        int from = Math.max(0, all.size() - lines);
        return String.join("\n", all.subList(from, all.size()));
    }
}
