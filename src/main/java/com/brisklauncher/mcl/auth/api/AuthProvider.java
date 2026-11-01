package com.brisklauncher.mcl.auth.api;

/**
 * 认证接口（开源）。
 * 实现类由闭源 JAR 通过 {@code META-INF/services} 注册，UI 层只依赖本接口。
 */
public interface AuthProvider {

    AccountType type();

    LoginResult login(LoginCredentials credentials);

    LoginResult refresh(StoredSession session);

    boolean validate(StoredSession session);

    GameSession toGameSession(StoredSession session);
}