package com.brisklauncher.mcl;

import com.brisklauncher.mcl.download.DownloadTask;
import com.brisklauncher.mcl.download.HttpDownloader;
import com.brisklauncher.mcl.download.MirrorResolver;
import com.brisklauncher.mcl.setting.LauncherSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 下载吞吐基准（开发期用）：对比官方源 / BMCLAPI 在不同并发下的实际速度。
 * 只挑小文件，避免占用太多带宽。
 */
public final class DownloadBench {

    public static void main(String[] args) throws Exception {
        Path home = Path.of(System.getProperty("mcl.home",
                Path.of(".").toAbsolutePath().normalize().resolve(".mcl-home").toString()));
        System.setProperty("mcl.home", home.toString());

        List<String> hashes = pickHashes(home.resolve("e2e").resolve("assets").resolve("objects"), 40);
        if (hashes.isEmpty()) {
            System.out.println("no assets to benchmark (install a version first)");
            return;
        }
        System.out.println("benchmark assets: " + hashes.size());

        for (LauncherSettings.Source source : List.of(LauncherSettings.Source.BMCLAPI,
                LauncherSettings.Source.OFFICIAL)) {
            for (int concurrency : new int[]{4, 12}) {
                LauncherSettings settings = new LauncherSettings(source, "", concurrency, 0, 20,
                        "zh_CN", true, 4096, false, "", "", false, "", false, "github", "");
                MirrorResolver mirrors = new MirrorResolver(settings);
                HttpDownloader downloader = new HttpDownloader(concurrency, 0, 20);
                Path dir = home.resolve("bench").resolve(source + "-" + concurrency);
                List<DownloadTask> tasks = new ArrayList<>();
                for (String hash : hashes) {
                    tasks.add(new DownloadTask(
                            mirrors.resolve("https://resources.download.minecraft.net/"
                                    + hash.substring(0, 2) + "/" + hash),
                            dir.resolve(hash), hash, -1));
                }
                long start = System.nanoTime();
                try {
                    downloader.downloadAll(tasks, progress -> { });
                    double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
                    System.out.printf("%-8s concurrency=%-2d  %.1fs  %.2f files/s%n",
                            source, concurrency, seconds, hashes.size() / seconds);
                } catch (Exception e) {
                    System.out.printf("%-8s concurrency=%-2d  FAILED: %s%n", source, concurrency, e);
                } finally {
                    downloader.close();
                }
            }
        }
    }

    private static List<String> pickHashes(Path objectsDir, int limit) {
        if (!Files.isDirectory(objectsDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(objectsDir, 2)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().length() == 40)
                    .limit(limit)
                    .map(path -> path.getFileName().toString())
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }
}
