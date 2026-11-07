package com.brisklauncher.mcl.ui;

import com.brisklauncher.mcl.MclContext;
import com.brisklauncher.mcl.game.GameInstance;
import com.brisklauncher.mcl.i18n.I18n;
import com.brisklauncher.mcl.modpack.ServerPackExporter;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Path;

/**
 * 导出服务端整合包：先分析哪些 mod 能上服务端，再勾选要打包的东西。
 *
 * <p>版面对齐使用者要求：「快捷分离客户端 mod 并导出可直接作为服务端使用的 mod 包，
 * 或加上服务端核心 / eula / server.properties / start.bat(.sh) 的可直接用整合包」。
 */
public final class ServerPackDialog {

    private ServerPackDialog() {
    }

    public static void show(Window owner, MclContext context, GameInstance entry) {
        Stage dialog = new Stage();
        dialog.initOwner(owner);
        dialog.initModality(Modality.NONE);
        dialog.initStyle(StageStyle.TRANSPARENT);
        dialog.setTitle(I18n.t("serverpack.title"));

        Label title = new Label("🖥 " + I18n.t("serverpack.title") + " · " + entry.id());
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: -brisk-text;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = UiKit.closeButtonStyled();
        close.setOnAction(e -> dialog.close());
        HBox header = new HBox(8, title, spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        Label status = new Label(I18n.t("serverpack.analysing"));
        status.getStyleClass().add("cat-hint");
        status.setWrapText(true);

        VBox excludedBox = new VBox(3);
        ScrollPane excludedScroll = new ScrollPane(excludedBox);
        excludedScroll.setFitToWidth(true);
        excludedScroll.getStyleClass().add("edge-to-edge");
        excludedScroll.setPrefViewportHeight(140);
        // 让「已剔除」清单吃掉多余高度：弹窗底部不留一大片空白
        VBox.setVgrow(excludedScroll, Priority.ALWAYS);
        Label excludedTitle = new Label(I18n.t("serverpack.excluded_title"));
        excludedTitle.getStyleClass().add("section-title");

        // ---- 选项 ----
        int defaultMemory = context.settings().defaultMemoryMb();
        ServerPackExporter.Options defaults = ServerPackExporter.Options.defaults(defaultMemory);
        CheckBox config = new CheckBox(I18n.t("serverpack.opt.config"));
        config.setSelected(defaults.includeConfig());
        CheckBox defaultConfigs = new CheckBox(I18n.t("serverpack.opt.defaultconfigs"));
        defaultConfigs.setSelected(defaults.includeDefaultConfigs());
        CheckBox eula = new CheckBox(I18n.t("serverpack.opt.eula"));
        eula.setSelected(defaults.includeEula());
        CheckBox properties = new CheckBox(I18n.t("serverpack.opt.properties"));
        properties.setSelected(defaults.includeServerProperties());
        CheckBox core = new CheckBox(I18n.t("serverpack.opt.core"));
        core.setSelected(defaults.includeCore());
        CheckBox keepUnknown = new CheckBox(I18n.t("serverpack.opt.keep_unknown"));
        keepUnknown.setSelected(defaults.keepUnknown());
        FlowPane toggles = new FlowPane(14, 8, config, defaultConfigs, eula, properties, core,
                keepUnknown);

        Spinner<Integer> memory = new Spinner<>(512, 65536, defaults.memoryMb(), 512);
        memory.setEditable(true);
        memory.setPrefWidth(130);
        TextField port = new TextField(String.valueOf(defaults.port()));
        port.setPrefWidth(90);
        TextField motd = new TextField(defaults.motd());
        HBox.setHgrow(motd, Priority.ALWAYS);
        Spinner<Integer> maxPlayers = new Spinner<>(1, 1000, defaults.maxPlayers());
        maxPlayers.setPrefWidth(100);
        CheckBox onlineMode = new CheckBox(I18n.t("serverpack.opt.online_mode"));
        onlineMode.setSelected(defaults.onlineMode());

        HBox rowMemory = new HBox(8, new Label(I18n.t("serverpack.memory")), memory,
                new Label(I18n.t("serverpack.port")), port,
                new Label(I18n.t("serverpack.max_players")), maxPlayers);
        rowMemory.setAlignment(Pos.CENTER_LEFT);
        HBox rowMotd = new HBox(8, new Label(I18n.t("serverpack.motd")), motd);
        rowMotd.setAlignment(Pos.CENTER_LEFT);
        HBox rowOnline = new HBox(8, onlineMode);
        rowOnline.setAlignment(Pos.CENTER_LEFT);

        Label hint = new Label(I18n.t("serverpack.hint"));
        hint.getStyleClass().add("cat-hint");
        hint.setWrapText(true);

        // ---- 底部 ----
        Button analyse = new Button(I18n.t("serverpack.reanalysing"));
        Button export = new Button(I18n.t("serverpack.export"));
        export.getStyleClass().add("primary-button");
        export.setDisable(true);
        ServerPackExporter.Analysis[] analysisHolder = new ServerPackExporter.Analysis[1];

        Runnable runAnalysis = () -> {
            status.setText(I18n.t("serverpack.analysing"));
            excludedBox.getChildren().clear();
            export.setDisable(true);
            Thread.ofVirtual().name("mcl-serverpack-analyse").start(() -> {
                try {
                    ServerPackExporter.Analysis analysis = ServerPackExporter.analyze(context, entry,
                            name -> Platform.runLater(() ->
                                    status.setText(I18n.t("serverpack.analysing_file", name))));
                    Platform.runLater(() -> {
                        analysisHolder[0] = analysis;
                        status.setText(I18n.t("serverpack.analysed", analysis.mods().size(),
                                analysis.kept().size(), analysis.excluded().size(),
                                analysis.unknownCount()));
                        for (ServerPackExporter.ModVerdict verdict : analysis.excluded()) {
                            Label line = new Label("• " + verdict.fileName()
                                    + (verdict.title() == null || verdict.title().isBlank()
                                    ? "" : "（" + verdict.title() + "）"));
                            line.getStyleClass().add("cat-hint");
                            line.setWrapText(true);
                            excludedBox.getChildren().add(line);
                        }
                        if (analysis.excluded().isEmpty()) {
                            excludedBox.getChildren().add(UiKit.catHint(I18n.t("serverpack.no_excluded")));
                        }
                        export.setDisable(false);
                    });
                } catch (Throwable error) {
                    Platform.runLater(() -> status.setText(I18n.t("serverpack.analyse_failed",
                            String.valueOf(error.getMessage()))));
                }
            });
        };
        analyse.setOnAction(e -> runAnalysis.run());

        export.setOnAction(e -> {
            ServerPackExporter.Analysis analysis = analysisHolder[0];
            if (analysis == null) {
                return;
            }
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.t("serverpack.export"));
            chooser.setInitialFileName(entry.id() + "-server.zip");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Zip", "*.zip"));
            File target = chooser.showSaveDialog(dialog);
            if (target == null) {
                return;
            }
            int memoryValue = memory.getValue() == null ? defaults.memoryMb() : memory.getValue();
            int portValue = parsePort(port.getText(), defaults.port());
            int maxValue = maxPlayers.getValue() == null ? defaults.maxPlayers() : maxPlayers.getValue();
            ServerPackExporter.Options options = new ServerPackExporter.Options(
                    config.isSelected(), defaultConfigs.isSelected(), eula.isSelected(),
                    properties.isSelected(), core.isSelected(), keepUnknown.isSelected(),
                    memoryValue, portValue, motd.getText(), maxValue, onlineMode.isSelected());
            Path zip = target.toPath();
            TaskDialog.run(dialog, I18n.t("serverpack.exporting"), progress ->
                            ServerPackExporter.export(context, entry, analysis, options, zip, progress),
                    result -> {
                        MCLDialog.info(dialog, I18n.t("serverpack.export"),
                                I18n.t("serverpack.exported", result.toString()));
                        DesktopActions.openFolder(result.getParent());
                    });
        });

        HBox footer = new HBox(8, analyse, spacer, export);
        footer.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, header, status, excludedTitle, excludedScroll, toggles,
                rowMemory, rowMotd, rowOnline, hint, footer);
        root.setPadding(new Insets(16));
        root.setStyle("-fx-background-color: -brisk-bg; -fx-background-radius: 12;"
                + " -fx-border-color: -brisk-border; -fx-border-radius: 12;"
                + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 15, 0, 0, 5);");

        Scene scene = new Scene(root, 780, 660);
        UiKit.applyTheme(scene);
        scene.setFill(Color.TRANSPARENT);
        dialog.setScene(scene);
        UiKit.escToClose(scene, dialog);
        UiKit.makeDraggable(dialog, header);
        UiKit.popIn(dialog, root);
        dialog.show();
        runAnalysis.run();
    }

    private static int parsePort(String text, int fallback) {
        try {
            int value = Integer.parseInt(text.trim());
            return value < 1 || value > 65535 ? fallback : value;
        } catch (Exception error) {
            return fallback;
        }
    }

    /** 開發期用：直接導出到指定 zip，不開界面（回歸驗證）。 */
    public static Path exportDirect(MclContext context, GameInstance entry,
                                    ServerPackExporter.Options options, Path target,
                                    boolean autoAnalyse) throws Exception {
        ServerPackExporter.Analysis analysis = ServerPackExporter.analyze(context, entry, name -> { });
        return ServerPackExporter.export(context, entry, analysis, options, target, progress -> { });
    }
}
