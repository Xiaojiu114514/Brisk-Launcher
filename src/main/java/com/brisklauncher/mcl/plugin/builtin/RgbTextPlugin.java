package com.brisklauncher.mcl.plugin.builtin;

import com.brisklauncher.mcl.i18n.I18n;
import com.brisklauncher.mcl.plugin.LauncherPlugin;
import com.brisklauncher.mcl.plugin.PluginContext;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 内建插件：Minecraft 文字 RGB 编辑器。
 *
 * <p>玩家要的「彩色文字」有很多种写法（伺服器插件、聊天、MOTD、书、告示牌、BBCode…），
 * 手写十六进位格式码很痛苦。这里：选颜色（或直接输入颜色代码）→ 选输出格式 → 看不同背景的预览 → 复制。
 *
 * <p>输出格式：
 * <ul>
 *   <li>{@code &x&R&R&G&G&B&B} —— 常见插件（Essential 等）的 & 形式</li>
 *   <li>{@code &#RRGGBB} —— 另一种常见短形式</li>
 *   <li>{@code <#RRGGBB>} —— Paper / MiniMessage 聊天形式</li>
 *   <li>{@code \\u00A7x…} —— server.properties 的转义写法</li>
 *   <li>{@code §x§R§R§G§G§B§B} —— 控制台 / 游戏内直接贴</li>
 *   <li>{@code [COLOR=#RRGGBB]} —— BBCode（论坛、部分插件）</li>
 * </ul>
 *
 * <p>支援**渐变**：选两个颜色，逐字插色。
 */
public final class RgbTextPlugin implements LauncherPlugin {

    /** 輸出格式。 */
    enum Format {
        AMP_HEX, AMP_HASH, CHAT, MOTD_ESCAPED, CONSOLE, BBCODE
    }

    /** 預覽背景。 */
    private enum Background {
        CHAT, TOOLTIP, MOTD, SIGN, BOOK
    }

    private Color primary = Color.web("#12BFA8");
    private Color secondary = Color.web("#FF5555");

    @Override
    public String id() {
        return "rgb-text";
    }

    @Override
    public String name() {
        return I18n.t("tools.rgb.name");
    }

    @Override
    public String description() {
        return I18n.t("tools.rgb.desc");
    }

    @Override
    public String icon() {
        return "🌈";
    }

    @Override
    public Node createPanel(PluginContext context) {
        TextArea input = new TextArea();
        input.setPromptText(I18n.t("tools.rgb.input_hint"));
        input.setPrefRowCount(2);
        input.setWrapText(true);

        TextArea output = new TextArea();
        output.setPrefRowCount(3);
        output.setWrapText(true);
        output.setStyle("-fx-font-family: 'Consolas', monospace;");

        Label status = new Label();
        status.getStyleClass().add("cat-hint");

        // ---- 颜色：代码输入在最前 ----
        TextField hexInput = new TextField("#12BFA8");
        hexInput.setPrefWidth(110);
        hexInput.setStyle("-fx-font-family: 'Consolas', monospace;");
        ColorPicker picker = new ColorPicker(primary);
        picker.setPrefWidth(120);
        CheckBox gradient = new CheckBox(I18n.t("tools.rgb.gradient"));
        ColorPicker picker2 = new ColorPicker(secondary);
        picker2.setPrefWidth(120);
        picker2.setDisable(true);
        Label gradientHint = new Label(I18n.t("tools.rgb.gradient_hint"));
        gradientHint.getStyleClass().add("cat-hint");
        gradientHint.setWrapText(true);
        gradientHint.setVisible(false);
        gradientHint.setManaged(false);

        FlowPane palette = new FlowPane(4, 4);
        palette.setPrefWrapLength(16 * 26);

        // ---- 输出格式 ----
        ComboBox<Format> formatBox = new ComboBox<>();
        formatBox.getItems().setAll(Format.values());
        formatBox.setValue(Format.CONSOLE);
        formatBox.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(Format format, boolean empty) {
                super.updateItem(format, empty);
                setText(empty || format == null ? null : formatLabel(format));
            }
        });
        formatBox.setButtonCell(formatBox.getCellFactory().call(null));

        // ---- 预览背景 ----
        ComboBox<Background> backgroundBox = new ComboBox<>();
        backgroundBox.getItems().setAll(Background.values());
        backgroundBox.setValue(Background.CHAT);
        backgroundBox.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(Background background, boolean empty) {
                super.updateItem(background, empty);
                setText(empty || background == null ? null : backgroundLabel(background));
            }
        });
        backgroundBox.setButtonCell(backgroundBox.getCellFactory().call(null));

        // ---- 样式 ----
        CheckBox bold = new CheckBox(I18n.t("tools.rgb.bold"));
        CheckBox italic = new CheckBox(I18n.t("tools.rgb.italic"));
        CheckBox underline = new CheckBox(I18n.t("tools.rgb.underline"));
        CheckBox strike = new CheckBox(I18n.t("tools.rgb.strike"));
        CheckBox magic = new CheckBox(I18n.t("tools.rgb.magic"));

        StackPane preview = new StackPane();
        preview.setMinHeight(96);
        preview.setAlignment(Pos.CENTER_LEFT);

        // ---- 产生 / 预览 ----
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            String text = input.getText() == null ? "" : input.getText();
            Color from = parseColor(hexInput.getText(), primary);
            primary = from;
            List<Segment> segments = buildSegments(text, from,
                    gradient.isSelected() ? picker2.getValue() : null,
                    bold.isSelected(), italic.isSelected(), underline.isSelected(),
                    strike.isSelected(), magic.isSelected());
            output.setText(encode(segments, formatBox.getValue()));
            preview.getChildren().setAll(previewNode(segments, backgroundBox.getValue()));
            status.setText(I18n.t("tools.rgb.length", visibleLength(text))
                    + " · " + formatLabel(formatBox.getValue()));
        };

        // 颜色方块（选中即套用到「主要颜色」）
        for (char code = '0'; code <= '9'; code++) {
            palette.getChildren().add(swatch(code, picker, hexInput, refresh));
        }
        for (char code = 'a'; code <= 'f'; code++) {
            palette.getChildren().add(swatch(code, picker, hexInput, refresh));
        }

        hexInput.textProperty().addListener((obs, was, now) -> {
            Color parsed = parseColor(now, null);
            if (parsed != null) {
                primary = parsed;
                picker.setValue(parsed);
            }
            refresh[0].run();
        });
        picker.valueProperty().addListener((obs, was, now) -> {
            if (now != null) {
                primary = now;
                String hex = toHex(now);
                if (!hex.equalsIgnoreCase(hexInput.getText())) {
                    hexInput.setText(hex);
                }
            }
            refresh[0].run();
        });
        picker2.valueProperty().addListener((obs, was, now) -> {
            secondary = now == null ? secondary : now;
            refresh[0].run();
        });
        gradient.selectedProperty().addListener((obs, was, now) -> {
            picker2.setDisable(!now);
            gradientHint.setVisible(now);
            gradientHint.setManaged(now);
            refresh[0].run();
        });
        input.textProperty().addListener((obs, was, now) -> refresh[0].run());
        formatBox.valueProperty().addListener((obs, was, now) -> refresh[0].run());
        backgroundBox.valueProperty().addListener((obs, was, now) -> refresh[0].run());
        for (CheckBox box : List.of(bold, italic, underline, strike, magic)) {
            box.selectedProperty().addListener((obs, was, now) -> refresh[0].run());
        }

        Button copy = new Button(I18n.t("tools.rgb.copy"));
        copy.getStyleClass().add("primary-button");
        copy.setOnAction(e -> {
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(output.getText() == null ? "" : output.getText());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            context.info(I18n.t("tools.rgb.name"), I18n.t("tools.rgb.copied"));
        });
        Button clear = new Button(I18n.t("tools.rgb.clear"));
        clear.setOnAction(e -> {
            input.clear();
            output.clear();
            refresh[0].run();
        });

        Label hint = new Label(I18n.t("tools.rgb.hint"));
        hint.getStyleClass().add("cat-hint");
        hint.setWrapText(true);

        HBox colorRow = new HBox(8, new Label(I18n.t("tools.rgb.hex")), hexInput, picker,
                gradient, new Label(I18n.t("tools.rgb.gradient_to")), picker2);
        colorRow.setAlignment(Pos.CENTER_LEFT);
        HBox formatRow = new HBox(8, new Label(I18n.t("tools.rgb.format")), formatBox,
                new Label(I18n.t("tools.rgb.background")), backgroundBox);
        formatRow.setAlignment(Pos.CENTER_LEFT);
        FlowPane styleRow = new FlowPane(10, 6, bold, italic, underline, strike, magic);
        HBox actions = new HBox(8, copy, clear, status);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox panel = new VBox(10,
                hint,
                new Label(I18n.t("tools.rgb.input")), input,
                colorRow, palette, gradientHint,
                new VBox(6, new Label(I18n.t("tools.rgb.style")), styleRow),
                formatRow,
                new Label(I18n.t("tools.rgb.output")), output,
                new Label(I18n.t("tools.rgb.preview")), preview,
                actions);
        panel.setPadding(new Insets(4, 4, 12, 4));
        ScrollPane scroll = new ScrollPane(panel);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("edge-to-edge");

        // 开发期：自动选预览背景（截图/回归验证用）
        String devBackground = System.getProperty("mcl.dev.rgbbg");
        if (devBackground != null && !devBackground.isBlank()) {
            for (Background value : Background.values()) {
                if (value.name().equalsIgnoreCase(devBackground.trim())) {
                    backgroundBox.setValue(value);
                }
            }
        }
        // 开发期：自动勾选样式（b=粗体 i=斜体 u=底线 s=删除线）——截图验证用
        String devStyle = System.getProperty("mcl.dev.rgbstyle");
        if (devStyle != null && !devStyle.isBlank()) {
            String lower = devStyle.toLowerCase(java.util.Locale.ROOT);
            bold.setSelected(lower.contains("b"));
            italic.setSelected(lower.contains("i"));
            underline.setSelected(lower.contains("u"));
            strike.setSelected(lower.contains("s"));
        }
        // 开发期：自动填入并生成（截图/回归验证用）
        String devText = System.getProperty("mcl.dev.autorgb");
        if (devText != null && !devText.isBlank()) {
            input.setText(devText);
            String devGradient = System.getProperty("mcl.dev.rgbgradient");
            if (devGradient != null && !devGradient.isBlank()) {
                gradient.setSelected(true);
            }
            refresh[0].run();
        } else {
            refresh[0].run();
        }
        return scroll;
    }

    // ---------- 模型 ----------

    /** 一段同樣式的文字。 */
    private record Segment(String text, Color color, boolean bold, boolean italic,
                           boolean underline, boolean strike, boolean magic) {
    }

    static List<Segment> buildSegments(String text, Color from, Color to,
                                               boolean bold, boolean italic, boolean underline,
                                               boolean strike, boolean magic) {
        List<Segment> segments = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return segments;
        }
        if (to == null) {
            segments.add(new Segment(text, from, bold, italic, underline, strike, magic));
            return segments;
        }
        // 渐变：逐字插值（空格也参与，看起来才连续）
        int length = Math.max(1, text.length() - 1);
        for (int index = 0; index < text.length(); index++) {
            double ratio = (double) index / length;
            Color color = from.interpolate(to, ratio);
            segments.add(new Segment(String.valueOf(text.charAt(index)), color, bold, italic,
                    underline, strike, magic));
        }
        return segments;
    }

    // ---------- 编码 ----------

    static String encode(List<Segment> segments, Format format) {
        if (segments.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        switch (format) {
            case BBCODE -> {
                Segment first = segments.get(0);
                if (first.bold()) {
                    sb.append("[B]");
                }
                if (first.italic()) {
                    sb.append("[I]");
                }
                if (first.underline()) {
                    sb.append("[U]");
                }
                if (first.strike()) {
                    sb.append("[S]");
                }
                if (sameColor(segments)) {
                    sb.append("[COLOR=").append(toHex(first.color())).append(']');
                    sb.append(textOf(segments));
                    sb.append("[/COLOR]");
                } else {
                    for (Segment segment : segments) {
                        sb.append("[COLOR=").append(toHex(segment.color())).append(']')
                                .append(segment.text()).append("[/COLOR]");
                    }
                }
                if (first.strike()) {
                    sb.append("[/S]");
                }
                if (first.underline()) {
                    sb.append("[/U]");
                }
                if (first.italic()) {
                    sb.append("[/I]");
                }
                if (first.bold()) {
                    sb.append("[/B]");
                }
            }
            case CHAT -> {
                Segment first = segments.get(0);
                if (sameColor(segments)) {
                    sb.append('<').append(toHex(first.color())).append('>');
                    sb.append(textOf(segments));
                } else {
                    for (Segment segment : segments) {
                        sb.append('<').append(toHex(segment.color())).append('>')
                                .append(segment.text());
                    }
                }
            }
            default -> {
                Segment first = segments.get(0);
                if (sameColor(segments)) {
                    // 颜色代码要先写：MC 的颜色代码会清掉前面的样式，顺序反了粗体就没了
                    appendColor(sb, format, first.color());
                    appendStyle(sb, format, first);
                    sb.append(textOf(segments));
                } else {
                    for (Segment segment : segments) {
                        appendColor(sb, format, segment.color());
                        appendStyle(sb, format, segment);
                        sb.append(segment.text());
                    }
                }
                appendReset(sb, format);
            }
        }
        return sb.toString();
    }

    private static void appendColor(StringBuilder sb, Format format, Color color) {
        String hex = toHex(color).substring(1);
        switch (format) {
            case AMP_HASH -> sb.append("&#").append(hex);
            case AMP_HEX -> {
                sb.append("&x");
                for (char digit : hex.toCharArray()) {
                    sb.append('&').append(digit);
                }
            }
            case MOTD_ESCAPED -> {
                sb.append("\\u00A7x");
                for (char digit : hex.toCharArray()) {
                    sb.append("\\u00A7").append(Character.toLowerCase(digit));
                }
            }
            default -> {
                sb.append('§').append('x');
                for (char digit : hex.toCharArray()) {
                    sb.append('§').append(Character.toUpperCase(digit));
                }
            }
        }
    }

    private static void appendStyle(StringBuilder sb, Format format, Segment segment) {
        appendCode(sb, format, segment.magic() ? 'k' : 0);
        appendCode(sb, format, segment.bold() ? 'l' : 0);
        appendCode(sb, format, segment.strike() ? 'm' : 0);
        appendCode(sb, format, segment.underline() ? 'n' : 0);
        appendCode(sb, format, segment.italic() ? 'o' : 0);
    }

    private static void appendCode(StringBuilder sb, Format format, char code) {
        if (code == 0) {
            return;
        }
        switch (format) {
            case AMP_HEX, AMP_HASH -> sb.append('&').append(code);
            case MOTD_ESCAPED -> sb.append("\\u00A7").append(code);
            default -> sb.append('§').append(code);
        }
    }

    private static void appendReset(StringBuilder sb, Format format) {
        switch (format) {
            case AMP_HEX, AMP_HASH -> sb.append("&r");
            case MOTD_ESCAPED -> sb.append("\\u00A7r");
            default -> sb.append("§r");
        }
    }
    private static boolean sameColor(List<Segment> segments) {
        Color first = segments.get(0).color();
        for (Segment segment : segments) {
            if (!same(first, segment.color())) {
                return false;
            }
        }
        return true;
    }

    private static String textOf(List<Segment> segments) {
        StringBuilder sb = new StringBuilder();
        for (Segment segment : segments) {
            sb.append(segment.text());
        }
        return sb.toString();
    }

    // ---------- 预览 ----------

    private static Node previewNode(List<Segment> segments, Background background) {
        TextFlow flow = new TextFlow();
        flow.setLineSpacing(2);
        for (Segment segment : segments) {
            Text text = new Text(segment.text());
            text.setFill(segment.color());
            text.setFont(Font.font("Microsoft YaHei UI",
                    segment.bold() ? FontWeight.BOLD : FontWeight.NORMAL,
                    segment.italic() ? FontPosture.ITALIC : FontPosture.REGULAR, 15));
            text.setUnderline(segment.underline());
            text.setStrikethrough(segment.strike());
            if (segment.italic()) {
                // 中文字型多半没有斜体字面（JavaFX 会直接忽略 font posture），
                // 所以额外挂一个 shear 让它真的斜起来
                text.setStyle("-fx-font-style: italic;");
                text.getTransforms().add(new javafx.scene.transform.Shear(-0.24, 0));
            }
            flow.getChildren().add(text);
        }
        StackPane box = new StackPane();
        box.setPadding(new Insets(14, 18, 14, 18));
        box.setAlignment(Pos.CENTER_LEFT);
        box.setMinHeight(84);
        switch (background) {
            case TOOLTIP -> {
                box.setStyle("-fx-background-color: #1A0A2A; -fx-background-radius: 6;"
                        + " -fx-border-color: #3B1F5C; -fx-border-radius: 6;");
                for (var child : flow.getChildren()) {
                    if (child instanceof Text text) {
                        text.setFill(lighten((Color) text.getFill()));
                    }
                }
            }
            case MOTD -> {
                box.setStyle("-fx-background-color: #14101C; -fx-background-radius: 10;");
                flow.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
                box.setAlignment(Pos.CENTER);
            }
            case SIGN -> {
                box.setStyle("-fx-background-color: linear-gradient(to right, #B08A5A, #9C7846);"
                        + " -fx-background-radius: 6; -fx-border-color: #6E4F2A; -fx-border-radius: 6;");
            }
            case BOOK -> {
                box.setStyle("-fx-background-color: #F2E4C8; -fx-background-radius: 4;"
                        + " -fx-border-color: #D9C39A; -fx-border-radius: 4;");
            }
            default -> box.setStyle("-fx-background-color: rgba(16, 16, 20, 0.86);"
                    + " -fx-background-radius: 8;");
        }
        if (background == Background.CHAT) {
            Text prefix = new Text("[Brisk] ");
            prefix.setFill(Color.web("#FFE9A8"));
            prefix.setFont(Font.font("Microsoft YaHei UI", FontWeight.BOLD, 15));
            flow.getChildren().add(0, prefix);
        }
        if (background == Background.BOOK) {
            for (var child : flow.getChildren()) {
                if (child instanceof Text text) {
                    text.setFill(darken((Color) text.getFill()));
                }
            }
        }
        box.getChildren().add(flow);
        return box;
    }

    private static Color lighten(Color color) {
        return color.interpolate(Color.WHITE, 0.15);
    }

    private static Color darken(Color color) {
        return color.interpolate(Color.web("#3A2A12"), 0.35);
    }

    // ---------- 小工具 ----------

    private static String formatLabel(Format format) {
        return I18n.t(switch (format) {
            case AMP_HEX -> "tools.rgb.format.amp_hex";
            case AMP_HASH -> "tools.rgb.format.amp_hash";
            case CHAT -> "tools.rgb.format.chat";
            case MOTD_ESCAPED -> "tools.rgb.format.motd";
            case CONSOLE -> "tools.rgb.format.console";
            case BBCODE -> "tools.rgb.format.bbcode";
        });
    }

    private static String backgroundLabel(Background background) {
        return I18n.t(switch (background) {
            case CHAT -> "tools.rgb.bg.chat";
            case TOOLTIP -> "tools.rgb.bg.tooltip";
            case MOTD -> "tools.rgb.bg.motd";
            case SIGN -> "tools.rgb.bg.sign";
            case BOOK -> "tools.rgb.bg.book";
        });
    }

    private Node swatch(char code, ColorPicker picker, TextField hexInput, Runnable[] refresh) {
        Color color = McText.colorOf(code);
        Button button = new Button();
        button.setMinSize(22, 22);
        button.setPrefSize(22, 22);
        button.setMaxSize(22, 22);
        button.setStyle("-fx-background-color: " + toHex(color) + ";"
                + " -fx-background-radius: 5; -fx-border-color: #A8D8D1; -fx-border-radius: 5;"
                + " -fx-padding: 0;");
        button.setTooltip(new javafx.scene.control.Tooltip("§" + code + "  " + toHex(color)));
        button.setOnAction(e -> {
            picker.setValue(color);
            hexInput.setText(toHex(color));
            refresh[0].run();
        });
        return button;
    }

    private static Color parseColor(String text, Color fallback) {
        if (text == null) {
            return fallback;
        }
        String value = text.trim();
        if (value.isEmpty()) {
            return fallback;
        }
        if (!value.startsWith("#")) {
            value = "#" + value;
        }
        try {
            return Color.web(value);
        } catch (Exception error) {
            return fallback;
        }
    }

    private static boolean same(Color a, Color b) {
        if (a == null || b == null) {
            return false;
        }
        return Math.abs(a.getRed() - b.getRed()) < 0.004
                && Math.abs(a.getGreen() - b.getGreen()) < 0.004
                && Math.abs(a.getBlue() - b.getBlue()) < 0.004;
    }

    private static String toHex(Color color) {
        return String.format(Locale.ROOT, "#%02X%02X%02X",
                (int) Math.round(color.getRed() * 255),
                (int) Math.round(color.getGreen() * 255),
                (int) Math.round(color.getBlue() * 255));
    }

    private static int visibleLength(String raw) {
        return raw == null ? 0 : raw.replace("\n", " ").length();
    }
}
