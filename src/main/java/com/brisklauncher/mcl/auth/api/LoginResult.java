package com.brisklauncher.mcl.auth.api;

import java.util.List;

/** 登录结果。errorMessage 只放面向用户的安全提示，不含任何令牌。 */
public record LoginResult(boolean success, List<StoredSession> sessions, String errorMessage) {

    public LoginResult {
        sessions = sessions == null ? List.of() : List.copyOf(sessions);
    }

    public LoginResult(boolean success, StoredSession session, String errorMessage) {
        this(success, session == null ? List.of() : List.of(session), errorMessage);
    }

    public static LoginResult ok(StoredSession session) {
        return new LoginResult(true, session, null);
    }

    public static LoginResult ok(List<StoredSession> sessions) {
        return new LoginResult(true, sessions, null);
    }

    public static LoginResult fail(String safeMessage) {
        return new LoginResult(false, List.of(), safeMessage);
    }

    /** 主角色（第一个）；兼容只关心单账号的旧调用。 */
    public StoredSession session() {
        return sessions.isEmpty() ? null : sessions.get(0);
    }
}