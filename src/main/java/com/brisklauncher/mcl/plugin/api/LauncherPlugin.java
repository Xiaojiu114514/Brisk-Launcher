package com.brisklauncher.mcl.plugin.api;

import javafx.scene.Node;

import java.util.List;

/**
 * 启动器插件接口（稳定 API，破坏性变更只在主版本内发生）。
 *
 * <p>插件分两种：
 * <ol>
 *   <li><b>内建</b>：直接编在启动器里（RGB 文字、服务器 Ping、正版皮肤查询……）；</li>
 *   <li><b>外部</b>：放在 {@code <启动器资料>/plugins/*.jar}，jar 根目录的 {@code plugin.json}
 *       指定 id / 名称 / 实现类。</li>
 * </ol>
 *
 * <p>两种插件走同一套接口。外部插件只依赖本包（{@code com.brisklauncher.mcl.plugin.api}）
 * 与 JDK / JavaFX 类型，不需要、也不该引用启动器内部类。
 *
 * <p><b>生命周期</b>（外部插件）：
 * <pre>
 *   扫描 jar → plugin.json → 禁用/信任检查 → 实例化（public 无参构造器）
 *     → onLoad(PluginContext)          // 每个实例一次，可能不在 FX 线程
 *     → createPanel(PluginContext)     // 只在用户点开这个工具时调用，一定在 FX 线程
 *     → onUnload()                     // 插件被移除 / 刷新 / 启动器退出
 * </pre>
 *
 * <p><b>能力</b>：{@link #createPanel(PluginContext)} 提供一个工具面板（工具页），
 * {@link #pages()} 提供任意数量的导航页。两者都可以不提供。
 */
public interface LauncherPlugin {

    /** 唯一 id：只允许 {@code [A-Za-z0-9._-]}，会用来命名插件资料夹。 */
    String id();

    /** 显示名称（当前界面语言）。想跟随语言变化就重写 {@link PluginContext#messages()} 的键。 */
    String name();

    /** 一句话说明（工具页列表里显示）。 */
    default String description() {
        return "";
    }

    /** 列表里的小图标（emoji）。 */
    default String icon() {
        return "🧩";
    }

    /** 插件版本号；不重写时用 {@code plugin.json} 的 {@code version}。 */
    default String version() {
        return "";
    }

    /**
     * 载入回调：插件被成功载入后调用一次。
     *
     * <p>适合做「读取配置、注册认证实现、声明服务」这类一次性初始化；
     * 不要在这里长时间阻塞（它会拖慢启动），也不要在非 FX 线程碰界面。
     */
    default void onLoad(PluginContext context) {
    }

    /**
     * 贡献导航页（顶栏入口）。
     *
     * <p>页面的 {@code id()} 会加上插件 id 前缀，避免两个插件撞名。
     */
    default List<LauncherPage> pages() {
        return List.of();
    }

    /**
     * 创建工具面板。每次面板被丢弃后重新打开都会调用一次，一定在 FX 执行绪。
     *
     * <p>返回 {@code null} 表示「这个插件不提供工具面板」（只用导航页的插件可以这样）。
     *
     * @param context 启动器提供的受限上下文（资料目录、HTTP、通知、插件服务……）
     */
    default Node createPanel(PluginContext context) {
        return null;
    }

    /**
     * 卸载回调：插件被移除 / 刷新 / 启动器退出时调用。
     *
     * <p>必须释放自己开的执行绪、定时器、文件句柄与全局监听器。
     */
    default void onUnload() {
    }
}
