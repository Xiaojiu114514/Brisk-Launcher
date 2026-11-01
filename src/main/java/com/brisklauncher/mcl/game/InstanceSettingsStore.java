package com.brisklauncher.mcl.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.brisklauncher.mcl.AppPaths;
import com.brisklauncher.mcl.security.SafeLog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 实例设置持久化：{@code <launcher>/instances/<rootHash>-<id>/instance.json}。
 *
 * <p>用 root 路径的短哈希做前缀，避免不同大版本文件夹里的同名版本互相覆盖。
 */
public final class InstanceSettingsStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public InstanceSettings load(GameInstance entry, int defaultMemoryMb) {
        Path file = fileFor(entry);
        InstanceSettings fallback = InstanceSettings.defaults(defaultMemoryMb, entry.isolated());
        if (!Files.isRegularFile(file)) {
            return fallback;
        }
        try {
            JsonNode node = MAPPER.readTree(file.toFile());
            List<String> jvmArgs = new ArrayList<>();
            for (JsonNode arg : node.path("jvmArgs")) {
                jvmArgs.add(arg.asText(""));
            }
            return new InstanceSettings(
                    node.path("memoryMb").asInt(fallback.memoryMb()),
                    node.path("javaPath").asText(""),
                    jvmArgs,
                    node.path("isolated").asBoolean(fallback.isolated()),
                    node.path("width").asInt(0),
                    node.path("height").asInt(0));
        } catch (Exception e) {
            SafeLog.warn("读取实例设置失败，使用默认值");
            return fallback;
        }
    }

    public void save(GameInstance entry, InstanceSettings settings) {
        Path file = fileFor(entry);
        try {
            Files.createDirectories(file.getParent());
            ObjectNode node = MAPPER.createObjectNode();
            node.put("id", entry.id());
            node.put("root", entry.root().path().toString());
            node.put("memoryMb", settings.memoryMb());
            node.put("javaPath", settings.javaPath());
            node.put("isolated", settings.isolated());
            node.put("width", settings.width());
            node.put("height", settings.height());
            ArrayNode args = node.putArray("jvmArgs");
            settings.jvmArgs().forEach(args::add);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), node);
        } catch (Exception e) {
            SafeLog.warn("保存实例设置失败");
        }
    }

    public Path fileFor(GameInstance entry) {
        return AppPaths.instancesDir()
                .resolve(shortHash(entry.root().path().toString()) + "-" + sanitize(entry.id()))
                .resolve("instance.json");
    }

    private static String shortHash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 8);
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    private static String sanitize(String id) {
        return id.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
