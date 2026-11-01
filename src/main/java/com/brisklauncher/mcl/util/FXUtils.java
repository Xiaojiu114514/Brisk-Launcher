package com.brisklauncher.mcl.util;

import com.brisklauncher.mcl.security.SafeLog;
import javafx.application.Platform;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * JavaFX 线程切换与轻量异步执行（对应 HMCL 的 {@code FXUtils}）。
 *
 * <p>统一两件事：
 * <ul>
 *   <li>把结果切回 JavaFX 线程，避免各页面自己写 {@code Platform.runLater}；</li>
 *   <li>用虚拟线程跑阻塞 IO，不用 ForkJoinPool.commonPool（下载并发高时更容易回收）。</li>
 * </ul>
 */
public final class FXUtils {

    private FXUtils() {
    }

    public static boolean isFxThread() {
        return Platform.isFxApplicationThread();
    }

    public static void runLater(Runnable action) {
        if (action != null) {
            Platform.runLater(action);
        }
    }

    /** 已經在 FX 線程就直接執行，否則排到 FX 線程。 */
    public static void runOnFxThread(Runnable action) {
        if (action == null) {
            return;
        }
        if (isFxThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    /**
     * 背景执行 + 结果回 FX 线程。异常不会中断其它任务，只交给 {@code onError}。
     */
    public static <T> void runAsync(String name, Callable<T> body,
                                    Consumer<T> onSuccess, Consumer<Throwable> onError) {
        Thread.ofVirtual().name(name == null ? "mcl-async" : name).start(() -> {
            try {
                T result = body.call();
                if (onSuccess != null) {
                    runOnFxThread(() -> onSuccess.accept(result));
                }
            } catch (Throwable error) {
                if (onError != null) {
                    runOnFxThread(() -> onError.accept(error));
                } else {
                    SafeLog.warn("后台任务失败（" + name + "）：" + error.getClass().getSimpleName());
                }
            }
        });
    }

    public static void runAsync(String name, Runnable body) {
        runAsync(name, () -> {
            body.run();
            return null;
        }, null, null);
    }
}
