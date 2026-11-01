package com.brisklauncher.mcl.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.brisklauncher.mcl.security.SafeLog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 版本 JSON 合并（处理 {@code inheritsFrom}）。
 *
 * <p>规则：
 * <ul>
 *   <li>libraries：父在前、子在后，按 {@code group:artifact[:classifier]} 去重，子版本覆盖父版本；</li>
 *   <li>arguments：父的 game/jvm 参数在前，子的追加在后；</li>
 *   <li>downloads / javaVersion / logging 等对象：逐字段覆盖，避免子版本的空对象把父版本信息冲掉；</li>
 *   <li>mainClass / assetIndex / assets / type 等标量：子版本优先。</li>
 * </ul>
 * 这是 Forge/NeoForge 安装器生成的 JSON 能正常启动的关键。
 */
public final class GameVersionMerger {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path gameRoot;

    public GameVersionMerger(Path gameRoot) {
        this.gameRoot = gameRoot.toAbsolutePath().normalize();
    }

    /** 合并后的完整版本 JSON（调用方不要修改返回值）。 */
    public ObjectNode merge(GameInstance entry) throws IOException {
        return merge(entry, new LinkedHashSet<>());
    }

    public ObjectNode merge(GameInstance entry, Set<String> visited) throws IOException {
        return mergeFile(entry.jsonFile(), visited);
    }

    private ObjectNode mergeFile(Path jsonFile, Set<String> visited) throws IOException {
        if (!Files.isRegularFile(jsonFile)) {
            throw new IOException("缺少版本 JSON：" + jsonFile.getFileName());
        }
        JsonNode raw = MAPPER.readTree(jsonFile.toFile());
        if (!raw.isObject()) {
            throw new IOException("版本 JSON 格式错误");
        }
        ObjectNode child = (ObjectNode) raw;
        String parentId = child.path("inheritsFrom").asText("");
        if (!parentId.isEmpty()) {
            // 安全审计 L10：inheritsFrom 会被当路径片段用，必须是合法 id
            parentId = com.brisklauncher.mcl.security.PathSafety.requirePathSegment(parentId);
        }
        if (parentId.isBlank()) {
            return child.deepCopy();
        }
        if (!visited.add(parentId)) {
            throw new IOException("版本继承出现死循环：" + parentId);
        }
        Path parentFile = gameRoot.resolve("versions").resolve(parentId).resolve(parentId + ".json");
        if (!Files.isRegularFile(parentFile)) {
            throw new IOException("缺少父版本 " + parentId + "，请先安装原版");
        }
        ObjectNode parent = mergeFile(parentFile, visited);
        return combine(parent, child);
    }

    private static ObjectNode combine(ObjectNode parent, ObjectNode child) {
        ObjectNode merged = parent.deepCopy();
        merged.put("id", child.path("id").asText(merged.path("id").asText("")));

        // ---- libraries ----
        Map<String, JsonNode> byKey = new LinkedHashMap<>();
        for (JsonNode library : parent.path("libraries")) {
            byKey.put(libraryKey(library), library);
        }
        for (JsonNode library : child.path("libraries")) {
            byKey.put(libraryKey(library), library);
        }
        ArrayNode libraries = MAPPER.createArrayNode();
        byKey.values().forEach(libraries::add);
        if (!libraries.isEmpty()) {
            merged.set("libraries", libraries);
        }

        // ---- arguments ----
        if (merged.has("arguments") || child.has("arguments")) {
            ObjectNode mergedArgs = merged.has("arguments") && merged.get("arguments").isObject()
                    ? (ObjectNode) merged.get("arguments")
                    : MAPPER.createObjectNode();
            for (String section : List.of("game", "jvm")) {
                JsonNode childSection = child.path("arguments").path(section);
                if (childSection.isArray() && !childSection.isEmpty()) {
                    ArrayNode target = mergedArgs.withArray(section);
                    childSection.forEach(target::add);
                }
            }
            merged.set("arguments", mergedArgs);
        }

        // ---- 对象型字段：逐字段覆盖 ----
        mergeObject(merged, parent, child, "downloads");
        mergeObject(merged, parent, child, "javaVersion");
        mergeObject(merged, parent, child, "logging");

        // ---- 标量字段：子优先 ----
        for (String key : List.of("mainClass", "assetIndex", "assets", "type", "releaseTime",
                "time", "minimumLauncherVersion", "complianceLevel", "jar")) {
            if (child.has(key) && !child.get(key).isNull()) {
                merged.set(key, child.get(key));
            }
        }
        if (child.has("minecraftArguments")) {
            merged.set("minecraftArguments", child.get("minecraftArguments"));
        }
        return merged;
    }

    private static void mergeObject(ObjectNode merged, ObjectNode parent, ObjectNode child, String key) {
        JsonNode childNode = child.get(key);
        if (childNode == null || childNode.isNull()) {
            return;
        }
        if (!childNode.isObject()) {
            merged.set(key, childNode);
            return;
        }
        ObjectNode target = merged.has(key) && merged.get(key).isObject()
                ? (ObjectNode) merged.get(key)
                : MAPPER.createObjectNode();
        childNode.fields().forEachRemaining(entry -> target.set(entry.getKey(), entry.getValue()));
        merged.set(key, target);
    }

    /** group:artifact[:classifier]，去掉版本号，用于去重。 */
    static String libraryKey(JsonNode library) {
        String name = library.path("name").asText("");
        String[] parts = name.split(":");
        if (parts.length < 2) {
            return name;
        }
        String key = parts[0] + ":" + parts[1];
        if (parts.length >= 4) {
            key += ":" + parts[3];
        }
        return key.toLowerCase(java.util.Locale.ROOT);
    }

    // ---------- 继承链工具 ----------

    /**
     * 返回从子到父的版本 ID 链，例如 {@code [fabric-loader-0.16.5-1.20.1, 1.20.1]}。
     * 用于收集 classpath 上每一层的 client jar —— Fabric/Forge 的实例没有自己的 jar，
     * 必须把父版本的 jar 带上（HMCL 的做法）。
     */
    public static List<String> chain(Path gameRoot, String startId) {
        List<String> result = new ArrayList<>();
        String current = startId;
        Set<String> visited = new LinkedHashSet<>();
        while (current != null && !current.isBlank() && visited.add(current)) {
            result.add(current);
            Path json = gameRoot.resolve("versions").resolve(current).resolve(current + ".json");
            if (!Files.isRegularFile(json)) {
                break;
            }
            try {
                current = MAPPER.readTree(json.toFile()).path("inheritsFrom").asText("");
            } catch (IOException e) {
                SafeLog.warn("读取继承链失败：" + current);
                break;
            }
        }
        return result;
    }

    /** 继承链每一层里实际存在的 jar。 */
    public static List<Path> chainJars(Path gameRoot, String startId) {
        List<Path> result = new ArrayList<>();
        for (String id : chain(gameRoot, startId)) {
            Path jar = gameRoot.resolve("versions").resolve(id).resolve(id + ".jar");
            if (Files.isRegularFile(jar)) {
                result.add(jar);
            }
        }
        return result;
    }
}
