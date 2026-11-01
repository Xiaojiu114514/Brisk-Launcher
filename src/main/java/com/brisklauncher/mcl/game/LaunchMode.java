package com.brisklauncher.mcl.game;

import java.nio.file.Path;

/**
 * 启动方式。Store 应用优先用 {@code shell:AppsFolder} 激活，
 * 不去直接调用 WindowsApps 下的 exe（开发方案 4.11.2）。
 */
public enum LaunchMode {
    JVM,
    EXE,
    SHELL_APPS_FOLDER,
    URI
}
