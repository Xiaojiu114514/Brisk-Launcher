package com.brisklauncher.mcl.plugin.api;

/**
 * 插件 API 的版本常数。
 *
 * <p>这是外挂编译期可见的 API 版本；{@code plugin.json} 的 {@code "apiVersion"} 由启动器在载入时
 * 检查（比启动器高就拒绝）。运行期没有「启动器 API 版本」查询方法，{@code VERSION} 是编译期常数。
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
