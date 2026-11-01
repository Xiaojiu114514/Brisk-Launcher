package com.brisklauncher.mcl.game.mod;

import com.brisklauncher.mcl.security.SafeLog;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 从 mod / 资源包 / 光影包「档案本身」取出图标图片。
 *
 * <p>以前列表是拿 Modrinth 的远端图示（要联网、要等 CDN），其实图标就包在 jar 里：
 * <ul>
 *   <li>Fabric / Quilt：{@code fabric.mod.json} 的 {@code icon}（字串或 {尺寸: 路径} 物件）</li>
 *   <li>Forge / NeoForge：{@code META-INF/mods.toml} 的 {@code logoFile=}</li>
 *   <li>资源包 / 光影包：{@code pack.png}</li>
 *   <li>都没有时再试常见档名：{@code icon.png}、{@code logo.png}、{@code assets/<modid>/icon.png}</li>
 * </ul>
 * 只读中央目录与一张小图，不解析 class，失败就回 {@code null}（界面用占位图）。
 */
public final class ModJarIcon {

    private static final int MAX_ICON_BYTES = 512 * 1024;
    private static final List<String> FALLBACKS = List.of(
            "icon.png", "logo.png", "icon.jpg", "logo.jpg", "pack.png",
            "assets/icon.png", "META-INF/icon.png");

    private ModJarIcon() {
    }

    /** 讀圖標位元組；沒有就回 {@code null}。 */
    public static byte[] read(Path file) {
        if (file == null || !Files.exists(file)) {
            return null;
        }
        try {
            if (Files.isDirectory(file)) {
                // 资料夹形态的资源包／光影包：先找根目录常见图标，再看里面一层
                for (String name : List.of("pack.png", "icon.png", "logo.png", "preview.png")) {
                    Path candidate = file.resolve(name);
                    if (Files.isRegularFile(candidate)) {
                        byte[] bytes = readSmall(candidate);
                        if (bytes != null) {
                            return bytes;
                        }
                    }
                }
                try (java.util.stream.Stream<Path> children = Files.list(file)) {
                    for (Path child : children.limit(40).toList()) {
                        if (!Files.isDirectory(child)) {
                            continue;
                        }
                        for (String name : List.of("pack.png", "icon.png", "logo.png")) {
                            Path candidate = child.resolve(name);
                            if (Files.isRegularFile(candidate)) {
                                byte[] bytes = readSmall(candidate);
                                if (bytes != null) {
                                    return bytes;
                                }
                            }
                        }
                    }
                }
                return null;
            }
            try (ZipFile zip = new ZipFile(file.toFile())) {
                List<String> candidates = new ArrayList<>();
                String modId = "";
                String fromMeta = iconFromFabric(zip);
                if (fromMeta == null) {
                    fromMeta = iconFromForge(zip);
                } else {
                    modId = modIdFromFabric(zip);
                }
                if (fromMeta != null) {
                    candidates.add(fromMeta);
                }
                candidates.addAll(FALLBACKS);
                if (!modId.isBlank()) {
                    candidates.add("assets/" + modId + "/icon.png");
                    candidates.add("assets/" + modId + "/logo.png");
                }
                for (String name : candidates) {
                    ZipEntry entry = zip.getEntry(name);
                    if (entry == null || entry.isDirectory() || entry.getSize() > MAX_ICON_BYTES) {
                        continue;
                    }
                    byte[] bytes = readEntry(zip, entry);
                    if (bytes != null && bytes.length > 0) {
                        return bytes;
                    }
                }
                // 最后一招：有些光影包／资源包把图标包在里面一层资料夹
                // （例如「Complementary/pack.png」），固定候选路径就都找不到。
                // 这里扫中央目录，只接受「深度 ≤ 2、档名像图标、大小 ≤ 512KB」的图片。
                byte[] nested = readNestedIcon(zip);
                if (nested != null) {
                    return nested;
                }
            }
        } catch (Exception error) {
            SafeLog.warn("读取 Mod 图标失败：" + file.getFileName() + "（"
                    + error.getClass().getSimpleName() + "）");
        }
        return null;
    }

    private static byte[] readSmall(Path path) {
        try {
            return Files.size(path) <= MAX_ICON_BYTES ? Files.readAllBytes(path) : null;
        } catch (Exception error) {
            return null;
        }
    }

    private static byte[] readEntry(ZipFile zip, ZipEntry entry) {
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(MAX_ICON_BYTES);
            return bytes.length == 0 ? null : bytes;
        } catch (Exception error) {
            return null;
        }
    }

    /**
     * 巢状图标：扫中央目录找「深度 ≤ 2、档名像图标」的图片。
     *
     * <p>光影包常把整个资料夹包进 zip（{@code ShaderName/pack.png}），
     * 之前只查固定的 {@code pack.png} 就永远找不到图标。
     */
    private static byte[] readNestedIcon(ZipFile zip) {
        int scanned = 0;
        java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            if (++scanned > 4000) {
                // 大型整合包动辄上万个条目：扫到上限就放弃，不要拖慢列表
                break;
            }
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory() || entry.getSize() <= 0 || entry.getSize() > MAX_ICON_BYTES) {
                continue;
            }
            String name = entry.getName();
            if (name.chars().filter(c -> c == '/').count() > 2) {
                continue;
            }
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (!lower.endsWith(".png") && !lower.endsWith(".jpg") && !lower.endsWith(".jpeg")) {
                continue;
            }
            String file = lower.substring(lower.lastIndexOf('/') + 1);
            if (!file.startsWith("pack") && !file.startsWith("icon") && !file.startsWith("logo")
                    && !file.startsWith("preview")) {
                continue;
            }
            byte[] bytes = readEntry(zip, entry);
            if (bytes != null && bytes.length > 0) {
                return bytes;
            }
        }
        return null;
    }

    /** fabric.mod.json / quilt.mod.json 的 icon 欄位。 */
    private static String iconFromFabric(ZipFile zip) {
        for (String name : List.of("fabric.mod.json", "quilt.mod.json")) {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null) {
                continue;
            }
            try (InputStream input = zip.getInputStream(entry)) {
                String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                var node = com.brisklauncher.mcl.net.Http.mapper().readTree(json);
                var icon = node.path("icon");
                if (icon.isTextual()) {
                    return icon.asText("");
                }
                if (icon.isObject() && icon.size() > 0) {
                    // {"128": "assets/mod/icon_128.png"} → 取最大尺寸那个
                    String best = null;
                    int bestSize = -1;
                    var fields = icon.fields();
                    while (fields.hasNext()) {
                        var field = fields.next();
                        int size;
                        try {
                            size = Integer.parseInt(field.getKey());
                        } catch (NumberFormatException error) {
                            size = 0;
                        }
                        if (field.getValue().isTextual() && size > bestSize) {
                            bestSize = size;
                            best = field.getValue().asText("");
                        }
                    }
                    return best;
                }
            } catch (Exception ignored) {
                // 换下一个
            }
        }
        return null;
    }

    private static String modIdFromFabric(ZipFile zip) {
        for (String name : List.of("fabric.mod.json", "quilt.mod.json")) {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null) {
                continue;
            }
            try (InputStream input = zip.getInputStream(entry)) {
                String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                String id = com.brisklauncher.mcl.net.Http.mapper().readTree(json).path("id").asText("");
                if (!id.isBlank()) {
                    return id;
                }
            } catch (Exception ignored) {
                // 换下一个
            }
        }
        return "";
    }

    /** Forge / NeoForge 的 mods.toml（logoFile 或 logoFile=）。 */
    private static String iconFromForge(ZipFile zip) {
        for (String name : List.of("META-INF/neoforge.mods.toml", "META-INF/mods.toml")) {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null) {
                continue;
            }
            try (InputStream input = zip.getInputStream(entry)) {
                String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                for (String line : text.split("\\R")) {
                    String trimmed = line.trim();
                    if (!trimmed.startsWith("logoFile")) {
                        continue;
                    }
                    int first = trimmed.indexOf('"');
                    int last = trimmed.lastIndexOf('"');
                    if (first >= 0 && last > first) {
                        return trimmed.substring(first + 1, last);
                    }
                }
            } catch (Exception ignored) {
                // 换下一个
            }
        }
        return null;
    }
}
