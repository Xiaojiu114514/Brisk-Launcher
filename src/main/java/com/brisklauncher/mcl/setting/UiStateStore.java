package com.brisklauncher.mcl.setting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.brisklauncher.mcl.AppPaths;
import com.brisklauncher.mcl.net.Http;
import com.brisklauncher.mcl.security.SafeLog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 纯 UI 状态（“上次选中的大版本文件夹”“每个文件夹上次选中的实例”）。
 *
 * <p>刻意不使用 {@code java.util.prefs}：它会写注册表，在受限环境（沙箱、精简系统、
 * 非管理员账号）下会抛 {@code SecurityException}，进而把启动链路的异常处理带偏。
 * 这里落在启动器目录的 {@code ui-state.json}，可随便携包一起拷走。
 *
 * <p>结构：{@code {"root": "<绝对路径>", "lastVersions": {"<root 路径>": "<实例 id>"}}}。
 * 旧文件没有 {@code root} 字段也能读，属于纯追加。
 */
public final class UiStateStore {

    private static final String KEY_ROOT = "root";
    private static final String KEY_VERSIONS = "lastVersions";
    private static final String KEY_PREFS = "prefs";
    private static final String KEY_DEPS_LATEST = "dependenciesLatest";

    private final Path file = AppPaths.launcherRoot().resolve("ui-state.json");
    private final ObjectMapper mapper = Http.mapper();

    public String lastVersion(Path root) {
        return load().getOrDefault(root.toString(), "");
    }

    public void rememberVersion(Path root, String versionId) {
        Map<String, String> state = load();
        if (versionId == null || versionId.isBlank()) {
            state.remove(root.toString());
        } else {
            state.put(root.toString(), versionId);
        }
        save(state, lastRoot());
    }

    /** 上次選中的大版本文件夾；沒有記錄時回傳空字串。 */
    public String lastRoot() {
        JsonNode node = readState();
        return node == null ? "" : node.path(KEY_ROOT).asText("");
    }

    public void rememberRoot(Path root) {
        if (root == null) {
            return;
        }
        save(load(), root.toAbsolutePath().normalize().toString());
    }

    public synchronized Map<String, String> load() {
        Map<String, String> result = new LinkedHashMap<>();
        JsonNode node = readState();
        if (node == null) {
            return result;
        }
        node.path(KEY_VERSIONS).fields().forEachRemaining(entry ->
                result.put(entry.getKey(), entry.getValue().asText("")));
        return result;
    }

    private JsonNode readState() {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return mapper.readTree(file.toFile());
        } catch (Exception e) {
            SafeLog.warn("读取 ui-state.json 失败");
            return null;
        }
    }

    private synchronized void save(Map<String, String> versions, String root) {
        write(versions, root, dependenciesLatest());
    }

    // ---------- 下载偏好 ----------

    /**
     * 安装 Mod 时「前置一键下载最新版」是否开启（默认开启）。
     *
     * <p>存在 {@code ui-state.json} 的 {@code prefs} 里，而不是 launcher.json：
     * 改这个开关不该重建下载池／安装器（{@code MclContext.saveSettings} 会整包重建）。
     */
    public boolean dependenciesLatest() {
        JsonNode node = readState();
        if (node == null) {
            return true;
        }
        return node.path(KEY_PREFS).path(KEY_DEPS_LATEST).asBoolean(true);
    }

    public void setDependenciesLatest(boolean value) {
        write(load(), lastRoot(), value);
    }

    private synchronized void write(Map<String, String> versions, String root, boolean dependenciesLatest) {
        try {
            Files.createDirectories(file.getParent());
            ObjectNode node = mapper.createObjectNode();
            if (root != null && !root.isBlank()) {
                node.put(KEY_ROOT, root);
            }
            ObjectNode prefs = node.putObject(KEY_PREFS);
            prefs.put(KEY_DEPS_LATEST, dependenciesLatest);
            ObjectNode lastVersions = node.putObject(KEY_VERSIONS);
            versions.forEach(lastVersions::put);
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), node);
        } catch (Exception e) {
            // UI 状态写不进去不应影响使用
            SafeLog.warn("保存 ui-state.json 失败（不影响启动）");
        }
    }
}
