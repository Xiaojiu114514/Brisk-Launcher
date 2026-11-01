package com.brisklauncher.mcl.security;

import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 远端字符串 → 本地路径的唯一入口（安全审计 H1 的修复核心）。
 *
 * <p>规则：任何来自版本 JSON / 网路 / 档名的字符串，拼进本地路径前都必须过这里。
 * 这里只做「结构」校验（不逃逸、不是绝对路径、不含 NUL），
 * 栏位格式用 {@link #requireId} / {@link #requireHash} / {@link #requireVersion}。
 */
public final class PathSafety {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z._+-]{1,64}");
    /** Windows 保留设备名：写到 NUL/CON 会静默丢弃内容或行为异常（安全审计 L-01）。 */
    private static final Pattern RESERVED_DEVICE =
            Pattern.compile("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?$");

    private PathSafety() {
    }

    /** 把 {@code relative} 解析到 {@code base} 之下；越界、絕對路徑、UNC、NUL 一律拒絕。 */
    public static Path resolveUnder(Path base, String relative) {
        if (base == null) {
            throw new SecurityException("路径基准为空");
        }
        if (relative == null || relative.isBlank() || relative.indexOf('\0') >= 0) {
            throw new SecurityException("路径为空或含非法字符");
        }
        String r = relative.replace('\\', '/').trim();
        if (r.startsWith("/") || r.startsWith("//") || r.matches("^[A-Za-z]:.*")) {
            throw new SecurityException("拒绝绝对路径：" + relative);
        }
        Path normBase = base.toAbsolutePath().normalize();
        Path resolved = normBase.resolve(r).normalize();
        if (!resolved.startsWith(normBase)) {
            throw new SecurityException("路径逃逸：" + relative);
        }
        // 出来的名字不允许再是 ".." 这种残留
        for (Path part : normBase.relativize(resolved)) {
            String name = part.toString();
            if (name.equals("..") || name.equals(".")) {
                throw new SecurityException("路径逃逸：" + relative);
            }
        }
        return resolved;
    }

    /** 只取檔名（不含任何目錄部分），用於外部 API 給的 filename。 */
    public static String safeFileName(String name) {
        if (name == null || name.isBlank() || name.indexOf('\0') >= 0) {
            throw new SecurityException("文件名为空或含非法字符");
        }
        String base = name.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        String file = slash >= 0 ? base.substring(slash + 1) : base;
        if (file.isBlank() || ".".equals(file) || "..".equals(file) || file.matches("^[A-Za-z]:.*")) {
            throw new SecurityException("非法文件名：" + name);
        }
        if (file.chars().anyMatch(c -> "<>:\"|?*".indexOf(c) >= 0)) {
            throw new SecurityException("文件名含 Windows 保留字符：" + name);
        }
        if (RESERVED_DEVICE.matcher(file).matches()) {
            throw new SecurityException("文件名是 Windows 保留设备名：" + name);
        }
        return file;
    }

    /**
     * 版本 id / 资料夹名用的校验：允许空白、括号、加号（例如 {@code 1.21.1-Fabric 0.19.5}），
     * 但禁止路径分隔符、磁碟符、NUL 与 {@code .}/{@code ..}。
     */
    public static String requirePathSegment(String s) {
        if (s == null || s.isBlank() || s.indexOf('\0') >= 0) {
            throw new SecurityException("非法的名称：空或含 NUL");
        }
        String value = s.trim();
        if (value.equals(".") || value.equals("..")) {
            throw new SecurityException("非法的名称：" + s);
        }
        if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0) {
            throw new SecurityException("非法的名称（含路径分隔符）：" + s);
        }
        if (value.matches("^[A-Za-z]:.*")) {
            throw new SecurityException("非法的名称（含磁碟符）：" + s);
        }
        if (value.chars().anyMatch(c -> "<>\"|?*".indexOf(c) >= 0)) {
            throw new SecurityException("非法的名称（含 Windows 保留字元）：" + s);
        }
        return value;
    }

    public static String requireId(String s) {
        return require(ID, s, "id");
    }

    public static String requireHash(String s) {
        // 安全审计 L-01：这里原本有个恒等三元表达式（看起来像特殊处理，其实什么都没做）
        return require(HASH, s, "hash");
    }

    public static String requireVersion(String s) {
        return require(VERSION, s, "version");
    }

    public static boolean isHexSha1(String s) {
        return s != null && s.length() == 40 && s.chars().allMatch(c -> Character.isDigit(c) || (c >= 'a' && c <= 'f'));
    }

    private static String require(Pattern pattern, String value, String label) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new SecurityException("非法的 " + label + "：" + value);
        }
        return value;
    }
}
