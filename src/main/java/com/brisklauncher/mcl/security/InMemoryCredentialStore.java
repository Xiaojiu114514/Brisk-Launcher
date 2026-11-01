package com.brisklauncher.mcl.security;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 仅用于单元测试或非 Windows 平台的降级占位，进程退出即消失。 */
public final class InMemoryCredentialStore implements CredentialStore {

    private final Map<String, char[]> store = new ConcurrentHashMap<>();

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public void put(String target, char[] secret) {
        store.put(target, secret == null ? new char[0] : secret.clone());
    }

    @Override
    public Optional<char[]> get(String target) {
        char[] value = store.get(target);
        return value == null ? Optional.empty() : Optional.of(value.clone());
    }

    @Override
    public void delete(String target) {
        char[] removed = store.remove(target);
        if (removed != null) {
            java.util.Arrays.fill(removed, '\0');
        }
    }
}