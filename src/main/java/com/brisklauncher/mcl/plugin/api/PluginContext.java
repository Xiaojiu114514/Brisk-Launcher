package com.brisklauncher.mcl.plugin.api;

import com.brisklauncher.mcl.auth.api.AuthProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.List;

/**
 * 给插件用的受限上下文：启动器愿意开放给插件的全部能力都在这里。
 *
 * <p>刻意只暴露 JDK / JavaFX / Jackson 型别与 {@code auth.api}，不暴露启动器内部类——
 * 外部插件是独立编译的，不该跟启动器版本互相绑死。
 *
 * <p><b>目录约定</b>：
 * <ul>
 *   <li>{@link #dataDir()}：启动器的公开资料目录，**只读**（读缓存、读已安装实例等）；</li>
 *   <li>{@link #pluginDataDir()}：这个插件自己的资料夹（{@code data/plugin-data/<id>}），
 *       可读可写，启动器保证已建好。</li>
 * </ul>
 * 启动器自己的私密目录（账号、密钥、日志）不对插件开放，插件也不应该去猜路径。
 *
 * <p><b>线程约定</b>：除 {@link #runLater(Runnable)} 外，所有方法都可以在任意线程调用；
 * 但 {@code createPanel} / {@code pages()} 的内容构建一定在 JavaFX 执行绪上。
 *
 * <p><b>安全提醒</b>：插件与启动器在同一个行程里，能读写当前 Windows 使用者的文件与网络。
 * 这里的限制是「约定」，不是沙箱。
 */
public interface PluginContext {

    /** 这个上下文的插件 id。 */
    String pluginId();

    /** 启动器公开资料目录（{@code <启动器>/data}）。约定只读。 */
    Path dataDir();

    /** 这个插件自己的资料夹（{@code data/plugin-data/<id>}），可写；启动器已建立。 */
    Path pluginDataDir();

    /** 启动器版本号（例如 {@code 1.0.0}）。 */
    String launcherVersion();

    /** 当前界面语言标识：{@code zh_CN} / {@code zh_TW} / {@code zh_HK} / {@code en_US} / 自订语言包 id。 */
    String locale();

    /** 当前主视窗（可能为 {@code null}）。 */
    Window ownerWindow();

    /** 用系统浏览器打开网址。 */
    void openUrl(String url);

    /** 用资源管理器打开资料夹。 */
    void openFolder(Path path);

    /** 同步 GET 字节（已带 User-Agent、代理与 20 秒超时）。 */
    byte[] httpGet(String url) throws Exception;

    /** 同步 GET 文字（UTF-8）。 */
    String httpGetText(String url) throws Exception;

    /** 可配置的 HTTP 用户端：自定义方法 / 标头 / 超时 / POST body。 */
    PluginHttp http();

    /** JSON 序列化器（与启动器同一份 Jackson，省得插件自带一份）。 */
    ObjectMapper json();

    /** 日志：写进启动器日志，前面自动加 {@code [plugin:id]}。 */
    PluginLog log();

    /** 插件自带的语言包（{@code i18n/messages*.properties}，见开发指南）。 */
    PluginMessages messages();

    /** 插件之间的服务登记处：把自己的实现开放给别的插件，或查找别人的。 */
    PluginServices services();

    /** 这个插件的配置档（{@code data/plugin-data/<id>/config.properties}，自动存盘）。 */
    PluginConfig config();

    /** 换语言时回调（在 FX 执行绪）；回传的 Runnable 用来取消注册。 */
    Runnable addLocaleListener(Runnable listener);

    /** 把一个动作排到 JavaFX 执行绪（后台线程算完后更新界面用）。 */
    void runLater(Runnable action);

    /**
     * 注册一个认证实现（可覆盖同 {@link AuthProvider#type()} 的内建实现）。
     *
     * <p>插件卸载时启动器会自动注销；同一个 id 后注册的优先。
     */
    void registerAuthProvider(AuthProvider provider);

    /** 一个已安装实例的基本资讯。 */
    record InstanceInfo(String id, String mcVersion, Path gameDir) {
    }

    /**
     * 当前主题包的信息（插件想跟启动器配色一致时用）。
     *
     * @param accent 主题宣告的主色（{@code #RRGGBB}，可能是空字串）
     * @param background 主题背景图（没有就 {@code null}）
     */
    record ThemeInfo(String id, String name, boolean dark, String accent, Path background) {
    }

    /** 当前主题包（内建主题是 {@code default}）。 */
    ThemeInfo theme();

    /** 启动器里所有 Java 版实例（给「选择版本」下拉用）。 */
    List<InstanceInfo> instances();

    /**
     * 启动器目前「选中」的实例游戏目录（没有选中时回传 {@code null}）。
     *
     * <p>给「服务器 Mod 一致性检查」这种需要读本地 mods 的插件用；只给目录路径，
     * 插件自己决定要读什么。
     */
    Path selectedInstanceGameDir();

    /** 选中实例的显示名（没有时回传空字串）。 */
    String selectedInstanceLabel();

    /** 提示对话框（非阻塞）。 */
    void info(String title, String message);

    /** 错误对话框（非阻塞）。 */
    void error(String title, String message);

    /** 确认对话框（非阻塞；用户点「确定」才执行 {@code onConfirm}）。 */
    void confirm(String title, String message, Runnable onConfirm);
}
