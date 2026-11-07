package com.brisklauncher.mcl.ui;

import com.brisklauncher.mcl.plugin.PluginManager;
import com.brisklauncher.mcl.plugin.api.LauncherPage;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * 插件贡献的导航页的外壳：把 {@link LauncherPage} 接到启动器的页面生命周期上。
 *
 * <p>内容只在第一次显示时建立；页面被丢弃（切走就算，除启动页外不缓存）时
 * 调用 {@link LauncherPage#onDispose()}。
 */
public final class PluginPage extends VBox implements MclPage {

    private final PluginManager.Entry entry;
    private final LauncherPage page;
    private boolean built;

    public PluginPage(PluginManager.Entry entry, LauncherPage page) {
        this.entry = entry;
        this.page = page;
        getStyleClass().add("page");
        setPadding(new Insets(18));
        setSpacing(12);
    }

    /** 这个页面在 {@code MclApplication} / 顶栏里用的 key。 */
    public static String keyOf(PluginManager.Entry entry, LauncherPage page) {
        return PluginManager.pageKey(entry.id(), page.id());
    }

    @Override
    public void onShown() {
        if (!built) {
            built = true;
            Node content = null;
            try {
                content = page.createContent(entry.context());
            } catch (Throwable error) {
                content = new Label("⚠ " + entry.name() + "\n" + error);
                content.getStyleClass().add("cat-hint");
            }
            if (content == null) {
                content = new Label(entry.name());
                content.getStyleClass().add("cat-hint");
            }
            if (content instanceof Region region) {
                VBox.setVgrow(region, Priority.ALWAYS);
            }
            getChildren().setAll(content);
        }
        try {
            page.onShown();
        } catch (Throwable ignored) {
            // 插件自己的回调失败不该拖垮页面切换
        }
    }

    @Override
    public void onHidden() {
        try {
            page.onHidden();
        } catch (Throwable ignored) {
            // 同上
        }
    }

    @Override
    public void onReleased() {
        try {
            page.onDispose();
        } catch (Throwable ignored) {
            // 同上
        }
        getChildren().clear();
        built = false;
    }
}
