package com.brisklauncher.mcl.plugin.api;

/**
 * 插件之间的服务登记处（「插件扩展插件」的入口）。
 *
 * <p>这是**自愿契约**：开放方用 {@link #provide(String, Object)} 登记一个名字，
 * 使用方用 {@link #find(String, Class)} 查。名字建议加前缀（{@code "myplugin.api"}），
 * 类型对不上就回传 {@code null}，不会抛例外。
 *
 * <p>生命周期：登记项在插件卸载时由启动器自动清掉；后登记的覆盖先登记的。
 * 依赖别的插件时，请在 {@code plugin.json} 写 {@code requires}，启动器会先载入被依赖者。
 */
public interface PluginServices {

    /** 登记一个服务（{@code service} 为 {@code null} 表示注销）。 */
    void provide(String name, Object service);

    /** 查一个服务；不存在或类型不符回传 {@code null}。 */
    <T> T find(String name, Class<T> type);

    /** 查一个服务；不存在直接抛 {@link IllegalStateException}。 */
    <T> T require(String name, Class<T> type);

    /** 这个名字现在有没有人登记。 */
    boolean has(String name);
}
