package com.brisklauncher.mcl.game.launch;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次启动的句柄：UI 用它观察完成状态，也用它发起“强制取消”。
 *
 * <p>{@link CompletableFuture#cancel(boolean)} 不会中断 supplyAsync 里的线程，
 * 所以这里用显式标记 + 检查点，配合 {@link RunningGames#stopByVersion(String)}
 * 才能真正把启动过程停下来。
 */
public final class LaunchHandle {

    private final String versionId;
    private final RunningGames runningGames;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile CompletableFuture<Process> future;

    LaunchHandle(String versionId, RunningGames runningGames) {
        this.versionId = versionId;
        this.runningGames = runningGames;
    }

    public CompletableFuture<Process> future() {
        return future;
    }

    void attach(CompletableFuture<Process> future) {
        this.future = future;
    }

    public String versionId() {
        return versionId;
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /** 请求取消：设置标记并立刻结束已经启动的游戏进程。 */
    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        try {
            runningGames.stopByVersion(versionId);
        } catch (Exception ignored) {
            // 尽力而为
        }
    }
}
