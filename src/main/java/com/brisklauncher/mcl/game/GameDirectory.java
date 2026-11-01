package com.brisklauncher.mcl.game;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 大版本文件夹 = 一个 Minecraft 游戏根目录（官方 .minecraft、HMCL 多版本目录、PCL 版本隔离目录都算）。
 * 内部结构：{@code <path>/versions/<id>/<id>.json}。
 */
public final class GameDirectory {

    private final Path path;
    private String displayName;

    public GameDirectory(Path selectedPath, String displayName) {
        Path normalized = selectedPath.toAbsolutePath().normalize();

        // 用户可能直接选了 versions 目录，这里自动上提一级
        if ("versions".equals(String.valueOf(normalized.getFileName()))
                && normalized.getParent() != null
                && Files.isDirectory(normalized.getParent())) {
            this.path = normalized.getParent();
        } else {
            this.path = normalized;
        }

        this.displayName = displayName == null || displayName.isBlank()
                ? folderName(this.path)
                : displayName;
    }

    public Path path()              { return path; }
    public String displayName()     { return displayName; }
    public void displayName(String n) {
        if (n != null && !n.isBlank()) {
            this.displayName = n;
        }
    }

    public Path versionsDir()      { return path.resolve("versions"); }
    public Path modsDir()          { return path.resolve("mods"); }
    public Path resourcePacksDir() { return path.resolve("resourcepacks"); }
    public Path shaderPacksDir()   { return path.resolve("shaderpacks"); }
    public Path savesDir()         { return path.resolve("saves"); }
    public Path configDir()        { return path.resolve("config"); }
    public Path logsDir()          { return path.resolve("logs"); }
    public Path cslLocalSkinDir()  { return path.resolve("CustomSkinLoader").resolve("LocalSkin"); }
    public Path librariesDir()     { return path.resolve("libraries"); }
    public Path assetsDir()        { return path.resolve("assets"); }

    public boolean isValid() {
        return Files.isDirectory(versionsDir());
    }

    public boolean looksLikeMinecraftHome() {
        return Files.isDirectory(versionsDir())
                || Files.isRegularFile(path.resolve("launcher_profiles.json"))
                || Files.isDirectory(path.resolve("libraries"));
    }

    private static String folderName(Path path) {
        Path name = path.getFileName();
        if (name != null) {
            return name.toString();
        }
        return path.toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof GameDirectory root && path.equals(root.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path);
    }

    @Override
    public String toString() {
        return displayName + " (" + path + ")";
    }
}
