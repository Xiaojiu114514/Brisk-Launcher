package com.brisklauncher.mcl.plugin.api;

/**
 * 插件日志：写进启动器的 {@code launcher.log}，每行自动带 {@code [plugin:<id>]} 前缀。
 *
 * <p>不要用它记录令牌、密码、Cookie 或玩家个资。
 */
public interface PluginLog {

    void info(String message);

    void warn(String message);

    /** 记录例外（含堆栈）。 */
    void error(String message, Throwable error);
}
