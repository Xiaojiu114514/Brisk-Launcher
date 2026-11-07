package com.brisklauncher.mcl.ui;

import com.brisklauncher.mcl.AppPaths;
import com.brisklauncher.mcl.security.SafeLog;
import com.brisklauncher.mcl.setting.ThemeColors;
import com.brisklauncher.mcl.setting.ThemePacks;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * 維護「JVM 原生啟動圖」{@code <安裝目錄>/splash.gif}。
 *
 * <p>問題：{@code -splash:} 的圖是 JVM 在 JavaFX 之前顯示的，那時讀不到主題；如果固定用一張
 * 中性圖，淺色主題的使用者就會先看到一下深色（使用者回報「先黑一下」）。
 *
 * <p>做法：把「目前主題的顏色」用 {@link NativeSplash} 畫成 GIF，覆蓋掉安裝目錄裡的 splash.gif；
 * 下次啟動（以及 jpackage 的 {@code $ROOTDIR/splash.gif}）就是主題色。
 * 換主題、換語言包、主題檔案變動都會讓簽名改變 → 下次啟動前自動重畫。
 *
 * <p>寫不進去（安裝目錄唯讀）就安靜跳過：內建的預設主題 splash 仍然可用，只是不跟主題。
 */
public final class NativeSplashService {

    private static final String SIGNATURE_FILE = "native-splash.txt";

    private NativeSplashService() {
    }

    /** 同步確保 splash.gif 與目前主題一致（背景執行緒用）。 */
    public static void ensureUpToDate() {
        try {
            Path target = target();
            if (target == null) {
                return;
            }
            Map<String, String> tokens = ThemeColors.current();
            String signature = signature(tokens);
            Path signatureFile = AppPaths.dataDir().resolve(SIGNATURE_FILE);
            String previous = Files.isRegularFile(signatureFile)
                    ? Files.readString(signatureFile, StandardCharsets.UTF_8).trim() : "";
            if (signature.equals(previous) && Files.isRegularFile(target)) {
                return;
            }
            NativeSplash.write(target, tokens);
            Files.createDirectories(signatureFile.getParent());
            Files.writeString(signatureFile, signature, StandardCharsets.UTF_8);
            SafeLog.info("[splash] 已按主題重畫原生啟動圖（" + ThemePacks.selectedId() + "）");
        } catch (Throwable error) {
            // 唯讀安裝目錄 / 沒有寫入權限都是可接受的：保留內建 splash
            SafeLog.warn("[splash] 產生主題啟動圖失敗：" + error.getClass().getSimpleName());
        }
    }

    /** 換主題後呼叫：丟到背景執行緒做，不要卡 UI。 */
    public static void ensureUpToDateAsync() {
        Thread.ofVirtual().name("mcl-native-splash").start(NativeSplashService::ensureUpToDate);
    }

    /** 安裝目錄的 splash.gif（launch.ps1 / VBS / jpackage 都看這個路徑）。 */
    private static Path target() {
        try {
            Path install = AppPaths.installDir();
            if (install == null) {
                return null;
            }
            return install.resolve("splash.gif");
        } catch (Throwable error) {
            return null;
        }
    }

    /** 簽名：主題 id + 所有用到的設計變數（排序後），主題一改就會變。 */
    private static String signature(Map<String, String> tokens) {
        StringBuilder builder = new StringBuilder(ThemePacks.selectedId());
        // 只納入影響 splash 的變數，避免無關的 token 變動也重畫
        for (String name : new String[]{"-brisk-surface", "-brisk-border", "-brisk-accent",
                "-brisk-text", "-brisk-text-muted", "-brisk-border-soft"}) {
            builder.append('|').append(name).append('=').append(tokens.getOrDefault(name, ""));
        }
        return Integer.toHexString(builder.toString().hashCode()) + "|" + builder;
    }

    /** 除錯用：目前簽名（測試會呼叫）。 */
    static String debugSignature() {
        return signature(new TreeMap<>(ThemeColors.current()));
    }
}
