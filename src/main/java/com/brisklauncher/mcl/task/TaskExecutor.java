package com.brisklauncher.mcl.task;

import com.brisklauncher.mcl.game.install.InstallProgress;
import com.brisklauncher.mcl.util.FXUtils;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 任务执行器：虚拟线程跑任务，回呼统一回到 JavaFX 线程。
 *
 * <p>进度事件做「只保留最新一条 + 每次排一次 runLater」的合并，
 * 安装上万个档案时不会把 FX 事件队列塞满（这是旧版 TaskDialog 的主要卡顿来源）。
 */
public final class TaskExecutor {

    private TaskExecutor() {
    }

    public static <T> Task<T> run(Task<T> task, TaskListener<T> listener) {
        TaskListener<T> safe = listener == null ? new TaskListener<>() {
        } : listener;

        ProgressCoalescer coalescer = new ProgressCoalescer(task, safe);
        task.state(Task.State.RUNNING);
        FXUtils.runOnFxThread(safe::onStarted);

        Thread worker = Thread.ofVirtual().name("mcl-task-" + task.name()).start(() -> {
            try {
                T result = task.execute(coalescer::publish);
                coalescer.flush();
                if (task.isCancelled()) {
                    task.state(Task.State.CANCELLED);
                    FXUtils.runOnFxThread(safe::onCancelled);
                    return;
                }
                task.state(Task.State.SUCCEEDED);
                FXUtils.runOnFxThread(() -> safe.onSucceeded(result));
            } catch (Throwable error) {
                coalescer.flush();
                if (task.isCancelled() || error instanceof InterruptedException) {
                    task.state(Task.State.CANCELLED);
                    FXUtils.runOnFxThread(safe::onCancelled);
                    return;
                }
                task.state(Task.State.FAILED);
                FXUtils.runOnFxThread(() -> safe.onFailed(error));
            } finally {
                task.detach();
            }
        });
        task.attach(worker);
        return task;
    }

    /** 合併進度事件：背景線程只寫最新值，FX 線程每次事件循環取一次。 */
    private static final class ProgressCoalescer {

        private final Task<?> task;
        private final TaskListener<?> listener;
        private final AtomicReference<InstallProgress> pending = new AtomicReference<>();
        private final AtomicBoolean scheduled = new AtomicBoolean();

        private ProgressCoalescer(Task<?> task, TaskListener<?> listener) {
            this.task = task;
            this.listener = listener;
        }

        private void publish(InstallProgress progress) {
            if (progress == null || task.isCancelled()) {
                return;
            }
            pending.set(progress);
            if (scheduled.compareAndSet(false, true)) {
                FXUtils.runLater(this::drain);
            }
        }

        @SuppressWarnings("unchecked")
        private void drain() {
            scheduled.set(false);
            InstallProgress latest = pending.getAndSet(null);
            if (latest != null && !task.isCancelled()) {
                ((TaskListener<Object>) listener).onProgress(latest);
            }
        }

        private void flush() {
            drain();
        }
    }
}
