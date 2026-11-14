package com.brisklauncher.mcl.util;

import com.brisklauncher.mcl.AppPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * 记忆体优化（第 189 轮：参数对齐 PCL2 与 HMCL 的默认值，不再自己发明）。
 *
 * <p>两家启动器给游戏加的 GC/诊断参数几乎是同一组（PCL「优化的 G1GC」= {@code ModLaunch.vb} 的
 * SetupType 4；HMCL = {@code DefaultLauncher} 的 {@code addUnstableDefault}）：
 *
 * <ul>
 *   <li>{@code +UseG1GC} + {@code G1NewSizePercent=20} + {@code G1ReservePercent=20}——
 *       Minecraft 分配率高，年轻代给足才不会一秒 GC 好几次，预留 to-space 避免疏散失败；</li>
 *   <li>{@code MaxGCPauseMillis=50}——**客户端**要的是不掉帧，两家都用 50（Aikar 的 200 是伺服器取向）；</li>
 *   <li>{@code G1HeapRegionSize=32M}——两家都用 32M；区域一半大小以上的分配算 Humongous，
 *       直接进老年代且难回收，MC 的大纹理/模型经常踩到；</li>
 *   <li>{@code -OmitStackTraceInFastThrow}——关掉「JIT 省事」的行为，
 *       崩溃报告里才看得到完整堆栈（本项目做崩溃分析，这条必须有，两家都开）；</li>
 *   <li>{@code -DontCompileHugeMethods}——大方法照编，模组环境少踩 JIT 坑（HMCL）；</li>
 *   <li>{@code +PerfDisableSharedMem}——GC 不写 perf 共享映射，少一点磁盘 IO 抖动（PCL 优化档）；</li>
 *   <li>{@code -Dlog4j2.formatMsgNoLookups=true}（PCL 无条件加）、
 *       {@code -Djdk.lang.Process.allowAmbiguousCommands=True}（PCL）——无害的兼容旗标。</li>
 * </ul>
 *
 * <p>**刻意不加**：{@code -XX:+AlwaysPreTouch}、{@code -Xms = -Xmx}。两者都会让提交量/工作集
 * 立刻顶到堆上限，与本项目「启动后内存要低」的取向相反；堆上限仍由使用者设定的记忆体决定
 * （本类不动 {@code -Xmx}）。
 *
 * <p>**整合包档**（{@code mods} 里的 jar 数 >= {@link #MODPACK_THRESHOLD}）再加 PCL「优化 G1GC」里
 * 那两条堆空闲比例：加载期的峰值过后，G1 会把用不到的堆还回去，进程工作集才不会一直钉在满堆。
 *
 * <p>开关存在 {@code <data>/tuning.properties}，不写进 {@code launcher.json}，
 * 避免每次增减设定栏位都要改 record 与序列化。Forge/NeoForge 的 {@code -Dfml.*} 由
 * {@code GameLauncher} 按加载器加（那两条跟 GC 无关）。
 */
public final class MemoryTuning {

    private static final String FILE_NAME = "tuning.properties";
    private static final String KEY = "optimizeMemory";
    /** mods 目录里 jar 数达到这个量级，才认为是「大型整合包」。 */
    private static final int MODPACK_THRESHOLD = 50;

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
     * @param args     现有 JVM 参数（就地追加）
     * @param memoryMb 分配给游戏的记忆体，只用来挑区域大小，**不改堆上限**
     * @param modCount {@code mods} 目录里的 jar 数（决定是否套用整合包那两条空闲比例）
     * @return 本次真正追加的旗标（写进会话日志，方便事后核对；没有追加就是空清单）
     */
    public static List<String> apply(List<String> args, int memoryMb, int modCount) {
        List<String> added = new ArrayList<>();
        if (!enabled() || args == null) {
            return added;
        }
        // 实验性旗标要排在前面：JDK 会按顺序解析（G1NewSizePercent 在旧 JDK 上也是实验性的）。
        add(args, added, "-XX:+UnlockExperimentalVMOptions");
        add(args, added, "-XX:+UseG1GC");
        add(args, added, "-XX:G1NewSizePercent=20");
        add(args, added, "-XX:G1ReservePercent=20");
        add(args, added, "-XX:MaxGCPauseMillis=50");
        add(args, added, "-XX:G1HeapRegionSize=" + regionSize(memoryMb));
        add(args, added, "-XX:-OmitStackTraceInFastThrow");
        add(args, added, "-XX:-DontCompileHugeMethods");
        add(args, added, "-XX:+PerfDisableSharedMem");
        add(args, added, "-Dlog4j2.formatMsgNoLookups=true");
        add(args, added, "-Djdk.lang.Process.allowAmbiguousCommands=True");
        if (modCount >= MODPACK_THRESHOLD) {
            // PCL「优化的 G1GC」在 Java 12+ 会加这两条：加载峰值过后把空闲堆还回去。
            add(args, added, "-XX:MinHeapFreeRatio=25");
            add(args, added, "-XX:MaxHeapFreeRatio=40");
        }
        report(added, memoryMb, modCount);
        return added;
    }

    /**
     * 区域大小：PCL / HMCL 都固定 32M，但堆很小时 32M 会让区域数少得可怜
     * （512M 堆只有 16 个区域，年轻代连一次疏散都勉强），所以按堆大小回退。
     */
    private static String regionSize(int memoryMb) {
        if (memoryMb >= 8192) {
            return "32M";
        }
        if (memoryMb >= 2048) {
            return "16M";
        }
        return "8M";
    }

    private static void add(List<String> args, List<String> added, String flag) {
        if (has(args, flag)) {
            return;
        }
        args.add(flag);
        added.add(flag);
    }

    private static void report(List<String> added, int memoryMb, int modCount) {
        if (added.isEmpty()) {
            com.brisklauncher.mcl.security.SafeLog.info("记忆体优化未追加任何参数（可能已被自订参数覆盖）");
            return;
        }
        com.brisklauncher.mcl.security.SafeLog.info("已套用记忆体优化参数（" + memoryMb + "MB · mods="
                + modCount + " · " + (modCount >= MODPACK_THRESHOLD ? "整合包组" : "基础组") + "）："
                + String.join(" ", added));
    }

    private static boolean has(List<String> args, String flag) {
        String key = flagKey(flag);
        for (String arg : args) {
            if (arg != null && flagKey(arg).equals(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取旗标名：{@code -XX:Foo=1} / {@code -XX:Foo} / {@code -XX:+Foo} / {@code -XX:-Foo}
     * 都归一到 {@code -XX:Foo}。
     *
     * <p>必须按名字比对而不是整串 startsWith：使用者写了 {@code -XX:MaxGCPauseMillis=200}
     * 时，用 startsWith 比对 {@code =50} 会认为「没有」→ 追加自己的 50 把使用者的值盖掉。
     */
    private static String flagKey(String flag) {
        int eq = flag.indexOf('=');
        String key = eq < 0 ? flag : flag.substring(0, eq);
        if (key.startsWith("-XX:+") || key.startsWith("-XX:-")) {
            key = "-XX:" + key.substring(5);
        }
        return key;
    }
}
