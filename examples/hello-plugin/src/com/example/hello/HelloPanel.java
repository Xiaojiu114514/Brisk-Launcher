package com.example.hello;

import com.brisklauncher.mcl.plugin.api.PluginContext;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * 工具页面展示例：跟内建插件一样挂在「工具」页里。
 */
final class HelloPanel {

    private HelloPanel() {
    }

    static Node create(PluginContext context) {
        Label info = new Label(context.messages().t("hello.panel.hint"));
        info.setWrapText(true);

        Button notify = new Button(context.messages().t("hello.panel.notify"));
        notify.setOnAction(event -> context.info(context.messages().t("hello.name"),
                context.messages().t("hello.panel.notified")));

        Button openData = new Button(context.messages().t("hello.panel.open_data"));
        openData.setOnAction(event -> context.openFolder(context.pluginDataDir()));

        VBox root = new VBox(10, info,
                new Label(context.messages().t("hello.panel.version", context.launcherVersion())),
                notify, openData);
        root.setPadding(new Insets(4));
        return root;
    }
}
