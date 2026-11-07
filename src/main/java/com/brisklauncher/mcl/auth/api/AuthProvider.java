package com.brisklauncher.mcl.auth.api;

import java.util.Locale;

/**
 * 认证接口（对外稳定 API）。
 *
 * <p>内建实现（离线 / 正版 / 第三方 Yggdrasil）由启动器注册；插件可以用两种方式提供实现：
 * <ol>
 *   <li>在 jar 里放 {@code META-INF/services/com.brisklauncher.mcl.auth.api.AuthProvider}
 *       （内容是实现类的全名）；</li>
 *   <li>在 {@code onLoad} 里呼叫 {@link com.brisklauncher.mcl.plugin.api.PluginContext#registerAuthProvider}。</li>
 * </ol>
 * 同一个 {@link #id()} 后注册的优先；同 {@link #type()} 的实现会被插件替换（例如接自建皮肤站）。
 * 插件卸载时启动器自动注销，不会留下悬空实现。
 */
public interface AuthProvider {

    /** 账号类型（决定账号存盘与界面归类）。 */
    AccountType type();

    /**
     * 实现 id：同名实现会互相覆盖。
     *
     * <p>默认用类型名的小写（{@code microsoft} / {@code offline} / {@code third_party}）。
     * 想在同一个类型下提供多套实现（例如两个不同的第三方皮肤站），请覆盖成不同的 id。
     */
    default String id() {
        return type().name().toLowerCase(Locale.ROOT);
    }

    /** 界面上的显示名称（账号页下拉用）。 */
    default String displayName() {
        return type().name();
    }

    /** 界面上的小图标（emoji，可以为空）。 */
    default String icon() {
        return "";
    }

    LoginResult login(LoginCredentials credentials);

    LoginResult refresh(StoredSession session);

    boolean validate(StoredSession session);

    GameSession toGameSession(StoredSession session);
}
