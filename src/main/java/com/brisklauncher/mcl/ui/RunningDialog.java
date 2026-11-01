package com.brisklauncher.mcl.ui;

import com.brisklauncher.mcl.game.launch.RunningGames;
import com.brisklauncher.mcl.i18n.I18n;
import java.util.List;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

public final class RunningDialog {
   private RunningDialog() {
   }

   public static void show(Window owner, RunningGames runningGames) {
      Stage dialog = new Stage();
      dialog.initOwner(owner);
      dialog.initModality(Modality.NONE);
      dialog.initStyle(StageStyle.TRANSPARENT);
      Label title = new Label(I18n.t("running.title"));
      title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #14453F;");
      Button close = UiKit.closeButtonStyled();
      close.getStyleClass().addAll(new String[]{"window-button", "window-close"});
      close.setOnAction((e) -> dialog.close());
      Region spacer = new Region();
      HBox.setHgrow(spacer, Priority.ALWAYS);
      HBox header = new HBox(new Node[]{title, spacer, close});
      header.setAlignment(Pos.CENTER_LEFT);
      VBox list = new VBox((double)8.0F);
      List<RunningGames.RunningGame> games = runningGames.list();
      if (games.isEmpty()) {
         Label empty = new Label(I18n.t("running.none"));
         empty.getStyleClass().add("cat-hint");
         list.getChildren().add(empty);
      } else {
         for(RunningGames.RunningGame game : games) {
            Label name = new Label(game.versionId());
            name.setStyle("-fx-font-weight: bold;");
            long var10002 = game.pid();
            Label meta = new Label("PID " + var10002 + " · " + game.uptime());
            meta.getStyleClass().add("cat-hint");
            VBox text = new VBox((double)2.0F, new Node[]{name, meta});
            HBox.setHgrow(text, Priority.ALWAYS);
            Button stop = new Button(I18n.t("running.stop"));
            stop.getStyleClass().add("button");
            stop.setOnAction((e) -> {
               runningGames.stop(game.pid());
               stop.setDisable(true);
               stop.setText(I18n.t("running.stopped"));
            });
            HBox row = new HBox((double)8.0F, new Node[]{text, stop});
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets((double)10.0F));
            row.getStyleClass().add("entry-card");
            list.getChildren().add(row);
         }
      }

      Button stopAll = new Button(I18n.t("running.stop_all"));
      stopAll.getStyleClass().add("primary-button");
      stopAll.setDisable(games.isEmpty());
      stopAll.setOnAction((e) -> MCLDialog.confirm(owner, I18n.t("running.stop_all"), I18n.t("running.confirm_stop_all"), () -> {
            runningGames.stopAll();
            dialog.close();
         }));
      VBox root = new VBox((double)14.0F, new Node[]{header, list, stopAll});
      root.setPadding(new Insets((double)20.0F));
      root.setStyle("-fx-background-color: #F4FCFA; -fx-background-radius: 12; -fx-border-color: #BFE9E2; -fx-border-radius: 12; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 15, 0, 0, 5);");
      Scene scene = new Scene(root, (double)460.0F, (double)200.0F);
      UiKit.applyTheme(scene);
      scene.setFill(Color.TRANSPARENT);
      dialog.setScene(scene);
      com.brisklauncher.mcl.ui.UiKit.escToClose(scene, dialog);
      dialog.sizeToScene();
      UiKit.makeDraggable(dialog, header);
      UiKit.popIn(dialog, root);
      dialog.showAndWait();
   }
}
