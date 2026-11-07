package com.example.hello;

import com.brisklauncher.mcl.plugin.api.LauncherPage;
import com.brisklauncher.mcl.plugin.api.PluginContext;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * 导航页示例：插件可以在顶栏加自己的入口，内容完全自己画。
 */
public final class HelloPage implements LauncherPage {

    private PluginContext context;
    private Label output;
    private Label clicks;

    @Override
    public String id() {
        return "greeting";
    }

    @Override
    public String title() {
        return context == null ? "Hello" : context.messages().t("hello.page.title");
    }

    @Override
    public String icon() {
        return "👋";
    }

    @Override
    public Node createContent(PluginContext context) {
        this.context = context;
        TextField name = new TextField("Steve");
        output = new Label(context.messages().t("hello.page.idle"));
        output.setWrapText(true);

        Button greet = new Button(context.messages().t("hello.page.greet"));
        greet.getStyleClass().add("primary-button");
        greet.setOnAction(event -> output.setText(
                context.messages().t("hello.greeting", name.getText())));

        clicks = new Label(countText());
        Button add = new Button(context.messages().t("hello.page.add"));
        add.setOnAction(event -> {
            int next = context.config().getInt("counter", 0) + 1;
            context.config().set("counter", Integer.toString(next));
            clicks.setText(countText());
            context.services().provide("hello.counter", next);
        });

        VBox root = new VBox(10,
                new Label(context.messages().t("hello.page.subtitle")),
                new HBox(8, name, greet),
                output,
                new HBox(8, add, clicks),
                new Label(context.messages().t("hello.page.environment",
                        context.launcherVersion(), context.locale(), context.pluginDataDir())));
        root.setPadding(new Insets(4));
        return root;
    }

    private String countText() {
        return context.messages().t("hello.page.counter", context.config().getInt("counter", 0));
    }
}
