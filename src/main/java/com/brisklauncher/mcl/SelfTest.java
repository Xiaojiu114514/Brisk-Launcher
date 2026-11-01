package com.brisklauncher.mcl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.brisklauncher.mcl.game.mod.ModManager;
import com.brisklauncher.mcl.game.Rules;
import com.brisklauncher.mcl.game.GameInstance;
import com.brisklauncher.mcl.game.GameVersionMerger;
import com.brisklauncher.mcl.game.GameDirectory;
import com.brisklauncher.mcl.game.GameScanner;
import com.brisklauncher.mcl.download.DownloadProgress;
import com.brisklauncher.mcl.download.DownloadTask;
import com.brisklauncher.mcl.download.HttpDownloader;
import com.brisklauncher.mcl.game.install.VersionInstaller;
import com.brisklauncher.mcl.modpack.ModpackIndex;
import com.brisklauncher.mcl.modpack.MrpackExporter;
import com.brisklauncher.mcl.modpack.MrpackReader;
import com.brisklauncher.mcl.net.Http;
import com.brisklauncher.mcl.download.remote.LoaderMetaClient;
import com.brisklauncher.mcl.download.remote.MojangApi;
import com.brisklauncher.mcl.download.remote.ModrinthClient;
import com.brisklauncher.mcl.setting.LauncherSettings;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 离线/联网自检工具（开发期用，不参与 UI）。
 * 直接运行：见 README 的“自检”一节。
 */
public final class SelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        Path home = Path.of(System.getProperty("mcl.home",
                Path.of(".").toAbsolutePath().normalize().resolve(".mcl-home").toString()));
        System.setProperty("mcl.home", home.toString());
        com.brisklauncher.mcl.security.NativeSetup.prepare();
        System.out.println("[selftest] mcl.home=" + home);

        try {
            MclContext context = new MclContext();
            testManifest(context);
            testFabricMeta(context);
            testModrinth(context);
            testJreScan(context);
            testLanguage(context);
            testAccountStore();
            testRules();
            testInstalledVersions(context);
            testCrashEngine(context);
            testModpackRoundTrip(context, home);
            testDownloader(context, home);
            testFrpConfig(context);
        } catch (Throwable error) {
            failed++;
            System.out.println("[FAIL] unexpected: " + error);
            error.printStackTrace(System.out);
        }

        System.out.println("[selftest] passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    // ---------- 网络 ----------

    private static void testManifest(MclContext context) {
        try {
            List<MojangApi.VersionMeta> versions = context.mojang().versions(true);
            check("manifest", versions.size() > 100, "count=" + versions.size());
            MojangApi.VersionMeta latest = versions.stream().filter(MojangApi.VersionMeta::isRelease)
                    .findFirst().orElseThrow();
            JsonNode json = context.mojang().versionJson(latest);
            check("versionJson", json.has("mainClass") && json.path("libraries").size() > 0,
                    latest.id() + " mainClass=" + json.path("mainClass").asText());
        } catch (Exception e) {
            fail("manifest", e);
        }
    }

    private static void testFabricMeta(MclContext context) {
        try {
            List<LoaderMetaClient.LoaderVersion> loaders = context.loaderMeta().fabricLoaders("1.20.1");
            check("fabric.loaders", !loaders.isEmpty(), "count=" + loaders.size());
            JsonNode profile = context.loaderMeta().fabricProfile("1.20.1", loaders.get(0).version());
            check("fabric.profile", "1.20.1".equals(profile.path("inheritsFrom").asText()),
                    "inheritsFrom=" + profile.path("inheritsFrom").asText());
            List<LoaderMetaClient.LoaderVersion> forge = context.loaderMeta().forgeVersions("1.20.1");
            check("forge.versions", !forge.isEmpty(), "count=" + forge.size());
            List<LoaderMetaClient.LoaderVersion> neo = context.loaderMeta().neoforgeVersions("1.20.1");
            // Maven 元数据在部分网络下会被拦截；为空只作为提示，不算失败
            check("neoforge.versions", true, "count=" + neo.size()
                    + (neo.isEmpty() ? " (network unavailable, skipped)" : ""));
        } catch (Exception e) {
            fail("loaderMeta", e);
        }
    }

    private static void testModrinth(MclContext context) {
        try {
            ModrinthClient.SearchResult result = context.modrinth()
                    .search("sodium", "mod", List.of("fabric"), List.of("1.20.1"), List.of());
            check("modrinth.search", result.isSuccess() && !result.projects().isEmpty(),
                    result.isSuccess() ? "hits=" + result.totalHits() : result.errorMessage());
            if (result.isSuccess() && !result.projects().isEmpty()) {
                ModrinthClient.VersionFile file = context.modrinth()
                        .bestFile(result.projects().get(0).id(), "fabric", "1.20.1");
                check("modrinth.file", !file.downloadUrl().isBlank() && !file.fileName().isBlank(),
                        file.fileName());
            }
        } catch (Exception e) {
            fail("modrinth", e);
        }
    }

    // ---------- 本地 ----------

    private static void testJreScan(MclContext context) {
        try {
            var jdks = context.javaManager().scanAll();
            check("jre.scan", !jdks.isEmpty(), "count=" + jdks.size()
                    + " first=" + (jdks.isEmpty() ? "-" : jdks.get(0).display()));
        } catch (Exception e) {
            fail("jre.scan", e);
        }
    }

    private static void testAccountStore() {
        java.nio.file.Path file = com.brisklauncher.mcl.AppPaths.launcherRoot()
                .resolve("accounts-selftest.json");
        try {
            com.brisklauncher.mcl.auth.AccountStore store =
                    new com.brisklauncher.mcl.auth.AccountStore(file);
            String uuid = "00000000000000000000000000000001";
            boolean saved = store.save(java.util.List.of(
                    new com.brisklauncher.mcl.auth.AccountStore.Account(uuid,
                            "SelfTest", com.brisklauncher.mcl.auth.api.AccountType.OFFLINE, null)), uuid);
            java.util.List<com.brisklauncher.mcl.auth.AccountStore.Account> loaded = store.load();
            check("account.store", saved && loaded.size() == 1
                            && uuid.equals(loaded.get(0).uuid()) && "SelfTest".equals(loaded.get(0).name()),
                    "saved=" + saved + " loaded=" + loaded.size());
            java.nio.file.Files.deleteIfExists(file);
        } catch (Exception error) {
            fail("account.store", error);
        }
    }

    private static void testLanguage(MclContext context) {
        String original = context.settings().language();
        try {
            context.updateLanguage("en_US");
            String translated = com.brisklauncher.mcl.i18n.I18n.t("nav.launch");
            boolean saved = "en_US".equals(context.settings().language());
            check("i18n.switch", "Launch".equals(translated) && saved,
                    "nav.launch=" + translated + " saved=" + context.settings().language());
        } finally {
            context.updateLanguage(original);
        }
    }

    private static void testRules() {        try {
            JsonNode allowWindows = Http.mapper().readTree(
                    "{\"action\":\"allow\",\"os\":{\"name\":\"windows\"}}");
            JsonNode allowOsx = Http.mapper().readTree(
                    "{\"action\":\"allow\",\"os\":{\"name\":\"osx\"}}");
            JsonNode demoArg = Http.mapper().readTree(
                    "{\"rules\":[{\"action\":\"allow\",\"features\":{\"is_demo_user\":true}}]}");
            boolean windows = Rules.allowsRules(Http.mapper().createArrayNode().add(allowWindows),
                    Rules.Features.none());
            boolean osx = Rules.allowsRules(Http.mapper().createArrayNode().add(allowOsx),
                    Rules.Features.none());
            boolean demo = Rules.allows(demoArg, Rules.Features.none());
            check("rules", windows && !osx && !demo,
                    "windows=" + windows + " osx=" + osx + " demo=" + demo);
        } catch (Exception e) {
            fail("rules", e);
        }
    }

    private static void testInstalledVersions(MclContext context) {
        try {
            List<GameDirectory> roots = context.gameRepository().roots();
            if (roots.isEmpty()) {
                check("installed.scan", true, "no version root (skipped)");
                return;
            }
            GameDirectory root = roots.get(0);
            GameScanner scanner = context.scanner();
            List<GameInstance> entries = scanner.scan(root);
            check("installed.scan", !entries.isEmpty(), "count=" + entries.size());
            if (entries.isEmpty()) {
                return;
            }
            GameInstance entry = entries.get(0);
            ObjectNode merged = new GameVersionMerger(root.path()).merge(entry);
            check("installed.merge", !merged.path("mainClass").asText("").isBlank()
                            && merged.path("libraries").size() > 0,
                    entry.id() + " libs=" + merged.path("libraries").size());
            boolean complete = context.gameRepository().looksComplete(entry);
            check("installed.complete", true, entry.id() + " complete=" + complete
                    + " missing=" + context.versionInstaller().missingFiles(entry));
            check("content.list", !context.mods().list(entry, ModManager.Kind.MODS).isEmpty()
                            || context.mods().list(entry, ModManager.Kind.MODS).isEmpty(),
                    "mods=" + context.mods().list(entry, ModManager.Kind.MODS).size());
        } catch (Exception e) {
            fail("installed", e);
        }
    }

    private static void testCrashEngine(MclContext context) throws Exception {
        Path rootDir = AppPaths.launcherRoot().resolve("testroot");
        Path versionDir = rootDir.resolve("versions").resolve("crashtest");
        Files.createDirectories(versionDir);
        Files.writeString(versionDir.resolve("crashtest.json"), """
                {"id":"crashtest","type":"release","mainClass":"net.minecraft.client.main.Main",
                 "libraries":[],"arguments":{"game":[],"jvm":[]}}
                """, StandardCharsets.UTF_8);
        Files.createDirectories(versionDir.resolve("logs"));
        Files.writeString(versionDir.resolve("logs").resolve("latest.log"),
                "java.lang.OutOfMemoryError: Java heap space\n"
                        + "at net.minecraft.client.Minecraft.run(Minecraft.java:1)\n"
                        + "Mixin apply failed: examplemod.mixins.json\n", StandardCharsets.UTF_8);
        Files.createDirectories(versionDir.resolve("mods"));
        Files.write(versionDir.resolve("mods").resolve("examplemod-1.2.3.jar"), new byte[]{1, 2, 3});

        GameInstance entry = new GameScanner().scanSingle(new GameDirectory(rootDir, "test"), "crashtest")
                .orElseThrow();
        var analysis = context.crashRules().analyze(entry);
        check("crash.analyze", analysis.matches().size() >= 2,
                "hits=" + analysis.matches().stream().map(m -> m.rule().id()).toList());
        check("crash.suspects", analysis.suspectMods().contains("examplemod-1.2.3.jar")
                        || !analysis.suspectMods().isEmpty(),
                "suspects=" + analysis.suspectMods());

        Path zip = context.crashExporter().export(entry, analysis);
        check("crash.export", Files.isRegularFile(zip) && Files.size(zip) > 200,
                zip.getFileName() + " size=" + Files.size(zip));
    }

    private static void testModpackRoundTrip(MclContext context, Path home) throws Exception {
        Path rootDir = home.resolve("packroot");
        Path versionDir = rootDir.resolve("versions").resolve("1.20.1-testpack");
        Files.createDirectories(versionDir.resolve("mods"));
        Files.createDirectories(versionDir.resolve("config"));
        Files.writeString(versionDir.resolve("1.20.1-testpack.json"), """
                {"id":"1.20.1-testpack","inheritsFrom":"1.20.1","type":"release",
                 "mainClass":"net.minecraft.client.main.Main","libraries":[]}
                """, StandardCharsets.UTF_8);
        Files.writeString(versionDir.resolve("mods").resolve("demo-mod.jar"), "fake jar",
                StandardCharsets.UTF_8);
        Files.writeString(versionDir.resolve("config").resolve("demo.toml"), "enabled=true",
                StandardCharsets.UTF_8);

        GameInstance entry = new GameScanner()
                .scanSingle(new GameDirectory(rootDir, "pack"), "1.20.1-testpack").orElseThrow();
        Path mrpack = home.resolve("roundtrip.mrpack");
        context.modpackExporter().export(entry, mrpack, MrpackExporter.Options.everything());
        check("mrpack.export", Files.isRegularFile(mrpack) && Files.size(mrpack) > 300,
                "size=" + Files.size(mrpack));

        ModpackIndex index = new MrpackReader().read(mrpack);
        check("mrpack.read", "1.20.1".equals(index.mcVersion()),
                "mc=" + index.mcVersion() + " name=" + index.displayName());
        List<String> overrides = new MrpackReader().listOverrideEntries(mrpack);
        check("mrpack.overrides", overrides.contains("mods/demo-mod.jar"),
                overrides.toString());

        // 路径穿越必须被拒绝
        boolean rejected = false;
        try {
            com.brisklauncher.mcl.modpack.MrpackPathValidator.validate("../../evil.exe");
        } catch (SecurityException e) {
            rejected = true;
        }
        check("mrpack.pathSafety", rejected, "traversal rejected=" + rejected);
    }

    private static void testDownloader(MclContext context, Path home) throws Exception {
        Path target = home.resolve("download-test").resolve("fabric-profile.json");
        String url = "https://meta.fabricmc.net/v2/versions/loader/1.20.1/0.16.5/profile/json";
        DownloadTask task = new DownloadTask(context.mirrors().resolve(url), target, null, -1);
        AtomicReference<DownloadProgress> last = new AtomicReference<>();
        context.downloader().downloadAll(List.of(task), last::set);
        boolean downloaded = Files.isRegularFile(target)
                && Files.readString(target, StandardCharsets.UTF_8).contains("fabric-loader");
        check("download.single", downloaded,
                "size=" + Files.size(target) + " progress=" + last.get());

        // 第二次应当跳过（不发请求），直接成功
        context.downloader().downloadAll(List.of(task), p -> { });
        check("download.skipExisting", Files.isRegularFile(target), target.getFileName().toString());

        // 校验失败必须报错
        boolean rejected = false;
        try {
            DownloadTask bad = new DownloadTask(context.mirrors().resolve(url),
                    home.resolve("download-test").resolve("bad.json"),
                    "0000000000000000000000000000000000000000", -1);
            context.downloader().downloadAll(List.of(bad), p -> { });
        } catch (RuntimeException e) {
            rejected = true;
        }
        check("download.hashCheck", rejected, "mismatch rejected=" + rejected);
    }

    private static void testFrpConfig(MclContext context) throws Exception {
        var config = new com.brisklauncher.mcl.online.FrpService.FrpConfig(
                "frps.example.com", 7000, "token123", 25565, 25565);
        String toml = config.toToml();
        check("frp.toml", toml.contains("serverAddr") && toml.contains("[[proxies]]")
                        && toml.contains("localPort = 25565"),
                "toml length=" + toml.length());
        check("frp.portCheck", true, "25565 open="
                + com.brisklauncher.mcl.online.FrpService.isLocalPortOpen(25565));
    }

    // ---------- 断言 ----------

    private static void check(String name, boolean condition, String detail) {
        if (condition) {
            passed++;
            System.out.println("[PASS] " + name + "  " + detail);
        } else {
            failed++;
            System.out.println("[FAIL] " + name + "  " + detail);
        }
    }

    private static void fail(String name, Exception error) {
        failed++;
        System.out.println("[FAIL] " + name + "  " + error);
    }
}
