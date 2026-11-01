package com.brisklauncher.mcl.plugin.builtin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 投影档案（litematic / schem / schematic / nbt 结构）的材料清单统计。
 *
 * <p>四种格式的共同点是「都只是 NBT + 一块方块资料」，差别在怎么存方块 id：
 * <ul>
 *   <li><b>.nbt</b>（原版结构方块）：{@code palette} + {@code blocks[{pos, state}]}，最直观；</li>
 *   <li><b>.litematic</b>（Litematica）：{@code BlockStatePalette} + 位打包的 {@code BlockStates}；</li>
 *   <li><b>.schem</b>（Sponge / WorldEdit 新版）：{@code Palette} + varint 阵列；</li>
 *   <li><b>.schematic</b>（MCEdit 老格式）：{@code Blocks} 位元组 + {@code AddBlocks} 高位，只有数字 id。</li>
 * </ul>
 *
 * <p>只统计「非空气方块」的数量，并附上长宽高与总体积，方便玩家直接照著准备材料。
 */
final class SchematicMaterials {

    /** 統計結果。 */
    record Result(String format, int width, int height, int length, int totalBlocks,
                  Map<String, Integer> materials, String note) {

        int volume() {
            return width * height * length;
        }
    }

    private SchematicMaterials() {
    }

    static Result parse(Path file) throws Exception {
        // 安全审计 v2 A-01：投影档是「别人给的档案」，读取要有上限
        byte[] bytes = com.brisklauncher.mcl.security.InputBudget.readBounded(
                file, com.brisklauncher.mcl.security.InputBudget.MAX_LOCAL_FILE);
        Nbt.Tag root = Nbt.read(bytes);
        Map<String, Object> map = Nbt.compound(root.value());
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);

        if (map.containsKey("Regions")) {
            return parseLitematic(map, name);
        }
        if (map.containsKey("Blocks") && map.get("Blocks") instanceof Map) {
            return parseSpongeV3(map);
        }
        if (map.containsKey("Palette") && map.containsKey("BlockData")) {
            return parseSpongeV2(map);
        }
        if (map.containsKey("palette") && map.containsKey("blocks")) {
            return parseStructure(map);
        }
        if (map.containsKey("Blocks")) {
            return parseClassic(map);
        }
        throw new IllegalArgumentException("认不出这个投影档案格式（不是 litematic / schem / schematic / nbt）");
    }

    // ---------- 原版结构方块 ----------

    private static Result parseStructure(Map<String, Object> map) {
        int[] size = Nbt.intArray(map.get("size"));
        int width = size.length > 0 ? size[0] : 0;
        int height = size.length > 1 ? size[1] : 0;
        int length = size.length > 2 ? size[2] : 0;

        List<String> palette = new ArrayList<>();
        for (Object item : Nbt.list(map.get("palette"))) {
            palette.add(Nbt.string(Nbt.compound(item).get("Name")));
        }
        Map<String, Integer> materials = new LinkedHashMap<>();
        int total = 0;
        for (Object item : Nbt.list(map.get("blocks"))) {
            Map<String, Object> block = Nbt.compound(item);
            int state = Nbt.intValue(block.get("state"), -1);
            if (state < 0 || state >= palette.size()) {
                continue;
            }
            String blockName = palette.get(state);
            if (isAir(blockName)) {
                continue;
            }
            total++;
            materials.merge(blockName, 1, Integer::sum);
        }
        return new Result("NBT 结构", width, height, length, total, sorted(materials),
                "原版结构方块格式，材料名称直接来自 palette");
    }

    // ---------- Litematica ----------

    private static Result parseLitematic(Map<String, Object> map, String fileName) {
        Map<String, Object> regions = Nbt.compound(map.get("Regions"));
        if (regions.isEmpty()) {
            throw new IllegalArgumentException("litematic 里没有任何 region");
        }
        Map<String, Integer> materials = new LinkedHashMap<>();
        int width = 0;
        int height = 0;
        int length = 0;
        int total = 0;
        int decodedRegions = 0;
        for (var entry : regions.entrySet()) {
            Map<String, Object> region = Nbt.compound(entry.getValue());
            List<String> palette = new ArrayList<>();
            for (Object item : Nbt.list(region.get("BlockStatePalette"))) {
                palette.add(Nbt.string(Nbt.compound(item).get("Name")));
            }
            long[] states = Nbt.longArray(region.get("BlockStates"));
            Map<String, Object> size = Nbt.compound(region.get("Size"));
            int rx = Math.abs(Nbt.intValue(size.get("x"), 0));
            int ry = Math.abs(Nbt.intValue(size.get("y"), 0));
            int rz = Math.abs(Nbt.intValue(size.get("z"), 0));
            width = Math.max(width, rx);
            height = Math.max(height, ry);
            length = Math.max(length, rz);
            if (palette.isEmpty() || states.length == 0) {
                continue;
            }
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.size() - 1)));
            long volume = (long) rx * ry * rz;
            int regionTotal = 0;
            for (long index = 0; index < volume; index++) {
                long paletteIndex = readBits(states, index, bits);
                if (paletteIndex < 0 || paletteIndex >= palette.size()) {
                    continue;
                }
                String blockName = palette.get((int) paletteIndex);
                if (isAir(blockName)) {
                    continue;
                }
                regionTotal++;
                materials.merge(blockName, 1, Integer::sum);
            }
            total += regionTotal;
            decodedRegions++;
        }
        String note = "共 " + regions.size() + " 个 region，成功解码 " + decodedRegions + " 个";
        if (decodedRegions < regions.size()) {
            note += "（其余 region 缺少方块资料）";
        }
        if (total == 0) {
            note += "；没有统计到方块，可能是位打包格式不同，请回报这个档案";
        }
        return new Result("Litematica（" + fileName + "）", width, height, length, total,
                sorted(materials), note);
    }

    /**
     * 读位打包的阵列（Litematica 的 LitematicaBitArray：值从低位开始、可跨 long）。
     */
    private static long readBits(long[] array, long index, int bits) {
        long bitPosition = index * bits;
        long value = 0;
        for (int bit = 0; bit < bits; bit++) {
            long position = bitPosition + bit;
            int longIndex = (int) (position / 64);
            if (longIndex < 0 || longIndex >= array.length) {
                return -1;
            }
            int offset = (int) (position % 64);
            long current = (array[longIndex] >>> offset) & 1L;
            value |= current << bit;
        }
        return value;
    }

    // ---------- Sponge v2 / v3 ----------

    private static Result parseSpongeV2(Map<String, Object> map) {
        Map<String, Integer> palette = readSpongePalette(Nbt.compound(map.get("Palette")));
        int[] indices = readVarInts(Nbt.byteArray(map.get("BlockData")));
        int width = Nbt.intValue(map.get("Width"), 0);
        int height = Nbt.intValue(map.get("Height"), 0);
        int length = Nbt.intValue(map.get("Length"), 0);
        return countByPalette("Sponge schem", width, height, length, palette, indices,
                "WorldEdit / Sponge 格式（varint 方块资料）");
    }

    private static Result parseSpongeV3(Map<String, Object> map) {
        Map<String, Object> blocks = Nbt.compound(map.get("Blocks"));
        Map<String, Integer> palette = readSpongePalette(Nbt.compound(blocks.get("Palette")));
        int[] indices = readVarInts(Nbt.byteArray(blocks.get("Data")));
        int width = Nbt.intValue(map.get("Width"), 0);
        int height = Nbt.intValue(map.get("Height"), 0);
        int length = Nbt.intValue(map.get("Length"), 0);
        return countByPalette("Sponge schem v3", width, height, length, palette, indices,
                "Sponge v3 格式（Blocks.Palette + Blocks.Data）");
    }

    private static Map<String, Integer> readSpongePalette(Map<String, Object> paletteMap) {
        Map<String, Integer> palette = new LinkedHashMap<>();
        for (var entry : paletteMap.entrySet()) {
            palette.put(entry.getKey(), Nbt.intValue(entry.getValue(), -1));
        }
        return palette;
    }

    private static Result countByPalette(String format, int width, int height, int length,
                                         Map<String, Integer> palette, int[] indices, String note) {
        // palette 是 name → index，这里反过来
        Map<Integer, String> byIndex = new TreeMap<>();
        for (var entry : palette.entrySet()) {
            byIndex.put(entry.getValue(), entry.getKey());
        }
        Map<String, Integer> materials = new LinkedHashMap<>();
        int total = 0;
        for (int index : indices) {
            String blockName = byIndex.get(index);
            if (blockName == null || isAir(blockName)) {
                continue;
            }
            total++;
            materials.merge(blockName, 1, Integer::sum);
        }
        return new Result(format, width, height, length, total, sorted(materials), note);
    }

    // ---------- MCEdit 老格式 ----------

    private static Result parseClassic(Map<String, Object> map) {
        byte[] blocks = Nbt.byteArray(map.get("Blocks"));
        byte[] add = Nbt.byteArray(map.get("AddBlocks"));
        int width = Nbt.intValue(map.get("Width"), 0);
        int height = Nbt.intValue(map.get("Height"), 0);
        int length = Nbt.intValue(map.get("Length"), 0);
        Map<String, Integer> materials = new LinkedHashMap<>();
        int total = 0;
        for (int index = 0; index < blocks.length; index++) {
            int id = blocks[index] & 0xFF;
            if (add.length > 0) {
                int packed = add[index / 2] & 0xFF;
                int high = (index % 2 == 0) ? (packed & 0x0F) : ((packed >> 4) & 0x0F);
                id |= high << 8;
            }
            if (id == 0) {
                continue;
            }
            total++;
            materials.merge(classicName(id), 1, Integer::sum);
        }
        String note = "MCEdit 老格式：只有数字 id，名称来自内建对照表（未收录的显示为 unknown:<id>）";
        return new Result("MCEdit schematic", width, height, length, total, sorted(materials), note);
    }

    private static String classicName(int id) {
        return switch (id) {
            case 1 -> "minecraft:stone";
            case 2 -> "minecraft:grass_block";
            case 3 -> "minecraft:dirt";
            case 4 -> "minecraft:cobblestone";
            case 5 -> "minecraft:oak_planks";
            case 7 -> "minecraft:bedrock";
            case 8, 9 -> "minecraft:water";
            case 10, 11 -> "minecraft:lava";
            case 12 -> "minecraft:sand";
            case 13 -> "minecraft:gravel";
            case 14 -> "minecraft:gold_ore";
            case 15 -> "minecraft:iron_ore";
            case 16 -> "minecraft:coal_ore";
            case 17 -> "minecraft:oak_log";
            case 18 -> "minecraft:oak_leaves";
            case 20 -> "minecraft:glass";
            case 24 -> "minecraft:sandstone";
            case 35 -> "minecraft:white_wool";
            case 41 -> "minecraft:gold_block";
            case 42 -> "minecraft:iron_block";
            case 43, 44 -> "minecraft:stone_slab";
            case 45 -> "minecraft:bricks";
            case 46 -> "minecraft:tnt";
            case 47 -> "minecraft:bookshelf";
            case 48 -> "minecraft:mossy_cobblestone";
            case 49 -> "minecraft:obsidian";
            case 50 -> "minecraft:torch";
            case 52 -> "minecraft:spawner";
            case 53 -> "minecraft:oak_stairs";
            case 54 -> "minecraft:chest";
            case 56 -> "minecraft:diamond_ore";
            case 57 -> "minecraft:diamond_block";
            case 58 -> "minecraft:crafting_table";
            case 61, 62 -> "minecraft:furnace";
            case 64 -> "minecraft:oak_door";
            case 65 -> "minecraft:ladder";
            case 66 -> "minecraft:rail";
            case 67 -> "minecraft:cobblestone_stairs";
            case 73, 74 -> "minecraft:redstone_ore";
            case 78 -> "minecraft:snow";
            case 79 -> "minecraft:ice";
            case 80 -> "minecraft:snow_block";
            case 82 -> "minecraft:clay";
            case 87 -> "minecraft:netherrack";
            case 88 -> "minecraft:soul_sand";
            case 89 -> "minecraft:glowstone";
            case 98 -> "minecraft:stone_bricks";
            case 112 -> "minecraft:nether_bricks";
            case 121 -> "minecraft:end_stone";
            case 133 -> "minecraft:emerald_block";
            case 152 -> "minecraft:redstone_block";
            case 155 -> "minecraft:quartz_block";
            case 159 -> "minecraft:white_terracotta";
            case 162, 163 -> "minecraft:dark_oak_log";
            case 179 -> "minecraft:red_sandstone";
            default -> "unknown:" + id;
        };
    }

    // ---------- 工具 ----------

    static boolean isAir(String name) {
        if (name == null || name.isBlank()) {
            return true;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("minecraft:air") || lower.equals("minecraft:cave_air")
                || lower.equals("minecraft:void_air") || lower.equals("air")
                || lower.equals("minecraft:structure_void");
    }

    /** varint（protobuf 風格）陣列 → int 陣列。 */
    private static int[] readVarInts(byte[] data) {
        List<Integer> values = new ArrayList<>();
        int index = 0;
        while (index < data.length) {
            int value = 0;
            int shift = 0;
            while (index < data.length) {
                int current = data[index++] & 0xFF;
                value |= (current & 0x7F) << shift;
                if ((current & 0x80) == 0) {
                    break;
                }
                shift += 7;
                if (shift > 35) {
                    break;
                }
            }
            values.add(value);
        }
        int[] result = new int[values.size()];
        for (int i = 0; i < values.size(); i++) {
            result[i] = values.get(i);
        }
        return result;
    }

    private static Map<String, Integer> sorted(Map<String, Integer> materials) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(materials.entrySet());
        entries.sort((a, b) -> {
            int byCount = Integer.compare(b.getValue(), a.getValue());
            return byCount != 0 ? byCount : a.getKey().compareTo(b.getKey());
        });
        Map<String, Integer> result = new LinkedHashMap<>();
        for (var entry : entries) {
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }
}
