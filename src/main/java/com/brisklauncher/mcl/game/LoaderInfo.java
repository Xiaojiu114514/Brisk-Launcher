package com.brisklauncher.mcl.game;

import java.util.Locale;

/**
 * 加载器信息，{@code VANILLA} 表示原版。
 * 版本号来自版本 JSON 里的 libraries（例如 {@code net.fabricmc:fabric-loader:0.16.5}）。
 */
public record LoaderInfo(LoaderType type, String version) {

    public enum LoaderType {
        VANILLA("Vanilla"),
        FABRIC("Fabric"),
        QUILT("Quilt"),
        FORGE("Forge"),
        NEOFORGE("NeoForge"),
        OPTIFINE("OptiFine");

        private final String display;

        LoaderType(String display) {
            this.display = display;
        }

        public String displayName() {
            return display;
        }

        /** 加载器偏好使用的 Java 主版本（仅在版本 JSON 没写 javaVersion 时兜底）。 */
        public int preferredJava(String mcVersion) {
            return switch (this) {
                case FABRIC, QUILT, FORGE, NEOFORGE -> JavaVersionMapper.forMinecraft(mcVersion);
                case OPTIFINE -> JavaVersionMapper.forMinecraft(mcVersion);
                case VANILLA -> JavaVersionMapper.forMinecraft(mcVersion);
            };
        }
    }

    public static LoaderInfo vanilla(String mcVersion) {
        return new LoaderInfo(LoaderType.VANILLA, mcVersion);
    }

    public String id() {
        return type.name().toLowerCase(Locale.ROOT) + "-" + version;
    }

    /** 常见互斥组合，UI 置灰依据。 */
    public boolean conflictsWith(LoaderInfo other) {
        if (other == null || other.type == LoaderType.VANILLA) {
            return false;
        }
        boolean forgeFamily = type == LoaderType.FORGE || type == LoaderType.NEOFORGE;
        boolean otherForgeFamily = other.type == LoaderType.FORGE || other.type == LoaderType.NEOFORGE;
        return forgeFamily && otherForgeFamily && type != other.type;
    }

    public boolean isVanilla() {
        return type == LoaderType.VANILLA;
    }
}
