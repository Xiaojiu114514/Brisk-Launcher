package com.brisklauncher.mcl.game.install;

/** 安装/补全失败。消息已经是可直接展示的安全文本。 */
public class InstallException extends RuntimeException {

    public InstallException(String message) {
        super(message);
    }

    public InstallException(String message, Throwable cause) {
        super(message, cause);
    }
}
