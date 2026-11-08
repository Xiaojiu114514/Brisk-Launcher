package com.brisklauncher.mcl.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.brisklauncher.mcl.AppPaths;
import com.brisklauncher.mcl.download.DownloadTask;
import com.brisklauncher.mcl.download.HttpDownloader;
import com.brisklauncher.mcl.net.Http;
import com.brisklauncher.mcl.security.SafeLog;
import com.brisklauncher.mcl.setting.LauncherSettings;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 启动器自动更新（GitHub / Gitee）。
 *
 * <p>流程刻意做得「安全但老实」：
 * <ul>
 *   <li>只读 release 资讯（版本号、说明、资产清单），不偷偷替换自己的 exe；</li>
 *   <li>有新版本 → 弹窗显示版本与说明，玩家自己点「打开下载页」或「下载到本地」；</li>
 *   <li>下载到 {@code <data>/updates/}，下完打开资料夹，由玩家自己覆盖（启动器在执行时无法安全替换自身）。</li>
 * </ul>
 *
 * <p>仓库地址留空时不检查（等使用者提供）。{@code updateRepo} 接受 {@code owner/repo} 或完整网址。
 */
public final class UpdateService {

    /** 目前版本（與 build.gradle / pom.xml 保持一致）。 */
    public static final String CURRENT_VERSION = "1.1.2";

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    /** 一個 release 資產（通常是 zip / exe）。{@code sha256} 為空表示上游沒給摘要。 */
    public record Asset(String name, String url, long size, String sha256) {
        public Asset(String name, String url, long size) {
            this(name, url, size, "");
        }

        public String sizeText() {
            if (size <= 0) {
                return "";
            }
            if (size < 1024L * 1024) {
                return String.format(Locale.ROOT, "%.0f KB", size / 1024.0);
            }
            return String.format(Locale.ROOT, "%.1f MB", size / (1024.0 * 1024));
        }
    }

    /** 最新 release。 */
    public record Release(String tag, String name, String notes, String pageUrl,
                          List<Asset> assets, String source) {

        /** 去掉前綴 v 的版本號。 */
        public String version() {
            String cleaned = tag == null ? "" : tag.trim();
            while (cleaned.startsWith("v") || cleaned.startsWith("V")) {
                cleaned = cleaned.substring(1);
            }
            return cleaned;
        }
    }

    /** 檢查結果。 */
    public record CheckResult(boolean updateAvailable, Release release, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    private UpdateService() {
    }

    /** 檢查更新（阻塞，呼叫方放背景執行緒）。 */
    public static CheckResult check(LauncherSettings settings) {
        if (settings == null || !settings.updateConfigured()) {
            return new CheckResult(false, null, "尚未设定更新仓库地址");
        }
        String repo = normalizeRepo(settings.updateRepo());
        if (repo.isEmpty()) {
            return new CheckResult(false, null, "仓库地址格式应为 owner/repo");
        }
        boolean gitee = "gitee".equalsIgnoreCase(settings.updateChannel());
        String api = gitee
                ? "https://gitee.com/api/v5/repos/" + repo + "/releases/latest"
                : "https://api.github.com/repos/" + repo + "/releases/latest";
        try {
            JsonNode root = Http.getJson(URI.create(api), TIMEOUT);
            String tag = root.path("tag_name").asText("");
            if (tag.isBlank()) {
                return new CheckResult(false, null, "release 资讯里没有 tag_name");
            }
            List<Asset> assets = new ArrayList<>();
            for (JsonNode asset : root.path("assets")) {
                String url = asset.path("browser_download_url").asText("");
                if (url.isBlank()) {
                    continue;
                }
                // 安全审计 M-07：GitHub release asset 会带 digest（"sha256:..."），
                // 有就取来校验；没有（例如 Gitee）则只能靠 size，并在日志提醒。
                String digest = asset.path("digest").asText("");
                String sha256 = "";
                if (digest.startsWith("sha256:")) {
                    sha256 = digest.substring("sha256:".length()).trim().toLowerCase(Locale.ROOT);
                }
                assets.add(new Asset(asset.path("name").asText(""), url,
                        asset.path("size").asLong(0), sha256));
            }
            String page = root.path("html_url").asText("");
            if (page.isBlank()) {
                page = gitee ? "https://gitee.com/" + repo + "/releases"
                        : "https://github.com/" + repo + "/releases";
            }
            Release release = new Release(tag,
                    root.path("name").asText(tag),
                    root.path("body").asText(""),
                    page,
                    List.copyOf(assets),
                    gitee ? "gitee" : "github");
            boolean newer = compareVersions(release.version(), CURRENT_VERSION) > 0;
            return new CheckResult(newer, release, null);
        } catch (Exception error) {
            String message = Http.describe(error);
            SafeLog.warn("检查更新失败：" + message);
            return new CheckResult(false, null, message);
        }
    }

    /**
     * 从 release 资产里挑出「可以自动覆盖更新」的桌面版 zip。
     *
     * <p>偏好顺序：Windows x64 → 名字带 Brisk → zip；排除 source / src 之类的原始码包。
     * 没有可用 zip 时回传 {@code null}（呼叫方改走「开启下载页」）。
     */
    public static Asset pickDesktopPackage(Release release) {
        if (release == null || release.assets() == null) {
            return null;
        }
        Asset best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Asset asset : release.assets()) {
            String name = asset.name() == null ? "" : asset.name().toLowerCase(Locale.ROOT);
            // 单 exe 发行：更新资产就是一个 .exe（优先），旧的 zip 仍然兼容
            boolean exeAsset = name.endsWith(".exe");
            if ((!exeAsset && !name.endsWith(".zip")) || name.contains("source") || name.contains("-src")) {
                continue;
            }
            int score = 0;
            if (exeAsset) {
                // 单 exe 发行优先：exe 就是可直接替换的运行文件，不用再解压
                score += 5;
            }
            if (name.contains("windows") || name.contains("win64") || name.contains("win-")) {
                score += 3;
            }
            if (name.contains("brisk")) {
                score += 2;
            }
            if (name.contains("x64") || name.contains("amd64")) {
                score += 1;
            }
            if (score > bestScore) {
                bestScore = score;
                best = asset;
            }
        }
        return best;
    }

    /** 下載某個資產到 {@code <data>/updates/}。 */
    public static Path download(Asset asset, HttpDownloader downloader,
                                Consumer<Double> progress) throws Exception {
        if (asset == null || asset.url() == null || asset.url().isBlank()) {
            throw new IllegalArgumentException("没有可下载的档案");
        }
        Path dir = AppPaths.dataDir().resolve("updates");
        java.nio.file.Files.createDirectories(dir);
        String name = asset.name() == null || asset.name().isBlank()
                ? "brisk-launcher-update.zip" : asset.name();
        // 安全审计 L9：资产名来自远端 JSON，只能取 basename
        Path target = com.brisklauncher.mcl.security.PathSafety.resolveUnder(
                dir, com.brisklauncher.mcl.security.PathSafety.safeFileName(name));
        // 上游没给 size 时 ratio 只能算 0 或 1（进度条看着像卡住）→ 先发一个「不定长」让界面转圈
        if (progress != null && asset.size() <= 0) {
            progress.accept(-1.0);
        }
        long size = asset.size();
        SafeLog.info("[update] 开始下载更新包：" + target.getFileName() + "（声明大小 " + size + " 字节）");
        int[] lastBucket = {-1};
        downloader.downloadAll(List.of(new DownloadTask(List.of(URI.create(asset.url())), target,
                null, size > 0 ? size : -1)), snapshot -> {
            double ratio = snapshot == null ? 0 : snapshot.ratio();
            // 上游没给 size 时 bytesTotal 一直是 0，ratio 只能算出 0 或 1 → 进度条会停在 0%。
            // 这种情况统一发 -1（界面显示成"转圈"），不要假装 0%。
            if (progress != null) {
                progress.accept(size > 0 ? ratio : -1.0);
            }
            if (size > 0) {
                int bucket = (int) (ratio * 10);
                if (bucket != lastBucket[0]) {
                    lastBucket[0] = bucket;
                    SafeLog.info("[update] 下载进度 " + Math.round(ratio * 100) + "%（"
                            + (snapshot == null ? 0 : snapshot.bytesDone()) + "/"
                            + (snapshot == null ? 0 : snapshot.bytesTotal()) + "）");
                }
            }
        });
        SafeLog.info("[update] 更新包下载完成：" + target.getFileName());
        // 安全审计 M-07：下载完再按上游 digest 校验一次 SHA-256
        if (asset.sha256() != null && !asset.sha256().isBlank()) {
            String actual = sha256Of(target);
            if (!asset.sha256().equalsIgnoreCase(actual)) {
                try {
                    java.nio.file.Files.deleteIfExists(target);
                } catch (Exception ignored) {
                    // 尽力而为
                }
                throw new SecurityException("更新包校验失败（SHA-256 不匹配），已删除下载的文件");
            }
            SafeLog.info("[update] 更新包 SHA-256 校验通过");
        } else {
            SafeLog.warn("[update] 上游没有提供摘要（digest），本次下载只校验了文件大小");
        }
        return target;
    }

    /** 计算文件的 SHA-256（十六进制小写）。 */
    private static String sha256Of(Path file) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (var stream = java.nio.file.Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte value : digest.digest()) {
            sb.append(String.format("%02x", value & 0xFF));
        }
        return sb.toString();
    }

    /** 把 {@code owner/repo}、{@code https://github.com/owner/repo} 都整理成 {@code owner/repo}。 */
    public static String normalizeRepo(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return "";
        }
        text = text.replaceAll("^https?://", "");
        text = text.replaceAll("^(www\\.)?github\\.com/", "");
        text = text.replaceAll("^(www\\.)?gitee\\.com/", "");
        if (text.endsWith(".git")) {
            text = text.substring(0, text.length() - 4);
        }
        // 去掉 /releases 之后的尾巴
        int cut = text.indexOf("/releases");
        if (cut > 0) {
            text = text.substring(0, cut);
        }
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text.matches("[^/\\s]+/[^/\\s]+") ? text : "";
    }

    /** 版本號比較（1.2.0 &gt; 1.10.0 這種按數字段比，而不是字串比）。 */
    public static int compareVersions(String left, String right) {
        int[] a = numbers(left);
        int[] b = numbers(right);
        int length = Math.max(a.length, b.length);
        for (int index = 0; index < length; index++) {
            int av = index < a.length ? a[index] : 0;
            int bv = index < b.length ? b[index] : 0;
            if (av != bv) {
                return Integer.compare(av, bv);
            }
        }
        return 0;
    }

    private static int[] numbers(String version) {
        if (version == null || version.isBlank()) {
            return new int[0];
        }
        String[] pieces = version.split("[^0-9]+");
        List<Integer> values = new ArrayList<>();
        for (String piece : pieces) {
            if (piece.isBlank()) {
                continue;
            }
            try {
                values.add(Integer.parseInt(piece));
            } catch (NumberFormatException ignored) {
                // 超长段直接跳过
            }
        }
        int[] result = new int[values.size()];
        for (int index = 0; index < values.size(); index++) {
            result[index] = values.get(index);
        }
        return result;
    }
}
