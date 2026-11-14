package com.brisklauncher.mcl.ui;

import com.brisklauncher.mcl.MclContext;
import com.brisklauncher.mcl.auth.api.AccountType;
import com.brisklauncher.mcl.auth.api.AuthProvider;
import com.brisklauncher.mcl.auth.api.LoginCredentials;
import com.brisklauncher.mcl.auth.api.LoginResult;
import com.brisklauncher.mcl.auth.api.StoredSession;
import com.brisklauncher.mcl.auth.impl.AuthProviderRegistry;
import com.brisklauncher.mcl.auth.impl.MicrosoftAuthProvider;
import com.brisklauncher.mcl.auth.impl.OfflineAuthProvider;
import com.brisklauncher.mcl.auth.impl.YggdrasilAuthProvider;
import com.brisklauncher.mcl.auth.AccountStore;
import com.brisklauncher.mcl.i18n.I18n;
import com.brisklauncher.mcl.security.SafeLog;
import com.brisklauncher.mcl.security.SessionTokenStore;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.List;
import java.util.Optional;

/**
 * 账户页：正版（浏览器回呼）/ 离线 / 第三方（Yggdrasil）三个标签。
 *
 * <p>安全：密码只在请求体内流转，用完立即清零；令牌只进 Windows 凭据管理器；
 * 日志只记录“尝试 / 成功 / 刷新”三种事件（SafeLog 强制）。
 */
public final class AccountPage extends VBox implements MclPage {

    private final MclContext context;
    private final YggdrasilAuthProvider yggdrasil = new YggdrasilAuthProvider();
    private final OfflineAuthProvider offline = new OfflineAuthProvider();

    private final StackPane content = new StackPane();
    // 账号名可能带下划线（xiaojiu_114514）：Label 默认不解析助记符，显式关掉以防基类换成 Button（第 190 轮）
    private final Label currentLabel = UiKit.noMnemonic(new Label());
    private Runnable currentPaneBuilder;
    private Runnable accountUnsubscribe;

    public AccountPage(MclContext context) {
        this.context = context;
        getStyleClass().add("page");
        setPadding(new Insets(18));
        setSpacing(12);

        // 帐号在顶栏或其它页面被切换时，这里要跟著变（反之亦然）
        accountUnsubscribe = context.accounts().addChangeListener(() ->
                Platform.runLater(this::onAccountChanged));

        ToggleGroup group = new ToggleGroup();
        group.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle == null && oldToggle != null) {
                Platform.runLater(() -> oldToggle.setSelected(true));
            }
        });

        microsoftTab = pill("account.tab.microsoft", group, this::showMicrosoft);
        microsoftTab.setSelected(true);
        offlineTab = pill("account.tab.offline", group, this::showOffline);
        thirdPartyTab = pill("account.tab.third_party", group, this::showThirdParty);

        HBox tabs = new HBox(6, microsoftTab, offlineTab, thirdPartyTab);
        tabs.setAlignment(Pos.CENTER_LEFT);

        updateCurrentLabel();
        currentLabel.getStyleClass().add("instance-meta");

        content.getChildren().add(buildMicrosoftPane());
        currentPaneBuilder = this::showMicrosoft;
        VBox.setVgrow(content, Priority.ALWAYS);

        String securityHint = I18n.t("account.security_hint");
        if (!context.accounts().isTokenPersistent()) {
            securityHint = securityHint + "\n⚠ " + I18n.t("account.token_not_persistent");
        }
        Label hintLabel = UiKit.catHint(securityHint);
        hintLabel.setWrapText(true);

        getChildren().addAll(
                UiKit.title(I18n.t("account.title") + " ✦"),
                currentLabel, tabs, content,
                hintLabel);

        // 开发期：直接切到某个分页（截图回归用）——offline / third
        String devTab = System.getProperty("mcl.dev.accounttab");
        if (devTab != null && !devTab.isBlank()) {
            Platform.runLater(() -> {
                if (devTab.trim().equalsIgnoreCase("offline")) {
                    tabSelect(AccountType.OFFLINE);
                } else if (devTab.trim().equalsIgnoreCase("third")) {
                    tabSelect(AccountType.THIRD_PARTY);
                }
            });
        }
    }

    @Override
    public void onShown() {
        if (System.getProperty("mcl.dev.logindialog") != null) {
            Platform.runLater(this::startMicrosoftLogin);
        }
        updateCurrentLabel();
        if (currentPaneBuilder != null) {
            currentPaneBuilder.run();
        }
    }

    /** 帳號被新增/切換/刪除後重建目前分頁，讓「當前帳號」標記立刻同步。 */
    private void onAccountChanged() {
        updateCurrentLabel();
        if (currentPaneBuilder != null) {
            currentPaneBuilder.run();
        }
    }

    @Override
    public void onReleased() {
        if (accountUnsubscribe != null) {
            accountUnsubscribe.run();
            accountUnsubscribe = null;
        }
        currentPaneBuilder = null;
        content.getChildren().clear();
    }

    // ---------- 正版 ----------

    private ToggleButton microsoftTab;
    private ToggleButton offlineTab;
    private ToggleButton thirdPartyTab;

    /** 切到某個帳號分頁（閘門上的「去登錄正版賬號」用）。 */
    private void tabSelect(AccountType type) {
        switch (type) {
            case MICROSOFT -> {
                if (microsoftTab != null) {
                    microsoftTab.setSelected(true);
                }
                showMicrosoft();
            }
            case OFFLINE -> {
                if (offlineTab != null) {
                    offlineTab.setSelected(true);
                }
                showOffline();
            }
            case THIRD_PARTY -> {
                if (thirdPartyTab != null) {
                    thirdPartyTab.setSelected(true);
                }
                showThirdParty();
            }
        }
    }

    private void showMicrosoft() {
        currentPaneBuilder = this::showMicrosoft;
        content.getChildren().setAll(buildMicrosoftPane());
    }

    private VBox buildMicrosoftPane() {
        Button loginButton = new Button(I18n.t("account.microsoft.login"));
        loginButton.getStyleClass().add("primary-button");
        loginButton.setOnAction(e -> startMicrosoftLogin());

        // 官方网站 / 官方购买页：提倡购买正版（合规，也更容易通过 Mojang 审核）
        Button official = new Button(I18n.t("account.official_site"));
        official.setOnAction(e -> DesktopActions.openUrl("https://www.minecraft.net/zh-hans"));
        Button buy = new Button(I18n.t("account.buy"));
        buy.getStyleClass().add("primary-button");
        buy.setOnAction(e -> DesktopActions.openUrl(
                "https://www.minecraft.net/zh-hans/store/minecraft-java-bedrock-edition"));
        HBox links = new HBox(8, loginButton, official, buy);
        links.setAlignment(Pos.CENTER_LEFT);

        Label buyHint = new Label(I18n.t("account.buy_hint"));
        buyHint.getStyleClass().add("cat-hint");
        buyHint.setWrapText(true);

        ListView<AccountStore.Account> list = accountList(AccountType.MICROSOFT);
        VBox box = accountPane("account.tab.microsoft", list,
                new VBox(6, links, buyHint), null);
        return box;
    }

    /** 微软登录：系统浏览器 + loopback 回呼（设备码登录已移除，见 MicrosoftAuthProvider 的注释）。 */
    private void startMicrosoftLogin() {
        Window owner = getScene().getWindow();
        Stage dialog = new Stage();
        dialog.initOwner(owner);
        dialog.initModality(Modality.NONE);
        // 无边框 + 主题样式：跟其它弹窗（版本设置、依赖图…）完全一致
        dialog.initStyle(javafx.stage.StageStyle.TRANSPARENT);
        dialog.setTitle(I18n.t("account.microsoft.dialog_title"));

        Label title = new Label(I18n.t("account.microsoft.dialog_title"));
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: -brisk-text;");
        javafx.scene.layout.Region headerSpacer = new javafx.scene.layout.Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        Button headerClose = UiKit.closeButtonStyled();
        headerClose.setOnAction(e -> dialog.close());
        HBox header = new HBox(8, title, headerSpacer, headerClose);
        header.setAlignment(Pos.CENTER_LEFT);

        Label stateLabel = new Label(I18n.t("account.microsoft.opening"));
        stateLabel.setWrapText(true);
        stateLabel.getStyleClass().add("instance-meta");

        Label hintLabel = new Label();
        hintLabel.setWrapText(true);
        hintLabel.getStyleClass().add("cat-hint");

        ProgressBar progress = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.getStyleClass().add("login-progress");

        // 「手动打开登录页」：自动开浏览器失败、或使用者把登录页关掉时用。
        // URL 只在内存里（含 client_id + 一次性 state），默认不显示、不进日志。
        final String[] authorizeUrl = {null};
        Button openPageButton = new Button(I18n.t("account.microsoft.open_page"));
        openPageButton.setVisible(false);
        openPageButton.setManaged(false);
        openPageButton.setOnAction(e -> {
            String url = authorizeUrl[0];
            if (url == null) {
                return;
            }
            if (DesktopActions.openUrl(url)) {
                hintLabel.setText(I18n.t("account.microsoft.open_page_hint"));
                return;
            }
            // 连手动都打不开浏览器：把链接放剪贴板（贴在界面上等于把 URL 摆进截图）
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(url);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            hintLabel.setText(I18n.t("account.microsoft.open_page_copied"));
        });

        Button settingsButton = new Button(I18n.t("account.microsoft.open_settings"));
        settingsButton.setVisible(false);
        settingsButton.setManaged(false);
        settingsButton.setOnAction(e -> {
            dialog.close();
            com.brisklauncher.mcl.MclApplication.navigateToSettings();
        });

        Button closeButton = new Button(I18n.t("common.cancel"));
        closeButton.setOnAction(e -> dialog.close());

        HBox footer = new HBox(8, progress, openPageButton, closeButton);
        footer.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(progress, Priority.ALWAYS);

        VBox card = new VBox(12, header, stateLabel, hintLabel, settingsButton, footer);
        card.setPadding(new Insets(18));
        card.setStyle("-fx-background-color: -brisk-bg; -fx-background-radius: 12;"
                + " -fx-border-color: -brisk-border; -fx-border-radius: 12;"
                + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 15, 0, 0, 5);");

        javafx.scene.Scene scene = new javafx.scene.Scene(card, 540, 260);
        UiKit.applyTheme(scene);
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        dialog.setScene(scene);
        UiKit.escToClose(scene, dialog);
        UiKit.makeDraggable(dialog, header);

        // 成功→存档并刷新；失败→提示（错误讯息经过 Redactor，不含令牌/appid）
        java.util.function.Consumer<LoginResult> onDone = result -> Platform.runLater(() -> {
            dialog.close();
            if (result.success()) {
                if (!persist(result)) {
                    return;
                }
                updateCurrentLabel();
                showMicrosoft();
                MCLDialog.info(owner, I18n.t("account.tab.microsoft"),
                        I18n.t("account.microsoft.success", result.session().name()));
            } else {
                MCLDialog.error(owner, I18n.t("account.microsoft.failed", ""),
                        result.errorMessage());
            }
        });

        // 每次登录都重新取值：改过设置页 / 换过环境变量都不用重启
        String effectiveId = com.brisklauncher.mcl.util.MsClientId.resolve(
                context.settings().microsoftClientId());
        SafeLog.loginAttempt(AccountType.MICROSOFT);

        // 没有可用的客户端 ID：不发任何请求，直接说明原因（开发构建 / 自己编译的版本）
        if (effectiveId.isBlank()) {
            stateLabel.setText(I18n.t("account.microsoft.no_client_id"));
            hintLabel.setText(I18n.t("account.microsoft.no_client_id_hint"));
            progress.setVisible(false);
            progress.setManaged(false);
            settingsButton.setVisible(true);
            settingsButton.setManaged(true);
            closeButton.setText(I18n.t("common.close"));
            UiKit.popIn(dialog, card);
            dialog.show();
            return;
        }

        UiKit.popIn(dialog, card);
        dialog.show();

        // P0-1：不在打开对话框时就偷偷发请求；只有「登录」是使用者的明确操作
        MicrosoftAuthProvider browser = new MicrosoftAuthProvider(effectiveId);
        browser.loginWithBrowser(
                state -> Platform.runLater(() -> stateLabel.setText(state)),
                url -> Platform.runLater(() -> {
                    // URL 到手就把「手动打开登录页」亮出来（自动开浏览器失败时这就是唯一出路）
                    authorizeUrl[0] = url;
                    openPageButton.setVisible(true);
                    openPageButton.setManaged(true);
                }),
                onDone);
    }

    // ---------- 离线 ----------

    private void showOffline() {
        currentPaneBuilder = this::showOffline;
        // 不再要求先登录正版账号：没有正版的玩家也能直接用离线账号。
        // 「购买正版」的提示仍保留在页面上（见下面的 buy 按钮）。
        ListView<AccountStore.Account> list = accountList(AccountType.OFFLINE);

        TextField nameField = new TextField();
        nameField.setPromptText(I18n.t("account.offline.prompt"));
        HBox.setHgrow(nameField, Priority.ALWAYS);

        Button add = new Button(I18n.t("account.offline.add"));
        add.setOnAction(e -> {
            SafeLog.loginAttempt(AccountType.OFFLINE);
            LoginResult result = offline.login(new LoginCredentials(
                    AccountType.OFFLINE, nameField.getText() == null ? "" : nameField.getText().trim(),
                    null, null));
            if (!result.success()) {
                MCLDialog.error(getScene().getWindow(), I18n.t("account.offline.add"),
                        result.errorMessage());
                return;
            }
            SafeLog.loginSuccess(AccountType.OFFLINE);
            if (!persist(result)) {
                return;
            }
            updateCurrentLabel();
            showOffline();
            MCLDialog.info(getScene().getWindow(), I18n.t("account.offline.add"),
                    I18n.t("account.offline.added", result.session().name()));
        });

        VBox box = accountPane("account.tab.offline", list, new HBox(8, nameField, add), null);
        content.getChildren().setAll(box);
    }

    // ---------- 第三方 ----------

    private void showThirdParty() {
        currentPaneBuilder = this::showThirdParty;
        // 同上：第三方（Yggdrasil / authlib-injector）登录不再被正版登录拦截。
        ListView<AccountStore.Account> list = accountList(AccountType.THIRD_PARTY);

        TextField endpointField = new TextField();
        endpointField.setPromptText("https://littleskin.cn/api/yggdrasil （或 ALI）");
        TextField userField = new TextField();
        userField.setPromptText(I18n.t("account.third_party.username"));
        PasswordField passField = new PasswordField();
        passField.setPromptText(I18n.t("account.third_party.password"));

        Button add = new Button(I18n.t("account.third_party.login"));
        add.getStyleClass().add("primary-button");
        add.setOnAction(e -> {
            String username = userField.getText() == null ? "" : userField.getText().trim();
            char[] password = passField.getText() == null ? new char[0] : passField.getText().toCharArray();
            SafeLog.loginAttempt(AccountType.THIRD_PARTY);
            String endpoint = endpointField.getText() == null ? "" : endpointField.getText().trim();
            java.net.URI endpointUri;
            try {
                endpointUri = java.net.URI.create(endpoint.isEmpty()
                        ? "https://littleskin.cn/api/yggdrasil" : endpoint);
            } catch (IllegalArgumentException bad) {
                // L12：位址乱填时给提示，不要让按钮「按了没反应」
                java.util.Arrays.fill(password, '\0');
                MCLDialog.error(getScene().getWindow(), I18n.t("account.third_party.login"),
                        I18n.t("account.third_party.bad_endpoint"));
                endpointField.setStyle("-fx-border-color: -brisk-danger;");
                return;
            }
            LoginResult result = yggdrasil.login(new LoginCredentials(
                    AccountType.THIRD_PARTY, username, password, endpointUri));
            java.util.Arrays.fill(password, '\0');
            passField.clear();
            if (!result.success()) {
                MCLDialog.error(getScene().getWindow(), I18n.t("account.third_party.login"),
                        result.errorMessage());
                return;
            }
            if (!persist(result)) {
                return;
            }
            updateCurrentLabel();
            showThirdParty();
            MCLDialog.info(getScene().getWindow(), I18n.t("account.third_party.login"),
                    I18n.t("account.offline.added", result.session().name()));
        });

        VBox form = new VBox(8, endpointField, userField, passField, add);
        VBox box = accountPane("account.tab.third_party", list, form, null);
        content.getChildren().setAll(box);
    }

    // ---------- 通用 ----------

    /** 有沒有登入過微軟正版帳號。 */
    private boolean hasMicrosoftAccount() {
        try {
            return !context.accounts().list(AccountType.MICROSOFT).isEmpty();
        } catch (Exception error) {
            return false;
        }
    }

    /**
     * 合规闸门：没有正版登入时，离线/第三方分页只显示说明与「去登录正版账号」。
     *
     * <p>这样既符合 Mojang 的使用条款，也让审核方一眼看到「本启动器优先引导正版」。
     */
    @Deprecated
    private void showLicenceGate(String titleKey) {
        Label title = UiKit.title(I18n.t(titleKey));
        Label body = new Label(I18n.t("account.gate.body"));
        body.setWrapText(true);
        body.getStyleClass().add("cat-hint");
        Button goMicrosoft = new Button(I18n.t("account.gate.button"));
        goMicrosoft.getStyleClass().add("primary-button");
        goMicrosoft.setOnAction(e -> tabSelect(AccountType.MICROSOFT));
        Button buy = new Button(I18n.t("account.buy"));
        buy.setOnAction(e -> DesktopActions.openUrl(
                "https://www.minecraft.net/zh-hans/store/minecraft-java-bedrock-edition"));
        HBox actions = new HBox(8, goMicrosoft, buy);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(10, title, UiKit.catHint(I18n.t("account.gate.title")), body, actions);
        box.setPadding(new Insets(16));
        box.getStyleClass().add("card");
        content.getChildren().setAll(box);
    }
    private VBox accountPane(String titleKey, ListView<AccountStore.Account> list,
                             javafx.scene.Node form, javafx.scene.Node extra) {
        Button setCurrentButton = new Button(I18n.t("account.set_current"));
        setCurrentButton.setOnAction(e -> {
            AccountStore.Account selected = list.getSelectionModel().getSelectedItem();
            if (selected != null) {
                context.accounts().setCurrent(selected.uuid());
                updateCurrentLabel();
                list.refresh();
            }
        });

        Button refreshButton = new Button(I18n.t("account.refresh"));
        refreshButton.setOnAction(e -> refreshAccount(list));

        Button deleteButton = new Button(I18n.t("common.delete"));
        deleteButton.setOnAction(e -> {
            AccountStore.Account selected = list.getSelectionModel().getSelectedItem();
            if (selected == null) {
                return;
            }
            MCLDialog.confirm(getScene().getWindow(), I18n.t("common.delete"),
                    I18n.t("account.delete_confirm", selected.name()), () -> {
                        context.accounts().delete(selected.uuid());
                        updateCurrentLabel();
                        list.getItems().setAll(context.accounts().list(selected.type()));
                    });
        });

        HBox actions = new HBox(8, setCurrentButton, refreshButton, deleteButton);
        VBox box = new VBox(10, UiKit.title(I18n.t(titleKey)), form, list, actions);
        box.setPadding(new Insets(16));
        box.getStyleClass().add("card");
        VBox.setVgrow(list, Priority.ALWAYS);
        return box;
    }

    private ListView<AccountStore.Account> accountList(AccountType type) {
        ListView<AccountStore.Account> list = new ListView<>();
        list.setCellFactory(view -> new ListCell<>() {
            {
                // 账号名可能带下划线（xiaojiu_114514 / _Hatsune__Miku_）。ListCell 默认不解析助记符，
                // 这里显式关掉，避免哪天换成 Button 类的基类又把 _ 吞掉（第 190 轮）。
                setMnemonicParsing(false);
            }

            @Override
            protected void updateItem(AccountStore.Account item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                    return;
                }
                boolean isCurrent = item.uuid().equals(context.accounts().currentUuid());
                setText(item.name() + "  ·  " + item.uuid()
                        + (isCurrent ? "  ✔ " + I18n.t("account.current_mark") : ""));
                setStyle(isCurrent ? "-fx-font-weight: bold;" : "");
            }
        });
        list.getItems().setAll(context.accounts().list(type));
        VBox.setVgrow(list, Priority.ALWAYS);
        return list;
    }

    /** 刷新选中账号的令牌（正版无法刷新时会提示重新登录）。 */
    private void refreshAccount(ListView<AccountStore.Account> list) {
        AccountStore.Account selected = list.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        SessionTokenStore tokenStore = SessionTokenStore.defaultStore();
        Optional<StoredSession> stored = tokenStore.load(selected.uuid());
        if (stored.isEmpty()) {
            MCLDialog.error(getScene().getWindow(), I18n.t("account.refresh"),
                    I18n.t("account.token_missing"));
            return;
        }
        Optional<AuthProvider> provider = AuthProviderRegistry.get(stored.get().type());
        if (provider.isEmpty()) {
            MCLDialog.error(getScene().getWindow(), I18n.t("account.refresh"),
                    I18n.t("account.provider_missing"));
            return;
        }
        LoginResult result = provider.get().refresh(stored.get());
        if (result.success()) {
            tokenStore.save(result.session());
            MCLDialog.info(getScene().getWindow(), I18n.t("account.refresh"),
                    I18n.t("account.refreshed", result.session().name()));
        } else {
            MCLDialog.error(getScene().getWindow(), I18n.t("account.refresh"),
                    result.errorMessage() == null ? I18n.t("account.relogin") : result.errorMessage());
        }
    }

    private void updateCurrentLabel() {
        currentLabel.setText(context.accounts().current()
                .map(account -> I18n.t("account.current", account.name(), account.type().name()))
                .orElse(I18n.t("account.no_current")));
    }

    /** 保存账号；失败时明确提示（通常是数据目录不可写或磁盘已满）。 */
    private boolean persist(LoginResult result) {
        if (context.accounts().save(result)) {
            return true;
        }
        MCLDialog.error(getScene().getWindow(), I18n.t("account.title"),
                I18n.t("account.save_failed"));
        return false;
    }

    private ToggleButton pill(String key, ToggleGroup group, Runnable onSelect) {
        ToggleButton button = new ToggleButton(I18n.t(key));
        button.setToggleGroup(group);
        button.getStyleClass().add("pill-toggle");
        button.setOnAction(e -> onSelect.run());
        return button;
    }
}
