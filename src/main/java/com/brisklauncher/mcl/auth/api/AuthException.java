package com.brisklauncher.mcl.auth.api;

/** 认证异常。message 绝不包含令牌、密码等内容。 */
public class AuthException extends RuntimeException {

    public AuthException(String safeMessage) {
        super(safeMessage);
    }

    public AuthException(String safeMessage, Throwable cause) {
        super(safeMessage, cause);
    }
}