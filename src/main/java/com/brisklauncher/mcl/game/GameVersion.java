package com.brisklauncher.mcl.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class GameVersion {
    private final JsonNode root;
    private final ObjectMapper mapper = new ObjectMapper();

    public GameVersion(File jsonFile) throws IOException {
        this.root = mapper.readTree(jsonFile);
    }

    public String mainClass() { return root.path("mainClass").asText(); }
    public String assets() { return root.path("assetIndex").path("id").asText(root.path("assets").asText()); }

    public List<String> gameArgs() { return extractArgs("game"); }
    public List<String> jvmArgs() { return extractArgs("jvm"); }

    private List<String> extractArgs(String section) {
        List<String> args = new ArrayList<>();
        JsonNode node = root.path("arguments").path(section);
        if (node.isArray()) {
            for (JsonNode arg : node) {
                if (arg.isTextual()) {
                    args.add(arg.asText());
                }
            }
        }
        return args;
    }

    public List<String> libraries() {
        List<String> libs = new ArrayList<>();
        JsonNode node = root.path("libraries");
        if (node.isArray()) {
            for (JsonNode lib : node) {
                JsonNode artifact = lib.path("downloads").path("artifact");
                if (artifact.has("path")) {
                    libs.add(artifact.path("path").asText());
                }
            }
        }
        return libs;
    }
}