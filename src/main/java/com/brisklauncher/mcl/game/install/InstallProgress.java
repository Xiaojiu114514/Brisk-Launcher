package com.brisklauncher.mcl.game.install;

/** 安装进度：阶段 + 百分比 + 细节文本。 */
public record InstallProgress(Stage stage, double progress, String detail) {

    public enum Stage {
        RESOLVING,
        VERSION_JSON,
        CLIENT_JAR,
        LIBRARIES,
        ASSETS,
        LOGGING,
        LOADER,
        JAVA,
        FINISHING,
        DONE,
        /** 自动更新：下载新版本更新包。 */
        UPDATE_DOWNLOAD,
        /** 自动更新：解压缩并检查更新包。 */
        UPDATE_EXTRACT
    }

    public static InstallProgress of(Stage stage) {
        return new InstallProgress(stage, 0, "");
    }

    public int percent() {
        return (int) Math.round(Math.max(0, Math.min(1, progress)) * 100);
    }
}
