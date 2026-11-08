package com.brisklauncher.mcl.security;

import com.brisklauncher.mcl.AppPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 临时目录守卫。
 *
 * <p>为什么要它：ImageIO（以及一部分桌面 API）会把缓存写到 {@code java.io.tmpdir}。
 * 如果 {@code %TEMP%} 不可写（受限账户、安全软件的受控文件夹访问、目录 ACL 被改过），
 * 就会抛 {@code AccessDeniedException: ...\Temp\imageio*.tmp}，而且只在 JVM 内部某处炸，
 * 玩家看到的是一句莫名其妙的错误。
 *
 * <p>做法和 HMCL 一样：**不依赖系统临时目录** —— 先用启动器自己的 {@code data/tmp}，
 * 系统临时目录不可写时就把 {@code java.io.tmpdir} 换过去。
 */
public final class TempGuard {

    private TempGuard() {
    }

    /** 在启动最早阶段调用一次；失败也只是降级，不抛例外。 */
    public static void ensureWritable() {
        String current = System.getProperty("java.io.tmpdir");
        if (isWritable(current)) {
            return;
        }
        try {
            Path fallback = AppPaths.dataDir().resolve("tmp");
            Files.createDirectories(fallback);
            if (isWritable(fallback.toString())) {
                System.setProperty("java.io.tmpdir", fallback.toString());
                SafeLog.warn("系统临时目录不可写（" + current + "），已改用 " + fallback);
            } else {
                SafeLog.warn("系统临时目录和 " + fallback + " 都不可写，图片缓存等功能可能异常");
            }
        } catch (Throwable error) {
            SafeLog.warn("切换临时目录失败：" + error.getClass().getSimpleName());
        }
    }

    private static boolean isWritable(String dir) {
        if (dir == null || dir.isBlank()) {
            return false;
        }
        try {
            Path test = Files.createTempFile(Paths.get(dir), "mcl-temp-", ".tmp");
            Files.deleteIfExists(test);
            return true;
        } catch (Throwable error) {
            return false;
        }
    }
}