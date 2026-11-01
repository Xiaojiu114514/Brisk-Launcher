package com.brisklauncher.mcl.auth.api;

import java.net.URI;
import java.util.Arrays;

/**
 * 登录凭据。secret 使用 char[] 且做防御性拷贝，用完必须调用 {@link #wipe()}。
 */
public record LoginCredentials(AccountType type, String username, char[] secret, URI endpoint) {

    public LoginCredentials {
        secret = secret == null ? null : secret.clone();
    }

    @Override
    public char[] secret() {
        return secret == null ? null : secret.clone();
    }

    public void wipe() {
        if (secret != null) {
            Arrays.fill(secret, '\0');
        }
    }
}