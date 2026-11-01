package com.brisklauncher.mcl.plugin.builtin;

import com.brisklauncher.mcl.i18n.I18n;
import com.brisklauncher.mcl.plugin.LauncherPlugin;
import com.brisklauncher.mcl.plugin.PluginContext;
import com.brisklauncher.mcl.ui.UiKit;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * 内建插件：投影（Litematica / WorldEdit / 原版结构）材料清单导出。
 *
 * <p>做大型建筑最痛的是「材料够不够」：读投影档按方块类型统计数量，
 * 直接算成「几组（64）」与「几箱潜影盒（27 组）」，可导出 txt / csv。
 *
 * <p>方块名称尽量显示中文：直接从该版本客户端 jar 的
 * {@code assets/minecraft/lang/zh_cn.json} 读官方译名（不内建译名表，跟著游戏语言走），
 * 读不到才退回原始 id。
 */
public final class SchematicMaterialsPlugin implements LauncherPlugin {

    private static final int MAX_ROWS = 300;

    private SchematicMaterials.Result current;
    /** 方塊 id → 中文名（靜態快取：一個檔約 100KB，只留最新一份）。 */
    private static Map<String, String> zhNames = Map.of();
    private static String zhSource = "";

    @Override
    public String id() {
        return "schematic-materials";
    }

    @Override
    public String name() {
        return I18n.t("tools.schem.name");
    }

    @Override
    public String description() {
        return I18n.t("tools.schem.desc");
    }

    @Override
    public String icon() {
        return "🧱";
    }

    @Override
    public Node createPanel(PluginContext context) {
        Label status = new Label(I18n.t("tools.schem.idle"));
        status.getStyleClass().add("cat-hint");
        status.setWrapText(true);

        Label summary = new Label();
        summary.setWrapText(true);
        summary.setStyle("-fx-font-size: 13px; -fx-text-fill: #14453F;");

        GridPane table = new GridPane();
        table.setHgap(18);
        table.setVgap(3);
        table.setPadding(new Insets(4));
        ScrollPane tableScroll = new ScrollPane(table);
        tableScroll.setFitToWidth(true);
        tableScroll.getStyleClass().add("edge-to-edge");
        tableScroll.setPrefViewportHeight(320);
        VBox.setVgrow(tableScroll, Priority.ALWAYS);

        Button exportTxt = new Button(I18n.t("tools.schem.export_txt"));
        Button exportCsv = new Button(I18n.t("tools.schem.export_csv"));
        Button copy = new Button(I18n.t("tools.schem.copy"));
        exportTxt.setDisable(true);
        exportCsv.setDisable(true);
        copy.setDisable(true);

        Runnable enableActions = () -> {
            boolean ready = current != null;
            exportTxt.setDisable(!ready);
            exportCsv.setDisable(!ready);
            copy.setDisable(!ready);
        };

        // 版本下拉：切换后「选择投影文件」会直接开该版本的 schematics 文件夹
        ComboBox<PluginContext.InstanceInfo> versionCombo = new ComboBox<>();
        versionCombo.setPrefWidth(260);
        versionCombo.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(PluginContext.InstanceInfo info, boolean empty) {
                super.updateItem(info, empty);
                setText(empty || info == null ? null : info.mcVersion() + " · " + info.id());
            }
        });
        versionCombo.setButtonCell(versionCombo.getCellFactory().call(null));
        versionCombo.getItems().setAll(context.instances());
        boolean matched = false;
        for (PluginContext.InstanceInfo info : versionCombo.getItems()) {
            if (info.id().equals(context.selectedInstanceLabel())) {
                versionCombo.setValue(info);
                matched = true;
                break;
            }
        }
        if (!matched && !versionCombo.getItems().isEmpty()) {
            versionCombo.setValue(versionCombo.getItems().get(0));
        }
        versionCombo.valueProperty().addListener((obs, was, now) -> loadNames(now));

        Button pickButton = new Button(I18n.t("tools.schem.pick"));
        pickButton.getStyleClass().add("primary-button");
        Runnable pickInVersion = () -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.t("tools.schem.pick"));
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                    "Schematic (*.litematic, *.schem, *.schematic, *.nbt)",
                    "*.litematic", "*.schem", "*.schematic", "*.nbt"));
            PluginContext.InstanceInfo info = versionCombo.getValue();
            if (info != null) {
                Path dir = info.gameDir().resolve("schematics");
                if (Files.isDirectory(dir)) {
                    chooser.setInitialDirectory(dir.toFile());
                } else if (Files.isDirectory(info.gameDir())) {
                    chooser.setInitialDirectory(info.gameDir().toFile());
                }
            }
            File file = chooser.showOpenDialog(context.ownerWindow());
            if (file != null) {
                load(context, file.toPath(), status, summary, table, enableActions);
            }
        };
        pickButton.setOnAction(e -> pickInVersion.run());

        // 「在该版本的投影资料夹里挑档案」：直接开档案选择器（不是开资料夹）
        Button pickInVersionButton = new Button(I18n.t("tools.schem.pick_in_version"));
        pickInVersionButton.setOnAction(e -> {
            PluginContext.InstanceInfo info = versionCombo.getValue();
            if (info != null) {
                Path dir = info.gameDir().resolve("schematics");
                if (!Files.isDirectory(dir)) {
                    try {
                        Files.createDirectories(dir);
                    } catch (Exception ignored) {
                        // 建立失败就让选择器落在 gameDir
                    }
                }
            }
            pickInVersion.run();
        });

        exportTxt.setOnAction(e -> export(context, false, status));
        exportCsv.setOnAction(e -> export(context, true, status));
        copy.setOnAction(e -> {
            if (current == null) {
                return;
            }
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(asText());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            status.setText(I18n.t("tools.schem.copied"));
        });

        HBox versionRow = new HBox(8, new Label(I18n.t("tools.schem.version")), versionCombo,
                pickInVersionButton);
        versionRow.setAlignment(Pos.CENTER_LEFT);
        HBox actions = new HBox(8, pickButton, exportTxt, exportCsv, copy);
        actions.setAlignment(Pos.CENTER_LEFT);

        Label hint = new Label(I18n.t("tools.schem.hint"));
        hint.getStyleClass().add("cat-hint");
        hint.setWrapText(true);

        VBox panel = new VBox(10, hint, versionRow, actions, status, summary, tableScroll);
        panel.setPadding(new Insets(4, 4, 12, 4));
        ScrollPane scroll = new ScrollPane(panel);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("edge-to-edge");
        // 面板离开场景（换插件/关工具页）时把解析结果与表格丢掉，避免大投影一直留在记忆体
        scroll.parentProperty().addListener((obs, was, now) -> {
            if (now == null) {
                current = null;
                table.getChildren().clear();
                summary.setText("");
            }
        });

        loadNames(versionCombo.getValue());

        // 开发期：自动载入某个投影档（截图/回归验证用）
        String devFile = System.getProperty("mcl.dev.schematicfile");
        if (devFile != null && !devFile.isBlank()) {
            Platform.runLater(() -> load(context, Path.of(devFile.trim()), status, summary,
                    table, enableActions));
        }
        return scroll;
    }

    // ---------- 译名 ----------

    /** 從指定版本的客戶端 jar 讀 zh_cn.json；讀不到就保留舊的對照表。 */
    private static void loadNames(PluginContext.InstanceInfo info) {
        if (info == null) {
            return;
        }
        Path jar = findClientJar(info);
        if (jar == null) {
            return;
        }
        if (jar.toString().equals(zhSource) && !zhNames.isEmpty()) {
            return;
        }
        // 先试 jar 内的语言档；很多版本的语言档其实在 assets 物件里，再试一次
        if (loadNamesFromJar(jar)) {
            zhSource = jar.toString();
            return;
        }
        loadNamesFromAssets(info);
        if (!zhNames.isEmpty()) {
            zhSource = info.id() + ":assets";
        }
    }

    private static boolean loadNamesFromJar(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry("assets/minecraft/lang/zh_cn.json");
            if (entry == null) {
                return false;
            }
            try (InputStream input = zip.getInputStream(entry)) {
                String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                var node = com.brisklauncher.mcl.net.Http.mapper().readTree(json);
                Map<String, String> names = new LinkedHashMap<>();
                var fields = node.fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    String key = field.getKey();
                    if (key.startsWith("block.minecraft.")) {
                        names.put("minecraft:" + key.substring("block.minecraft.".length()),
                                field.getValue().asText(""));
                    }
                }
                if (!names.isEmpty()) {
                    zhNames = names;
                    return true;
                }
            }
        } catch (Exception error) {
            // 读不到就用原始 id
        }
        return false;
    }

    /**
     * 从 {@code assets/indexes/*.json} 找 {@code minecraft/lang/zh_cn.json} 的杂凑，
     * 再去 {@code assets/objects/<前两码>/<杂凑>} 读。1.13+ 的语言档就在这里。
     */
    private static void loadNamesFromAssets(PluginContext.InstanceInfo info) {
        Path root = info.gameDir();
        for (int depth = 0; depth < 4 && root != null; depth++) {
            Path assets = root.resolve("assets");
            if (Files.isDirectory(assets.resolve("indexes"))) {
                readAssetLang(assets);
                return;
            }
            root = root.getParent();
        }
    }

    private static void readAssetLang(Path assets) {
        try (var stream = Files.list(assets.resolve("indexes"))) {
            List<Path> indexes = stream.filter(file -> file.getFileName().toString().endsWith(".json"))
                    .sorted((a, b) -> {
                        try {
                            return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                        } catch (Exception error) {
                            return 0;
                        }
                    }).toList();
            for (Path index : indexes) {
                var node = com.brisklauncher.mcl.net.Http.mapper().readTree(Files.readAllBytes(index));
                String hash = node.path("objects").path("minecraft/lang/zh_cn.json")
                        .path("hash").asText("");
                if (hash.length() < 2) {
                    continue;
                }
                Path object = assets.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
                if (!Files.isRegularFile(object)) {
                    continue;
                }
                byte[] bytes = Files.readAllBytes(object);
                // 有些物件是 gzip 过的
                String json = isGzip(bytes)
                        ? new String(new java.util.zip.GZIPInputStream(
                        new java.io.ByteArrayInputStream(bytes)).readAllBytes(),
                        StandardCharsets.UTF_8)
                        : new String(bytes, StandardCharsets.UTF_8);
                var lang = com.brisklauncher.mcl.net.Http.mapper().readTree(json);
                Map<String, String> names = new LinkedHashMap<>();
                var fields = lang.fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    if (field.getKey().startsWith("block.minecraft.")) {
                        names.put("minecraft:" + field.getKey().substring("block.minecraft.".length()),
                                field.getValue().asText(""));
                    }
                }
                if (!names.isEmpty()) {
                    zhNames = names;
                    return;
                }
            }
        } catch (Exception error) {
            // 读不到就用原始 id
        }
    }

    private static boolean isGzip(byte[] bytes) {
        return bytes.length > 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B;
    }

    /**
     * 找客户端 jar。
     *
     * <p>整合包实例通常是 {@code inheritsFrom} 一个原版版本、自己没有 jar，
     * 所以找不到时再往同一个 {@code versions/} 目录里找任何一个 {@code <名字>/<名字>.jar}
     * （原版版本一定在那里），用来读官方语言档。
     */
    static Path findClientJar(PluginContext.InstanceInfo info) {
        Path direct = info.gameDir().resolve(info.id() + ".jar");
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        Path nested = info.gameDir().resolve("versions").resolve(info.id())
                .resolve(info.id() + ".jar");
        if (Files.isRegularFile(nested)) {
            return nested;
        }
        // 隔离实例的 gameDir 就是 versions/<id>，上一层才是 versions/
        Path versionsDir;
        Path parent = info.gameDir().getParent();
        if (parent != null && parent.getFileName() != null
                && "versions".equals(parent.getFileName().toString())) {
            versionsDir = parent;
        } else {
            versionsDir = info.gameDir().resolve("versions");
        }
        if (versionsDir != null && Files.isDirectory(versionsDir)) {
            // 优先找与该实例 MC 版本同名的那个 jar
            Path preferred = versionsDir.resolve(info.mcVersion()).resolve(info.mcVersion() + ".jar");
            if (Files.isRegularFile(preferred)) {
                return preferred;
            }
            try (var stream = Files.list(versionsDir)) {
                for (Path dir : stream.filter(Files::isDirectory).toList()) {
                    Path name = dir.getFileName();
                    if (name == null) {
                        continue;
                    }
                    Path jar = dir.resolve(name.toString() + ".jar");
                    if (Files.isRegularFile(jar)) {
                        return jar;
                    }
                }
            } catch (Exception ignored) {
                // 扫不到就算了，退回原始 id
            }
        }
        return null;
    }

    /** 方塊 id → 顯示名稱（中文譯名 + 原始 id）。 */
    private static String displayName(String id) {
        String zh = zhNames.get(id);
        if (zh == null || zh.isBlank()) {
            return id;
        }
        return zh + "  (" + id + ")";
    }

    // ---------- 载入 ----------

    private void load(PluginContext context, Path file, Label status, Label summary,
                      GridPane table, Runnable enableActions) {
        status.setText(I18n.t("tools.schem.loading", file.getFileName().toString()));
        Thread.ofVirtual().name("mcl-schematic").start(() -> {
            try {
                SchematicMaterials.Result parsed = SchematicMaterials.parse(file);
                Platform.runLater(() -> {
                    current = parsed;
                    refresh(summary, table, file);
                    status.setText(I18n.t("tools.schem.loaded",
                            parsed.materials().size(), parsed.totalBlocks()));
                    // 之前这里忘了重新整理按钮状态 → 汇出一直是灰的（使用者回报）
                    enableActions.run();
                });
            } catch (Throwable error) {
                // 安全审计 v2 A-02：StackOverflowError 是 Error 不是 Exception，
                // 用 catch (Exception) 接不住 → 虚拟线程直接死掉、界面永远停在「载入中」。
                // 这里必须接 Throwable，保证 UI 一定回到可操作状态。
                String message = error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage();
                Platform.runLater(() -> status.setText(I18n.t("tools.schem.failed", message)));
            }
        });
    }

    private void refresh(Label summary, GridPane table, Path file) {
        SchematicMaterials.Result data = current;
        summary.setText(I18n.t("tools.schem.summary", file.getFileName().toString(),
                data.format(), data.width(), data.height(), data.length(),
                data.totalBlocks(), data.materials().size())
                + (data.note() == null || data.note().isBlank() ? "" : "\n" + data.note()));

        table.getChildren().clear();
        table.add(header(I18n.t("tools.schem.col.material")), 0, 0);
        table.add(header(I18n.t("tools.schem.col.count")), 1, 0);
        table.add(header(I18n.t("tools.schem.col.stacks")), 2, 0);
        table.add(header(I18n.t("tools.schem.col.boxes")), 3, 0);
        int row = 1;
        for (Map.Entry<String, Integer> entry : data.materials().entrySet()) {
            if (row > MAX_ROWS) {
                Label more = new Label(I18n.t("tools.schem.more", data.materials().size()));
                more.getStyleClass().add("cat-hint");
                table.add(more, 0, row);
                break;
            }
            table.add(new Label(displayName(entry.getKey())), 0, row);
            table.add(new Label(String.valueOf(entry.getValue())), 1, row);
            int stacks = (entry.getValue() + 63) / 64;
            table.add(new Label(String.valueOf(stacks)), 2, row);
            table.add(new Label(String.valueOf((stacks + 26) / 27)), 3, row);
            row++;
        }
    }

    private static Label header(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-weight: bold; -fx-text-fill: #14453F;");
        return label;
    }

    // ---------- 导出 ----------

    private void export(PluginContext context, boolean csv, Label status) {
        if (current == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(csv ? I18n.t("tools.schem.export_csv") : I18n.t("tools.schem.export_txt"));
        chooser.setInitialFileName(csv ? "materials.csv" : "materials.txt");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                csv ? "CSV" : "Text", csv ? "*.csv" : "*.txt"));
        File target = chooser.showSaveDialog(context.ownerWindow());
        if (target == null) {
            return;
        }
        try {
            String content = csv ? asCsv() : asText();
            Files.writeString(target.toPath(), content, StandardCharsets.UTF_8);
            status.setText(I18n.t("tools.schem.exported", target.getAbsolutePath()));
            context.openFolder(target.toPath().getParent());
        } catch (Exception error) {
            context.error(I18n.t("tools.schem.export_txt"), String.valueOf(error.getMessage()));
        }
    }

    private String asText() {
        SchematicMaterials.Result data = current;
        StringBuilder sb = new StringBuilder();
        sb.append(I18n.t("tools.schem.title")).append('\n');
        sb.append("====================\n");
        sb.append(data.format()).append('\n');
        sb.append(String.format(Locale.ROOT, "%s: %d x %d x %d\n",
                I18n.t("tools.schem.size"), data.width(), data.height(), data.length()));
        sb.append(I18n.t("tools.schem.total", data.totalBlocks())).append('\n');
        sb.append('\n');
        for (Map.Entry<String, Integer> entry : data.materials().entrySet()) {
            int stacks = (entry.getValue() + 63) / 64;
            sb.append(String.format(Locale.ROOT, "%-48s %8d  (%d %s)",
                    displayName(entry.getKey()), entry.getValue(), stacks,
                    I18n.t("tools.schem.stacks_short"))).append('\n');
        }
        return sb.toString();
    }

    private String asCsv() {
        SchematicMaterials.Result data = current;
        StringBuilder sb = new StringBuilder();
        sb.append("id,name_zh,count,stacks,shulker_boxes\n");
        for (Map.Entry<String, Integer> entry : data.materials().entrySet()) {
            int stacks = (entry.getValue() + 63) / 64;
            String zh = zhNames.getOrDefault(entry.getKey(), "");
            // 安全审计 v2 A-07：方块 id 是投影档里的任意字符串。
            // 原来只把逗号换成下划线，于是 =cmd|'/c calc'!A1 这类值会被 Excel 当公式执行，
            // 换行/引号还能伪造表格行。现在按 RFC 4180 包裹 + 公式前缀加单引号。
            sb.append(csvCell(entry.getKey())).append(',')
                    .append(csvCell(zh)).append(',')
                    .append(entry.getValue()).append(',')
                    .append(stacks).append(',').append((stacks + 26) / 27).append('\n');
        }
        return sb.toString();
    }

    /**
     * CSV 单元格转义（RFC 4180 + 公式注入防护）。
     *
     * <p>以 {@code = + - @} 或制表符开头的值前面加单引号，Excel / Numbers / LibreOffice
     * 会当成纯文本而不是公式；引号、换行也一并处理，避免伪造表格结构。
     */
    public static String csvCell(String raw) {
        String value = raw == null ? "" : raw.replace("\r", " ").replace("\n", " ").trim();
        if (!value.isEmpty()) {
            char first = value.charAt(0);
            if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t') {
                value = "'" + value;
            }
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
