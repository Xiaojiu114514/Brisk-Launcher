package com.brisklauncher.mcl.plugin.api;

import java.nio.file.Path;

/**
 * 插件自己的配置档：{@code data/plugin-data/<id>/config.properties}（UTF-8）。
 *
 * <p>{@link #set(String, String)} 会立刻落盘（先写临时档再替换），不用自己 {@code save()}；
 * 大量写入请自己攒批再 {@link #save()}。
 *
 * <p>要存二进制或结构复杂的资料，就在 {@link PluginContext#pluginDataDir()} 里自己建档。
 */
public interface PluginConfig {

    String get(String key, String fallback);

    int getInt(String key, int fallback);

    long getLong(String key, long fallback);

    boolean getBoolean(String key, boolean fallback);

    /** 写入并落盘；{@code value} 为 {@code null} 表示删除该键。 */
    void set(String key, String value);

    /** 把内存里的内容写回磁碟（一般不需要手动调用）。 */
    void save();

    /** 配置文件路径（还没建立时也会回传预期路径）。 */
    Path file();
}
