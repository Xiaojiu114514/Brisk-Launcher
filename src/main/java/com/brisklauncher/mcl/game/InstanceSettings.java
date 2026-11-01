package com.brisklauncher.mcl.game;

import java.util.List;

/**
 * 实例级设置（跟某个版本走），与全局 {@code launcher.json} 分开保存。
 *
 * @param memoryMb  最大堆内存
 * @param javaPath  自定义 java.exe，空串表示自动
 * @param jvmArgs   额外 JVM 参数
 * @param isolated  版本隔离：游戏目录是否指向 {@code versions/<id>}
 * @param width      窗口宽度，0 表示交给游戏默认
 * @param height     窗口高度，0 表示交给游戏默认
 */
public record InstanceSettings(int memoryMb, String javaPath, List<String> jvmArgs,
                              boolean isolated, int width, int height) {

    public InstanceSettings {
        memoryMb = Math.max(512, memoryMb);
        javaPath = javaPath == null ? "" : javaPath.trim();
        jvmArgs = jvmArgs == null ? List.of() : List.copyOf(jvmArgs);
        width = Math.max(0, width);
        height = Math.max(0, height);
    }

    public static InstanceSettings defaults(int memoryMb, boolean isolated) {
        return new InstanceSettings(memoryMb, "", List.of(), isolated, 0, 0);
    }

    public boolean hasCustomJava() {
        return !javaPath.isBlank();
    }
}
