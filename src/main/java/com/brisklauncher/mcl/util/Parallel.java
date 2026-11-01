package com.brisklauncher.mcl.util;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 极简的并行工具：固定并发数跑一批「互不相干」的工作。
 *
 * <p>为什么不用 parallelStream：这里的工作都是网路请求（会阻塞），用虚拟执行绪比较省，
 * 而且可以精确控制并发数（避免一次打爆 Modrinth 镜像或把使用者频宽吃光）。
 * 任一项失败都不影响其它项（失败的例外会被吞掉，呼叫方自己记录）。
 */
public final class Parallel {

    private Parallel() {
    }

    public static <T> void forEach(List<T> items, int concurrency, Consumer<T> action) {
        if (items == null || items.isEmpty() || action == null) {
            return;
        }
        // 并发数可由 -Dmcl.dev.parallel=N 覆写（用来量测并行带来的加速，平常不用设）
        int limit = Integer.getInteger("mcl.dev.parallel", concurrency);
        int workers = Math.max(1, Math.min(Math.min(concurrency, limit), items.size()));
        AtomicInteger cursor = new AtomicInteger();
        List<Thread> threads = new ArrayList<>(workers);
        for (int index = 0; index < workers; index++) {
            Thread thread = Thread.ofVirtual().name("mcl-parallel-" + index).unstarted(() -> {
                while (true) {
                    int position = cursor.getAndIncrement();
                    if (position >= items.size()) {
                        return;
                    }
                    try {
                        action.accept(items.get(position));
                    } catch (Exception ignored) {
                        // 单一项目失败不影响其它
                    }
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
