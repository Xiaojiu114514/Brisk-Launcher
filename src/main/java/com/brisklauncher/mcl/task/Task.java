package com.brisklauncher.mcl.task;

import com.brisklauncher.mcl.game.install.InstallProgress;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 可取消的长任务（参考 HMCL 的 {@code Task}）。
 *
 * <p>安装、下载、补全、整合包汇入汇出、启动准备都走这里：
 * UI 只注册 {@link TaskListener}，不在 JavaFX 线程跑阻塞逻辑。
 * 取消靠虚拟线程中断，沿用既有 {@code Thread.interrupted()} 判断，
 * 因此不需要额外的取消旗标传递。
 */
public abstract class Task<T> {

    public enum State {
        WAITING,
        RUNNING,
        SUCCEEDED,
        FAILED,
        CANCELLED
    }

    /** 進度上報入口；由 {@link TaskExecutor} 轉成 JavaFX 線程回呼。 */
    @FunctionalInterface
    public interface ProgressSink {
        void accept(InstallProgress progress);
    }

    private final String name;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile State state = State.WAITING;
    private volatile Thread worker;

    protected Task(String name) {
        this.name = name == null ? "task" : name;
    }

    public final String name() {
        return name;
    }

    public final State state() {
        return state;
    }

    public final boolean isCancelled() {
        return cancelled.get();
    }

    /** 真正的業務邏輯；需要支持取消的實現請在迴圈裡檢查中斷。 */
    public abstract T execute(ProgressSink progress) throws Exception;

    public final void cancel() {
        cancelled.set(true);
        Thread thread = worker;
        if (thread != null) {
            thread.interrupt();
        }
    }

    // ---------- 供 TaskExecutor 使用 ----------

    final void attach(Thread thread) {
        this.worker = thread;
        if (cancelled.get()) {
            thread.interrupt();
        }
    }

    final void detach() {
        this.worker = null;
    }

    final void state(State newState) {
        this.state = newState;
    }

    /** 便捷適配：把既有的 {@code Consumer<InstallProgress>} 風格包成任務。 */
    public static <T> Task<T> of(String name, Executable<T> body) {
        return new Task<>(name) {
            @Override
            public T execute(ProgressSink progress) throws Exception {
                return body.run(progress::accept);
            }
        };
    }

    @FunctionalInterface
    public interface Executable<T> {
        T run(Consumer<InstallProgress> progress) throws Exception;
    }
}
