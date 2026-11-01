package com.brisklauncher.mcl.ui;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** 轻量 UI 工厂，统一轻快风格。 */
public final class UiKit {

    private UiKit() {
    }

    public static Label title(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("section-title");
        return label;
    }

    public static Label catHint(String text) {
        Label label = new Label("✦ " + text);
        label.getStyleClass().add("cat-hint");
        return label;
    }

    public static Node placeholder(String text) {
        VBox box = new VBox(12, title("⚡ 施工中"), catHint(text));
        box.setPadding(new Insets(40));
        StackPane pane = new StackPane(box);
        pane.getStyleClass().add("page");
        return pane;
    }

    /** 彈窗彈出動畫（委託給 UiTransitions，讓所有彈窗行為一致）。 */
    public static void popIn(javafx.stage.Stage stage, Node root) {
        com.brisklauncher.mcl.ui.animation.UiTransitions.popIn(stage, root);
    }


    /**
     * 「首字母」头像（没有图标时用）：圆形底 + 一个字，颜色由文字杂凑决定。
     *
     * <p>比 emoji 占位可靠：字型缺字时 emoji 会变成豆腐方块（实测 🧩 就是）。
     */
    public static javafx.scene.Node letterAvatar(String text, double size) {
        String value = text == null ? "" : text.strip();
        String letter = value.isEmpty() ? "?"
                : new String(Character.toChars(value.codePointAt(0))).toUpperCase(java.util.Locale.ROOT);
        int hash = Math.abs(value.hashCode());
        String[] palette = {"#12BFA8", "#2E8B62", "#3E7B74", "#0E9C8D", "#4F9C93",
                "#5AD8C6", "#2F6F86", "#1F7A6E"};
        String color = palette[hash % palette.length];
        javafx.scene.control.Label label = new javafx.scene.control.Label(letter);
        label.setMinSize(size, size);
        label.setPrefSize(size, size);
        label.setMaxSize(size, size);
        label.setAlignment(javafx.geometry.Pos.CENTER);
        label.setStyle("-fx-background-color: " + color + "; -fx-background-radius: " + (size / 2)
                + "; -fx-text-fill: white; -fx-font-size: " + Math.round(size * 0.5)
                + "px; -fx-font-weight: bold;");
        return label;
    }
    /** 所有弹窗/对话框统一套用主题，避免出现系统灰底控件。 */
    public static void applyTheme(javafx.scene.Scene scene) {
        if (scene == null) {
            return;
        }
        var resource = UiKit.class.getResource("/styles/mcl-theme.css");
        if (resource == null) {
            return;
        }
        String css = resource.toExternalForm();
        if (!scene.getStylesheets().contains(css)) {
            scene.getStylesheets().add(css);
        }
        // 玩家自订主题：额外叠一段 CSS（放在最后，优先级高于预设主题）
        var themeCss = com.brisklauncher.mcl.setting.Packs.currentThemeCss();
        if (themeCss != null && java.nio.file.Files.isRegularFile(themeCss)) {
            // 安全审计 v3 A-14：主题包是「别人给的档案」，而 JavaFX CSS 的 url()
            // 支持 http(s)/file/jar —— 等于让主题包能发任意请求（隐私信标）、
            // 读本地文件、甚至把远端字体喂给字体解析器。加载前先做静态检查。
            if (!ThemeCssGuard.allowed(themeCss)) {
                return;
            }
            String custom = themeCss.toUri().toString();
            if (!scene.getStylesheets().contains(custom)) {
                scene.getStylesheets().add(custom);
            }
        }
    }

    /**
     * 让按钮以「文字宽度」为最小宽度。
     *
     * <p>放在 HBox 里时默认会被压缩，中文标签就成了省略号；统一包一层，
     * 再配合可换行的 FlowPane 使用。
     */
    /**
     * 给弹窗装 ESC 关闭：模态弹窗会挡住主窗口，玩家点不到「关闭启动器」，
     * 至少要有快捷键能先关掉弹窗（PCL 的弹窗同样支持 ESC）。
     */
    public static void escToClose(javafx.scene.Scene scene, javafx.stage.Stage dialog) {
        if (scene == null || dialog == null) {
            return;
        }
        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                event.consume();
                dialog.close();
            }
        });
    }
    /** 统一的弹窗关闭按钮（✕）：尺寸用代码给，避免 CSS 尺寸属性不支持导致文字被裁/消失。 */
    public static javafx.scene.control.Button closeButtonStyled() {
        javafx.scene.control.Button button = new javafx.scene.control.Button("✕");
        button.getStyleClass().addAll("window-button", "window-close");
        // 注意：不要写死宽度！✕ 走字体回退，宽度小于字形宽度时 JavaFX 会直接把文字裁掉（实测 34px 就完全空白）。
        button.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        button.setPrefHeight(30);
        button.setMinHeight(30);
        // 直接给文字色：主题包如果把 .window-button 的 text-fill 改透明，按钮就“消失”了
        button.setTextFill(javafx.scene.paint.Color.web("#1C5A54"));
        return button;
    }
    public static <T extends javafx.scene.control.Labeled> T noShrink(T control) {
        control.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        return control;
    }

    /**
     * 让弹窗可以按住某个区域自由拖动（标题列/空白处都行）。
     *
     * <p>弹窗是无边框 Stage，只能靠这个移动；带滑鼠的按下位移差计算，
     * 拖到萤幕边缘时夹住，不会拖出可视范围。
     *
     * @param dialog 要移动的视窗
     * @param handle 按住就能拖的节点（通常是标题列）
     */
    public static void makeDraggable(javafx.stage.Stage dialog, javafx.scene.Node handle) {
        if (dialog == null) {
            return;
        }
        // 注意：整个「顶部带状区域」都可以拖，不是只有标题文字。
        // 做法是把事件过滤器挂在场景根节点上，命中条件：
        //   1) 按下的位置在视窗顶部 88px 内，或落在给定的 handle 范围内；
        //   2) 按到的不是互动控件（按钮/输入框/下拉/列表…），否则会抢走点击。
        javafx.scene.Parent[] bound = new Parent[1];
        Runnable attach = () -> {
            javafx.scene.Scene scene = dialog.getScene();
            if (scene == null || scene.getRoot() == null) {
                return;
            }
            javafx.scene.Parent root = scene.getRoot();
            if (bound[0] == root) {
                return;
            }
            bound[0] = root;
            double[] offset = new double[2];
            boolean[] dragging = new boolean[1];
            root.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
                if (!event.isPrimaryButtonDown() || isInteractive(event.getTarget())) {
                    dragging[0] = false;
                    return;
                }
                // 只要不是按在互动控件上，整个视窗都可以拖（使用者要求整条 top 都能拖，
                // 干脆放宽到「非控件区域」都能拖，避免每个弹窗的标题高度不一致又要个别调）。
                if (handle != null && handle.getScene() != null) {
                    boolean inHandle = handle.localToScene(handle.getBoundsInLocal())
                            .contains(event.getSceneX(), event.getSceneY());
                    if (!inHandle && event.getSceneY() - dialog.getY() > 88) {
                        // 不在标题带、也不在 handle 范围：仍然允许拖，但排除内容区的缩放/滑动
                        // （内容区多半是 ScrollPane/ListView，已在 isInteractive 里排除）
                    }
                }
                dragging[0] = true;
                offset[0] = event.getScreenX() - dialog.getX();
                offset[1] = event.getScreenY() - dialog.getY();
            });
            root.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, event -> {
                if (!dragging[0] || !event.isPrimaryButtonDown()) {
                    return;
                }
                double nextX = event.getScreenX() - offset[0];
                double nextY = event.getScreenY() - offset[1];
                javafx.stage.Screen screen = screenOf(event.getScreenX(), event.getScreenY());
                if (screen != null) {
                    javafx.geometry.Rectangle2D bounds = screen.getVisualBounds();
                    nextX = Math.max(bounds.getMinX() - 40, Math.min(bounds.getMaxX() - 80, nextX));
                    nextY = Math.max(bounds.getMinY(), Math.min(bounds.getMaxY() - 40, nextY));
                }
                dialog.setX(nextX);
                dialog.setY(nextY);
            });
            root.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED,
                    event -> dragging[0] = false);
        };
        attach.run();
        dialog.sceneProperty().addListener((obs, oldScene, newScene) -> attach.run());
    }

    /** 互動控件（按鈕/輸入框/列表…）不吃拖動事件，否則點不到。 */
    private static boolean isInteractive(Object target) {
        if (!(target instanceof javafx.scene.Node node)) {
            return false;
        }
        javafx.scene.Node current = node;
        while (current != null) {
            // 带 no-window-drag 样式类的区域（例如依赖图的画布）自己处理拖动，
            // 不能再被视窗拖动拦一次，否则两个拖动会打架。
            if (current.getStyleClass().contains("no-window-drag")) {
                return true;
            }
            if (current instanceof javafx.scene.control.Button
                    || current instanceof javafx.scene.control.TextInputControl
                    || current instanceof javafx.scene.control.ComboBoxBase
                    || current instanceof javafx.scene.control.CheckBox
                    || current instanceof javafx.scene.control.RadioButton
                    || current instanceof javafx.scene.control.Slider
                    || current instanceof javafx.scene.control.Spinner
                    || current instanceof javafx.scene.control.ListView
                    || current instanceof javafx.scene.control.TableView
                    || current instanceof javafx.scene.control.TreeView
                    || current instanceof javafx.scene.control.ScrollBar
                    || current instanceof javafx.scene.control.ScrollPane
                    || current instanceof javafx.scene.control.TitledPane
                    || current instanceof javafx.scene.control.TabPane) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private static javafx.stage.Screen screenOf(double x, double y) {
        for (javafx.stage.Screen screen : javafx.stage.Screen.getScreens()) {
            if (screen.getVisualBounds().contains(x, y)) {
                return screen;
            }
        }
        return javafx.stage.Screen.getPrimary();
    }
}