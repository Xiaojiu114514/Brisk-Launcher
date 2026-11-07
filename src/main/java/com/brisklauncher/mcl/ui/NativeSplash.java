package com.brisklauncher.mcl.ui;

import com.brisklauncher.mcl.setting.ThemeColors;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;

/**
 * 用「目前主題的顏色」畫一張 JVM 原生啟動圖（動畫 GIF）。
 *
 * <p>為什麼要自己畫：{@code -splash:} 只吃圖片檔，而它在 JavaFX / CSS 引擎起來之前就要顯示，
 * 所以主題色只能事先「燒」進圖片。啟動器在第一次啟動 / 換主題時用這支把
 * {@code <安裝目錄>/splash.gif} 重畫一次，下一次啟動的原生 splash 就會跟主題一致
 * （否則淺色主題會先閃一下深色，使用者回報過這個問題）。
 *
 * <p>設計與 {@link LaunchSplash} 對齊（380×240、圓角卡片、虛線圓環、閃電、標題、滑動進度條），
 * 只用 Java2D，不需要 JavaFX Toolkit，可以安全地在背景執行緒跑。
 */
public final class NativeSplash {

    public static final int WIDTH = 380;
    public static final int HEIGHT = 240;
    // 幀數刻意少：GIF 量化是逐幀做的（實測 24 幀要 ~1.4 秒），12 幀夠順又只花一半時間
    private static final int FRAMES = 12;
    private static final int FRAME_DELAY_MS = 90;

    private NativeSplash() {
    }

    /**
     * 把主題色的啟動圖寫到 {@code target}（先寫暫存檔再原子替換，避免下次啟動讀到半張圖）。
     *
     * @param tokens {@link ThemeColors#current()} 的結果
     */
    public static void write(Path target, Map<String, String> tokens) throws IOException {
        Colors colors = new Colors(tokens);
        List<BufferedImage> frames = new ArrayList<>(FRAMES);
        for (int index = 0; index < FRAMES; index++) {
            frames.add(renderFrame(index, colors));
        }
        Path dir = target.getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path temp = Files.createTempFile(dir, "splash-", ".gif");
        try {
            writeGif(frames, temp);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 盡力而為
                }
            }
        }
    }

    /** 從設計變數取出的五個顏色（解析不到就用內建預設主題的值）。 */
    private record Colors(Color surface, Color border, Color accent, Color text, Color muted,
                          Color borderSoft) {

        private Colors(Map<String, String> tokens) {
            this(ThemeColors.awt(tokens, "-brisk-surface", Color.WHITE),
                    ThemeColors.awt(tokens, "-brisk-border", new Color(0xBF, 0xE9, 0xE2)),
                    ThemeColors.awt(tokens, "-brisk-accent", new Color(0x0E, 0x9C, 0x8D)),
                    ThemeColors.awt(tokens, "-brisk-text", new Color(0x14, 0x45, 0x3F)),
                    ThemeColors.awt(tokens, "-brisk-text-muted", new Color(0x5E, 0x8B, 0x85)),
                    ThemeColors.awt(tokens, "-brisk-border-soft", new Color(0xCB, 0xE9, 0xE4)));
        }
    }

    // ---------- 每一幀 ----------

    private static BufferedImage renderFrame(int index, Colors colors) {
        // 用 RGB 畫，交給 GIF 編碼器量化（自己塞 IndexColorModel 會被再量化一次，實測會糊掉）
        BufferedImage frame = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = frame.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                    RenderingHints.VALUE_STROKE_PURE);

            // 整面填主題底色：GIF 沒有 alpha，畫圓角卡片會在四角留一圈黑邊（使用者回報過）
            g.setColor(colors.surface());
            g.fillRect(0, 0, WIDTH, HEIGHT);

            // 虛線圓環 + 閃電
            int cx = WIDTH / 2;
            int cy = 92;
            int radius = 26;
            g.setColor(colors.accent());
            g.setStroke(new BasicStroke(4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
            int dashes = 8;
            for (int dash = 0; dash < dashes; dash++) {
                int start = (int) Math.round(index * (360.0 / FRAMES) + dash * (360.0 / dashes));
                g.drawArc(cx - radius, cy - radius, radius * 2, radius * 2, start, 26);
            }

            double pulse = 1.0 + 0.16 * Math.sin(index * (2 * Math.PI / FRAMES));
            g.setColor(colors.accent());
            g.fill(bolt(cx, cy, pulse));

            // 標題與狀態
            Font titleFont = new Font("Segoe UI", Font.BOLD, 15);
            drawCentered(g, "Brisk Launcher", titleFont, colors.text(), 142);
            Font statusFont = new Font("Segoe UI", Font.PLAIN, 11);
            String dots = ".".repeat((index / 3) % 4);
            drawCentered(g, "Starting" + dots, statusFont, colors.muted(), 166);

            // 進度條（不確定式：小方塊來回跑）
            int barWidth = 180;
            int barHeight = 4;
            int bx = (WIDTH - barWidth) / 2;
            int by = 186;
            g.setColor(colors.borderSoft());
            g.fillRoundRect(bx, by, barWidth, barHeight, 4, 4);
            int segment = 56;
            double phase = (index % FRAMES) / (double) FRAMES;
            int offset = (int) Math.round(
                    (barWidth - segment) * (0.5 - 0.5 * Math.cos(phase * 2 * Math.PI)));
            g.setColor(colors.accent());
            g.fillRoundRect(bx + offset, by, segment, barHeight, 4, 4);
        } finally {
            g.dispose();
        }
        return frame;
    }

    private static void drawCentered(Graphics2D g, String text, Font font, Color color,
                                     int baseline) {
        g.setFont(font);
        g.setColor(color);
        FontMetrics metrics = g.getFontMetrics(font);
        int width = metrics.stringWidth(text);
        g.drawString(text, (WIDTH - width) / 2, baseline);
    }

    /** 簡化版閃電（24×24 座標系）。 */
    private static Polygon bolt(int cx, int cy, double scale) {
        double s = 1.08 * scale;
        int[] xs = {13, 5, 10, 9, 18, 12};
        int[] ys = {2, 14, 14, 22, 10, 10};
        Polygon polygon = new Polygon();
        for (int i = 0; i < xs.length; i++) {
            polygon.addPoint(
                    (int) Math.round(cx + (xs[i] - 11.5) * s),
                    (int) Math.round(cy + (ys[i] - 12) * s));
        }
        return polygon;
    }

    // ---------- GIF 輸出 ----------

    private static void writeGif(List<BufferedImage> frames, Path target) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(target.toFile())) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            ImageWriteParam params = writer.getDefaultWriteParam();
            for (BufferedImage frame : frames) {
                ImageTypeSpecifier type = ImageTypeSpecifier.createFromRenderedImage(frame);
                IIOMetadata metadata = writer.getDefaultImageMetadata(type, params);
                configureGif(metadata, FRAME_DELAY_MS);
                writer.writeToSequence(new IIOImage(frame, null, metadata), params);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
    }

    /** 設定 GIF 幀延遲（1/100 秒）與無限循環。 */
    private static void configureGif(IIOMetadata metadata, int delayMillis) throws IOException {
        String format = metadata.getNativeMetadataFormatName();
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);

        IIOMetadataNode gce = child(root, "GraphicControlExtension");
        gce.setAttribute("disposalMethod", "none");
        gce.setAttribute("userInputFlag", "FALSE");
        gce.setAttribute("transparentColorFlag", "FALSE");
        gce.setAttribute("delayTime", Integer.toString(Math.max(1, delayMillis / 10)));
        gce.setAttribute("transparentColorIndex", "0");

        IIOMetadataNode extensions = child(root, "ApplicationExtensions");
        IIOMetadataNode loop = new IIOMetadataNode("ApplicationExtension");
        loop.setAttribute("applicationID", "NETSCAPE");
        loop.setAttribute("authenticationCode", "2.0");
        loop.setUserObject(new byte[]{1, 0, 0});
        extensions.appendChild(loop);

        metadata.setFromTree(format, root);
    }

    private static IIOMetadataNode child(IIOMetadataNode root, String name) {
        for (int index = 0; index < root.getLength(); index++) {
            if (root.item(index).getNodeName().equalsIgnoreCase(name)) {
                return (IIOMetadataNode) root.item(index);
            }
        }
        IIOMetadataNode node = new IIOMetadataNode(name);
        root.appendChild(node);
        return node;
    }
}
