package com.brisklauncher.mcl.auth.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * OAuth 2.0 PKCE（RFC 7636）与 state 的小工具。
 *
 * <p>用途：localhost 回呼流程是「公开客户端」，没有 client secret，
 * 所以必须用 PKCE 防止授权码被别的程式拦截后拿去换令牌；
 * {@code state} 则用来防 CSRF / 误导回呼。
 *
 * <p>安全性：verifier 只在本机记忆体里（不外传、不落盘、不写日志），
 * 浏览器只会拿到 SHA-256 之后的 challenge。
 */
final class Pkce {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Pkce() {
    }

    /** 產生 code_verifier：32 位元組隨機 → base64url（無 padding，43 字元）。 */
    static String verifier() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** challenge = BASE64URL(SHA-256(verifier))，對應 code_challenge_method=S256。 */
    static String challenge(String verifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception error) {
            throw new IllegalStateException("无法计算 PKCE challenge", error);
        }
    }

    /** 隨機 state（16 位元組）。 */
    static String state() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 常數時間比對 state，避免時序側信道。 */
    static boolean stateMatches(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
