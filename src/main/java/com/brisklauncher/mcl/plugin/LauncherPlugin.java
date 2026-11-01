package com.brisklauncher.mcl.plugin;

import javafx.scene.Node;

/**
 * 启动器插件接口（「工具」页面的每一个小工具都是一个插件）。
 *
 * <p>插件分两种：
 * <ol>
 *   <li><b>内建</b>：直接编在启动器里（RGB 文字、服务器 Ping、正版皮肤查询…）；</li>
 *   <li><b>外部</b>：放在 {@code <启动器资料>/plugins/*.jar}，jar 里有
 *       {@code plugin.json} 指定 id / 名称 / 实现类。</li>
 * </ol>
 *
 * <p>外部插件只依赖本接口与 {@link PluginContext}（都是 JDK + JavaFX 型别），
 * 不需要、也拿不到启动器内部类，避免插件和启动器版本互相绑死。
 */
public interface LauncherPlugin {

    /** 唯一 id（外部插件用 {@code plugin.json} 裡的同名欄位）。 */
    String id();

    /** 顯示名稱（中文）。 */
    String name();

    /** 一句話說明。 */
    String description();

    /** 列表裡的小圖示（emoji）。 */
    default String icon() {
        return "🧩";
    }

    /**
     * 建立工具面板。每次切到该工具时呼叫一次，{@link #dispose()} 时丢弃。
     *
     * @param context 启动器提供的受限上下文（资料目录、HTTP、打开资料夹…）
     */
    Node createPanel(PluginContext context);

    /** 面板被丟棄時釋放資源（線程、圖片、監聽器）。 */
    default void dispose() {
    }
}
