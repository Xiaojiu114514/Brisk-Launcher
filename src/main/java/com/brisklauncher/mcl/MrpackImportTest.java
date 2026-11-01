package com.brisklauncher.mcl;

import com.brisklauncher.mcl.game.GameInstance;
import com.brisklauncher.mcl.game.GameDirectory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * mrpack 导入端到端测试（开发期用）：用一个最小 mrpack（依赖 1.20.1 + overrides）验证
 * “建实例 → 写 json → 解压 overrides → 识别实例”整条链路。
 *
 * <p>前提：测试根目录里已经装好 1.20.1（{@code InstallAndLaunchTest 1.20.1}）。
 */
public final class MrpackImportTest {

    public static void main(String[] args) throws Exception {
        Path home = Path.of(System.getProperty("mcl.home",
                Path.of(".").toAbsolutePath().normalize().resolve(".mcl-home").toString()));
        System.setProperty("mcl.home", home.toString());

        Path rootPath = home.resolve("e2e");
        if (!Files.isRegularFile(rootPath.resolve("versions").resolve("1.20.1").resolve("1.20.1.json"))) {
            System.out.println("[mrpack] skipped: 1.20.1 not installed in " + rootPath);
            return;
        }

        MclContext context = new MclContext();
        GameDirectory root = new GameDirectory(rootPath, "e2e-root");

        Path packFile = home.resolve("synthetic-test-pack.mrpack");
        writeSyntheticPack(packFile);

        GameInstance entry = context.modpackInstaller().install(root, packFile, progress ->
                System.out.printf("[mrpack] %-10s %3d%% %s%n",
                        progress.stage(), progress.percent(), progress.detail()));

        int failures = 0;
        failures += check("instanceId", entry.id().toLowerCase().contains("testpack"), entry.id());
        failures += check("mcVersion", "1.20.1".equals(entry.mcVersion()), entry.mcVersion());
        failures += check("isolated", entry.isolated(), "gameDir=" + entry.gameDir());
        failures += check("override.config", Files.isRegularFile(
                entry.gameDir().resolve("config").resolve("test.toml")), "config/test.toml");
        failures += check("override.mod", Files.isRegularFile(
                entry.gameDir().resolve("mods").resolve("demo.jar")), "mods/demo.jar");
        failures += check("complete", context.versionInstaller().missingFiles(entry).isEmpty(),
                context.versionInstaller().missingFiles(entry).toString());
        failures += check("json", Files.isRegularFile(entry.jsonFile()), entry.jsonFile().toString());

        System.out.println("[mrpack] failures=" + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void writeSyntheticPack(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("modrinth.index.json"));
            zip.write("""
                    {
                      "formatVersion": 1,
                      "game": "minecraft",
                      "versionId": "1.0",
                      "name": "TestPack",
                      "summary": "synthetic test pack",
                      "files": [],
                      "dependencies": { "minecraft": "1.20.1" }
                    }
                    """.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("overrides/config/test.toml"));
            zip.write("enabled = true\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("overrides/mods/demo.jar"));
            zip.write("not-a-real-jar".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private static int check(String name, boolean condition, String detail) {
        System.out.println((condition ? "[PASS] " : "[FAIL] ") + name + "  " + detail);
        return condition ? 0 : 1;
    }
}
