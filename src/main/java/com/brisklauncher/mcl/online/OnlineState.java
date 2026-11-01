package com.brisklauncher.mcl.online;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 联机房间状态（对应开发方案 4.9.1 的 {@code RoomState}）。
 *
 * <p>进入房间后锁定账号切换：房间内成员名必须是进入时的账号名，
 * 否则服务端会认为是另一个玩家。UI 通过监听器实时刷新顶栏按钮。
 */
public final class OnlineState {

    public enum Phase {
        IDLE,
        CREATING,
        IN_ROOM,
        CLOSING
    }

    private volatile Phase phase = Phase.IDLE;
    private volatile String lockedAccount = "";
    private final List<Consumer<Phase>> listeners = new CopyOnWriteArrayList<>();

    public Phase phase() {
        return phase;
    }

    public boolean locked() {
        return phase == Phase.IN_ROOM || phase == Phase.CREATING;
    }

    public String lockedAccount() {
        return lockedAccount;
    }

    public void enter(Phase newPhase, String accountName) {
        this.phase = newPhase;
        if (newPhase == Phase.IN_ROOM) {
            this.lockedAccount = accountName == null ? "" : accountName;
        }
        notifyListeners();
    }

    public void leave() {
        this.phase = Phase.IDLE;
        this.lockedAccount = "";
        notifyListeners();
    }

    /** @return 取消註冊的動作；UI 重建時必須呼叫，避免舊頂欄被長期持有 */
    public Runnable addListener(Consumer<Phase> listener) {
        listeners.add(listener);
        listener.accept(phase);
        return () -> listeners.remove(listener);
    }

    private void notifyListeners() {
        for (Consumer<Phase> listener : listeners) {
            try {
                listener.accept(phase);
            } catch (Exception ignored) {
                // 单个监听器出错不影响其它监听器
            }
        }
    }
}
