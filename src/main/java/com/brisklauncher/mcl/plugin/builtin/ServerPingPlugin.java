package com.brisklauncher.mcl.plugin.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.brisklauncher.mcl.i18n.I18n;
import com.brisklauncher.mcl.net.Http;
import com.brisklauncher.mcl.plugin.api.LauncherPlugin;
import com.brisklauncher.mcl.plugin.api.PluginContext;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * 内建插件：服务器 Ping（MOTD / 人数 / 延迟 / 图标预览）。
 *
 * <p>协议实现在 {@link SlpClient}（纯 JDK），这里只负责解析 JSON 与画界面。
 * 直接连目标服务器，量到的延迟就是真实往返时间，不经过第三方查询网站。
 */
public final class ServerPingPlugin implements LauncherPlugin {

    /** 一次 Ping 的解析結果。 */
    private record Status(String host, int port, long latencyMs, String versionName,
                          int protocol, int online, int max, String motd, String favicon,
                          List<String> sample, String error) {
        boolean ok() {
            return error == null;
        }
    }

    private volatile boolean disposed;

    @Override
    public String id() {
        return "server-ping";
    }

    @Override
    public String name() {
        return I18n.t("tools.ping.name");
    }

    @Override
    public String description() {
        return I18n.t("tools.ping.desc");
    }

    @Override
    public String icon() {
        return "📡";
    }

    @Override
    public Node createPanel(PluginContext context) {
        disposed = false;
        TextField address = new TextField();
        address.setPromptText("play.example.com:25565");
        HBox.setHgrow(address, Priority.ALWAYS);

        Button ping = new Button(I18n.t("tools.ping.start"));
        ping.getStyleClass().add("primary-button");

        Label statusLine = new Label(I18n.t("tools.ping.idle"));
        statusLine.getStyleClass().add("cat-hint");
        statusLine.setWrapText(true);

        VBox result = new VBox(10);
        result.setPadding(new Insets(4));

        Label hint = new Label(I18n.t("tools.ping.hint"));
        hint.getStyleClass().add("cat-hint");
        hint.setWrapText(true);

        ping.setOnAction(e -> {
            String raw = address.getText() == null ? "" : address.getText().trim();
            if (raw.isEmpty()) {
                context.info(I18n.t("tools.ping.name"), I18n.t("tools.ping.need_address"));
                return;
            }
            // 安全审计 v3 A-16：SLP 会用这个位址建立 TCP 连接，
            // 指向 127.0.0.1 / 内网时等于「探测本机或内网端口」。自测本机服务器是合理用法，
            // 所以不硬拦，只在第一次问一次（确认记录绑定 acks）。
            if (isLocalTarget(raw) && !com.brisklauncher.mcl.security.Acks.has("pingLoopback")) {
                boolean ok;
                try {
                    ok = com.brisklauncher.mcl.ui.MCLDialog.confirmBlocking(null,
                            I18n.t("tools.ping.loopback_title"),
                            I18n.t("tools.ping.loopback_body", raw));
                } catch (Throwable error) {
                    ok = false;
                }
                if (!ok) {
                    return;
                }
                com.brisklauncher.mcl.security.Acks.record("pingLoopback");
            }
            ping.setDisable(true);
            statusLine.setText(I18n.t("tools.ping.querying", raw));
            result.getChildren().clear();
            Thread.ofVirtual().name("mcl-ping").start(() -> {
                long started = System.nanoTime();
                Status status = query(raw);
                long elapsed = (System.nanoTime() - started) / 1_000_000L;
                Platform.runLater(() -> {
                    if (disposed) {
                        return;
                    }
                    ping.setDisable(false);
                    render(result, status, elapsed);
                    statusLine.setText(status.ok()
                            ? I18n.t("tools.ping.done", status.host(), status.latencyMs())
                            : I18n.t("tools.ping.failed", status.error()));
                });
            });
        });
        address.setOnAction(e -> ping.fire());

        HBox searchRow = new HBox(8, new Label(I18n.t("tools.ping.address")), address, ping);
        searchRow.setAlignment(Pos.CENTER_LEFT);

        VBox panel = new VBox(12, hint, searchRow, statusLine, result);
        panel.setPadding(new Insets(4, 4, 12, 4));
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(panel);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("edge-to-edge");
        // 开发期自动查询（截图/回归验证用，正常启动不会触发）
        String devTarget = System.getProperty("mcl.dev.autoping");
        if (devTarget != null && !devTarget.isBlank()) {
            address.setText(devTarget);
            Platform.runLater(ping::fire);
        }
        return scroll;
    }

    @Override
    public void onUnload() {
        disposed = true;
    }

    // ---------- 结果渲染 ----------

    private void render(VBox result, Status status, long elapsed) {
        result.getChildren().clear();
        if (!status.ok()) {
            Label fail = new Label("🔴 " + status.error());
            fail.setStyle("-fx-text-fill: -brisk-accent-2; -fx-font-weight: bold;");
            fail.setWrapText(true);
            result.getChildren().add(fail);
            return;
        }
        Label header = new Label("🟢 " + status.host() + ":" + status.port()
                + "   ·   " + I18n.t("tools.ping.latency", status.latencyMs()));
        header.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: -brisk-ok;");

        javafx.scene.layout.StackPane icon = new javafx.scene.layout.StackPane();
        icon.setMinSize(64, 64);
        icon.setPrefSize(64, 64);
        icon.setMaxSize(64, 64);
        icon.getStyleClass().add("tag");
        Image favicon = decodeFavicon(status.favicon());
        icon.getChildren().add(favicon == null ? new Label("🖥") : new ImageView(favicon));

        Label version = new Label(I18n.t("tools.ping.version", status.versionName(),
                status.protocol() > 0 ? String.valueOf(status.protocol()) : "?"));
        version.getStyleClass().add("instance-meta");
        Label players = new Label(I18n.t("tools.ping.players", status.online(), status.max()));
        players.getStyleClass().add("instance-meta");
        VBox info = new VBox(4, version, players);
        info.setAlignment(Pos.CENTER_LEFT);

        HBox top = new HBox(12, icon, info);
        top.setAlignment(Pos.CENTER_LEFT);

        Node motd = McText.render(status.motd() == null || status.motd().isBlank()
                ? I18n.t("tools.ping.no_motd") : status.motd(), 17, Color.WHITE);
        javafx.scene.layout.StackPane motdBox = new javafx.scene.layout.StackPane(motd);
        motdBox.getStyleClass().add("preview-pane");
        motdBox.setPadding(new Insets(12));
        motdBox.setAlignment(Pos.CENTER_LEFT);

        result.getChildren().addAll(header, top, motdBox);
        if (!status.sample().isEmpty()) {
            java.util.List<String> visible = new ArrayList<>();
            for (String name : status.sample()) {
                // 样本名称常常带 § 格式码（服务器排版的广告位），显示时去掉
                String clean = McText.strip(name);
                // 安全审计 v3 A-13：单条名字也可能是 4MB（SlpClient 只限总量），
                // 塞进 Label 会把排版卡死 → 每条截断到 48 字元。
                if (clean.length() > 48) {
                    clean = clean.substring(0, 48) + "…";
                }
                visible.add(clean);
            }
            Label sample = new Label(I18n.t("tools.ping.sample", String.join("、", visible)));
            sample.getStyleClass().add("cat-hint");
            sample.setWrapText(true);
            result.getChildren().add(sample);
        }
        Label measured = new Label(I18n.t("tools.ping.address_line",
                status.host() + ":" + status.port(), elapsed));
        measured.getStyleClass().add("cat-hint");
        result.getChildren().add(measured);
    }

    private static Image decodeFavicon(String favicon) {
        if (favicon == null || favicon.isBlank()) {
            return null;
        }
        try {
            String base64 = favicon.substring(favicon.indexOf(',') + 1);
            byte[] bytes = Base64.getDecoder().decode(base64);
            // 安全审计 v3 A-12：favicon 的字节完全由「被 ping 的服务器」控制，必须过尺寸守卫
            Image image = com.brisklauncher.mcl.security.GuardedImages
                    .fromBytes(bytes, 64, 64, true, true);
            return image.isError() ? null : image;
        } catch (Exception error) {
            return null;
        }
    }

    // ---------- 查询 + JSON 解析 ----------

    /**
     * 位址是否指向本机 / 内网（安全审计 v3 A-16）。
     *
     * <p>不解析 DNS（避免「查询本身」就发出请求），只看字面：
     * {@code localhost}、{@code 127.0.0.0/8}、{@code ::1}、{@code 10.x}、
     * {@code 172.16-31.x}、{@code 192.168.x}、{@code 169.254.x}、{@code *.local}。
     */
    static boolean isLocalTarget(String address) {
        if (address == null) {
            return false;
        }
        String host = address.trim();
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) {
            host = host.substring(0, colon);      // 去掉 :port（IPv6 带端口不在这里处理）
        }
        host = host.toLowerCase(java.util.Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.equals("localhost") || host.startsWith("localhost.")
                || host.equals("::1") || host.startsWith("::ffff:127.")) {
            return true;
        }
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return host.endsWith(".local") || host.endsWith(".internal");
        }
        try {
            int first = Integer.parseInt(parts[0]);
            int second = Integer.parseInt(parts[1]);
            if (first == 127 || first == 10) {
                return true;
            }
            if (first == 192 && second == 168) {
                return true;
            }
            if (first == 172 && second >= 16 && second <= 31) {
                return true;
            }
            return first == 169 && second == 254;
        } catch (NumberFormatException error) {
            return false;
        }
    }

    private static Status query(String address) {
        try {
            SlpClient.Response response = SlpClient.ping(address, 8000);
            JsonNode root = Http.mapper().readTree(response.json());
            String versionName = root.path("version").path("name").asText("?");
            int protocol = root.path("version").path("protocol").asInt(0);
            JsonNode playersNode = root.path("players");
            int online = playersNode.path("online").asInt(0);
            int max = playersNode.path("max").asInt(0);
            List<String> sample = new ArrayList<>();
            for (JsonNode node : playersNode.path("sample")) {
                String name = node.path("name").asText("");
                if (!name.isBlank()) {
                    sample.add(name);
                    if (sample.size() >= 12) {
                        break;
                    }
                }
            }
            return new Status(response.host(), response.port(), response.latencyMs(),
                    versionName, protocol, online, max,
                    flattenDescription(root.path("description")),
                    root.path("favicon").asText(""), sample, null);
        } catch (Exception error) {
            String message = error.getMessage();
            return new Status(address, 0, 0, "", 0, 0, 0, "", "", List.of(),
                    (message == null || message.isBlank())
                            ? error.getClass().getSimpleName() : message);
        }
    }

    /**
     * MOTD 的 description 可能是字符串，也可能是 {@code {text, extra:[...]}} 树。
     * 这里摊平成带 {@code §} 代码的字符串，交给 {@link McText} 渲染颜色。
     */
    private static String flattenDescription(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText();
        }
        StringBuilder sb = new StringBuilder();
        appendPart(sb, node);
        return sb.toString();
    }

    private static void appendPart(StringBuilder sb, JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            sb.append(node.asText());
            return;
        }
        char code = colorCode(node.path("color").asText(""));
        if (code != 0) {
            sb.append('§').append(code);
        } else {
            String hex = node.path("color").asText("");
            if (hex.startsWith("#") && hex.length() == 7) {
                // 1.16+ 的 RGB 色：§x§R§R§G§G§B§B
                sb.append("§x");
                for (int i = 1; i < 7; i++) {
                    sb.append('§').append(Character.toLowerCase(hex.charAt(i)));
                }
            }
        }
        if (node.path("bold").asBoolean(false)) {
            sb.append("§l");
        }
        if (node.path("italic").asBoolean(false)) {
            sb.append("§o");
        }
        if (node.path("underlined").asBoolean(false)) {
            sb.append("§n");
        }
        if (node.path("strikethrough").asBoolean(false)) {
            sb.append("§m");
        }
        String text = node.path("text").asText("");
        if (!text.isEmpty()) {
            sb.append(text);
        }
        for (JsonNode extra : node.path("extra")) {
            appendPart(sb, extra);
        }
    }

    /** 命名顏色 → 舊版代碼。 */
    private static char colorCode(String color) {
        return switch (color == null ? "" : color.toLowerCase(Locale.ROOT)) {
            case "black" -> '0';
            case "dark_blue" -> '1';
            case "dark_green" -> '2';
            case "dark_aqua" -> '3';
            case "dark_red" -> '4';
            case "dark_purple" -> '5';
            case "gold" -> '6';
            case "gray" -> '7';
            case "dark_gray" -> '8';
            case "blue" -> '9';
            case "green" -> 'a';
            case "aqua" -> 'b';
            case "red" -> 'c';
            case "light_purple" -> 'd';
            case "yellow" -> 'e';
            case "white" -> 'f';
            default -> 0;
        };
    }
}
