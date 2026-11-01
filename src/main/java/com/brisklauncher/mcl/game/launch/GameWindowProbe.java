package com.brisklauncher.mcl.game.launch;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.ptr.IntByReference;
import com.brisklauncher.mcl.security.NativeSetup;

import java.util.Locale;

/**
 * 判断某个进程是否已经创建了可见窗口。
 *
 * <p>用 User32 枚举窗口比“固定等 3 秒”准确：游戏渲染出窗口后再关闭加载遮罩，
 * 玩家不会看到“启动成功”但窗口还没出来的空档。
 */
public final class GameWindowProbe {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static volatile boolean jnaReady = WINDOWS;

    private GameWindowProbe() {
    }

    /**
     * 是否有「标题包含任一关键字」的可见大窗口（用于 Store 版游戏：进程是 explorer，
     * 拿不到 pid，只能按窗口标题判断）。
     */
    public static boolean hasVisibleWindowTitled(String... keywords) {
        if (!WINDOWS || !jnaReady || keywords == null || keywords.length == 0) {
            return false;
        }
        try {
            NativeSetup.prepare();
            final boolean[] found = {false};
            User32.INSTANCE.EnumWindows((WinDef.HWND hwnd, Pointer data) -> {
                if (found[0]) {
                    return false;
                }
                if (!User32.INSTANCE.IsWindowVisible(hwnd)) {
                    return true;
                }
                int length = User32.INSTANCE.GetWindowTextLength(hwnd);
                if (length <= 0) {
                    return true;
                }
                char[] buffer = new char[length + 1];
                User32.INSTANCE.GetWindowText(hwnd, buffer, buffer.length);
                String title = new String(buffer).toLowerCase(Locale.ROOT);
                boolean match = false;
                for (String keyword : keywords) {
                    if (keyword != null && !keyword.isBlank()
                            && title.contains(keyword.toLowerCase(Locale.ROOT))) {
                        match = true;
                        break;
                    }
                }
                if (!match) {
                    return true;
                }
                WinDef.RECT rect = new WinDef.RECT();
                if (!User32.INSTANCE.GetWindowRect(hwnd, rect)) {
                    return true;
                }
                if (rect.right - rect.left < 320 || rect.bottom - rect.top < 240) {
                    return true;
                }
                found[0] = true;
                return false;
            }, null);
            return found[0];
        } catch (Throwable error) {
            jnaReady = false;
            return false;
        }
    }
    public static boolean hasVisibleWindow(long pid) {
        if (!WINDOWS || !jnaReady) {
            return false;
        }
        try {
            NativeSetup.prepare();
            final boolean[] found = {false};
            User32.INSTANCE.EnumWindows((WinDef.HWND hwnd, Pointer data) -> {
                if (found[0]) {
                    return false;
                }
                IntByReference windowPid = new IntByReference();
                User32.INSTANCE.GetWindowThreadProcessId(hwnd, windowPid);
                if (windowPid.getValue() != (int) pid) {
                    return true;
                }
                if (!User32.INSTANCE.IsWindowVisible(hwnd)) {
                    return true;
                }
                // 排除没有标题的隐藏辅助窗口
                if (User32.INSTANCE.GetWindowTextLength(hwnd) <= 0) {
                    return true;
                }
                // 排除启动瞬间的小工具窗（splash/托盘图标）：真正的游戏窗口至少几百像素
                WinDef.RECT rect = new WinDef.RECT();
                if (!User32.INSTANCE.GetWindowRect(hwnd, rect)) {
                    return true;
                }
                if (rect.right - rect.left < 320 || rect.bottom - rect.top < 240) {
                    return true;
                }
                found[0] = true;
                return false;
            }, null);
            return found[0];
        } catch (Throwable error) {
            // JNA 不可用时不阻塞启动，退回日志启发式
            jnaReady = false;
            return false;
        }
    }
}
