package com.brisklauncher.mcl.auth.api;

import java.util.Arrays;

/**
 * 组装启动参数所需的会话视图。
 *
 * @param endpoint 第三方认证服务器地址（Yggdrasil），正版/离线为 null
 */
public record GameSession(String username, String uuid, char[] accessToken,
                          String userType, String userProperties, String endpoint) {

    public GameSession {
        accessToken = accessToken == null ? null : accessToken.clone();
    }

    @Override
    public char[] accessToken() {
        return accessToken == null ? null : accessToken.clone();
    }

    public boolean isThirdParty() {
        return endpoint != null && !endpoint.isBlank();
    }

    public boolean isOffline() {
        return "legacy".equalsIgnoreCase(userType);
    }

    public void wipe() {
        if (accessToken != null) {
            Arrays.fill(accessToken, '\0');
        }
    }

    @Override
    public String toString() {
        return "GameSession[" + username + "]";
    }
}
