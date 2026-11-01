package com.brisklauncher.mcl.game;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 一个小版本 = {@code <root>/versions/<id>/}。
 *
 * <p>它同时代表“实例”：游戏目录、mods、存档等根路径都由 {@link #gameDir()} 决定，
 * 开启版本隔离时指向 {@code versions/<id>/}，否则指向大版本文件夹根目录。
 * 这样同一套逻辑可以兼容官方启动器目录、HMCL 目录和 PCL 的版本隔离目录。
 */
public final class GameInstance {

    private final GameDirectory root;
    private String id;
    // 重命名时资料夹会换路径，所以要能更新（之前是 final：改名后 versionDir 仍指向旧资料夹，
    // 于是「启用版本隔离」「打开游戏目录」全都在旧路径上操作）
    private Path versionDir;
    private String mcVersion;
    private LoaderInfo loader;
    private String type = "unknown";
    private String releaseTime = "";
    private int javaMajor = -1;
    private String mainClass = "";
    private boolean isolated;

    public GameInstance(GameDirectory root, String id, Path versionDir) {
        this.root = root;
        this.id = id;
        this.versionDir = versionDir;
        this.mcVersion = id;
        this.isolated = Files.isDirectory(versionDir.resolve("mods"))
                || Files.isDirectory(versionDir.resolve("config"))
                || Files.isDirectory(versionDir.resolve("saves"));
    }

    public GameDirectory root()      { return root; }
    public String id()             { return id; }

    /** 重命名实例时更新 id（目录与 json 由 GameRepository 负责搬移）。 */
    public void id(String value)   { this.id = value == null ? this.id : value; }
    public Path versionDir()       { return versionDir; }

    /** 重命名後把版本資料夾指向新路徑（與 {@link #id(String)} 一起用）。 */
    public void versionDir(Path value) { this.versionDir = value == null ? this.versionDir : value; }
    public Path jsonFile()         { return versionDir.resolve(id + ".json"); }
    public Path jarFile()          { return versionDir.resolve(id + ".jar"); }
    public Path nativesDir()       { return versionDir.resolve("natives"); }

    public String mcVersion()      { return mcVersion; }
    public void mcVersion(String v){ this.mcVersion = v; }
    public LoaderInfo loader()     { return loader; }
    public void loader(LoaderInfo l){ this.loader = l; }
    public String type()           { return type; }
    public void type(String v)     { this.type = v; }
    public String releaseTime()    { return releaseTime; }
    public void releaseTime(String v) { this.releaseTime = v == null ? "" : v; }
    public int javaMajor()         { return javaMajor; }
    public void javaMajor(int v)   { this.javaMajor = v; }
    public String mainClass()      { return mainClass; }
    public void mainClass(String v){ this.mainClass = v == null ? "" : v; }
    public boolean isolated()      { return isolated; }
    public void isolated(boolean v) { this.isolated = v; }

    /** 游戏目录（模组、存档、日志、CSL 的唯一根）。 */
    public Path gameDir() {
        // 版本隔离：只要版本资料夹内有实例资料（mods / config / saves）就强制隔离，
        // 不要只信 InstanceSettings（旧的设定档可能还是 isolated=false，会让档案跑到游戏根目录）。
        if (isolated || looksIsolated()) {
            return versionDir;
        }
        return root.path();
    }

    /** 版本資料夾內是否已有實例資料（用來判定隔離，與設定檔無關）。 */
    public boolean looksIsolated() {
        return Files.isDirectory(versionDir.resolve("mods"))
                || Files.isDirectory(versionDir.resolve("config"))
                || Files.isDirectory(versionDir.resolve("saves"));
    }

    public boolean hasClientJar() {
        return Files.isRegularFile(jarFile());
    }

    public String loaderDisplay() {
        if (loader == null || loader.type() == LoaderInfo.LoaderType.VANILLA) {
            return "Vanilla";
        }
        return loader.type().displayName() + " " + loader.version();
    }

    /** 卡片上的副标题：类型 · 加载器 · Java 主版本。 */
    public String subtitle() {
        StringBuilder sb = new StringBuilder();
        sb.append(mcVersion);
        if (loader != null && loader.type() != LoaderInfo.LoaderType.VANILLA) {
            sb.append(" · ").append(loaderDisplay());
        } else {
            sb.append(" · Vanilla");
        }
        if (javaMajor > 0) {
            sb.append(" · Java ").append(javaMajor);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return id;
    }
}
