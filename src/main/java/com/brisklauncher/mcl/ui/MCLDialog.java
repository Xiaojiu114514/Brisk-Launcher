package com.brisklauncher.mcl.ui;

import com.brisklauncher.mcl.crash.CrashAnalysis;
import com.brisklauncher.mcl.i18n.I18n;
import com.brisklauncher.mcl.security.SafeLog;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/**
 * 统一弹窗：提示 / 错误 / 二次确认 / 崩溃分析。
 * 所有文本都可一键复制，方便玩家去社区求助。
 */
public final class MCLDialog {

    private MCLDialog() {
    }

    public static void info(Window owner, String title, String message) {
        show(owner, "✦ " + title, message, false, null, null, null);
    }

    public static void error(Window owner, String title, String message) {
        show(owner, "❌ " + title, message, true, null, null, null);
    }

    /**
     * 阻塞式确认：回传使用者是否按了「确定」（供非 FX 执行绪使用）。
     *
     * <p>踩过的坑：原本用 {@code Platform.runLater(onConfirm)} 回写结果，而等待执行绪在
     * {@code showAndWait()} 返回后就立刻 countDown，那时 onConfirm 还没被执行 →
     * 使用者明明按了「确定」，呼叫端却永远拿到 false（「选择继续下载还是被拒绝」的真因）。
     * 现在结果在按钮事件里**同步**写入 holder，不再依赖排队的回呼。
     */
    public static boolean confirmBlocking(javafx.stage.Window owner, String title, String message) {
        final boolean[] ok = {false};
        // 安装时进度框是「应用程式模态」，如果这个确认框没有挂在它下面，
        // 就会被模态框挡住点不到（使用者会觉得安装卡住了）。挂上去就正常显示。
        final javafx.stage.Window actualOwner = owner != null ? owner : activeModalOwner();
        Runnable task = () -> show(actualOwner, "✦ " + title, message, false, null, null, null, ok);
        if (javafx.application.Platform.isFxApplicationThread()) {
            task.run();
            return ok[0];
        }
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        javafx.application.Platform.runLater(() -> {
            try {
                task.run();
            } catch (Throwable error) {
                SafeLog.warn("确认框显示失败：" + error.getClass().getSimpleName());
            } finally {
                latch.countDown();
            }
        });
        try {
            // 由使用者决定要等多久；这里只做安全上限（10 分钟），中断时视为取消
            latch.await(10, java.util.concurrent.TimeUnit.MINUTES);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return ok[0];
    }

    public static void confirm(Window owner, String title, String message, Runnable onConfirm) {
        show(owner, "✦ " + title, message, false, onConfirm, null, null);
    }

    /**
     * 崩溃分析弹窗：参考 PCL 的版式——标题 + 原因 + 建议 + 其余可能 + 疑似 Mod + 可折叠日志 + 操作按钮。
     */
    public static void crash(Window owner, CrashAnalysis analysis, Runnable onExport) {
        showCrash(owner, "💥 " + I18n.t("crash.title"),
                analysis == null ? I18n.t("crash.no_analysis") : null, analysis, onExport);
    }

    public static void launchFailed(Window owner, String message, CrashAnalysis analysis, Runnable onExport) {
        showCrash(owner, "❌ " + I18n.t("crash.launch_failed"), message, analysis, onExport);
    }

    private static void showCrash(Window owner, String title, String message,
                                  CrashAnalysis analysis, Runnable onExport) {
        Stage dialog = themedStage(owner);

        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #14453F;");

        VBox body = new VBox(8);
        body.setMaxWidth(620);



        String summary = analysis == null ? "" : analysis.toDisplayText();
        if (analysis != null && analysis.hasMatches()) {
            CrashAnalysis.Match first = analysis.matches().get(0);
            // 原因（最可能的一条单独突出）
            Label causeTitle = new Label("🩺 " + first.rule().category());
            causeTitle.setStyle("-fx-font-weight: bold; -fx-text-fill: #14453F;");
            Label cause = new Label(first.rule().explanation());
            cause.setWrapText(true);
            cause.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #0B6E63;");
            body.getChildren().addAll(causeTitle, cause);

            // 建议
            Label fixTitle = new Label("🛠 建议解决办法");
            fixTitle.setStyle("-fx-font-weight: bold; -fx-text-fill: #14453F;");
            Label fix = new Label(first.rule().suggestion());
            fix.setWrapText(true);
            fix.setStyle("-fx-text-fill: #4A6A66;");
            body.getChildren().addAll(fixTitle, fix);

            // 日志里抓到的「缺少的前置 Mod」：直接列名字，玩家照著去下载
            if (analysis.missingMods() != null && !analysis.missingMods().isEmpty()) {
                Label missingTitle = new Label(I18n.t("crash.missing_mods_title"));
                missingTitle.setStyle("-fx-font-weight: bold; -fx-text-fill: #0B6E63;");
                Label missing = new Label(String.join("、", analysis.missingMods()));
                missing.setWrapText(true);
                missing.setStyle("-fx-text-fill: #C0392B; -fx-font-size: 13px;"
                        + " -fx-font-weight: bold;");
                javafx.scene.layout.HBox missingRow = new javafx.scene.layout.HBox(8, missing);
                missingRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                for (String modId : analysis.missingMods()) {
                    javafx.scene.control.Button search = new javafx.scene.control.Button(
                            "🔎 " + modId);
                    search.getStyleClass().add("pill-toggle");
                    search.setOnAction(e -> {
                        dialog.close();
                        com.brisklauncher.mcl.MclApplication.navigateToDownloadSearch(modId);
                    });
                    missingRow.getChildren().add(search);
                }
                Label missingHint = new Label(I18n.t("crash.missing_mods_hint"));
                missingHint.setWrapText(true);
                missingHint.getStyleClass().add("cat-hint");
                body.getChildren().addAll(missingTitle, missingRow, missingHint);
            }
        }

        // 崩溃时间线：从启动器启动的那一瞬间到最终崩溃/退出（轴线样式）。
        // 放在「原因 + 建议」之后、「其他可能原因 / 疑似 Mod / 详细日志」之前：
        // 玩家先知道是什么问题、怎么修，再看「事情是按什么顺序发生的」。
        java.util.List<com.brisklauncher.mcl.crash.LaunchTimeline.Event> timeline =
                com.brisklauncher.mcl.crash.LaunchTimeline.events();
        if (!timeline.isEmpty()) {
            Label timelineTitle = new Label("🕒 崩溃时间线（版本启动 → 崩溃）");
            timelineTitle.setStyle("-fx-font-weight: bold; -fx-text-fill: #14453F;");
            javafx.scene.control.TitledPane timelinePane =
                    new javafx.scene.control.TitledPane("", CrashTimelineView.section(timeline));
            timelinePane.setExpanded(true);
            timelinePane.setGraphic(timelineTitle);
            body.getChildren().add(timelinePane);
            // 复制内容也带上时间线，方便贴到社区求助
            summary = (summary == null ? "" : summary + "\n\n")
                    + CrashTimelineView.plainText(timeline);
        }

        if (analysis == null || !analysis.hasMatches()) {
            Label unknown = new Label(I18n.t("crash.no_analysis"));
            unknown.setWrapText(true);
            unknown.setStyle("-fx-text-fill: #4A6A66;");
            body.getChildren().add(unknown);
        } else if (analysis.matches().size() > 1) {
            // 其余可能
            Label moreTitle = new Label("📋 其他可能原因");
            moreTitle.setStyle("-fx-font-weight: bold; -fx-text-fill: #14453F;");
            VBox more = new VBox(4);
            for (int i = 1; i < analysis.matches().size(); i++) {
                CrashAnalysis.Match match = analysis.matches().get(i);
                Label line = new Label("· [" + match.rule().category() + "] "
                        + match.rule().explanation());
                line.setWrapText(true);
                line.getStyleClass().add("cat-hint");
                Label hint = new Label("  → " + match.rule().suggestion());
                hint.setWrapText(true);
                hint.getStyleClass().add("cat-hint");
                more.getChildren().addAll(line, hint);
            }
            body.getChildren().addAll(moreTitle, more);
        }

        if (analysis != null && !analysis.suspectMods().isEmpty()) {
            Label modsTitle = new Label("🧩 疑似相关的 Mod");
            modsTitle.setStyle("-fx-font-weight: bold; -fx-text-fill: #14453F;");
            Label mods = new Label(String.join("、", analysis.suspectMods()));
            mods.setWrapText(true);
            mods.getStyleClass().add("cat-hint");
            body.getChildren().addAll(modsTitle, mods);
        }

        // 崩溃分析（命中规则的完整说明，放在日志之前；启动失败时的错误消息也归到这里）
        if (analysis != null && !analysis.matches().isEmpty() || (message != null && !message.isBlank())) {
            Label analysisTitle = new Label("🔍 崩溃分析");
            analysisTitle.setStyle("-fx-font-weight: bold; -fx-text-fill: #14453F;");
            VBox analysisBox = new VBox(4);
            if (message != null && !message.isBlank()) {
                Label messageLabel = new Label(message);
                messageLabel.setWrapText(true);
                messageLabel.setStyle("-fx-text-fill: #0B6E63;");
                analysisBox.getChildren().add(messageLabel);
            }
            for (CrashAnalysis.Match match : analysis.matches()) {
                String evidence = match.evidence();
                if (evidence != null && evidence.length() > 200) {
                    evidence = evidence.substring(0, 200) + "…";
                }
                Label line = new Label("· [" + match.rule().category() + "] "
                        + match.rule().explanation()
                        + (evidence == null || evidence.isBlank() ? "" : "\n  " + evidence.trim()));
                line.setWrapText(true);
                line.getStyleClass().add("cat-hint");
                analysisBox.getChildren().add(line);
            }
            body.getChildren().addAll(analysisTitle, analysisBox);
        }

        // 日志：默认折叠，需要时展开
        if (analysis != null && analysis.logTail() != null && !analysis.logTail().isBlank()) {
            javafx.scene.control.TextArea logArea = new javafx.scene.control.TextArea(analysis.logTail());
            logArea.setEditable(false);
            logArea.setWrapText(false);
            logArea.setPrefRowCount(10);
            logArea.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 11px;");
            javafx.scene.control.TitledPane logPane =
                    new javafx.scene.control.TitledPane("📄 详细日志（点击展开）", logArea);
            logPane.setExpanded(false);
            body.getChildren().add(logPane);
        }

        Button closeButton = com.brisklauncher.mcl.ui.UiKit.closeButtonStyled();
        closeButton.setOnAction(e -> dialog.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, titleLabel, spacer, closeButton);
        header.setAlignment(Pos.CENTER_LEFT);

        HBox buttons = new HBox(10);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        final String clipText = summary;
        if (clipText != null && !clipText.isBlank()) {
            Button copy = new Button(I18n.t("common.copy"));
            copy.getStyleClass().add("button");
            copy.setOnAction(e -> {
                ClipboardContent content = new ClipboardContent();
                content.putString(clipText);
                Clipboard.getSystemClipboard().setContent(content);
                copy.setText(I18n.t("common.copied"));
            });
            buttons.getChildren().add(copy);
        }
        if (onExport != null) {
            Button export = new Button(I18n.t("common.export_logs"));
            export.getStyleClass().add("primary-button");
            export.setOnAction(e -> {
                dialog.close();
                javafx.application.Platform.runLater(onExport);
            });
            buttons.getChildren().add(export);
        }
        Button ok = new Button(I18n.t("common.ok"));
        ok.getStyleClass().add("button");
        ok.setOnAction(e -> dialog.close());
        buttons.getChildren().add(ok);

        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setMaxHeight(520);
        scroll.setStyle("-fx-background-color: transparent;");

        VBox root = new VBox(12, header, scroll, buttons);
        decorate(dialog, root);
        dialog.show();
    }

    /**
     * 主题化的单行输入弹窗（替代 JavaFX 的 {@code TextInputDialog}：后者是系统外观，和启动器风格不统一）。
     *
     * @param defaultValue 预填内容
     * @param onConfirm    回车/确定时回调（已去首尾空白，非空才回调）
     */
    public static void prompt(Window owner, String title, String message,
                              String defaultValue, java.util.function.Consumer<String> onConfirm) {
        prompt(owner, title, message, defaultValue, null, onConfirm);
    }

    /**
     * 带校验的输入弹窗。
     *
     * @param validate 返回错误文字（{@code null} = 合法）；不合法时「确定」禁用并显示原因
     */
    public static void prompt(Window owner, String title, String message,
                              String defaultValue,
                              java.util.function.Function<String, String> validate,
                              java.util.function.Consumer<String> onConfirm) {
        Stage dialog = themedStage(owner);

        Label titleLabel = new Label("✦ " + title);
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #14453F;");

        Label messageLabel = new Label(message == null ? "" : message);
        messageLabel.setWrapText(true);
        messageLabel.setStyle("-fx-text-fill: #4A6A66; -fx-font-size: 13px;");

        javafx.scene.control.TextField input = new javafx.scene.control.TextField(
                defaultValue == null ? "" : defaultValue);
        input.setPrefWidth(360);

        Label errorLabel = new Label("");
        errorLabel.setWrapText(true);
        errorLabel.setMaxWidth(360);
        errorLabel.setStyle("-fx-text-fill: #B23B2E; -fx-font-size: 12px;");
        errorLabel.setManaged(false);
        errorLabel.setVisible(false);
        // 预填值（什么都没改）一律不可提交：避免「按 Enter 就把名字改成 旧名+新名 这种怪结果」
        final String preset = defaultValue == null ? "" : defaultValue.trim();

        HBox buttons = new HBox(10);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        Button cancel = new Button(I18n.t("common.cancel"));
        cancel.getStyleClass().add("button");
        cancel.setOnAction(e -> dialog.close());
        Button ok = new Button(I18n.t("common.ok"));
        ok.getStyleClass().add("primary-button");
        Runnable refreshState = () -> {
            String value = input.getText() == null ? "" : input.getText().trim();
            String problem = null;
            if (value.isEmpty()) {
                problem = I18n.t("dialog.input.empty");
            } else if (validate != null && value.equals(preset)) {
                // 只有「要求真的改名/改值」的呼叫端才挡未修改（单纯命名弹窗允许直接用预设值）
                problem = I18n.t("dialog.input.unchanged");
            } else if (validate != null) {
                problem = validate.apply(value);
            }
            boolean blocked = problem != null && !problem.isBlank();
            errorLabel.setText(blocked ? problem : "");
            errorLabel.setManaged(blocked);
            errorLabel.setVisible(blocked);
            ok.setDisable(blocked);
        };
        Runnable submit = () -> {
            String value = input.getText() == null ? "" : input.getText().trim();
            if (value.isEmpty() || (validate != null && value.equals(preset))) {
                return;
            }
            if (validate != null) {
                String problem = validate.apply(value);
                if (problem != null && !problem.isBlank()) {
                    refreshState.run();
                    return;
                }
            }
            dialog.close();
            javafx.application.Platform.runLater(() -> onConfirm.accept(value));
        };
        ok.setOnAction(e -> submit.run());
        input.setOnAction(e -> submit.run());
        input.textProperty().addListener((obs, was, now) -> refreshState.run());
        buttons.getChildren().addAll(cancel, ok);

        Button closeButton = com.brisklauncher.mcl.ui.UiKit.closeButtonStyled();
        closeButton.getStyleClass().addAll("window-button", "window-close");
        closeButton.setOnAction(e -> dialog.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(titleLabel, spacer, closeButton);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(12, header, messageLabel, input, errorLabel, buttons);
        decorate(dialog, root);
        refreshState.run();
        // 预填内容预设全选：一打字就是「取代」，不会变成「旧名字 + 新名字」
        // （实测踩过：没选中就直接打字，结果生出 1.21.1-Fabric11 这种名字）
        Runnable focusAndSelect = () -> {
            input.requestFocus();
            input.positionCaret(input.getText() == null ? 0 : input.getText().length());
            input.selectAll();
        };
        dialog.setOnShown(e -> focusAndSelect.run());
        input.focusedProperty().addListener((obs, was, now) -> {
            if (Boolean.TRUE.equals(now)
                    && preset.equals(input.getText() == null ? "" : input.getText().trim())) {
                input.selectAll();
            }
        });
        javafx.application.Platform.runLater(focusAndSelect);
        dialog.showAndWait();
    }

    /** 主题化的单选弹窗（替代 {@code ChoiceDialog}）。 */
    public static <T> void choose(Window owner, String title, String message,
                                  java.util.List<T> items,
                                  java.util.function.Function<T, String> labelOf,
                                  java.util.function.Consumer<T> onChosen) {
        Stage dialog = themedStage(owner);

        Label titleLabel = new Label("✦ " + title);
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #14453F;");
        Label messageLabel = new Label(message == null ? "" : message);
        messageLabel.setWrapText(true);
        messageLabel.setStyle("-fx-text-fill: #4A6A66; -fx-font-size: 13px;");

        javafx.scene.control.ListView<T> list = new javafx.scene.control.ListView<>();
        list.getItems().setAll(items);
        list.setPrefHeight(Math.min(320, 48 + items.size() * 34));
        list.setPrefWidth(420);
        if (!items.isEmpty()) {
            list.getSelectionModel().selectFirst();
        }
        list.setCellFactory(view -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : labelOf.apply(item));
            }
        });

        HBox buttons = new HBox(10);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        Button cancel = new Button(I18n.t("common.cancel"));
        cancel.getStyleClass().add("button");
        cancel.setOnAction(e -> dialog.close());
        Button ok = new Button(I18n.t("common.ok"));
        ok.getStyleClass().add("primary-button");
        Runnable submit = () -> {
            T chosen = list.getSelectionModel().getSelectedItem();
            dialog.close();
            if (chosen != null) {
                javafx.application.Platform.runLater(() -> onChosen.accept(chosen));
            }
        };
        ok.setOnAction(e -> submit.run());
        list.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                submit.run();
            }
        });
        buttons.getChildren().addAll(cancel, ok);

        Button closeButton = com.brisklauncher.mcl.ui.UiKit.closeButtonStyled();
        closeButton.getStyleClass().addAll("window-button", "window-close");
        closeButton.setOnAction(e -> dialog.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(titleLabel, spacer, closeButton);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(12, header, messageLabel, list, buttons);
        decorate(dialog, root);
        dialog.showAndWait();
    }

    /**
     * 目前正在显示的「应用程式模态」视窗（安装/下载进度框）。
     *
     * <p>用它当 owner，弹出的确认框才不会被模态框挡住。
     */
    private static javafx.stage.Window activeModalOwner() {
        for (javafx.stage.Window window : javafx.stage.Window.getWindows()) {
            if (!window.isShowing()) {
                continue;
            }
            if (window instanceof javafx.stage.Stage stage
                    && stage.getModality() == Modality.APPLICATION_MODAL) {
                return stage;
            }
        }
        return null;
    }

    private static Stage themedStage(Window owner) {
        Stage dialog = new Stage();
        dialog.initOwner(owner);
        dialog.initModality(Modality.NONE);
        dialog.initStyle(StageStyle.TRANSPARENT);
        // 注意：不要 setAlwaysOnTop(true)。
        // 置顶会让弹窗浮在「其它应用」之上（切到浏览器时它还压在最上面）；
        // JavaFX 的 owned window 本来就会跟在主窗口上面，去掉后层级就跟著启动器走。
        return dialog;
    }

    /** 统一的弹窗外观：圆角、暖色背景、阴影 + 主题样式表。 */
    private static void decorate(Stage dialog, VBox root) {
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: #F4FCFA; -fx-background-radius: 12;"
                + " -fx-border-color: #BFE9E2; -fx-border-radius: 12;"
                + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 15, 0, 0, 5);");
        // 无边框弹窗：按住标题列可以拖动
        javafx.scene.Node dragHandle = root.getChildren().isEmpty()
                ? root : root.getChildren().get(0);
        UiKit.makeDraggable(dialog, dragHandle);
        Scene scene = new Scene(root);
        UiKit.applyTheme(scene);
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        dialog.setScene(scene);
        dialog.sizeToScene();
        dialog.setOnShown(e -> com.brisklauncher.mcl.ui.animation.UiTransitions
                .popIn(dialog, root));
    }

    private static void show(Window owner, String title, String message, boolean isError,
                             Runnable onConfirm, Runnable onExport, String copyText) {
        show(owner, title, message, isError, onConfirm, onExport, copyText, null);
    }

    /**
     * @param syncConfirm 非 null 时，「确定」会**同步**把 true 写进这个 holder
     *                    （阻塞式确认框专用，避免排队回呼造成的竞态）
     */
    private static void show(Window owner, String title, String message, boolean isError,
                             Runnable onConfirm, Runnable onExport, String copyText,
                             boolean[] syncConfirm) {
        Stage dialog = new Stage();
        dialog.initOwner(owner);
        // 有 owner 又是阻塞式确认（安装流程会用）时，挂成 owner 的子模态：
        // 进度框是 APPLICATION_MODAL，不这样做的话确认框会被挡住点不到。
        dialog.initModality(syncConfirm != null && owner != null
                ? Modality.WINDOW_MODAL : Modality.NONE);
        dialog.initStyle(StageStyle.TRANSPARENT);

        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #14453F;");

        Label messageLabel = new Label(message == null ? "" : message);
        messageLabel.setWrapText(true);
        messageLabel.setStyle("-fx-text-fill: " + (isError ? "#0B6E63" : "#4A6A66")
                + "; -fx-font-size: 13px;");
        messageLabel.setMaxWidth(560);

        VBox messageBox = new VBox(messageLabel);
        ScrollPane scroll = new ScrollPane(messageBox);
        scroll.setFitToWidth(true);
        scroll.setMaxHeight(420);
        scroll.setPrefViewportHeight(Math.min(400, 60 + (message == null ? 0 : message.length()) / 2.2));
        scroll.getStyleClass().add("edge-to-edge");
        scroll.setStyle("-fx-background-color: transparent;");

        Button closeButton = com.brisklauncher.mcl.ui.UiKit.closeButtonStyled();
        closeButton.getStyleClass().addAll("window-button", "window-close");
        closeButton.setOnAction(e -> dialog.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(titleLabel, spacer, closeButton);
        header.setAlignment(Pos.CENTER_LEFT);

        HBox buttons = new HBox(10);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        if (copyText != null && !copyText.isBlank()) {
            Button copy = new Button(I18n.t("common.copy"));
            copy.getStyleClass().add("button");
            copy.setOnAction(e -> {
                ClipboardContent content = new ClipboardContent();
                content.putString(copyText);
                Clipboard.getSystemClipboard().setContent(content);
                copy.setText(I18n.t("common.copied"));
            });
            buttons.getChildren().add(copy);
        }
        if (onExport != null) {
            Button export = new Button(I18n.t("common.export_logs"));
            export.getStyleClass().add("button");
            export.setOnAction(e -> {
                dialog.close();
                // 下一个弹窗排到本轮事件循环之后：避免「在关闭中的模态窗里再开模态窗」导致卡死
                javafx.application.Platform.runLater(onExport);
            });
            buttons.getChildren().add(export);
        }

        if (onConfirm != null || syncConfirm != null) {
            Button cancel = new Button(I18n.t("common.cancel"));
            cancel.getStyleClass().add("button");
            cancel.setOnAction(e -> dialog.close());
            Button ok = new Button(I18n.t("common.confirm"));
            ok.getStyleClass().add("primary-button");
            ok.setOnAction(e -> {
                if (syncConfirm != null) {
                    syncConfirm[0] = true;
                }
                dialog.close();
                if (onConfirm != null) {
                    javafx.application.Platform.runLater(onConfirm);
                }
            });
            buttons.getChildren().addAll(cancel, ok);
        } else {
            Button ok = new Button(I18n.t("common.ok"));
            ok.getStyleClass().add("primary-button");
            ok.setOnAction(e -> dialog.close());
            buttons.getChildren().add(ok);
        }

        VBox root = new VBox(12, header, scroll, buttons);
        decorate(dialog, root);
        dialog.showAndWait();
    }
}
