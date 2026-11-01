package com.brisklauncher.mcl.auth.impl;

import com.brisklauncher.mcl.auth.api.AccountType;
import com.brisklauncher.mcl.auth.api.AuthProvider;
import com.brisklauncher.mcl.auth.api.GameSession;
import com.brisklauncher.mcl.auth.api.LoginCredentials;
import com.brisklauncher.mcl.auth.api.LoginResult;
import com.brisklauncher.mcl.auth.api.StoredSession;
import com.brisklauncher.mcl.util.Uuids;

/** 离线账号：本地生成 UUID，无任何网络请求。 */
public final class OfflineAuthProvider implements AuthProvider {

    private static final String OFFLINE = "legacy";

    @Override
    public AccountType type() {
        return AccountType.OFFLINE;
    }

    @Override
    public LoginResult login(LoginCredentials credentials) {
        String name = credentials.username();
        if (name == null || name.isBlank()) {
            return LoginResult.fail("用户名不能为空");
        }
        if (!name.matches("[A-Za-z0-9_]{3,16}")) {
            return LoginResult.fail("用户名需为 3-16 位字母、数字或下划线");
        }
        String uuid = Uuids.offlineUuid(name);
        return LoginResult.ok(new StoredSession(
                AccountType.OFFLINE, uuid, name, null, new char[]{'0'}, Long.MAX_VALUE, null));
    }

    @Override
    public LoginResult refresh(StoredSession session) {
        return LoginResult.ok(session);
    }

    @Override
    public boolean validate(StoredSession session) {
        return session != null && session.name() != null && !session.name().isBlank();
    }

    @Override
    public GameSession toGameSession(StoredSession session) {
        return new GameSession(session.name(), session.uuid(), new char[]{'0'}, OFFLINE, "{}", null);
    }
}