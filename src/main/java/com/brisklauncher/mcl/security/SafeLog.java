package com.brisklauncher.mcl.security;

import com.brisklauncher.mcl.auth.api.AccountType;

import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * 全局日志入口 —— 安全红线第 1 条。
 *
 * 设计原则：
 *  1. 只提供字符串入参的少数几个方法，不提供 Object/Throwable 重载，
 *     从 API 层面杜绝“把整个对象 toString() 打出来”。
 *  2. 二次防护：消息中若出现疑似凭据关键字，整条消息替换为占位符，
 *     绝不做“打码输出”，因为打码后的内容同样违规。
 *  3. 账号相关只允许记录三类事件：尝试登录 / 登录成功 / 令牌刷新。
 */
public final class SafeLog {

    private static final Logger LOGGER = Logger.getLogger("MCL");

    static {
        // 同时写一份档案：使用者回报问题时可以直接把 <data>/logs/launcher.log 给我们。
        // 安全审计 H-02/M-01：日志里有玩家名、实例路径、服务器地址，
        // 现在写到私密目录（%LOCALAPPDATA%\BriskLauncher\logs），并收紧权限。
        try {
            java.nio.file.Path dir = com.brisklauncher.mcl.AppPaths.logsDir();
            java.nio.file.Files.createDirectories(dir);
            com.brisklauncher.mcl.security.FilePerms.ensurePrivate(dir);
            java.util.logging.FileHandler handler = new java.util.logging.FileHandler(
                    dir.resolve("launcher.log").toString(), 4 * 1024 * 1024, 2, true);
            handler.setFormatter(new java.util.logging.SimpleFormatter());
            handler.setLevel(java.util.logging.Level.ALL);
            LOGGER.addHandler(handler);
            LOGGER.setUseParentHandlers(true);
        } catch (Exception ignored) {
            // 写不了档就只输出主控台
        }
    }

    /** 只做拦截，不做打码输出。 */
    private static final Pattern CREDENTIAL_LIKE = Pattern.compile(
            "(?i)(access[_-]?token|refresh[_-]?token|client[_-]?secret|" +
            "authorization\\s*[:=]|bearer\\s+\\S{8,}|password\\s*[:=])");

    private SafeLog() {
    }

    public static void info(String message) {
        LOGGER.info(() -> guard(message));
    }

    public static void warn(String message) {
        LOGGER.warning(() -> guard(message));
    }

    public static void error(String message) {
        LOGGER.log(Level.SEVERE, () -> guard(message));
    }

    // ---------- 账号相关唯一允许的三种事件 ----------

    public static void loginAttempt(AccountType type) {
        LOGGER.info(() -> "登录尝试: " + type);
    }

    public static void loginSuccess(AccountType type) {
        LOGGER.info(() -> "登录成功: " + type);
    }

    public static void tokenRefresh(AccountType type) {
        LOGGER.info(() -> "令牌刷新: " + type);
    }

    /**
     * 供其它模块在“把文本回显给用户/写入日志”之前调用。
     * 与 {@link #guard(String)} 的区别：这里返回的是可展示文本，命中红线时整段替换而不是抛出。
     */
    public static String sanitize(String message) {
        return guard(message);
    }

    private static String guard(String message) {
        if (message == null) {
            return "";
        }
        if (CREDENTIAL_LIKE.matcher(message).find()) {
            return "[已阻止：日志消息疑似包含凭据内容]";
        }
        return message;
    }
}