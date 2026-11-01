package com.brisklauncher.mcl.modpack;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Modrinth modpack 索引（{@code modrinth.index.json}）。
 *
 * <p>字段严格按 mrpack 规范：{@code formatVersion / game / versionId / name / dependencies / files}。
 * 这里用 JsonNode 手工解析，避免 Jackson 反射绑定在 JPMS 下的 opens 麻烦。
 */
public record ModpackIndex(String formatVersion, String name, String versionId, String summary,
                           Map<String, String> dependencies, List<FileEntry> files) {

    public record FileEntry(String path, String sha1, String sha512, long size,
                            List<String> downloads, boolean clientSide, boolean serverSide) {
    }

    public static ModpackIndex parse(JsonNode root) {
        Map<String, String> dependencies = new LinkedHashMap<>();
        JsonNode deps = root.path("dependencies");
        deps.fields().forEachRemaining(entry ->
                dependencies.put(entry.getKey(), entry.getValue().asText("")));

        List<FileEntry> files = new ArrayList<>();
        for (JsonNode file : root.path("files")) {
            List<String> downloads = new ArrayList<>();
            for (JsonNode url : file.path("downloads")) {
                downloads.add(url.asText(""));
            }
            JsonNode env = file.path("env");
            String client = env.path("client").asText("required");
            String server = env.path("server").asText("required");
            files.add(new FileEntry(
                    file.path("path").asText(""),
                    file.path("hashes").path("sha1").asText(""),
                    file.path("hashes").path("sha512").asText(""),
                    file.path("fileSize").asLong(0),
                    downloads,
                    !"unsupported".equalsIgnoreCase(client),
                    !"unsupported".equalsIgnoreCase(server)));
        }

        return new ModpackIndex(
                root.path("formatVersion").asText("1"),
                root.path("name").asText(""),
                root.path("versionId").asText(""),
                root.path("summary").asText(""),
                dependencies,
                files);
    }

    public String mcVersion() {
        return dependencies.getOrDefault("minecraft", "");
    }

    /** fabric / quilt / forge / neoforge，没有加载器时返回 null。 */
    public String loaderType() {
        if (dependencies.containsKey("fabric-loader")) {
            return "fabric";
        }
        if (dependencies.containsKey("quilt-loader")) {
            return "quilt";
        }
        if (dependencies.containsKey("neoforge")) {
            return "neoforge";
        }
        if (dependencies.containsKey("forge")) {
            return "forge";
        }
        return null;
    }

    public String loaderVersion() {
        String type = loaderType();
        if (type == null) {
            return "";
        }
        return dependencies.getOrDefault(type + "-loader", dependencies.getOrDefault(type, ""));
    }

    public String displayName() {
        if (name != null && !name.isBlank()) {
            return name;
        }
        return versionId == null || versionId.isBlank() ? "modpack" : versionId;
    }

    public int clientFileCount() {
        return (int) files.stream().filter(FileEntry::clientSide).count();
    }
}
