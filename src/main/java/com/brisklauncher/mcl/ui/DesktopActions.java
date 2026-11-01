package com.brisklauncher.mcl.ui;

import com.brisklauncher.mcl.security.SafeLog;

import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 打开网页 / 文件 / 文件夹。
 *
 * <p>只依赖 {@code java.awt.Desktop} 在部分环境（无桌面、被策略禁用、jlink 精简运行时）
 * 会静默失败，因此这里逐级回退到 {@code rundll32} 与 {@code cmd start}。
 * 这样“打开微软登录页”“打开 Terracotta 主页”在各个环境下都能用。
 */
public final class DesktopActions {

    private DesktopActions() {
    }

    public static boolean openUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        // 安全审计 L4：只允许 http/https（防 file: / smb: 泄露 NTLM、防 cmd 参数注入）
        String scheme = "";
        try {
            java.net.URI parsed = java.net.URI.create(url.trim());
            scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (parsed.getHost() == null) {
                return false;
            }
        } catch (IllegalArgumentException bad) {
            return false;
        }
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            SafeLog.warn("已拒绝打开非 http(s) 链接");
            return false;
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(java.net.URI.create(url));
                return true;
            }
        } catch (Exception e) {
            SafeLog.warn("Desktop.browse 不可用，尝试系统命令");
        }
        if (run("rundll32", "url.dll,FileProtocolHandler", url)) {
            return true;
        }
        // 不再用 cmd /c start（Java 不转义 &，会被拆成两条命令）
        return false;
    }

    public static boolean openFolder(Path path) {
        if (path == null) {
            return false;
        }
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path);
            }
        } catch (Exception e) {
            SafeLog.warn("无法创建目录：" + path);
            return false;
        }
        // 先走 ShellExecute（由签名 explorer 执行），再尝试 Desktop/explorer/cmd；
        // 这样即使第三方安全软件拦截“未签名 exe 启动子进程”，也还有机会打开目录。
        if (shellExecute(path)) {
            return true;
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(path.toFile());
                return true;
            }
        } catch (Exception e) {
            SafeLog.warn("Desktop.open 不可用，尝试 explorer");
        }
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                && run("explorer.exe", path.toAbsolutePath().toString())) {
            return true;
        }
        // 已移除 cmd /c start 回退（L4）：路径含 & 时会被当成第二条命令
        return false;
    }

    public static boolean openFile(Path path) {
        if (path == null || !Files.exists(path)) {
            return false;
        }
        if (shellExecute(path)) {
            return true;
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(path.toFile());
                return true;
            }
        } catch (Exception e) {
            SafeLog.warn("Desktop.open 不可用，尝试系统命令");
        }
        if (run("rundll32", "url.dll,FileProtocolHandler", path.toAbsolutePath().toString())) {
            return true;
        }
        return false;
    }

    private static boolean shellExecute(Path path) {
        try {
            // ShellExecute 的返回值 <= 32 表示失败；旧实现不看返回值，
            // 一旦被安全软件拦截就会「以为成功」而不再回退，表现为点了没反应。
            com.sun.jna.platform.win32.WinDef.INT_PTR result =
                    com.sun.jna.platform.win32.Shell32.INSTANCE.ShellExecute(null, "open",
                            path.toAbsolutePath().toString(), null, null, 1);
            long code = result == null ? 0L : result.longValue();
            if (code > 32L) {
                return true;
            }
            SafeLog.warn("ShellExecute 返回 " + code + "，改用其它方式打开");
            return false;
        } catch (Throwable error) {
            SafeLog.warn("ShellExecute 失败：" + error.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean run(String... command) {
        try {
            new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return true;
        } catch (Exception e) {
            SafeLog.warn("无法打开系统程序：" + String.join(" ", command));
            return false;
        }
    }
}
