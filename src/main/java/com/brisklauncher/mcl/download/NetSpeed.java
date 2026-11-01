package com.brisklauncher.mcl.download;

import com.brisklauncher.mcl.security.SafeLog;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 「最快源」测速（设定页下载源 = 自动时使用）。
 *
 * <p>做法很轻：**只对「主机」做一次 TCP 连线延迟测量**（不发 HTTP、不拉任何档案），
 * 每个主机最多 1 个 socket、1.2 秒超时；结果（含排序）快取 5 分钟。
 * 注意：这**不是**逐档案测速——上百个下载任务共用同一份排序结果。
 */
public final class NetSpeed {

    private static final Map<String, Long> LATENCY = new LinkedHashMap<>();
    /** 主機組合 → 排序結果（同一組候選只算一次）。 */
    private static final Map<String, List<String>> RANKED = new LinkedHashMap<>();
    private static final java.util.Set<String> LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final long TTL_MS = 5L * 60 * 1000;
    private static long measuredAt;

    private NetSpeed() {
    }

    /** 回傳主機的連線延遲（毫秒）；連不上回傳 {@link Long#MAX_VALUE}。 */
    public static long latency(String host) {
        if (host == null || host.isBlank()) {
            return Long.MAX_VALUE;
        }
        synchronized (LATENCY) {
            Long cached = LATENCY.get(host);
            if (cached != null && System.currentTimeMillis() - measuredAt < TTL_MS) {
                return cached;
            }
        }
        long value = probe(host);
        synchronized (LATENCY) {
            LATENCY.put(host, value);
            measuredAt = System.currentTimeMillis();
        }
        return value;
    }

    /**
     * 只查快取；没有结果就回传 {@code null}（**绝不探测**）。
     * 下载路径必须用这个，避免每个档案都卡在测速上。
     */
    public static List<String> cachedRank(List<String> hosts) {
        if (hosts == null || hosts.isEmpty()) {
            return hosts;
        }
        String cacheKey = String.join("|", new java.util.LinkedHashSet<>(hosts));
        synchronized (RANKED) {
            List<String> cached = RANKED.get(cacheKey);
            if (cached != null && System.currentTimeMillis() - measuredAt < TTL_MS) {
                return cached;
            }
        }
        return null;
    }

    /** 背景補做一次探測（單飛）；下次下載就會用到排序結果。 */
    private static final java.util.Set<String> WARMING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void warmUpAsync(List<String> hosts) {
        if (hosts == null || hosts.size() <= 1) {
            return;
        }
        String cacheKey = String.join("|", new java.util.LinkedHashSet<>(hosts));
        if (cachedRank(hosts) != null || !WARMING.add(cacheKey)) {
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                rank(hosts);
            } finally {
                WARMING.remove(cacheKey);
            }
        }, "brisk-speedtest-warm");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 依延迟把候选主机排序（最快的在前）；探测失败的主机排到最后，不会被丢掉。
     */
    public static List<String> rank(List<String> hosts) {
        if (hosts == null || hosts.size() <= 1) {
            return hosts == null ? List.of() : hosts;
        }
        List<String> unique = new ArrayList<>(new java.util.LinkedHashSet<>(hosts));
        String cacheKey = String.join("|", unique);
        synchronized (RANKED) {
            List<String> cached = RANKED.get(cacheKey);
            if (cached != null && System.currentTimeMillis() - measuredAt < TTL_MS) {
                return cached;
            }
        }
        Map<String, Long> result = new java.util.HashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(unique.size(), 4), runnable -> {
            Thread thread = new Thread(runnable, "brisk-speedtest");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (String host : unique) {
                futures.add(pool.submit(() -> result.put(host, latency(host))));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception ignored) {
                    // 单一主机失败不影响排序
                }
            }
        } finally {
            pool.shutdownNow();
        }
        List<String> ranked = new ArrayList<>(unique);
        ranked.sort((a, b) -> Long.compare(
                result.getOrDefault(a, Long.MAX_VALUE),
                result.getOrDefault(b, Long.MAX_VALUE)));
        synchronized (RANKED) {
            RANKED.put(cacheKey, ranked);
        }
        if (LOGGED.add(cacheKey)) {
            SafeLog.info("最快源测速（背景，一次）：" + ranked);
        }
        return ranked;
    }

    /** 已知無法解析的主機（DNS 掛掉/被牆），直接跳過不再重試。 */
    private static final java.util.Set<String> DEAD = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static long probe(String host) {
        if (DEAD.contains(host)) {
            return Long.MAX_VALUE;
        }
        long start = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, 443), 1200);
            return Math.max(1, (System.nanoTime() - start) / 1_000_000);
        } catch (Exception error) {
            if (error instanceof java.nio.channels.UnresolvedAddressException
                    || error.getCause() instanceof java.nio.channels.UnresolvedAddressException) {
                if (DEAD.add(host)) {
                    SafeLog.warn("测速：主机无法解析，已标记为不可用（" + host + "）");
                }
            }
            return Long.MAX_VALUE;
        }
    }

    /** 主機名（小寫）→ 供 MirrorResolver 排序用。 */
    public static String hostOf(java.net.URI uri) {
        String host = uri == null ? null : uri.getHost();
        return host == null ? "" : host.toLowerCase(Locale.ROOT);
    }
}
