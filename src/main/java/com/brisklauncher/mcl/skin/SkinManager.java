package com.brisklauncher.mcl.skin;

import com.brisklauncher.mcl.AppPaths;
import com.brisklauncher.mcl.auth.api.AccountType;
import com.brisklauncher.mcl.auth.AccountStore.Account;
import com.brisklauncher.mcl.game.GameInstance;
import com.brisklauncher.mcl.net.Http;
import com.brisklauncher.mcl.security.SafeLog;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 全局皮肤衣橱 + CustomSkinLoader 写入。
 *
 * <p>按开发方案 4.8：所有离线账号共用一套纹理，存 {@code <launcher>/skin/}；
 * 切换账号/实例时对当前实例写一次 CSL 本地皮肤。CSL 不热生效，写入只是“为下次启动准备”。
 */
public final class SkinManager {

    public enum Status {
        APPLIED,
        NO_SKIN,
        NO_TARGET,
        CSL_MISSING,
        FAILED
    }

    public record ApplyResult(Status status, String path) {
        public boolean applied() {
            return status == Status.APPLIED;
        }
    }

    private final Path skinDir = AppPaths.launcherRoot().resolve("skin");
    private final Path libraryDir = skinDir.resolve("library");
    private final Path globalSkin = skinDir.resolve("global_skin.png");
    private final Path globalCape = skinDir.resolve("global_cape.png");

    public Path skinDir() {
        return skinDir;
    }

    public Path globalSkin() {
        return globalSkin;
    }

    public Path globalCape() {
        return globalCape;
    }

    public Path libraryDir() {
        return libraryDir;
    }

    /** 离线皮肤库中保存的所有皮肤（按文件名排序）。 */
    public List<Path> librarySkins() {
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(libraryDir)) {
            return result;
        }
        try (var stream = Files.list(libraryDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(".png"))
                    .sorted()
                    .forEach(result::add);
        } catch (IOException ignored) {
            // 返回已读到的部分
        }
        return result;
    }

    /** 把一张皮肤存进离线皮肤库，并设为当前全局皮肤。 */
    public Path importSkin(Path source) {
        try {
            Files.createDirectories(libraryDir);
            String name = "skin-" + System.currentTimeMillis() + ".png";
            Path target = libraryDir.resolve(name);
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(target, globalSkin, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            SafeLog.warn("写入皮肤库失败");
            throw new IllegalStateException("写入皮肤库失败", e);
        }
    }

    /** 从皮肤库快速切换当前皮肤。 */
    public void selectSkin(Path librarySkin) {
        copyInto(librarySkin, globalSkin);
    }

    public void deleteSkin(Path librarySkin) {
        try {
            Files.deleteIfExists(librarySkin);
        } catch (IOException e) {
            SafeLog.warn("删除皮肤库文件失败");
        }
    }

    /**
     * 在线账号（LittleSkin / 微软）的皮肤缓存路径。
     *
     * <p>安全审计 H-01：{@code uuid} 是第三方皮肤站返回的**远端不可信输入**，以前只做
     * 「去连字符 + 小写」，{@code ../../x} 这种值会拼出 {@code skin/} 之外的路径（任意文件写）。
     * 现在：①只有 32 位 hex 才原样使用，其余一律换成摘要；②拼好后再用
     * {@link com.brisklauncher.mcl.security.PathSafety#resolveUnder} 断言必须落在 skin/ 内。
     */
    public Path onlineSkin(String uuid) {
        return com.brisklauncher.mcl.security.PathSafety.resolveUnder(
                skinDir, "online_" + sanitizeUuid(uuid) + ".png");
    }

    public Optional<Path> onlineSkinIfPresent(String uuid) {
        Path path = onlineSkin(uuid);
        return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }

    /**
     * 从会话服务器读取当前角色材质。
     *
     * <p>LittleSkin 与微软都提供无鉴权的 profile 接口；返回的 textures 属性是 base64
     * JSON，里面带 SKIN.url。下载后缓存到 {@code skin/online_<uuid>.png}，供 2D/3D 预览使用。
     */
    public Optional<Path> refreshOnlineSkin(Account account) {
        if (account == null || account.type() == AccountType.OFFLINE) {
            return Optional.empty();
        }
        String uuid = sanitizeUuid(account.uuid());
        String base = account.endpoint() == null || account.endpoint().isBlank()
                ? "https://sessionserver.mojang.com"
                : trimSlash(account.endpoint());
        String path = account.type() == AccountType.THIRD_PARTY
                ? "/sessionserver/session/minecraft/profile/" + uuid
                : "/session/minecraft/profile/" + uuid;
        try {
            JsonNode profile = Http.getJson(URI.create(base + path), Duration.ofSeconds(15));
            JsonNode properties = profile.path("properties");
            for (JsonNode property : properties) {
                if (!"textures".equals(property.path("name").asText())) {
                    continue;
                }
                String encoded = property.path("value").asText("");
                if (encoded.isBlank()) {
                    continue;
                }
                String decoded = new String(Base64.getDecoder().decode(encoded),
                        StandardCharsets.UTF_8);
                JsonNode textures = Http.mapper().readTree(decoded).path("textures");
                String url = textures.path("SKIN").path("url").asText("");
                if (url.isBlank()) {
                    continue;
                }
                byte[] bytes = Http.getBytes(URI.create(url), Duration.ofSeconds(20));
                Path target = onlineSkin(uuid);
                Files.createDirectories(target.getParent());
                Files.write(target, bytes);
                return Optional.of(target);
            }
        } catch (Exception error) {
            SafeLog.warn("获取在线皮肤失败：" + error.getClass().getSimpleName());
        }
        return Optional.empty();
    }

    private static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /** 32 位 hex（去掉连字符后的标准 UUID）。 */
    private static final java.util.regex.Pattern UUID_OK =
            java.util.regex.Pattern.compile("[0-9a-f]{32}");

    /**
     * 把远端给的 UUID 变成**只能当文件名用**的片段。
     *
     * <p>安全审计 H-01：不是标准 UUID 就一律换成 SHA-256 摘要（稳定、可重现、不含分隔符），
     * 绝不让远端字符串直接参与路径拼接。
     */
    private static String sanitizeUuid(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return "unknown";
        }
        String bare = uuid.replace("-", "").toLowerCase(Locale.ROOT);
        if (UUID_OK.matcher(bare).matches()) {
            return bare;
        }
        byte[] digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(bare.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            return "invalid";
        }
        StringBuilder sb = new StringBuilder("x");
        for (int i = 0; i < 12 && i < digest.length; i++) {
            sb.append(String.format("%02x", digest[i] & 0xFF));
        }
        SafeLog.warn("[skin] 账号 UUID 不是标准格式，已改用摘要作为缓存名");
        return sb.toString();
    }

    public boolean hasSkin() {
        return Files.isRegularFile(globalSkin);
    }

    public boolean hasCape() {
        return Files.isRegularFile(globalCape);
    }

    public void setSkin(Path source) {
        copyInto(source, globalSkin);
    }

    public void setCape(Path source) {
        copyInto(source, globalCape);
    }

    private void copyInto(Path source, Path destination) {
        try {
            Files.createDirectories(skinDir);
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            SafeLog.warn("保存皮肤文件失败");
            throw new IllegalStateException("保存皮肤文件失败", e);
        }
    }

    /** 判断实例是否安装了 CustomSkinLoader（按 jar 名模糊匹配，不依赖具体版本）。 */
    public boolean hasCustomSkinLoader(Path gameDir) {
        Path mods = gameDir.resolve("mods");
        if (!Files.isDirectory(mods)) {
            return false;
        }
        try (var stream = Files.list(mods)) {
            return stream.anyMatch(file -> {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                return name.endsWith(".jar") && name.contains("customskinloader");
            });
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 把全局纹理写入实例的 CSL 目录。
     * 未安装 CSL 时静默跳过（UI 自己提示），不创建无关目录。
     */
    public ApplyResult applyToInstance(GameInstance entry, String playerName) {
        if (entry == null) {
            return new ApplyResult(Status.NO_TARGET, "");
        }
        if (!hasSkin() && !hasCape()) {
            return new ApplyResult(Status.NO_SKIN, "");
        }
        Path gameDir = entry.gameDir();
        if (!hasCustomSkinLoader(gameDir)) {
            return new ApplyResult(Status.CSL_MISSING, gameDir.toString());
        }
        String safeName = sanitize(playerName);
        if (safeName.isEmpty()) {
            return new ApplyResult(Status.NO_TARGET, "");
        }
        try {
            Path csl = gameDir.resolve("CustomSkinLoader").resolve("LocalSkin");
            Files.createDirectories(csl.resolve("skins"));
            Files.createDirectories(csl.resolve("capes"));
            if (hasSkin()) {
                Files.copy(globalSkin, csl.resolve("skins").resolve(safeName + ".png"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            if (hasCape()) {
                Files.copy(globalCape, csl.resolve("capes").resolve(safeName + ".png"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            return new ApplyResult(Status.APPLIED, csl.toString());
        } catch (IOException e) {
            SafeLog.warn("写入 CustomSkinLoader 失败");
            return new ApplyResult(Status.FAILED, "");
        }
    }

    private static String sanitize(String name) {
        if (name == null) {
            return "";
        }
        return name.replaceAll("[^A-Za-z0-9_]", "");
    }
}
