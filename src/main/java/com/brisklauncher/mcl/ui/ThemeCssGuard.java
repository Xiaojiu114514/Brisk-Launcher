package com.brisklauncher.mcl.ui;

import com.brisklauncher.mcl.security.SafeLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 第三方主题包（{@code data/themes/<id>/*.css}）的静态检查（安全审计 v3 A-14）。
 *
 * <p>为什么要检查：JavaFX CSS 的 {@code url()} 支援 {@code http}/{@code https}/{@code file}/{@code jar}。
 * 一个「主题包」是玩家从群里下载、解压到 {@code data/themes/} 的档案 —— 于是它可以：
 * <ul>
 *   <li>让启动器向任意 URL 发请求（隐私信标：IP、启动时间、频次）；</li>
 *   <li>用 {@code file:///C:/…} 探测本机档案是否存在；</li>
 *   <li>用 {@code @font-face} 把远端二进位喂给字体解析器。</li>
 * </ul>
 *
 * <p>规则：只允许 {@code data:} 与「同目录相对路径」；拒绝 http/https/file/jar/协议相对；
 * 拒绝 {@code @font-face}。检查失败就**不加载**并记 WARNING（不弹窗，避免每次启动都打扰）。
 *
 * <p>一个主题包可以有多个 CSS（见 {@code theme.json} 的 {@code styles}），逐个检查。
 */
public final class ThemeCssGuard {

    private static final Pattern URL = Pattern.compile(
            "url\\(\\s*['\"]?([^'\")]*)", Pattern.CASE_INSENSITIVE);
    /** 單檔上限：正常主題只有幾 KB 到幾十 KB。 */
    private static final long MAX_CSS_BYTES = 2L * 1024 * 1024;

    private ThemeCssGuard() {
    }

    /** @return {@code true} 表示可以載入 */
    public static boolean allowed(Path css) {
        try {
            if (css == null || !Files.isRegularFile(css)) {
                return false;
            }
            if (Files.size(css) > MAX_CSS_BYTES) {
                SafeLog.warn("[theme] 主题 CSS 过大，已拒绝载入：" + css.getFileName());
                return false;
            }
            String text = Files.readString(css, StandardCharsets.UTF_8);
            String lower = text.toLowerCase(Locale.ROOT);
            if (lower.contains("@font-face")) {
                SafeLog.warn("[theme] 主题包不允许 @font-face，已拒绝载入：" + css.getFileName());
                return false;
            }
            Matcher matcher = URL.matcher(text);
            while (matcher.find()) {
                String target = matcher.group(1) == null ? "" : matcher.group(1).trim();
                String value = target.toLowerCase(Locale.ROOT);
                if (value.startsWith("http:") || value.startsWith("https:")
                        || value.startsWith("file:") || value.startsWith("jar:")
                        || value.startsWith("//")) {
                    SafeLog.warn("[theme] 主题包引用了外部资源（" + target
                            + "），已拒绝载入：" + css.getFileName());
                    return false;
                }
            }
            return true;
        } catch (IOException error) {
            SafeLog.warn("[theme] 读取主题 CSS 失败：" + error.getClass().getSimpleName());
            return false;
        }
    }
}
