package com.brisklauncher.mcl.download.remote;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 中文关键词 → 英文搜索词（参考 PCL2 的做法）。
 *
 * <p>Modrinth 的搜索只认英文名/描述，玩家输入「优化」「小地图」「存储」往往零结果。
 * 这里维护一份常用词表：命中就把英文对应词一起送去搜寻（原本的中文也保留，
 * 因为有些 Mod 标题本身就有中文）。
 *
 * <p>词表刻意保持小而实用，不做机器翻译——猜错反而更糟。
 */
public final class SearchTerms {

    /** 詞表：中文 → 追加的英文關鍵詞（可多個）。 */
    private static final List<String[]> DICTIONARY = List.of(
            new String[]{"优化", "optimization performance sodium lithium"},
            new String[]{"优化", "optimization performance sodium lithium"},
            new String[]{"性能", "performance optimization"},
            new String[]{"小地图", "minimap xaero journeymap"},
            new String[]{"小地图", "minimap xaero journeymap"},
            new String[]{"地图", "map"},
            new String[]{"地图", "map"},
            new String[]{"光影", "shader shaders iris oculus"},
            new String[]{"著色器", "shader shaders"},
            new String[]{"着色器", "shader shaders"},
            new String[]{"资源包", "resourcepack texture"},
            new String[]{"资源包", "resourcepack texture"},
            new String[]{"材质包", "resourcepack texture"},
            new String[]{"材质包", "resourcepack texture"},
            new String[]{"存储", "storage drawer chest barrel"},
            new String[]{"存储", "storage drawer chest barrel"},
            new String[]{"箱子", "chest storage"},
            new String[]{"建筑", "building decoration"},
            new String[]{"建筑", "building decoration"},
            new String[]{"家具", "furniture decoration"},
            new String[]{"魔法", "magic"},
            new String[]{"科技", "tech technology"},
            new String[]{"工业", "industrial tech"},
            new String[]{"工业", "industrial tech"},
            new String[]{"农业", "farming agriculture"},
            new String[]{"农业", "farming agriculture"},
            new String[]{"食物", "food cooking"},
            new String[]{"料理", "cooking food"},
            new String[]{"生物", "mob creature"},
            new String[]{"怪物", "mob monster"},
            new String[]{"维度", "dimension"},
            new String[]{"维度", "dimension"},
            new String[]{"探险", "adventure exploration"},
            new String[]{"探险", "adventure exploration"},
            new String[]{"地牢", "dungeon"},
            new String[]{"结构", "structure"},
            new String[]{"结构", "structure"},
            new String[]{"村庄", "village"},
            new String[]{"村庄", "village"},
            new String[]{"武器", "weapon combat"},
            new String[]{"装备", "equipment armor"},
            new String[]{"装备", "equipment armor"},
            new String[]{"工具", "tool utility"},
            new String[]{"附魔", "enchantment enchant"},
            new String[]{"药水", "potion"},
            new String[]{"药水", "potion"},
            new String[]{"合成", "crafting recipe"},
            new String[]{"配方", "recipe"},
            new String[]{"任务", "quest"},
            new String[]{"任务", "quest"},
            new String[]{"经济", "economy"},
            new String[]{"经济", "economy"},
            new String[]{"商店", "shop trade"},
            new String[]{"交易", "trade villager"},
            new String[]{"红石", "redstone"},
            new String[]{"红石", "redstone"},
            new String[]{"机械", "machinery machine"},
            new String[]{"机械", "machinery machine"},
            new String[]{"能源", "energy power"},
            new String[]{"发电", "power generator"},
            new String[]{"发电", "power generator"},
            new String[]{"传送", "teleport waystone"},
            new String[]{"传送", "teleport waystone"},
            new String[]{"死亡", "death grave"},
            new String[]{"坟墓", "grave death"},
            new String[]{"坟墓", "grave death"},
            new String[]{"整理", "inventory sorting"},
            new String[]{"背包", "inventory"},
            new String[]{"界面", "ui interface hud"},
            new String[]{"信息", "info hud"},
            new String[]{"显示", "display hud"},
            new String[]{"显示", "display hud"},
            new String[]{"聊天", "chat"},
            new String[]{"语音", "voice chat"},
            new String[]{"语音", "voice chat"},
            new String[]{"皮肤", "skin"},
            new String[]{"皮肤", "skin"},
            new String[]{"披风", "cape"},
            new String[]{"披风", "cape"},
            new String[]{"光影包", "shaderpack"},
            new String[]{"帧数", "fps performance"},
            new String[]{"帧数", "fps performance"},
            new String[]{"延迟", "latency ping"},
            new String[]{"延迟", "latency ping"},
            new String[]{"伺服器", "server"},
            new String[]{"服务器", "server"},
            new String[]{"整合包", "modpack"},
            new String[]{"前置", "api library"},
            new String[]{"图书馆", "library api"},
            new String[]{"图书馆", "library api"},
            new String[]{"建筑师", "architectury"},
            new String[]{"建筑", "building"},
            new String[]{"工艺", "craft"},
            new String[]{"工艺", "craft"}
    );

    private SearchTerms() {
    }

    /** 查詢是否含中日韓字元。 */
    public static boolean hasCjk(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(text.charAt(i));
            if (script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HANGUL) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把中文查询扩充成「原文 + 英文关键词」。
     *
     * @return 送给搜寻 API 的字串；没有中文或没命中词表时原样返回
     */
    public static String expand(String query) {
        if (query == null || query.isBlank() || !hasCjk(query)) {
            return query;
        }
        String lower = query.toLowerCase(Locale.ROOT);
        Set<String> extras = new LinkedHashSet<>();
        for (String[] entry : DICTIONARY) {
            if (lower.contains(entry[0])) {
                for (String word : entry[1].split(" ")) {
                    if (!word.isBlank()) {
                        extras.add(word);
                    }
                }
            }
        }
        if (extras.isEmpty()) {
            return query;
        }
        StringBuilder builder = new StringBuilder(query);
        for (String extra : extras) {
            builder.append(' ').append(extra);
        }
        return builder.toString();
    }

    /**
     * 只回英文关键词（不含原文），给「中文查询先用英文搜」用。
     *
     * @return 空格分隔的英文关键词；没命中词表回空字串
     */
    public static String translate(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        String lower = query.toLowerCase(Locale.ROOT);
        Set<String> extras = new LinkedHashSet<>();
        for (String[] entry : DICTIONARY) {
            if (lower.contains(entry[0])) {
                for (String word : entry[1].split(" ")) {
                    if (!word.isBlank()) {
                        extras.add(word);
                    }
                }
            }
        }
        return String.join(" ", extras);
    }
    /** 是否真的做了擴充（用於 UI 提示）。 */
    public static boolean wasExpanded(String original) {
        String expanded = expand(original);
        return expanded != null && !expanded.equals(original);
    }
}
