package com.brisklauncher.mcl.download;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 下载源健康度：记住哪些主机刚刚失败过，后续任务优先走健康的主机。
 *
 * <p>为什么需要它：如果用户选了官方源但官方 CDN 被拦，每个文件都先等一次连接超时才回退，
 * 几千个资源文件就会变成几小时。这里让“失败过一次的主机”排到候选末尾，
 * 整个会话最多只吃一次超时。
 */
public final class HostHealth {

    private static final Map<String, Integer> FAILURES = new ConcurrentHashMap<>();
    private static final int MAX_SCORE = 5;

    private HostHealth() {
    }

    public static void success(URI uri) {
        String host = hostOf(uri);
        if (host == null) {
            return;
        }
        FAILURES.computeIfPresent(host, (key, value) -> value <= 1 ? null : value - 1);
    }

    public static void failure(URI uri) {
        String host = hostOf(uri);
        if (host == null) {
            return;
        }
        FAILURES.merge(host, 1, (oldValue, one) -> Math.min(MAX_SCORE, oldValue + 1));
    }

    public static void reset() {
        FAILURES.clear();
    }

    /** 稳定排序：健康主机保持原顺序，失败过的主机按失败次数排到后面。 */
    public static List<URI> sorted(List<URI> mirrors) {
        if (mirrors.size() <= 1 || FAILURES.isEmpty()) {
            return mirrors;
        }
        List<URI> copy = new ArrayList<>(mirrors);
        copy.sort(Comparator.comparingInt(uri -> score(uri)));
        return copy;
    }

    private static int score(URI uri) {
        String host = hostOf(uri);
        return host == null ? 0 : FAILURES.getOrDefault(host, 0);
    }

    private static String hostOf(URI uri) {
        return uri == null ? null : uri.getHost();
    }
}
