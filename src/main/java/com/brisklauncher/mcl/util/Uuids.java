package com.brisklauncher.mcl.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 离线 UUID 生成（与官方 OfflinePlayer 算法一致）。 */
public final class Uuids {

    private Uuids() {
    }

    public static String offlineUuid(String username) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x30);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            StringBuilder sb = new StringBuilder(36);
            for (int i = 0; i < 16; i++) {
                if (i == 4 || i == 6 || i == 8 || i == 10) {
                    sb.append('-');
                }
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }
}