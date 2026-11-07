package com.brisklauncher.mcl.auth.impl;

import com.brisklauncher.mcl.auth.api.AccountType;
import com.brisklauncher.mcl.auth.api.AuthProvider;
import com.brisklauncher.mcl.security.SafeLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * 认证实现注册表。
 *
 * <p>开源侧内置离线 / 正版 / 第三方三套实现；插件可以：
 * <ul>
 *   <li>用 {@code META-INF/services} 注册（载入时自动扫描）；</li>
 *   <li>在 {@code onLoad} 里呼叫 {@link com.brisklauncher.mcl.plugin.api.PluginContext#registerAuthProvider}。</li>
 * </ul>
 * 同一个 {@code id} 后注册的覆盖先注册的；同 {@link AccountType} 取最后注册的那个，
 * 所以插件可以替换内建实现，但不会把内建实现弄丢（{@link #all()} 里还能看到被覆盖前的 id）。
 *
 * <p>线程安全：所有方法都 synchronized（登入流程在背景线程，插件注册在启动线程）。
 */
public final class AuthProviderRegistry {

    /** id → 实现（保持插入顺序，方便界面照着列）。 */
    private static final Map<String, AuthProvider> PROVIDERS = new LinkedHashMap<>();

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

    /** 注册（同 id 覆盖）。 */
    public static synchronized void register(AuthProvider provider) {
        if (provider == null) {
            return;
        }
        PROVIDERS.put(idOf(provider), provider);
    }

    /** 注销（插件卸载时用）。 */
    public static synchronized void unregister(String id) {
        if (id != null) {
            PROVIDERS.remove(id);
        }
    }

    /** 实现 id；插件没覆盖 {@code id()} 时就是类型名的小写。 */
    public static String idOf(AuthProvider provider) {
        if (provider == null) {
            return "";
        }
        try {
            String id = provider.id();
            if (id != null && !id.isBlank()) {
                return id.trim().toLowerCase(Locale.ROOT);
            }
        } catch (Throwable ignored) {
            // 插件实作坏掉就退回类型名
        }
        return provider.type().name().toLowerCase(Locale.ROOT);
    }

    /** 该类型的默认实现（最后注册的那个）。 */
    public static synchronized Optional<AuthProvider> get(AccountType type) {
        return forType(type).stream().reduce((first, second) -> second);
    }

    /** 按 id 找（账号存了 providerId 时用）。 */
    public static synchronized Optional<AuthProvider> byId(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(PROVIDERS.get(id.trim().toLowerCase(Locale.ROOT)));
    }

    /** 该类型下所有可用实现（界面下拉用；顺序 = 注册顺序）。 */
    public static synchronized List<AuthProvider> forType(AccountType type) {
        List<AuthProvider> result = new ArrayList<>();
        for (AuthProvider provider : PROVIDERS.values()) {
            if (provider.type() == type) {
                result.add(provider);
            }
        }
        return result;
    }

    /** 全部实现（除错 / 工具页显示用）。 */
    public static synchronized List<AuthProvider> all() {
        return new ArrayList<>(PROVIDERS.values());
    }

    /** 清空并重新注册内建实现（插件热重载时用；测试也用它）。 */
    public static synchronized void resetToBuiltins() {
        PROVIDERS.clear();
        register(new OfflineAuthProvider());
        register(new MicrosoftAuthProvider());
        register(new YggdrasilAuthProvider());
    }
}
