package com.brisklauncher.mcl.ui;

import com.brisklauncher.mcl.MclContext;
import com.brisklauncher.mcl.crash.CrashAnalysis;
import com.brisklauncher.mcl.download.remote.ModrinthClient;
import com.brisklauncher.mcl.game.GameInstance;
import com.brisklauncher.mcl.game.InstanceSettings;
import com.brisklauncher.mcl.game.JavaVersionMapper;
import com.brisklauncher.mcl.game.install.InstallProgress;
import com.brisklauncher.mcl.game.mod.ModCatalog;
import com.brisklauncher.mcl.game.mod.ModManager;
import com.brisklauncher.mcl.i18n.I18n;
import com.brisklauncher.mcl.java.JdkInfo;
import com.brisklauncher.mcl.security.SafeLog;
import com.brisklauncher.mcl.util.RemoteImages;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckBoxTreeItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.TabPane.TabClosingPolicy;
import javafx.scene.control.cell.CheckBoxTreeCell;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Callback;

public final class VersionSettingsDialog {
   private static Path currentGameDir;

   private VersionSettingsDialog() {
   }

   public static void show(Window owner, final MclContext context, GameInstance entry, Runnable onChanged) {
      InstanceSettings current = context.gameRepository().settingsFor(entry);
      Stage dialog = new Stage();
      dialog.initOwner(owner);
      dialog.initModality(Modality.NONE);
      dialog.initStyle(StageStyle.TRANSPARENT);
      dialog.setTitle(I18n.t("version.settings.title"));
      String var10002 = I18n.t("version.settings.title");
      Label title = new Label("⚙ " + var10002 + " · " + entry.id());
      title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: -brisk-text;");
      Button close = UiKit.closeButtonStyled();
      close.getStyleClass().addAll(new String[]{"window-button", "window-close"});
      close.setOnAction((e) -> dialog.close());
      Region spacer = new Region();
      HBox.setHgrow(spacer, Priority.ALWAYS);
      HBox header = new HBox((double)6.0F, new Node[]{title, spacer, close});
      header.setAlignment(Pos.CENTER_LEFT);
      TabPane tabs = new TabPane();
      tabs.setStyle("-fx-focus-color: transparent; -fx-faint-focus-color: transparent;");
      tabs.setFocusTraversable(false);
      tabs.setTabClosingPolicy(TabClosingPolicy.UNAVAILABLE);
      Label gameDir = new Label(entry.gameDir().toString());
      gameDir.getStyleClass().add("cat-hint");
      gameDir.setWrapText(true);
      gameDir.setMaxWidth((double)380.0F);
      GridPane overview = new GridPane();
      overview.setHgap((double)12.0F);
      overview.setVgap((double)12.0F);
      overview.setPadding(new Insets((double)16.0F));
      // 左边是标签栏：不给最小宽度时，右边内容一长（例如图标说明、目录路径）标签就被压成「…」
      javafx.scene.layout.ColumnConstraints labelColumn = new javafx.scene.layout.ColumnConstraints();
      labelColumn.setMinWidth((double)96.0F);
      javafx.scene.layout.ColumnConstraints valueColumn = new javafx.scene.layout.ColumnConstraints();
      valueColumn.setHgrow(Priority.ALWAYS);
      overview.getColumnConstraints().addAll(labelColumn, valueColumn);
      int row = 0;
      overview.addRow(row++, new Node[]{new Label(I18n.t("version.settings.mc")), new Label(entry.mcVersion())});
      overview.addRow(row++, new Node[]{new Label(I18n.t("version.settings.loader")), new Label(entry.loaderDisplay())});
      overview.addRow(row++, new Node[]{new Label(I18n.t("version.settings.java")), new Label(JavaVersionMapper.display(entry.javaMajor()))});
      overview.addRow(row++, new Node[]{new Label(I18n.t("version.settings.dir")), gameDir});
      Button openGameDir = new Button(I18n.t("version.open_game_folder"));
      openGameDir.setOnAction((e) -> openFolder(dialog, entry.gameDir()));
      Button repair = new Button(I18n.t("launch.repair"));
      repair.setOnAction((e) -> TaskDialog.runVoid(dialog, I18n.t("launch.repair"), (progress) -> {
            context.gameRepository().repair(entry, progress);
            return null;
         }, () -> MCLDialog.info(dialog, I18n.t("launch.repair"), I18n.t("launch.repair_done"))));
      Button exportPack = new Button(I18n.t("version.export_modpack"));
      exportPack.setOnAction((e) -> exportModpack(dialog, context, entry));
      // 客户端 / 服务端分离：导出可直接开的服务端整合包
      Button serverPack = new Button(I18n.t("serverpack.button"));
      serverPack.setOnAction((e) -> ServerPackDialog.show(dialog, context, entry));
      // Mod 一键升级到另一个游戏版本
      Button upgrade = new Button(I18n.t("modupgrade.button"));
      upgrade.setOnAction((e) -> ModUpgradeDialog.show(dialog, context, entry));
      Button renameEntry = new Button(I18n.t("version.rename"));
      renameEntry.setOnAction((e) -> {
         // 正在安装/补全的版本不能改名：改名会把资料夹搬走，安装中的写入就全落到旧路径
         if (com.brisklauncher.mcl.game.install.InstallLocks.isActive(
                 com.brisklauncher.mcl.game.install.InstallLocks.key(
                         entry.root().path().toString(), entry.id()))) {
            MCLDialog.info(owner, I18n.t("version.rename"),
                    I18n.t("install.locked_rename", entry.id()));
            return;
         }
         MCLDialog.prompt(owner, I18n.t("version.rename"),
              I18n.t("version.rename_hint"), entry.id(),
              // 先验证再提交：非法字元 / 同名资料夹 / 与目前名称相同都在弹窗里直接说明，
              // 不要等 Files.move 失败才丢一个看不懂的例外。
              (name) -> validateVersionName(entry, name), (name) -> {
            try {
               context.gameRepository().rename(entry, name);
               if (onChanged != null) {
                  try {
                     onChanged.run();
                  } catch (RuntimeException refresh) {
                     // 改名已经成功，列表刷新失败不该被当成「改名失败」
                     SafeLog.warn("[rename] refresh failed: " + refresh.getClass().getSimpleName());
                  }
               }
               // 改名后这个对话框标题与路径都过期了（versionDir 已换），先关掉再提示
               dialog.close();
               MCLDialog.info(null, I18n.t("version.rename"),
                     I18n.t("version.rename_done", name));
            } catch (Exception error) {
               MCLDialog.error(owner, I18n.t("version.rename"),
                    error.getClass().getSimpleName() + " · " + error.getMessage());
            }

         });
      });
      // 「删除此实例」从「加载器」页搬过来：放在「升级 Mod 到其它版本」右边（功能不变）
      Button deleteInstance = new Button(I18n.t("version.settings.delete_instance"));
      deleteInstance.getStyleClass().addAll(new String[]{"button", "window-close"});
      // 两段式确认：不开模态确认框（模态 + 之后关闭 owner 会让 JavaFX 卡死）
      boolean[] armed = new boolean[1];
      deleteInstance.setOnAction((e) -> {
         if (!armed[0]) {
            armed[0] = true;
            deleteInstance.setText(I18n.t("version.settings.delete_confirm_button"));
            return;
         }
         deleteInstance.setDisable(true);
         deleteInstance(dialog, context, entry, onChanged);
      });
      FlowPane actions = new FlowPane((double)8.0F, (double)8.0F);
      actions.getChildren().addAll(new Node[]{UiKit.noShrink(openGameDir), UiKit.noShrink(renameEntry), UiKit.noShrink(repair), UiKit.noShrink(exportPack), UiKit.noShrink(serverPack), UiKit.noShrink(upgrade), UiKit.noShrink(deleteInstance)});
      // 开发期：直接打开「重命名」弹窗截图（-Dmcl.dev.renameprompt=1）
      if (System.getProperty("mcl.dev.renameprompt") != null) {
         Platform.runLater(() -> Platform.runLater(renameEntry::fire));
      }
      overview.add(actions, 1, row++);
      // 版本图标：显示目前图标（自订图片或首字母占位块），可选图/清除
      javafx.scene.layout.StackPane iconHolder = new javafx.scene.layout.StackPane();
      iconHolder.setMinSize((double)44.0F, (double)44.0F);
      iconHolder.setPrefSize((double)44.0F, (double)44.0F);
      iconHolder.setMaxSize((double)44.0F, (double)44.0F);
      iconHolder.getChildren().setAll(VersionIconView.of(entry, 44));
      Button pickIcon = new Button(I18n.t("version.icon.pick"));
      pickIcon.setOnAction((e) -> {
         javafx.stage.FileChooser chooser = VersionIconView.picker();
         File chosen = chooser.showOpenDialog(dialog);
         if (chosen == null) {
            return;
         }
         try {
            com.brisklauncher.mcl.game.GameIcons.setIcon(entry, chosen.toPath());
            context.notifyInstanceIconChanged();
            iconHolder.getChildren().setAll(VersionIconView.of(entry, 44));
         } catch (Exception error) {
            MCLDialog.error(dialog, I18n.t("version.icon"), I18n.t("version.icon.failed", String.valueOf(error.getMessage())));
         }
      });
      Button clearIcon = new Button(I18n.t("version.icon.clear"));
      clearIcon.setOnAction((e) -> {
         try {
            com.brisklauncher.mcl.game.GameIcons.clearIcon(entry);
            context.notifyInstanceIconChanged();
            iconHolder.getChildren().setAll(VersionIconView.of(entry, 44));
         } catch (Exception error) {
            MCLDialog.error(dialog, I18n.t("version.icon"), I18n.t("version.icon.failed", String.valueOf(error.getMessage())));
         }
      });
      Label iconHint = UiKit.catHint(I18n.t("version.icon.hint"));
      iconHint.setWrapText(true);
      iconHint.setMaxWidth((double)340.0F);
      HBox iconRow = new HBox((double)10.0F, new Node[]{iconHolder, new VBox((double)6.0F, new Node[]{new HBox((double)8.0F, new Node[]{pickIcon, clearIcon}), iconHint})});
      iconRow.setAlignment(Pos.CENTER_LEFT);
      overview.addRow(row++, new Node[]{new Label(I18n.t("version.icon")), iconRow});
      Tab overviewTab = new Tab(I18n.t("version.tab.overview"), overview);
      Spinner<Integer> memory = new Spinner(512, 65536, current.memoryMb(), 256);
      memory.setEditable(true);
      memory.setMaxWidth(Double.MAX_VALUE);
      TextField javaPath = new TextField(current.javaPath());
      javaPath.setPromptText(I18n.t("version.settings.java_auto"));
      HBox.setHgrow(javaPath, Priority.ALWAYS);
      ComboBox<JdkInfo> jdkCombo = new ComboBox();
      jdkCombo.setMaxWidth(Double.MAX_VALUE);
      jdkCombo.setCellFactory((view) -> new ListCell<JdkInfo>() {
            protected void updateItem(JdkInfo jdk, boolean empty) {
               super.updateItem(jdk, empty);
               this.setText(!empty && jdk != null ? jdk.display() + " · " + String.valueOf(jdk.home()) : null);
            }
         });
      Runnable refreshJdks = () -> {
         List<JdkInfo> jdks = context.javaManager().scanAll();
         jdkCombo.getItems().setAll(jdks);
      };
      refreshJdks.run();
      jdkCombo.valueProperty().addListener((obs, oldValue, value) -> {
         if (value != null) {
            javaPath.setText(value.javaExecutable().toString());
         }

      });
      Button browseJava = new Button("…");
      browseJava.setOnAction((e) -> {
         FileChooser chooser = new FileChooser();
         chooser.setTitle("java.exe");
         chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Java", new String[]{"java.exe", "java"}));
         File file = chooser.showOpenDialog(dialog);
         if (file != null) {
            javaPath.setText(file.getAbsolutePath());
         }

      });
      Button downloadJava = new Button(I18n.t("version.settings.download_java", JavaVersionMapper.display(entry.javaMajor())));
      downloadJava.setOnAction((e) -> TaskDialog.run(dialog, I18n.t("version.settings.download_java", JavaVersionMapper.display(entry.javaMajor())), (progress) -> context.javaManager().ensureFor(entry.javaMajor(), context.downloader(), context.mirrors(), progress), (jdk) -> {
            refreshJdks.run();
            javaPath.setText(jdk.javaExecutable().toString());
            MCLDialog.info(dialog, I18n.t("version.settings.java"), I18n.t("version.settings.java_installed", jdk.display()));
         }));
      TextArea jvmArgs = new TextArea(String.join("\n", current.jvmArgs()));
      jvmArgs.setPrefRowCount(5);
      jvmArgs.setPromptText(I18n.t("version.settings.jvm_hint"));
      GridPane javaGrid = new GridPane();
      javaGrid.setHgap((double)12.0F);
      javaGrid.setVgap((double)12.0F);
      javaGrid.setPadding(new Insets((double)16.0F));
      javaGrid.addRow(0, new Node[]{new Label(I18n.t("version.settings.memory")), memory});
      javaGrid.addRow(1, new Node[]{new Label(I18n.t("version.settings.java_path")), new HBox((double)6.0F, new Node[]{javaPath, browseJava})});
      javaGrid.addRow(2, new Node[]{new Label(I18n.t("version.settings.java_detect")), jdkCombo});
      javaGrid.addRow(3, new Node[]{new Label(""), downloadJava});
      javaGrid.addRow(4, new Node[]{new Label(I18n.t("version.settings.jvm")), jvmArgs});
      GridPane.setHgrow((Node)javaGrid.getChildren().get(3), Priority.ALWAYS);
      Tab javaTab = new Tab(I18n.t("version.tab.java"), javaGrid);
      ComboBox<ModManager.Kind> kindCombo = new ComboBox();
      kindCombo.getItems().setAll(ModManager.Kind.values());
      kindCombo.setValue(ModManager.Kind.MODS);
      Function<ModManager.Kind, String> kindLabel = (kind) -> {
         String var10000;
         if (kind == null) {
            var10000 = "";
         } else {
            var10000 = kind.name();
            var10000 = I18n.t("content." + var10000.toLowerCase(Locale.ROOT));
         }

         return var10000;
      };
      Callback<ListView<ModManager.Kind>, ListCell<ModManager.Kind>> kindCells = (view) -> new ListCell<ModManager.Kind>() {
            protected void updateItem(ModManager.Kind kind, boolean empty) {
               super.updateItem(kind, empty);
               this.setText(empty ? null : (String)kindLabel.apply(kind));
            }
         };
      kindCombo.setCellFactory(kindCells);
      kindCombo.setButtonCell(kindCells.call(null));
      // 开发期：直接选中某个内容分类（例如 CONFIG，用于截图/回归验证）
      String devKind = System.getProperty("mcl.dev.contentkind");
      if (devKind != null && !devKind.isBlank()) {
         for (ModManager.Kind kind : ModManager.Kind.values()) {
            if (kind.name().equalsIgnoreCase(devKind.trim())) {
               kindCombo.setValue(kind);
               break;
            }
         }
      }
      String filterAll = I18n.t("content.filter.all");
      String filterEnabled = I18n.t("content.filter.enabled");
      String filterDisabled = I18n.t("content.filter.disabled");
      ComboBox<String> filterCombo = new ComboBox();
      filterCombo.getItems().setAll(new String[]{filterAll, filterEnabled, filterDisabled, I18n.t("content.filter.updatable")});
      filterCombo.setValue(filterAll);
      final String loaderKey = entry.loader() != null && !entry.loader().isVanilla() ? entry.loader().type().name().toLowerCase(Locale.ROOT) : "";
      final String mcVersion = entry.mcVersion();
      ModCatalog catalog = new ModCatalog(context.modrinth());
      ModDetailsDialog.MclContextPeek peek = new ModDetailsDialog.MclContextPeek() {
         public ModrinthClient modrinth() {
            return context.modrinth();
         }

         public String loaderKey() {
            return loaderKey;
         }

         public String mcVersion() {
            return mcVersion;
         }
      };
      List<ModCatalog.Entry> currentFiles = new ArrayList();
      Set<String> checkedNames = new LinkedHashSet();
      // 用 ListView 虚拟化：126 个 mod 只会建立「画面看得到的」那十几行节点，
      // 之前用 VBox 是 126 行全部建立（实测多占 ~80MB，滚动也卡）。
      Function<ModCatalog.Entry, HBox>[] rowHolder = new Function[1];
      java.util.Map<String, java.util.function.Consumer<ModCatalog.Entry>> rowUpdaters =
              new java.util.HashMap<>();
      javafx.scene.control.ListView<ModCatalog.Entry> contentList =
              new javafx.scene.control.ListView<>();
      contentList.getStyleClass().add("edge-to-edge");
      contentList.setStyle("-fx-background-color: transparent;");
      contentList.setCellFactory(view -> new javafx.scene.control.ListCell<>() {
         @Override
         protected void updateItem(ModCatalog.Entry item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            if (empty || item == null || rowHolder[0] == null) {
               setGraphic(null);
               return;
            }
            Node row = (Node) rowHolder[0].apply(item);
            if (row instanceof javafx.scene.layout.Region region) {
               // 让列宽跟著列表走（不然内容比列表宽就会冒出水平滚动条、右边按钮被切掉）
               region.setMaxWidth(Double.MAX_VALUE);
               region.prefWidthProperty().bind(view.widthProperty().subtract(44));
            }
            setGraphic(row);
         }
      });
      VBox.setVgrow(contentList, Priority.ALWAYS);
      Label checkedCount = new Label();
      checkedCount.getStyleClass().add("cat-hint");
      Runnable updateCount = () -> checkedCount.setText(I18n.t("content.checked_count", checkedNames.size()));
      Predicate<ModCatalog.Entry> matchesFilter = (item) -> {
         String filter = (String)filterCombo.getValue();
         if (filter != null && !filter.equals(filterAll)) {
            if (filter.equals(filterEnabled)) {
               return item.enabled();
            } else if (filter.equals(filterDisabled)) {
               return !item.enabled();
            } else {
               return item.hasUpdate();
            }
         } else {
            return true;
         }
      };
      // 换类型（mod ↔ 资源包 ↔ 存档）时才清图标快取
      ModManager.Kind[] lastIconKind = new ModManager.Kind[1];
      Runnable[] refreshHolder = new Runnable[1];
      // 实例被删掉之后，残留的非同步回呼还会叫刷新；这里直接挡掉，避免拿著不存在的目录一直重建 UI
      Runnable[] guardHolder = new Runnable[1];
      // 解析完一个 mod 就重建整个列表 → 126 个 mod 会重建 126 次（O(n²)）。
      // 这里合并成「最多每 350ms 重建一次」，而且解析本身已经去重。
      javafx.animation.PauseTransition refreshDebounce =
              new javafx.animation.PauseTransition(javafx.util.Duration.millis(350));
      boolean[] refreshPending = new boolean[1];
      final Runnable[] scheduleRefresh = new Runnable[1];
      scheduleRefresh[0] = () -> {
         if (refreshPending[0]) {
            return;
         }
         refreshPending[0] = true;
         refreshDebounce.setOnFinished(event -> {
            refreshPending[0] = false;
            refreshHolder[0].run();
         });
         refreshDebounce.playFromStart();
      };
      refreshHolder[0] = () -> {
         if (!java.nio.file.Files.exists(entry.gameDir())) {
            return;
         }
         ModCatalog.Entry previouslySelected = contentList.getSelectionModel().getSelectedItem();
         currentFiles.clear();

         for(ModManager.ContentFile file : context.mods().list(entry, (ModManager.Kind)kindCombo.getValue())) {
            currentFiles.add(catalog.local(file.path(), file.enabled()));
         }

         checkedNames.retainAll(currentFiles.stream().map(ModCatalog.Entry::fileName).toList());
         java.util.List<ModCatalog.Entry> visible = new java.util.ArrayList<>();
         for (ModCatalog.Entry item : currentFiles) {
            if (matchesFilter.test(item)) {
               visible.add(item);
            }
         }
         contentList.getItems().setAll(visible);
         contentList.setPlaceholder(UiKit.catHint(I18n.t("content.empty_dir")));
         if (previouslySelected != null) {
            contentList.getSelectionModel().select(previouslySelected);
         }

         updateCount.run();
         // ListView 会自己维持滚动位置；换类型时回到顶部
         contentList.scrollTo(0);

         // 一次把这批丢给 8 条执行绪；完成的回呼只「排一次」合并刷新
         catalog.resolveAll(new java.util.ArrayList<>(currentFiles), loaderKey, mcVersion,
                 (resolved) -> Platform.runLater(() -> {
                    java.util.function.Consumer<ModCatalog.Entry> updater =
                            rowUpdaters.get(resolved.fileName());
                    if (updater != null) {
                       updater.accept(resolved);
                    }
                 }));

         // 换了类型（mod ↔ 资源包 ↔ 存档）才清图标快取；单纯刷新时不能清，
         // 否则第一次打开时图标还在背景解码，listener 会被一起清掉（图标就永远不出现）。
         ModManager.Kind iconKindNow = (ModManager.Kind)kindCombo.getValue();
         if (lastIconKind[0] != null && lastIconKind[0] != iconKindNow) {
            ModIcons.clear();
         }
         lastIconKind[0] = iconKindNow;
         // 注意：这里**不能** ModIcons.clear()——第一次打开时图标还在背景解码，
         // clear() 会把等待中的 listener 一起清掉，结果就是「一打开什么都没有」。
         // 一次把这批档案的图标丢给 4 条背景执行绪解（不用等列表一个个排队）
         ModManager.Kind iconKind = (ModManager.Kind)kindCombo.getValue();
         if (iconKind == null || iconKind == ModManager.Kind.MODS
                 || iconKind == ModManager.Kind.RESOURCE_PACKS
                 || iconKind == ModManager.Kind.SHADER_PACKS) {
            java.util.List<java.nio.file.Path> icons = new java.util.ArrayList<>();
            for (ModCatalog.Entry item : currentFiles) {
               icons.add(item.path());
            }
            ModIcons.preload(icons, 40);
         }

      };
      Runnable refreshContent = () -> refreshHolder[0].run();
      rowHolder[0] = (item) -> {
         StackPane cover = new StackPane();
         cover.setMinSize((double)40.0F, (double)40.0F);
         cover.setPrefSize((double)40.0F, (double)40.0F);
         cover.getStyleClass().add("tag");
         // 图标底框改成透明（不然会有个米色圆角方块垫在图标下面）
         cover.setStyle("-fx-background-color: transparent; -fx-effect: null;");
         Node placeholder = UiKit.letterAvatar(item.title() != null && !item.title().isBlank()
                     ? item.title() : item.fileName(), 36);
         cover.getChildren().setAll(new Node[]{placeholder});
         ModManager.Kind selectedKind = (ModManager.Kind)kindCombo.getValue();
         // 第一次建立列表时 kindCombo 可能还没有值（初始化顺序），null 一律当成 Mod，
         // 否则 modded=false 就完全不会去读图标（画面只剩字母占位）。
         final ModManager.Kind currentKind =
                 selectedKind == null ? ModManager.Kind.MODS : selectedKind;
         boolean modded = currentKind == ModManager.Kind.MODS || currentKind == ModManager.Kind.RESOURCE_PACKS || currentKind == ModManager.Kind.SHADER_PACKS;
         // 有图标（本地或远端）之后就不再覆盖：避免远端图回来时把本地图标盖掉
         final boolean[] iconShown = {false};
         if (modded) {
            // 图标直接从 jar / 资料夹里读（离线、多执行绪），不再打 Modrinth CDN
            Image cachedIcon = ModIcons.cached(item.path());
            if (cachedIcon != null) {
               cover.getChildren().setAll(new Node[]{iconView(cachedIcon)});
               iconShown[0] = true;
            } else {
               ModIcons.load(item.path(), 40, (imagex) -> {
                  if (imagex != null) {
                     cover.getChildren().setAll(new Node[]{iconView(imagex)});
                     iconShown[0] = true;
                  } else {
                     // 档案里没有图标（光影包很常见）→ 退到 Modrinth 的专案图示
                     applyRemoteIcon(cover, item.iconUrl(), iconShown);
                  }
               });
            }
         } else if (currentKind == ModManager.Kind.SAVES) {
            cover.getChildren().setAll(new Node[]{placeholder});
            Path saveIcon = item.path().resolve("icon.png");
            if (Files.isRegularFile(saveIcon, new LinkOption[0])) {
               try {
                  Image image = com.brisklauncher.mcl.security.GuardedImages
                    .fromFile(saveIcon, 32, 32, true, false);
                  if (!image.isError()) {
                     cover.getChildren().setAll(new Node[]{iconView(image)});
                  }
               } catch (Exception var21) {
               }
            }
         } else if (currentKind == ModManager.Kind.CONFIG) {
            cover.getChildren().setAll(new Node[]{placeholder});
         }

         Label name = new Label(item.title() != null && !item.title().isBlank() ? item.title() : item.fileName());
         name.setStyle("-fx-font-weight: bold;");
         name.setTextOverrun(OverrunStyle.ELLIPSIS);
         name.setMaxWidth((double)300.0F);
         String metaName = item.fileName();
         Label meta = new Label(metaName + " · " + sizeText(item.size()) + (item.currentVersion() != null && !item.currentVersion().isBlank() ? " · " + item.currentVersion() : ""));
         meta.getStyleClass().add("cat-hint");
         VBox text = new VBox((double)2.0F, new Node[]{name, meta});
         HBox.setHgrow(text, Priority.ALWAYS);
         HBox tags = new HBox((double)4.0F);
         tags.setMinWidth(Double.NEGATIVE_INFINITY);
         if (currentKind == ModManager.Kind.MODS) {
            Label enabledTag = new Label(item.enabled() ? I18n.t("content.tag.enabled") : I18n.t("content.tag.disabled"));
            enabledTag.getStyleClass().add("tag");
            enabledTag.setMinWidth(Double.NEGATIVE_INFINITY);
            enabledTag.setStyle(item.enabled() ? "-fx-text-fill: -brisk-ok;" : "-fx-text-fill: -brisk-accent-2;");
            tags.getChildren().add(enabledTag);
         }

         // 解析结果回来时「只更新这一行」——不要整表重建（126 个 mod 会卡死）
         java.util.function.Consumer<ModCatalog.Entry> applyEntry = (fresh) -> {
            name.setText(fresh.title() != null && !fresh.title().isBlank()
                    ? fresh.title() : fresh.fileName());
            meta.setText(fresh.fileName() + " · " + sizeText(fresh.size())
                    + (fresh.currentVersion() != null && !fresh.currentVersion().isBlank()
                    ? " · " + fresh.currentVersion() : ""));
            tags.getChildren().clear();
            if (currentKind == ModManager.Kind.MODS) {
               Label enabledTag = new Label(fresh.enabled()
                       ? I18n.t("content.tag.enabled") : I18n.t("content.tag.disabled"));
               enabledTag.getStyleClass().add("tag");
               enabledTag.setMinWidth(Double.NEGATIVE_INFINITY);
               enabledTag.setStyle(fresh.enabled()
                       ? "-fx-text-fill: -brisk-ok;" : "-fx-text-fill: -brisk-accent-2;");
               tags.getChildren().add(enabledTag);
            }
            if (modded && fresh.hasUpdate()) {
               Label updateTag = new Label(I18n.t("content.tag.update"));
               updateTag.getStyleClass().add("tag");
               updateTag.setMinWidth(Double.NEGATIVE_INFINITY);
               updateTag.setStyle("-fx-text-fill: -brisk-warning; -fx-font-weight: bold;");
               tags.getChildren().add(updateTag);
            }
            // 背景解析回来才知道专案 id / 图示网址：本地没有图标时在这里补上
            if (modded && fresh.iconUrl() != null && !fresh.iconUrl().isBlank()) {
               applyRemoteIcon(cover, fresh.iconUrl(), iconShown);
            }
         };
         rowUpdaters.put(item.fileName(), applyEntry);
         applyEntry.accept(item);

         HBox contentRow = new HBox((double)8.0F);
         contentRow.setAlignment(Pos.CENTER_LEFT);
         contentRow.setPadding(new Insets((double)2.0F, (double)4.0F, (double)2.0F, (double)4.0F));
         CheckBox check = new CheckBox();
         check.setSelected(checkedNames.contains(item.fileName()));
         check.selectedProperty().addListener((obs, was, now) -> {
            if (Boolean.TRUE.equals(now)) {
               checkedNames.add(item.fileName());
            } else {
               checkedNames.remove(item.fileName());
            }

            updateCount.run();
         });
         contentRow.getChildren().add(check);
         if (currentKind != ModManager.Kind.CONFIG) {
            contentRow.getChildren().add(cover);
         }

         contentRow.getChildren().addAll(new Node[]{text, tags});
         if (modded) {
            Button details = new Button(I18n.t("content.details"));
            details.getStyleClass().add("button");
            details.setMinWidth(Double.NEGATIVE_INFINITY);
            details.setDisable(item.projectId() == null || item.projectId().isBlank());
            details.setOnAction((e) -> ModDetailsDialog.show(dialog, peek, item.projectId(), context.mods().dir(entry, (ModManager.Kind)kindCombo.getValue()), (ModManager.Kind)kindCombo.getValue(), refreshContent));
            Button update = new Button(I18n.t("content.update"));
            update.getStyleClass().add("button");
            update.setMinWidth(Double.NEGATIVE_INFINITY);
            update.setDisable(!item.hasUpdate());
            update.setOnAction((e) -> TaskDialog.runVoid(dialog, I18n.t("content.update"), (progress) -> {
                  progress.accept(new InstallProgress(InstallProgress.Stage.DONE, (double)0.5F, item.fileName()));

                  try {
                     catalog.installLatest(item);
                  } catch (Exception error) {
                     MCLDialog.error(dialog, I18n.t("content.update"), error.getMessage());
                  }

                  return null;
               }, refreshContent));
            contentRow.getChildren().addAll(new Node[]{details, update});
         }
         // 设定档：可以直接开编辑器（文本 / 可视化两种模式）
         if (currentKind == ModManager.Kind.CONFIG && Files.isRegularFile(item.path())) {
            Button edit = new Button(I18n.t("content.edit"));
            edit.getStyleClass().add("button");
            edit.setMinWidth(Double.NEGATIVE_INFINITY);
            edit.setOnAction((e) -> ConfigEditorDialog.show(dialog, item.path()));
            contentRow.getChildren().add(edit);
         }

         contentRow.setOnMouseClicked((event) -> {
            if (event.getClickCount() == 1) {
               for(Node node : contentRow.getChildren()) {
                  if (node instanceof CheckBox) {
                     CheckBox box = (CheckBox)node;
                     if (event.getTarget() != box) {
                        box.setSelected(!box.isSelected());
                        return;
                     }
                  }
               }

            }
         });
         return contentRow;
      };
      kindCombo.valueProperty().addListener((obs, oldValue, value) -> {
         checkedNames.clear();
         refreshContent.run();
      });
      filterCombo.valueProperty().addListener((obs, oldValue, value) -> refreshContent.run());
      refreshContent.run();
      Supplier<List<ModCatalog.Entry>> checkedFiles = () -> currentFiles.stream().filter((item) -> checkedNames.contains(item.fileName())).toList();
      Button toggle = (Button)UiKit.noShrink(new Button(I18n.t("content.toggle")));
      toggle.setOnAction((e) -> {
         List<ModCatalog.Entry> picked = (List)checkedFiles.get();
         if (picked.isEmpty()) {
            MCLDialog.info(dialog, I18n.t("version.tab.content"), I18n.t("content.select_first"));
         } else {
            try {
               for(ModCatalog.Entry item : picked) {
                  context.mods().toggle(new ModManager.ContentFile(item.fileName(), item.path(), item.size(), item.enabled(), (ModManager.Kind)kindCombo.getValue()));
               }

               refreshContent.run();
            } catch (Exception error) {
               MCLDialog.error(dialog, I18n.t("content.toggle"), error.getMessage());
            }

         }
      });
      // 检查更新是「显式」动作：打开 126 个 mod 的列表时不再自动对每个 mod 查一次远端版本
      Button checkUpdates = (Button)UiKit.noShrink(new Button(I18n.t("content.check_updates")));
      checkUpdates.setOnAction((e) -> {
         checkUpdates.setDisable(true);
         checkUpdates.setText(I18n.t("content.checking_updates"));
         java.util.List<ModCatalog.Entry> snapshot = new java.util.ArrayList<>(currentFiles);
         catalog.checkUpdates(snapshot, loaderKey, mcVersion,
                 (resolved) -> Platform.runLater(() -> {
                    java.util.function.Consumer<ModCatalog.Entry> updater =
                            rowUpdaters.get(resolved.fileName());
                    if (updater != null) {
                       updater.accept(resolved);
                    }
                 }));
         // 给个视觉回馈：2 秒后恢复按钮（背景仍在跑，结果到了会自动刷新）
         javafx.animation.PauseTransition restore =
                 new javafx.animation.PauseTransition(javafx.util.Duration.seconds(2));
         restore.setOnFinished(event -> {
            checkUpdates.setDisable(false);
            checkUpdates.setText(I18n.t("content.check_updates"));
         });
         restore.play();
      });
      Button batchUpdate = (Button)UiKit.noShrink(new Button(I18n.t("content.update_all")));
      batchUpdate.setOnAction((e) -> {
         List<ModCatalog.Entry> updatable = currentFiles.stream().filter(ModCatalog.Entry::hasUpdate).toList();
         if (updatable.isEmpty()) {
            MCLDialog.info(dialog, I18n.t("content.update_all"), I18n.t("content.no_updates"));
         } else {
            MCLDialog.confirm(dialog, I18n.t("content.update_all"), I18n.t("content.update_all_confirm", updatable.size()), () -> TaskDialog.runVoid(dialog, I18n.t("content.update_all"), (progress) -> {
                  int index = 0;

                  for(ModCatalog.Entry item : updatable) {
                     ++index;
                     progress.accept(new InstallProgress(InstallProgress.Stage.DONE, (double)index / (double)updatable.size(), item.fileName()));

                     try {
                        catalog.installLatest(item);
                     } catch (Exception error) {
                        String var10000 = item.fileName();
                        SafeLog.warn("更新失败：" + var10000 + "（" + error.getClass().getSimpleName() + "）");
                     }
                  }

                  return null;
               }, () -> {
                  refreshContent.run();
                  MCLDialog.info(dialog, I18n.t("content.update_all"), I18n.t("content.update_done"));
               }));
         }
      });
      Button deleteContent = (Button)UiKit.noShrink(new Button(I18n.t("common.delete")));
      deleteContent.setOnAction((e) -> {
         List<ModCatalog.Entry> picked = (List)checkedFiles.get();
         if (picked.isEmpty()) {
            MCLDialog.info(dialog, I18n.t("version.tab.content"), I18n.t("content.select_first"));
         } else {
            MCLDialog.confirm(dialog, I18n.t("common.delete"), I18n.t("content.delete_multi_confirm", picked.size()), () -> {
               try {
                  for(ModCatalog.Entry item : picked) {
                     context.mods().delete(item.path());
                  }

                  refreshContent.run();
               } catch (Exception error) {
                  MCLDialog.error(dialog, I18n.t("common.delete"), error.getMessage());
               }

            });
         }
      });
      Button openContentDir = (Button)UiKit.noShrink(new Button(I18n.t("content.open_dir")));
      openContentDir.setOnAction((e) -> {
         try {
            openFolder(dialog, context.mods().ensureDir(entry, (ModManager.Kind)kindCombo.getValue()));
         } catch (Exception error) {
            MCLDialog.error(dialog, I18n.t("content.open_dir"), error.getMessage());
         }

      });
      Button selectAll = (Button)UiKit.noShrink(new Button(I18n.t("content.select_all")));
      selectAll.setOnAction((e) -> {  // 只选「筛选后看得到的」
         // 用列表目前显示的项目（= 套过筛选的结果），不要用 currentFiles，
         // 否则「已启用 / 已禁用 / 有更新」的筛选等于没作用（使用者回报两次）。
         for (ModCatalog.Entry item : contentList.getItems()) {
            checkedNames.add(item.fileName());
         }
         refreshContent.run();
      });
      Button selectNone = (Button)UiKit.noShrink(new Button(I18n.t("content.select_none")));
      selectNone.setOnAction((e) -> {
         checkedNames.clear();
         refreshContent.run();
      });
      Runnable updateActionVisibility = () -> {
         boolean modLike = kindCombo.getValue() == ModManager.Kind.MODS || kindCombo.getValue() == ModManager.Kind.RESOURCE_PACKS || kindCombo.getValue() == ModManager.Kind.SHADER_PACKS;
         toggle.setVisible(modLike);
         toggle.setManaged(modLike);
         batchUpdate.setVisible(modLike);
         batchUpdate.setManaged(modLike);
         boolean enableable = kindCombo.getValue() == ModManager.Kind.MODS;
         toggle.setDisable(!enableable);
      };
      kindCombo.valueProperty().addListener((obs, oldValue, value) -> updateActionVisibility.run());
      updateActionVisibility.run();
      FlowPane contentActions = new FlowPane((double)8.0F, (double)8.0F);
      // 前置依赖图：一眼看出哪个 Mod 缺哪个前置，缺失的标红、点气泡可直接去下载
      Button depGraph = (Button)UiKit.noShrink(new Button(I18n.t("depgraph.button")));
      depGraph.setOnAction((e) -> ModDependencyGraphDialog.show(dialog, context, entry));
      contentActions.getChildren().addAll(new Node[]{kindCombo, filterCombo, depGraph, checkUpdates, toggle, batchUpdate, deleteContent, openContentDir, selectAll, selectNone});
      VBox contentBox = new VBox((double)10.0F, new Node[]{contentActions, checkedCount, contentList});
      contentBox.setPadding(new Insets((double)16.0F));
      VBox.setVgrow(contentList, Priority.ALWAYS);
      Tab contentTab = new Tab(I18n.t("version.tab.content"), contentBox);
      VBox loaderBox = new VBox((double)12.0F);
      loaderBox.setPadding(new Insets((double)16.0F));
      Label loaderInfo = new Label(I18n.t("version.settings.loader_current", entry.loaderDisplay()));
      loaderInfo.setWrapText(true);
      boolean vanilla = entry.loader() == null || entry.loader().isVanilla();
      Label conflictHint = UiKit.catHint(vanilla ? I18n.t("version.settings.loader_hint") : I18n.t("version.settings.loader_only_vanilla"));
      conflictHint.setWrapText(true);
      Button manageLoader = new Button(I18n.t("version.settings.manage_loader"));
      manageLoader.setDisable(!vanilla);
      manageLoader.setOnAction((e) -> LoaderInstallDialog.show(dialog, context, entry, () -> {
            if (onChanged != null) {
               onChanged.run();
            }

            dialog.close();
         }));
      loaderBox.getChildren().addAll(new Node[]{loaderInfo, conflictHint, manageLoader});
      Tab loaderTab = new Tab(I18n.t("version.tab.loader"), loaderBox);
      VBox logBox = new VBox((double)12.0F);
      logBox.setPadding(new Insets((double)16.0F));
      Button openLogs = new Button(I18n.t("version.settings.open_logs"));
      openLogs.setOnAction((e) -> openFolder(dialog, entry.gameDir().resolve("logs")));
      Button openCrashes = new Button(I18n.t("version.settings.open_crash_reports"));
      openCrashes.setOnAction((e) -> openFolder(dialog, entry.gameDir().resolve("crash-reports")));
      Button exportLogs = new Button(I18n.t("common.export_logs"));
      exportLogs.setOnAction((e) -> {
         CrashAnalysis analysis = context.crashRules().analyze(entry);
         TaskDialog.run(dialog, I18n.t("crash.exporting"), (progress) -> {
            progress.accept(new InstallProgress(InstallProgress.Stage.ASSETS, 0.1, I18n.t("crash.exporting")));
            return context.crashExporter().export(entry, analysis);
         }, (path) -> {
            MCLDialog.info(dialog, I18n.t("crash.exported"), I18n.t("crash.exported_path", path.toString()));
            DesktopActions.openFolder(path.getParent());
         });
      });
      logBox.getChildren().addAll(new Node[]{openLogs, openCrashes, exportLogs});
      Tab logTab = new Tab(I18n.t("version.tab.logs"), logBox);
      // 截图管理：直接用缩图墙面板（与独立弹窗同一份实现）
      Tab screenshotTab = new Tab(I18n.t("version.tab.screenshots"),
            ScreenshotDialog.createPanel(context, entry));
      tabs.getTabs().addAll(new Tab[]{overviewTab, javaTab, contentTab, loaderTab, logTab, screenshotTab});
      // 开发期：直接选中某个页签（截图/回归验证用，正常启动不走）
      String devTab = System.getProperty("mcl.dev.tab");
      if (devTab != null && !devTab.isBlank()) {
         try {
            tabs.getSelectionModel().select(Integer.parseInt(devTab.trim()));
         } catch (Exception ignored) {
            // 参数不合法就保持默认页签
         }
      }
      Button save = new Button(I18n.t("common.confirm"));
      save.getStyleClass().add("primary-button");
      save.setOnAction((e) -> {
         List<String> args = new ArrayList();

         for(String line : jvmArgs.getText().split("\\R")) {
            if (!line.isBlank()) {
               args.add(line.trim());
            }
         }

         InstanceSettings settings = new InstanceSettings(memory.getValue() == null ? current.memoryMb() : (Integer)memory.getValue(), javaPath.getText(), args, true, 0, 0);
         context.gameRepository().saveSettings(entry, settings);
         if (onChanged != null) {
            onChanged.run();
         }

         dialog.close();
      });
      Button cancel = new Button(I18n.t("common.cancel"));
      cancel.setOnAction((e) -> dialog.close());
      HBox footer = new HBox((double)10.0F, new Node[]{cancel, save});
      footer.setAlignment(Pos.CENTER_RIGHT);
      VBox root = new VBox((double)12.0F, new Node[]{header, tabs, footer});
      root.setPadding(new Insets((double)18.0F));
      root.setPrefSize((double)740.0F, (double)560.0F);
      root.setStyle("-fx-background-color: -brisk-bg; -fx-background-radius: 12; -fx-border-color: -brisk-border; -fx-border-radius: 12; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 15, 0, 0, 5);");
      Scene scene = new Scene(root, (double)740.0F, (double)560.0F);
      UiKit.applyTheme(scene);
      scene.setFill(Color.TRANSPARENT);
      dialog.setScene(scene);
      com.brisklauncher.mcl.ui.UiKit.escToClose(scene, dialog);
      UiKit.makeDraggable(dialog, title);
      UiKit.popIn(dialog, root);
      dialog.setOnHidden(e -> ModIcons.clear());
      // 不要用 showAndWait()：它会开一个巢状事件回圈，而「删除实例」会从这个回圈内部关掉本视窗，
      // JavaFX 在这里容易卡死（实测就是删除后整个介面冻住、列表也不刷新）。
      dialog.show();
   }

   private static void deleteInstance(Window owner, MclContext context, GameInstance entry, Runnable onChanged) {
      {
         // 删除走回收站是 Shell API，会阻塞 → 放背景执行绪。
         // 注意：**不要**用模态 TaskDialog（它的 owner 就是等一下要关掉的那个视窗，
         // 在模态回呼里 close owner 会让 JavaFX 的巢状事件回圈卡住 → 就是「删除卡死」）。
         com.brisklauncher.mcl.security.SafeLog.info("[delete] 开始删除 " + entry.id());
         Thread deleter = new Thread(() -> {
            try {
               context.gameRepository().delete(entry);
               com.brisklauncher.mcl.security.SafeLog.info("[delete] 档案已删除 " + entry.id());
            } catch (Exception error) {
               javafx.application.Platform.runLater(() -> {
                  MCLDialog.error(owner,
                          I18n.t("version.settings.delete_instance"), error.getMessage());
                  // 失败时版本还在（两阶段删除会把资料夹名字改回去），列表也要刷新，
                  // 免得界面上停在一份过期的资料。
                  if (onChanged != null) {
                     try {
                        onChanged.run();
                     } catch (Exception refresh) {
                        com.brisklauncher.mcl.security.SafeLog.warn("删除失败后刷新列表失败："
                                + refresh.getClass().getSimpleName());
                     }
                  }
               });
               return;
            }
            javafx.application.Platform.runLater(() -> {
            // 先关掉管理这个实例的视窗，再刷新列表
            if (owner instanceof Stage stage) {
               stage.close();
               com.brisklauncher.mcl.security.SafeLog.info("[delete] 视窗已关闭");
            }
            if (onChanged != null) {
               try {
                  onChanged.run();
               } catch (Exception error) {
                  com.brisklauncher.mcl.security.SafeLog.warn("删除后刷新列表失败："
                          + error.getClass().getSimpleName());
               }
               com.brisklauncher.mcl.security.SafeLog.info("[delete] 列表已刷新");
            }
            MCLDialog.info(null, I18n.t("version.settings.delete_instance"),
                    I18n.t("version.settings.deleted"));
            });
         }, "brisk-delete");
         deleter.setDaemon(true);
         deleter.start();
      }
   }

   private static ImageView iconView(Image image) {
      ImageView view = new ImageView(image);
      view.setFitWidth((double)32.0F);
      view.setFitHeight((double)32.0F);
      view.setPreserveRatio(true);
      return view;
   }

   /**
    * 本地档案里没有图标时的后备：用 Modrinth 专案图示。
    *
    * <p>光影包几乎都不带 {@code pack.png}，之前列表只好显示字母占位（使用者回报「光影图标不显示」）。
    * 这里走 {@link ModIcons#loadRemote}（同一个 URL 只下载一次、有快取），
    * 并且 {@code iconShown[0]} 一旦为 true 就不再覆盖，避免远端图回来盖掉本地图标。
    */
   private static void applyRemoteIcon(javafx.scene.layout.StackPane cover, String url,
                                       boolean[] iconShown) {
      if (iconShown[0] || url == null || url.isBlank()) {
         return;
      }
      Image cached = ModIcons.cachedRemote(url);
      if (cached != null) {
         cover.getChildren().setAll(new Node[]{iconView(cached)});
         iconShown[0] = true;
         return;
      }
      ModIcons.loadRemote(url, 40, image -> {
         if (image != null && cover.getScene() != null) {
            cover.getChildren().setAll(new Node[]{iconView(image)});
            iconShown[0] = true;
         }
      });
   }

   static void exportModpack(Window owner, MclContext context, GameInstance entry) {
      Path gameDir = entry.gameDir();
      currentGameDir = gameDir;
      CheckBoxTreeItem<String> rootItem = new CheckBoxTreeItem("");
      rootItem.setExpanded(true);
      rootItem.setIndependent(false);
      addExportLeaf(rootItem, I18n.t("version.export.mods"), "mods", true);
      CheckBoxTreeItem<String> configGroup = new CheckBoxTreeItem("");
      configGroup.setIndependent(false);
      configGroup.setExpanded(false);
      configGroup.setGraphic(new Label(I18n.t("version.export.config_group")));
      addExportLeaf(configGroup, I18n.t("version.export.config"), "config", true);
      addExportLeaf(configGroup, "defaultconfigs", "defaultconfigs", true);
      rootItem.getChildren().add(configGroup);
      addExportGroup(rootItem, I18n.t("version.export.resourcepacks"), "resourcepacks", true);
      addExportGroup(rootItem, I18n.t("version.export.shaderpacks"), "shaderpacks", true);
      addExportGroup(rootItem, I18n.t("version.export.saves"), "saves", false);
      addExportLeaf(rootItem, I18n.t("version.export.servers"), "servers.dat", Files.isRegularFile(gameDir.resolve("servers.dat"), new LinkOption[0]));
      addExportLeaf(rootItem, I18n.t("version.export.options_file"), "options.txt", Files.isRegularFile(gameDir.resolve("options.txt"), new LinkOption[0]));
      CheckBoxTreeItem<String> specialGroup = new CheckBoxTreeItem("");
      specialGroup.setIndependent(false);
      specialGroup.setGraphic(new Label(I18n.t("version.export.special_group")));
      Set<String> known = new LinkedHashSet(List.of("mods", "config", "defaultconfigs", "resourcepacks", "shaderpacks", "saves", "servers.dat", "options.txt"));
      LinkedHashMap<String, String> knownDataDirs = new LinkedHashMap();
      knownDataDirs.put("XaeroWaypoints", I18n.t("version.export.special.xaero_points"));
      knownDataDirs.put("XaeroWorldMap", I18n.t("version.export.special.xaero_map"));
      knownDataDirs.put("voxy", I18n.t("version.export.special.voxy"));
      knownDataDirs.put("schematics", I18n.t("version.export.special.schematics"));
      knownDataDirs.put("journeymap", I18n.t("version.export.special.journeymap"));
      knownDataDirs.put("replay_recordings", I18n.t("version.export.special.replay"));
      knownDataDirs.put("essential", I18n.t("version.export.special.essential"));

      for(Map.Entry<String, String> knownDir : knownDataDirs.entrySet()) {
         String name = (String)knownDir.getKey();
         known.add(name);
         if (Files.isDirectory(gameDir.resolve(name), new LinkOption[0])) {
            addExportLeaf(specialGroup, name, name, true);
         }
      }

      Set<String> junk = Set.of("logs", "crash-reports", "versions", "libraries", "assets", "natives", "CustomSkinLoader", "downloads", "screenshots", "debug", "usercache.json", "usercache.json.x", "launcher_profiles.json", "launcher_accounts.json", "optionsof.txt", "realms_persistence.json", "banned-ips.json", "banned-players.json", "ops.json", "whitelist.json", "server.properties", "eula.txt", "patched", ".mcl-backup", "native", "pack.json", "icon.png");

      try {
         Stream<Path> stream = Files.list(gameDir);

         try {
            for(Path path : stream.sorted().toList()) {
               String name = path.getFileName().toString();
               if (!known.contains(name) && !junk.contains(name) && !name.endsWith(".jar") && !name.endsWith(".json") && !name.endsWith(".log") && !name.endsWith(".part") && !name.endsWith(".tmp") && !name.startsWith(".")) {
                  addExportLeaf(specialGroup, name, name, true);
               }
            }
         } catch (Throwable var24) {
            if (stream != null) {
               try {
                  stream.close();
               } catch (Throwable var23) {
                  var24.addSuppressed(var23);
               }
            }

            throw var24;
         }

         if (stream != null) {
            stream.close();
         }
      } catch (Exception var25) {
      }

      if (!specialGroup.getChildren().isEmpty()) {
         specialGroup.setExpanded(false);
         rootItem.getChildren().add(specialGroup);
      }

      TreeView<String> tree = new TreeView(rootItem);
      tree.setCellFactory(CheckBoxTreeCell.forTreeView());
      tree.setShowRoot(false);
      tree.setPrefHeight((double)340.0F);
      tree.setMaxWidth(Double.MAX_VALUE);
      Stage dialog = new Stage();
      dialog.initOwner(owner);
      dialog.initModality(Modality.NONE);
      dialog.initStyle(StageStyle.TRANSPARENT);
      Label title = new Label("\ud83d\udce6 " + I18n.t("version.export_modpack"));
      title.getStyleClass().add("section-title");
      Label hint = UiKit.catHint(I18n.t("version.export.tree_hint"));
      hint.setWrapText(true);
      Button cancel = new Button(I18n.t("common.cancel"));
      Button confirm = new Button(I18n.t("version.export.confirm"));
      confirm.getStyleClass().add("primary-button");
      boolean[] accepted = new boolean[]{false};
      cancel.setOnAction((e) -> dialog.close());
      confirm.setOnAction((e) -> {
         accepted[0] = true;
         dialog.close();
      });
      HBox actions = new HBox((double)8.0F, new Node[]{cancel, confirm});
      actions.setAlignment(Pos.CENTER_RIGHT);
      VBox root = new VBox((double)10.0F, new Node[]{title, hint, tree, actions});
      root.setPadding(new Insets((double)18.0F));
      root.getStyleClass().add("card");
      Scene scene = new Scene(root, (double)560.0F, (double)520.0F);
      UiKit.applyTheme(scene);
      scene.setFill(Color.TRANSPARENT);
      dialog.setScene(scene);
      com.brisklauncher.mcl.ui.UiKit.escToClose(scene, dialog);
      UiKit.makeDraggable(dialog, title);
      UiKit.popIn(dialog, root);
      // 不要用 showAndWait()：它会开一个巢状事件回圈，而「删除实例」会从这个回圈内部关掉本视窗，
      // JavaFX 在这里容易卡死（实测就是删除后整个介面冻住、列表也不刷新）。
      // show() 是非阻塞的：一定要在「视窗关闭后」才检查 accepted，否则 accepted 永远是 false
      // （原本写成 show() 之后立刻 if (accepted[0])，所以汇出永远不会弹出储存对话框）
      dialog.setOnHidden((e) -> {
         if (!accepted[0]) {
            return;
         }
         Set<String> selected = new LinkedHashSet();
         collectExportSelection(rootItem, selected);
         if (selected.isEmpty()) {
            MCLDialog.info(owner, I18n.t("version.export_modpack"), I18n.t("version.export.nothing"));
            return;
         }
         FileChooser chooser = new FileChooser();
         chooser.setTitle(I18n.t("version.export_modpack"));
         chooser.setInitialFileName(entry.id() + ".mrpack");
         chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                 "Modrinth Modpack", new String[]{"*.mrpack"}));
         File file = chooser.showSaveDialog(owner);
         if (file == null) {
            return;
         }
         TaskDialog.runVoid(owner, I18n.t("version.export_modpack"), (progress) -> {
            context.modpackExporter().export(entry, file.toPath(), selected);
            progress.accept(new InstallProgress(InstallProgress.Stage.DONE, (double) 1.0F, file.getName()));
            return null;
         }, () -> {
            MCLDialog.info(owner, I18n.t("version.export_modpack"),
                    I18n.t("version.exported", file.getAbsolutePath()));
            DesktopActions.openFolder(file.getParentFile().toPath());
         });
      });
      dialog.show();
   }

   private static void syncExportTree(CheckBoxTreeItem<String> node) {
      for(TreeItem<String> raw : node.getChildren()) {
         if (raw instanceof CheckBoxTreeItem<String> child) {
            syncExportTree(child);
         }
      }

      if (!node.isIndependent() && !node.getChildren().isEmpty()) {
         boolean all = true;

         for(TreeItem<String> raw : node.getChildren()) {
            if (raw instanceof CheckBoxTreeItem) {
               CheckBoxTreeItem<String> child = (CheckBoxTreeItem)raw;
               if (!child.isSelected()) {
                  all = false;
                  break;
               }
            }
         }

         node.setSelected(all);
      }

   }

   private static void addExportLeaf(CheckBoxTreeItem<String> parent, String label, String value, boolean selected) {
      CheckBoxTreeItem<String> item = new CheckBoxTreeItem(value);
      item.setIndependent(true);
      item.setSelected(selected);
      item.setValue(value);
      parent.getChildren().add(item);
      item.setGraphic(new Label(label));
   }

   private static void addExportGroup(CheckBoxTreeItem<String> parent, String label, String folder, boolean selected) {
      CheckBoxTreeItem<String> group = new CheckBoxTreeItem(folder);
      group.setIndependent(false);
      group.setExpanded(false);
      group.setSelected(selected);
      group.setGraphic(new Label(label));
      Path gameDir = currentGameDir;
      Path target = gameDir == null ? null : gameDir.resolve(folder);
      if (target != null && Files.isDirectory(target, new LinkOption[0])) {
         try {
            Stream<Path> stream = Files.list(target);

            try {
               for(Path path : stream.sorted().toList()) {
                  String relative = folder + "/" + String.valueOf(path.getFileName());
                  CheckBoxTreeItem<String> child = new CheckBoxTreeItem(relative);
                  child.setIndependent(true);
                  child.setSelected(selected);
                  child.setGraphic(new Label(path.getFileName().toString()));
                  group.getChildren().add(child);
               }
            } catch (Throwable var13) {
               if (stream != null) {
                  try {
                     stream.close();
                  } catch (Throwable var12) {
                     var13.addSuppressed(var12);
                  }
               }

               throw var13;
            }

            if (stream != null) {
               stream.close();
            }
         } catch (Exception var14) {
         }
      }

      parent.getChildren().add(group);
   }

   private static void collectExportSelection(CheckBoxTreeItem<String> item, Set<String> selected) {
      String value = (String)item.getValue();
      if (value != null && !value.isBlank() && item.isSelected() && !item.isIndeterminate()) {
         selected.add(value);
      } else {
         for(TreeItem<String> child : item.getChildren()) {
            if (child instanceof CheckBoxTreeItem) {
               CheckBoxTreeItem<String> check = (CheckBoxTreeItem)child;
               collectExportSelection(check, selected);
            }
         }

      }
   }

   private static String sizeText(long bytes) {
      if (bytes < 1024L) {
         return bytes + " B";
      } else {
         return bytes < 1048576L ? String.format(Locale.ROOT, "%.0f KB", (double)bytes / (double)1024.0F) : String.format(Locale.ROOT, "%.1f MB", (double)bytes / (double)1048576.0F);
      }
   }

   private static void openFolder(Window owner, Path path) {
      try {
         Files.createDirectories(path);
      } catch (Exception error) {
         MCLDialog.error(owner, I18n.t("version.settings.title"), I18n.t("version.open_folder_failed", error.getMessage()));
         return;
      }

      if (!DesktopActions.openFolder(path)) {
         MCLDialog.error(owner, I18n.t("version.settings.title"), I18n.t("version.open_folder_failed", path.toString()));
      }

   }

   static Path pickDirectory(Window owner) {
      DirectoryChooser chooser = new DirectoryChooser();
      File dir = chooser.showDialog(owner);
      return dir == null ? null : dir.toPath();
   }

   /**
    * 重命名前的验证：回传错误说明（null = 合法）。
    *
    * <p>三个常见失败都在这里先挡下来，玩家不必等 {@code Files.move} 抛例外：
    * 非法字元、目标资料夹已存在、与目前名称相同。
    */
   public static String validateVersionName(GameInstance entry, String name) {
      String value = name == null ? "" : name.trim();
      if (value.isEmpty()) {
         return I18n.t("version.rename_invalid_empty");
      }
      if (value.equals(entry.id())) {
         return I18n.t("version.rename_same");
      }
      try {
         com.brisklauncher.mcl.security.PathSafety.requirePathSegment(value);
      } catch (RuntimeException bad) {
         return I18n.t("version.rename_invalid_chars");
      }
      Path target = entry.root().versionsDir().resolve(value);
      Path current = entry.versionDir() == null
              ? entry.root().versionsDir().resolve(entry.id()) : entry.versionDir();
      boolean same = target.toAbsolutePath().normalize()
              .equals(current.toAbsolutePath().normalize());
      if (!same && Files.exists(target)) {
         return I18n.t("version.rename_exists", value);
      }
      return null;
   }
}
