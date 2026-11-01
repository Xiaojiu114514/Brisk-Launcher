package com.brisklauncher.mcl.util;

import java.util.function.Supplier;

/**
 * 延迟初始化的值持有者（参考 HMCL 的 {@code Lazy}）。
 *
 * <p>两个用途：
 * <ul>
 *   <li>页面/重资源在首次使用时才创建，启动阶段保持轻量；</li>
 *   <li>{@link #clear()} 之后立即切断对值的强引用，让页面淘汰时能被回收。</li>
 * </ul>
 * 只在 JavaFX 线程使用，因此不加锁；跨线程使用时请自行保证可见性。
 */
public final class Lazy<T> {

    private Supplier<T> supplier;
    private T value;
    private boolean loaded;

    public Lazy(Supplier<T> supplier) {
        this.supplier = supplier;
    }

    public static <T> Lazy<T> of(Supplier<T> supplier) {
        return new Lazy<>(supplier);
    }

    /** 空壳：值由 {@link #set(Object)} 提供。 */
    public static <T> Lazy<T> empty() {
        return new Lazy<>(null);
    }

    public T get() {
        if (!loaded) {
            if (supplier == null) {
                return null;
            }
            T created = supplier.get();
            if (created == null) {
                return null;
            }
            value = created;
            loaded = true;
            // 创建完就丢掉 lambda，避免它继续强引用页面/上下文
            supplier = null;
        }
        return value;
    }

    public boolean isLoaded() {
        return loaded;
    }

    /** 已创建时返回值，不触发创建。 */
    public T peek() {
        return value;
    }

    public void set(T newValue) {
        this.value = newValue;
        this.loaded = newValue != null;
        this.supplier = null;
    }

    /** 释放值并恢复成可再次创建的惰性状态。 */
    public void reset(Supplier<T> newSupplier) {
        this.value = null;
        this.loaded = false;
        this.supplier = newSupplier;
    }

    /** 彻底释放：值、工厂引用都不再持有。 */
    public void clear() {
        this.value = null;
        this.supplier = null;
        this.loaded = false;
    }
}
