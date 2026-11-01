package com.brisklauncher.mcl.security;

import com.brisklauncher.mcl.AppPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 第三方内容风险确认纪录（对应安全审计 v2.0 的 §4.5）。
 *
 * <p>只在使用者「明确选择继续」时写入一笔带时间戳的纪录；取消则不写。
 * 目的有两个：
 * <ol>
 *   <li>同一件事不再重复打扰（一次确认、长期有效）；</li>
 *   <li>支持人员排查时可以直接看这个档案，避免「用户说没提示过」的争执。</li>
 * </ol>
 *
 * <p>档案位置：{@code <launcherRoot>/acks.json}（私密目录，仅当前用户可访问），
 * 内容只有确认项与时间，不含任何凭据。
 *
 * <p>安全审计 L-04 / H-02：以前放在 {@code data/acks.json}（安装目录，可能人人可写）——
 * 预写这个文件就能跳过风险提示，进而让 {@code data/plugins/*.jar} 被静默加载执行。
 * 现在搬进私密目录；旧位置的内容会迁移一次，然后删掉。
 */
public final class Acks {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private Acks() {
    }

    private static Path file() {
        return AppPaths.acksFile();
    }

    /** 旧版本把 acks.json 放在 data/：迁移一次到私密目录并删掉旧文件。 */
    private static void migrateLegacy() {
        try {
            Path legacy = AppPaths.dataDir().resolve("acks.json");
            if (!Files.isRegularFile(legacy) || Files.exists(file())) {
                return;
            }
            Files.createDirectories(file().getParent());
            Files.copy(legacy, file(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(legacy);
            FilePerms.ensurePrivateFile(file());
            SafeLog.info("已把 acks.json 迁移到私密目录");
        } catch (Exception error) {
            SafeLog.warn("迁移 acks.json 失败：" + error.getClass().getSimpleName());
        }
    }

    /** 是否已確認過某項（例如 plugins / customMirror / insecureTls / modrinth）。 */
    public static boolean has(String key) {
        return read().containsKey(key);
    }

    /**
     * 带对象指纹的确认（安全审计 v3 A-15）。
     *
     * <p>问题：ack 记的是「玩家同意过某类风险」，但风险大小取决于**具体对象**。
     * 玩家在信任 BMCLAPI 的前提下点了同意，之后把自订镜像换成陌生网域，
     * 旧的确认**照样有效** —— 一次同意被复用到风险完全不同的对象上。
     *
     * <p>做法：把对象（主机名 / 摘要）附在 key 上。换了对象就等于没确认过。
     */
    public static String scopedKey(String kind, String subject) {
        String clean = subject == null ? "" : subject.trim().toLowerCase(java.util.Locale.ROOT);
        if (clean.isEmpty()) {
            return kind;
        }
        return kind + ":" + clean;
    }

    /** 這個「種類」是否有任一對象被確認過（例如任何鏡像）。 */
    public static boolean hasAnyOfKind(String kind) {
        String prefix = kind + ":";
        for (String key : read().keySet()) {
            if (key.equals(kind) || key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 記錄一次確認（版本號一併寫入，方便日後做大版本重問）。 */
    public static void record(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        Map<String, Object> data = read();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("at", Instant.now().toString());
        entry.put("version", launcherVersion());
        data.put(key, entry);
        try {
            Files.createDirectories(file().getParent());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(file().toFile(), data);
            FilePerms.ensurePrivateFile(file());
        } catch (Exception error) {
            SafeLog.warn("写入 acks.json 失败：" + error.getClass().getSimpleName());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read() {
        migrateLegacy();
        try {
            if (!Files.isRegularFile(file())) {
                return new LinkedHashMap<>();
            }
            Map<String, Object> data = MAPPER.readValue(file().toFile(), Map.class);
            return data == null ? new LinkedHashMap<>() : data;
        } catch (Exception error) {
            return new LinkedHashMap<>();
        }
    }

    private static String launcherVersion() {
        String version = Acks.class.getPackage() == null
                ? null : Acks.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "1.0.0" : version;
    }
}
