package com.brisklauncher.mcl.util;

import com.brisklauncher.mcl.AppPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * 记忆体优化（参考 PCL 的做法，但只挑「安全、对所有版本都无害」的参数）。
 *
 * <p>PCL 预设会给游戏加一批 GC 与记忆体相关旗标，主要目的是：
 * 减少 GC 停顿（G1 的 pause 目标）、避免不必要的显式 GC、降低 humongous 物件造成的碎片。
 * 这里只加这几项，不动堆大小（堆大小仍由使用者设定的记忆体决定）。
 *
 * <p>开关存在 {@code <data>/tuning.properties}，不写进 {@code launcher.json}，
 * 避免每次增减设定栏位都要改 record 与序列化。
 */
public final class MemoryTuning {

    private static final String FILE_NAME = "tuning.properties";
    private static final String KEY = "optimizeMemory";

    private MemoryTuning() {
    }

    private static Path file() {
        return AppPaths.dataDir().resolve(FILE_NAME);
    }

    /** 是否啟用（預設開）。 */
    public static boolean enabled() {
        try {
            if (!Files.isRegularFile(file())) {
                return true;
            }
            Properties props = new Properties();
            try (var in = Files.newInputStream(file())) {
                props.load(in);
            }
            return Boolean.parseBoolean(props.getProperty(KEY, "true"));
        } catch (Exception ignored) {
            return true;
        }
    }

    public static void setEnabled(boolean value) {
        try {
            Files.createDirectories(file().getParent());
            Properties props = new Properties();
            if (Files.isRegularFile(file())) {
                try (var in = Files.newInputStream(file())) {
                    props.load(in);
                }
            }
            props.setProperty(KEY, String.valueOf(value));
            try (var out = Files.newOutputStream(file())) {
                props.store(out, "Brisk Launcher tuning");
            }
        } catch (Exception error) {
            com.brisklauncher.mcl.security.SafeLog.warn("写入 tuning.properties 失败："
                    + error.getClass().getSimpleName());
        }
    }

    /**
     * 把优化参数追加到 JVM 参数清单（已存在同名参数时略过，尊重使用者的自订）。
     *
     * @param args   现有 JVM 参数（就地追加）
     * @param memoryMb 分配给游戏的记忆体（只用来决定是否启用分代相关旗标）
     */
    public static void apply(List<String> args, int memoryMb) {
        if (!enabled() || args == null) {
            return;
        }
        // 大堆才值得调整 G1 的区域大小；小堆加了反而更慢
        if (memoryMb >= 4096 && !has(args, "-XX:G1HeapRegionSize")) {
            args.add("-XX:G1HeapRegionSize=16M");
        }
        if (!has(args, "-XX:MaxGCPauseMillis")) {
            args.add("-XX:MaxGCPauseMillis=50");
        }
        if (!has(args, "-XX:+UseG1GC")) {
            args.add("-XX:+UseG1GC");
        }
        if (!has(args, "-XX:+DisableExplicitGC")) {
            args.add("-XX:+DisableExplicitGC");
        }
        if (!has(args, "-XX:+ParallelRefProcEnabled")) {
            args.add("-XX:+ParallelRefProcEnabled");
        }
        com.brisklauncher.mcl.security.SafeLog.info("已套用记忆体优化参数（" + memoryMb + "MB）");
    }

    private static boolean has(List<String> args, String prefix) {
        for (String arg : args) {
            if (arg != null && arg.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
