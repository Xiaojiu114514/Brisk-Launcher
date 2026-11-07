package com.brisklauncher.mcl.plugin.api;

/**
 * 插件 API 的版本常数。
 *
 * <p>启动器在载入插件时会把这里的值告诉插件（{@link PluginContext#launcherVersion()} 是启动器版本，
 * 这里是 <b>API</b> 版本）。插件可以在 {@code onLoad} 里做一次判断，自己决定要不要降级运行。
 *
 * <p>规则：加方法 = 次版本号 +1；删方法或改语义 = 主版本号 +1。
 */
public final class PluginApi {

    /** 当前 API 版本。 */
    public static final int VERSION = 2;

    /** {@code plugin.json} 里可宣告 {@code "apiVersion": 2}；启动器不认就拒绝载入。 */
    public static final String MANIFEST_API = "apiVersion";

    private PluginApi() {
    }
}
