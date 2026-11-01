package com.brisklauncher.mcl.modpack;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * mrpack 路径安全校验（安全红线）。
 * 同一份逻辑同时用于：导出前自检 + 导入前逐条校验。
 */
public final class MrpackPathValidator {

    private MrpackPathValidator() {
    }

    public static void validate(String path) throws SecurityException {
        if (path == null || path.isBlank()) {
            throw new SecurityException("空路径");
        }
        String normalized = path.replace('\\', '/');

        if (normalized.indexOf('\0') >= 0) {
            throw new SecurityException("非法字符 NUL");
        }
        // 绝对路径 / UNC
        if (normalized.startsWith("/")) {
            throw new SecurityException("拒绝绝对路径: " + path);
        }
        // 盘符绝对或盘符相对 (C:\ 或 C:foo)
        if (normalized.matches("^[A-Za-z]:.*")) {
            throw new SecurityException("拒绝盘符路径: " + path);
        }
        // 目录穿越（Paths.get 对 a:b 会抛 InvalidPathException，统一转成 SecurityException）
        Path path2;
        try {
            path2 = Paths.get(normalized).normalize();
        } catch (java.nio.file.InvalidPathException illegal) {
            throw new SecurityException("非法路径: " + path, illegal);
        }
        if (path2.startsWith("..")) {
            throw new SecurityException("拒绝目录穿越: " + path);
        }
        if (path2.isAbsolute()) {
            throw new SecurityException("拒绝绝对路径: " + path);
        }
    }

    /**
     * 解析并二次确认结果仍位于 root 之内（防止规范化后逃逸）。
     */
    public static Path resolveSafe(Path root, String relative) {
        validate(relative);
        Path base = root.toAbsolutePath().normalize();
        Path resolved = base.resolve(relative.replace('\\', '/')).normalize();
        if (!resolved.startsWith(base)) {
            throw new SecurityException("路径逃逸: " + relative);
        }
        return resolved;
    }
}