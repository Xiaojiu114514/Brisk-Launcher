package com.brisklauncher.mcl.ui;

import com.brisklauncher.mcl.util.RemoteImages;

import com.brisklauncher.mcl.MclContext;
import com.brisklauncher.mcl.game.LoaderInfo;
import com.brisklauncher.mcl.game.GameInstance;
import com.brisklauncher.mcl.game.GameDirectory;
import com.brisklauncher.mcl.download.DownloadTask;
import com.brisklauncher.mcl.i18n.I18n;
import com.brisklauncher.mcl.game.install.InstallProgress;
import com.brisklauncher.mcl.modpack.ModpackIndex;
import com.brisklauncher.mcl.download.remote.CurseForgeClient;
import com.brisklauncher.mcl.download.remote.LoaderMetaClient;
import com.brisklauncher.mcl.download.remote.MojangApi;
import com.brisklauncher.mcl.download.remote.ModrinthClient;
import com.brisklauncher.mcl.download.remote.SearchTerms;
import com.brisklauncher.mcl.security.SafeLog;
import com.brisklauncher.mcl.setting.LauncherSettings;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 下载页：游戏版本 / 加载器 / Mod / 整合包 / 资源包 / 光影。
 *
 * <p>游戏版本与加载器是“安装到某个大版本文件夹”；
 * Mod/资源包/光影是“下载到某个实例”；整合包是“下载后导入成新实例”。
 */
public final class DownloadPage extends HBox implements MclPage {

    private record Category(String key, String displayKey, String projectType) {
    }

    /** 搜索源：Modrinth（默认，免密钥）与 CurseForge（构建期注入 API key）。 */
    private enum Source {
        MODRINTH("download.source.modrinth"),
        CURSEFORGE("download.source.curseforge");

        private final String labelKey;

        Source(String labelKey) {
            this.labelKey = labelKey;
        }

        String label() {
            return I18n.t(labelKey);
        }
    }

    /**
     * 搜索结果的一条。
     *
     * <p>CurseForge 的项目没有作者 / 环境 / 分类 / 游戏版本字段，这里统一包成
     * {@link ModrinthClient.Project}（缺的字段留空），列表、详情与安装流程就不必为
     * 两个来源各写一份；真正来自哪个源由 {@link #source()} 决定。
     */
    private record SearchHit(Source source, ModrinthClient.Project project, String curseForgeId) {

        static SearchHit modrinth(ModrinthClient.Project project) {
            return new SearchHit(Source.MODRINTH, project, "");
        }

        static SearchHit curseForge(CurseForgeClient.Project project) {
            return new SearchHit(Source.CURSEFORGE,
                    new ModrinthClient.Project(project.id(), project.slug(), project.title(),
                            project.summary(), "", project.downloads(), project.iconUrl(),
                            "", "", "", List.of(), List.of()),
                    project.id());
        }
    }

    /** 一次搜索的参数（两个来源共用；CurseForge 用不到的字段忽略）。 */
    private record SearchQuery(String text, String searchText, boolean translated, String loader,
                               String mcVersion, List<String> environments, String sort, int offset) {
    }

    private static final List<Category> CATEGORIES = List.of(
            new Category("version", "download.category.version", null),
            new Category("mod", "download.category.mod", "mod"),
            new Category("modpack", "download.category.modpack", "modpack"),
            new Category("resourcepack", "download.category.resourcepack", "resourcepack"),
            new Category("shader", "download.category.shader", "shader"));

    private final MclContext context;
    private final StackPane content = new StackPane();
    private final List<MojangApi.VersionMeta> manifest = new ArrayList<>();
    private Category currentCategory = CATEGORIES.get(0);
    private boolean manifestLoaded;
    /** 清单请求正在飞：构造 + onShown 连续建页时不要重复拉。 */
    private boolean manifestLoading;
    /** 等清单回来再填「游戏版本」下拉的搜索面板（避免每个面板各拉一次 manifest）。 */
    private final List<ComboBox<String>> pendingVersionCombos = new ArrayList<>();
    private ListView<MojangApi.VersionMeta> versionList;
    private Label versionStatus;
    private ComboBox<GameDirectory> instanceRootCombo;
    private TextField versionSearch;
    private List<CheckBox> versionFilters = new ArrayList<>();
    private String pendingSearch;
    /** 下一次建目標實例下拉框時要強制選中的實例（前置依賴圖跳轉用，只用一次）。 */
    private String pendingInstanceId;
    /** 上次用過的搜尋來源（同一支啟動器內記住；重開預設 Modrinth）。 */
    private static Source rememberedSource = Source.MODRINTH;
    private final java.util.Map<String, ToggleButton> categoryButtons = new java.util.HashMap<>();

    /**
     * 远端图示快取（上限 40 张 / 8MB）。
     *
     * <p>清单 cell 会反复回收重建，没有快取就等于每次卷动都重新下载+解码同一张图；
     * 加上上限又避免整页专案图示常驻。页面卸载时整块清掉。
     */
    private final com.brisklauncher.mcl.util.ImageCache iconCache =
            com.brisklauncher.mcl.util.ImageCache.forIcons();

    public DownloadPage(MclContext context) {
        this.context = context;
        getStyleClass().add("page");
        setPadding(new Insets(18));
        setSpacing(16);

        VBox side = new VBox(6);
        side.setPrefWidth(175);
        side.setPadding(new Insets(12));
        side.getStyleClass().add("card");

        content.getStyleClass().add("card");
        content.setPadding(new Insets(16));
        VBox.setVgrow(content, Priority.ALWAYS);
        HBox.setHgrow(content, Priority.ALWAYS);

        ToggleGroup group = new ToggleGroup();
        group.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle == null && oldToggle != null) {
                Platform.runLater(() -> oldToggle.setSelected(true));
            }
        });

        for (Category category : CATEGORIES) {
            ToggleButton button = new ToggleButton(I18n.t(category.displayKey()));
            button.setToggleGroup(group);
            button.setMaxWidth(Double.MAX_VALUE);
            button.setAlignment(Pos.CENTER_LEFT);
            button.getStyleClass().add("side-button");
            button.setOnAction(e -> showCategory(category));
            side.getChildren().add(button);
            categoryButtons.put(category.key(), button);
            if (side.getChildren().size() == 1) {
                button.setSelected(true);
            }
        }

        // 第三方内容提示：常驻在左侧栏底部（不打扰、但一直看得到）
        javafx.scene.control.Label thirdPartyNote =
                com.brisklauncher.mcl.ui.UiKit.catHint(I18n.t("download.third_party_note"));
        thirdPartyNote.setWrapText(true);
        thirdPartyNote.setMaxWidth(200);
        if (side instanceof javafx.scene.layout.VBox sideBox) {
            javafx.scene.layout.Region grow = new javafx.scene.layout.Region();
            javafx.scene.layout.VBox.setVgrow(grow, javafx.scene.layout.Priority.ALWAYS);
            sideBox.getChildren().addAll(grow, thirdPartyNote);
        }
        getChildren().addAll(side, content);
        String initialKey = System.getProperty("mcl.download.category", "version");
        Category initial = CATEGORIES.stream()
                .filter(category -> category.key().equals(initialKey))
                .findFirst()
                .orElse(CATEGORIES.get(0));
        currentCategory = initial;
        showCategory(initial);
    }

    public void onShown() {
        // 重新构建当前分类，顺便刷新“安装到哪个文件夹 / 哪个实例”的候选列表
        showCategory(currentCategory);
        if (!manifestLoaded) {
            loadManifest();
        }
    }

    /** 页面被丢弃：清掉图示缓存、清单与列表内容，避免下载页常驻内存。 */
    @Override
    public void onReleased() {
        iconCache.clear();
        com.brisklauncher.mcl.util.RemoteImages.resetFailed();
        manifest.clear();
        pendingVersionCombos.clear();
        versionFilters.clear();
        categoryButtons.clear();
        // 注意：只清数据、不把控件字段置 null；后台回调可能晚一步到达，
        // 留着空控件最多是一次无效果更新，置 null 会变成 NPE 噪音。
        if (versionList != null) {
            versionList.setItems(javafx.collections.FXCollections.observableArrayList());
        }
        content.getChildren().clear();
    }

    private void showCategory(Category category) {
        this.currentCategory = category;
        ToggleButton selected = categoryButtons.get(category.key());
        if (selected != null) {
            selected.setSelected(true);
        }
        content.getChildren().setAll(switch (category.key()) {
            case "version" -> buildVersionPane();
            case "loader" -> buildLoaderPane();
            default -> buildSearchPane(category);
        });
        if (!manifestLoaded) {
            loadManifest();
        }
    }

    /** 外部要求直接打開某個欄目（依賴圖的「打開下載頁」會用）。 */
    public void openCategory(String key) {
        Category target = CATEGORIES.stream()
                .filter(category -> category.key().equals(key))
                .findFirst().orElse(CATEGORIES.get(0));
        showCategory(target);
    }

    // ---------- 游戏版本 ----------

    private VBox buildVersionPane() {
        versionFilters = new ArrayList<>();
        HBox chips = new HBox(12);
        for (String type : List.of("release", "snapshot", "old", "april")) {
            CheckBox box = new CheckBox(I18n.t("download.filter." + type));
            box.setSelected("release".equals(type));
            box.selectedProperty().addListener((obs, oldValue, value) -> refreshVersionList());
            versionFilters.add(box);
            chips.getChildren().add(box);
        }

        versionSearch = new TextField();
        versionSearch.setPromptText(I18n.t("download.search_version"));
        HBox.setHgrow(versionSearch, Priority.ALWAYS);
        versionSearch.textProperty().addListener((obs, oldValue, value) -> refreshVersionList());

        versionList = new ListView<>();
        versionList.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(MojangApi.VersionMeta meta, boolean empty) {
                super.updateItem(meta, empty);
                setText(empty || meta == null ? null
                        : meta.id() + "   [" + meta.type() + "]   " + meta.date());
            }
        });
        VBox.setVgrow(versionList, Priority.ALWAYS);

        instanceRootCombo = rootCombo();
        Button install = new Button(I18n.t("download.install"));
        install.getStyleClass().add("primary-button");
        install.setDisable(true);
        install.setOnAction(e -> installVersion(instanceRootCombo.getValue(),
                versionList.getSelectionModel().getSelectedItem()));

        versionList.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, value) ->
                install.setDisable(value == null || instanceRootCombo.getValue() == null));
        instanceRootCombo.valueProperty().addListener((obs, oldValue, value) ->
                install.setDisable(value == null || versionList.getSelectionModel().getSelectedItem() == null));

        versionStatus = new Label();
        versionStatus.getStyleClass().add("cat-hint");

        VBox box = new VBox(10,
                UiKit.title(I18n.t("download.category.version")),
                chips, versionSearch, versionList, install, versionStatus);
        loadManifest();
        return box;
    }

    private void refreshVersionList() {
        if (versionList == null) {
            return;
        }
        java.util.Set<String> selected = new java.util.LinkedHashSet<>();
        List<String> types = List.of("release", "snapshot", "old", "april");
        for (int i = 0; i < versionFilters.size() && i < types.size(); i++) {
            if (versionFilters.get(i).isSelected()) {
                selected.add(types.get(i));
            }
        }
        String query = versionSearch == null || versionSearch.getText() == null
                ? "" : versionSearch.getText().trim().toLowerCase(Locale.ROOT);

        List<MojangApi.VersionMeta> filtered = manifest.stream()
                .filter(meta -> selected.isEmpty() || selected.contains(categoryOf(meta)))
                .filter(meta -> query.isEmpty() || meta.id().toLowerCase(Locale.ROOT).contains(query))
                .toList();
        versionList.getItems().setAll(filtered);
        if (!filtered.isEmpty()) {
            versionList.getSelectionModel().selectFirst();
        }
        if (versionStatus != null) {
            versionStatus.setText(manifest.isEmpty()
                    ? I18n.t("download.loading")
                    : I18n.t("download.total", filtered.size()));
        }
    }

    private static String categoryOf(MojangApi.VersionMeta meta) {
        String type = meta.type() == null ? "" : meta.type();
        if ("release".equals(type)) {
            return "release";
        }
        if (type.startsWith("old")) {
            return "old";
        }
        if ("snapshot".equals(type)) {
            return isAprilFools(meta) ? "april" : "snapshot";
        }
        return "other";
    }

    private static boolean isAprilFools(MojangApi.VersionMeta meta) {
        String id = meta.id() == null ? "" : meta.id().toLowerCase(Locale.ROOT);
        for (String known : List.of("potato", "oneblockatatime", "20w14infinite",
                "3d shareware", "rv-pre1", "23w13a_or_b", "craftmine", "15w14a",
                "love-and-hugs", "25w14")) {
            if (id.contains(known)) {
                return true;
            }
        }
        String date = meta.date() == null ? "" : meta.date();
        return date.length() >= 10 && "04-01".equals(date.substring(5, 10));
    }

    private void loadManifest() {
        if (manifestLoaded) {
            refreshVersionList();
            return;
        }
        if (manifestLoading) {
            // 构造 + onShown 可能连续触发两次建页：清单只拉一次
            return;
        }
        manifestLoading = true;
        if (versionStatus != null) {
            versionStatus.setText(I18n.t("download.loading"));
        }
        Thread.ofVirtual().name("mcl-manifest").start(() -> {
            try {
                List<MojangApi.VersionMeta> versions = context.mojang().versions(true);
                Platform.runLater(() -> {
                    manifestLoading = false;
                    manifest.clear();
                    manifest.addAll(versions);
                    manifestLoaded = true;
                    refreshVersionList();
                    // 搜索面板的「游戏版本」下拉等这份清单，一起填掉
                    for (ComboBox<String> pending : new ArrayList<>(pendingVersionCombos)) {
                        addReleaseVersions(pending);
                    }
                    pendingVersionCombos.clear();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    manifestLoading = false;
                    pendingVersionCombos.clear();
                    if (versionStatus != null) {
                        versionStatus.setText(e.getMessage());
                    }
                });
            }
        });
    }

    private void installVersion(GameDirectory root, MojangApi.VersionMeta meta) {
        if (root == null || meta == null) {
            return;
        }
        try {
            java.nio.file.Files.createDirectories(root.path());
            long usable = java.nio.file.Files.getFileStore(root.path()).getUsableSpace();
            if (usable > 0 && usable < 1024L * 1024 * 1024) {
                MCLDialog.error(getScene().getWindow(), I18n.t("download.install"),
                        I18n.t("download.no_space"));
                return;
            }
        } catch (Exception ignored) {
            // 取不到磁盘信息时交给下载器报错
        }
        if (!writable(getScene().getWindow(), root.path())) {
            return;
        }
        // 选好版本后交给「安装新版本」视窗：确认目录、选加载器，再开始多线程下载
        InstallVersionDialog.show(getScene().getWindow(), context, meta.id(), root);
    }

    private void startVanillaInstall(GameDirectory root, MojangApi.VersionMeta meta, String instanceName) {
        // 局部变量不能在 lambda 里重新赋值 → 用数组当可变容器
        GameInstance[] holder = new GameInstance[1];
        TaskDialog.run(getScene().getWindow(), I18n.t("download.install_version", meta.id()),
                progress -> context.gameRepository().installVanilla(root, meta.id(), progress),
                installed -> {
                    GameInstance entry = installed;
                    if (instanceName != null && !instanceName.isBlank()
                            && !instanceName.equals(entry.id())) {
                        try {
                            entry = context.gameRepository().rename(entry, instanceName);
                        } catch (Exception error) {
                            com.brisklauncher.mcl.security.SafeLog.warn("实例重命名失败："
                                    + error.getClass().getSimpleName());
                        }
                    }
                    holder[0] = entry;
                    MCLDialog.info(getScene().getWindow(), I18n.t("download.install"),
                            I18n.t("download.installed", entry.id()));
                    installFinished(entry);
                    MCLDialog.confirm(getScene().getWindow(),
                            I18n.t("download.install_loader_question"),
                            I18n.t("download.install_loader_hint", holder[0].id()),
                            () -> LoaderInstallDialog.show(getScene().getWindow(),
                                    context, holder[0], () -> showCategory(currentCategory)));
                });
    }

    // ---------- 加载器 ----------

    private VBox buildLoaderPane() {
        ComboBox<String> mcVersion = new ComboBox<>();
        mcVersion.setEditable(true);
        mcVersion.setPromptText(I18n.t("download.mc_version"));
        fillMcVersions(mcVersion);

        ToggleGroup typeGroup = new ToggleGroup();
        HBox typeRow = new HBox(6);
        for (LoaderInfo.LoaderType type : List.of(LoaderInfo.LoaderType.FABRIC,
                LoaderInfo.LoaderType.FORGE, LoaderInfo.LoaderType.NEOFORGE)) {
            ToggleButton button = new ToggleButton(type.displayName());
            button.setToggleGroup(typeGroup);
            button.getStyleClass().add("filter-chip");
            button.setUserData(type);
            typeRow.getChildren().add(button);
        }

        ComboBox<LoaderMetaClient.LoaderVersion> loaderVersion = new ComboBox<>();
        loaderVersion.setMaxWidth(Double.MAX_VALUE);
        loaderVersion.setPromptText(I18n.t("download.loader_version"));
        loaderVersion.setCellFactory(view -> new ListCell<>() {
            @Override
        

    protected void updateItem(LoaderMetaClient.LoaderVersion version, boolean empty) {
                super.updateItem(version, empty);
                setText(empty || version == null ? null
                        : version.version() + (version.stable() ? "  ★" : ""));
            }
        });

        ComboBox<GameDirectory> rootCombo = rootCombo();
        Button install = new Button(I18n.t("download.install"));
        install.getStyleClass().add("primary-button");
        install.setDisable(true);

        javafx.scene.control.CheckBox fabricApi =
                new javafx.scene.control.CheckBox(I18n.t("download.fabric_api"));
        fabricApi.setSelected(true);

        Label status = new Label(I18n.t("download.loader_hint"));
        status.getStyleClass().add("cat-hint");
        status.setWrapText(true);

        Runnable updateFabricApiVisibility = () -> {
            ToggleButton toggle = (ToggleButton) typeGroup.getSelectedToggle();
            boolean fabric = toggle != null
                    && toggle.getUserData() == LoaderInfo.LoaderType.FABRIC;
            fabricApi.setVisible(fabric);
            fabricApi.setManaged(fabric);
        };

        Runnable loadVersions = () -> {
            String mc = mcVersion.getValue();
            ToggleButton selected = (ToggleButton) typeGroup.getSelectedToggle();
            if (mc == null || mc.isBlank() || selected == null) {
                return;
            }
            LoaderInfo.LoaderType type = (LoaderInfo.LoaderType) selected.getUserData();
            status.setText(I18n.t("download.loading"));
            loaderVersion.getItems().clear();
            Thread.ofVirtual().name("mcl-loader-list").start(() -> {
                try {
                    List<LoaderMetaClient.LoaderVersion> versions =
                            context.gameRepository().availableLoaders(type, mc.trim());
                    // 正式版优先、beta/alpha 排到最后；同组内新版本在前。
                    // 以前直接按元数据顺序，玩家一开下拉就点到 beta（镜像往往还没同步）。
                    java.util.List<LoaderMetaClient.LoaderVersion> sorted =
                            new java.util.ArrayList<>(versions);
                    sorted.sort((a, b) -> {
                        if (a.stable() != b.stable()) {
                            return a.stable() ? -1 : 1;
                        }
                        return compareLoaderVersions(b.version(), a.version());
                    });
                    // 其他启动器的做法：默认不显示 beta/alpha，只列正式版；
                    // 只有在「一个正式版都没有」时才把测试版列出来（否则下拉会是空的）。
                    java.util.List<LoaderMetaClient.LoaderVersion> visible =
                            new java.util.ArrayList<>(sorted);
                    final java.util.List<LoaderMetaClient.LoaderVersion> items = visible;
                    Platform.runLater(() -> {
                        loaderVersion.getItems().setAll(items);
                        if (!items.isEmpty()) {
                            loaderVersion.getSelectionModel().selectFirst();
                        }
                        status.setText(I18n.t("download.total", items.size()));
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> status.setText(e.getMessage()));
                }
            });
        };

        mcVersion.valueProperty().addListener((obs, oldValue, value) -> loadVersions.run());
        typeGroup.selectedToggleProperty().addListener((obs, oldValue, newValue) -> {
            updateFabricApiVisibility.run();
            if (newValue != null) {
                loadVersions.run();
            }
        });
        updateFabricApiVisibility.run();
        loaderVersion.valueProperty().addListener((obs, oldValue, value) ->
                install.setDisable(value == null || rootCombo.getValue() == null));
        rootCombo.valueProperty().addListener((obs, oldValue, value) ->
                install.setDisable(value == null || loaderVersion.getValue() == null));

        typeRow.getChildren().get(0).getStyleClass().add("selected");
        ((ToggleButton) typeRow.getChildren().get(0)).setSelected(true);
        loadVersions.run();

        install.setOnAction(e -> {
            GameDirectory root = rootCombo.getValue();
            LoaderMetaClient.LoaderVersion version = loaderVersion.getValue();
            ToggleButton selected = (ToggleButton) typeGroup.getSelectedToggle();
            if (root == null || version == null || selected == null) {
                return;
            }
            LoaderInfo.LoaderType type = (LoaderInfo.LoaderType) selected.getUserData();
            boolean withFabricApi = fabricApi.isVisible() && fabricApi.isSelected();
            TaskDialog.run(getScene().getWindow(),
                    I18n.t("download.install_loader", type.displayName(), version.version()),
                    progress -> {
                        var entry = context.gameRepository().installLoader(
                                root, type, mcVersion.getValue().trim(), version.version(), progress);
                        if (withFabricApi) {
                            progress.accept(new InstallProgress(InstallProgress.Stage.LOADER, 0.92,
                                    "Fabric API"));
                            try {
                                context.installFabricApi(entry);
                            } catch (Exception error) {
                                com.brisklauncher.mcl.security.SafeLog.warn(
                                        "Fabric API 安装失败：" + error.getClass().getSimpleName());
                            }
                        }
                        return entry;
                    },
                    entry -> {
                        MCLDialog.info(getScene().getWindow(), I18n.t("download.install"),
                                I18n.t("download.installed", entry.id())
                                        + (withFabricApi ? "\n" + I18n.t("download.fabric_api_added",
                                        "Fabric API") : ""));
                        installFinished(entry);
                    });
        });

        VBox box = new VBox(12,
                UiKit.title(I18n.t("download.category.loader")),
                new Label(I18n.t("download.mc_version")), mcVersion,
                new Label(I18n.t("download.loader_type")), typeRow,
                new Label(I18n.t("download.loader_version")), loaderVersion,
                new HBox(8, new Label(I18n.t("download.target_root")), rootCombo),
                fabricApi, install, status);
        return box;
    }

    private void fillMcVersions(ComboBox<String> combo) {
        List<String> installed = new ArrayList<>();
        for (GameDirectory root : context.gameRepository().roots()) {
            for (GameInstance entry : context.gameRepository().versions(root)) {
                if (!installed.contains(entry.mcVersion())) {
                    installed.add(entry.mcVersion());
                }
            }
        }
        // 版本列表统一从新到旧
        installed.sort((left, right) -> compareVersionAsc(right, left));
        combo.getItems().setAll(installed);
        if (!installed.isEmpty()) {
            combo.setValue(installed.get(0));
        }
        Thread.ofVirtual().name("mcl-mc-versions").start(() -> {
            try {
                List<MojangApi.VersionMeta> versions = context.mojang().versions();
                List<String> releases = versions.stream()
                        .filter(MojangApi.VersionMeta::isRelease)
                        .map(MojangApi.VersionMeta::id)
                        .toList();
                Platform.runLater(() -> {
                    List<String> merged = new ArrayList<>(installed);
                    for (String release : releases) {
                        if (!merged.contains(release)) {
                            merged.add(release);
                        }
                    }
                    String previous = combo.getValue();
                    combo.getItems().setAll(merged);
                    if (previous != null) {
                        combo.setValue(previous);
                    }
                });
            } catch (Exception ignored) {
                // 离线时只用已安装版本
            }
        });
    }

    // ---------- Mod / 整合包 / 资源包 / 光影 ----------

    // ---------- Mod / 资源包 / 光影搜索 ----------

    private VBox buildSearchPane(Category category) {
        TextField query = new TextField();
        query.setPromptText(I18n.t("download.search_hint"));
        HBox.setHgrow(query, Priority.ALWAYS);
        Button searchButton = new Button(I18n.t("common.search"));

        // 下载源：Modrinth / CurseForge。CurseForge 需要构建期注入的 API key；
        // 没有 key 时**不禁用**选项，切过去在结果区提示「API 为空」。
        ComboBox<Source> source = new ComboBox<>();
        source.getItems().setAll(Source.values());
        // 下拉项与按钮都用本地化名称（枚举的 toString 不能带 i18n）
        source.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Source entry) {
                return entry == null ? "" : entry.label();
            }

            @Override
            public Source fromString(String text) {
                return null;
            }
        });
        // 开发期：截图用，直接选 CurseForge（-Dmcl.dev.downloadsource=curseforge）
        Source devSource = "curseforge".equalsIgnoreCase(
                System.getProperty("mcl.dev.downloadsource", ""))
                ? Source.CURSEFORGE : rememberedSource;
        source.getSelectionModel().select(devSource);

        ComboBox<String> loader = new ComboBox<>();
        boolean loaderVisible = "mod".equals(category.key()) || "modpack".equals(category.key());
        if (loaderVisible) {
            loader.getItems().add(I18n.t("download.all_loaders"));
            loader.getItems().addAll("fabric", "forge", "neoforge");
            loader.getSelectionModel().selectFirst();
        }
        loader.setVisible(loaderVisible);
        loader.setManaged(loaderVisible);

        ComboBox<String> gameVersion = new ComboBox<>();
        fillSearchGameVersions(gameVersion);

        CheckBox client = new CheckBox(I18n.t("download.env.client"));
        CheckBox server = new CheckBox(I18n.t("download.env.server"));
        boolean environmentVisible = "mod".equals(category.key());
        client.setVisible(environmentVisible);
        client.setManaged(environmentVisible);
        server.setVisible(environmentVisible);
        server.setManaged(environmentVisible);

        ListView<SearchHit> results = new ListView<>();
        results.setPlaceholder(UiKit.catHint(I18n.t("download.popular_placeholder")));
        results.setCellFactory(view -> new ProjectCell());
        VBox.setVgrow(results, Priority.ALWAYS);
        HBox.setHgrow(results, Priority.ALWAYS);

        ImageView detailIcon = new ImageView();
        detailIcon.setFitWidth(56);
        detailIcon.setFitHeight(56);
        detailIcon.setPreserveRatio(true);
        Label detailTitle = new Label(I18n.t("download.detail.title"));
        detailTitle.getStyleClass().add("instance-name");
        detailTitle.setWrapText(true);
        Label detailMeta = new Label();
        detailMeta.getStyleClass().add("cat-hint");
        Label detailDesc = new Label();
        detailDesc.setWrapText(true);
        detailDesc.getStyleClass().add("instance-meta");
        FlowPane detailTags = new FlowPane(4, 4);
        // 「在 Modrinth / CurseForge / MC 百科 打开」：
        // 三个按钮很占高度，会把下面「可用版本」清单压到只剩一行 →
        // 改成**可折叠**（预设收起，只留一行细小的「外部连结」开关）。
        ToggleButton linksToggle = new ToggleButton(I18n.t("download.open.toggle"));
        linksToggle.getStyleClass().add("link-toggle");
        linksToggle.setFocusTraversable(false);
        linksToggle.setVisible(false);
        linksToggle.setManaged(false);
        FlowPane detailLinks = new FlowPane(6, 6);
        detailLinks.setVisible(false);
        detailLinks.setManaged(false);
        linksToggle.selectedProperty().addListener((obs, was, now) -> {
            boolean open = Boolean.TRUE.equals(now);
            detailLinks.setVisible(open);
            detailLinks.setManaged(open);
            linksToggle.setText(I18n.t(open ? "download.open.toggle_hide" : "download.open.toggle"));
        });
        VBox linksBox = new VBox(6, linksToggle, detailLinks);
        TreeView<Object> versions = new TreeView<>();
        versions.setShowRoot(false);
        versions.setMaxWidth(Double.MAX_VALUE);
        versions.setStyle("-fx-alignment: top-left;");
        // 版本清单至少要放得下几行，不可以被上面的说明文字压成一条线
        versions.setMinHeight(200);
        versions.setPrefHeight(260);
        versions.setCellFactory(view -> new TreeCell<>() {
            @Override
            protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                if (item instanceof ModrinthClient.VersionFile file) {
                    setText((file.versionNumber().isBlank() ? file.displayName() : file.versionNumber())
                            + "  [" + String.join("/", file.loaders())
                            + " · " + versionRange(file.gameVersions()) + "]");
                    setStyle("-fx-font-size: 12px;");
                } else {
                    setText(String.valueOf(item));
                    setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
                }
            }
        });
        Label dependencyLabel = new Label(I18n.t("download.detail.no_deps"));
        dependencyLabel.getStyleClass().add("cat-hint");
        dependencyLabel.setWrapText(true);
        Button install = new Button(I18n.t("download.detail.install"));
        install.getStyleClass().add("primary-button");
        install.setMaxWidth(Double.MAX_VALUE);
        install.setDisable(true);

        // 只有这一张卡片内部自适应：fitToHeight 让内容纵向撑满视口，
        // 版本树用 VGrow 吃掉剩余高度（原来下面留一大片空白），
        // 但卡片本身的宽高与整页布局保持原样（不再用写死的 260/360）。
        VBox detailContent = new VBox(8,
                new HBox(8, detailIcon, new VBox(2, detailTitle, detailMeta)),
                detailTags, detailDesc, linksBox,
                new Label(I18n.t("download.detail.versions")), versions,
                new Label(I18n.t("download.detail.deps")), dependencyLabel);
        VBox.setVgrow(versions, Priority.ALWAYS);
        versions.setMaxHeight(Double.MAX_VALUE);
        ScrollPane detailScroll = new ScrollPane(detailContent);
        detailScroll.setFitToWidth(true);
        detailScroll.setFitToHeight(true);
        detailScroll.setStyle("-fx-background-color: transparent;");
        detailContent.setPadding(new Insets(4));
        VBox.setVgrow(detailScroll, Priority.ALWAYS);
        // 让「安装此版本」始终贴在这张卡片的底部，并与左侧「下载到实例」那一行齐平：
        // 卡片本身随左侧列表一起撑满整行高度。
        VBox detailPane = new VBox(8, detailScroll);
        detailPane.setPrefWidth(300);
        detailPane.setMinWidth(280);
        detailPane.setMaxHeight(Double.MAX_VALUE);
        detailPane.getStyleClass().add("card");
        detailPane.setPadding(new Insets(10));
        install.setMaxWidth(Double.MAX_VALUE);

        AtomicReference<SearchHit> selectedProject = new AtomicReference<>();
        AtomicReference<ModrinthClient.VersionFile> selectedFile = new AtomicReference<>();
        AtomicReference<List<ModrinthClient.Dependency>> selectedDeps =
                new AtomicReference<>(List.of());
        AtomicReference<List<ModrinthClient.VersionFile>> currentFiles =
                new AtomicReference<>(List.of());
        TreeItem<Object> versionRoot = new TreeItem<>("root");
        versions.setRoot(versionRoot);

        ComboBox<GameDirectory> rootCombo = rootCombo();
        ComboBox<GameInstance> instanceCombo = new ComboBox<>();
        instanceCombo.setMaxWidth(Double.MAX_VALUE);
        instanceCombo.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(GameInstance entry, boolean empty) {
                super.updateItem(entry, empty);
                setText(empty || entry == null ? null : entry.id() + " · " + entry.subtitle());
            }
        });
        Runnable refreshInstances = () -> {
            GameDirectory root = rootCombo.getValue();
            if (root == null) {
                instanceCombo.getItems().clear();
                return;
            }
            List<GameInstance> entries = context.gameRepository().versions(root);
            instanceCombo.getItems().setAll(entries);
            if (!entries.isEmpty()) {
                selectPreferredInstance(instanceCombo, entries, root);
            }
        };
        rootCombo.valueProperty().addListener((obs, oldValue, value) -> refreshInstances.run());
        refreshInstances.run();

        java.util.function.BiConsumer<List<ModrinthClient.VersionFile>, GameInstance> populate =
                (files, instance) -> {
                    currentFiles.set(files);
                    versionRoot.getChildren().clear();
                    selectedFile.set(null);
                    if (files.isEmpty()) {
                        install.setDisable(true);
                        return;
                    }
                    int selectedIndex = compatibleFileIndex(files, instance);
                    TreeItem<Object> selectedItem = null;
                    String instanceLoader = instanceLoaderKey(instance);
                    String instanceMajor = instance == null ? "" : majorGroup(instance.mcVersion());
                    // 加载器 → 大版本（26.x）→ 小版本（26.1.2）→ 具体 Mod 版本
                    java.util.Map<String, java.util.Map<String, java.util.Map<String, TreeItem<Object>>>> groups =
                            new java.util.TreeMap<>(DownloadPage::compareLoaderGroup);
                    for (int i = 0; i < files.size(); i++) {
                        ModrinthClient.VersionFile file = files.get(i);
                        String loaderKey = loaderKeyOf(file);
                        String gameKey = representativeVersion(file, instance);
                        String major = majorGroup(gameKey);
                        TreeItem<Object> minorItem = groups
                                .computeIfAbsent(loaderKey, key -> new java.util.TreeMap<>(DownloadPage::compareGroup))
                                .computeIfAbsent(major, key -> new java.util.TreeMap<>(DownloadPage::compareGroup))
                                .computeIfAbsent(gameKey, key -> new TreeItem<>("    " + key));
                        TreeItem<Object> fileItem = new TreeItem<>(file);
                        minorItem.getChildren().add(fileItem);
                        if (i == selectedIndex) {
                            selectedItem = fileItem;
                        }
                    }
                    java.util.List<TreeItem<Object>> defaultExpand = new ArrayList<>();
                    for (var loaderEntry : groups.entrySet()) {
                        TreeItem<Object> loaderItem = new TreeItem<>(loaderLabel(loaderEntry.getKey()));
                        boolean loaderMatches = loaderEntry.getKey().equals(instanceLoader);
                        for (var majorEntry : loaderEntry.getValue().entrySet()) {
                            TreeItem<Object> majorItem = new TreeItem<>(majorEntry.getKey());
                            boolean majorMatches = loaderMatches
                                    && majorEntry.getKey().equals(instanceMajor);
                            for (TreeItem<Object> minorItem : majorEntry.getValue().values()) {
                                majorItem.getChildren().add(minorItem);
                                if (majorMatches && instance != null
                                        && minorItem.getValue().toString().trim()
                                        .equals(instance.mcVersion())) {
                                    defaultExpand.add(minorItem);
                                }
                            }
                            majorItem.setExpanded(false);
                            loaderItem.getChildren().add(majorItem);
                            if (majorMatches) {
                                defaultExpand.add(majorItem);
                            }
                        }
                        loaderItem.setExpanded(false);
                        versionRoot.getChildren().add(loaderItem);
                        if (loaderMatches) {
                            defaultExpand.add(loaderItem);
                        }
                    }
                    if (selectedItem != null) {
                        // 默认展开当前选中版本所在的整条路径，其余折叠
                        for (TreeItem<Object> node = selectedItem.getParent(); node != null; node = node.getParent()) {
                            node.setExpanded(true);
                        }
                        versions.getSelectionModel().select(selectedItem);
                        TreeItem<Object> scrollTarget = selectedItem.getParent() == null
                                ? selectedItem : selectedItem.getParent();
                        versions.scrollTo(versions.getRow(scrollTarget));
                        install.setDisable(false);
                    } else {
                        for (TreeItem<Object> node : defaultExpand) {
                            node.setExpanded(true);
                        }
                        install.setDisable(true);
                    }
                };

        instanceCombo.valueProperty().addListener((obs, oldValue, value) -> {
            if (!currentFiles.get().isEmpty()) {
                populate.accept(currentFiles.get(), value);
            }
        });

        Runnable clearDetail = () -> {
            selectedProject.set(null);
            selectedFile.set(null);
            selectedDeps.set(List.of());
            currentFiles.set(List.of());
            detailTitle.setText(I18n.t("download.detail.title"));
            detailMeta.setText("");
            detailDesc.setText("");
            detailTags.getChildren().clear();
            detailLinks.getChildren().clear();
            detailLinks.setVisible(false);
            detailLinks.setManaged(false);
            linksToggle.setSelected(false);
            linksToggle.setVisible(false);
            linksToggle.setManaged(false);
            detailIcon.setImage(null);
            versionRoot.getChildren().clear();
            versions.setDisable(true);
            install.setDisable(true);
            dependencyLabel.setText(I18n.t("download.detail.no_deps"));
        };

        Label status = new Label();
        status.getStyleClass().add("cat-hint");
        status.setWrapText(true);

        Runnable updateDependencies = () -> {
            SearchHit hit = selectedProject.get();
            ModrinthClient.VersionFile file = selectedFile.get();
            if (hit == null || file == null) {
                return;
            }
            selectedDeps.set(file.dependencies());
            List<ModrinthClient.Dependency> required = file.dependencies().stream()
                    .filter(dependency -> "required".equals(dependency.type()))
                    .toList();
            if (required.isEmpty()) {
                dependencyLabel.setText(I18n.t("download.detail.no_deps"));
                install.setDisable(false);
                return;
            }
            dependencyLabel.setText(I18n.t("download.loading"));
            CompletableFuture.supplyAsync(() -> dependencyNames(required))
                    .thenAccept(names -> Platform.runLater(() -> {
                        // 玩家已经换版本 / 清空详情：旧请求的依赖名不要再写回去
                        if (selectedFile.get() != file) {
                            return;
                        }
                        dependencyLabel.setText(I18n.t("download.detail.deps_list",
                                String.join("、", names)));
                        install.setDisable(false);
                    }));
        };

        Runnable showDetail = () -> {
            SearchHit hit = selectedProject.get();
            if (hit == null) {
                clearDetail.run();
                return;
            }
            ModrinthClient.Project project = hit.project();
            detailTitle.setText(project.title());
            detailMeta.setText(metaLine(hit));
            detailDesc.setText(project.description());
            detailTags.getChildren().setAll(tagsFor(hit));
            // 外部连结：预设收起（只显示一个小开关），需要时再展开，避免压缩版本清单
            detailLinks.getChildren().setAll(
                    externalLink(I18n.t("download.open.modrinth"), modrinthUrl(project), status),
                    externalLink(I18n.t("download.open.curseforge"),
                            curseforgeUrl(hit, category), status),
                    externalLink(I18n.t("download.open.mcmod"), mcmodUrl(project), status));
            linksToggle.setSelected(false);
            linksToggle.setVisible(true);
            linksToggle.setManaged(true);
            // 开发期：截图用，直接展开外部连结（-Dmcl.dev.openlinks=1）
            if (System.getProperty("mcl.dev.openlinks") != null) {
                linksToggle.setSelected(true);
            }
            if (project.iconUrl() != null && !project.iconUrl().isBlank()) {
                String url = project.iconUrl();
                String key = "detail:" + url;
                Image cachedIcon = iconCache.get(key);
                detailIcon.setImage(cachedIcon);
                if (cachedIcon == null) {
                    RemoteImages.load(url, 56, 56, image -> {
                        iconCache.put(key, image);
                        detailIcon.setImage(image);
                    });
                }
            } else {
                detailIcon.setImage(null);
            }
            versions.setDisable(true);
            install.setDisable(true);
            dependencyLabel.setText(I18n.t("download.detail.no_deps"));
            String loaderValue = loaderValue(loader);
            String mcValue = mcValue(gameVersion);
            if (hit.source() == Source.CURSEFORGE) {
                // CurseForge 只有一个「最新相容文件」：没有明确筛选时跟随目标实例，
                // 免得默认给玩家装一个跟实例版本对不上的文件。
                GameInstance target = instanceCombo.getValue();
                String cfLoader = loaderValue;
                String cfMc = mcValue;
                if (target != null) {
                    if (cfLoader == null) {
                        String key = instanceLoaderKey(target);
                        cfLoader = "vanilla".equals(key) || "datapack".equals(key) ? null : key;
                    }
                    if (cfMc == null) {
                        cfMc = target.mcVersion();
                    }
                }
                showCurseForgeFile(hit, cfLoader, cfMc, instanceCombo, versions, install,
                        dependencyLabel, detailTags, populate, selectedProject,
                        !"modpack".equals(category.key()));
                return;
            }
            CompletableFuture.supplyAsync(() -> context.modrinth().versions(project.id(),
                            loaderValue == null ? List.of() : List.of(loaderValue),
                            mcValue == null ? List.of() : List.of(mcValue)))
                    .thenAccept(files -> Platform.runLater(() -> {
                        // 玩家已经切走 / 切了来源 / 清空详情：旧请求的结果不能再填进详情卡
                        if (selectedProject.get() != hit) {
                            return;
                        }
                        versions.setDisable(files.isEmpty());
                        populate.accept(files, instanceCombo.getValue());
                        if (files.isEmpty()) {
                            dependencyLabel.setText(I18n.t("download.version_none"));
                        }
                    })).exceptionally(error -> {
                        Platform.runLater(() -> {
                            if (selectedProject.get() == hit) {
                                dependencyLabel.setText(
                                        I18n.t("download.version_fetch_failed", rootMessage(error)));
                            }
                        });
                        return null;
                    });
        };

        // 快速上下选（键盘 / 滚轮扫过）时不要每个条目都发一次「版本 / 文件」请求：
        // 选中后等 250ms，只有停下来才真正去取详情。
        javafx.animation.PauseTransition detailDebounce =
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(250));
        detailDebounce.setOnFinished(event -> showDetail.run());
        results.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, value) -> {
            selectedProject.set(value);
            selectedFile.set(null);
            install.setDisable(true);
            detailDebounce.stop();
            if (value == null) {
                showDetail.run();
            } else {
                detailDebounce.playFromStart();
            }
        });
        versions.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, item) -> {
            ModrinthClient.VersionFile file = item != null
                    && item.getValue() instanceof ModrinthClient.VersionFile value ? value : null;
            selectedFile.set(file);
            install.setDisable(file == null);
            if (file != null) {
                updateDependencies.run();
            }
        });

        java.util.concurrent.atomic.AtomicInteger page = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger totalHits =
                new java.util.concurrent.atomic.AtomicInteger(0);
        Button previousPage = new Button(I18n.t("download.prev"));
        Button nextPage = new Button(I18n.t("download.next"));
        Label pageLabel = new Label();
        HBox pager = new HBox(8, previousPage, pageLabel, nextPage);
        pager.setAlignment(Pos.CENTER_LEFT);

        java.util.concurrent.atomic.AtomicInteger searchGeneration =
                new java.util.concurrent.atomic.AtomicInteger(0);
        // 同一条件的搜索在 600ms 内只打一次：防双击 / 重复事件造成的并发请求
        java.util.concurrent.atomic.AtomicReference<String> lastSearchSignature =
                new java.util.concurrent.atomic.AtomicReference<>("");
        java.util.concurrent.atomic.AtomicLong lastSearchAt =
                new java.util.concurrent.atomic.AtomicLong(0);
        java.util.function.IntConsumer runSearch = requestedPage -> {
            int requested = Math.max(0, requestedPage);
            String text = query.getText() == null ? "" : query.getText().trim();
            Source selectedSource = source.getValue() == null ? Source.MODRINTH : source.getValue();
            String loaderValue = loaderValue(loader);
            String mcValue = mcValue(gameVersion);
            String signature = selectedSource + "|" + text + "|" + loaderValue + "|" + mcValue
                    + "|" + client.isSelected() + "|" + server.isSelected() + "|" + requested;
            long now = System.currentTimeMillis();
            if (signature.equals(lastSearchSignature.get()) && now - lastSearchAt.get() < 600L) {
                return;
            }
            lastSearchSignature.set(signature);
            lastSearchAt.set(now);
            // 切来源 / 翻页会产生多个后台请求；只让最后一次的结果上屏，避免旧结果盖新的
            int generation = searchGeneration.incrementAndGet();
            page.set(requested);
            status.setText(I18n.t("download.loading"));
            results.getItems().clear();
            clearDetail.run();
            rememberedSource = selectedSource;
            boolean curseForge = selectedSource == Source.CURSEFORGE;
            if (curseForge && mirrorFirst()) {
                // 镜像回源慢（实测 10–17 秒）：先说明一下，别让人以为卡死
                status.setText(I18n.t("download.loading") + "  ·  "
                        + I18n.t("download.curseforge.mirror_slow"));
            }
            // 没有 key 也照常搜：CurseForgeClient 会走镜像源（设置页下载源决定先跑哪个）
            results.setPlaceholder(UiKit.catHint(I18n.t("download.popular_placeholder")));
            List<String> environments = new ArrayList<>();
            if (client.isSelected()) {
                environments.add("client");
            }
            if (server.isSelected()) {
                environments.add("server");
            }
            String sort = text.isEmpty() ? "downloads" : null;
            int offset = page.get() * 20;
            // 中文关键词：先用翻好的英文去搜（混着中文 Modrinth / CurseForge 基本搜不到），
            // 英文也没结果时再退回原文。
            String englishQuery = SearchTerms.hasCjk(text) ? SearchTerms.translate(text) : "";
            boolean translated = !englishQuery.isBlank();
            SearchQuery searchQuery = new SearchQuery(text, translated ? englishQuery : text,
                    translated, loaderValue, mcValue, environments, sort, offset);

            java.util.function.BiConsumer<List<SearchHit>, Integer> applyHits = (hits, total) -> {
                if (total <= 0) {
                    results.getItems().clear();
                    status.setText(I18n.t("download.empty_result"));
                    previousPage.setDisable(page.get() <= 0);
                    nextPage.setDisable(true);
                    pageLabel.setText("");
                    return;
                }
                results.getItems().setAll(hits);
                int pages = Math.max(1, (total + 19) / 20);
                String base = text.isEmpty()
                        ? I18n.t("download.popular_count", total)
                        : I18n.t("download.total", total);
                status.setText(base + "  ·  " + I18n.t("download.page", page.get() + 1, pages)
                        + (translated ? "  " + I18n.t("download.chinese_hint") : ""));
                previousPage.setDisable(page.get() <= 0);
                nextPage.setDisable(page.get() + 1 >= pages);
                pageLabel.setText(I18n.t("download.page", page.get() + 1, pages));
                if (!hits.isEmpty()) {
                    results.getSelectionModel().selectFirst();
                }
            };
            java.util.function.Consumer<String> showError = message -> {
                results.getItems().clear();
                results.setPlaceholder(UiKit.catHint(message));
                status.setText(message);
                previousPage.setDisable(true);
                nextPage.setDisable(true);
                pageLabel.setText("");
            };

            if (curseForge) {
                searchCurseForge(category, searchQuery, result -> {
                    if (searchGeneration.get() != generation) {
                        return;
                    }
                    if (!result.isSuccess()) {
                        showError.accept(curseForgeError(result.errorMessage()));
                        return;
                    }
                    totalHits.set(result.totalHits());
                    applyHits.accept(result.projects().stream().map(SearchHit::curseForge).toList(),
                            result.totalHits());
                    if (result.viaMirror()) {
                        // 让玩家看得出这次是镜像返回的
                        status.setText(status.getText() + "  ·  "
                                + I18n.t("download.curseforge.via_mirror"));
                    }
                });
                return;
            }
            searchModrinth(category, searchQuery, result -> {
                if (searchGeneration.get() != generation) {
                    return;
                }
                if (!result.isSuccess()) {
                    showError.accept(result.errorMessage());
                    return;
                }
                totalHits.set(result.totalHits());
                applyHits.accept(result.projects().stream().map(SearchHit::modrinth).toList(),
                        result.totalHits());
            });
        };
        previousPage.setOnAction(e -> runSearch.accept(page.get() - 1));
        nextPage.setOnAction(e -> runSearch.accept(page.get() + 1));
        searchButton.setOnAction(e -> runSearch.accept(0));
        query.setOnAction(e -> runSearch.accept(0));
        // CurseForge 的额外说明：只在选 CurseForge 时显示（无前置一键安装 / 镜像慢 / 官方要 key）
        Label sourceHint = UiKit.catHint(I18n.t("download.curseforge.hint"));
        sourceHint.setWrapText(true);
        boolean initialCurseForge = source.getValue() == Source.CURSEFORGE;
        sourceHint.setVisible(initialCurseForge);
        sourceHint.setManaged(initialCurseForge);
        // 切换来源：清空后立刻重搜；客户端/服务端筛选只对 Modrinth 有意义（CurseForge 不支援）
        source.valueProperty().addListener((obs, oldValue, value) -> {
            boolean curseForge = value == Source.CURSEFORGE;
            client.setVisible(environmentVisible && !curseForge);
            client.setManaged(environmentVisible && !curseForge);
            server.setVisible(environmentVisible && !curseForge);
            server.setManaged(environmentVisible && !curseForge);
            sourceHint.setVisible(curseForge);
            sourceHint.setManaged(curseForge);
            if (value != null) {
                runSearch.accept(0);
            }
        });

        install.setOnAction(e -> {
            SearchHit hit = selectedProject.get();
            ModrinthClient.VersionFile file = selectedFile.get();
            if (hit == null || file == null) {
                return;
            }
            GameDirectory root = rootCombo.getValue();
            GameInstance instance = instanceCombo.getValue();
            if ("modpack".equals(category.key())) {
                importModpackProject(hit, root, mcValue(gameVersion));
                return;
            }
            if (instance == null) {
                MCLDialog.info(getScene().getWindow(), I18n.t("download.download_to_instance"),
                        I18n.t("download.no_instance"));
                return;
            }
            installWithDependencies(hit, file, instance, category.key(),
                    loaderValue(loader), mcValue(gameVersion));
        });

        HBox searchBar = new HBox(8, query, searchButton);
        HBox sourceBox = new HBox(6, new Label(I18n.t("download.source")), source);
        sourceBox.setAlignment(Pos.CENTER_LEFT);
        HBox filters = new HBox(10, sourceBox, client, server, loader, gameVersion);
        filters.setAlignment(Pos.CENTER_LEFT);
        VBox filterBox = new VBox(6, filters, sourceHint);
        HBox targetRow = "modpack".equals(category.key())
                ? new HBox(8, new Label(I18n.t("download.target_root")), rootCombo)
                : new HBox(8, new Label(I18n.t("download.target_instance")), instanceCombo);
        targetRow.setAlignment(Pos.CENTER_LEFT);


        HBox body = new HBox(12, results, detailPane);
        VBox.setVgrow(body, Priority.ALWAYS);
        HBox.setHgrow(results, Priority.ALWAYS);

        // 「前置一键下载最新」：默认开启（只对 Mod 分类显示）。
        // 关闭时恢复旧行为：逐个前置让玩家自己挑版本。
        CheckBox depsLatest = new CheckBox(I18n.t("download.deps.latest_toggle"));
        depsLatest.setSelected(context.uiState().dependenciesLatest());
        depsLatest.setTooltip(new javafx.scene.control.Tooltip(I18n.t("download.deps.latest_toggle_hint")));
        depsLatest.selectedProperty().addListener((obs, was, now) ->
                context.uiState().setDependenciesLatest(Boolean.TRUE.equals(now)));

        // 「安装此版本」放在页面底部一行，和左侧「下载到实例」齐平
        javafx.scene.layout.Region bottomSpacer = new javafx.scene.layout.Region();
        HBox.setHgrow(bottomSpacer, Priority.ALWAYS);
        HBox bottomBar = new HBox(10, targetRow, depsLatest, bottomSpacer, install);
        bottomBar.setAlignment(Pos.CENTER_LEFT);
        if (!"mod".equals(category.key())) {
            depsLatest.setVisible(false);
            depsLatest.setManaged(false);
        }
        install.setMinWidth(190);
        install.setPrefWidth(220);

        VBox box = new VBox(10,
                UiKit.title(I18n.t(category.displayKey())),
                searchBar, filterBox, body, pager, bottomBar, status);
        String pending = pendingSearch;
        pendingSearch = null;
        if (pending != null && !pending.isBlank()) {
            query.setText(pending);
        }
        Platform.runLater(() -> {
            // 页面可能已经被下一次 showCategory 换掉（启动页跳搜索会连续重建两片）：
            // 旧面板已从 content 移除就不再发请求，避免同一栏位短时间搜两遍
            if (box.getParent() != null) {
                runSearch.accept(0);
            }
        });
        return box;
    }

    // ---------- 搜索（Modrinth / CurseForge 分流） ----------

    /** Modrinth 搜索：中文翻英文没命中时再用原文搜一次。 */
    private void searchModrinth(Category category, SearchQuery query,
                                Consumer<ModrinthClient.SearchResult> done) {
        List<String> loaders = query.loader() == null ? List.of() : List.of(query.loader());
        List<String> versions = query.mcVersion() == null ? List.of() : List.of(query.mcVersion());
        CompletableFuture.supplyAsync(() -> context.modrinth().search(query.searchText(),
                        category.projectType(), loaders, versions, query.environments(),
                        query.sort(), query.offset()))
                .thenAccept(result -> Platform.runLater(() -> {
                    if (query.translated() && result.isSuccess() && result.totalHits() == 0
                            && !query.text().equals(query.searchText())) {
                        // 翻译没命中：用原文再搜一次
                        CompletableFuture.supplyAsync(() -> context.modrinth().search(query.text(),
                                        category.projectType(), loaders, versions,
                                        query.environments(), query.sort(), query.offset()))
                                .thenAccept(fallback -> Platform.runLater(() -> done.accept(fallback)));
                        return;
                    }
                    done.accept(result);
                }));
    }

    /** CurseForge 搜索：端点顺序按设置页「下载源」，返回结果由调用方在 FX 线程处理。 */
    private void searchCurseForge(Category category, SearchQuery query,
                                  Consumer<CurseForgeClient.SearchResult> done) {
        boolean preferMirror = mirrorFirst();
        CompletableFuture.supplyAsync(() -> CurseForgeClient.search(query.searchText(),
                        category.projectType(), query.mcVersion(), query.loader(), query.offset(),
                        preferMirror))
                .thenAccept(result -> Platform.runLater(() -> {
                    if (query.translated() && result.isSuccess() && result.totalHits() == 0
                            && !query.text().equals(query.searchText())) {
                        CompletableFuture.supplyAsync(() -> CurseForgeClient.search(query.text(),
                                        category.projectType(), query.mcVersion(), query.loader(),
                                        query.offset(), preferMirror))
                                .thenAccept(fallback -> Platform.runLater(() -> done.accept(fallback)));
                        return;
                    }
                    done.accept(result);
                }));
    }

    /**
     * 设置页「下载源」→ CurseForge 端点顺序（与 Modrinth 镜像一致）。
     *
     * <p>优先镜像 / 仅镜像 → 镜像先跑；优先官方 / 自定义 → 官方先跑。
     * 两边都失败时客户端会自己回退另一个；没有 key 时官方会被直接跳过。
     */
    private boolean mirrorFirst() {
        LauncherSettings settings = context.settings();
        LauncherSettings.Source source = settings == null
                ? LauncherSettings.Source.AUTO : settings.source();
        return source != LauncherSettings.Source.OFFICIAL
                && source != LauncherSettings.Source.CUSTOM;
    }

    /** CurseForgeClient 的错误讯息是中文；这里按内容换成当前语言的文案。 */
    private static String curseForgeError(String message) {
        if (message == null || message.isBlank()) {
            return I18n.t("download.curseforge.error");
        }
        if (message.contains("API 为空")) {
            return I18n.t("download.curseforge.no_key");
        }
        if (message.contains("频繁")) {
            return I18n.t("download.curseforge.rate_limited");
        }
        java.util.regex.Matcher http = java.util.regex.Pattern
                .compile("HTTP\\s*(\\d{3})").matcher(message);
        if (http.find()) {
            return I18n.t("download.curseforge.http_error", http.group(1));
        }
        return I18n.t("download.curseforge.error_detail", message);
    }

    /** 详情卡片第二行：作者（CurseForge 没有作者，改显示来源）+ 下载数。 */
    private static String metaLine(SearchHit hit) {
        ModrinthClient.Project project = hit.project();
        String author = project.author() == null ? "" : project.author().trim();
        String prefix;
        if (!author.isEmpty()) {
            prefix = author;
        } else if (hit.source() == Source.CURSEFORGE) {
            prefix = I18n.t("download.source.curseforge");
        } else {
            prefix = I18n.t("download.source.modrinth");
        }
        return prefix + " · " + project.downloads() + " downloads";
    }

    /**
     * CurseForge 项目没有版本清单（只做了 {@code bestFile}）：
     * 取「当前 MC 版本 + 加载器」下最新的可用文件，包成一个 VersionFile 填进同一棵版本树，
     * 后面的下载 / 依赖 / 安装流程就完全复用。
     */
    private void showCurseForgeFile(SearchHit hit, String loaderValue, String mcVersion,
                                    ComboBox<GameInstance> instanceCombo,
                                    TreeView<Object> versions, Button install,
                                    Label dependencyLabel, FlowPane detailTags,
                                    java.util.function.BiConsumer<List<ModrinthClient.VersionFile>,
                                            GameInstance> populate,
                                    AtomicReference<SearchHit> selectedProject,
                                    boolean importable) {
        CompletableFuture.supplyAsync(() ->
                        CurseForgeClient.bestFile(hit.curseForgeId(), mcVersion, loaderValue,
                                mirrorFirst()))
                .thenAccept(entry -> Platform.runLater(() -> {
                    if (selectedProject.get() != hit) {
                        // 玩家已经点了别的项目，丢弃这次结果
                        return;
                    }
                    if (entry == null) {
                        versions.setDisable(true);
                        install.setDisable(true);
                        dependencyLabel.setText(I18n.t("download.curseforge.no_file"));
                        return;
                    }
                    if (entry.downloadUrl() == null || entry.downloadUrl().isBlank()) {
                        // 作者关闭了第三方下载：CurseForge 只给网页下载按钮
                        versions.setDisable(true);
                        install.setDisable(true);
                        dependencyLabel.setText(I18n.t("download.curseforge.no_download"));
                        return;
                    }
                    ModrinthClient.VersionFile file = curseForgeVersionFile(entry, loaderValue, mcVersion);
                    versions.setDisable(false);
                    dependencyLabel.setText(I18n.t("download.detail.no_deps"));
                    populate.accept(List.of(file), instanceCombo.getValue());
                    // CurseForge 项目级没有「客户端 / 服务端」字段；文件级 gameVersions 里有，
                    // 取到文件后在详情卡补一个环境标签（列表卡片不额外发请求去猜）
                    String environment = environmentTag(entry.gameVersions());
                    if (importable && !environment.isEmpty()) {
                        detailTags.getChildren().add(tag(environment));
                    }
                    if (!importable) {
                        // CurseForge 整合包是 manifest.json + overrides 的 zip，
                        // 本启动器的整合包安装器只吃 Modrinth .mrpack → 不假装能装
                        install.setDisable(true);
                        dependencyLabel.setText(I18n.t("download.curseforge.modpack_unsupported"));
                    }
                }))
                .exceptionally(error -> {
                    Platform.runLater(() -> {
                        if (selectedProject.get() == hit) {
                            dependencyLabel.setText(
                                    I18n.t("download.version_fetch_failed", rootMessage(error)));
                        }
                    });
                    return null;
                });
    }

    /** 把 CurseForge 的档案包装成 Modrinth 的 VersionFile（无哈希、无依赖）。 */
    private static ModrinthClient.VersionFile curseForgeVersionFile(CurseForgeClient.FileEntry entry,
                                                                    String loader, String mcVersion) {
        return new ModrinthClient.VersionFile(
                entry.fileId(), entry.fileName(), entry.fileName(), "",
                entry.downloadUrl(), entry.fileName(), "", entry.size(),
                mcVersionList(entry.gameVersions(), mcVersion),
                loader == null || loader.isBlank() ? List.of() : List.of(loader),
                List.of());
    }

    /** CurseForge 的 gameVersions 去掉 Client / Server / 加载器名，只留 MC 版本。 */
    private static List<String> mcVersionList(List<String> gameVersions, String fallback) {
        List<String> result = new ArrayList<>();
        for (String value : gameVersions) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.equals("client") || lower.equals("server")
                    || lower.equals("fabric") || lower.equals("forge")
                    || lower.equals("neoforge") || lower.equals("quilt")
                    || lower.equals("liteloader")) {
                continue;
            }
            result.add(value);
        }
        if (result.isEmpty() && fallback != null && !fallback.isBlank()) {
            result.add(fallback);
        }
        return result;
    }

    /** CurseForge 文件级的环境标签（Client / Server 在文件的 gameVersions 里）。 */
    private static String environmentTag(List<String> gameVersions) {
        boolean client = false;
        boolean server = false;
        for (String value : gameVersions) {
            if (value == null) {
                continue;
            }
            if ("client".equalsIgnoreCase(value)) {
                client = true;
            } else if ("server".equalsIgnoreCase(value)) {
                server = true;
            }
        }
        if (!client && !server) {
            return "";
        }
        if (client && server) {
            return I18n.t("download.env.tag.both");
        }
        return client ? I18n.t("download.env.tag.client") : I18n.t("download.env.tag.server");
    }

    /** 从启动页“下载皮肤补丁”跳到 Mod 搜索并自动搜 CustomSkinLoader。 */
    public void searchMod(String query) {
        searchMod(query, null);
    }

    /**
     * 跳到 Mod 搜索并指定目标实例（前置依赖图点气泡时用：缺前置的那个实例就是要装的目标）。
     *
     * @param instanceId 为 null 时沿用「上次选中的实例」
     */
    public void searchMod(String query, String instanceId) {
        pendingSearch = query;
        pendingInstanceId = instanceId;
        Category mod = CATEGORIES.stream()
                .filter(category -> "mod".equals(category.key()))
                .findFirst()
                .orElse(CATEGORIES.get(2));
        currentCategory = mod;
        showCategory(mod);
    }

    private record DependencyPlan(List<ModrinthClient.VersionFile> files, List<String> names) {
    }

    private void installWithDependencies(SearchHit hit,
                                         ModrinthClient.VersionFile file,
                                         GameInstance instance, String categoryKey,
                                         String loader, String mcVersion) {
        List<ModrinthClient.Dependency> required = file.dependencies().stream()
                .filter(dependency -> "required".equals(dependency.type()))
                .toList();
        if (required.isEmpty()) {
            downloadFiles(List.of(file), instance, categoryKey, file.displayName());
            return;
        }
        String dependencyLoader = loader;
        String dependencyMc = mcVersion;
        if (dependencyLoader == null && !file.loaders().isEmpty()) {
            dependencyLoader = file.loaders().get(0);
        }
        if (dependencyMc == null && !file.gameVersions().isEmpty()) {
            dependencyMc = file.gameVersions().get(0);
        }
        String finalLoader = dependencyLoader;
        String finalMc = dependencyMc;
        CompletableFuture.supplyAsync(() -> new DependencyPlan(
                        resolveDependencies(file, finalLoader, finalMc, instance),
                        dependencyNames(required)))
                .thenAccept(plan -> Platform.runLater(() -> {
                    if (plan.files().isEmpty()) {
                        MCLDialog.info(getScene().getWindow(), I18n.t("download.detail.install"),
                                I18n.t("download.deps.all_installed", String.join("、", plan.names())));
                        downloadFiles(List.of(file), instance, categoryKey, file.displayName());
                        return;
                    }
                    // 前置一键下载最新（默认）：解析结果本身就是「当前 loader + MC 版本的最新相容版」，
                    // 直接全部装上，不再逐个弹版本选择。关掉这个开关才走旧流程。
                    if (context.uiState().dependenciesLatest()) {
                        List<ModrinthClient.VersionFile> picked = new ArrayList<>(plan.files());
                        picked.add(file);
                        downloadFiles(picked, instance, categoryKey, hit.project().title());
                        return;
                    }
                    int choice = askDependencyChoice(plan.files().size(), plan.names());
                    if (choice == 1) {
                        // 连同前置：逐个让玩家确认版本（默认最新相容版，通常只有一个必需前置）
                        List<ModrinthClient.VersionFile> picked = new ArrayList<>();
                        picked.add(file);
                        chooseDependencyVersions(plan.files(), 0, picked, instance, categoryKey,
                                hit.project().title(), finalLoader, finalMc);
                    } else if (choice == 2) {
                        downloadFiles(List.of(file), instance, categoryKey, file.displayName());
                    }
                }));
    }

    /**
     * 逐个询问前置的安装版本（候选只有 1 个时直接采用，不打扰用户）。
     * 选完后统一下载。
     */
    private void chooseDependencyVersions(List<ModrinthClient.VersionFile> deps, int index,
                                          List<ModrinthClient.VersionFile> picked,
                                          GameInstance instance, String categoryKey, String title,
                                          String loader, String mcVersion) {
        if (index >= deps.size()) {
            downloadFiles(picked, instance, categoryKey, title);
            return;
        }
        ModrinthClient.VersionFile dep = deps.get(index);
        List<ModrinthClient.VersionFile> candidates = List.of(dep);
        try {
            // VersionFile 不带 projectId → 用文件哈希反查来源项目
            ModrinthClient.FoundFile found = context.modrinth().byFileHash(dep.sha1());
            String projectId = found == null ? "" : found.projectId();
            if (!projectId.isBlank()) {
                List<ModrinthClient.VersionFile> all = context.modrinth().versions(projectId,
                        loader == null || loader.isBlank() ? List.of() : List.of(loader),
                        mcVersion == null || mcVersion.isBlank() ? List.of() : List.of(mcVersion));
                if (all.size() > 1) {
                    candidates = all.size() > 10 ? all.subList(0, 10) : all;
                }
            }
        } catch (Exception ignored) {
            // 拿不到候选就用原解析结果
        }
        if (candidates.size() <= 1) {
            picked.add(dep);
            chooseDependencyVersions(deps, index + 1, picked, instance, categoryKey, title,
                    loader, mcVersion);
            return;
        }
        MCLDialog.choose(getScene().getWindow(), I18n.t("download.deps.choose_version"),
                I18n.t("download.deps.choose_version_hint", dep.fileName()),
                candidates,
                version -> version.versionNumber() + "  (" + version.fileName() + ")",
                version -> {
                    picked.add(version);
                    chooseDependencyVersions(deps, index + 1, picked, instance, categoryKey, title,
                            loader, mcVersion);
                });
    }

    /** 0=取消，1=连同前置，2=只装本体。 */
    private int askDependencyChoice(int count, List<String> names) {
        javafx.stage.Stage dialog = new javafx.stage.Stage();
        dialog.initOwner(getScene().getWindow());
        dialog.initModality(javafx.stage.Modality.NONE);
        dialog.initStyle(javafx.stage.StageStyle.TRANSPARENT);
        dialog.setTitle(I18n.t("download.deps.title"));

        Label title = new Label(I18n.t("download.deps.header", count));
        title.getStyleClass().add("section-title");
        Label body = new Label(I18n.t("download.deps.body", String.join("\n", names)));
        body.setWrapText(true);
        body.setMaxWidth(470);
        Button all = new Button(I18n.t("download.deps.all"));
        Button only = new Button(I18n.t("download.deps.only"));
        Button cancel = new Button(I18n.t("common.cancel"));
        all.getStyleClass().add("primary-button");
        int[] choice = {0};
        all.setOnAction(e -> {
            choice[0] = 1;
            dialog.close();
        });
        only.setOnAction(e -> {
            choice[0] = 2;
            dialog.close();
        });
        cancel.setOnAction(e -> dialog.close());
        HBox actions = new HBox(8, cancel, only, all);
        actions.setAlignment(Pos.CENTER_RIGHT);
        VBox root = new VBox(12, title, body, actions);
        root.setPadding(new Insets(18));
        root.setStyle("-fx-background-color: -brisk-bg; -fx-background-radius: 12;"
                + " -fx-border-color: -brisk-border; -fx-border-radius: 12;");
        javafx.scene.Scene scene = new javafx.scene.Scene(root, 520, 280);
        UiKit.applyTheme(scene);
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        dialog.setScene(scene);
        UiKit.popIn(dialog, root);
        dialog.showAndWait();
        return choice[0];
    }

    private List<ModrinthClient.VersionFile> resolveDependencies(ModrinthClient.VersionFile root,
                                                                 String loader, String mcVersion,
                                                                 GameInstance instance) {
        List<ModrinthClient.VersionFile> files = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<ModrinthClient.Dependency> queue = new ArrayDeque<>(root.dependencies());
        while (!queue.isEmpty() && files.size() < 24) {
            ModrinthClient.Dependency dependency = queue.removeFirst();
            if (!"required".equals(dependency.type())) {
                continue;
            }
            String key = dependency.projectId().isBlank()
                    ? dependency.versionId() : dependency.projectId();
            if (key.isBlank() || !visited.add(key)) {
                continue;
            }
            try {
                ModrinthClient.VersionFile resolved = dependency.versionId().isBlank()
                        ? null : context.modrinth().versionById(dependency.versionId());
                if (resolved == null) {
                    resolved = context.modrinth().bestFile(dependency.projectId(), loader, mcVersion);
                }
                if (alreadyInstalled(instance, "mods", resolved.fileName())) {
                    SafeLog.info("前置已安装，跳过：" + resolved.fileName());
                    continue;
                }
                files.add(resolved);
                queue.addAll(resolved.dependencies());
            } catch (Exception error) {
                SafeLog.warn("无法解析前置 " + key + "：" + error.getMessage());
            }
        }
        return files;
    }

    /** 按去掉版本号后的文件名判断同类 Mod 是否已安装，避免重复堆前置。 */
    private static boolean alreadyInstalled(GameInstance instance, String folder, String fileName) {
        if (instance == null || fileName == null || fileName.isBlank()) {
            return false;
        }
        Path dir = instance.gameDir().resolve(folder);
        if (!Files.isDirectory(dir)) {
            return false;
        }
        String wanted = normalizedModName(fileName);
        try (var stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile).anyMatch(path -> {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                return (name.endsWith(".jar") || name.endsWith(".disabled"))
                        && normalizedModName(name).equals(wanted);
            });
        } catch (Exception error) {
            return false;
        }
    }

    private static String normalizedModName(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT)
                .replaceFirst("\\.(jar|disabled)$", "")
                .replaceFirst("[-_]?\\d.*$", "");
        return name.replaceAll("[-_]+$", "");
    }

    private List<String> dependencyNames(List<ModrinthClient.Dependency> dependencies) {
        List<String> ids = dependencies.stream()
                .map(ModrinthClient.Dependency::projectId)
                .filter(id -> !id.isBlank())
                .distinct()
                .toList();
        Map<String, String> names = new java.util.HashMap<>();
        try {
            for (ModrinthClient.Project project : context.modrinth().projects(ids)) {
                names.put(project.id(), project.title());
            }
        } catch (Exception ignored) {
            // 名字取不到时退回 id / 文件名
        }
        List<String> result = new ArrayList<>();
        for (ModrinthClient.Dependency dependency : dependencies) {
            String name = names.get(dependency.projectId());
            if (name == null || name.isBlank()) {
                name = dependency.fileName().isBlank()
                        ? dependency.projectId() : dependency.fileName();
            }
            result.add(name + (dependency.type().equals("optional") ? "（可选）" : ""));
        }
        return result;
    }


    /**
     * 候选下载地址。
     *
     * <p>CurseForge 的 forgecdn 直链会按设置页下载源给出「官方 CDN + MCIM 镜像」两条候选；
     * 其它地址仍走原有 MirrorResolver（BMCLAPI 域名替换），映射不出就回退原始 URL。
     */
    private java.util.List<java.net.URI> sources(MclContext context, String url) {
        java.util.List<java.net.URI> curseForge =
                CurseForgeClient.downloadCandidates(url, mirrorFirst());
        if (!curseForge.isEmpty()) {
            return curseForge;
        }
        try {
            java.util.List<java.net.URI> resolved = context.mirrors().resolve(url);
            if (resolved != null && !resolved.isEmpty()) {
                return resolved;
            }
        } catch (Exception ignored) {
            // 映射失败就走原始地址
        }
        return java.util.List.of(java.net.URI.create(url));
    }
    private void downloadFiles(List<ModrinthClient.VersionFile> files, GameInstance instance,
                               String categoryKey, String title) {
        String folder = switch (categoryKey) {
            case "resourcepack" -> "resourcepacks";
            case "shader" -> "shaderpacks";
            default -> "mods";
        };
        Path targetDir = instance.gameDir().resolve(folder);
        if (!writable(getScene().getWindow(), targetDir)) {
            return;
        }
        TaskDialog.run(getScene().getWindow(), I18n.t("download.download_to_instance"), progress -> {
            List<DownloadTask> tasks = new ArrayList<>();
            for (ModrinthClient.VersionFile file : files) {
                String name = Path.of(file.fileName()).getFileName().toString();
                Path target = instance.gameDir().resolve(folder).resolve(name);
                tasks.add(new DownloadTask(sources(context, file.downloadUrl()),
                        target, file.sha1(), file.size()));
            }
            context.downloader().downloadAll(tasks, snapshot ->
                    progress.accept(new InstallProgress(InstallProgress.Stage.ASSETS,
                            0.05 + snapshot.ratio() * 0.9,
                            snapshot.completed() + "/" + snapshot.total())));
            return tasks;
        }, tasks -> {
            // 下载完成后明确告诉玩家文件落在哪
            Path first = tasks.get(0).destination();
            String message = tasks.size() == 1
                    ? I18n.t("download.saved_path", first.toString())
                    : I18n.t("download.saved_path_multi", tasks.size(), first.getParent().toString());
            MCLDialog.info(getScene().getWindow(), I18n.t("download.download_to_instance"), message);
        });
    }

    /** 在未强制筛选时，优先选中与目标实例兼容的版本，但仍保留其它版本可选。 */
    private static int compatibleFileIndex(List<ModrinthClient.VersionFile> files, GameInstance instance) {
        if (instance == null || files.isEmpty()) {
            return 0;
        }
        String mc = instance.mcVersion();
        String loader = instance.loader() == null ? ""
                : instance.loader().type().name().toLowerCase(Locale.ROOT);
        for (int i = 0; i < files.size(); i++) {
            ModrinthClient.VersionFile file = files.get(i);
            if (file.gameVersions().contains(mc)
                    && (loader.isEmpty() || file.loaders().contains(loader))) {
                return i;
            }
        }
        for (int i = 0; i < files.size(); i++) {
            if (files.get(i).gameVersions().contains(mc)) {
                return i;
            }
        }
        return 0;
    }

    private static String representativeVersion(ModrinthClient.VersionFile file, GameInstance instance) {
        if (instance != null && file.gameVersions().contains(instance.mcVersion())) {
            return instance.mcVersion();
        }
        return file.gameVersions().isEmpty() ? "unknown" : file.gameVersions().get(0);
    }

    private static String majorGroup(String version) {
        if (version.startsWith("1.")) {
            String[] parts = version.split("\\.");
            if (parts.length >= 2) {
                return parts[0] + "." + parts[1] + ".x";
            }
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^(\\d+)").matcher(version);
        if (matcher.find()) {
            return matcher.group(1) + ".x";
        }
        return version + ".x";
    }

    /** 大版本/小版本按数字倒序，避免 1.9 排在 1.21 前面。 */
    private static int compareGroup(String left, String right) {
        String[] a = left.split("[^0-9]+");
        String[] b = right.split("[^0-9]+");
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int av = i < a.length && !a[i].isEmpty() ? parseIntSafe(a[i]) : 0;
            int bv = i < b.length && !b[i].isEmpty() ? parseIntSafe(b[i]) : 0;
            if (av != bv) {
                return Integer.compare(bv, av);
            }
        }
        return right.compareTo(left);
    }

    private static int parseIntSafe(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String loaderValue(ComboBox<String> combo) {
        String value = combo == null ? null : combo.getValue();
        return value == null || value.equals(I18n.t("download.all_loaders")) ? null : value;
    }

    private static String mcValue(ComboBox<String> combo) {
        String value = combo == null ? null : combo.getValue();
        return value == null || value.equals(I18n.t("download.all_versions")) ? null : value;
    }

    private void fillSearchGameVersions(ComboBox<String> combo) {
        combo.getItems().add(I18n.t("download.all_versions"));
        combo.getSelectionModel().selectFirst();
        if (manifestLoaded) {
            addReleaseVersions(combo);
            return;
        }
        // 清单还没回来：登记一下，等 loadManifest 的回呼一起填；
        // 不要每个面板各自拉一次 version_manifest
        pendingVersionCombos.add(combo);
        loadManifest();
    }

    /** 用已缓存的清单填「游戏版本」下拉（只留 release，按清单顺序）。 */
    private void addReleaseVersions(ComboBox<String> combo) {
        List<String> ids = manifest.stream()
                .filter(meta -> "release".equals(meta.type()))
                .map(MojangApi.VersionMeta::id)
                .distinct()
                .toList();
        String selected = combo.getValue();
        combo.getItems().setAll(I18n.t("download.all_versions"));
        combo.getItems().addAll(ids);
        combo.setValue(selected == null ? I18n.t("download.all_versions") : selected);
    }

    private static ListCell<ModrinthClient.VersionFile> versionCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(ModrinthClient.VersionFile file, boolean empty) {
                super.updateItem(file, empty);
                setText(empty || file == null ? null
                        : file.displayName() + "  [" + String.join("/", file.loaders()) + " · "
                        + versionRange(file.gameVersions()) + "]");
            }
        };
    }

    /**
     * 「在第三方网站打开」的按钮：直接开系统浏览器，失败时把网址显示在状态列。
     */
    private static Button externalLink(String text, String url, Label status) {
        Button button = new Button(text);
        button.getStyleClass().add("button");
        button.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        button.setOnAction(e -> {
            if (!DesktopActions.openUrl(url) && status != null) {
                status.setText(I18n.t("download.open_failed", url));
            }
        });
        return button;
    }

    /** Modrinth 專案頁：有 slug 就直接開，沒有就用名稱搜尋。 */
    private static String modrinthUrl(ModrinthClient.Project project) {
        String type = switch (project.projectType() == null ? "" : project.projectType()) {
            case "modpack" -> "modpack";
            case "resourcepack" -> "resourcepack";
            case "shader" -> "shader";
            default -> "mod";
        };
        String slug = project.slug() == null ? "" : project.slug().trim();
        if (!slug.isBlank() && slug.matches("[A-Za-z0-9!@$()`.+,\"'\\-]{1,64}")) {
            return "https://modrinth.com/" + type + "/" + slug;
        }
        return "https://modrinth.com/" + type + "s?q=" + encodeQuery(project.title());
    }

    /** CurseForge 搜尋（帶對應的分類過濾；CurseForge 沒有公開的 slug 對照）。 */
    private static String curseforgeUrl(ModrinthClient.Project project) {
        String category = switch (project.projectType() == null ? "" : project.projectType()) {
            case "modpack" -> "modpacks";
            case "resourcepack" -> "texture-packs";
            case "shader" -> "shaders";
            default -> "mc-mods";
        };
        return "https://www.curseforge.com/minecraft/search?class=" + category
                + "&sortBy=relevancy&search=" + encodeQuery(project.title());
    }

    /** 来自 CurseForge 的结果直接开项目页（有 slug 时）；其余退回按名称搜索。 */
    private static String curseforgeUrl(SearchHit hit, Category category) {
        ModrinthClient.Project project = hit.project();
        String slug = project.slug() == null ? "" : project.slug().trim();
        if (hit.source() == Source.CURSEFORGE
                && slug.matches("[A-Za-z0-9!@$()`.+,\"'\\-]{1,64}")) {
            String path = switch (category.key()) {
                case "modpack" -> "modpacks";
                case "resourcepack" -> "texture-packs";
                case "shader" -> "shaders";
                default -> "mc-mods";
            };
            return "https://www.curseforge.com/minecraft/" + path + "/" + slug;
        }
        return curseforgeUrl(project);
    }

    /** MC 百科（中文站）搜尋。 */
    private static String mcmodUrl(ModrinthClient.Project project) {
        return "https://search.mcmod.cn/s?key=" + encodeQuery(project.title());
    }

    private static String encodeQuery(String text) {
        try {
            return java.net.URLEncoder.encode(text == null ? "" : text,
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    /** 游戏版本标签：只显示大概区间（1.20.1 – 1.21.4），不逐条罗列。 */
    private static String versionRange(List<String> versions) {
        if (versions == null || versions.isEmpty()) {
            return "-";
        }
        List<String> releases = new ArrayList<>();
        for (String version : versions) {
            if (version != null && !version.isBlank()) {
                releases.add(version);
            }
        }
        if (releases.isEmpty()) {
            return "-";
        }
        List<String> sorted = new ArrayList<>(releases);
        sorted.sort(DownloadPage::compareVersionAsc);
        String min = sorted.get(0);
        String max = sorted.get(sorted.size() - 1);
        return min.equals(max) ? min : min + " – " + max;
    }

    private static int compareVersionAsc(String left, String right) {
        String[] a = left.split("[^0-9a-zA-Z]+");
        String[] b = right.split("[^0-9a-zA-Z]+");
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            String as = i < a.length ? a[i] : "";
            String bs = i < b.length ? b[i] : "";
            boolean an = as.matches("\\d+");
            boolean bn = bs.matches("\\d+");
            if (an && bn) {
                int cmp = Integer.compare(parseIntSafe(as), parseIntSafe(bs));
                if (cmp != 0) {
                    return cmp;
                }
            } else {
                int cmp = as.compareTo(bs);
                if (cmp != 0) {
                    return cmp;
                }
            }
        }
        return 0;
    }

    /** 版本文件的加载器归类（一个文件可能写多个加载器，取第一个可识别的）。 */
    private static String loaderKeyOf(ModrinthClient.VersionFile file) {
        for (String loader : file.loaders()) {
            String key = loader.toLowerCase(java.util.Locale.ROOT);
            if (key.contains("fabric")) {
                return "fabric";
            }
            if (key.contains("quilt")) {
                return "quilt";
            }
            if (key.contains("neoforge")) {
                return "neoforge";
            }
            if (key.contains("forge")) {
                return "forge";
            }
            if (key.contains("liteloader")) {
                return "liteloader";
            }
            if (key.contains("rift")) {
                return "rift";
            }
            if (key.contains("datapack")) {
                return "datapack";
            }
            if (key.contains("bukkit") || key.contains("spigot") || key.contains("paper")) {
                return "server";
            }
        }
        return file.loaders().isEmpty() ? "other"
                : file.loaders().get(0).toLowerCase(java.util.Locale.ROOT);
    }

    private static String instanceLoaderKey(GameInstance instance) {
        if (instance == null || instance.loader() == null) {
            return "vanilla";
        }
        return switch (instance.loader().type()) {
            case FABRIC -> "fabric";
            case QUILT -> "quilt";
            case FORGE, OPTIFINE -> "forge";
            case NEOFORGE -> "neoforge";
            case VANILLA -> "vanilla";
        };
    }

    private static String loaderLabel(String key) {
        return switch (key) {
            case "fabric" -> "Fabric";
            case "quilt" -> "Quilt";
            case "forge" -> "Forge";
            case "neoforge" -> "NeoForge";
            case "liteloader" -> "LiteLoader";
            case "rift" -> "Rift";
            case "datapack" -> I18n.t("download.loader.datapack");
            case "server" -> I18n.t("download.loader.server");
            case "vanilla" -> I18n.t("download.loader.datapack");
            case "other" -> I18n.t("download.loader.other");
            default -> key;
        };
    }

    /** 加载器分组固定顺序（原版/数据包 → Fabric → Quilt → Forge → NeoForge → 其它）。 */
    private static int compareLoaderGroup(String left, String right) {
        List<String> order = List.of("vanilla", "datapack", "fabric", "quilt", "forge",
                "neoforge", "liteloader", "rift", "server", "other");
        int a = order.indexOf(left);
        int b = order.indexOf(right);
        if (a < 0) {
            a = order.size();
        }
        if (b < 0) {
            b = order.size();
        }
        if (a != b) {
            return Integer.compare(a, b);
        }
        return left.compareTo(right);
    }

    private boolean writable(javafx.stage.Window owner, Path dir) {
        try {
            Files.createDirectories(dir);
            // 用随机临时文件探测（与之前能用的构建一致）：
            // 固定文件名容易被安全软件规则拦截，随机名更接近真实下载行为。
            Path probe = Files.createTempFile(dir, ".mcl-write", ".tmp");
            Files.deleteIfExists(probe);
            return true;
        } catch (Exception error) {
            MCLDialog.error(owner, I18n.t("download.install"),
                    I18n.t("download.permission_denied", dir.toString())
                            + "\n\n" + error.getClass().getSimpleName()
                            + (error.getMessage() == null ? "" : "：" + error.getMessage()));
            return false;
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null
                ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static List<Label> tagsFor(SearchHit hit) {
        if (hit.source() == Source.CURSEFORGE) {
            // CurseForge 搜索结果不带头部环境 / 分类 / 游戏版本字段，只标来源
            List<Label> tags = new ArrayList<>();
            tags.add(tag(I18n.t("download.source.curseforge")));
            return tags;
        }
        return tagsFor(hit.project());
    }

    private static List<Label> tagsFor(ModrinthClient.Project project) {
        List<Label> tags = new ArrayList<>();
        String env = envTag(project);
        if (!env.isEmpty()) {
            tags.add(tag(env));
        }
        for (String category : project.categories()) {
            if (List.of("fabric", "forge", "neoforge", "").contains(category)) {
                tags.add(tag(category));
            }
        }
        if (!project.gameVersions().isEmpty()) {
            tags.add(tag(versionRange(project.gameVersions())));
        }
        if (tags.isEmpty()) {
            tags.add(tag(I18n.t("download.env.tag.universal")));
        }
        return tags;
    }

    private static Label tag(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("tag");
        return label;
    }

    private static String envTag(ModrinthClient.Project project) {
        String client = project.clientSide();
        String server = project.serverSide();
        if (client.isEmpty() && server.isEmpty()) {
            return "";
        }
        boolean clientOk = !"unsupported".equalsIgnoreCase(client);
        boolean serverOk = !"unsupported".equalsIgnoreCase(server);
        if (clientOk && serverOk) {
            return I18n.t("download.env.tag.both");
        }
        if (clientOk) {
            return I18n.t("download.env.tag.client");
        }
        if (serverOk) {
            return I18n.t("download.env.tag.server");
        }
        return "";
    }

    private final class ProjectCell extends ListCell<SearchHit> {
        @Override
        protected void updateItem(SearchHit hit, boolean empty) {
            super.updateItem(hit, empty);
            if (empty || hit == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            ModrinthClient.Project project = hit.project();
            StackPane coverBox = new StackPane();
            coverBox.setMinSize(48, 48);
            coverBox.setPrefSize(48, 48);
            coverBox.getStyleClass().add("tag");
        // 搜寻结果的图标不要米色底框 → 透明
        coverBox.setStyle("-fx-background-color: transparent; -fx-effect: null;");
            // 先显示占位图；图标通过 Http 层（带 UA / 镜像）取回后再替换，
            // 不再让 JavaFX 自己裸连 cdn.modrinth.com（国内基本连不上）。
            coverBox.getChildren().setAll(coverPlaceholder());
            if (project.iconUrl() != null && !project.iconUrl().isBlank()) {
                String url = project.iconUrl();
                String key = "list:" + url;
                ImageView cover = new ImageView();
                cover.setFitWidth(44);
                cover.setFitHeight(44);
                cover.setPreserveRatio(true);
                Image cached = iconCache.get(key);
                if (cached != null) {
                    cover.setImage(cached);
                    coverBox.getChildren().setAll(cover);
                } else {
                    RemoteImages.load(url, 44, 44, image -> {
                        iconCache.put(key, image);
                        // 列表复用 cell：只有仍显示同一个项目时才替换，避免串图
                        if (getItem() == hit) {
                            cover.setImage(image);
                            coverBox.getChildren().setAll(cover);
                        }
                    });
                }
            }

            // 标题前面带上对照表里的中文名（HMCL 的中英对照表），玩家一眼能认出是哪个 Mod
            String chineseName = com.brisklauncher.mcl.download.remote.LocalizedNames.chineseFor(
                    project.slug() == null ? project.title() : project.slug());
            if (chineseName == null || chineseName.isBlank()) {
                chineseName = com.brisklauncher.mcl.download.remote.LocalizedNames.chineseFor(project.title());
            }
            String titleText = chineseName == null || chineseName.isBlank()
                    || chineseName.equalsIgnoreCase(project.title())
                    ? project.title()
                    : chineseName + "  " + project.title();
            Label title = new Label(titleText);
            title.setStyle("-fx-font-weight: bold;");
            Label meta = new Label(metaLine(hit));
            meta.getStyleClass().add("cat-hint");
            Label description = new Label(project.description());
            description.getStyleClass().add("instance-meta");
            description.setMaxWidth(430);
            description.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
            FlowPane tags = new FlowPane(4, 4);
            tags.setPrefWrapLength(430);
            tags.getChildren().setAll(tagsFor(hit));
            VBox text = new VBox(2, title, meta, description, tags);
            HBox.setHgrow(text, Priority.ALWAYS);
            HBox row = new HBox(10, coverBox, text);
            row.setPadding(new Insets(4, 0, 4, 0));
            row.setAlignment(Pos.CENTER_LEFT);
            setText(null);
            setGraphic(row);
        }
    }

    private static Label coverPlaceholder() {
        Label label = new Label("?");
        label.setStyle("-fx-text-fill: -brisk-text-dim; -fx-font-size: 20px; -fx-font-weight: bold;");
        return label;
    }

    private VBox buildSearchPaneOld(Category category) {
        TextField query = new TextField();
        query.setPromptText(I18n.t("common.search"));
        HBox.setHgrow(query, Priority.ALWAYS);
        Button searchButton = new Button(I18n.t("common.search"));

        ComboBox<String> loader = new ComboBox<>();
        loader.getItems().addAll("fabric", "forge", "neoforge", "");
        loader.getSelectionModel().selectFirst();
        boolean loaderVisible = "mod".equals(category.key()) || "modpack".equals(category.key());
        loader.setVisible(loaderVisible);
        loader.setManaged(loaderVisible);

        ComboBox<String> gameVersion = new ComboBox<>();
        gameVersion.setPromptText(I18n.t("download.mc_version"));
        fillMcVersions(gameVersion);

        CheckBox client = new CheckBox(I18n.t("download.env.client"));
        CheckBox server = new CheckBox(I18n.t("download.env.server"));

        ListView<ModrinthClient.Project> results = new ListView<>();
        results.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(ModrinthClient.Project project, boolean empty) {
                super.updateItem(project, empty);
                if (empty || project == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label title = new Label(project.title() + "   " + envLabel(project));
                title.setStyle("-fx-font-weight: bold;");
                Label meta = new Label(project.author() + " · " + project.downloads() + " downloads");
                meta.getStyleClass().add("cat-hint");
                Label description = new Label(project.description());
                description.setWrapText(true);
                description.getStyleClass().add("instance-meta");
                VBox box = new VBox(2, title, meta, description);
                box.setPadding(new Insets(4, 0, 4, 0));
                setGraphic(box);
            }
        });
        VBox.setVgrow(results, Priority.ALWAYS);

        Label status = new Label();
        status.getStyleClass().add("cat-hint");
        status.setWrapText(true);

        ComboBox<GameDirectory> rootCombo = rootCombo();
        ComboBox<GameInstance> instanceCombo = new ComboBox<>();
        instanceCombo.setMaxWidth(Double.MAX_VALUE);
        instanceCombo.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(GameInstance entry, boolean empty) {
                super.updateItem(entry, empty);
                setText(empty || entry == null ? null : entry.id() + " · " + entry.subtitle());
            }
        });
        Runnable refreshInstances = () -> {
            GameDirectory root = rootCombo.getValue();
            if (root == null) {
                instanceCombo.getItems().clear();
                return;
            }
            List<GameInstance> entries = context.gameRepository().versions(root);
            instanceCombo.getItems().setAll(entries);
            if (!entries.isEmpty()) {
                selectPreferredInstance(instanceCombo, entries, root);
            }
        };
        rootCombo.valueProperty().addListener((obs, oldValue, value) -> refreshInstances.run());
        refreshInstances.run();

        Button download = new Button("modpack".equals(category.key())
                ? I18n.t("download.download_import")
                : I18n.t("download.download_to_instance"));
        download.getStyleClass().add("primary-button");
        download.setDisable(true);
        results.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, value) ->
                download.setDisable(value == null));

        Runnable runSearch = () -> {
            status.setText(I18n.t("download.loading"));
            results.getItems().clear();
            List<String> environments = new ArrayList<>();
            if (client.isSelected()) {
                environments.add("client");
            }
            if (server.isSelected()) {
                environments.add("server");
            }
            List<String> loaders = loader.isVisible() && loader.getValue() != null
                    ? List.of(loader.getValue()) : List.of();
            List<String> versions = gameVersion.getValue() == null || gameVersion.getValue().isBlank()
                    ? List.of() : List.of(gameVersion.getValue().trim());
            context.modrinth().searchAsync(query.getText(), category.projectType(),
                            loaders, versions, environments)
                    .thenAccept(result -> Platform.runLater(() -> {
                        if (!result.isSuccess()) {
                            results.getItems().clear();
                            status.setText(result.errorMessage());
                            return;
                        }
                        results.getItems().setAll(result.projects());
                        status.setText(I18n.t("download.total", result.totalHits()));
                    }));
        };
        searchButton.setOnAction(e -> runSearch.run());
        query.setOnAction(e -> runSearch.run());

        download.setOnAction(e -> {
            ModrinthClient.Project project = results.getSelectionModel().getSelectedItem();
            if (project == null) {
                return;
            }
            String mc = gameVersion.getValue() == null ? "" : gameVersion.getValue().trim();
            String loaderKey = loader.isVisible() ? loader.getValue() : null;
            GameDirectory root = rootCombo.getValue();
            GameInstance instance = instanceCombo.getValue();
            if ("modpack".equals(category.key())) {
                importModpackProject(project, root, loaderKey, mc);
            } else {
                downloadToInstance(project, instance, category.key(), loaderKey, mc);
            }
        });

        HBox searchBar = new HBox(8, query, searchButton);
        HBox filters = new HBox(10, client, server, loader, gameVersion);
        filters.setAlignment(Pos.CENTER_LEFT);
        HBox targetRow = "modpack".equals(category.key())
                ? new HBox(8, new Label(I18n.t("download.target_root")), rootCombo)
                : new HBox(8, new Label(I18n.t("download.target_instance")), instanceCombo);
        targetRow.setAlignment(Pos.CENTER_LEFT);


        VBox box = new VBox(10,
                UiKit.title(I18n.t(category.displayKey())),
                searchBar, filters, results, targetRow, download, status);
        return box;
    }

    private void downloadToInstance(ModrinthClient.Project project, GameInstance instance,
                                    String categoryKey, String loader, String mcVersion) {
        if (instance == null) {
            MCLDialog.info(getScene().getWindow(), I18n.t("download.download_to_instance"),
                    I18n.t("download.no_instance"));
            return;
        }
        TaskDialog.run(getScene().getWindow(),
                I18n.t("download.download_to_instance"), progress -> {
                    ModrinthClient.VersionFile file = context.modrinth()
                            .bestFile(project.id(), loader, mcVersion);
                    String folder = switch (categoryKey) {
                        case "resourcepack" -> "resourcepacks";
                        case "shader" -> "shaderpacks";
                        default -> "mods";
                    };
                    // 安全审计 M7：档名只能取 basename，保留字符直接拒绝
            String safeName = com.brisklauncher.mcl.security.PathSafety.safeFileName(file.fileName());
            Path target = com.brisklauncher.mcl.security.PathSafety.resolveUnder(
                    instance.gameDir().resolve(folder), safeName);
                    progress.accept(new InstallProgress(InstallProgress.Stage.ASSETS, 0.1,
                            file.fileName()));
                    DownloadTask task = new DownloadTask(
                            context.mirrors().resolve(file.downloadUrl()), target, file.sha1(), file.size());
                    context.downloader().downloadAll(List.of(task), snapshot ->
                            progress.accept(new InstallProgress(InstallProgress.Stage.ASSETS,
                                    0.1 + snapshot.ratio() * 0.85,
                                    snapshot.completed() + "/" + snapshot.total())));
                    return target;
                }, path -> MCLDialog.info(getScene().getWindow(), I18n.t("download.download_to_instance"),
                        I18n.t("download.saved", path.getFileName().toString())));
    }

    private void importModpackProject(SearchHit hit, GameDirectory root, String mcVersion) {
        if (root == null) {
            MCLDialog.info(getScene().getWindow(), I18n.t("download.target_root"),
                    I18n.t("version.empty_roots"));
            return;
        }
        if (!writable(getScene().getWindow(), root.path())) {
            return;
        }
        // 主题化输入框（系统外观的 TextInputDialog 和启动器风格不统一）
        MCLDialog.prompt(getScene().getWindow(), I18n.t("download.download_import"),
                I18n.t("download.instance_name"), hit.project().title(),
                name -> startModpackInstall(hit, root, name, mcVersion));
    }

    /** 旧的 Modrinth-only 入口（{@code buildSearchPaneOld} 还在呼叫）。 */
    private void importModpackProject(ModrinthClient.Project project, GameDirectory root,
                                      String loader, String mcVersion) {
        importModpackProject(SearchHit.modrinth(project), root, mcVersion);
    }

    private void startModpackInstall(SearchHit hit, GameDirectory root,
                                     String instanceName, String mcVersion) {
        TaskDialog.run(getScene().getWindow(), I18n.t("download.download_import"), progress -> {
            ModrinthClient.VersionFile file = hit.source() == Source.CURSEFORGE
                    ? curseForgeModpackFile(hit, mcVersion)
                    : context.modrinth().bestFile(hit.project().id(), null, mcVersion);
            Path archive = com.brisklauncher.mcl.AppPaths.cacheDir()
                    .resolve("modpacks").resolve(
                            com.brisklauncher.mcl.security.PathSafety.safeFileName(file.fileName()));
            progress.accept(new InstallProgress(InstallProgress.Stage.ASSETS, 0.05,
                    file.fileName()));
            DownloadTask task = new DownloadTask(
                    sources(context, file.downloadUrl()), archive, file.sha1(), file.size());
            context.downloader().downloadAll(List.of(task), snapshot ->
                    progress.accept(new InstallProgress(InstallProgress.Stage.ASSETS,
                            0.05 + snapshot.ratio() * 0.35,
                            snapshot.completed() + "/" + snapshot.total())));

            // 防御：CurseForge 的整合包是 manifest.json + overrides 的 zip，本启动器只吃 .mrpack
            if (com.brisklauncher.mcl.modpack.MrpackReader.looksLikeCurseForgeZip(archive)) {
                throw new IllegalStateException(I18n.t("download.curseforge.modpack_unsupported"));
            }
            ModpackIndex index = context.modpackInstaller().peek(archive);
            progress.accept(new InstallProgress(InstallProgress.Stage.RESOLVING, 0.4,
                    index.displayName()));
            return context.modpackInstaller().install(root, archive, instanceName, progress);
        }, entry -> {
            MCLDialog.info(getScene().getWindow(), I18n.t("download.download_import"),
                    I18n.t("download.installed", entry.id()));
            installFinished(entry);
        });
    }

    /** CurseForge 整合包：取当前 MC 版本下最新、可直连下载的档案。 */
    private ModrinthClient.VersionFile curseForgeModpackFile(SearchHit hit, String mcVersion) {
        CurseForgeClient.FileEntry entry = CurseForgeClient.bestFile(hit.curseForgeId(),
                mcVersion, null, mirrorFirst());
        if (entry == null || entry.downloadUrl() == null || entry.downloadUrl().isBlank()) {
            throw new IllegalStateException(I18n.t("download.curseforge.no_file"));
        }
        return curseForgeVersionFile(entry, null, mcVersion);
    }

    // ---------- 公共 ----------

    private ComboBox<GameDirectory> rootCombo() {
        ComboBox<GameDirectory> combo = new ComboBox<>();
        combo.setMaxWidth(Double.MAX_VALUE);
        combo.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(GameDirectory root, boolean empty) {
                super.updateItem(root, empty);
                setText(empty || root == null ? null : root.displayName());
            }
        });
        combo.getItems().setAll(context.gameRepository().roots());
        if (!combo.getItems().isEmpty()) {
            // 默认跟随“当前正在用的大版本文件夹”，而不是永远选第一个
            GameDirectory target = preferredRoot(combo.getItems());
            if (target == null) {
                combo.getSelectionModel().selectFirst();
            } else {
                combo.getSelectionModel().select(target);
            }
        }
        combo.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue != null) {
                context.uiState().rememberRoot(newValue.path());
            }
        });
        return combo;
    }

    /** 优先：上次使用的大版本文件夹 → 有实例的文件夹 → 第一个。 */
    private GameDirectory preferredRoot(List<GameDirectory> roots) {
        String remembered = context.uiState().lastRoot();
        if (!remembered.isBlank()) {
            for (GameDirectory root : roots) {
                if (root.path().toString().equals(remembered)) {
                    return root;
                }
            }
        }
        for (GameDirectory root : roots) {
            if (root.isValid()) {
                return root;
            }
        }
        return roots.isEmpty() ? null : roots.get(0);
    }

    /** 目标实例默认跟随「上次选中的实例」，避免每次都要手动重选。 */
    private void selectPreferredInstance(javafx.scene.control.ComboBox<GameInstance> combo,
                                         List<GameInstance> entries, GameDirectory root) {
        if (entries.isEmpty()) {
            return;
        }
        // 前置依赖图点气泡跳过来时，强制选中「缺前置的那个实例」（只用一次）
        String forced = pendingInstanceId;
        if (forced != null && !forced.isBlank()) {
            GameInstance match = entries.stream()
                    .filter(entry -> entry.id().equals(forced))
                    .findFirst().orElse(null);
            if (match != null) {
                combo.getSelectionModel().select(match);
                pendingInstanceId = null;
                return;
            }
        }
        String remembered = root == null ? "" : context.uiState().lastVersion(root.path());
        GameInstance target = null;
        if (!remembered.isBlank()) {
            target = entries.stream().filter(entry -> entry.id().equals(remembered))
                    .findFirst().orElse(null);
        }
        combo.getSelectionModel().select(target == null ? entries.get(0) : target);
    }

    private void installFinished(GameInstance entry) {
        if (entry != null) {
            context.gameRepository().settingsFor(entry);
        }
        // 让启动页 / 版本页立刻看到新版本（不用等背景监看的 1.5 秒）
        context.notifyInstancesChanged();
    }

    /** Modrinth 环境标签：结果用项目级，下载文件时以文件级为准（开发方案 4.5）。 */
    private static String envLabel(ModrinthClient.Project project) {
        String client = project.clientSide();
        String server = project.serverSide();
        if (client.isEmpty() && server.isEmpty()) {
            return "";
        }
        boolean clientOk = !"unsupported".equalsIgnoreCase(client);
        boolean serverOk = !"unsupported".equalsIgnoreCase(server);
        if (clientOk && serverOk) {
            return "[" + I18n.t("download.env.both") + "]";
        }
        if (clientOk) {
            return "[" + I18n.t("download.env.client_only") + "]";
        }
        if (serverOk) {
            return "[" + I18n.t("download.env.server_only") + "]";
        }
        return "";
    }

    /** 版本号比较：按「数字段」比，比不了就按字符串；用于下拉排序（新的在前）。 */
    private static int compareLoaderVersions(String a, String b) {
        String[] left = a.split("[.\\-_+]");
        String[] right = b.split("[.\\-_+]");
        int count = Math.max(left.length, right.length);
        for (int index = 0; index < count; index++) {
            String x = index < left.length ? left[index] : "0";
            String y = index < right.length ? right[index] : "0";
            boolean xNumber = x.matches("\\d+");
            boolean yNumber = y.matches("\\d+");
            if (xNumber && yNumber) {
                int compared = Long.compare(Long.parseLong(x), Long.parseLong(y));
                if (compared != 0) {
                    return compared;
                }
            } else {
                int compared = x.compareToIgnoreCase(y);
                if (compared != 0) {
                    return compared;
                }
            }
        }
        return 0;
    }
}
