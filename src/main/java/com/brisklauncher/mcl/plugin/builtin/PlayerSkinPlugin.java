package com.brisklauncher.mcl.plugin.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.brisklauncher.mcl.i18n.I18n;
import com.brisklauncher.mcl.net.Http;
import com.brisklauncher.mcl.plugin.LauncherPlugin;
import com.brisklauncher.mcl.plugin.PluginContext;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * 内建插件：查询正版（Mojang）玩家的名字、UUID、皮肤与披风。
 *
 * <p>走官方两个公开接口：
 * <ol>
 *   <li>{@code api.mojang.com/users/profiles/minecraft/<名字>} → UUID；</li>
 *   <li>{@code sessionserver.mojang.com/session/minecraft/profile/<UUID>} → textures（base64）。</li>
 * </ol>
 * 查的是「官方正版帐号」，第三方皮肤站（LittleSkin 等）不在这里。
 */
public final class PlayerSkinPlugin implements LauncherPlugin {

    /** 查詢結果。 */
    private record Profile(String name, String uuid, String skinUrl, String capeUrl,
                           String model, byte[] skinBytes, String error) {
        boolean ok() {
            return error == null;
        }
    }

    private volatile boolean disposed;

    @Override
    public String id() {
        return "player-skin";
    }

    @Override
    public String name() {
        return I18n.t("tools.skin.name");
    }

    @Override
    public String description() {
        return I18n.t("tools.skin.desc");
    }

    @Override
    public String icon() {
        return "👤";
    }

    @Override
    public Node createPanel(PluginContext context) {
        disposed = false;
        TextField name = new TextField();
        name.setPromptText("Notch");
        HBox.setHgrow(name, Priority.ALWAYS);

        Button search = new Button(I18n.t("tools.skin.query"));
        search.getStyleClass().add("primary-button");

        Label status = new Label(I18n.t("tools.skin.idle"));
        status.getStyleClass().add("cat-hint");
        status.setWrapText(true);

        VBox result = new VBox(10);
        result.setPadding(new Insets(4));

        Label hint = new Label(I18n.t("tools.skin.hint"));
        hint.getStyleClass().add("cat-hint");
        hint.setWrapText(true);

        search.setOnAction(e -> {
            String target = name.getText() == null ? "" : name.getText().trim();
            if (target.isEmpty()) {
                context.info(I18n.t("tools.skin.name"), I18n.t("tools.skin.need_name"));
                return;
            }
            search.setDisable(true);
            status.setText(I18n.t("tools.skin.querying", target));
            result.getChildren().clear();
            Thread.ofVirtual().name("mcl-skin-lookup").start(() -> {
                Profile profile = query(target);
                Platform.runLater(() -> {
                    if (disposed) {
                        return;
                    }
                    search.setDisable(false);
                    render(context, result, profile);
                    status.setText(profile.ok()
                            ? I18n.t("tools.skin.done", profile.name())
                            : I18n.t("tools.skin.failed", profile.error()));
                });
            });
        });
        name.setOnAction(e -> search.fire());

        HBox row = new HBox(8, new Label(I18n.t("tools.skin.player")), name, search);
        row.setAlignment(Pos.CENTER_LEFT);

        VBox panel = new VBox(12, hint, row, status, result);
        panel.setPadding(new Insets(4, 4, 12, 4));
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(panel);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("edge-to-edge");
        // 开发期自动查询（截图/回归验证用，正常启动不会触发）
        String devTarget = System.getProperty("mcl.dev.autoskin");
        if (devTarget != null && !devTarget.isBlank()) {
            name.setText(devTarget);
            Platform.runLater(search::fire);
        }
        return scroll;
    }

    @Override
    public void dispose() {
        disposed = true;
    }

    // ---------- 查询 ----------

    private static Profile query(String name) {
        try {
            JsonNode user = Http.getJson(
                    java.net.URI.create("https://api.mojang.com/users/profiles/minecraft/"
                            + encode(name)),
                    java.time.Duration.ofSeconds(15));
            String rawId = user.path("id").asText("");
            String realName = user.path("name").asText(name);
            if (rawId.isBlank()) {
                return new Profile(name, "", "", "", "", null, "找不到这个正版玩家");
            }
            JsonNode session = Http.getJson(
                    java.net.URI.create("https://sessionserver.mojang.com/session/minecraft/profile/"
                            + rawId + "?unsigned=false"),
                    java.time.Duration.ofSeconds(15));
            String skinUrl = "";
            String capeUrl = "";
            String model = "classic";
            for (JsonNode property : session.path("properties")) {
                if (!"textures".equals(property.path("name").asText(""))) {
                    continue;
                }
                String value = property.path("value").asText("");
                if (value.isBlank()) {
                    continue;
                }
                String decoded = new String(Base64.getDecoder().decode(value),
                        java.nio.charset.StandardCharsets.UTF_8);
                JsonNode textures = Http.mapper().readTree(decoded).path("textures");
                skinUrl = textures.path("SKIN").path("url").asText("");
                capeUrl = textures.path("CAPE").path("url").asText("");
                model = textures.path("SKIN").path("metadata").path("model").asText("classic");
            }
            byte[] skinBytes = null;
            if (!skinUrl.isBlank()) {
                try {
                    skinBytes = Http.getBytes(java.net.URI.create(skinUrl),
                            java.time.Duration.ofSeconds(15));
                } catch (Exception error) {
                    // 皮肤图下载失败不影响其它资讯
                }
            }
            return new Profile(realName, dashed(rawId), skinUrl, capeUrl,
                    model == null || model.isBlank() ? "classic" : model, skinBytes, null);
        } catch (Exception error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName()
                    : error.getMessage();
            if (message.contains("404")) {
                message = "找不到这个正版玩家（或名字拼错）";
            } else if (message.contains("429")) {
                message = "查询太频繁（Mojang 限流），稍等一分钟再试";
            }
            return new Profile(name, "", "", "", "", null, message);
        }
    }

    // ---------- 渲染 ----------

    private void render(PluginContext context, VBox result, Profile profile) {
        result.getChildren().clear();
        if (!profile.ok()) {
            Label fail = new Label("⚠ " + profile.error());
            fail.setStyle("-fx-text-fill: #0F7C72; -fx-font-weight: bold;");
            fail.setWrapText(true);
            result.getChildren().add(fail);
            return;
        }

        // 头像：按皮肤实际分辨率裁头部（64x64 是 8x8，128x128 的 HD 皮肤是 16x16），
        // 再按整数倍放大显示（不模糊），并叠上帽子层。
        StackPane head = new StackPane();
        head.setMinSize(112, 112);
        head.setPrefSize(112, 112);
        head.setMaxSize(112, 112);
        head.getStyleClass().add("preview-pane");
        if (profile.skinBytes() != null) {
            Image skin = com.brisklauncher.mcl.security.GuardedImages
                    .fromBytes(profile.skinBytes(), 0, 0, false, false);
            if (!skin.isError()) {
                // 用 Canvas 直接放大（关掉平滑）比 ImageView 更锐利，
                // 在高 DPI 缩放下也不会糊成一团
                head.getChildren().add(headCanvas(skin, 112));
            }
        } else {
            head.getChildren().add(new Label("👤"));
        }

        Label title = new Label(profile.name());
        title.setStyle("-fx-font-size: 17px; -fx-font-weight: bold; -fx-text-fill: #14453F;");
        Label uuid = new Label("UUID  " + profile.uuid());
        uuid.getStyleClass().add("instance-meta");
        Label model = new Label(I18n.t("tools.skin.model",
                "slim".equalsIgnoreCase(profile.model()) ? "Slim（Alex）" : "Classic（Steve）"));
        model.getStyleClass().add("instance-meta");
        VBox info = new VBox(4, title, uuid, model);
        info.setAlignment(Pos.CENTER_LEFT);

        HBox top = new HBox(14, head, info);
        top.setAlignment(Pos.CENTER_LEFT);

        Label skinLabel = new Label(I18n.t("tools.skin.skin_url",
                profile.skinUrl().isBlank() ? "—" : profile.skinUrl()));
        skinLabel.getStyleClass().add("cat-hint");
        skinLabel.setWrapText(true);
        Label capeLabel = new Label(I18n.t("tools.skin.cape_url",
                profile.capeUrl().isBlank() ? I18n.t("tools.skin.no_cape") : profile.capeUrl()));
        capeLabel.getStyleClass().add("cat-hint");
        capeLabel.setWrapText(true);

        Button downloadSkin = new Button(I18n.t("tools.skin.download"));
        downloadSkin.setDisable(profile.skinBytes() == null);
        downloadSkin.setOnAction(e -> saveBytes(context, profile.skinBytes(),
                profile.name() + "-skin.png"));
        Button openNa = new Button(I18n.t("tools.skin.open_site"));
        openNa.setOnAction(e -> context.openUrl("https://namemc.com/profile/" + profile.name()));
        Button copyUuid = new Button(I18n.t("tools.skin.copy_uuid"));
        copyUuid.setOnAction(e -> {
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(profile.uuid());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            context.info(I18n.t("tools.skin.name"), I18n.t("tools.skin.uuid_copied"));
        });
        Button copySkin = new Button(I18n.t("tools.skin.copy_skin"));
        copySkin.setDisable(profile.skinUrl().isBlank());
        copySkin.setOnAction(e -> copyText(context, profile.skinUrl(),
                I18n.t("tools.skin.copy_skin")));
        Button copyCape = new Button(I18n.t("tools.skin.copy_cape"));
        copyCape.setDisable(profile.capeUrl().isBlank());
        copyCape.setOnAction(e -> copyText(context, profile.capeUrl(),
                I18n.t("tools.skin.copy_cape")));
        HBox actions = new HBox(8, downloadSkin, copySkin, copyCape, openNa, copyUuid);

        result.getChildren().addAll(top, skinLabel, capeLabel, actions);
    }

    /**
     * 把皮肤头部（含帽子层）画到 Canvas 上，整数倍放大且不做平滑，
     * 出来就是「像素级清晰」而不是被插值糊掉。
     */
    private static javafx.scene.canvas.Canvas headCanvas(Image skin, double size) {
        javafx.scene.canvas.Canvas canvas = new javafx.scene.canvas.Canvas(size, size);
        var gc = canvas.getGraphicsContext2D();
        gc.setImageSmoothing(false);
        // 64x64 皮肤的头是 8x8（在 8,8）；128x128 HD 皮肤是 16x16（在 16,16）
        int scale = skin.getWidth() >= 128 ? 2 : 1;
        int unit = 8 * scale;
        double factor = Math.floor(size / unit) * unit / unit;
        double draw = Math.floor(size / unit) * unit;
        if (draw <= 0) {
            draw = size;
        }
        double offset = (size - draw) / 2;
        gc.drawImage(skin, unit, unit, unit, unit, offset, offset, draw, draw);
        if (skin.getHeight() >= 64 * scale) {
            gc.drawImage(skin, 5 * unit, unit, unit, unit, offset, offset, draw, draw);
        }
        return canvas;
    }

    private static void copyText(PluginContext context, String text, String what) {
        if (text == null || text.isBlank()) {
            context.info(what, I18n.t("tools.skin.no_cape_to_copy"));
            return;
        }
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(text);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        context.info(what, I18n.t("tools.rgb.copied"));
    }

    private static void saveBytes(PluginContext context, byte[] bytes, String suggested) {        if (bytes == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("tools.skin.download"));
        chooser.setInitialFileName(suggested);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG", "*.png"));
        File file = chooser.showSaveDialog(context.ownerWindow());
        if (file == null) {
            return;
        }
        try {
            Path path = file.toPath();
            Files.write(path, bytes);
            context.openFolder(path.getParent());
        } catch (Exception error) {
            context.error(I18n.t("tools.skin.download"), String.valueOf(error.getMessage()));
        }
    }

    private static String dashed(String raw) {
        if (raw == null || raw.length() != 32) {
            return raw == null ? "" : raw;
        }
        return raw.substring(0, 8) + "-" + raw.substring(8, 12) + "-" + raw.substring(12, 16)
                + "-" + raw.substring(16, 20) + "-" + raw.substring(20);
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
