package com.brisklauncher.mcl.auth.impl;

import com.brisklauncher.mcl.auth.api.AccountType;
import com.brisklauncher.mcl.auth.api.AuthProvider;
import com.brisklauncher.mcl.security.SafeLog;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * 认证实现注册表。
 *
 * <p>开源侧内置离线 / 正版 / 第三方三套实现；如果将来把认证模块拆成闭源 JAR，
 * 仍可通过 {@link ServiceLoader} 覆盖同类型的实现（后注册的优先）。
 */
public final class AuthProviderRegistry {

    private static final Map<AccountType, AuthProvider> PROVIDERS = new EnumMap<>(AccountType.class);

    static {
        register(new OfflineAuthProvider());
        register(new MicrosoftAuthProvider());
        register(new YggdrasilAuthProvider());
        try {
            ServiceLoader.load(AuthProvider.class).forEach(AuthProviderRegistry::register);
        } catch (Throwable t) {
            // 扩展模块缺失不应导致启动失败
            SafeLog.warn("认证扩展模块未加载");
        }
    }

    private AuthProviderRegistry() {
    }

    public static void register(AuthProvider provider) {
        if (provider != null) {
            PROVIDERS.put(provider.type(), provider);
        }
    }

    public static Optional<AuthProvider> get(AccountType type) {
        return Optional.ofNullable(PROVIDERS.get(type));
    }
}
