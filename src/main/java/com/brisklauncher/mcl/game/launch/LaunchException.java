package com.brisklauncher.mcl.game.launch;

/** 启动链路异常。消息可直接展示给用户，不包含令牌或完整命令行。 */
public class LaunchException extends RuntimeException {

    public LaunchException(String message) {
        super(message);
    }

    public LaunchException(String message, Throwable cause) {
        super(message, cause);
    }
}
