package com.brisklauncher.mcl.java;

import java.nio.file.Path;
import java.util.Locale;

/**
 * 单个 Java 运行时描述。
 *
 * @param bits 32 或 64；32 位 JVM 最大堆只有约 1.5G，选择 Java 时优先 64 位
 */
public record JdkInfo(Path home, String version, int majorVersion, boolean managed, int bits) {

    public JdkInfo(Path home, String version, int majorVersion, boolean managed) {
        this(home, version, majorVersion, managed, 64);
    }

    public Path javaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return home.resolve("bin").resolve(windows ? "java.exe" : "java");
    }

    public boolean is64Bit() {
        return bits == 64;
    }

    public String display() {
        String base = "Java " + majorVersion + " · " + version + " · " + bits + " 位";
        return managed ? base + "（启动器托管）" : base;
    }

    @Override
    public String toString() {
        return display();
    }
}
