package com.brisklauncher.mcl.plugin.builtin;

import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.ArrayList;
import java.util.List;

/**
 * Minecraft 旧版文字格式（{@code §} / {@code &} 代码）的解析与渲染。
 *
 * <p>支援：16 色（0-9a-f）、加粗 l、斜体 o、下划线 n、删除线 m、随机 k、重置 r，
 * 以及 1.16+ 的 24 位色 {@code §x§R§R§G§G§B§B}。
 *
 * <p>用途：RGB 文字编辑器的即时预览，以及服务器 MOTD 的颜色渲染
 * （MOTD 就是一段带 {@code §} 代码的字符串）。
 */
public final class McText {

    /** 一段同樣式文字。 */
    public record Segment(String text, Color color, boolean bold, boolean italic,
                          boolean underline, boolean strike, boolean magic) {
    }

    private static final Color[] PALETTE = {
            Color.web("#000000"), Color.web("#0000AA"), Color.web("#00AA00"), Color.web("#00AAAA"),
            Color.web("#AA0000"), Color.web("#AA00AA"), Color.web("#12BFA8"), Color.web("#AAAAAA"),
            Color.web("#4A6A66"), Color.web("#5555FF"), Color.web("#55FF55"), Color.web("#55FFFF"),
            Color.web("#FF5555"), Color.web("#FF55FF"), Color.web("#FFFF55"), Color.web("#FFFFFF")
    };

    private static final String CODES = "0123456789abcdefklmnor";

    /** 段数上限（安全审计 v3 A-13）：每段在渲染时是一个独立的 Text 节点。 */
    private static final int MAX_SEGMENTS = 256;
    /** 可见字元上限：MOTD 本来只有一两行。 */
    private static final int MAX_VISIBLE_CHARS = 2048;

    private McText() {
    }

    /** 色板（0-f），給編輯器的調色盤用。 */
    public static Color[] palette() {
        return PALETTE.clone();
    }

    /** 舊版 16 色代碼（0-f）轉 Color。 */
    public static Color colorOf(char code) {
        int index = CODES.indexOf(Character.toLowerCase(code));
        return index >= 0 && index < 16 ? PALETTE[index] : null;
    }

    /**
     * 解析一段带格式代码的文字（{@code §} 或 {@code &} 都可以）。
     *
     * <p>遇到第一个颜色代码之前的文字用 {@code baseColor}。
     */
    public static List<Segment> parse(String raw, Color baseColor) {
        List<Segment> segments = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return segments;
        }
        // 安全审计 v3 A-13：MOTD 的内容由「你 ping 的服务器」决定。
        // 每个 § 色码会 flush 成一个 Segment，渲染时就是独立 Text 节点 ——
        // 4MB 的「§a§b§c…」能造出约 200 万个节点，直接把界面打死。
        // 这里限字元数与段数：超出后不再新增段，全部并入最后一段（文字仍可见）。
        String text = raw.length() > MAX_VISIBLE_CHARS
                ? raw.substring(0, MAX_VISIBLE_CHARS) : raw;
        StringBuilder buffer = new StringBuilder();
        Color color = baseColor == null ? Color.WHITE : baseColor;
        boolean bold = false;
        boolean italic = false;
        boolean underline = false;
        boolean strike = false;
        boolean magic = false;
        int i = 0;
        while (i < text.length()) {
            char current = text.charAt(i);
            if ((current == '§' || current == '&') && i + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(i + 1));
                // 24 位色：§x§R§R§G§G§B§B
                if (code == 'x' && i + 13 < text.length()) {
                    StringBuilder hex = new StringBuilder();
                    for (int k = 0; k < 6; k++) {
                        int index = i + 2 + k * 2;
                        if (index + 1 >= text.length()) {
                            break;
                        }
                        hex.append(text.charAt(index + 1));
                    }
                    if (hex.length() == 6) {
                        flush(segments, buffer, color, bold, italic, underline, strike, magic);
                        color = safeColor("#" + hex);
                        i += 14;
                        continue;
                    }
                }
                if (CODES.indexOf(code) >= 0) {
                    flush(segments, buffer, color, bold, italic, underline, strike, magic);
                    switch (code) {
                        case 'k' -> magic = true;
                        case 'l' -> bold = true;
                        case 'm' -> strike = true;
                        case 'n' -> underline = true;
                        case 'o' -> italic = true;
                        case 'r' -> {
                            color = baseColor == null ? Color.WHITE : baseColor;
                            bold = italic = underline = strike = magic = false;
                        }
                        default -> {
                            // 颜色代码会重置所有样式（与游戏内行为一致）
                            color = PALETTE[CODES.indexOf(code)];
                            bold = italic = underline = strike = magic = false;
                        }
                    }
                    i += 2;
                    continue;
                }
            }
            buffer.append(current);
            i++;
        }
        flush(segments, buffer, color, bold, italic, underline, strike, magic);
        return segments;
    }

    private static void flush(List<Segment> segments, StringBuilder buffer, Color color,
                              boolean bold, boolean italic, boolean underline,
                              boolean strike, boolean magic) {
        if (buffer.isEmpty()) {
            return;
        }
        if (segments.size() >= MAX_SEGMENTS) {
            // 段数到顶：把文字并进最后一段（保持可见，不再新增节点）
            Segment last = segments.get(segments.size() - 1);
            segments.set(segments.size() - 1, new Segment(
                    last.text() + buffer, last.color(), last.bold(), last.italic(),
                    last.underline(), last.strike(), last.magic()));
            buffer.setLength(0);
            return;
        }
        segments.add(new Segment(buffer.toString(), color, bold, italic, underline, strike, magic));
        buffer.setLength(0);
    }

    /** 把格式代碼文字渲染成可以放進界面的 TextFlow。 */
    public static TextFlow render(String raw, double fontSize, Color baseColor) {
        TextFlow flow = new TextFlow();
        flow.setLineSpacing(2);
        for (Segment segment : parse(raw, baseColor)) {
            Text text = new Text(segment.text());
            text.setFill(segment.color());
            text.setFont(Font.font("Microsoft YaHei UI",
                    segment.bold() ? FontWeight.BOLD : FontWeight.NORMAL,
                    segment.italic() ? FontPosture.ITALIC : FontPosture.REGULAR,
                    fontSize));
            text.setUnderline(segment.underline());
            text.setStrikethrough(segment.strike());
            if (segment.magic()) {
                // 「随机字元」在预览里没法真的抖动，用等宽字体示意
                text.setFont(Font.font("Consolas", fontSize));
            }
            flow.getChildren().add(text);
        }
        return flow;
    }

    /** 去掉所有格式代碼（給複製/純文字用）。 */
    public static String strip(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("[§&][0-9a-fk-orx]", "").replaceAll("[§&][0-9A-Fa-f]", "");
    }

    /** 去掉代碼後的字數（用於「MOTD 最長 59 字元」這類提示）。 */
    public static int visibleLength(String raw) {
        return strip(raw).length();
    }

    private static Color safeColor(String hex) {
        try {
            return Color.web(hex);
        } catch (Exception error) {
            return Color.WHITE;
        }
    }
}
