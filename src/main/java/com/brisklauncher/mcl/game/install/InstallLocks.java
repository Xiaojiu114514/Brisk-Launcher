package com.brisklauncher.mcl.game.install;

import com.brisklauncher.mcl.security.SafeLog;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 正在安装/修复的版本登记表。
 *
 * <p>为什么需要：安装进度框如果没有把主窗口锁住，玩家可以在安装途中
 * 打开「版本设置 → 删除」把正在写入的版本删掉——轻则安装失败，重则留下半个版本
 * （使用者回报的「正在安装的版本可以被删掉」）。
 *
 * <p>现在做两层防护：①进度框改成应用程式模态（看得到、点不到）；
 * ②即使有别的路径进来删除，也会被这里挡下并给出提示。
 */
public final class InstallLocks {

    private static final Map<String, String> ACTIVE = new ConcurrentHashMap<>();

    private InstallLocks() {
    }

    /**
     * 标记某个版本开始安装。
     *
     * @param key      版本 key（{@link #key}）
     * @param progressKey 显示用（例如「安装 1.21.1」）
     * @return 收尾用的 Runnable（务必在 finally 里呼叫）
     */
    public static Runnable begin(String key, String progressKey) {
        if (key == null || key.isBlank()) {
            return () -> {
            };
        }
        ACTIVE.put(key, progressKey == null ? "" : progressKey);
        SafeLog.info("[install] 锁定版本：" + key);
        return () -> {
            ACTIVE.remove(key);
            SafeLog.info("[install] 解除版本锁：" + key);
        };
    }

    /** 這個版本是不是正在被安裝/修復。 */
    public static boolean isActive(String key) {
        return key != null && ACTIVE.containsKey(key);
    }

    /** 有沒有任何安裝在跑（給「刪除整個資料夾」之類的操作判斷）。 */
    public static boolean anyActive() {
        return !ACTIVE.isEmpty();
    }

    /** 註冊/查詢用的 key：根目錄 + 版本資料夾名（不同根目錄可能有同名版本）。 */
    public static String key(String rootPath, String id) {
        return (rootPath == null ? "" : rootPath) + "|" + (id == null ? "" : id);
    }
}
