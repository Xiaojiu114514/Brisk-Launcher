package com.brisklauncher.mcl.game;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.brisklauncher.mcl.AppPaths;
import com.brisklauncher.mcl.security.SafeLog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 持久化用户添加的多版本文件夹列表。 */
public final class GameDirectoryStore {

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    public GameDirectoryStore() {
        this(AppPaths.launcherRoot().resolve("versionroots.json"));
    }

    public GameDirectoryStore(Path file) {
        this.file = file;
    }

    @SuppressWarnings("unchecked")
    public List<GameDirectory> load() {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            Map<String, Object> root = mapper.readValue(file.toFile(), Map.class);
            Object raw = root.get("roots");
            if (!(raw instanceof List<?> list)) {
                return List.of();
            }
            List<GameDirectory> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Path p = Path.of(String.valueOf(m.get("path")));
                    String name = m.get("displayName") == null
                            ? null : String.valueOf(m.get("displayName"));
                    result.add(new GameDirectory(p, name));
                }
            }
            return result;
        } catch (IOException e) {
            SafeLog.warn("读取版本文件夹列表失败");
            return List.of();
        }
    }

    public void save(List<GameDirectory> roots) {
        try {
            Files.createDirectories(file.getParent());
            List<Map<String, Object>> list = new ArrayList<>();
            for (GameDirectory r : roots) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("path", r.path().toString());
                m.put("displayName", r.displayName());
                list.add(m);
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("roots", list);
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
        } catch (IOException e) {
            SafeLog.warn("保存版本文件夹列表失败");
        }
    }
}