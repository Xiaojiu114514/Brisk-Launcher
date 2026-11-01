package com.brisklauncher.mcl.util;

import com.brisklauncher.mcl.security.SafeLog;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 把档案/资料夹丢到 Windows 回收站，而不是直接删掉。
 *
 * <p>启动器里「删除实例」「删除 Mod」都是使用者资料，误删无法挽回，
 * 所以统一走回收站（等价于在档案总管按 Delete）。
 * 非 Windows 或 shell32 不可用时退回一般删除，并在日志里注明。
 */
public final class RecycleBin {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    // SHFILEOPSTRUCT 常量
    private static final int FO_DELETE = 3;
    private static final int FOF_SILENT = 0x0004;
    private static final int FOF_NOCONFIRMATION = 0x0010;
    private static final int FOF_ALLOWUNDO = 0x0040;
    private static final int FOF_NOERRORUI = 0x0400;

    private static volatile Shell32 shell32;
    private static volatile boolean shell32Unavailable;

    private RecycleBin() {
    }

    /** 刪除檔案或整個資料夾（進回收站）。成功回傳 true。 */
    public static boolean delete(Path path) {
        if (path == null || !Files.exists(path)) {
            return true;
        }
        Path absolute = path.toAbsolutePath().normalize();
        if (WINDOWS) {
            Shell32 api = shell32();
            if (api != null) {
                try {
                    if (shellFileOperation(api, absolute)) {
                        return true;
                    }
                    SafeLog.warn("回收站删除失败，改为直接删除：" + absolute.getFileName());
                } catch (Throwable error) {
                    shell32Unavailable = true;
                    SafeLog.warn("回收站不可用（" + error.getClass().getSimpleName() + "），改为直接删除");
                }
            }
        }
        return deletePermanently(absolute);
    }

    private static boolean shellFileOperation(Shell32 api, Path absolute) {
        String text = absolute.toString();
        int chars = text.length();
        // 需要「双 null 结尾」的 UTF-16 路径列表
        Memory buffer = new Memory((chars + 2) * 2L);
        buffer.setWideString(0, text);
        buffer.setShort(chars * 2L, (short) 0);

        SHFILEOPSTRUCT operation = new SHFILEOPSTRUCT();
        operation.wFunc = FO_DELETE;
        operation.pFrom = buffer;
        operation.fFlags = (short) (FOF_ALLOWUNDO | FOF_NOCONFIRMATION | FOF_NOERRORUI | FOF_SILENT);
        operation.write();
        int result = api.SHFileOperationW(operation);
        operation.read();
        boolean ok = result == 0 && !operation.fAnyOperationsAborted;
        return ok && !Files.exists(absolute);
    }

    private static Shell32 shell32() {
        if (shell32Unavailable) {
            return null;
        }
        Shell32 local = shell32;
        if (local == null) {
            synchronized (RecycleBin.class) {
                if (shell32 == null) {
                    try {
                        shell32 = Native.load("shell32", Shell32.class, W32APIOptions.DEFAULT_OPTIONS);
                    } catch (Throwable error) {
                        shell32Unavailable = true;
                        return null;
                    }
                }
                local = shell32;
            }
        }
        return local;
    }

    private static boolean deletePermanently(Path absolute) {
        try {
            if (Files.isDirectory(absolute)) {
                try (Stream<Path> stream = Files.walk(absolute)) {
                    for (Path item : stream.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(item);
                    }
                }
            } else {
                Files.deleteIfExists(absolute);
            }
            return true;
        } catch (IOException error) {
            SafeLog.warn("删除失败：" + absolute.getFileName() + "（" + error.getClass().getSimpleName() + "）");
            return false;
        }
    }

    /** SHFileOperationW 的结构体，字段顺序必须与 Win32 定义一致。 */
    @Structure.FieldOrder({"hwnd", "wFunc", "pFrom", "pTo", "fFlags",
            "fAnyOperationsAborted", "hNameMappings", "lpszProgressTitle"})
    public static class SHFILEOPSTRUCT extends Structure {
        public Pointer hwnd;
        public int wFunc;
        public Pointer pFrom;
        public Pointer pTo;
        public short fFlags;
        public boolean fAnyOperationsAborted;
        public Pointer hNameMappings;
        public Pointer lpszProgressTitle;
    }

    interface Shell32 extends StdCallLibrary {
        int SHFileOperationW(SHFILEOPSTRUCT operation);
    }
}
