package com.example.hello;

import com.brisklauncher.mcl.plugin.api.LauncherPage;
import com.brisklauncher.mcl.plugin.api.LauncherPlugin;
import com.brisklauncher.mcl.plugin.api.PluginContext;
import javafx.scene.Node;

import java.util.List;

/**
 * 示例插件：一次示范工具面板、导航页、自订配置、语言包与插件服务。
 *
 * <p>编译时只依赖 {@code com.brisklauncher.mcl.plugin.api} 与 JavaFX，
 * 不引用启动器内部类。
 */
public final class HelloPlugin implements LauncherPlugin {

    private PluginContext context;

    @Override
    public String id() {
        return "hello-plugin";
    }

    @Override
    public String name() {
        // name() 会在插件载入时与切换语言时被调用，所以这里可以直接用语言包
        return context == null ? "Hello Plugin" : context.messages().t("hello.name");
    }

    @Override
    public String description() {
        return context == null ? "" : context.messages().t("hello.desc");
    }

    @Override
    public String icon() {
        return "👋";
    }

    @Override
    public void onLoad(PluginContext context) {
        this.context = context;
        // 插件自己的资料夹：data/plugin-data/hello-plugin/
        context.log().info("Hello Plugin 已载入，资料夹：" + context.pluginDataDir());
        context.config().set("loaded_at", Long.toString(System.currentTimeMillis()));
        // 开放一个服务给别的插件用；名字加前缀避免撞车
        context.services().provide("hello.greeting", (java.util.function.Function<String, String>)
                who -> context.messages().t("hello.greeting", who));
    }

    @Override
    public List<LauncherPage> pages() {
        return List.of(new HelloPage());
    }

    @Override
    public Node createPanel(PluginContext context) {
        this.context = context;
        return HelloPanel.create(context);
    }

    @Override
    public void onUnload() {
        if (context != null) {
            context.services().provide("hello.greeting", null);
            context.config().set("unloaded_at", Long.toString(System.currentTimeMillis()));
            context.log().info("Hello Plugin 已卸载");
        }
        context = null;
    }
}
