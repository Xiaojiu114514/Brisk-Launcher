package com.brisklauncher.mcl.ui;

import com.brisklauncher.mcl.game.install.InstallProgress;
import com.brisklauncher.mcl.i18n.I18n;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 多进度条视窗（PCL 风格）：每个安装阶段一条进度，完成的打勾、进行中的跑。
 *
 * <p>安装流程本身是分阶段的（解析 → 客户端 → 依赖库 → 资源 → 加载器 → 收尾），
 * 用一条总进度看不出「卡在哪个阶段」；这里每个阶段一行，使用者一眼能看到全部过程。
 */
public final class MultiProgressDialog {

    /** 安裝主體：透過 {@code progress} 回報每個階段的百分比。 */
    public interface Body {
        void run(Consumer<InstallProgress> progress) throws Exception;
    }

    /** 階段的顯示順序（與實際流程一致）。 */
    private static final InstallProgress.Stage[] ORDER = {
            InstallProgress.Stage.RESOLVING,
            InstallProgress.Stage.VERSION_JSON,
            InstallProgress.Stage.CLIENT_JAR,
            InstallProgress.Stage.LIBRARIES,
            InstallProgress.Stage.ASSETS,
            InstallProgress.Stage.LOGGING,
            // Forge / NeoForge 安装器需要的 Java 可能会自动补齐（~190MB），
            // 之前没有这一列 → 下载 Java 时进度条完全不动，看起来像「卡住」。
            InstallProgress.Stage.JAVA,
            InstallProgress.Stage.LOADER,
            InstallProgress.Stage.FINISHING
    };

    private MultiProgressDialog() {
    }

    public static void run(Window owner, String title, Body body, Runnable onSuccess) {
        Stage dialog = new Stage();
        dialog.initOwner(owner);
        // 应用程式模态：下载/安装期间不让玩家在主窗口操作（可以打开别的分页把版本删掉）
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initStyle(StageStyle.TRANSPARENT);
        dialog.setTitle(title);

        Label header = new Label(title);
        header.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: -brisk-text;");
        Button hide = UiKit.closeButtonStyled();
        hide.getStyleClass().addAll("window-button", "window-close");
        final Thread[] workerRef = new Thread[1];
        final boolean[] cancelled = {false};
        hide.setOnAction(e -> {
            MCLDialog.confirm(owner, title, I18n.t("install.cancel_confirm"), () -> {
                cancelled[0] = true;
                if (workerRef[0] != null) {
                    workerRef[0].interrupt();
                }
                com.brisklauncher.mcl.security.SafeLog.info("[install] 使用者取消安装");
                dialog.close();
            });
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        // 「下载中 / 安装中 / 已完成」状态徽章：固定在右上角（关闭钮左边），
        // 尺寸用 USE_PREF_SIZE，视窗再窄也不会被压成「…」或消失。
        Label phaseBadge = new Label(I18n.t("install.phase.download"));
        phaseBadge.setMinWidth(Region.USE_PREF_SIZE);
        phaseBadge.setMaxWidth(Region.USE_PREF_SIZE);
        phaseBadge.setAlignment(Pos.CENTER);
        java.util.function.Consumer<String> setPhase = key -> {
            String color = switch (key) {
                case "install" -> "#E08B2A";
                case "done" -> "#2E8B62";
                default -> "#12BFA8";
            };
            String text = switch (key) {
                case "install" -> I18n.t("install.phase.install");
                case "done" -> I18n.t("install.phase.done");
                default -> I18n.t("install.phase.download");
            };
            phaseBadge.setText(text);
            phaseBadge.setStyle("-fx-background-color: " + color + "; -fx-text-fill: -brisk-on-accent;"
                    + " -fx-background-radius: 10; -fx-padding: 2 12 2 12;"
                    + " -fx-font-size: 12px; -fx-font-weight: bold;");
        };
        setPhase.accept("download");
        HBox headerRow = new HBox(8, header, spacer, phaseBadge, hide);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        Map<InstallProgress.Stage, ProgressBar> bars = new LinkedHashMap<>();
        Map<InstallProgress.Stage, Label> labels = new LinkedHashMap<>();
        Map<InstallProgress.Stage, Label> speeds = new LinkedHashMap<>();
        Map<InstallProgress.Stage, Label> counts = new LinkedHashMap<>();
        java.util.Set<InstallProgress.Stage> doneStages = new java.util.LinkedHashSet<>();
        Label statusLine = new Label(I18n.t("install.status.preparing"));
        statusLine.getStyleClass().add("cat-hint");
        // 左侧（阶段 + 当前文件）可以被截断，右侧（字节 / 速度 / 剩余）固定宽度右对齐，
        // 这样文件名长短变化时右边那组数字不会左右乱跳。
        statusLine.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        statusLine.setMaxWidth(Double.MAX_VALUE);
        Label statusRight = new Label("");
        statusRight.getStyleClass().add("cat-hint");
        statusRight.setMinWidth(250);
        statusRight.setPrefWidth(250);
        statusRight.setAlignment(Pos.CENTER_RIGHT);
        HBox statusRow = new HBox(8, statusLine, statusRight);
        HBox.setHgrow(statusLine, Priority.ALWAYS);
        VBox rows = new VBox(8);
        for (InstallProgress.Stage stage : ORDER) {
            Label name = new Label(stageName(stage));
            name.setMinWidth(92);
            ProgressBar bar = new ProgressBar(0);
            bar.setMaxWidth(Double.MAX_VALUE);
            GridPane.setHgrow(bar, Priority.ALWAYS);
            // 数量单独一栏（74/75），档名一栏（不会把数量挤掉）
            Label count = new Label("");
            count.getStyleClass().add("cat-hint");
            count.setMinWidth(76);
            count.setPrefWidth(76);
            count.setTextOverrun(javafx.scene.control.OverrunStyle.CLIP);
            Label detail = new Label("");
            detail.getStyleClass().add("cat-hint");
            detail.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
            detail.setMaxWidth(Double.MAX_VALUE);
            Label speed = new Label("");
            speed.getStyleClass().add("cat-hint");
            speed.setMinWidth(84);
            speed.setPrefWidth(84);
            speed.setAlignment(Pos.CENTER_RIGHT);
            // 四栏：阶段名 | 进度条 | 明细 | 速度（速度固定在最后一栏，永不随文字移动）
            GridPane row = new GridPane();
            row.setHgap(8);
            row.setVgap(2);
            row.getColumnConstraints().addAll(
                    col(92, javafx.scene.layout.Priority.NEVER),
                    col(220, javafx.scene.layout.Priority.ALWAYS),
                    col(76, javafx.scene.layout.Priority.NEVER),
                    col(180, javafx.scene.layout.Priority.ALWAYS),
                    col(84, javafx.scene.layout.Priority.NEVER));
            row.add(name, 0, 0);
            row.add(bar, 1, 0);
            row.add(count, 2, 0);
            row.add(detail, 3, 0);
            row.add(speed, 4, 0);
            rows.getChildren().add(row);
            bars.put(stage, bar);
            labels.put(stage, detail);
            speeds.put(stage, speed);
            counts.put(stage, count);
        }

        VBox root = new VBox(12, headerRow, statusRow, rows);
        root.setPadding(new Insets(16));
        root.setPrefWidth(560);
        root.setStyle("-fx-background-color: -brisk-bg; -fx-background-radius: 12;"
                + " -fx-border-color: -brisk-border; -fx-border-radius: 12;");

        javafx.scene.Scene scene = new javafx.scene.Scene(root);
        UiKit.applyTheme(scene);
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        dialog.setScene(scene);
        UiKit.makeDraggable(dialog, headerRow);
        UiKit.popIn(dialog, root);

        Consumer<InstallProgress> progress = item -> {
            if (item == null) {
                return;
            }
            Platform.runLater(() -> {
                // 阶段 → 徽章：前段是「下载中」（版本 JSON / 客户端 / 依赖库 / 资源），
                // 后段（加载器 / Java / 收尾）才算「安装中」；收尾完成切成「已完成」。
                InstallProgress.Stage stage = item.stage();
                String phase = switch (stage) {
                    case LOADER, JAVA, FINISHING -> "install";
                    case DONE -> "done";
                    default -> "download";
                };
                if (stage == InstallProgress.Stage.FINISHING && item.progress() >= 1.0) {
                    phase = "done";
                }
                setPhase.accept(phase);
                ProgressBar bar = bars.get(item.stage());
                if (bar != null) {
                    bar.setProgress(Math.max(0, Math.min(1, item.progress())));
                }
                Label detail = labels.get(item.stage());
                String text = item.detail() == null ? "" : item.detail();
                String speedText = "";
                int cut = text.lastIndexOf(" · ");
                if (cut > 0 && text.substring(cut).contains("MB/s")) {
                    speedText = text.substring(cut + 3).trim();
                    text = text.substring(0, cut);
                }
                Label countLabel = counts.get(item.stage());
                if (countLabel != null) {
                    int slash = text.indexOf('/');
                    // 没有「数量」的阶段（版本 JSON / 客户端 / 收尾…）就显示百分比或 ✓，
                    // 让每一行都能自己说明进度，不用靠猜。
                    if (slash <= 0) {
                        if (item.progress() >= 1.0) {
                            countLabel.setText("✓");
                        } else if (item.progress() > 0.001) {
                            countLabel.setText(item.percent() + "%");
                        } else {
                            countLabel.setText("");
                        }
                    }
                    if (slash > 0) {
                        int space = text.indexOf(' ', slash);
                        countLabel.setText(space > 0 ? text.substring(0, space) : text);
                        text = space > 0 ? text.substring(space + 1) : "";
                        // 拆掉数量后原本会留下前面的「· 」，看起来像漏字
                        if (text.startsWith("·")) {
                            text = text.substring(1).trim();
                        }
                    }
                }
                if (detail != null) {
                    detail.setText(text);
                }
                Label speedLabel = speeds.get(item.stage());
                if (speedLabel != null) {
                    speedLabel.setText(speedText);
                }
                if (item.progress() >= 1.0) {
                    if (!doneStages.contains(item.stage())) {
                        doneStages.add(item.stage());
                        statusLine.setText(I18n.t("install.status.stage_done", stageName(item.stage())));
                        statusRight.setText("");
                    }
                } else if (text != null && !text.isBlank()) {
                    String left = text;
                    StringBuilder right = new StringBuilder();
                    for (String segment : text.split(" · ")) {
                        boolean numeric = segment.contains("MB/s")
                                || segment.matches("\\d+(\\.\\d+)? / \\d+(\\.\\d+)? MB")
                                || segment.startsWith("剩") || segment.endsWith(" left");
                        if (numeric) {
                            if (right.length() > 0) {
                                right.append(" · ");
                            }
                            right.append(segment);
                            if (left.endsWith(" · " + segment)) {
                                left = left.substring(0, left.length() - segment.length() - 3);
                            } else if (left.equals(segment)) {
                                left = "";
                            }
                        }
                    }
                    statusLine.setText(left.isBlank()
                            ? I18n.t("install.status.working", stageName(item.stage()), "")
                            : I18n.t("install.status.working", stageName(item.stage()), left));
                    statusRight.setText(right.toString());
                }
            });
        };

        Thread worker = new Thread(() -> {
            try {
                long started = System.currentTimeMillis();
                com.brisklauncher.mcl.security.SafeLog.info("[install] 开始");
                workerRef[0] = Thread.currentThread();
                body.run(progress);
                com.brisklauncher.mcl.security.SafeLog.info("[install] 结束，耗时 "
                        + (System.currentTimeMillis() - started) + "ms");
                if (cancelled[0]) {
                    Platform.runLater(dialog::close);
                    return;
                }
                Platform.runLater(() -> {
                    for (ProgressBar bar : bars.values()) {
                        if (bar.getProgress() < 1) {
                            bar.setProgress(1);
                        }
                    }
                    dialog.close();
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                });
            } catch (Throwable error) {
                // 一定要留下纪录：安装失败最常「看不到任何提示」
                com.brisklauncher.mcl.security.SafeLog.warn("安装失败：" + error.getClass().getSimpleName()
                        + " · " + error.getMessage());
                error.printStackTrace();
                String reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                Platform.runLater(() -> {
                    dialog.close();
                    MCLDialog.error(null, title, reason);
                });
            }
        }, "brisk-install");
        worker.setDaemon(true);
        dialog.show();
        worker.start();
    }

    private static javafx.scene.layout.ColumnConstraints col(double width,
                                                            javafx.scene.layout.Priority grow) {
        javafx.scene.layout.ColumnConstraints c = new javafx.scene.layout.ColumnConstraints();
        c.setMinWidth(width);
        c.setPrefWidth(width);
        c.setHgrow(grow);
        return c;
    }

    private static String stageName(InstallProgress.Stage stage) {
        return switch (stage) {
            case RESOLVING -> I18n.t("install.stage.resolving");
            case VERSION_JSON -> I18n.t("install.stage.json");
            case CLIENT_JAR -> I18n.t("install.stage.client");
            case LIBRARIES -> I18n.t("install.stage.libraries");
            case ASSETS -> I18n.t("install.stage.assets");
            case LOGGING -> I18n.t("install.stage.logging");
            case LOADER -> I18n.t("install.stage.loader");
            case JAVA -> I18n.t("install.stage.java");
            case FINISHING -> I18n.t("install.stage.finishing");
            case DONE -> I18n.t("install.stage.done");
            case UPDATE_DOWNLOAD -> I18n.t("update.stage.download");
            case UPDATE_EXTRACT -> I18n.t("update.stage.extract");
        };
    }
}
