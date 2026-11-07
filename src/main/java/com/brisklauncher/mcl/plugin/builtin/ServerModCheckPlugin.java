package com.brisklauncher.mcl.plugin.builtin;

import com.brisklauncher.mcl.i18n.I18n;
import com.brisklauncher.mcl.plugin.api.LauncherPlugin;
import com.brisklauncher.mcl.plugin.api.PluginContext;
import com.brisklauncher.mcl.ui.UiKit;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 内建插件：伺服器 Mod 一致性检查。
 *
 * <p>加入别人的伺服器最常见的失败原因是「客户端 Mod 清单跟伺服器对不上」
 * （多了会踢、少了会崩、版本不一致会 handshake 失败）。
 * 这里拿「伺服器整合包 zip」或「伺服器资料夹」跟「目前选中的客户端实例」比对：
 * <ul>
 *   <li>只在伺服器（客户端缺）→ 要补；</li>
 *   <li>只在客户端（伺服器没有）→ 多半要移除；</li>
 *   <li>同名但 SHA-1 不同 → 版本不一致。</li>
 * </ul>
 */
public final class ServerModCheckPlugin implements LauncherPlugin {

    /** 比對結果。 */
    record Diff(Map<String, String> onlyServer, Map<String, String> onlyClient,
                        List<String> mismatch, int compared) {
    }

    @Override
    public String id() {
        return "server-mod-check";
    }

    @Override
    public String name() {
        return I18n.t("tools.modcheck.name");
    }

    @Override
    public String description() {
        return I18n.t("tools.modcheck.desc");
    }

    @Override
    public String icon() {
        return "🧾";
    }

    @Override
    public Node createPanel(PluginContext context) {
        Label target = new Label(clientLabel(context));
        target.getStyleClass().add("cat-hint");
        target.setWrapText(true);

        Label status = new Label(I18n.t("tools.modcheck.idle"));
        status.getStyleClass().add("cat-hint");
        status.setWrapText(true);

        CheckBox ignoreVersion = new CheckBox(I18n.t("tools.modcheck.ignore_version"));
        ignoreVersion.setSelected(true);

        VBox result = new VBox(8);

        // ---- 按服务器 IP 查（Forge/NeoForge 会在状态里公开 mod 清单）----
        TextField address = new TextField();
        address.setPromptText("play.example.com:25566");
        HBox.setHgrow(address, Priority.ALWAYS);
        Button query = new Button(I18n.t("tools.modcheck.query"));
        query.setOnAction(e -> {
            String addressText = address.getText() == null ? "" : address.getText().trim();
            if (addressText.isEmpty()) {
                context.info(I18n.t("tools.modcheck.name"), I18n.t("tools.modcheck.need_address"));
                return;
            }
            query.setDisable(true);
            status.setText(I18n.t("tools.modcheck.comparing"));
            Thread.ofVirtual().name("mcl-modcheck-ping").start(() -> {
                try {
                    String json = SlpClient.ping(addressText, 8000).json();
                    java.util.Set<String> serverIds = parseServerModIds(json);
                    java.util.Set<String> clientIds = clientModIds(context);
                    javafx.application.Platform.runLater(() -> {
                        query.setDisable(false);
                        if (serverIds.isEmpty()) {
                            result.getChildren().clear();
                            status.setText(I18n.t("tools.modcheck.no_modinfo"));
                            result.getChildren().add(UiKit.catHint(
                                    I18n.t("tools.modcheck.ip_hint")));
                            return;
                        }
                        renderIds(result, status, serverIds, clientIds);
                    });
                } catch (Exception error) {
                    javafx.application.Platform.runLater(() -> {
                        query.setDisable(false);
                        status.setText(I18n.t("tools.modcheck.failed",
                                String.valueOf(error.getMessage())));
                    });
                }
            });
        });
        HBox addressRow = new HBox(8, new Label(I18n.t("tools.modcheck.address")), address, query);
        addressRow.setAlignment(Pos.CENTER_LEFT);

        Button pickZip = new Button(I18n.t("tools.modcheck.pick_zip"));
        pickZip.getStyleClass().add("primary-button");
        pickZip.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.t("tools.modcheck.pick_zip"));
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                    "Server pack (*.zip, *.mrpack)", "*.zip", "*.mrpack"));
            File file = chooser.showOpenDialog(context.ownerWindow());
            if (file == null) {
                return;
            }
            pickZip.setDisable(true);
            status.setText(I18n.t("tools.modcheck.comparing"));
            Thread.ofVirtual().name("mcl-modcheck").start(() -> {
                try {
                    Map<String, String> serverMods = readFromZip(file.toPath());
                    Diff diff = compare(context, serverMods, ignoreVersion.isSelected());
                    javafx.application.Platform.runLater(() -> {
                        pickZip.setDisable(false);
                        render(result, status, diff);
                    });
                } catch (Exception error) {
                    javafx.application.Platform.runLater(() -> {
                        pickZip.setDisable(false);
                        status.setText(I18n.t("tools.modcheck.failed",
                                String.valueOf(error.getMessage())));
                    });
                }
            });
        });

        Button pickFolder = new Button(I18n.t("tools.modcheck.pick_folder"));
        pickFolder.setOnAction(e -> {
            javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
            chooser.setTitle(I18n.t("tools.modcheck.pick_folder"));
            File dir = chooser.showDialog(context.ownerWindow());
            if (dir == null) {
                return;
            }
            pickFolder.setDisable(true);
            status.setText(I18n.t("tools.modcheck.comparing"));
            Thread.ofVirtual().name("mcl-modcheck").start(() -> {
                try {
                    Map<String, String> serverMods = readFromFolder(dir.toPath());
                    Diff diff = compare(context, serverMods, ignoreVersion.isSelected());
                    javafx.application.Platform.runLater(() -> {
                        pickFolder.setDisable(false);
                        render(result, status, diff);
                    });
                } catch (Exception error) {
                    javafx.application.Platform.runLater(() -> {
                        pickFolder.setDisable(false);
                        status.setText(I18n.t("tools.modcheck.failed",
                                String.valueOf(error.getMessage())));
                    });
                }
            });
        });

        Label hint = new Label(I18n.t("tools.modcheck.hint"));
        hint.getStyleClass().add("cat-hint");
        hint.setWrapText(true);

        HBox actions = new HBox(8, pickZip, pickFolder);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox panel = new VBox(10, target, hint, addressRow, actions, ignoreVersion, status, result);
        panel.setPadding(new Insets(4, 4, 12, 4));
        ScrollPane scroll = new ScrollPane(panel);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("edge-to-edge");
        return scroll;
    }

    private static String clientLabel(PluginContext context) {
        Path dir = context.selectedInstanceGameDir();
        if (dir == null) {
            return I18n.t("tools.modcheck.no_instance");
        }
        return I18n.t("tools.modcheck.client", context.selectedInstanceLabel(), dir.toString());
    }

    // ---------- 按服务器 IP 比对（状态查询的 modinfo）----------

    /** 平台/加載器本身：不算「Mod」。 */
    private static final java.util.Set<String> PLATFORM_IDS = java.util.Set.of(
            "forge", "neoforge", "minecraft", "fabricloader", "fabric-loader", "fabric",
            "quilt_loader", "java", "mcp");

    /**
     * 从 Server List Ping 的 JSON 里取服务器 Mod 清单。
     *
     * <p>Forge/NeoForge 的状态回应长这样：
     * {@code "modinfo": {"type":"FML","modList":[{"modid":"forge","version":"47.3.11"}, ...]}}；
     * Fabric / 原版没有这个栏位 → 回传空集合（界面会提示改用整合包比对）。
     */
    static java.util.Set<String> parseServerModIds(String json) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        try {
            var root = com.brisklauncher.mcl.net.Http.mapper().readTree(json);
            var modInfo = root.path("modinfo");
            var list = modInfo.has("modList") ? modInfo.path("modList") : modInfo.path("modlist");
            for (var item : list) {
                String id = item.path("modid").asText("");
                if (id.isBlank()) {
                    id = item.path("modId").asText("");
                }
                if (id.isBlank()) {
                    id = item.path("name").asText("");
                }
                String lower = id.toLowerCase(Locale.ROOT);
                if (!lower.isBlank() && !PLATFORM_IDS.contains(lower)) {
                    ids.add(lower);
                }
            }
        } catch (Exception ignored) {
            // 解析失败就当没有 mod 清单
        }
        return ids;
    }

    /** 客戶端實例的 mod id 集合（讀 jar 內的 fabric.mod.json / mods.toml）。 */
    static java.util.Set<String> clientModIds(PluginContext context) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        Path gameDir = context.selectedInstanceGameDir();
        if (gameDir == null) {
            return ids;
        }
        Path modsDir = gameDir.resolve("mods");
        if (!Files.isDirectory(modsDir)) {
            return ids;
        }
        try (var stream = Files.list(modsDir)) {
            for (Path jar : stream.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(".jar"))
                    .toList()) {
                var meta = com.brisklauncher.mcl.game.mod.ModJarMeta.read(jar);
                if (meta != null && !meta.modId().isBlank()) {
                    ids.add(meta.modId().toLowerCase(Locale.ROOT));
                }
            }
        } catch (Exception ignored) {
            // 读不到就当没有
        }
        return ids;
    }

    /** 只按 mod id 比對：服務器有但客戶端沒有 / 客戶端多裝了。 */
    record IdDiff(java.util.List<String> onlyServer, java.util.List<String> onlyClient) {
    }

    static IdDiff diffIds(java.util.Set<String> server, java.util.Set<String> client) {
        java.util.List<String> onlyServer = new java.util.ArrayList<>();
        java.util.List<String> onlyClient = new java.util.ArrayList<>();
        for (String id : server) {
            if (!client.contains(id)) {
                onlyServer.add(id);
            }
        }
        for (String id : client) {
            if (!server.contains(id)) {
                onlyClient.add(id);
            }
        }
        java.util.Collections.sort(onlyServer);
        java.util.Collections.sort(onlyClient);
        return new IdDiff(onlyServer, onlyClient);
    }

    /** 顯示按 id 比對的結果。 */
    private void renderIds(VBox result, Label status, java.util.Set<String> serverIds,
                           java.util.Set<String> clientIds) {
        IdDiff diff = diffIds(serverIds, clientIds);
        result.getChildren().clear();
        status.setText(I18n.t("tools.modcheck.server_mods", serverIds.size())
                + " · " + I18n.t("tools.modcheck.summary", serverIds.size(),
                diff.onlyServer().size(), diff.onlyClient().size(), 0));
        boolean consistent = diff.onlyServer().isEmpty() && diff.onlyClient().isEmpty();
        Label verdict = new Label(consistent
                ? "✅ " + I18n.t("tools.modcheck.consistent")
                : "⚠ " + I18n.t("tools.modcheck.inconsistent"));
        verdict.setStyle("-fx-font-weight: bold; -fx-text-fill: "
                + (consistent ? "#2E7D5B" : "#C0392B") + ";");
        result.getChildren().add(verdict);
        if (!diff.onlyServer().isEmpty()) {
            result.getChildren().add(section(I18n.t("tools.modcheck.only_server"),
                    diff.onlyServer()));
        }
        if (!diff.onlyClient().isEmpty()) {
            result.getChildren().add(section(I18n.t("tools.modcheck.only_client"),
                    diff.onlyClient()));
        }
        if (consistent) {
            result.getChildren().add(UiKit.catHint(I18n.t("tools.modcheck.consistent_hint")));
        }
    }


    private static Map<String, String> readFromZip(Path zipFile) throws Exception {
        Map<String, String> mods = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            for (ZipEntry entry : zip.stream().toList()) {
                String name = entry.getName().replace('\\', '/');
                if (!name.startsWith("mods/") || !name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    continue;
                }
                String fileName = name.substring("mods/".length());
                if (fileName.contains("/")) {
                    continue;
                }
                try (InputStream input = zip.getInputStream(entry)) {
                    mods.put(fileName, sha1(input));
                }
            }
        }
        return mods;
    }

    private static Map<String, String> readFromFolder(Path dir) throws Exception {
        Map<String, String> mods = new LinkedHashMap<>();
        Path modsDir = dir.resolve("mods");
        if (!Files.isDirectory(modsDir)) {
            modsDir = dir;
        }
        try (var stream = Files.list(modsDir)) {
            for (Path path : stream.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString()
                            .toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .toList()) {
                try (InputStream input = Files.newInputStream(path)) {
                    mods.put(path.getFileName().toString(), sha1(input));
                }
            }
        }
        return mods;
    }

    private static String sha1(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) > 0) {
            digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    // ---------- 比对 ----------

    private static Diff compare(PluginContext context, Map<String, String> serverMods,
                                boolean ignoreVersion) {
        Path gameDir = context.selectedInstanceGameDir();
        Map<String, String> clientMods = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (gameDir != null) {
            try {
                clientMods.putAll(readFromFolder(gameDir));
            } catch (Exception ignored) {
                // 读不到就当客户端没有 mod
            }
        }
        return diff(serverMods, clientMods, ignoreVersion);
    }

    /** 純比對（抽出來是為了能單獨驗證，不依賴界面/實例）。 */
    static Diff diff(Map<String, String> serverMods, Map<String, String> clientMods,
                     boolean ignoreVersion) {
        Map<String, String> onlyServer = new LinkedHashMap<>();
        Map<String, String> onlyClient = new LinkedHashMap<>();
        List<String> mismatch = new ArrayList<>();
        for (var entry : serverMods.entrySet()) {
            String clientHash = clientMods.get(entry.getKey());
            if (clientHash == null) {
                onlyServer.put(entry.getKey(), entry.getValue());
            } else if (!ignoreVersion && !clientHash.equalsIgnoreCase(entry.getValue())) {
                mismatch.add(entry.getKey());
            }
        }
        for (var entry : clientMods.entrySet()) {
            if (!serverMods.containsKey(entry.getKey())) {
                onlyClient.put(entry.getKey(), entry.getValue());
            }
        }
        return new Diff(onlyServer, onlyClient, mismatch, serverMods.size());
    }

    // ---------- 显示 ----------

    private void render(VBox result, Label status, Diff diff) {
        result.getChildren().clear();
        status.setText(I18n.t("tools.modcheck.summary", diff.compared(),
                diff.onlyServer().size(), diff.onlyClient().size(), diff.mismatch().size()));

        boolean consistent = diff.onlyServer().isEmpty() && diff.onlyClient().isEmpty()
                && diff.mismatch().isEmpty();
        Label verdict = new Label(consistent
                ? "✅ " + I18n.t("tools.modcheck.consistent")
                : "⚠ " + I18n.t("tools.modcheck.inconsistent"));
        verdict.setStyle("-fx-font-weight: bold; -fx-text-fill: "
                + (consistent ? "#2E7D5B" : "#C0392B") + ";");
        result.getChildren().add(verdict);

        if (!diff.onlyServer().isEmpty()) {
            result.getChildren().add(section(I18n.t("tools.modcheck.only_server"),
                    diff.onlyServer().keySet()));
        }
        if (!diff.onlyClient().isEmpty()) {
            result.getChildren().add(section(I18n.t("tools.modcheck.only_client"),
                    diff.onlyClient().keySet()));
        }
        if (!diff.mismatch().isEmpty()) {
            result.getChildren().add(section(I18n.t("tools.modcheck.mismatch"),
                    diff.mismatch()));
        }
        if (consistent) {
            result.getChildren().add(UiKit.catHint(I18n.t("tools.modcheck.consistent_hint")));
        }
    }

    private static Node section(String title, java.util.Collection<String> items) {
        VBox box = new VBox(3);
        Label label = new Label(title + " (" + items.size() + ")");
        label.getStyleClass().add("section-title");
        box.getChildren().add(label);
        int shown = 0;
        for (String item : items) {
            Label line = new Label("• " + item);
            line.getStyleClass().add("cat-hint");
            line.setWrapText(true);
            box.getChildren().add(line);
            if (++shown >= 40) {
                box.getChildren().add(UiKit.catHint("…"));
                break;
            }
        }
        return box;
    }
}
