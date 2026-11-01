package com.brisklauncher.mcl.game.launch;

import com.brisklauncher.mcl.security.SafeLog;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 正在运行的游戏进程登记表（对应 UI 的“运行中”浮窗与“强制停止全部”）。
 * 进程退出后自动移除；非用户主动停止的退出会通知崩溃检测监听器。
 */
public final class RunningGames {

    public record RunningGame(String versionId, long pid, Instant startedAt, Process process) {
        public String uptime() {
            Duration duration = Duration.between(startedAt, Instant.now());
            long minutes = duration.toMinutes();
            long seconds = duration.toSeconds() % 60;
            return minutes + ":" + String.format("%02d", seconds);
        }
    }

    /** 游戏进程退出事件；stoppedByUser=true 时不弹崩溃窗。 */
    public record ExitInfo(String versionId, long pid, int exitCode, boolean stoppedByUser) {
    }

    private final Map<Long, RunningGame> games = new ConcurrentHashMap<>();
    private final Set<Long> intentionalStops = ConcurrentHashMap.newKeySet();
    private final List<Consumer<ExitInfo>> exitListeners = new CopyOnWriteArrayList<>();

    /**
     * 注册退出监听器。
     *
     * @return 取消注册的动作；页面被丢弃时务必呼叫，否则旧页面永远被这里强引用
     */
    public Runnable addExitListener(Consumer<ExitInfo> listener) {
        exitListeners.add(listener);
        return () -> exitListeners.remove(listener);
    }

    public void register(String versionId, Process process) {
        RunningGame game = new RunningGame(versionId, process.pid(), Instant.now(), process);
        games.put(process.pid(), game);
        process.onExit().thenAccept(exited -> {
            games.remove(game.pid());
            boolean intentional = intentionalStops.remove(game.pid());
            if (!intentional) {
                ExitInfo info = new ExitInfo(versionId, game.pid(), exited.exitValue(), false);
                for (Consumer<ExitInfo> listener : exitListeners) {
                    try {
                        listener.accept(info);
                    } catch (Exception error) {
                        SafeLog.warn("处理游戏退出事件失败：" + error.getClass().getSimpleName());
                    }
                }
            }
        });
    }

    public List<RunningGame> list() {
        return new ArrayList<>(games.values());
    }

    public boolean hasRunning() {
        return !games.isEmpty();
    }

    public int count() {
        return games.size();
    }

    /**
     * 解除对正在运行的游戏的跟踪，但**不结束进程**。
     *
     * <p>用于「关闭启动器」：游戏应当继续跑（PCL/HMCL 都是这样），
     * 之前这里调用了 stopAll()，导致退出启动器连带把游戏杀掉。
     */
    public void detachAll() {
        games.clear();
    }
    public int stopAll() {
        int stopped = 0;
        for (RunningGame game : list()) {
            Process process = game.process();
            if (process.isAlive()) {
                intentionalStops.add(game.pid());
                try {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                } catch (Exception ignored) {
                    // 进程树可能已经被回收
                }
                process.destroyForcibly();
                stopped++;
            }
            games.remove(game.pid());
        }
        if (stopped > 0) {
            SafeLog.info("已强制停止 " + stopped + " 个游戏进程");
        }
        return stopped;
    }

    /** 按实例 ID 强制结束（取消启动、关闭运行中实例时使用）。 */
    public int stopByVersion(String versionId) {
        int stopped = 0;
        for (RunningGame game : list()) {
            if (!game.versionId().equals(versionId)) {
                continue;
            }
            Process process = game.process();
            if (process.isAlive()) {
                intentionalStops.add(game.pid());
                try {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                } catch (Exception ignored) {
                    // 忽略
                }
                process.destroyForcibly();
                stopped++;
            }
            games.remove(game.pid());
        }
        return stopped;
    }

    public boolean stop(long pid) {
        RunningGame game = games.get(pid);
        if (game == null) {
            return false;
        }
        Process process = game.process();
        intentionalStops.add(pid);
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        games.remove(pid);
        return true;
    }
}
