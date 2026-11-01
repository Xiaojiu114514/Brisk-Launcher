package com.brisklauncher.mcl.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 私密目录/文件权限（安全审计 H-02 / M-02）。
 *
 * <p>旧实现直接用 JDK 的 {@code AclFileAttributeView.setAcl}：它**只写 DACL、不禁用继承**，
 * 父目录可继承的 ACE（例如 {@code BUILTIN\Users} 的读权限）下次继承传播时还会合回来，
 * 而且从不校验结果 —— 等于「自以为加固了，其实没有」。
 *
 * <p>现在改为调用系统 {@code icacls}：{@code /inheritance:r} 去掉继承，
 * {@code /grant:r "<user>:(OI)(CI)F"} 只保留当前用户，再用 {@code icacls} 回读一次确认
 * 不存在其它 ALLOW 主体；结果以 {@code boolean} 返回，由 {@code AppPaths} 决定是否提示用户。
 */
public final class FilePerms {

    /**
     * 已经处理过的路径（同一支进程内不再重复调用 icacls）。
     *
     * <p>为什么要缓存：{@code icacls} 每次都要 spawn 一个进程（100~300ms）。
     * 之前每次读写密钥/确认记录都会收紧一次权限，启动时能攒出 10 次左右 → 启动慢了好几秒。
     */
    private static final java.util.Set<String> DONE =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private FilePerms() {
    }

    /** 尽力把 {@code dir} 设成「仅当前用户」；返回是否**确认**成功。 */
    public static boolean ensurePrivate(Path dir) {
        if (dir == null) {
            return false;
        }
        try {
            Files.createDirectories(dir);
        } catch (Exception error) {
            return false;
        }
        return lockDown(dir, true);
    }

    /** 单个文件的权限收紧（密钥、确认记录等）。 */
    public static boolean ensurePrivateFile(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return false;
        }
        return lockDown(file, false);
    }

    private static boolean lockDown(Path target, boolean directory) {
        if (!isWindows()) {
            return true;
        }
        Path absolute = target.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute)) {
            SafeLog.warn("跳过符号链接的权限设置：" + absolute.getFileName());
            return false;
        }
        String key = (directory ? "d:" : "f:") + absolute;
        if (DONE.contains(key)) {
            return true;
        }
        String user = System.getProperty("user.name", "");
        if (user.isBlank()) {
            return false;
        }
        // 安全审计 v2 A-08：`/inheritance:r` 会把 SYSTEM 与 Administrators 也摘掉，
        // 结果是杀软/EDR、域管理员、备份工具都失去访问权（对安全是反效果）。
        // 这里用**知名 SID**（*S-1-5-18 / *S-1-5-32-544）而不是本地化组名，
        // 否则德语/法语等系统的组名不是 "Administrators"，授权会静默失败。
        String perms = directory ? "(OI)(CI)F" : "F";
        List<String> grant = new ArrayList<>();
        grant.add("/inheritance:r");
        grant.add("/grant:r");
        grant.add(user + ":" + perms);
        grant.add("/grant:r");
        grant.add("*S-1-5-18:" + perms);
        grant.add("/grant:r");
        grant.add("*S-1-5-32-544:" + perms);
        if (!runIcacls(absolute, grant)) {
            return false;
        }
        // 回读校验只做一次（下次同路径直接用缓存）。
        // 授权 + 校验各 spawn 一个 icacls；合并后每次新路径只花两次进程的时间。
        boolean ok = verify(absolute);
        if (ok) {
            DONE.add(key);
        }
        return ok;
    }

    /**
     * 用**绝对路径**调用 icacls（安全审计 v2 A-08）。
     *
     * <p>{@code ProcessBuilder("icacls", ...)} 会按「应用目录 → 当前目录 → System32 → PATH」搜索，
     * 而启动器的 CWD 就是安装目录 —— 那里放一个同名 exe 就能劫持。这里固定用 System32 下的。
     */
    private static String icaclsPath() {
        String root = System.getenv("SystemRoot");
        if (root == null || root.isBlank()) {
            root = "C:\\Windows";
        }
        return root + "\\System32\\icacls.exe";
    }

    private static boolean runIcacls(Path target, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add(icaclsPath());
        command.add(target.toString());
        command.addAll(args);
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output;
            try (var stream = process.getInputStream()) {
                output = new String(stream.readAllBytes(), java.nio.charset.Charset.defaultCharset());
            }
            int exit = process.waitFor();
            if (exit != 0) {
                SafeLog.warn("icacls 失败（" + exit + "）：" + oneLine(output)
                        + " → " + target.getFileName());
                return false;
            }
            return true;
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            SafeLog.warn("icacls 不可用：" + error.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 回读 DACL，确认没有「任何本地登录用户」都能访问的主体。
     *
     * <p>安全审计 v2 A-08：白名单要放 SYSTEM / Administrators（我们自己刚加的），
     * 风险名单除了 Everyone / Users / Authenticated Users，还要加上
     * {@code INTERACTIVE} / {@code CONSOLE LOGON} / {@code CREATOR OWNER}
     * —— 它们同样代表「任何本地登录用户」。
     */
    private static boolean verify(Path target) {
        List<String> command = List.of(icaclsPath(), target.toString());
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output;
            try (var stream = process.getInputStream()) {
                output = new String(stream.readAllBytes(), java.nio.charset.Charset.defaultCharset());
            }
            process.waitFor();
            String lower = output.toLowerCase(Locale.ROOT);
            for (String risky : new String[]{"everyone", "builtin\\users", "authenticated users",
                    "已验证的用户", "interactive", "console logon", "creator owner",
                    // 知名 SID 的文字形式（某些系统用 SID 显示）
                    "s-1-1-0", "s-1-5-11", "s-1-5-4", "s-1-5-12", "s-1-3-0"}) {
                if (lower.contains(risky)) {
                    SafeLog.warn("权限回读发现公共主体（" + risky + "）：" + target.getFileName());
                    return false;
                }
            }
            return true;
        } catch (Exception error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private static String oneLine(String text) {
        String value = text == null ? "" : text.replace('\r', ' ').replace('\n', ' ').trim();
        return value.length() > 160 ? value.substring(0, 160) + "…" : value;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
