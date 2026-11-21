package com.brisklauncher.mcl.download.remote;

import com.brisklauncher.mcl.net.Http;
import com.brisklauncher.mcl.net.HttpException;
import com.brisklauncher.mcl.security.SafeLog;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Amazon Corretto JDK（Windows x64）。
 *
 * <p>为什么用它当 Oracle 之后的通用备援：
 * <ul>
 *   <li>8 / 11 / 16 / 17 / 21 / 25 每个主版本都有**稳定的 latest 地址**（不用每次抓版本号）；</li>
 *   <li>官方提供 {@code latest_sha256} 纯文本端点，满足「只认 SHA-256」的红线；</li>
 *   <li>CDN（corretto.aws）在国内 / 本机实测都能直连，不像 GitHub 会 PKIX、镜像会落后。</li>
 * </ul>
 */
public final class CorrettoJdkApi {

    /** 一个可直接下载的 Corretto 包（size = -1 表示 HEAD 拿不到大小）。 */
    public record JdkAsset(int major, String label, URI link, String sha256, long size) {
    }

    private static final Set<Integer> SUPPORTED = Set.of(8, 11, 16, 17, 21, 25);
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final String BASE = "https://corretto.aws/downloads/latest/";
    private static final String SHA_BASE = "https://corretto.aws/downloads/latest_sha256/";
    private static final Pattern SHA256 = Pattern.compile("\\b([0-9a-fA-F]{64})\\b");

    public boolean supports(int major) {
        return SUPPORTED.contains(major);
    }

    public JdkAsset resolve(int major) {
        if (!supports(major)) {
            throw new HttpException("Corretto 没有 Java " + major + " 的 Windows x64 JDK");
        }
        String name = "amazon-corretto-" + major + "-x64-windows-jdk.zip";
        URI link = URI.create(BASE + name);
        String sha = sha256Of(URI.create(SHA_BASE + name));
        if (sha == null) {
            throw new HttpException("Corretto 没有提供 Java " + major + " 的 SHA-256");
        }
        return new JdkAsset(major, "Amazon Corretto " + major, link, sha,
                Http.headLength(link, TIMEOUT));
    }

    /** 读官方 latest_sha256 纯文本；失败回传 null（调用方会换下一个来源）。 */
    private static String sha256Of(URI uri) {
        try {
            String body = Http.getString(uri, TIMEOUT).trim();
            Matcher matcher = SHA256.matcher(body);
            if (matcher.find()) {
                return matcher.group(1).toLowerCase(Locale.ROOT);
            }
            SafeLog.warn("Corretto 校验值格式无法识别：" + uri);
        } catch (RuntimeException e) {
            SafeLog.warn("Corretto 校验值取得失败（" + Http.hostOf(uri) + "）：" + e.getMessage());
        }
        return null;
    }
}
