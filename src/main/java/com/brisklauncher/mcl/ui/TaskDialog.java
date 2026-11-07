package com.brisklauncher.mcl.ui;

import com.brisklauncher.mcl.game.install.InstallProgress;
import com.brisklauncher.mcl.i18n.I18n;
import com.brisklauncher.mcl.task.Task;
import com.brisklauncher.mcl.task.TaskExecutor;
import com.brisklauncher.mcl.task.TaskListener;
import java.nio.file.AccessDeniedException;
import java.util.Objects;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

public final class TaskDialog {
   private TaskDialog() {
   }

   public static <T> void run(Window owner, String title, TaskBody<T> body, final Consumer<T> onSuccess) {
      final Stage dialog = new Stage();
      dialog.initOwner(owner);
      // 安装/下载期间必须锁住整个启动器：以前是 Modality.NONE，玩家可以一边安装
      // 一边在「版本设置」里把正在写入的版本删掉 → 半个版本、档案占用、安装失败。
      dialog.initModality(Modality.APPLICATION_MODAL);
      dialog.initStyle(StageStyle.TRANSPARENT);
      dialog.setTitle(title);
      dialog.setResizable(false);
      Label titleLabel = new Label(title);
      titleLabel.getStyleClass().add("section-title");
      final Label stageLabel = new Label(I18n.t("task.preparing"));
      stageLabel.getStyleClass().add("instance-meta");
      final Label detailLabel = new Label("");
      detailLabel.getStyleClass().add("cat-hint");
      detailLabel.setWrapText(true);
      detailLabel.setMaxWidth((double)420.0F);
      final ProgressBar bar = new ProgressBar((double)-1.0F);
      bar.setMaxWidth(Double.MAX_VALUE);
      bar.getStyleClass().add("startup-progress");
      Region spacer = new Region();
      HBox.setHgrow(spacer, Priority.ALWAYS);
      final Button cancel = new Button(I18n.t("common.cancel"));
      cancel.getStyleClass().add("button");
      HBox footer = new HBox((double)8.0F, new Node[]{spacer, cancel});
      footer.setAlignment(Pos.CENTER_RIGHT);
      VBox root = new VBox((double)10.0F, new Node[]{titleLabel, stageLabel, bar, detailLabel, footer});
      root.setPadding(new Insets((double)20.0F));
      root.setStyle("-fx-background-color: -brisk-bg; -fx-background-radius: 12; -fx-border-color: -brisk-border; -fx-border-radius: 12;");
      Scene scene = new Scene(root, (double)480.0F, (double)210.0F);
      UiKit.applyTheme(scene);
      scene.setFill(Color.TRANSPARENT);
      dialog.setScene(scene);
      com.brisklauncher.mcl.ui.UiKit.escToClose(scene, dialog);
      Objects.requireNonNull(body);
      Task<T> task = Task.<T>of(title, body::run);
      TaskExecutor.run(task, new TaskListener<T>() {
         public void onProgress(InstallProgress progress) {
            bar.setProgress(progress.progress() < (double)0.0F ? (double)-1.0F : progress.progress());
            stageLabel.setText(TaskDialog.stageText(progress.stage()));
            detailLabel.setText(progress.detail() == null ? "" : progress.detail());
         }

         public void onSucceeded(T result) {
            dialog.close();
            if (onSuccess != null) {
               Platform.runLater(() -> onSuccess.accept(result));
            }

         }

         public void onFailed(Throwable error) {
            bar.setProgress((double)0.0F);
            bar.setVisible(false);
            stageLabel.setText(I18n.t("task.failed"));
            detailLabel.setText(TaskDialog.friendlyMessage(error));
            detailLabel.setStyle("-fx-text-fill: -brisk-accent-2;");
            cancel.setText(I18n.t("common.close"));
            cancel.setDisable(false);
            dialog.setHeight((double)280.0F);
         }

         public void onCancelled() {
            dialog.close();
         }
      });
      cancel.setOnAction((e) -> {
         if (task.state() == Task.State.RUNNING) {
            task.cancel();
            stageLabel.setText(I18n.t("task.cancelling"));
            cancel.setDisable(true);
         } else {
            dialog.close();
         }

      });
      dialog.setOnCloseRequest((event) -> {
         if (task.state() == Task.State.RUNNING) {
            task.cancel();
            event.consume();
         }

      });
      UiKit.popIn(dialog, root);
      dialog.show();
      com.brisklauncher.mcl.security.SafeLog.info("[task] 进度框已显示（模态="
              + dialog.getModality() + "），主窗口交互已锁定");
   }

   public static void runVoid(Window owner, String title, TaskBody<Void> body, Runnable onSuccess) {
      run(owner, title, body, (ignored) -> {
         if (onSuccess != null) {
            onSuccess.run();
         }

      });
   }

   private static String friendlyMessage(Throwable error) {
      Throwable rootCause;
      for(rootCause = error; rootCause.getCause() != null && rootCause.getCause() != rootCause; rootCause = rootCause.getCause()) {
      }

      String message = rootCause.getMessage() != null && !rootCause.getMessage().isBlank() ? rootCause.getMessage() : rootCause.getClass().getSimpleName();
      if (rootCause instanceof AccessDeniedException || message.contains("Access is denied") || message.contains("存取被拒") || message.contains("拒绝访问")) {
         message = message + "\n\n" + I18n.t("task.permission_hint");
      }

      return message;
   }

   static String stageText(InstallProgress.Stage stage) {
      String var10000;
      switch (stage) {
         case RESOLVING -> var10000 = I18n.t("install.stage.resolving");
         case VERSION_JSON -> var10000 = I18n.t("install.stage.version_json");
         case CLIENT_JAR -> var10000 = I18n.t("install.stage.client_jar");
         case LIBRARIES -> var10000 = I18n.t("install.stage.libraries");
         case ASSETS -> var10000 = I18n.t("install.stage.assets");
         case LOGGING -> var10000 = I18n.t("install.stage.logging");
         case LOADER -> var10000 = I18n.t("install.stage.loader");
         case JAVA -> var10000 = I18n.t("install.stage.java");
         case FINISHING -> var10000 = I18n.t("install.stage.finishing");
         case DONE -> var10000 = I18n.t("install.stage.done");
         case UPDATE_DOWNLOAD -> var10000 = I18n.t("update.stage.download");
         case UPDATE_EXTRACT -> var10000 = I18n.t("update.stage.extract");
         default -> throw new MatchException((String)null, (Throwable)null);
      }

      return var10000;
   }

   @FunctionalInterface
   public interface TaskBody<T> {
      T run(Consumer<InstallProgress> var1) throws Exception;
   }
}
