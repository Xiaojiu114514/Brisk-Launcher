package com.brisklauncher.mcl.ui;

import com.brisklauncher.mcl.i18n.I18n;
import com.brisklauncher.mcl.security.Acks;
import com.brisklauncher.mcl.security.SafeLog;
import com.brisklauncher.mcl.setting.LauncherSettings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.util.function.Consumer;

/**
 * 首次启动的「网路功能说明」。
 *
 * <p>安全审计 v2 A-05：启动器预设会向 GitHub 拉崩溃规则、检查更新、对镜像测速，
 * 但以前从来没有告诉过玩家。这不是漏洞，是「预设行为要让使用者看见」的问题。
 *
 * <p>外观沿用 {@link MCLDialog} 的主题（同一个底色、边框、圆角与 `primary-button`），
 * 避免出现第二种弹窗风格。
 */
public final class NetworkNoticeDialog {

    private NetworkNoticeDialog() {
    }

    /**
     * 显示说明（只显示一次，确认后写入 acks）。
     *
     * @param owner    主视窗
     * @param current  目前设定（用来决定勾选状态）
     * @param onSave   按下「了解并继续」时回传要套用的设定（已包含使用者的勾选）
     */
    public static void showOnce(Window owner, LauncherSettings current,
                                Consumer<LauncherSettings> onSave) {
        if (Acks.has("networkNotice")) {
            return;
        }
        show(owner, current, onSave);
    }

    /** 開發期預覽用：忽略「已確認」記錄，直接顯示（`-Dmcl.dev.networknotice=1`）。 */
    public static void showForced(Window owner, LauncherSettings current,
                                  Consumer<LauncherSettings> onSave) {
        show(owner, current, onSave);
    }

    private static void show(Window owner, LauncherSettings current,
                             Consumer<LauncherSettings> onSave) {
        Stage dialog = new Stage();
        dialog.initOwner(owner);
        dialog.initModality(Modality.NONE);
        dialog.initStyle(StageStyle.TRANSPARENT);

        Label title = new Label("🌐 " + I18n.t("network.notice.title"));
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #14453F;");

        Label intro = new Label(I18n.t("network.notice.intro"));
        intro.setWrapText(true);
        intro.setMaxWidth(560);
        intro.setStyle("-fx-text-fill: #4A6A66; -fx-font-size: 13px;");

        CheckBox crashRules = new CheckBox(I18n.t("settings.crash.remote_update"));
        crashRules.setSelected(current.crashRuleRemoteUpdate());
        CheckBox autoUpdate = new CheckBox(I18n.t("update.auto"));
        autoUpdate.setSelected(current.autoUpdate());

        Label crashDesc = new Label(I18n.t("network.notice.crash_rules_desc"));
        crashDesc.setWrapText(true);
        crashDesc.setMaxWidth(520);
        crashDesc.setStyle("-fx-text-fill: #6C8A85; -fx-font-size: 12px;");
        Label updateDesc = new Label(I18n.t("network.notice.update_desc"));
        updateDesc.setWrapText(true);
        updateDesc.setMaxWidth(520);
        updateDesc.setStyle("-fx-text-fill: #6C8A85; -fx-font-size: 12px;");

        Label speedDesc = new Label(I18n.t("network.notice.speedtest_desc"));
        speedDesc.setWrapText(true);
        speedDesc.setMaxWidth(520);
        speedDesc.setStyle("-fx-text-fill: #6C8A85; -fx-font-size: 12px;");

        Label privacy = new Label(I18n.t("network.notice.privacy"));
        privacy.setWrapText(true);
        privacy.setMaxWidth(560);
        privacy.setStyle("-fx-text-fill: #14453F; -fx-font-size: 12.5px;"
                + " -fx-background-color: #E8FAF6; -fx-background-radius: 8;"
                + " -fx-padding: 10 12 10 12;");

        VBox crashBox = new VBox(4, crashRules, crashDesc);
        VBox updateBox = new VBox(4, autoUpdate, updateDesc);
        VBox speedBox = new VBox(4, speedDesc);

        Button later = new Button(I18n.t("network.notice.later"));
        later.setOnAction(e -> {
            // 「稍后」= 保持现状，但不再重复弹出（避免每次启动都打扰）
            Acks.record("networkNotice");
            dialog.close();
        });

        Button ok = new Button(I18n.t("network.notice.ok"));
        ok.getStyleClass().add("primary-button");
        ok.setOnAction(e -> {
            Acks.record("networkNotice");
            // 只改这两个开关，其余设定原样保留（用 record 的复制方法，避免漏栏位）
            LauncherSettings updated = current
                    .withCrashRuleRemoteUpdate(crashRules.isSelected())
                    .withAutoUpdate(autoUpdate.isSelected());
            dialog.close();
            try {
                onSave.accept(updated);
            } catch (Throwable error) {
                SafeLog.warn("保存网络设置失败：" + error.getClass().getSimpleName());
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(10, spacer, later, ok);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(14, title, intro, crashBox, updateBox, speedBox, privacy, buttons);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: #F4FCFA; -fx-background-radius: 12;"
                + " -fx-border-color: #BFE9E2; -fx-border-radius: 12;"
                + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 15, 0, 0, 5);");
        root.setMaxWidth(600);

        UiKit.makeDraggable(dialog, title);
        Scene scene = new Scene(root);
        UiKit.applyTheme(scene);
        UiKit.escToClose(scene, dialog);
        dialog.setScene(scene);
        dialog.setOnShown(e -> UiKit.popIn(dialog, root));
        dialog.show();
    }
}
