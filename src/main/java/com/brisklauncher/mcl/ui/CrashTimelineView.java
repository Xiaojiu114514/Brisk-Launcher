package com.brisklauncher.mcl.ui;

import com.brisklauncher.mcl.crash.LaunchTimeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 崩溃时间线的显示：左边时刻、中间一条轴（圆点表示事件级别）、右边描述。
 *
 * <p>轴的起点是「启动器启动」，终点是崩溃/退出，所以玩家能一眼看出时间花在哪里：
 * 是在下载文件、等 Java、加载 Mod，还是进游戏之后才出事。
 */
public final class CrashTimelineView {

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter CLOCK_MS =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private CrashTimelineView() {
    }

    public static Node build(List<LaunchTimeline.Event> events, long baseMillis) {
        VBox box = new VBox(0);
        if (events == null || events.isEmpty()) {
            box.getChildren().add(UiKit.catHint("没有记录到启动事件"));
            return box;
        }
        long base = baseMillis > 0 ? baseMillis : events.get(0).epochMillis();
        for (int index = 0; index < events.size(); index++) {
            box.getChildren().add(row(events.get(index), index == events.size() - 1, base));
        }
        return box;
    }

    private static Node row(LaunchTimeline.Event event, boolean last, long base) {
        // 左：时:分:秒 + 相对秒数
        Label clock = new Label(CLOCK.format(Instant.ofEpochMilli(event.epochMillis())
                .atZone(ZoneId.systemDefault())));
        clock.setStyle("-fx-font-size: 11px; -fx-text-fill: #2E6B64;");
        Label relative = new Label(String.format(Locale.ROOT, "+%.2fs",
                (event.epochMillis() - base) / 1000.0));
        relative.getStyleClass().add("cat-hint");
        relative.setStyle("-fx-font-size: 10px;");
        VBox timeColumn = new VBox(0, clock, relative);
        timeColumn.setMinWidth(84);
        timeColumn.setPrefWidth(84);
        timeColumn.setAlignment(Pos.TOP_LEFT);
        timeColumn.setPadding(new Insets(0, 6, 0, 0));

        // 中：圆点 + 连接线
        Circle dot = new Circle(event.level() == LaunchTimeline.Level.ERROR ? 5.5 : 4.5,
                colorOf(event.level()));
        dot.setStroke(Color.WHITE);
        dot.setStrokeWidth(1.2);
        StackPane dotBox = new StackPane(dot);
        dotBox.setAlignment(Pos.TOP_CENTER);
        dotBox.setPadding(new Insets(3, 0, 0, 0));
        Region line = new Region();
        line.setPrefWidth(2);
        line.setMinWidth(2);
        line.setMaxWidth(2);
        line.setStyle("-fx-background-color: #BFE9E2;");
        if (last) {
            line.setVisible(false);
        }
        VBox marker = new VBox(0, dotBox, line);
        marker.setAlignment(Pos.TOP_CENTER);
        marker.setMinWidth(16);
        marker.setPrefWidth(16);
        VBox.setVgrow(line, Priority.ALWAYS);

        // 右：标题 + 细节
        Label title = new Label(event.title());
        title.setWrapText(true);
        title.setStyle("-fx-font-size: 12.5px; -fx-font-weight: "
                + (event.level() == LaunchTimeline.Level.ERROR ? "bold" : "normal") + ";"
                + " -fx-text-fill: " + hexOf(event.level()) + ";");
        VBox text = new VBox(1, title);
        if (event.detail() != null && !event.detail().isBlank()) {
            Label detail = new Label(event.detail());
            detail.setWrapText(true);
            detail.getStyleClass().add("cat-hint");
            detail.setStyle("-fx-font-size: 11px;");
            text.getChildren().add(detail);
        }
        text.setPadding(new Insets(0, 0, 10, 8));
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox row = new HBox(0, timeColumn, marker, text);
        row.setAlignment(Pos.TOP_LEFT);
        row.setFillHeight(true);
        return row;
    }

    private static Color colorOf(LaunchTimeline.Level level) {
        return switch (level) {
            case OK -> Color.web("#2E8B62");
            case WARN -> Color.web("#E0A83C");
            case ERROR -> Color.web("#C0392B");
            default -> Color.web("#4F9C93");
        };
    }

    private static String hexOf(LaunchTimeline.Level level) {
        return switch (level) {
            case OK -> "#2E7D5B";
            case WARN -> "#1F7A6E";
            case ERROR -> "#C0392B";
            default -> "#14453F";
        };
    }

    /** 時間線 + 摘要（給崩潰彈窗用：標題、總時長、事件數）。 */
    public static Node section(List<LaunchTimeline.Event> events) {
        VBox root = new VBox(4);
        if (events == null || events.isEmpty()) {
            root.getChildren().add(UiKit.catHint("没有记录到启动事件"));
            return root;
        }
        long base = events.get(0).epochMillis();
        long last = events.get(events.size() - 1).epochMillis();
        Label summary = new Label("从版本启动到结束共 "
                + String.format(Locale.ROOT, "%.1f", (last - base) / 1000.0)
                + " 秒 · " + events.size() + " 个事件");
        summary.getStyleClass().add("cat-hint");
        root.getChildren().add(summary);

        // 事件太多时只显示「开头 + 结尾」：开头是启动链路，结尾才是崩溃现场，
        // 中间那几十条加载日志对判断问题没什么用（而且会把弹窗撑得很长）。
        int head = 4;
        int tail = 7;
        boolean truncate = events.size() > head + tail + 2;
        List<LaunchTimeline.Event> shown = new ArrayList<>();
        if (truncate) {
            shown.addAll(events.subList(0, head));
            shown.addAll(events.subList(events.size() - tail, events.size()));
        } else {
            shown.addAll(events);
        }
        int omitted = events.size() - shown.size();
        for (int index = 0; index < shown.size(); index++) {
            if (truncate && index == head) {
                root.getChildren().add(gapRow(omitted));
            }
            boolean isLast = index == shown.size() - 1;
            root.getChildren().add(row(shown.get(index), isLast, base));
        }
        return root;
    }

    /** 省略提示：軸線用虛線段表示「這裡還有 N 條事件」。 */
    private static Node gapRow(int omitted) {
        Label time = new Label("⋯");
        time.getStyleClass().add("cat-hint");
        time.setMinWidth(84);
        time.setPrefWidth(84);
        Region line = new Region();
        line.setPrefWidth(2);
        line.setMinWidth(2);
        line.setMaxWidth(2);
        line.setPrefHeight(22);
        line.setStyle("-fx-background-color: #CBE9E4;");
        VBox marker = new VBox(0, line);
        marker.setAlignment(Pos.TOP_CENTER);
        marker.setMinWidth(16);
        marker.setPrefWidth(16);
        Label text = new Label("省略中间 " + omitted + " 个事件（加载日志）");
        text.getStyleClass().add("cat-hint");
        text.setStyle("-fx-font-size: 11px;");
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(0, time, marker, text);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** 給「複製」用的純文字。 */
    public static String plainText(List<LaunchTimeline.Event> events) {
        if (events == null || events.isEmpty()) {
            return "";
        }
        long base = events.get(0).epochMillis();
        StringBuilder sb = new StringBuilder("启动时间线：\n");
        for (LaunchTimeline.Event event : events) {
            sb.append(CLOCK_MS.format(Instant.ofEpochMilli(event.epochMillis())
                            .atZone(ZoneId.systemDefault())))
                    .append("  +")
                    .append(String.format(Locale.ROOT, "%.2fs", (event.epochMillis() - base) / 1000.0))
                    .append("  ").append(event.title());
            if (event.detail() != null) {
                sb.append("  [").append(event.detail()).append(']');
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
