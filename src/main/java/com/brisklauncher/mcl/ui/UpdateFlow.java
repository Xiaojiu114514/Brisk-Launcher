package com.brisklauncher.mcl.ui;

import com.brisklauncher.mcl.MclContext;
import com.brisklauncher.mcl.i18n.I18n;
import com.brisklauncher.mcl.update.UpdateInstaller;
import com.brisklauncher.mcl.update.UpdateService;
import javafx.application.Platform;
import javafx.stage.Window;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 自动覆盖更新的 UI 流程：提示 → 下载/解压进度 → 套用（关闭启动器并重新开启）。
 *
 * <p>两种入口：
 * <ul>
 *   <li>启动时自动更新（设定页「自动下载并覆盖更新」开启）：不询问，直接跑进度框；</li>
 *   <li>手动「立即检查更新」：先确认，再跑同一套流程。</li>
 * </ul>
 * 开发模式或安装目录不可写时退回「开启下载页」，不会假装更新成功。
 */
public final class UpdateFlow {

    private UpdateFlow() {
    }

    /**
     * @param userInitiated true = 使用者主动按「检查更新」；false = 启动时自动检查
     */
    public static void offer(Window owner, MclContext context, UpdateService.Release release,
                             boolean userInitiated) {
        if (release == null) {
            return;
        }
        String version = release.version();
        if (!UpdateInstaller.supported()) {
            // 开发执行 / 安装目录不可写：不能覆盖自己，只能请玩家开下载页
            if (userInitiated) {
                MCLDialog.confirm(owner, I18n.t("update.available_title"),
                        I18n.t("update.unsupported_body", version, notes(release)),
                        () -> DesktopActions.openUrl(release.pageUrl()));
            } else {
                com.brisklauncher.mcl.security.SafeLog.info(
                        "[update] 目前执行目录不支援自动覆盖更新，略过启动时更新");
            }
            return;
        }
        if (UpdateInstaller.isKnownFailure(version)) {
            if (userInitiated) {
                MCLDialog.confirm(owner, I18n.t("update.available_title"),
                        I18n.t("update.retry_body", version),
                        () -> startInstall(owner, context, release));
            } else {
                com.brisklauncher.mcl.security.SafeLog.warn(
                        "[update] " + version + " 上次自动更新失败，这次先略过（可在设定页手动重试）");
            }
            return;
        }
        if (!userInitiated) {
            startInstall(owner, context, release);
            return;
        }
        MCLDialog.confirm(owner, I18n.t("update.available_title"),
                I18n.t("update.install_body", version,
                        UpdateService.CURRENT_VERSION, notes(release)),
                () -> startInstall(owner, context, release));
    }

    /** 下载 + 解压（进度框），完成后写出覆盖脚本并结束启动器。 */
    private static void startInstall(Window owner, MclContext context, UpdateService.Release release) {
        AtomicReference<UpdateInstaller.Staged> staged = new AtomicReference<>();
        TaskDialog.runVoid(owner, I18n.t("update.installing_title", release.version()),
                progress -> {
                    staged.set(UpdateInstaller.stage(release, context.downloader(), progress));
                    return null;
                },
                () -> {
                    try {
                        UpdateInstaller.applyAndRestart(staged.get());
                    } catch (Exception error) {
                        String message = error.getMessage() == null
                                ? error.getClass().getSimpleName() : error.getMessage();
                        MCLDialog.error(owner, I18n.t("update.section"),
                                I18n.t("update.apply_failed", release.version(), message));
                        return;
                    }
                    // 脚本会等这个行程结束才开始覆盖，所以这里直接关闭
                    Platform.exit();
                });
    }

    /** release notes 太长会把弹窗撑爆，截断成固定长度。 */
    private static String notes(UpdateService.Release release) {
        String notes = release.notes() == null ? "" : release.notes().trim();
        if (notes.length() > 400) {
            notes = notes.substring(0, 400) + "…";
        }
        return notes;
    }
}
