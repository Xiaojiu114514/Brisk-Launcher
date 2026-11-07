package com.brisklauncher.mcl.plugin.api;

import javafx.scene.Node;

/**
 * 插件贡献的一个导航页（顶栏入口）。
 *
 * <p>页面对象在插件载入时就存在，但 {@link #createContent(PluginContext)} 只在用户第一次
 * 切到这一页时调用一次；页面被丢弃时调用 {@link #onDispose()}。
 *
 * <p>线程约定：{@code createContent} / {@code onShown} / {@code onHidden} / {@code onDispose}
 * 都在 JavaFX 执行绪上调用。
 */
public interface LauncherPage {

    /** 页面 id：只允许 {@code [A-Za-z0-9._-]}，启动器会自动加上插件 id 前缀。 */
    String id();

    /** 顶栏与标题上的文字（当前界面语言）。 */
    String title();

    /** 顶栏上的小图标（emoji，可以为空字串）。 */
    default String icon() {
        return "";
    }

    /** 页面内容；返回的 Node 会直接铺满内容区。 */
    Node createContent(PluginContext context);

    /** 页面成为可见页时调用。 */
    default void onShown() {
    }

    /** 页面被切走时调用；页面还可能被复用，所以不要在这里销毁内容。 */
    default void onHidden() {
    }

    /** 页面被丢弃时调用：断开监听器、停掉轮询、释放图片。 */
    default void onDispose() {
    }
}
