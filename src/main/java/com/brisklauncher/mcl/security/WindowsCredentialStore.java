package com.brisklauncher.mcl.security;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Windows 凭据管理器实现。
 * 令牌以 UTF-8 字节写入 CredentialBlob，目标名为 {@code MCL/account/<uuid>}。
 */
public final class WindowsCredentialStore implements CredentialStore {

    private static final int CRED_TYPE_GENERIC = 1;
    private static final int CRED_PERSIST_LOCAL_MACHINE = 2;
    private static final int ERROR_NOT_FOUND = 1168;
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static volatile Boolean available;

    private interface Advapi32Ex extends StdCallLibrary {
        Advapi32Ex INSTANCE = Native.load("Advapi32", Advapi32Ex.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean CredWriteW(CREDENTIALW credential, int flags);

        boolean CredReadW(WString target, int type, int flags, PointerByReference out);

        boolean CredDeleteW(WString target, int type, int flags);

        boolean CredEnumerateW(WString filter, int flags,
                               com.sun.jna.ptr.IntByReference count, PointerByReference out);

        void CredFree(Pointer pointer);
    }

    public static class CREDENTIALW extends Structure {
        public WinDef.DWORD Flags = new WinDef.DWORD(0);
        public WinDef.DWORD Type;
        public WString TargetName;
        public WString Comment;
        public WinBase.FILETIME LastWritten;
        public WinDef.DWORD CredentialBlobSize;
        public Pointer CredentialBlob;
        public WinDef.DWORD Persist;
        public WinDef.DWORD AttributeCount;
        public Pointer Attributes;
        public WString TargetAlias;
        public WString UserName;

        public CREDENTIALW() {
            super(ALIGN_DEFAULT);
        }

        public CREDENTIALW(Pointer pointer) {
            super(pointer, ALIGN_DEFAULT);
            read();
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("Flags", "Type", "TargetName", "Comment", "LastWritten",
                    "CredentialBlobSize", "CredentialBlob", "Persist", "AttributeCount",
                    "Attributes", "TargetAlias", "UserName");
        }
    }

    @Override
    public boolean isAvailable() {
        if (!WINDOWS) {
            return false;
        }
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        synchronized (WindowsCredentialStore.class) {
            if (available == null) {
                try {
                    // 先把 JNA 的 tmpdir 指到可写目录，再触发 jnidispatch 加载
                    NativeSetup.prepare();
                    Advapi32Ex.INSTANCE.getClass();
                    available = Boolean.TRUE;
                } catch (Throwable error) {
                    SafeLog.warn("Windows 凭据管理器不可用，令牌将只保存在本次会话："
                            + error.getClass().getSimpleName());
                    available = Boolean.FALSE;
                }
            }
            return available;
        }
    }

    @Override
    public void put(String target, char[] secret) {
        requireWindows();
        byte[] bytes = toBytes(secret);
        Memory blob = new Memory(Math.max(bytes.length, 1));
        blob.write(0, bytes, 0, bytes.length);
        try {
            CREDENTIALW cred = new CREDENTIALW();
            cred.Type = new WinDef.DWORD(CRED_TYPE_GENERIC);
            cred.TargetName = new WString(target);
            cred.CredentialBlobSize = new WinDef.DWORD(bytes.length);
            cred.CredentialBlob = blob;
            cred.Persist = new WinDef.DWORD(CRED_PERSIST_LOCAL_MACHINE);
            cred.UserName = new WString("MCL");
            cred.write();
            if (!Advapi32Ex.INSTANCE.CredWriteW(cred, 0)) {
                throw new IllegalStateException("写入凭据失败, code=" + Kernel32.INSTANCE.GetLastError());
            }
        } finally {
            Arrays.fill(bytes, (byte) 0);
            blob.clear(Math.max(bytes.length, 1));
        }
    }

    @Override
    public Optional<char[]> get(String target) {
        requireWindows();
        PointerByReference ref = new PointerByReference();
        if (!Advapi32Ex.INSTANCE.CredReadW(new WString(target), CRED_TYPE_GENERIC, 0, ref)) {
            int code = Kernel32.INSTANCE.GetLastError();
            if (code == ERROR_NOT_FOUND) {
                return Optional.empty();
            }
            throw new IllegalStateException("读取凭据失败, code=" + code);
        }
        Pointer pointer = ref.getValue();
        try {
            CREDENTIALW cred = new CREDENTIALW(pointer);
            int size = cred.CredentialBlobSize.intValue();
            if (size <= 0) {
                return Optional.of(new char[0]);
            }
            byte[] bytes = cred.CredentialBlob.getByteArray(0, size);
            try {
                return Optional.of(new String(bytes, StandardCharsets.UTF_8).toCharArray());
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        } finally {
            Advapi32Ex.INSTANCE.CredFree(pointer);
        }
    }

    @Override
    public void delete(String target) {
        requireWindows();
        Advapi32Ex.INSTANCE.CredDeleteW(new WString(target), CRED_TYPE_GENERIC, 0);
    }

    /**
     * 枚举以 {@code prefix} 开头的凭据目标名（安全审计 v2 A-09）。
     *
     * <p>用 {@code CredEnumerateW} 加通配符过滤，只读目标名、不读取密钥内容；
     * 任何失败都回空清单（清理是「尽力而为」，不能影响启动）。
     */
    @Override
    public List<String> listTargets(String prefix) {
        if (!WINDOWS || !isAvailable()) {
            return List.of();
        }
        String filter = (prefix == null ? "" : prefix) + "*";
        com.sun.jna.ptr.IntByReference count = new com.sun.jna.ptr.IntByReference(0);
        PointerByReference ref = new PointerByReference();
        if (!Advapi32Ex.INSTANCE.CredEnumerateW(new WString(filter), 0, count, ref)) {
            // ERROR_NOT_FOUND 是「一条都没有」，属正常情况
            return List.of();
        }
        Pointer array = ref.getValue();
        java.util.List<String> targets = new java.util.ArrayList<>();
        try {
            int total = count.getValue();
            com.sun.jna.Pointer[] credentials = array.getPointerArray(0, total);
            for (com.sun.jna.Pointer pointer : credentials) {
                CREDENTIALW cred = new CREDENTIALW(pointer);
                if (cred.TargetName != null) {
                    String name = cred.TargetName.toString();
                    if (name != null && name.startsWith(filter.substring(0, filter.length() - 1))) {
                        targets.add(name);
                    }
                }
            }
        } catch (Throwable error) {
            SafeLog.warn("枚举凭据失败：" + error.getClass().getSimpleName());
        } finally {
            Advapi32Ex.INSTANCE.CredFree(array);
        }
        return targets;
    }

    private static byte[] toBytes(char[] chars) {
        return new String(chars == null ? new char[0] : chars).getBytes(StandardCharsets.UTF_8);
    }

    private static void requireWindows() {
        if (!WINDOWS) {
            throw new UnsupportedOperationException("当前平台不支持 Windows 凭据管理器");
        }
    }
}