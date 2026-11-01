package com.brisklauncher.mcl.game.launch;

/**
 * 启动阶段。只保留进度权重，阶段文案由 StartupOverlay 通过 i18n 取得。
 */
public enum LaunchProgress {

    PREPARING(0.08),
    CHECKING_FILES(0.18),
    INSTALLING(0.45),
    INSTALLING_JAVA(0.55),
    RESOLVING_JAVA(0.62),
    BUILDING_ARGS(0.75),
    LAUNCHING(0.85),
    RUNNING(1.00);

    private final double weight;

    LaunchProgress(double weight) {
        this.weight = weight;
    }

    public double weight() {
        return weight;
    }
}
