package com.brisklauncher.mcl.plugin.api;

import java.util.List;

/**
 * 插件自带的语言包。
 *
 * <p>jar 里放 {@code i18n/messages.properties}（默认）与
 * {@code i18n/messages_zh_CN.properties} / {@code messages_zh_TW.properties} 等，
 * 键名自订（建议加插件前缀，例如 {@code myplugin.title=…}）。
 *
 * <p>查找顺序：当前语言（{@code zh_TW}）→ 同语言的主方言（{@code zh}）→ {@code en_US} →
 * 默认 {@code i18n/messages.properties} → 回传键名本身。缺键不会喷 {@code !key!}。
 */
public interface PluginMessages {

    /** 当前语言下的文字；没有就按上面的顺序回退。 */
    String t(String key);

    /** 带 {@code MessageFormat} 参数（{@code {0}}、{@code {1}}…）。 */
    String t(String key, Object... args);

    /** 这个键在当前语言包里存在吗（用来判断要不要自己兜底）。 */
    boolean has(String key);

    /** 这个插件一共提供了哪些语言（按文件扫描，例如 {@code [zh_CN, zh_TW, en_US]}）。 */
    List<String> locales();
}
