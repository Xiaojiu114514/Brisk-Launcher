package com.brisklauncher.mcl.plugin;

import com.brisklauncher.mcl.net.Http;
import com.brisklauncher.mcl.plugin.api.PluginApi;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * {@code plugin.json} 的解析器（只做语法与基本校验，不碰 classloader / 摘要 / 禁用名单）。
 *
 * <p>拆出来是为了可测：{@code PluginSelfTest} 可以直接拿一支 jar 验证「启动器读到的
 * id / class / apiVersion / requires 是什么」，不用建整个 {@link PluginManager}。
 */
final class PluginManifest {

    /** 解析结果；{@code localeTags} 是 jar 里自带的语言包标签。 */
    record Data(String id, String name, String description, String className, String version,
                String author, String homepage, String icon, int apiVersion,
                List<String> requires, List<String> localeTags) {
    }

    static final String FILE = "plugin.json";

    private PluginManifest() {
    }

    /**
     * 读取并校验 {@code plugin.json}。
     *
     * @throws IllegalArgumentException 檔案缺失或 JSON 壞掉（訊息可直接顯示給使用者）
     */
    static Data read(Path jar) throws Exception {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(FILE);
            if (entry == null) {
                throw new IllegalArgumentException("缺少 plugin.json（必須放在 jar 根目錄）");
            }
            String text;
            try (InputStream input = zip.getInputStream(entry)) {
                text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            var node = Http.mapper().readTree(text);
            String className = node.path("class").asText("").trim();
            if (className.isBlank()) {
                throw new IllegalArgumentException("plugin.json 缺少 class 栏位");
            }
            List<String> requires = new ArrayList<>();
            for (var item : node.path("requires")) {
                String required = PluginManager.sanitizeId(item.asText(""));
                if (!required.isBlank()) {
                    requires.add(required);
                }
            }
            return new Data(
                    PluginManager.sanitizeId(node.path("id").asText("")),
                    node.path("name").asText(""),
                    node.path("description").asText(""),
                    className,
                    node.path("version").asText(""),
                    node.path("author").asText(""),
                    node.path("homepage").asText(""),
                    node.path("icon").asText(""),
                    node.path(PluginApi.MANIFEST_API).asInt(PluginApi.VERSION),
                    requires,
                    PluginMessagesImpl.scanLocales(zip));
        }
    }
}
