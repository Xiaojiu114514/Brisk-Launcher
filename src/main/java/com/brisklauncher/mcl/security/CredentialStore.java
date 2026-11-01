package com.brisklauncher.mcl.security;

import java.util.List;
import java.util.Optional;

/** 系统凭据存储抽象。令牌永不进入启动器目录。 */
public interface CredentialStore {

    boolean isAvailable();

    void put(String target, char[] secret);

    Optional<char[]> get(String target);

    void delete(String target);

    /**
     * 列出以 {@code prefix} 开头的凭据目标名（安全审计 v2 A-09 的孤儿清理用）。
     *
     * <p>默认返回空清单：只有 Windows 实现能在不读取密钥的前提下枚举。
     */
    default List<String> listTargets(String prefix) {
        return List.of();
    }
}