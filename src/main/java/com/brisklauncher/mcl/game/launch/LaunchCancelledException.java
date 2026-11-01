package com.brisklauncher.mcl.game.launch;

/** 用户主动取消启动（不是错误，UI 不应弹失败框）。 */
public class LaunchCancelledException extends RuntimeException {

    public LaunchCancelledException() {
        super("已取消启动");
    }
}
