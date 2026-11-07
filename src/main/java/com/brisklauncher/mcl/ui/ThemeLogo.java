package com.brisklauncher.mcl.ui;

import com.brisklauncher.mcl.security.SafeLog;
import com.brisklauncher.mcl.setting.ThemePacks;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 主題 logo / 視窗圖示的統一入口。
 *
 * <p>取圖優先序：
 * <ol>
 *   <li>主題包自帶的 {@code logo.png}（{@code theme.json} 的 {@code "logo"} 或檔名 {@code logo.*}）；</li>
 *   <li>啟動器內建的櫻花粉方塊 {@code /assets/logo/logo-N.png}（16…256 各一檔）。</li>
 * </ol>
 *
 * <p><b>系統圖示跟不跟主題？</b>能跟就跟：{@code Stage.getIcons()} 可以在執行期換，
 * 所以切主題時這裡會把工作列 / Alt-Tab 的圖示一起換掉。主題沒提供 logo 時用內建的那組
 * （多尺寸，工作列在 100% / 150% / 200% DPI 下都會挑到合適的）。
 *
 * <p>圖檔都只在本地讀，不連網；主題 logo 的路徑已經由 {@link ThemePacks} 做過目錄逃逸與大小檢查。
 */
public final class ThemeLogo {

    /** 頂欄顯示尺寸；實際載入 2 倍圖，HiDPI 才不會糊。 */
    public static final int DISPLAY_SIZE = 30;

    private static final int[] ICON_SIZES = {16, 24, 32, 48, 64, 128, 256};
    private static final String FALLBACK_PATTERN = "/assets/logo/logo-%d.png";

    private static volatile String displayKey;
    private static volatile Image displayImage;
    private static volatile String iconKey;
    private static volatile List<Image> iconImages;

    private ThemeLogo() {
    }

    /** 目前主題的 logo 檔（沒有就 null）。 */
    private static Path themeLogo() {
        try {
            ThemePacks.Theme theme = ThemePacks.current();
            Path logo = theme == null ? null : theme.logo();
            return logo != null && java.nio.file.Files.isRegularFile(logo) ? logo : null;
        } catch (Throwable error) {
            return null;
        }
    }

    /** 頂欄用小圖；永遠不會回 null（最差也會是內建圖，再不行回 null 由呼叫端隱藏）。 */
    public static Image display() {
        Path logo = themeLogo();
        String key = (logo == null ? "bundled" : logo.toString()) + '|' + DISPLAY_SIZE;
        Image cached = displayImage;
        if (cached != null && key.equals(displayKey)) {
            return cached;
        }
        int request = DISPLAY_SIZE * 2;
        Image image = null;
        if (logo != null) {
            image = loadFile(logo, request);
        }
        if (image == null) {
            image = loadResource(String.format(FALLBACK_PATTERN, 64), request);
        }
        displayKey = key;
        displayImage = image;
        return image;
    }

    /** 視窗 / 工作列圖示（依尺寸由小到大）。 */
    public static List<Image> windowIcons() {
        Path logo = themeLogo();
        String key = logo == null ? "bundled" : logo.toString();
        List<Image> cached = iconImages;
        if (cached != null && key.equals(iconKey)) {
            return cached;
        }
        List<Image> icons = new ArrayList<>();
        if (logo != null) {
            Image image = loadFile(logo, 0);
            if (image != null) {
                icons.add(image);
            }
        }
        if (icons.isEmpty()) {
            // 內建的多尺寸組；Windows 會自己挑最合適的一張
            for (int size : ICON_SIZES) {
                Image image = loadResource(String.format(FALLBACK_PATTERN, size), 0);
                if (image != null) {
                    icons.add(image);
                }
            }
        }
        icons = List.copyOf(icons);
        iconKey = key;
        iconImages = icons;
        return icons;
    }

    /** 把目前主題的圖示套到視窗（只有 {@link Stage} 有圖示可換）。 */
    public static void applyWindowIcons(Window window) {
        if (!(window instanceof Stage stage)) {
            return;
        }
        Runnable task = () -> {
            try {
                List<Image> icons = windowIcons();
                if (!icons.isEmpty()) {
                    stage.getIcons().setAll(icons);
                }
            } catch (Throwable error) {
                SafeLog.warn("设置窗口图标失败：" + error.getClass().getSimpleName());
            }
        };
        if (Platform.isFxApplicationThread()) {
            task.run();
        } else {
            Platform.runLater(task);
        }
    }

    /** 丟掉快取（主題切換後由 {@link #applyWindowIcons} 的下一次呼叫自然重建）。 */
    public static void invalidate() {
        displayKey = null;
        displayImage = null;
        iconKey = null;
        iconImages = null;
    }

    private static Image loadFile(Path path, int request) {
        try {
            return request > 0
                    ? new Image(path.toUri().toString(), request, request, true, true, false)
                    : new Image(path.toUri().toString(), 0, 0, true, true, false);
        } catch (Throwable error) {
            return null;
        }
    }

    private static Image loadResource(String resource, int request) {
        try (var input = ThemeLogo.class.getResourceAsStream(resource)) {
            if (input == null) {
                return null;
            }
            return request > 0
                    ? new Image(input, request, request, true, true)
                    : new Image(input);
        } catch (Throwable error) {
            return null;
        }
    }
}
