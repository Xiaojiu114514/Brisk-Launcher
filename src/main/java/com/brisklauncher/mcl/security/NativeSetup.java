package com.brisklauncher.mcl.security;

import com.brisklauncher.mcl.AppPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JNA 原生库落地目录。
 *
 * <p>JNA 默认把 {@code jnidispatch.dll} 释放到 {@code java.io.tmpdir}，在某些环境下
 * （临时目录权限异常、被安全软件拦截、从 jlink 镜像启动）会报
 * {@code Failed to create temporary file ... 存取被拒}。
 * 这里在 JNA 加载前（{@code Native.<clinit>} 之前）把 {@code jna.tmpdir}
 * 指到启动器自己的可写目录，并验证可写。
 */
public final class NativeSetup {

    private static volatile boolean prepared;

    private NativeSetup() {
    }

    public static synchronized void prepare() {
        if (prepared) {
            return;
        }
        prepared = true;
        Path dir = chooseWritableDir();
        if (dir == null) {
            SafeLog.warn("没有找到可写的原生库目录，JNA 可能无法加载");
            return;
        }
        System.setProperty("jna.tmpdir", dir.toString());
        // 仅在系统临时目录确实不可写时才改写，避免影响其它组件
        String temp = System.getProperty("java.io.tmpdir", "");
        if (temp.isBlank() || !isWritable(Path.of(temp))) {
            System.setProperty("java.io.tmpdir", dir.toString());
        }
        dir.toFile().deleteOnExit();
    }

    public static Path nativeDir() {
        return AppPaths.dataDir().resolve("native");
    }

    private static Path chooseWritableDir() {
        Path[] candidates = {
                nativeDir(),
                AppPaths.launcherRoot().resolve("tmp"),
                localAppDataNative(),
                Path.of(System.getProperty("user.home", "."), ".mcl", "native"),
                Path.of(System.getProperty("java.io.tmpdir", "."), "mcl-native")
        };
        for (Path candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            try {
                Files.createDirectories(candidate);
                if (isWritable(candidate)) {
                    return candidate;
                }
            } catch (Exception ignored) {
                // 换下一个候选
            }
        }
        return null;
    }

    private static Path localAppDataNative() {
        String localAppData = System.getenv("LOCALAPPDATA");
        return localAppData == null || localAppData.isBlank()
                ? null : Path.of(localAppData, "BriskLauncher", "native");
    }

    private static boolean isWritable(Path dir) {
        try {
            Files.createDirectories(dir);
            Path probe = Files.createTempFile(dir, ".mcl-write-test", ".tmp");
            Files.deleteIfExists(probe);
            return true;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }
}
