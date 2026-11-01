package com.brisklauncher.mcl.game;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;

/**
 * 版本 JSON 里 library 条目的统一解析。
 *
 * <p>这里是踩坑最多的地方，集中到一处避免安装器和启动器各写一份还写错：
 * <ul>
 *   <li>老版本（1.6~1.12）的 natives 库只有 {@code downloads.classifiers}，**没有** artifact，
 *       自己按 name 推导路径去下载会 404（lwjgl-platform / jinput-platform 都是这种）；</li>
 *   <li>现代版本（1.19+）的 natives 是独立条目，artifact 路径里带 {@code -natives-}，
 *       要下载但不进 classpath；</li>
 *   <li>Forge 的自定义库可能没有 downloads 字段，只有 {@code name} + {@code url}，需要推导路径。</li>
 * </ul>
 */
public final class Libraries {

    public static final String MOJANG_LIBRARIES = "https://libraries.minecraft.net/";

    private Libraries() {
    }

    /** 是否有可下载的普通构件。 */
    public static boolean hasArtifact(JsonNode library) {
        JsonNode artifact = library.path("downloads").path("artifact");
        return !artifact.path("url").asText("").isEmpty()
                || !artifact.path("path").asText("").isEmpty();
    }

    /** 只有 natives，没有普通构件（不能按 name 推导下载 404 的 jar）。 */
    public static boolean isNativesOnly(JsonNode library) {
        if (hasArtifact(library)) {
            return false;
        }
        return library.path("natives").isObject() || library.path("downloads").path("classifiers").isObject();
    }

    /** 构件在 libraries 目录里的相对路径；无法确定时返回 null。 */
    public static String pathOf(JsonNode library) {
        JsonNode artifact = library.path("downloads").path("artifact");
        String path = artifact.path("path").asText("");
        if (!path.isEmpty()) {
            return path;
        }
        return derivePath(library.path("name").asText(""));
    }

    public static String derivePath(String name) {
        String[] parts = name.split(":");
        if (parts.length < 3 || parts[0].isEmpty() || parts[1].isEmpty()) {
            return null;
        }
        String group = parts[0].replace('.', '/');
        String artifactId = parts[1];
        String version = parts[2];
        String classifier = parts.length > 3 ? "-" + parts[3] : "";
        return group + "/" + artifactId + "/" + version + "/"
                + artifactId + "-" + version + classifier + ".jar";
    }

    /** 需要解压到 natives 目录的 jar（现代版本命名约定）。 */
    public static boolean isNativeJar(String relativePath) {
        if (relativePath == null) {
            return false;
        }
        String name = relativePath.substring(relativePath.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return name.contains("-natives-");
    }

    /** 当前系统对应的 natives classifier 键（把 ${arch} 换成 32/64）。 */
    public static String nativeClassifier(JsonNode library) {
        JsonNode natives = library.path("natives");
        if (!natives.isObject()) {
            return null;
        }
        String key = natives.path(Rules.OS_NAME).asText("");
        return key.isEmpty() ? null : key.replace("${arch}", Rules.archBits());
    }

    public static String baseUrl(JsonNode library) {
        String url = library.path("url").asText("");
        if (url.isEmpty()) {
            return MOJANG_LIBRARIES;
        }
        return url.endsWith("/") ? url : url + "/";
    }
}
