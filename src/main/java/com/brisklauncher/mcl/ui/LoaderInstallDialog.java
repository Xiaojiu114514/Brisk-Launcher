package com.brisklauncher.mcl.ui;

import com.brisklauncher.mcl.MclContext;
import com.brisklauncher.mcl.download.remote.LoaderMetaClient;
import com.brisklauncher.mcl.download.remote.ModrinthClient;
import com.brisklauncher.mcl.game.GameInstance;
import com.brisklauncher.mcl.game.LoaderInfo;
import com.brisklauncher.mcl.game.install.InstallProgress;
import com.brisklauncher.mcl.i18n.I18n;
import com.brisklauncher.mcl.security.SafeLog;
import java.util.List;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

public final class LoaderInstallDialog {
   private LoaderInstallDialog() {
   }

   public static void show(Window owner, MclContext context, GameInstance entry, Runnable onInstalled) {
      Stage dialog = new Stage();
      dialog.initOwner(owner);
      dialog.initModality(Modality.NONE);
      dialog.initStyle(StageStyle.TRANSPARENT);
      String var10002 = I18n.t("loader.title");
      Label title = new Label("\ud83e\udde9 " + var10002 + " · " + entry.mcVersion());
      title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: -brisk-text;");
      Button close = UiKit.closeButtonStyled();
      close.getStyleClass().addAll(new String[]{"window-button", "window-close"});
      close.setOnAction((e) -> dialog.close());
      Region spacer = new Region();
      HBox.setHgrow(spacer, Priority.ALWAYS);
      HBox header = new HBox(new Node[]{title, spacer, close});
      header.setAlignment(Pos.CENTER_LEFT);
      ToggleGroup typeGroup = new ToggleGroup();
      HBox typeRow = new HBox((double)6.0F);
      ToggleButton currentButton = null;
      ToggleButton fallbackButton = null;

      for(LoaderInfo.LoaderType type : List.of(LoaderInfo.LoaderType.FABRIC, LoaderInfo.LoaderType.FORGE, LoaderInfo.LoaderType.NEOFORGE, LoaderInfo.LoaderType.OPTIFINE)) {
         ToggleButton button = new ToggleButton(type.displayName());
         button.setToggleGroup(typeGroup);
         button.getStyleClass().add("filter-chip");
         button.setUserData(type);
         typeRow.getChildren().add(button);
         if (fallbackButton == null) {
            fallbackButton = button;
         }

         if (entry.loader() != null && entry.loader().type() == type) {
            currentButton = button;
         }
      }

      (currentButton == null ? fallbackButton : currentButton).setSelected(true);
      ComboBox<LoaderMetaClient.LoaderVersion> versionCombo = new ComboBox();
      versionCombo.setMaxWidth(Double.MAX_VALUE);
      versionCombo.setPromptText(I18n.t("download.loader_version"));
      versionCombo.setCellFactory((view) -> new ListCell<LoaderMetaClient.LoaderVersion>() {
            protected void updateItem(LoaderMetaClient.LoaderVersion version, boolean empty) {
               super.updateItem(version, empty);
               this.setText(!empty && version != null ? version.version() + (version.stable() ? "  ★" : "") : null);
            }
         });
      Label status = new Label();
      status.getStyleClass().add("cat-hint");
      status.setWrapText(true);
      CheckBox fabricApi = new CheckBox(I18n.t("download.fabric_api"));
      fabricApi.setSelected(true);
      String[] fabricApiVersion = new String[]{null};
      Button pickFabricApi = new Button(I18n.t("download.fabric_api_version"));
      pickFabricApi.setOnAction((e) -> {
         List<ModrinthClient.VersionFile> files = context.fabricApiVersions(entry.mcVersion());
         if (files.isEmpty()) {
            MCLDialog.info(owner, I18n.t("download.fabric_api"), I18n.t("content.no_versions"));
         } else {
            MCLDialog.choose(owner, I18n.t("download.fabric_api_version"), I18n.t("download.fabric_api_version_hint"), files, (file) -> {
               String var10000 = file.versionNumber();
               return var10000 + "  (" + file.fileName() + ")";
            }, (file) -> {
               fabricApiVersion[0] = file.versionId();
               pickFabricApi.setText(file.versionNumber());
            });
         }
      });
      HBox fabricApiRow = new HBox((double)8.0F, new Node[]{fabricApi, pickFabricApi});
      fabricApiRow.setAlignment(Pos.CENTER_LEFT);
      Button install = new Button(I18n.t("download.install"));
      install.getStyleClass().add("primary-button");
      install.setDisable(true);
      Runnable updateFabricApiVisibility = () -> {
         ToggleButton selected = (ToggleButton)typeGroup.getSelectedToggle();
         boolean fabric = selected != null && selected.getUserData() == LoaderInfo.LoaderType.FABRIC;
         fabricApiRow.setVisible(fabric);
         fabricApiRow.setManaged(fabric);
      };
      Runnable loadVersions = () -> {
         ToggleButton selected = (ToggleButton)typeGroup.getSelectedToggle();
         if (selected != null) {
            LoaderInfo.LoaderType type = (LoaderInfo.LoaderType)selected.getUserData();
            if (type == LoaderInfo.LoaderType.OPTIFINE) {
               versionCombo.getItems().clear();
               status.setText(I18n.t("loader.optifine_manual"));
               install.setDisable(true);
            } else {
               status.setText(I18n.t("download.loading"));
               versionCombo.getItems().clear();
               Thread.ofVirtual().name("mcl-loader-versions").start(() -> {
                  try {
                     List<LoaderMetaClient.LoaderVersion> versions = context.gameRepository().availableLoaders(type, entry.mcVersion());
                     Platform.runLater(() -> {
                        versionCombo.getItems().setAll(versions);
                        if (!versions.isEmpty()) {
                           versionCombo.getSelectionModel().selectFirst();
                        }

                        status.setText(versions.isEmpty() ? I18n.t("loader.none") : I18n.t("download.total", versions.size()));
                        install.setDisable(versions.isEmpty());
                     });
                  } catch (Exception e) {
                     Platform.runLater(() -> {
                        status.setText(e.getMessage());
                        install.setDisable(true);
                     });
                  }

               });
            }
         }
      };
      typeGroup.selectedToggleProperty().addListener((obs, oldValue, newValue) -> {
         updateFabricApiVisibility.run();
         loadVersions.run();
      });
      updateFabricApiVisibility.run();
      loadVersions.run();
      install.setOnAction((e) -> {
         ToggleButton selected = (ToggleButton)typeGroup.getSelectedToggle();
         LoaderMetaClient.LoaderVersion version = (LoaderMetaClient.LoaderVersion)versionCombo.getValue();
         if (selected != null && version != null) {
            LoaderInfo.LoaderType type = (LoaderInfo.LoaderType)selected.getUserData();
            boolean withFabricApi = fabricApi.isVisible() && fabricApi.isSelected();
            Runnable doInstall = () -> TaskDialog.run(dialog, I18n.t("download.install_loader", type.displayName(), version.version()), (progress) -> {
                  GameInstance installed = context.gameRepository().installLoader(entry.root(), type, entry.mcVersion(), version.version(), progress);
                  if (withFabricApi) {
                     progress.accept(new InstallProgress(InstallProgress.Stage.LOADER, 0.92, "Fabric API"));

                     try {
                        context.installFabricApi(installed, fabricApiVersion[0]);
                     } catch (Exception error) {
                        SafeLog.warn("Fabric API 安装失败：" + error.getClass().getSimpleName());
                     }
                  }

                  return installed;
               }, (installed) -> {
                  dialog.close();
                  Platform.runLater(() -> {
                     String var10001 = I18n.t("loader.title");
                     String installedInfo = I18n.t("loader.installed", installed.id());
                     MCLDialog.info(owner, var10001, installedInfo + (withFabricApi ? "\n" + I18n.t("download.fabric_api_added", "Fabric API") : ""));
                  });
                  if (onInstalled != null) {
                     onInstalled.run();
                  }

               });
            if (entry.loader() != null && !entry.loader().isVanilla() && entry.loader().type() != type) {
               MCLDialog.confirm(dialog, I18n.t("loader.title"), I18n.t("loader.other_loader_confirm", entry.loaderDisplay(), type.displayName()), doInstall);
            } else {
               doInstall.run();
            }

         }
      });
      Label hint = UiKit.catHint(I18n.t("loader.hint"));
      hint.setWrapText(true);
      Label newHint = UiKit.catHint(I18n.t("loader.new_instance_hint", entry.id()));
      newHint.setWrapText(true);
      VBox root = new VBox((double)14.0F, new Node[]{header, new Label(I18n.t("download.loader_type")), typeRow, new Label(I18n.t("download.loader_version")), versionCombo, fabricApiRow, install, status, newHint, hint});
      root.setPadding(new Insets((double)20.0F));
      root.setPrefWidth((double)520.0F);
      root.setStyle("-fx-background-color: -brisk-bg; -fx-background-radius: 12; -fx-border-color: -brisk-border; -fx-border-radius: 12; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 15, 0, 0, 5);");
      Scene scene = new Scene(root, (double)520.0F, (double)400.0F);
      UiKit.applyTheme(scene);
      scene.setFill(Color.TRANSPARENT);
      dialog.setScene(scene);
      com.brisklauncher.mcl.ui.UiKit.escToClose(scene, dialog);
      dialog.sizeToScene();
      UiKit.makeDraggable(dialog, header);
      UiKit.popIn(dialog, root);
      dialog.show();
   }
}
