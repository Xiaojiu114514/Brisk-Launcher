package com.brisklauncher.mcl.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.brisklauncher.mcl.net.Http;
import com.brisklauncher.mcl.security.SafeLog;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 记录「这个实例装过哪个加载器」。
 *
 * <p>版本 JSON 可能被旧版本的「校验并补全」重写成原版，加载器信息就这么没了。
 * PCL 会把加载器版本记在自己的实例元数据里，这样补全时能重新装回来（而不是猜）。
 * 这里同样单独存一份 {@code loader.json}。
 */
public final class LoaderRecord {

    private static final String FILE_NAME = "loader.json";

    private LoaderRecord() {
    }

    private static Path fileFor(GameInstance entry) {
        return entry.versionDir().resolve(FILE_NAME);
    }

    /** 写入记录（安装加载器成功后调用）。 */
    public static void save(GameInstance entry, LoaderInfo loader) {
        if (entry == null || loader == null || loader.isVanilla()) {
            return;
        }
        try {
            ObjectNode node = Http.mapper().createObjectNode();
            node.put("type", loader.type().name());
            node.put("version", loader.version() == null ? "" : loader.version());
            Files.writeString(fileFor(entry),
                    Http.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(node));
        } catch (Exception error) {
            SafeLog.warn("写入加载器记录失败：" + error.getClass().getSimpleName());
        }
    }

    /** 读取记录；没有记录或读不出来时回传 {@code null}。 */
    public static LoaderInfo load(GameInstance entry) {
        if (entry == null) {
            return null;
        }
        Path file = fileFor(entry);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonNode node = Http.mapper().readTree(Files.readString(file));
            LoaderInfo.LoaderType type = LoaderInfo.LoaderType.valueOf(
                    node.path("type").asText("").toUpperCase(java.util.Locale.ROOT));
            String version = node.path("version").asText("");
            if (type == LoaderInfo.LoaderType.VANILLA) {
                return null;
            }
            return new LoaderInfo(type, version);
        } catch (Exception error) {
            return null;
        }
    }
}
