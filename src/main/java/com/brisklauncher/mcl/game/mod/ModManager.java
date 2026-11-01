package com.brisklauncher.mcl.game.mod;

import com.brisklauncher.mcl.game.GameInstance;
import com.brisklauncher.mcl.security.SafeLog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 实例内容管理：Mod / 资源包 / 光影 / 存档。
 *
 * <p>与 PCL2 / HMCL 一致：禁用通过改后缀实现（{@code .jar.disabled}），
 * 这样不移动文件、不破坏用户自己的目录结构，随时可以恢复。
 */
public final class ModManager {

    public enum Kind {
        MODS("mods", new String[]{".jar", ".zip"}, ".jar.disabled"),
        RESOURCE_PACKS("resourcepacks", new String[]{".zip"}, ".zip.disabled"),
        SHADER_PACKS("shaderpacks", new String[]{".zip"}, ".zip.disabled"),
        SAVES("saves", new String[]{}, ""),
        CONFIG("config", new String[]{}, "");

        private final String folder;
        private final String[] extensions;
        private final String disabledSuffix;

        Kind(String folder, String[] extensions, String disabledSuffix) {
            this.folder = folder;
            this.extensions = extensions;
            this.disabledSuffix = disabledSuffix;
        }

        public String folder() {
            return folder;
        }

        public String disabledSuffix() {
            return disabledSuffix;
        }
    }

    public record ContentFile(String name, Path path, long size, boolean enabled, Kind kind) {
        public String sizeText() {
            if (size < 1024) {
                return size + " B";
            }
            if (size < 1024 * 1024) {
                return String.format(Locale.ROOT, "%.1f KB", size / 1024.0);
            }
            return String.format(Locale.ROOT, "%.1f MB", size / (1024.0 * 1024.0));
        }
    }

    public Path dir(GameInstance entry, Kind kind) {
        return switch (kind) {
            case MODS -> entry.gameDir().resolve("mods");
            case RESOURCE_PACKS -> entry.gameDir().resolve("resourcepacks");
            case SHADER_PACKS -> entry.gameDir().resolve("shaderpacks");
            case SAVES -> entry.gameDir().resolve("saves");
            case CONFIG -> entry.gameDir().resolve("config");
        };
    }

    public List<ContentFile> list(GameInstance entry, Kind kind) {
        Path dir = dir(entry, kind);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<ContentFile> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(file -> Files.isDirectory(file) || Files.isRegularFile(file))
                    .filter(file -> Files.isDirectory(file) || belongs(file, kind))
                    .forEach(file -> {
                        try {
                            String name = file.getFileName().toString();
                            boolean enabled = !isDisabled(name);
                            long size = Files.isDirectory(file) ? 0L : Files.size(file);
                            result.add(new ContentFile(name, file, size, enabled, kind));
                        } catch (IOException ignored) {
                            // 文件读不到就不列出来
                        }
                    });
        } catch (IOException e) {
            SafeLog.warn("读取内容目录失败：" + dir.getFileName());
        }
        result.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return result;
    }

    private static boolean belongs(Path file, Kind kind) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (kind.extensions.length == 0) {
            return true;
        }
        for (String extension : kind.extensions) {
            if (name.endsWith(extension) || name.endsWith(extension + ".disabled")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDisabled(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".disabled");
    }

    /** 切换启用/禁用（改后缀），返回切换后的文件。 */
    public Path toggle(ContentFile file) {
        Path source = file.path();
        String name = source.getFileName().toString();
        Path target;
        try {
            if (file.enabled()) {
                target = source.resolveSibling(name + ".disabled");
            } else if (name.endsWith(".disabled")) {
                target = source.resolveSibling(name.substring(0, name.length() - ".disabled".length()));
            } else {
                return source;
            }
            if (Files.exists(target)) {
                throw new IllegalStateException("目标文件已存在：" + target.getFileName());
            }
            Files.move(source, target);
            return target;
        } catch (IOException e) {
            throw new IllegalStateException("切换启用状态失败：" + e.getMessage(), e);
        }
    }

    /** 删除内容文件（送回收站，误删还能捞回来）。 */
    public void delete(Path file) {
        if (!com.brisklauncher.mcl.util.RecycleBin.delete(file)) {
            throw new IllegalStateException("删除失败：" + file.getFileName()
                    + "（文件可能正被游戏占用）");
        }
    }

    /** 打开内容目录（不存在就创建）。 */
    public Path ensureDir(GameInstance entry, Kind kind) {
        Path dir = dir(entry, kind);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建目录：" + dir);
        }
        return dir;
    }
}
