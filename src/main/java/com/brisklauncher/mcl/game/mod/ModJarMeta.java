package com.brisklauncher.mcl.game.mod;

import com.brisklauncher.mcl.security.SafeLog;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 从 mod jar 里读出「自己叫什么、依赖谁」。
 *
 * <p>为什么需要它：Modrinth 的 {@code /version_file/{sha1}} 查不到的 Mod（自制、第三方站下载）在依赖图上
 * 只有一个孤零零的节点；但 jar 里的 metadata 其实写得很清楚：
 * <ul>
 *   <li>Fabric / Quilt：{@code fabric.mod.json} → {@code id}、{@code depends}（modid → 版本范围）；</li>
 *   <li>Forge / NeoForge：{@code META-INF/mods.toml} / {@code META-INF/neoforge.mods.toml} →
 *       {@code [[dependencies.<modid>]]} 区块里的 {@code modId}。</li>
 * </ul>
 * 只读 metadata（不解析 class），失败就回传 null，不影响其它 Mod。
 *
 * @param modId   Mod 自己的 id（例如 {@code sodium-extra}）
 * @param name    显示名称（可能为空）
 * @param depends 依赖的 mod id（已去掉 minecraft/fabricloader/forge/java 这类平台依赖）
 */
public record ModJarMeta(String modId, String name, List<String> depends) {

    /** 平台/加載器依賴：不是「前置 Mod」，不畫到圖上。 */
    private static final List<String> PLATFORM = List.of(
            "minecraft", "fabricloader", "fabric-loader", "fabric", "quilt_loader",
            "quilt-loader", "forge", "neoforge", "java", "cloth-config", "cloth_config");

    private static final Pattern TOML_SECTION = Pattern.compile("^\\[\\[dependencies\\.([A-Za-z0-9_\\-]+)]]");
    private static final Pattern TOML_MOD_ID = Pattern.compile("^\\s*modId\\s*=\\s*[\"']([^\"']+)[\"']");

    /** 讀取一個 jar 的 metadata；不是 mod jar 或讀不到就回傳 null。 */
    public static ModJarMeta read(Path jar) {
        if (jar == null) {
            return null;
        }
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ModJarMeta fabric = readFabric(zip);
            if (fabric != null) {
                return fabric;
            }
            return readForge(zip);
        } catch (Exception error) {
            SafeLog.warn("读取 Mod metadata 失败：" + jar.getFileName());
            return null;
        }
    }

    private static ModJarMeta readFabric(ZipFile zip) {
        ZipEntry entry = zip.getEntry("fabric.mod.json");
        if (entry == null) {
            return null;
        }
        try (InputStream input = zip.getInputStream(entry)) {
            String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            var node = com.brisklauncher.mcl.net.Http.mapper().readTree(json);
            String id = node.path("id").asText("");
            String name = node.path("name").asText("");
            List<String> depends = new ArrayList<>();
            var fields = node.path("depends").fields();
            while (fields.hasNext()) {
                var field = fields.next();
                String dependencyId = field.getKey();
                if (!PLATFORM.contains(dependencyId.toLowerCase(Locale.ROOT))) {
                    depends.add(dependencyId);
                }
            }
            return new ModJarMeta(id, name, List.copyOf(depends));
        } catch (Exception error) {
            return null;
        }
    }

    private static ModJarMeta readForge(ZipFile zip) {
        ZipEntry entry = zip.getEntry("META-INF/neoforge.mods.toml");
        if (entry == null) {
            entry = zip.getEntry("META-INF/mods.toml");
        }
        if (entry == null) {
            return null;
        }
        try (InputStream input = zip.getInputStream(entry)) {
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            String modId = "";
            String name = "";
            List<String> depends = new ArrayList<>();
            String currentSection = "";
            for (String line : text.split("\\R")) {
                String trimmed = line.trim();
                Matcher section = TOML_SECTION.matcher(trimmed);
                if (section.find()) {
                    currentSection = section.group(1);
                    continue;
                }

                Matcher modIdMatcher = TOML_MOD_ID.matcher(line);
                if (modIdMatcher.find()) {
                    String value = modIdMatcher.group(1);
                    if (currentSection.isBlank()) {
                        // 顶层的 modId：Mod 自己
                        if (modId.isBlank()) {
                            modId = value;
                        }
                    } else if (!value.equalsIgnoreCase(currentSection)
                            && !PLATFORM.contains(value.toLowerCase(Locale.ROOT))) {
                        depends.add(value);
                    }
                    continue;
                }
                if (trimmed.startsWith("displayName") && name.isBlank()) {
                    int first = trimmed.indexOf('"');
                    int last = trimmed.lastIndexOf('"');
                    if (first >= 0 && last > first) {
                        name = trimmed.substring(first + 1, last);
                    }
                }
            }
            // [[dependencies.<modid>]] 的区块名也可能是依赖方；modId 已经收集到 depends
            if (modId.isBlank()) {
                return null;
            }
            return new ModJarMeta(modId, name, List.copyOf(depends));
        } catch (Exception error) {
            return null;
        }
    }
}
