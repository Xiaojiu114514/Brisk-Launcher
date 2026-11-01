package com.brisklauncher.mcl.ui;

import com.brisklauncher.mcl.MclContext;
import com.brisklauncher.mcl.auth.AccountStore;
import com.brisklauncher.mcl.auth.api.AccountType;
import com.brisklauncher.mcl.game.GameInstance;
import com.brisklauncher.mcl.i18n.I18n;
import com.brisklauncher.mcl.security.SafeLog;
import com.brisklauncher.mcl.skin.SkinManager;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

public final class SkinLibraryPane extends VBox {
   private final MclContext context;
   private final Canvas avatar = new Canvas((double)128.0F, (double)128.0F);
   private final StackPane previewBox = new StackPane();
   private final Button toggle3d = new Button(I18n.t("skin.show_3d"));
   private final FlowPane library = new FlowPane((double)6.0F, (double)6.0F);
   private final Label libraryHint = new Label(I18n.t("skin.library_hint"));
   private final Label status = new Label();
   private final Label targetLabel = new Label(I18n.t("skin.no_target"));
   private final Button choose = new Button(I18n.t("skin.choose"));
   private final Button chooseCape = new Button(I18n.t("skin.choose_cape"));
   private final Button apply = new Button(I18n.t("skin.apply"));
   private final Image steve = loadSteve();
   private SkinPreview3D preview3d;
   private GameInstance target;
   private String playerName = "";
   private boolean autoApply;
   private boolean fetchingOnline;
   private AccountStore.Account current;

   public SkinLibraryPane(MclContext context) {
      this.context = context;
      this.setPadding(Insets.EMPTY);
      this.setSpacing((double)8.0F);
      this.setPrefWidth((double)360.0F);
      this.setMaxWidth(Double.MAX_VALUE);
      this.setFillWidth(true);
      Label title = new Label(I18n.t("skin.title"));
      title.getStyleClass().add("section-title");
      this.avatar.setWidth((double)128.0F);
      this.avatar.setHeight((double)128.0F);
      this.previewBox.getChildren().setAll(new Node[]{this.avatar});
      this.previewBox.getStyleClass().add("skin-preview-box");
      this.previewBox.setAlignment(Pos.CENTER);
      this.previewBox.setPrefHeight((double)168.0F);
      this.previewBox.setMinHeight((double)168.0F);
      this.toggle3d.getStyleClass().add("filter-chip");
      this.toggle3d.setOnAction((e) -> this.toggle3d());
      this.status.getStyleClass().add("cat-hint");
      this.status.setWrapText(true);
      this.status.setMaxWidth((double)320.0F);
      this.targetLabel.getStyleClass().add("cat-hint");
      this.targetLabel.setWrapText(true);
      this.targetLabel.setMaxWidth((double)320.0F);
      this.choose.setOnAction((e) -> this.chooseFile(false));
      this.chooseCape.setOnAction((e) -> this.chooseFile(true));
      HBox buttons = new HBox((double)6.0F, new Node[]{this.choose, this.chooseCape, this.toggle3d});
      buttons.setAlignment(Pos.CENTER_LEFT);
      this.libraryHint.getStyleClass().add("cat-hint");
      this.libraryHint.setWrapText(true);
      this.library.setPrefWrapLength((double)320.0F);
      this.library.setPadding(new Insets((double)2.0F));
      this.apply.getStyleClass().add("primary-button");
      this.apply.setMaxWidth(Double.MAX_VALUE);
      this.apply.setOnAction((e) -> this.applySkin(true));
      this.getChildren().addAll(new Node[]{title, this.previewBox, this.targetLabel, buttons, this.libraryHint, this.library, this.apply, this.status});
      this.refreshModel();
      if (Boolean.getBoolean("mcl.skin.3d")) {
         Platform.runLater(this::toggle3d);
      }

   }

   private void toggle3d() {
      if (this.preview3d == null) {
         this.preview3d = new SkinPreview3D();
         this.previewBox.getChildren().setAll(new Node[]{this.preview3d});
         this.toggle3d.setText(I18n.t("skin.show_2d"));
         this.applyTextureToPreview();
         this.status.setText(I18n.t("skin.preview_ready"));
      } else {
         this.preview3d.dispose();
         this.preview3d = null;
         this.previewBox.getChildren().setAll(new Node[]{this.avatar});
         this.toggle3d.setText(I18n.t("skin.show_3d"));
         this.refreshModel();
      }

   }

   public void release() {
      if (this.preview3d != null) {
         this.preview3d.dispose();
         this.preview3d = null;
      }

      this.previewBox.getChildren().setAll(new Node[]{this.avatar});
      this.library.getChildren().clear();
      this.previewBox.setVisible(true);
      this.target = null;
      this.current = null;
      this.status.setText("");
   }

   private void refreshModel() {
      this.current = this.context.accounts().current().orElse(null);
      boolean offline = this.current != null && this.current.type() == AccountType.OFFLINE;
      this.libraryHint.setVisible(offline);
      this.libraryHint.setManaged(offline);
      this.library.setVisible(offline);
      this.library.setManaged(offline);
      this.choose.setVisible(offline);
      this.choose.setManaged(offline);
      this.chooseCape.setVisible(offline);
      this.chooseCape.setManaged(offline);
      this.apply.setVisible(offline);
      this.apply.setManaged(offline);
      if (offline) {
         this.libraryHint.setText(I18n.t("skin.library_hint"));
         this.refreshLibrary();
      } else if (this.current == null) {
         this.libraryHint.setText(I18n.t("skin.library_offline"));
      } else {
         this.libraryHint.setText(I18n.t("skin.library_online"));
      }

      if (this.current == null) {
         this.showSkin((Path)null);
         this.status.setText(I18n.t("skin.placeholder"));
      } else if (offline) {
         Path skin = this.context.skins().globalSkin();
         this.showSkin(Files.isRegularFile(skin, new LinkOption[0]) ? skin : null);
         this.status.setText(Files.isRegularFile(skin, new LinkOption[0]) ? (this.preview3d == null ? I18n.t("skin.avatar_ready") : I18n.t("skin.preview_ready")) : I18n.t("skin.placeholder"));
      } else {
         Optional<Path> cached = this.context.skins().onlineSkinIfPresent(this.current.uuid());
         if (cached.isPresent()) {
            this.showSkin((Path)cached.get());
            this.status.setText(I18n.t("skin.online_ready"));
         } else {
            this.showSkin((Path)null);
            this.status.setText(I18n.t("skin.online_loading"));
            this.fetchOnlineSkin(false);
         }

      }
   }

   private void showSkin(Path skin) {
      Image image = this.steve;
      if (skin != null) {
         // 安全审计 v2 A-06：皮肤档也可能是「别人给的」，先看尺寸再解码
         Image loaded = com.brisklauncher.mcl.security.ImageGuard.acceptable(skin)
                 ? com.brisklauncher.mcl.security.GuardedImages.fromFileOriginal(skin)
                 : this.steve;
         if (!loaded.isError() && loaded.getWidth() >= (double)64.0F) {
            image = loaded;
         }
      }

      GraphicsContext gc = this.avatar.getGraphicsContext2D();
      gc.clearRect((double)0.0F, (double)0.0F, this.avatar.getWidth(), this.avatar.getHeight());
      gc.setImageSmoothing(false);
      double factor = Math.max((double)1.0F, image.getWidth() / (double)64.0F);
      gc.drawImage(image, (double)8.0F * factor, (double)8.0F * factor, (double)8.0F * factor, (double)8.0F * factor, (double)0.0F, (double)0.0F, this.avatar.getWidth(), this.avatar.getHeight());
      if (this.preview3d != null) {
         if (skin != null) {
            this.preview3d.setSkin(skin);
         } else {
            this.preview3d.usePlaceholder();
         }
      }

   }

   private void applyTextureToPreview() {
      if (this.preview3d != null) {
         if (this.current == null) {
            this.preview3d.usePlaceholder();
         } else {
            Path skin = this.current.type() == AccountType.OFFLINE ? this.context.skins().globalSkin() : this.context.skins().onlineSkin(this.current.uuid());
            if (Files.isRegularFile(skin, new LinkOption[0])) {
               this.preview3d.setSkin(skin);
            } else {
               this.preview3d.usePlaceholder();
            }

         }
      }
   }

   private void fetchOnlineSkin(boolean force) {
      if (!this.fetchingOnline && this.current != null && this.current.type() != AccountType.OFFLINE) {
         this.fetchingOnline = true;
         AccountStore.Account account = this.current;
         CompletableFuture.runAsync(() -> {
            Optional<Path> skin = this.context.skins().refreshOnlineSkin(account);
            Platform.runLater(() -> {
               this.fetchingOnline = false;
               if (skin.isPresent()) {
                  this.showSkin((Path)skin.get());
                  this.status.setText(I18n.t("skin.online_ready"));
               } else {
                  this.status.setText(I18n.t("skin.online_failed"));
               }

            });
         });
      }
   }

   private void refreshLibrary() {
      this.library.getChildren().clear();

      for(Path file : this.context.skins().librarySkins()) {
         Image image = com.brisklauncher.mcl.security.ImageGuard.acceptable(file)
                 ? com.brisklauncher.mcl.security.GuardedImages
                         .fromFile(file, 40, 40, true, true)
                 : this.steve;
         ImageView view = new ImageView(image);
         view.setFitWidth((double)40.0F);
         view.setFitHeight((double)40.0F);
         view.setPreserveRatio(false);
         double factor = image.getWidth() / (double)64.0F;
         view.setViewport(new Rectangle2D((double)8.0F * factor, (double)8.0F * factor, (double)8.0F * factor, (double)8.0F * factor));
         StackPane tile = new StackPane(new Node[]{view});
         tile.getStyleClass().add("skin-tile");
         tile.setOnMouseClicked((event) -> {
            this.context.skins().selectSkin(file);
            this.refreshModel();
            if (this.autoApply && this.target != null) {
               this.applySkin(false);
            }

         });
         this.library.getChildren().add(tile);
      }

      if (this.library.getChildren().isEmpty()) {
         Label empty = UiKit.catHint(I18n.t("skin.library_empty"));
         empty.setWrapText(true);
         this.library.getChildren().add(empty);
      }

   }

   public void setTarget(GameInstance entry, String playerName, boolean autoApply) {
      this.target = entry;
      this.playerName = playerName == null ? "" : playerName;
      this.autoApply = autoApply;
      if (entry == null) {
         this.targetLabel.setText(I18n.t("skin.no_target"));
      } else {
         this.targetLabel.setText(I18n.t("skin.target", entry.id()));
      }

      if (autoApply && entry != null && !this.playerName.isBlank()) {
         this.applySkin(false);
      } else {
         this.refreshModel();
      }

   }

   public void refresh() {
      this.refreshModel();
      if (this.current != null && this.current.type() != AccountType.OFFLINE) {
         this.fetchOnlineSkin(true);
      }

   }

   private void chooseFile(boolean cape) {
      if (this.current != null && this.current.type() == AccountType.OFFLINE) {
         FileChooser chooser = new FileChooser();
         chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG", new String[]{"*.png"}));
         File file = chooser.showOpenDialog(this.getScene() == null ? null : this.getScene().getWindow());
         if (file != null) {
            try {
               if (cape) {
                  this.context.skins().setCape(file.toPath());
                  this.status.setText(I18n.t("skin.cape_loaded"));
               } else {
                  this.context.skins().importSkin(file.toPath());
                  this.status.setText(I18n.t("skin.imported"));
               }

               this.refreshModel();
               if (this.autoApply && this.target != null) {
                  this.applySkin(false);
               }
            } catch (Exception var5) {
               SafeLog.warn("皮肤文件保存失败");
               this.status.setText(I18n.t("skin.copy_failed"));
            }

         }
      } else {
         this.status.setText(I18n.t("skin.library_offline"));
      }
   }

   private void applySkin(boolean notify) {
      if (this.target == null) {
         this.status.setText(I18n.t("skin.no_target"));
      } else {
         SkinManager.ApplyResult result = this.context.skins().applyToInstance(this.target, this.playerName);
         Label var10000 = this.status;
         String var10001;
         switch (result.status()) {
            case APPLIED -> var10001 = I18n.t("skin.applied_hint");
            case NO_SKIN -> var10001 = I18n.t("skin.none");
            case NO_TARGET -> var10001 = I18n.t("skin.no_target");
            case CSL_MISSING -> var10001 = I18n.t("skin.no_csl");
            case FAILED -> var10001 = I18n.t("skin.apply_failed");
            default -> throw new MatchException((String)null, (Throwable)null);
         }

         var10000.setText(var10001);
         if (notify && result.status() == SkinManager.Status.APPLIED) {
            MCLDialog.info(this.getScene().getWindow(), I18n.t("skin.title"), I18n.t("skin.applied_hint"));
         }

      }
   }

   private static Image loadSteve() {
      try {
         InputStream in = SkinLibraryPane.class.getResourceAsStream("/styles/steve.png");

         Image var1;
         label50: {
            try {
               if (in != null) {
                  var1 = com.brisklauncher.mcl.security.GuardedImages.fromStreamOriginal(in);
                  break label50;
               }
            } catch (Throwable var4) {
               if (in != null) {
                  try {
                     in.close();
                  } catch (Throwable var3) {
                     var4.addSuppressed(var3);
                  }
               }

               throw var4;
            }

            if (in != null) {
               in.close();
            }

            return new WritableImage(1, 1);
         }

         if (in != null) {
            in.close();
         }

         return var1;
      } catch (Exception var5) {
         return new WritableImage(1, 1);
      }
   }
}
