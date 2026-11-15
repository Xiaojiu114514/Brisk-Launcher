package com.brisklauncher.mcl.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import com.brisklauncher.mcl.i18n.I18n;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 輕量 Markdown 檢視（GitHub / Gitee release notes 的常見子集）。
 *
 * <p>刻意不引入 {@code javafx.web}：單 exe 已經夠大，而且 release notes 只需要
 * 標題、粗體/斜體、行內碼、程式碼區塊、清單、引用、表格與連結。所有顏色都走
 * {@code -brisk-*} token，主題包與深色主題都能跟著變。
 */
final class MarkdownView {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern UNORDERED = Pattern.compile("^\\s*[-*+]\\s+(.+)$");
    private static final Pattern ORDERED = Pattern.compile("^\\s*(\\d+)[.)]\\s+(.+)$");
    private static final Pattern RULE = Pattern.compile("^\\s*([-*_])(?:\\s*\\1){2,}\\s*$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile(
            "^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$");
    private static final Pattern LINK = Pattern.compile(
            "\\[([^\\]]+)\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");
    private static final Pattern BARE_URL = Pattern.compile("https?://[^\\s<>)]+");
    private static final Pattern HTML_TAG = Pattern.compile("(?is)</?[a-zA-Z][^>]*>");

    private MarkdownView() {
    }

    /** 產生可放進彈窗的滾動區。 */
    static ScrollPane create(String markdown, double maxHeight) {
        VBox content = render(markdown);
        content.setFillWidth(true);
        content.setPadding(new Insets(2, 10, 2, 2));
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setMaxHeight(maxHeight);
        scroll.setPrefViewportWidth(560);
        scroll.setPrefViewportHeight(Math.min(maxHeight, 360));
        scroll.getStyleClass().add("edge-to-edge");
        scroll.setStyle("-fx-background-color: transparent;");
        return scroll;
    }

    private static VBox render(String markdown) {
        VBox box = new VBox(6);
        box.setFillWidth(true);
        String[] lines = normalize(markdown).split("\n", -1);
        int index = 0;
        while (index < lines.length) {
            String raw = lines[index];
            String line = raw.trim();
            if (line.isEmpty()) {
                index++;
                continue;
            }
            if (line.startsWith("```")) {
                index++;
                List<String> code = new ArrayList<>();
                while (index < lines.length && !lines[index].trim().startsWith("```")) {
                    code.add(lines[index]);
                    index++;
                }
                if (index < lines.length) {
                    index++;
                }
                box.getChildren().add(codeBlock(String.join("\n", code)));
                continue;
            }
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                String headingText = heading.group(2).replaceAll("\\s+#+\\s*$", "");
                box.getChildren().add(headingBlock(heading.group(1).length(), headingText));
                index++;
                continue;
            }
            if (RULE.matcher(line).matches()) {
                Region rule = new Region();
                rule.setPrefHeight(1);
                rule.setMinHeight(1);
                rule.setMaxWidth(Double.MAX_VALUE);
                rule.setStyle("-fx-background-color: -brisk-border-soft;");
                box.getChildren().add(rule);
                index++;
                continue;
            }
            if (line.startsWith(">")) {
                List<String> quoteLines = new ArrayList<>();
                while (index < lines.length && lines[index].trim().startsWith(">")) {
                    String value = lines[index].trim();
                    quoteLines.add(value.length() > 1 ? value.substring(1).stripLeading() : "");
                    index++;
                }
                box.getChildren().add(quoteBlock(String.join("\n", quoteLines)));
                continue;
            }
            Matcher unordered = UNORDERED.matcher(raw);
            Matcher ordered = ORDERED.matcher(raw);
            if (unordered.matches() || ordered.matches()) {
                Block block = listBlock(lines, index);
                box.getChildren().add(block.node());
                index = block.next();
                continue;
            }
            if (isTableStart(lines, index)) {
                Block block = tableBlock(lines, index);
                box.getChildren().add(block.node());
                index = block.next();
                continue;
            }
            List<String> paragraphLines = new ArrayList<>();
            while (index < lines.length) {
                String value = lines[index].trim();
                if (value.isEmpty() || isBlockStart(lines, index)) {
                    break;
                }
                paragraphLines.add(value);
                index++;
            }
            if (!paragraphLines.isEmpty()) {
                box.getChildren().add(paragraphBlock(String.join(" ", paragraphLines)));
            }
        }
        if (box.getChildren().isEmpty()) {
            Label empty = new Label(I18n.t("update.no_notes"));
            empty.setWrapText(true);
            empty.getStyleClass().add("cat-hint");
            box.getChildren().add(empty);
        }
        return box;
    }

    private static boolean isBlockStart(String[] lines, int index) {
        String line = lines[index].trim();
        if (line.isEmpty()) {
            return true;
        }
        if (line.startsWith("```") || line.startsWith(">") || HEADING.matcher(line).matches()
                || RULE.matcher(line).matches() || UNORDERED.matcher(lines[index]).matches()
                || ORDERED.matcher(lines[index]).matches()) {
            return true;
        }
        return isTableStart(lines, index);
    }

    private static boolean isTableStart(String[] lines, int index) {
        if (index + 1 >= lines.length || !lines[index].contains("|")) {
            return false;
        }
        return TABLE_SEPARATOR.matcher(lines[index + 1].trim()).matches();
    }

    private static Block listBlock(String[] lines, int start) {
        VBox list = new VBox(3);
        list.setFillWidth(true);
        int index = start;
        while (index < lines.length) {
            String raw = lines[index];
            Matcher unordered = UNORDERED.matcher(raw);
            Matcher ordered = ORDERED.matcher(raw);
            boolean isOrdered = ordered.matches();
            if (!unordered.matches() && !isOrdered) {
                break;
            }
            String value = isOrdered ? ordered.group(2) : unordered.group(1);
            int indent = leadingSpaces(raw) / 2;
            Label marker = new Label(isOrdered ? ordered.group(1) + "." : "•");
            marker.setMinWidth(Region.USE_PREF_SIZE);
            marker.setStyle("-fx-text-fill: -brisk-text-muted; -fx-font-size: 13px;");
            TextFlow flow = inline(value, "-brisk-text");
            HBox.setHgrow(flow, Priority.ALWAYS);
            flow.setMaxWidth(Double.MAX_VALUE);
            HBox row = new HBox(7, marker, flow);
            row.setAlignment(Pos.TOP_LEFT);
            row.setPadding(new Insets(0, 0, 0, Math.min(indent, 8) * 14));
            list.getChildren().add(row);
            index++;
        }
        return new Block(list, index);
    }

    private static Block tableBlock(String[] lines, int start) {
        List<String> rows = new ArrayList<>();
        int index = start;
        while (index < lines.length && lines[index].trim().contains("|")) {
            rows.add(lines[index].trim());
            index++;
        }
        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(4);
        grid.setPadding(new Insets(6, 8, 6, 8));
        grid.setStyle("-fx-background-color: -brisk-surface; -fx-background-radius: 6;"
                + " -fx-border-color: -brisk-border-soft; -fx-border-radius: 6;");
        int rowIndex = 0;
        for (int row = 0; row < rows.size(); row++) {
            if (row == 1) {
                continue;
            }
            List<String> cells = splitTableRow(rows.get(row));
            for (int column = 0; column < cells.size(); column++) {
                TextFlow cell = inline(cells.get(column), row == 0 ? "-brisk-text" : "-brisk-text-muted");
                cell.setStyle((row == 0 ? "-fx-font-weight: bold;" : ""));
                cell.setMaxWidth(260);
                GridPane.setHgrow(cell, Priority.ALWAYS);
                grid.add(cell, column, rowIndex);
            }
            rowIndex++;
        }
        return new Block(grid, index);
    }

    private static List<String> splitTableRow(String row) {
        String value = row.trim();
        if (value.startsWith("|")) {
            value = value.substring(1);
        }
        if (value.endsWith("|")) {
            value = value.substring(0, value.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        for (String cell : value.split("\\|", -1)) {
            cells.add(cell.trim());
        }
        return cells;
    }

    private static Node headingBlock(int level, String text) {
        TextFlow flow = inline(text, "-brisk-text");
        int size = switch (level) {
            case 1 -> 20;
            case 2 -> 18;
            case 3 -> 16;
            case 4 -> 15;
            default -> 14;
        };
        flow.setStyle("-fx-font-size: " + size + "px; -fx-font-weight: bold;");
        flow.setMaxWidth(Double.MAX_VALUE);
        VBox box = new VBox(flow);
        box.setFillWidth(true);
        box.setPadding(new Insets(level <= 2 ? 8 : 3, 0, 2, 0));
        return box;
    }

    private static Node paragraphBlock(String text) {
        TextFlow flow = inline(text, "-brisk-text");
        flow.setLineSpacing(2);
        flow.setMaxWidth(Double.MAX_VALUE);
        VBox box = new VBox(flow);
        box.setFillWidth(true);
        return box;
    }

    private static Node quoteBlock(String text) {
        Region bar = new Region();
        bar.setPrefWidth(3);
        bar.setMinWidth(3);
        bar.setMaxWidth(3);
        bar.setStyle("-fx-background-color: -brisk-border; -fx-background-radius: 2;");
        TextFlow flow = inline(text, "-brisk-text-muted");
        HBox.setHgrow(flow, Priority.ALWAYS);
        flow.setMaxWidth(Double.MAX_VALUE);
        HBox row = new HBox(10, bar, flow);
        row.setAlignment(Pos.TOP_LEFT);
        row.setPadding(new Insets(2, 0, 2, 0));
        return row;
    }

    private static Node codeBlock(String code) {
        Label label = new Label(code);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 12px;"
                + " -fx-text-fill: -brisk-text; -fx-background-color: -brisk-surface-hover;"
                + " -fx-background-radius: 6; -fx-padding: 8;");
        return label;
    }

    private static TextFlow inline(String text, String fillToken) {
        TextFlow flow = new TextFlow();
        flow.setLineSpacing(2);
        String value = text == null ? "" : text;
        int index = 0;
        while (index < value.length()) {
            if (value.startsWith("`", index)) {
                int end = value.indexOf('`', index + 1);
                if (end > index) {
                    add(flow, value.substring(index + 1, end), fillToken,
                            "-fx-font-family: 'Consolas', monospace;");
                    index = end + 1;
                    continue;
                }
            }
            if (value.startsWith("**", index) || value.startsWith("__", index)) {
                String marker = value.substring(index, index + 2);
                int end = value.indexOf(marker, index + 2);
                if (end > index + 2) {
                    add(flow, value.substring(index + 2, end), fillToken, "-fx-font-weight: bold;");
                    index = end + 2;
                    continue;
                }
            }
            if (value.startsWith("~~", index)) {
                int end = value.indexOf("~~", index + 2);
                if (end > index + 2) {
                    add(flow, value.substring(index + 2, end), fillToken, "-fx-strikethrough: true;");
                    index = end + 2;
                    continue;
                }
            }
            if (value.startsWith("[", index)) {
                Matcher link = LINK.matcher(value);
                link.region(index, value.length());
                if (link.lookingAt()) {
                    addLink(flow, link.group(1), link.group(2), fillToken);
                    index = link.end();
                    continue;
                }
            }
            if (value.startsWith("http://", index) || value.startsWith("https://", index)) {
                Matcher url = BARE_URL.matcher(value);
                url.region(index, value.length());
                if (url.lookingAt()) {
                    addLink(flow, url.group(), url.group(), fillToken);
                    index = url.end();
                    continue;
                }
            }
            char current = value.charAt(index);
            if (current == '*' || current == '_') {
                int end = value.indexOf(current, index + 1);
                if (end > index + 1) {
                    add(flow, value.substring(index + 1, end), fillToken, "-fx-font-style: italic;");
                    index = end + 1;
                    continue;
                }
            }
            int next = nextSpecial(value, index + 1);
            add(flow, value.substring(index, next), fillToken, "");
            index = next;
        }
        return flow;
    }

    private static void addLink(TextFlow flow, String label, String url, String fillToken) {
        Hyperlink link = new Hyperlink(label);
        link.setFocusTraversable(false);
        link.setStyle("-fx-text-fill: " + fillToken + "; -fx-padding: 0;"
                + " -fx-border-color: transparent; -fx-underline: true;");
        link.setOnAction(event -> DesktopActions.openUrl(url));
        flow.getChildren().add(link);
    }

    private static void add(TextFlow flow, String text, String fillToken, String extraStyle) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Text node = new Text(text);
        node.setStyle("-fx-fill: " + fillToken + "; " + extraStyle);
        flow.getChildren().add(node);
    }

    private static int nextSpecial(String value, int from) {
        for (int index = from; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '*' || current == '_' || current == '`' || current == '~' || current == '[') {
                return index;
            }
            if (current == 'h' && (value.startsWith("http://", index)
                    || value.startsWith("https://", index))) {
                return index;
            }
        }
        return value.length();
    }

    private static int leadingSpaces(String value) {
        int count = 0;
        while (count < value.length() && (value.charAt(count) == ' ' || value.charAt(count) == '\t')) {
            count += value.charAt(count) == '\t' ? 2 : 1;
        }
        return count;
    }

    private static String normalize(String markdown) {
        String text = markdown == null ? "" : markdown.replace("\r\n", "\n").replace('\r', '\n');
        text = text.replaceAll("(?is)<br\\s*/?>", "\n");
        text = text.replaceAll("(?is)</p\\s*>", "\n\n");
        // 角括號自動連結先轉成標準 Markdown，避免下面的 HTML 清洗把 URL 吃掉
        text = text.replaceAll("<(https?://[^>]+)>", "[$1]($1)");
        text = HTML_TAG.matcher(text).replaceAll("");
        return text.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'");
    }

    private record Block(Node node, int next) {
    }
}
