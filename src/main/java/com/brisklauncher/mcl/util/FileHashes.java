package com.brisklauncher.mcl.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 档案 SHA-1（带快取）。
 *
 * <p>为什么要快取：126 个 mod 每次刷新列表/开依赖图都重算一次 SHA-1，
 * 几百 MB 的 jar 加起来就是好几秒的纯 IO，而且这些档案根本没变。
 * 这里用「绝对路径 + 大小 + 修改时间」当 key，只重算真的变过的档案。
 *
 * <p>快取上限 512 笔，超过就淘汰最旧的（LRU）；只在背景执行绪使用，内部同步。
 */
public final class FileHashes {

    private static final int MAX_ENTRIES = 512;

    private static final Map<String, String> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private FileHashes() {
    }

    /** 算 SHA-1；同一個檔案（路徑/大小/時間都沒變）只算一次。 */
    public static String sha1(Path file) throws Exception {
        String key = keyOf(file);
        synchronized (CACHE) {
            String hit = CACHE.get(key);
            if (hit != null) {
                return hit;
            }
        }
        String value = compute(file);
        synchronized (CACHE) {
            CACHE.put(key, value);
        }
        return value;
    }

    private static String keyOf(Path file) throws Exception {
        long size = Files.size(file);
        long modified = Files.getLastModifiedTime(file).toMillis();
        return file.toAbsolutePath().normalize() + "|" + size + "|" + modified;
    }

    private static String compute(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** 測試/換實例時清空。 */
    public static void clear() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }
}
