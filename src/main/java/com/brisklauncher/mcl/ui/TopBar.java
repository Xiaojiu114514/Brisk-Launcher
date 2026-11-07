package com.brisklauncher.mcl.ui;

import com.brisklauncher.mcl.MclContext;
import com.brisklauncher.mcl.auth.AccountStore;
import com.brisklauncher.mcl.i18n.I18n;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.event.EventTarget;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 顶部标题栏：导航、账号快捷切换、运行中入口、窗口按钮、窗口拖拽。
 * 房间内会禁用账号切换（开发方案 4.9.1）。
 */
public final class TopBar extends HBox {

    public static final List<String> NAV_KEYS = List.of(
            "launch", "download", "version", "online", "account", "tools", "settings");

    /** 插件贡献的导航项（key 用 {@code PluginManager.pageKey}）。 */
    public record NavItem(String key, String label) {
    }

    private final Stage stage;
    private final MclContext context;
    private final Consumer<String> onNavigate;
    private final MenuButton accountMenu;
    private final Button runningButton;
    private final ToggleGroup navGroup = new ToggleGroup();

    private double dragX;
    private double dragY;
    private Runnable onMinimizeRequested;
    private Runnable onCloseRequested;
    private javafx.animation.Timeline poller;
    private Runnable onlineStateUnsubscribe;
    private Runnable accountUnsubscribe;
    private Runnable themeUnsubscribe;
    private boolean disposed;

    public TopBar(Stage stage, String initialKey, Consumer<String> onNavigate, MclContext context) {
        this(stage, initialKey, onNavigate, context, List.of());
    }

    /**
     * @param extraNav 插件贡献的导航页，插在「工具」与「设置」之间
     */
    public TopBar(Stage stage, String initialKey, Consumer<String> onNavigate, MclContext context,
                  List<NavItem> extraNav) {
        this.stage = stage;
        this.context = context;
        this.onNavigate = onNavigate;

        getStyleClass().add("top-bar");
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(8, 8, 8, 14));
        setSpacing(14);

        // 品牌區：主題 logo（圖片）+ 字標（可用 theme.json 的 wordmark 關掉）+ 啟動器名稱小字（一定保留）
        Label wordmark = new Label("Brisk");
        wordmark.getStyleClass().add("logo");
        Label sub = new Label(I18n.t("topbar.brand"));
        sub.getStyleClass().add("logo-sub");

        javafx.scene.image.ImageView brandIcon = new javafx.scene.image.ImageView();
        brandIcon.setFitWidth(ThemeLogo.DISPLAY_SIZE);
        brandIcon.setFitHeight(ThemeLogo.DISPLAY_SIZE);
        brandIcon.setPreserveRatio(true);
        brandIcon.setSmooth(true);
        updateBrandIcon(brandIcon);
        updateBrandWordmark(wordmark);

        HBox brand = new HBox(7, brandIcon, wordmark, sub);
        brand.setAlignment(Pos.CENTER_LEFT);

        // 切主題時換 logo 圖 + 字標開關（系統圖示由 UiKit.applyTheme → ThemeLogo 一起換）
        themeUnsubscribe = com.brisklauncher.mcl.setting.ThemePacks.addThemeListener(
                () -> Platform.runLater(() -> {
                    ThemeLogo.invalidate();
                    updateBrandIcon(brandIcon);
                    updateBrandWordmark(wordmark);
                }));

        navGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle == null && oldToggle != null) {
                Platform.runLater(() -> oldToggle.setSelected(true));
            }
        });

        HBox nav = new HBox(4);
        nav.setAlignment(Pos.CENTER_LEFT);
        List<NavItem> items = new ArrayList<>();
        for (String key : NAV_KEYS) {
            if ("settings".equals(key) && extraNav != null) {
                items.addAll(extraNav);
            }
            items.add(new NavItem(key, I18n.t("nav." + key)));
        }
        if (extraNav != null && !NAV_KEYS.contains("settings")) {
            items.addAll(extraNav);
        }
        for (NavItem item : items) {
            ToggleButton button = new ToggleButton(item.label());
            button.setToggleGroup(navGroup);
            button.getStyleClass().add("nav-button");
            button.setUserData(item.key());
            button.setOnAction(e -> onNavigate.accept(item.key()));
            if (item.key().equals(initialKey)) {
                button.setSelected(true);
            }
            nav.getChildren().add(button);
        }

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        runningButton = new Button();
        runningButton.getStyleClass().add("running-chip");
        runningButton.setOnAction(e -> RunningDialog.show(stage.getScene().getWindow(),
                context.gameLauncher().runningGames()));
        runningButton.setVisible(false);
        runningButton.setManaged(false);

        accountMenu = new MenuButton();
        accountMenu.getStyleClass().add("account-switch-btn");
        accountMenu.setOnShowing(e -> refreshAccountMenu());
        refreshAccountMenu();

        Button minimize = new Button("—");
        minimize.getStyleClass().add("window-button");
        minimize.setOnAction(e -> {
            if (onMinimizeRequested != null) {
                onMinimizeRequested.run();
            } else {
                stage.setIconified(true);
            }
        });

        Button close = new Button("✕");
        close.getStyleClass().addAll("window-button", "window-close");
        close.setOnAction(e -> {
            if (onCloseRequested != null) {
                onCloseRequested.run();
            } else {
                stage.close();
            }
        });

        HBox windowButtons = new HBox(4, minimize, close);
        windowButtons.setAlignment(Pos.CENTER_RIGHT);

        getChildren().addAll(brand, nav, spacer, runningButton, accountMenu, windowButtons);
        installDrag();

        onlineStateUnsubscribe = context.onlineState()
                .addListener(phase -> Platform.runLater(this::refreshAccountMenu));
        accountUnsubscribe = context.accounts()
                .addChangeListener(() -> Platform.runLater(this::refreshAccountMenu));
        startPolling();
    }

    /**
     * 顶栏被丢弃（语言切换重建 / 视窗关闭）时呼叫。
     *
     * <p>不呼叫的话：每秒 2 次的轮询会一直跑，而 OnlineState 的监听器会强引用旧顶栏，
     * 造成每次换语言都留下一整套旧 UI。
     */
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        if (poller != null) {
            poller.stop();
            poller = null;
        }
        if (onlineStateUnsubscribe != null) {
            onlineStateUnsubscribe.run();
            onlineStateUnsubscribe = null;
        }
        if (accountUnsubscribe != null) {
            accountUnsubscribe.run();
            accountUnsubscribe = null;
        }
        if (themeUnsubscribe != null) {
            themeUnsubscribe.run();
            themeUnsubscribe = null;
        }
        navGroup.getToggles().clear();
        accountMenu.getItems().clear();
        getChildren().clear();
    }

    // ---------- 账号 ----------

    private void refreshAccountMenu() {
        accountMenu.getItems().clear();
        boolean locked = context.onlineState().locked();
        accountMenu.setDisable(locked);
        if (locked) {
            accountMenu.setTooltip(new Tooltip(I18n.t("online.p2p.lock_hint")));
        } else {
            accountMenu.setTooltip(null);
        }

        List<AccountStore.Account> all = context.accounts().listAll();
        String currentUuid = context.accounts().currentUuid();

        AccountStore.Account current = all.stream()
                .filter(account -> account.uuid().equals(currentUuid))
                .findFirst().orElse(null);
        String text = current == null ? I18n.t("account.no_current") : current.name();
        accountMenu.setText((locked ? "🔒 " : "") + text);

        if (all.isEmpty()) {
            MenuItem empty = new MenuItem(I18n.t("account.no_current"));
            empty.setDisable(true);
            accountMenu.getItems().add(empty);
        } else {
            for (com.brisklauncher.mcl.auth.api.AccountType type : com.brisklauncher.mcl.auth.api.AccountType.values()) {
                List<AccountStore.Account> group = all.stream()
                        .filter(account -> account.type() == type).toList();
                if (group.isEmpty()) {
                    continue;
                }
                MenuItem header = new MenuItem(I18n.t("account.tab." + type.name().toLowerCase(java.util.Locale.ROOT)));
                header.setDisable(true);
                if (!accountMenu.getItems().isEmpty()) {
                    accountMenu.getItems().add(new SeparatorMenuItem());
                }
                accountMenu.getItems().add(header);
                for (AccountStore.Account account : group) {
                    String prefix = account.uuid().equals(currentUuid) ? "✔ " : "    ";
                    MenuItem item = new MenuItem(prefix + account.name());
                    item.setOnAction(e -> {
                        context.accounts().setCurrent(account.uuid());
                        refreshAccountMenu();
                    });
                    accountMenu.getItems().add(item);
                }
            }
        }

        accountMenu.getItems().add(new SeparatorMenuItem());
        MenuItem manage = new MenuItem(I18n.t("account.manage"));
        manage.setOnAction(e -> onNavigate.accept("account"));
        accountMenu.getItems().add(manage);
    }

    // ---------- 运行中 ----------

    private void startPolling() {
        poller = new Timeline(new KeyFrame(Duration.seconds(2), e -> refreshRunning()));
        poller.setCycleCount(Animation.INDEFINITE);
        poller.play();
        refreshRunning();
    }

    private void refreshRunning() {
        int count = context.gameLauncher().runningGames().count();
        runningButton.setVisible(count > 0);
        runningButton.setManaged(count > 0);
        if (count > 0) {
            runningButton.setText(I18n.t("running.badge", count));
        }
    }

    // ---------- 导航 / 窗口 ----------

    public void select(String key) {
        for (var toggle : navGroup.getToggles()) {
            if (key.equals(toggle.getUserData())) {
                toggle.setSelected(true);
                return;
            }
        }
    }

    public void setOnMinimizeRequested(Runnable action) {
        this.onMinimizeRequested = action;
    }

    public void setOnCloseRequested(Runnable action) {
        this.onCloseRequested = action;
    }

    public void refreshAccount() {
        refreshAccountMenu();
    }

    // ---------- 品牌 ----------

    /** 換上目前主題的 logo（取不到圖就整張隱藏，不要留一個空白佔位）。 */
    private static void updateBrandIcon(javafx.scene.image.ImageView view) {
        javafx.scene.image.Image image = ThemeLogo.display();
        boolean available = image != null && !image.isError();
        view.setImage(available ? image : null);
        view.setVisible(available);
        view.setManaged(available);
    }

    /** 主題可以用 {@code "wordmark": false} 只留小字；內建主題一律顯示。 */
    private static void updateBrandWordmark(Label wordmark) {
        boolean show;
        try {
            show = com.brisklauncher.mcl.setting.ThemePacks.current().wordmark();
        } catch (Throwable error) {
            show = true;
        }
        wordmark.setVisible(show);
        wordmark.setManaged(show);
    }

    private void installDrag() {
        setOnMousePressed(event -> {
            if (isInteractiveTarget(event.getTarget())) {
                return;
            }
            dragX = event.getSceneX();
            dragY = event.getSceneY();
        });
        setOnMouseDragged(event -> {
            if (isInteractiveTarget(event.getTarget())) {
                return;
            }
            stage.setX(event.getScreenX() - dragX);
            stage.setY(event.getScreenY() - dragY);
        });
    }

    private static boolean isInteractiveTarget(EventTarget target) {
        Node node = target instanceof Node n ? n : null;
        while (node != null) {
            if (node instanceof ButtonBase || node instanceof MenuButton) {
                return true;
            }
            node = node.getParent();
        }
        return false;
    }
}
