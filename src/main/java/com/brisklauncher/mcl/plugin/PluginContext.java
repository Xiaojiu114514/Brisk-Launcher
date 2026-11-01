package com.brisklauncher.mcl.plugin;

import javafx.stage.Window;

import java.nio.file.Path;

/**
 * 给插件用的受限上下文。
 *
 * <p>刻意只暴露 JDK / JavaFX 型别：外部插件的 jar 是独立编译的，不应该依赖启动器内部类。
 * 需要新增能力时在这里加方法，而不是把 {@code MclContext} 直接丢出去。
 */
public interface PluginContext {

    /** 啟動器資料目錄（{@code <啟動器>/data}，快取/日誌/下載都放這）。 */
    Path dataDir();

    /** 啟動器設定根目錄（launcher.json / accounts.json 所在）。 */
    Path launcherRoot();

    /** 啟動器版本號。 */
    String launcherVersion();

    /** 當前主視窗（可能為 null）。 */
    Window ownerWindow();

    /** 用系統瀏覽器打開網址。 */
    void openUrl(String url);

    /** 用資源管理器打開資料夾。 */
    void openFolder(Path path);

    /** 同步 GET 位元組（已帶 User-Agent / 代理設定）。 */
    byte[] httpGet(String url) throws Exception;

    /** 同步 GET 文字（UTF-8）。 */
    String httpGetText(String url) throws Exception;

    /**
     * 启动器目前「选中的实例」的游戏目录（没有选中时回传 null）。
     *
     * <p>给「伺服器 Mod 一致性检查」这种需要读本地 mods 的插件用；
     * 只给目录路径，插件自己决定要读什么。
     */
    Path selectedInstanceGameDir();

    /** 選中實例的顯示名（沒有時回傳空字串），給界面標題用。 */
    String selectedInstanceLabel();

    /** 一個已安裝實例的基本資訊（插件用來找該版本的目錄，例如 schematics 文件夾）。 */
    record InstanceInfo(String id, String mcVersion, Path gameDir) {
    }

    /** 啟動器裡所有 Java 版實例（給「選擇版本」下拉用）。 */
    java.util.List<InstanceInfo> instances();

    /** 提示對話框（非阻塞）。 */
    void info(String title, String message);

    /** 錯誤對話框（非阻塞）。 */
    void error(String title, String message);
}
