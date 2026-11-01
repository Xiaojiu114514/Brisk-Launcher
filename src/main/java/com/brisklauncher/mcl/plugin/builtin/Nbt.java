package com.brisklauncher.mcl.plugin.builtin;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 最小的 NBT 读取器（Minecraft 的存档/结构档案都用它）。
 *
 * <p>只读不写：投影档案（litematic / schem / schematic / nbt 结构）本质上都是 NBT，
 * 这里把整棵树读成 {@code Map<String, Object>}（compound）+ {@code List}（list）+
 * 基本型别，让上层自己挑栏位。
 *
 * <p>支援全部 13 种 tag；自动判断 gzip（大部分投影档都是 gzip 压缩的）。
 */
final class Nbt {

    /** 一個已命名的 tag。 */
    record Tag(int type, String name, Object value) {
    }

    static final int TAG_END = 0;
    static final int TAG_BYTE = 1;
    static final int TAG_SHORT = 2;
    static final int TAG_INT = 3;
    static final int TAG_LONG = 4;
    static final int TAG_FLOAT = 5;
    static final int TAG_DOUBLE = 6;
    static final int TAG_BYTE_ARRAY = 7;
    static final int TAG_STRING = 8;
    static final int TAG_LIST = 9;
    static final int TAG_COMPOUND = 10;
    static final int TAG_INT_ARRAY = 11;
    static final int TAG_LONG_ARRAY = 12;

    private Nbt() {
    }

    /** 讀整個檔案（自動解 gzip）。 */
    static Tag read(byte[] data) throws IOException {
        InputStream raw = new ByteArrayInputStream(data);
        if (data.length >= 2 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B) {
            raw = new GZIPInputStream(raw);
        }
        try (DataInputStream input = new DataInputStream(raw)) {
            int type = input.readUnsignedByte();
            if (type == TAG_END) {
                return new Tag(TAG_END, "", null);
            }
            String name = readString(input);
            // 安全审计 v2 A-01/A-02：解析过程要有「巢状深度」与「累计位元组」两道预算，
            // 否则 300KB 的恶意 .schem 就能 6 万层递回把堆叠打穿（StackOverflowError）。
            long[] budget = {com.brisklauncher.mcl.security.InputBudget.MAX_INFLATED};
            return new Tag(type, name, readPayload(input, type, 0, budget));
        }
    }

    private static Object readPayload(DataInputStream input, int type) throws IOException {
        return readPayload(input, type, 0, new long[]{com.brisklauncher.mcl.security.InputBudget.MAX_INFLATED});
    }

    private static Object readPayload(DataInputStream input, int type, int depth, long[] budget)
            throws IOException {
        if (depth > com.brisklauncher.mcl.security.InputBudget.MAX_NBT_DEPTH) {
            throw new IOException("NBT 巢状过深（> "
                    + com.brisklauncher.mcl.security.InputBudget.MAX_NBT_DEPTH + " 层），疑似恶意档案");
        }
        switch (type) {
            case TAG_BYTE:
                return input.readByte();
            case TAG_SHORT:
                return input.readShort();
            case TAG_INT:
                return input.readInt();
            case TAG_LONG:
                return input.readLong();
            case TAG_FLOAT:
                return input.readFloat();
            case TAG_DOUBLE:
                return input.readDouble();
            case TAG_BYTE_ARRAY: {
                int length = input.readInt();
                check(length);
                charge(budget, length);
                byte[] bytes = new byte[length];
                input.readFully(bytes);
                return bytes;
            }
            case TAG_STRING:
                return readString(input);
            case TAG_LIST: {
                int listType = input.readUnsignedByte();
                int length = input.readInt();
                check(length);
                charge(budget, Math.max(0L, (long) length * 8));
                List<Object> list = new ArrayList<>(Math.min(length, 4096));
                for (int index = 0; index < length; index++) {
                    list.add(readPayload(input, listType, depth + 1, budget));
                }
                return list;
            }
            case TAG_COMPOUND: {
                Map<String, Object> map = new LinkedHashMap<>();
                while (true) {
                    int childType = input.readUnsignedByte();
                    if (childType == TAG_END) {
                        break;
                    }
                    String childName = readString(input);
                    map.put(childName, readPayload(input, childType, depth + 1, budget));
                }
                return map;
            }
            case TAG_INT_ARRAY: {
                int length = input.readInt();
                check(length);
                charge(budget, (long) length * 4);
                int[] values = new int[length];
                for (int index = 0; index < length; index++) {
                    values[index] = input.readInt();
                }
                return values;
            }
            case TAG_LONG_ARRAY: {
                int length = input.readInt();
                check(length);
                charge(budget, (long) length * 8);
                long[] values = new long[length];
                for (int index = 0; index < length; index++) {
                    values[index] = input.readLong();
                }
                return values;
            }
            default:
                throw new IOException("未知的 NBT tag 型别：" + type);
        }
    }

    /** 累計位元組預算：超過就拋（防「一大串 8MB 陣列」把記憶體吃光）。 */
    private static void charge(long[] budget, long bytes) throws IOException {
        budget[0] -= bytes;
        if (budget[0] < 0) {
            throw new IOException("NBT 内容超出解析预算，已拒绝载入");
        }
    }

    /** 長度檢查：防止損壞檔案直接把記憶體吃光。 */
    private static void check(int length) throws IOException {
        if (length < 0 || length > com.brisklauncher.mcl.security.InputBudget.MAX_NBT_ARRAY) {
            throw new IOException("NBT 长度异常：" + length);
        }
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ---------- 取值工具 ----------

    @SuppressWarnings("unchecked")
    static Map<String, Object> compound(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object value) {
        return value instanceof List ? (List<Object>) value : List.of();
    }

    static int intValue(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    static long[] longArray(Object value) {
        return value instanceof long[] array ? array : new long[0];
    }

    static byte[] byteArray(Object value) {
        return value instanceof byte[] array ? array : new byte[0];
    }

    static int[] intArray(Object value) {
        return value instanceof int[] array ? array : new int[0];
    }

    static String string(Object value) {
        return value instanceof String text ? text : "";
    }
}
