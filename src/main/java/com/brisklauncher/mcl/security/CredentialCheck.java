package com.brisklauncher.mcl.security;

import com.brisklauncher.mcl.auth.api.AccountType;
import com.brisklauncher.mcl.auth.api.StoredSession;

import java.util.Optional;

/**
 * 凭据存储自检：验证 JNA / Windows 凭据管理器在当前启动方式下是否可用。
 *
 * <p>用途：jlink/jpackage 打包后如果出现“无法写入凭据管理器”，
 * 可以直接运行 {@code mcl.bat --credentials-test} 看具体原因。
 * 测试用的条目会被立即删除，不会污染真实账号。
 */
public final class CredentialCheck {

    private CredentialCheck() {
    }

    public static int run() {
        NativeSetup.prepare();
        String target = "MCL/self-test/" + System.nanoTime();
        WindowsCredentialStore store = new WindowsCredentialStore();
        if (!store.isAvailable()) {
            System.out.println("[credentials] FAILED: JNA / Windows Credential Manager unavailable");
            return 2;
        }
        try {
            store.put(target, "mcl-self-test".toCharArray());
            Optional<char[]> loaded = store.get(target);
            store.delete(target);
            if (loaded.isEmpty() || !"mcl-self-test".equals(new String(loaded.get()))) {
                System.out.println("[credentials] FAILED: read back mismatch");
                return 3;
            }
            System.out.println("[credentials] OK: Windows Credential Manager works");
            return 0;
        } catch (Throwable error) {
            System.out.println("[credentials] FAILED: " + error.getClass().getSimpleName()
                    + " - " + error.getMessage());
            return 4;
        }
    }

    /** 供 AccountList 使用的简单持久化探测。 */
    public static boolean persistent() {
        return new WindowsCredentialStore().isAvailable();
    }

    public static StoredSession sampleSession() {
        return new StoredSession(AccountType.OFFLINE, "00000000000000000000000000000000",
                "SelfTest", null, new char[]{'0'}, Long.MAX_VALUE, null);
    }
}
