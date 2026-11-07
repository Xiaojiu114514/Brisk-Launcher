package com.brisklauncher.mcl.plugin.builtin;

import com.brisklauncher.mcl.i18n.I18n;
import com.brisklauncher.mcl.plugin.api.LauncherPlugin;
import com.brisklauncher.mcl.plugin.api.PluginContext;
import com.brisklauncher.mcl.ui.UiKit;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内建插件：spark 报告分析。
 *
 * <p>spark 是服务端/客户端常用的效能分析工具，玩家贴出来的通常是一段文字报告
 * （TPS、tick 时间、各方法/线程的百分比）。这个插件把那段文字丢进去，
 * 抽出「TPS / MSPT / 最耗时的方法」，并用大白话说出问题在哪。
 *
 * <p>只做文字报告；spark 的网页连结（/profiler 产生的 URL）需要另外的 API，
 * 这里会明确提示「请贴文字报告」。
 */
public final class SparkReportPlugin implements LauncherPlugin {

    /** 百分比行：[ ] 12.34% xxx / 12.34% xxx。 */
    // 百分比行可能带日志前缀（[12:00:02] [Server thread/INFO]:   32.45%  xxx），所以用 find 而不是整行匹配
    private static final Pattern PERCENT_LINE = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)%\\s+(\\S.*)$");
    private static final Pattern TPS_LINE = Pattern.compile(
            "TPS from last [^:]*:\\s*([0-9.,\\s]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TICK_LINE = Pattern.compile(
            "Tick durations[^:]*:\\s*([0-9./\\s,;]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MSPT_LINE = Pattern.compile(
            "(?:avg|average)?\\s*(?:tick|mspt)[^:=]*[:=]\\s*([0-9.]+)\\s*ms", Pattern.CASE_INSENSITIVE);

    @Override
    public String id() {
        return "spark-report";
    }

    @Override
    public String name() {
        return I18n.t("tools.spark.name");
    }

    @Override
    public String description() {
        return I18n.t("tools.spark.desc");
    }

    @Override
    public String icon() {
        return "⚡";
    }

    @Override
    public Node createPanel(PluginContext context) {
        TextArea input = new TextArea();
        input.setPromptText(I18n.t("tools.spark.input_hint"));
        input.setPrefRowCount(10);
        input.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 11px;");

        Label status = new Label(I18n.t("tools.spark.idle"));
        status.getStyleClass().add("cat-hint");
        status.setWrapText(true);

        VBox result = new VBox(8);

        Button analyse = new Button(I18n.t("tools.spark.analyse"));
        analyse.getStyleClass().add("primary-button");
        analyse.setOnAction(e -> {
            String text = input.getText() == null ? "" : input.getText();
            if (text.isBlank()) {
                context.info(I18n.t("tools.spark.name"), I18n.t("tools.spark.need_text"));
                return;
            }
            Report report = Report.parse(text);
            render(result, status, report);
        });

        Button open = new Button(I18n.t("tools.spark.open_file"));
        open.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.t("tools.spark.open_file"));
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                    "spark (*.sparkprofile, *.txt, *.log, *.json)",
                    "*.sparkprofile", "*.txt", "*.log", "*.json"));
            File file = chooser.showOpenDialog(context.ownerWindow());
            if (file == null) {
                return;
            }
            // .sparkprofile 是 spark 导出的二进位（protobuf）档，直接解析成摘要
            if (SparkProfile.looksLikeProfile(file.toPath())) {
                input.clear();
                try {
                    renderProfile(result, status, SparkProfile.read(file.toPath()),
                            file.getName());
                } catch (Exception error) {
                    context.error(I18n.t("tools.spark.open_file"), String.valueOf(error.getMessage()));
                }
                return;
            }
            try {
                input.setText(Files.readString(file.toPath(), StandardCharsets.UTF_8));
                status.setText(I18n.t("tools.spark.loaded", file.getName()));
            } catch (Exception error) {
                context.error(I18n.t("tools.spark.open_file"), String.valueOf(error.getMessage()));
            }
        });
        Button clear = new Button(I18n.t("tools.rgb.clear"));
        clear.setOnAction(e -> {
            input.clear();
            result.getChildren().clear();
            status.setText(I18n.t("tools.spark.idle"));
        });

        Label hint = new Label(I18n.t("tools.spark.hint"));
        hint.getStyleClass().add("cat-hint");
        hint.setWrapText(true);

        HBox actions = new HBox(8, analyse, open, clear);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox panel = new VBox(10, hint, input, actions, status, result);
        panel.setPadding(new Insets(4, 4, 12, 4));
        ScrollPane scroll = new ScrollPane(panel);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("edge-to-edge");
        // 开发期：自动载入报告并分析（截图/回归验证用，正常启动不会触发）
        String devReport = System.getProperty("mcl.dev.sparktext");
        if (devReport != null && !devReport.isBlank()) {
            try {
                java.nio.file.Path devPath = java.nio.file.Path.of(devReport.trim());
                if (SparkProfile.looksLikeProfile(devPath)) {
                    // 直接喂 .sparkprofile（protobuf）给解析器
                    SparkProfile.Profile profile = SparkProfile.read(devPath);
                    renderProfile(result, status, profile, devPath.getFileName().toString());
                } else {
                    input.setText(Files.readString(devPath, StandardCharsets.UTF_8));
                    javafx.application.Platform.runLater(analyse::fire);
                }
            } catch (Exception error) {
                status.setText(I18n.t("tools.spark.no_data"));
            }
        }
        return scroll;
    }

    // ---------- 解析 ----------

    /** 解析結果。 */
    record Report(List<Double> tps, String tickDurations, Double avgMspt,
                  List<Hot> hotspots, boolean looksLikeUrl) {

        record Hot(double percent, String name) {
        }

        static Report parse(String text) {
            List<Double> tps = new ArrayList<>();
            String tick = "";
            Double avg = null;
            List<Hot> hotspots = new ArrayList<>();
            boolean url = text.trim().startsWith("http") && text.trim().split("\\R").length <= 2;

            Matcher tpsMatcher = TPS_LINE.matcher(text);
            if (tpsMatcher.find()) {
                for (String piece : tpsMatcher.group(1).split("[,;\\s]+")) {
                    try {
                        tps.add(Double.parseDouble(piece.replace(',', '.')));
                    } catch (NumberFormatException ignored) {
                        // 不是数字就跳过
                    }
                }
            }
            Matcher tickMatcher = TICK_LINE.matcher(text);
            if (tickMatcher.find()) {
                tick = tickMatcher.group(1).trim();
            }
            Matcher msptMatcher = MSPT_LINE.matcher(text);
            if (msptMatcher.find()) {
                try {
                    avg = Double.parseDouble(msptMatcher.group(1));
                } catch (NumberFormatException ignored) {
                    // 解析不到就算了
                }
            }
            for (String line : text.split("\\R")) {
                Matcher matcher = PERCENT_LINE.matcher(line);
                if (!matcher.find()) {
                    continue;
                }
                try {
                    double percent = Double.parseDouble(matcher.group(1));
                    String name = matcher.group(2).trim();
                    if (name.length() > 90) {
                        name = name.substring(0, 90) + "…";
                    }
                    hotspots.add(new Hot(percent, name));
                } catch (NumberFormatException ignored) {
                    // 百分比不是数字
                }
            }
            hotspots.sort(Comparator.comparingDouble(Hot::percent).reversed());
            if (hotspots.size() > 12) {
                hotspots = new ArrayList<>(hotspots.subList(0, 12));
            }
            return new Report(tps, tick, avg, hotspots, url);
        }

        /** 三個時間窗裡最差的那個 TPS（不是最後一個）。 */
        double worstTps() {
            return tps.stream().mapToDouble(Double::doubleValue).min().orElse(-1);
        }
    }

    // ---------- 显示 ----------

    private static String fix(String key, Object... args) {
        return com.brisklauncher.mcl.i18n.I18n.t(key, args);
    }

    private void render(VBox result, Label status, Report report) {
        result.getChildren().clear();
        if (report.looksLikeUrl()) {
            status.setText(I18n.t("tools.spark.url_only"));
            result.getChildren().add(UiKit.catHint(I18n.t("tools.spark.url_hint")));
            return;
        }
        if (report.hotspots().isEmpty() && report.tps().isEmpty()) {
            status.setText(I18n.t("tools.spark.no_data"));
            result.getChildren().add(UiKit.catHint(I18n.t("tools.spark.no_data_hint")));
            return;
        }

        VBox overview = new VBox(4);
        overview.getStyleClass().add("card");
        overview.setPadding(new Insets(12));
        Label title = new Label("📋 " + I18n.t("tools.spark.overview"));
        title.getStyleClass().add("section-title");
        overview.getChildren().add(title);
        if (!report.tps().isEmpty()) {
            double worst = report.worstTps();
            Label tps = new Label(fix("tools.spark.tps", joinNumbers(report.tps())));
            tps.setStyle("-fx-text-fill: " + (worst < 18 ? "#C0392B"
                    : worst < 20 ? "#1F7A6E" : "#2E7D5B") + "; -fx-font-weight: bold;");
            overview.getChildren().add(tps);
        }
        if (!report.tickDurations().isBlank()) {
            Label tick = new Label(fix("tools.spark.tick", report.tickDurations()));
            tick.getStyleClass().add("instance-meta");
            tick.setWrapText(true);
            overview.getChildren().add(tick);
        }
        if (report.avgMspt() != null) {
            Label mspt = new Label(fix("tools.spark.mspt", report.avgMspt()));
            mspt.getStyleClass().add("instance-meta");
            overview.getChildren().add(mspt);
        }
        // 结论
        List<String> verdicts = new ArrayList<>();
        double worst = report.worstTps();
        if (worst >= 0 && worst < 15) {
            verdicts.add(I18n.t("tools.spark.verdict.severe"));
        } else if (worst >= 0 && worst < 19.5) {
            verdicts.add(I18n.t("tools.spark.verdict.lagging"));
        }
        if (report.avgMspt() != null && report.avgMspt() > 50) {
            verdicts.add(I18n.t("tools.spark.verdict.tick_over"));
        }
        if (!report.hotspots().isEmpty() && report.hotspots().get(0).percent() >= 25) {
            verdicts.add(fix("tools.spark.verdict.hotspot",
                    report.hotspots().get(0).name(), report.hotspots().get(0).percent()));
        }
        if (verdicts.isEmpty()) {
            verdicts.add(I18n.t("tools.spark.verdict.ok"));
        }
        for (String verdict : verdicts) {
            Label line = new Label("• " + verdict);
            line.setWrapText(true);
            line.setStyle("-fx-text-fill: -brisk-text;");
            overview.getChildren().add(line);
        }
        result.getChildren().add(overview);

        if (!report.hotspots().isEmpty()) {
            Label hotTitle = new Label("🔥 " + I18n.t("tools.spark.hotspots"));
            hotTitle.getStyleClass().add("section-title");
            VBox list = new VBox(3);
            for (Report.Hot hot : report.hotspots()) {
                Label line = new Label(String.format(Locale.ROOT, "%5.1f%%", hot.percent())
                        + "  " + hot.name());
                line.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 11px;");
                list.getChildren().add(line);
            }
            result.getChildren().addAll(hotTitle, list);
        }
        if (!report.hotspots().isEmpty()
                && report.hotspots().get(0).name().toLowerCase(Locale.ROOT).contains("java.")) {
            result.getChildren().add(UiKit.catHint(I18n.t("tools.spark.hint.gc")));
        }
        status.setText(I18n.t("tools.spark.analysed", report.hotspots().size()));
    }

    private static String joinNumbers(List<Double> values) {
        StringBuilder sb = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                sb.append(" / ");
            }
            sb.append(String.format(Locale.ROOT, "%.2f", values.get(index)));
        }
        return sb.toString();
    }

    /**
     * 显示 {@code .sparkprofile}（spark 导出的 protobuf）的摘要。
     *
     * <p>spark 网页版有「Overview / Flat / Tree」几种检视；这里做启动器最实用的部分：
     * 概况（TPS / MSPT / 时间 / tick / 记忆体 / GC / CPU / 系统）、结论、以及
     * 依「自身耗时」排序的热点清单（flat 检视）。
     */
    private void renderProfile(VBox result, Label status, SparkProfile.Profile profile,
                               String fileName) {
        result.getChildren().clear();

        VBox overview = new VBox(4);
        overview.getStyleClass().add("card");
        overview.setPadding(new Insets(12));
        Label title = new Label("📋 " + I18n.t("tools.spark.overview")
                + " · " + I18n.t("tools.spark.profile.tag"));
        title.getStyleClass().add("section-title");
        overview.getChildren().add(title);

        Label platform = new Label(I18n.t("tools.spark.profile.platform",
                profile.platformType().isBlank() ? "server" : profile.platformType(),
                profile.platformVersion().isBlank() ? "-" : profile.platformVersion(),
                profile.minecraftVersion().isBlank() ? "-" : profile.minecraftVersion(),
                String.valueOf(profile.sparkVersion())));
        platform.getStyleClass().add("instance-meta");
        platform.setWrapText(true);
        overview.getChildren().add(platform);

        Label duration = new Label(I18n.t("tools.spark.profile.duration",
                String.format(Locale.ROOT, "%.1f", profile.durationMillis() / 1000.0),
                String.valueOf(profile.ticks()),
                String.format(Locale.ROOT, "%.1f", profile.intervalMicros() / 1000.0)));
        duration.getStyleClass().add("instance-meta");
        overview.getChildren().add(duration);

        if (profile.hasTps()) {
            double worst = profile.worstTps();
            Label tps = new Label(I18n.t("tools.spark.tps",
                    String.format(Locale.ROOT, "%.2f / %.2f / %.2f", profile.tps1m(),
                            profile.tps5m(), profile.tps15m())));
            tps.setStyle("-fx-text-fill: " + (worst < 18 ? "#C0392B"
                    : worst < 20 ? "#1F7A6E" : "#2E7D5B") + "; -fx-font-weight: bold;");
            overview.getChildren().add(tps);
        }
        if (profile.hasMspt()) {
            Label mspt = new Label(I18n.t("tools.spark.profile.mspt",
                    String.format(Locale.ROOT, "%.2f", profile.msptMean()),
                    String.format(Locale.ROOT, "%.2f", profile.mspt95()),
                    String.format(Locale.ROOT, "%.2f", profile.msptMax())));
            mspt.getStyleClass().add("instance-meta");
            overview.getChildren().add(mspt);
        }
        if (profile.heapTotal() > 0) {
            Label heap = new Label(I18n.t("tools.spark.profile.memory",
                    Math.round(profile.heapUsed() / 1048576.0),
                    Math.round(profile.heapTotal() / 1048576.0)));
            heap.getStyleClass().add("instance-meta");
            overview.getChildren().add(heap);
        }
        if (profile.hasGc() || !profile.javaVersion().isBlank()) {
            String gc = profile.hasGc()
                    ? I18n.t("tools.spark.profile.gc", String.valueOf(profile.gcTotal()),
                    String.format(Locale.ROOT, "%.1f", profile.gcAvgTime()))
                    : "";
            String sys = I18n.t("tools.spark.profile.system",
                    profile.javaVendor().isBlank() ? "-" : profile.javaVendor(),
                    profile.javaVersion().isBlank() ? "-" : profile.javaVersion(),
                    profile.osName().isBlank() ? "-" : profile.osName());
            Label line = new Label((gc.isBlank() ? "" : gc + "  ·  ") + sys);
            line.getStyleClass().add("instance-meta");
            line.setWrapText(true);
            overview.getChildren().add(line);
        }
        if (profile.hasCpu() || profile.totalEntities() > 0) {
            String cpu = profile.hasCpu()
                    ? I18n.t("tools.spark.profile.cpu",
                    String.format(Locale.ROOT, "%.1f", profile.cpuProcess1m()),
                    String.format(Locale.ROOT, "%.1f", profile.cpuSystem1m()))
                    : "";
            String world = I18n.t("tools.spark.profile.world",
                    String.valueOf(profile.playerCount()), String.valueOf(profile.totalEntities()));
            Label line = new Label((cpu.isBlank() ? "" : cpu + "  ·  ") + world);
            line.getStyleClass().add("instance-meta");
            line.setWrapText(true);
            overview.getChildren().add(line);
        }
        if (!profile.sources().isEmpty()) {
            List<String> names = new ArrayList<>();
            for (SparkProfile.Source source : profile.sources()) {
                names.add(source.version().isBlank()
                        ? source.name() : source.name() + "@" + source.version());
            }
            String joined = String.join("、", names.size() > 8 ? names.subList(0, 8) : names);
            Label mods = new Label(I18n.t("tools.spark.profile.sources",
                    profile.sources().size(), joined + (names.size() > 8 ? "…" : "")));
            mods.getStyleClass().add("instance-meta");
            mods.setWrapText(true);
            overview.getChildren().add(mods);
        }

        // 结论（用 TPS / MSPT）
        List<String> verdicts = new ArrayList<>();
        double worstTps = profile.worstTps();
        if (worstTps >= 0 && worstTps < 15) {
            verdicts.add(I18n.t("tools.spark.verdict.severe"));
        } else if (worstTps >= 0 && worstTps < 19.5) {
            verdicts.add(I18n.t("tools.spark.verdict.lagging"));
        }
        if (profile.hasMspt() && profile.msptMean() > 50) {
            verdicts.add(I18n.t("tools.spark.verdict.tick_over"));
        }
        if (profile.hasMspt() && profile.msptMax() > 200) {
            verdicts.add(I18n.t("tools.spark.profile.verdict.spike", profile.msptMax()));
        }
        if (verdicts.isEmpty()) {
            verdicts.add(I18n.t("tools.spark.verdict.ok"));
        }
        for (String verdict : verdicts) {
            Label line = new Label("• " + verdict);
            line.setWrapText(true);
            line.setStyle("-fx-text-fill: -brisk-text;");
            overview.getChildren().add(line);
        }
        result.getChildren().add(overview);

        // 热点（flat 检视：自身耗时）
        if (!profile.hotspots().isEmpty()) {
            Label hotTitle = new Label("🔥 " + I18n.t("tools.spark.hotspots"));
            hotTitle.getStyleClass().add("section-title");
            VBox list = new VBox(3);
            for (SparkProfile.Hotspot hot : profile.hotspots()) {
                Label line = new Label(String.format(Locale.ROOT, "%5.1f%%", hot.percent())
                        + "  " + hot.display());
                line.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 11px;");
                list.getChildren().add(line);
            }
            result.getChildren().addAll(hotTitle, list);
        }
        if (!profile.threads().isEmpty()) {
            StringBuilder threads = new StringBuilder();
            for (SparkProfile.ThreadSummary thread : profile.threads()) {
                if (threads.length() > 0) {
                    threads.append("、");
                }
                threads.append(thread.name()).append(" ")
                        .append(Math.round(thread.totalMillis())).append("ms");
            }
            result.getChildren().add(UiKit.catHint(I18n.t("tools.spark.profile.threads",
                    threads.toString())));
        }
        status.setText(I18n.t("tools.spark.profile.loaded", fileName,
                profile.hotspots().size()));
    }
}
