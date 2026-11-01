package com.brisklauncher.mcl.auth.api;

import java.util.Arrays;

/**
 * 持久化的会话。令牌只允许通过 {@code SessionTokenStore} 写入 Windows 凭据管理器，
 * 不允许写入实例目录、launcher.json、日志。
 */
public record StoredSession(AccountType type,
                            String uuid,
                            String name,
                            char[] refreshToken,
                            char[] accessToken,
                            long expiresAt,
                            String endpoint) {

    public StoredSession {
        refreshToken = refreshToken == null ? null : refreshToken.clone();
        accessToken = accessToken == null ? null : accessToken.clone();
    }

    @Override
    public char[] refreshToken() {
        return refreshToken == null ? null : refreshToken.clone();
    }

    @Override
    public char[] accessToken() {
        return accessToken == null ? null : accessToken.clone();
    }

    public boolean isExpired() {
        return System.currentTimeMillis() >= expiresAt;
    }

    public void wipe() {
        if (refreshToken != null) Arrays.fill(refreshToken, '\0');
        if (accessToken != null)  Arrays.fill(accessToken, '\0');
    }

    @Override
    public String toString() {
        // 绝不输出令牌
        return "StoredSession[" + type + ", " + name + ", " + uuid + "]";
    }
}