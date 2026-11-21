package com.brisklauncher.mcl.download.remote;

import com.brisklauncher.mcl.net.Http;
import com.brisklauncher.mcl.net.HttpException;
import com.brisklauncher.mcl.security.SafeLog;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Oracle JDK 下载资讯（Java 21 / 25）。
 *
 * <p><b>为什么固定 21.0.12</b>：Oracle 从 21.0.13 起把 JDK 21 改成 OTN 授权，下载页要先点授权、
 * 直连一律 404；21.0.12 是最后一个 **NFTC**（免费使用、可再散布）且能直连的版本。
 * 21.0.12 在 {@code /archive/} 下，档名固定、内容不会变，所以校验值可以写死当保底。
 *
 * <p>25 目前仍是 NFTC，走 {@code /latest/} 稳定位址；哈希每次更新都会变，所以一律从
 * 官方 {@code .zip.sha256} 取。
 */
public final class OracleJdkApi {

    /** 一个可直接下载的 Oracle JDK 包（size = -1 表示未知，下载后只验 SHA-256）。 */
    public record JdkAsset(int major, String label, URI link, String sha256, long size) {
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final String BASE = "https://download.oracle.com/java/";

    /** 21.0.12：NFTC 的最后一个可直接下载版本（21.0.13+ 需要 OTN 授权）。 */
    private static final String JDK21_URL = BASE + "21/archive/jdk-21.0.12_windows-x64_bin.zip";

    /** 官方 .sha256 的固定值（archive 目录里的文件不会变，已核对过官方旁车档）。 */
    private static final String JDK21_SHA256 =
            "167af6612931baf82d1e55dd81444b94fcae68dffa73055946843c11cd2a37e5";

    /** 21.0.12 archive 的固定大小（2026-11 实测 196,941,465 bytes）。 */
    private static final long JDK21_SIZE = 196_941_465L;

    private static final Pattern SHA256 = Pattern.compile("\\b([0-9a-fA-F]{64})\\b");

    /** 目前只有 21 / 25 有「免授权、可直接下载」的 Oracle 包。 */
    public boolean supports(int major) {
        return major == 21 || major == 25;
    }

    public JdkAsset resolve(int major) {
        if (major == 21) {
            // archive 文件不可变，直接用官方 .sha256 的固定值，省掉一次「可能被墙」的请求；
            // 万一真的对不上，verifyChecksum 会丢档并改用 Adoptium JDK。
            return new JdkAsset(21, "Oracle JDK 21.0.12", URI.create(JDK21_URL),
                    JDK21_SHA256, JDK21_SIZE);
        }
        if (major == 25) {
            URI link = URI.create(BASE + "25/latest/jdk-25_windows-x64_bin.zip");
            String sha = sha256Of(URI.create(link + ".sha256"));
            if (sha == null) {
                throw new HttpException("Oracle 没有提供 Java 25 的 SHA-256");
            }
            return new JdkAsset(25, "Oracle JDK 25", link, sha, -1L);
        }
        throw new HttpException("Oracle 没有 Java " + major + " 的直接下载地址");
    }

    /** 读官方 {@code .sha256} 旁车档；抓不到／格式不对回传 null，由调用方决定保底或跳过。 */
    private static String sha256Of(URI uri) {
        try {
            String body = Http.getString(uri, TIMEOUT).trim();
            Matcher matcher = SHA256.matcher(body);
            if (matcher.find()) {
                return matcher.group(1).toLowerCase(Locale.ROOT);
            }
            SafeLog.warn("Oracle 校验值格式无法识别：" + uri);
        } catch (RuntimeException e) {
            SafeLog.warn("Oracle 校验值取得失败（" + Http.hostOf(uri) + "）：" + e.getMessage());
        }
        return null;
    }
}
