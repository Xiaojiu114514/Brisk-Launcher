package com.brisklauncher.mcl.ui;

/** 页面契约：切页时刷新数据，避免页面之间状态不同步。 */
public interface MclPage {

    /** 页面成为可见页时调用。 */
    default void onShown() {
    }

    /** 页面被切走时调用，用于停止轮询/动画，避免页面重建后泄漏。 */
    default void onHidden() {
    }

    /**
     * 页面被丢弃（页面缓存淘汰、语言切换重建、窗口关闭）时调用。
     *
     * <p>与 {@link #onHidden()} 的区别：onHidden 之后页面还可能被复用，
     * 这里必须彻底断开监听器、定时器、图片缓存与 3D 资源。
     */
    default void onReleased() {
    }
}
