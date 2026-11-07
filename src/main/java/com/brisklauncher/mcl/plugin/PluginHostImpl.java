package com.brisklauncher.mcl.plugin;

import com.brisklauncher.mcl.MclContext;
import com.brisklauncher.mcl.auth.api.AuthProvider;
import com.brisklauncher.mcl.i18n.I18n;
import com.brisklauncher.mcl.net.Http;
import com.brisklauncher.mcl.plugin.api.PluginConfig;
import com.brisklauncher.mcl.plugin.api.PluginContext;
import com.brisklauncher.mcl.plugin.api.PluginHttp;
import com.brisklauncher.mcl.plugin.api.PluginLog;
import com.brisklauncher.mcl.plugin.api.PluginMessages;
import com.brisklauncher.mcl.plugin.api.PluginServices;
import com.brisklauncher.mcl.security.SafeLog;
import com.brisklauncher.mcl.update.UpdateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.stage.Window;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * {@link PluginContext} 的實現：把啟動器能力收窄成「可以給插件的那一份」。
 *
 * <p>每個插件一個實例，所以 {@code pluginId()} / {@code pluginDataDir()} / {@code messages()}
 * 都是這個插件自己的。啟動器的私密目錄（帳號、密鑰、日誌）不經過這裡。
 */
final class PluginHostImpl implements PluginContext {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final MclContext context;
    private final String pluginId;
    private final Path pluginDataDir;
    private final PluginHttpImpl http = new PluginHttpImpl();
    private final PluginMessagesImpl messages;
    private final PluginServices services;
    private final PluginConfig config;
    private final PluginLog log;
    private final BiConsumer<String, AuthProvider> authRegistrar;

    PluginHostImpl(MclContext context, String pluginId, Path pluginDataDir,
                   ClassLoader loader, List<String> locales,
                   PluginServicesRegistry services, BiConsumer<String, AuthProvider> authRegistrar) {
        this.context = context;
        this.pluginId = pluginId;
        this.pluginDataDir = pluginDataDir;
        this.messages = new PluginMessagesImpl(loader, locales);
        this.services = services.view(pluginId);
        this.config = new PluginConfigImpl(pluginDataDir.resolve("config.properties"));
        this.authRegistrar = authRegistrar;
        this.log = new PluginLog() {
            @Override
            public void info(String message) {
                SafeLog.info("[plugin:" + pluginId + "] " + message);
            }

            @Override
            public void warn(String message) {
                SafeLog.warn("[plugin:" + pluginId + "] " + message);
            }

            @Override
            public void error(String message, Throwable error) {
                String detail = error == null ? "" : " - " + error.getClass().getSimpleName()
                        + (error.getMessage() == null ? "" : ": " + error.getMessage());
                SafeLog.warn("[plugin:" + pluginId + "] " + message + detail);
            }
        };
    }

    @Override
    public String pluginId() {
        return pluginId;
    }

    @Override
    public Path dataDir() {
        return com.brisklauncher.mcl.AppPaths.dataDir();
    }

    @Override
    public Path pluginDataDir() {
        return pluginDataDir;
    }

    @Override
    public String launcherVersion() {
        return UpdateService.CURRENT_VERSION;
    }

    @Override
    public String locale() {
        return I18n.currentLocaleTag();
    }

    @Override
    public Window ownerWindow() {
        // 主視窗 = 目前顯示中、不是彈窗的那一個；取不到就給 null（對話框會自己找）
        for (Window window : Window.getWindows()) {
            if (window.isShowing() && window instanceof javafx.stage.Stage stage
                    && stage.getModality() == javafx.stage.Modality.NONE) {
                return window;
            }
        }
        return Window.getWindows().stream().filter(Window::isShowing).findFirst().orElse(null);
    }

    @Override
    public void openUrl(String url) {
        com.brisklauncher.mcl.ui.DesktopActions.openUrl(url);
    }

    @Override
    public void openFolder(Path path) {
        com.brisklauncher.mcl.ui.DesktopActions.openFolder(path);
    }

    @Override
    public byte[] httpGet(String url) throws Exception {
        return Http.getBytes(java.net.URI.create(url), TIMEOUT);
    }

    @Override
    public String httpGetText(String url) throws Exception {
        return Http.getString(java.net.URI.create(url), TIMEOUT);
    }

    @Override
    public PluginHttp http() {
        return http;
    }

    @Override
    public ObjectMapper json() {
        return Http.mapper();
    }

    @Override
    public PluginLog log() {
        return log;
    }

    @Override
    public PluginMessages messages() {
        return messages;
    }

    @Override
    public PluginServices services() {
        return services;
    }

    @Override
    public PluginConfig config() {
        return config;
    }

    @Override
    public Runnable addLocaleListener(Runnable listener) {
        return I18n.addListener(listener);
    }

    @Override
    public void runLater(Runnable action) {
        if (action == null) {
            return;
        }
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    @Override
    public void registerAuthProvider(AuthProvider provider) {
        if (provider != null) {
            authRegistrar.accept(pluginId, provider);
        }
    }

    @Override
    public ThemeInfo theme() {
        var theme = com.brisklauncher.mcl.setting.ThemePacks.current();
        return new ThemeInfo(theme.id(), theme.name(), theme.dark(), theme.accent(),
                theme.background());
    }

    @Override
    public List<InstanceInfo> instances() {
        List<InstanceInfo> result = new ArrayList<>();
        try {
            for (var root : context.gameRepository().roots()) {
                for (var instance : context.gameRepository().versions(root)) {
                    result.add(new InstanceInfo(instance.id(), instance.mcVersion(), instance.gameDir()));
                }
            }
        } catch (Exception ignored) {
            // 扫描失败就回空列表
        }
        return result;
    }

    @Override
    public Path selectedInstanceGameDir() {
        var instance = selectedInstance();
        return instance == null ? null : instance.gameDir();
    }

    @Override
    public String selectedInstanceLabel() {
        var instance = selectedInstance();
        return instance == null ? "" : instance.id();
    }

    /** 記住的上次選中實例：先看 ui-state 的大版本資料夾 + 實例 id，找不到就用第一個。 */
    private com.brisklauncher.mcl.game.GameInstance selectedInstance() {
        try {
            var repository = context.gameRepository();
            var roots = repository.roots();
            if (roots.isEmpty()) {
                return null;
            }
            String rememberedRoot = context.uiState().lastRoot();
            var root = roots.stream()
                    .filter(item -> item.path().toString().equals(rememberedRoot))
                    .findFirst().orElse(roots.get(0));
            var instances = repository.versions(root);
            if (instances.isEmpty()) {
                return null;
            }
            String rememberedId = context.uiState().lastVersion(root.path());
            return instances.stream()
                    .filter(item -> item.id().equals(rememberedId))
                    .findFirst().orElse(instances.get(0));
        } catch (Exception error) {
            return null;
        }
    }

    @Override
    public void info(String title, String message) {
        onFx(() -> com.brisklauncher.mcl.ui.MCLDialog.info(ownerWindow(), title, message));
    }

    @Override
    public void error(String title, String message) {
        onFx(() -> com.brisklauncher.mcl.ui.MCLDialog.error(ownerWindow(), title, message));
    }

    @Override
    public void confirm(String title, String message, Runnable onConfirm) {
        onFx(() -> com.brisklauncher.mcl.ui.MCLDialog.confirm(ownerWindow(), title, message,
                onConfirm == null ? () -> { } : onConfirm));
    }

    private void onFx(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }
}
