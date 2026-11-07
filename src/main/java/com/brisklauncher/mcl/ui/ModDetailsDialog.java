package com.brisklauncher.mcl.ui;

import com.brisklauncher.mcl.download.remote.ModrinthClient;
import com.brisklauncher.mcl.game.mod.ModManager;
import com.brisklauncher.mcl.i18n.I18n;
import com.brisklauncher.mcl.net.Http;
import com.brisklauncher.mcl.security.SafeLog;
import com.brisklauncher.mcl.util.RemoteImages;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 「详情」弹窗：列出某个 Modrinth 项目在当前实例版本/加载器下的所有版本，
 * 可以直接安装/更新到实例目录。
 */
public final class ModDetailsDialog {

    private ModDetailsDialog() {
    }

    /** 只暴露弹窗需要的最小上下文，避免把整个 MclContext 塞进 UI 类。 */
    public interface MclContextPeek {
        ModrinthClient modrinth();

        String loaderKey();

        String mcVersion();
    }

    public static void show(Window owner, MclContextPeek context, String projectId,
                            Path targetDir, ModManager.Kind kind, Runnable onInstalled) {
        if (projectId == null || projectId.isBlank()) {
            MCLDialog.info(owner, I18n.t("content.details"), I18n.t("content.not_from_modrinth"));
            return;
        }
        ModrinthClient client = context.modrinth();
        String loader = context.loaderKey();
        String mcVersion = context.mcVersion();

        Stage dialog = new Stage();
        dialog.initOwner(owner);
        dialog.initModality(Modality.NONE);
        dialog.initStyle(StageStyle.TRANSPARENT);

        Label titleLabel = new Label("✦ " + I18n.t("content.details"));
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: -brisk-text;");

        Label projectLabel = new Label(projectId);
        projectLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        Label description = new Label();
        description.setWrapText(true);
        description.getStyleClass().add("cat-hint");
        description.setMaxWidth(560);

        ImageView icon = new ImageView();
        icon.setFitWidth(48);
        icon.setFitHeight(48);
        icon.setPreserveRatio(true);

        Label status = new Label(I18n.t("content.loading"));
        status.getStyleClass().add("cat-hint");

        ListView<ModrinthClient.VersionFile> versions = new ListView<>();
        versions.setPrefHeight(300);
        versions.setPrefWidth(600);
        versions.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(ModrinthClient.VersionFile file, boolean empty) {
                super.updateItem(file, empty);
                if (empty || file == null) {
                    setText(null);
                    return;
                }
                String date = file.datePublished();
                if (date.length() > 10) {
                    date = date.substring(0, 10);
                }
                setText(file.displayName() + "   ·   " + date + "   ·   " + file.fileName());
            }
        });

        Button cancel = new Button(I18n.t("common.cancel"));
        cancel.getStyleClass().add("button");
        cancel.setOnAction(e -> dialog.close());

        Button install = new Button(I18n.t("content.install_selected"));
        install.getStyleClass().add("primary-button");
        install.setDisable(true);
        versions.getSelectionModel().selectedItemProperty().addListener(
                (obs, was, now) -> install.setDisable(now == null));

        install.setOnAction(e -> {
            ModrinthClient.VersionFile file = versions.getSelectionModel().getSelectedItem();
            if (file == null) {
                return;
            }
            install.setDisable(true);
            status.setText(I18n.t("content.downloading", file.fileName()));
            CompletableFuture.runAsync(() -> {
                try {
                    byte[] bytes = Http.getBytes(java.net.URI.create(file.downloadUrl()),
                            Duration.ofMinutes(2));
                    Files.createDirectories(targetDir);
                    Path target = targetDir.resolve(safeName(file.fileName()));
                    Path temp = target.resolveSibling(target.getFileName() + ".part");
                    Files.write(temp, bytes);
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                    Platform.runLater(() -> {
                        status.setText(I18n.t("content.install_done", file.fileName()));
                        if (onInstalled != null) {
                            onInstalled.run();
                        }
                    });
                } catch (Exception error) {
                    SafeLog.warn("安装内容失败：" + error.getClass().getSimpleName());
                    Platform.runLater(() -> {
                        status.setText(I18n.t("content.install_failed")
                                + error.getClass().getSimpleName());
                        install.setDisable(false);
                    });
                }
            });
        });

        Button closeButton = com.brisklauncher.mcl.ui.UiKit.closeButtonStyled();
        closeButton.setOnAction(e -> dialog.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, titleLabel, spacer, closeButton);
        header.setAlignment(Pos.CENTER_LEFT);

        HBox projectRow = new HBox(10, icon, new VBox(2, projectLabel, description));
        projectRow.setAlignment(Pos.CENTER_LEFT);

        Region buttonSpacer = new Region();
        HBox.setHgrow(buttonSpacer, Priority.ALWAYS);
        HBox buttons = new HBox(10, status, buttonSpacer, cancel, install);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(12, header, projectRow, versions, buttons);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: -brisk-bg; -fx-background-radius: 12;"
                + " -fx-border-color: -brisk-border; -fx-border-radius: 12;"
                + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 15, 0, 0, 5);");
        Scene scene = new Scene(root);
        UiKit.applyTheme(scene);
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        dialog.setScene(scene);
        UiKit.escToClose(scene, dialog);
        dialog.sizeToScene();

        CompletableFuture.runAsync(() -> {
            List<ModrinthClient.VersionFile> loaded;
            try {
                loaded = client.versions(projectId,
                        loader == null || loader.isBlank() ? List.of() : List.of(loader),
                        mcVersion == null || mcVersion.isBlank() ? List.of() : List.of(mcVersion));
                if (loaded.isEmpty()) {
                    loaded = client.versions(projectId, List.of(), List.of());
                }
            } catch (Exception error) {
                loaded = List.of();
            }
            String iconUrl = null;
            String desc = null;
            String title = null;
            try {
                List<ModrinthClient.Project> projects = client.projects(List.of(projectId));
                if (!projects.isEmpty()) {
                    iconUrl = projects.get(0).iconUrl();
                    title = projects.get(0).title();
                    desc = projects.get(0).description();
                }
            } catch (Exception ignored) {
                // 项目信息可选
            }
            List<ModrinthClient.VersionFile> resolvedFiles = loaded;
            String resolvedIcon = iconUrl;
            String resolvedDesc = desc;
            String resolvedTitle = title;
            Platform.runLater(() -> {
                versions.getItems().setAll(resolvedFiles);
                if (!resolvedFiles.isEmpty()) {
                    versions.getSelectionModel().selectFirst();
                }
                status.setText(resolvedFiles.isEmpty() ? I18n.t("content.no_versions")
                        : I18n.t("content.version_count", resolvedFiles.size()));
                if (resolvedTitle != null) {
                    projectLabel.setText(resolvedTitle);
                }
                if (resolvedDesc != null) {
                    description.setText(resolvedDesc);
                }
                if (resolvedIcon != null) {
                    RemoteImages.load(resolvedIcon, 48, 48, icon::setImage);
                }
            });
        });

        UiKit.makeDraggable(dialog, header);
        UiKit.popIn(dialog, root);
        dialog.show();
    }

    private static String safeName(String name) {
        String value = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|]", "_");
        return value.isBlank() ? "download.bin" : value;
    }
}
