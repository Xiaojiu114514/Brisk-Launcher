package com.brisklauncher.mcl.task;

import com.brisklauncher.mcl.game.install.InstallProgress;

/**
 * 任务生命周期回呼，全部在 JavaFX 线程被呼叫。
 *
 * <p>预设方法全为空，UI 只覆写关心的那几个。
 */
public interface TaskListener<T> {

    default void onStarted() {
    }

    /** 進度已經在 TaskExecutor 內做合併，不會把 FX 事件隊列打爆。 */
    default void onProgress(InstallProgress progress) {
    }

    default void onSucceeded(T result) {
    }

    default void onFailed(Throwable error) {
    }

    default void onCancelled() {
    }
}
