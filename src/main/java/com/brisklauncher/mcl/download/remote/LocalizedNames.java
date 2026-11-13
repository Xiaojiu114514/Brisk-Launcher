package com.brisklauncher.mcl.download.remote;

import com.brisklauncher.mcl.security.SafeLog;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 模组 / 整合包的中英文对照表。
 *
 * <p>数据来自 Hello Minecraft! Launcher 的 {@code mod.csv} / {@code modpack.csv}
 * （GPL-3.0，本项目同为 GPL-3.0）。CSV 列：{@code id,curseforgeSlug,modrinthSlug,中文名,英文名,缩写,别名}。
 *
 * <p>用途：玩家用中文搜「工业时代」「小地图」时，能同时拿英文名和 Modrinth/CurseForge 的 slug 去查，
 * 反过来也能用英文名找到对应的中文名。
 */
public final class LocalizedNames {

    private static volatile boolean loaded;
    private static final Map<String, String> NAME_TO_CHINESE = new HashMap<>();
    private static final Map<String, String> SLUG_TO_CHINESE = new HashMap<>();
    private static final Map<String, Set<String>> CHINESE_TO_ALIASES = new HashMap<>();
    private static final Map<String, String> ALIAS_TO_CHINESE = new HashMap<>();
    /** 中文名 → 最适合拿去搜的单一词（Modrinth slug 优先，其次 CurseForge slug，最后英文名）。 */
    private static final Map<String, String> BEST_QUERY = new HashMap<>();

    private LocalizedNames() {
    }

    /** 中文名 → 该条目的所有检索别名（英文名 / 缩写 / slug）。 */
    public static Set<String> aliasesForChinese(String chinese) {
        load();
        if (chinese == null) {
            return Set.of();
        }
        Set<String> direct = CHINESE_TO_ALIASES.get(chinese.trim());
        return direct == null ? Set.of() : direct;
    }

    /** slug 或英文名 → 中文名（查不到回 null）。 */
    public static String chineseFor(String slugOrEnglish) {
        load();
        if (slugOrEnglish == null || slugOrEnglish.isBlank()) {
            return null;
        }
        String key = slugOrEnglish.trim().toLowerCase(Locale.ROOT);
        String bySlug = SLUG_TO_CHINESE.get(key);
        if (bySlug != null) {
            return bySlug;
        }
        return NAME_TO_CHINESE.get(key);
    }

    /**
     * 把玩家输入的中文（或部分中文）扩展成英文检索词。
     *
     * <p>规则：表里某条的中文名**包含**输入内容（或输入内容包含该中文名）时，把它的别名都带上。
     * 只做包含匹配，不做模糊打分 —— 宁可少给几个词，也不要送一堆无关结果。
     */
    public static Set<String> expandChinese(String query) {
        load();
        Set<String> result = new LinkedHashSet<>();
        if (query == null) {
            return result;
        }
        String value = query.trim();
        if (value.length() < 2) {
            return result;
        }
        for (Map.Entry<String, Set<String>> entry : CHINESE_TO_ALIASES.entrySet()) {
            String chinese = entry.getKey();
            if (chinese.isBlank()) {
                continue;
            }
            if (chinese.contains(value) || value.contains(chinese)) {
                result.addAll(entry.getValue());
            }
            if (result.size() > 24) {
                break;
            }
        }
        return result;
    }

    /**
     * 中文名 → 单一最佳检索词。
     *
     * <p>为什么不把别名全拼起来：Modrinth 的 q 参数是整串匹配，塞十个词反而零结果。
     */
    public static String bestQuery(String chinese) {
        load();
        if (chinese == null) {
            return null;
        }
        return BEST_QUERY.get(chinese.trim());
    }

    /**
     * 找「包含关系」的中文条目，回传它的最佳检索词（输入可以是长句的一部分）。
     *
     * <p>打分而不是「第一个命中就返回」：HashMap 的顺序是随机的，之前同一个词
     * 每次可能映射到不同 Mod（实测「皮肤补丁」时而命中 WSkinLoader）。
     * 规则：完全匹配 &gt; 中文名以输入开头 &gt; 中文名包含输入 &gt; 输入包含中文名；
     * 同分时取**更短**的中文名（更具体）。
     */
    public static String bestQueryLoose(String query) {
        load();
        if (query == null || query.trim().length() < 2) {
            return null;
        }
        String value = query.trim();
        String bestKey = null;
        String bestValue = null;
        int bestScore = -1;
        for (Map.Entry<String, String> entry : BEST_QUERY.entrySet()) {
            String chinese = entry.getKey();
            int score;
            if (chinese.equals(value)) {
                score = 3;
            } else if (chinese.startsWith(value)) {
                score = 2;
            } else if (chinese.contains(value)) {
                score = 1;
            } else if (value.contains(chinese)) {
                score = 0;
            } else {
                continue;
            }
            if (score > bestScore
                    || (score == bestScore && (bestKey == null || chinese.length() < bestKey.length()))) {
                bestScore = score;
                bestKey = chinese;
                bestValue = entry.getValue();
            }
        }
        return bestValue;
    }

    /** 命中条目的全部候选检索词（按上面的打分排序，去重，最多 6 个）。 */
    public static java.util.List<String> bestQueryCandidates(String query) {
        load();
        java.util.List<String> result = new java.util.ArrayList<>();
        if (query == null || query.trim().length() < 2) {
            return result;
        }
        String value = query.trim();
        java.util.List<String[]> scored = new java.util.ArrayList<>();
        for (Map.Entry<String, String> entry : BEST_QUERY.entrySet()) {
            String chinese = entry.getKey();
            int score;
            if (chinese.equals(value)) {
                score = 3;
            } else if (chinese.startsWith(value)) {
                score = 2;
            } else if (chinese.contains(value)) {
                score = 1;
            } else if (value.contains(chinese)) {
                score = 0;
            } else {
                continue;
            }
            scored.add(new String[]{String.valueOf(score), chinese, entry.getValue()});
        }
        scored.sort((a, b) -> {
            int byScore = Integer.compare(Integer.parseInt(b[0]), Integer.parseInt(a[0]));
            if (byScore != 0) {
                return byScore;
            }
            return Integer.compare(a[1].length(), b[1].length());
        });
        for (String[] item : scored) {
            if (!result.contains(item[2])) {
                result.add(item[2]);
            }
            if (result.size() >= 6) {
                break;
            }
        }
        return result;
    }

    private static void load() {
        if (loaded) {
            return;
        }
        synchronized (LocalizedNames.class) {
            if (loaded) {
                return;
            }
            read("/assets/lang/mod.csv");
            read("/assets/lang/modpack.csv");
            loaded = true;
            SafeLog.info("[search] 中英对照表已载入：" + CHINESE_TO_ALIASES.size() + " 条");
        }
    }

    private static void read(String resource) {
        try (InputStream input = LocalizedNames.class.getResourceAsStream(resource)) {
            if (input == null) {
                SafeLog.warn("[search] 缺少对照表资源：" + resource);
                return;
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String text = line.trim();
                if (text.isEmpty() || text.startsWith("#")) {
                    continue;
                }
                String[] parts = text.split(",", -1);
                if (parts.length < 5) {
                    continue;
                }
                String curseSlug = parts[1].trim();
                String modrinthSlug = parts[2].trim();
                String chinese = parts[3].trim();
                String english = parts[4].trim();
                String aliasField = parts.length > 6 ? parts[6].trim() : "";
                if (chinese.isEmpty()) {
                    continue;
                }
                Set<String> aliases = new LinkedHashSet<>();
                if (!english.isEmpty()) {
                    aliases.add(english.toLowerCase(Locale.ROOT));
                    NAME_TO_CHINESE.putIfAbsent(english.toLowerCase(Locale.ROOT), chinese);
                }
                if (!parts[5].trim().isEmpty()) {
                    aliases.add(parts[5].trim().toLowerCase(Locale.ROOT));
                }
                for (String raw : new String[]{curseSlug, modrinthSlug}) {
                    if (!raw.isEmpty()) {
                        aliases.add(raw.toLowerCase(Locale.ROOT));
                        SLUG_TO_CHINESE.putIfAbsent(raw.toLowerCase(Locale.ROOT), chinese);
                    }
                }
                if (!aliasField.isEmpty()) {
                    for (String raw : aliasField.split("[;|]")) {
                        String alias = raw.trim().toLowerCase(Locale.ROOT);
                        if (!alias.isEmpty()) {
                            aliases.add(alias);
                        }
                    }
                }
                CHINESE_TO_ALIASES.put(chinese, aliases);
                String best = !modrinthSlug.isEmpty() ? modrinthSlug
                        : (!curseSlug.isEmpty() ? curseSlug : english.toLowerCase(Locale.ROOT));
                if (!best.isEmpty()) {
                    BEST_QUERY.put(chinese, best);
                }
                for (String alias : aliases) {
                    ALIAS_TO_CHINESE.putIfAbsent(alias, chinese);
                }
            }
        } catch (Exception error) {
            SafeLog.warn("[search] 读取对照表失败：" + error.getClass().getSimpleName());
        }
    }

    /** 供调试/自检用：已载入多少条。 */
    public static int size() {
        load();
        return CHINESE_TO_ALIASES.size();
    }

    static List<String> entriesForDebug() {
        load();
        return new ArrayList<>(CHINESE_TO_ALIASES.keySet());
    }
}