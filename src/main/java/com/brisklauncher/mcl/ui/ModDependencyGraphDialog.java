package com.brisklauncher.mcl.ui;

import com.brisklauncher.mcl.MclContext;
import com.brisklauncher.mcl.game.GameInstance;
import com.brisklauncher.mcl.game.mod.ModDependencyGraph;
import com.brisklauncher.mcl.i18n.I18n;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 前置依赖关系图弹窗。
 *
 * <p>把「谁依赖谁、缺哪个前置」画成一张可拖动/缩放的图：
 * <ul>
 *   <li>白色气泡 = 已安装；<b>黄色</b> = 对不上 Modrinth 的未知来源；<b>红色</b> = 缺失的前置；</li>
 *   <li>气泡可以拖动、按「重新排列」回到自动布局；</li>
 *   <li>空白处拖动平移整张图、滚轮以游标为中心缩放（裁剪在面板内）；</li>
 *   <li><b>双击</b>气泡才会跳到下载页搜寻（单击只是选中，避免拖动时误触）。</li>
 * </ul>
 */
public final class ModDependencyGraphDialog {

    private static final double NODE_WIDTH = 150;
    private static final double NODE_HEIGHT = 52;
    private static final double MIN_ZOOM = 0.3;
    private static final double MAX_ZOOM = 2.5;

    private ModDependencyGraphDialog() {
    }

    public static void show(Window owner, MclContext context, GameInstance entry) {
        Stage dialog = new Stage();
        dialog.initOwner(owner);
        dialog.initModality(Modality.NONE);
        dialog.initStyle(StageStyle.TRANSPARENT);
        dialog.setTitle(I18n.t("depgraph.title"));

        Label title = new Label("🔗 " + I18n.t("depgraph.title") + " · " + entry.id());
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: -brisk-text;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = UiKit.closeButtonStyled();
        close.setOnAction(e -> dialog.close());
        HBox header = new HBox(8, title, spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        Pane graphPane = new Pane();
        graphPane.setStyle("-fx-background-color: -brisk-bg; -fx-background-radius: 12;"
                + " -fx-border-color: -brisk-border-soft; -fx-border-radius: 12;"
                + " -fx-cursor: open-hand;");
        graphPane.getStyleClass().add("no-window-drag");
        graphPane.setMinSize(600, 420);
        graphPane.setPrefSize(860, 480);

        StackPane graphViewport = new StackPane(graphPane);
        graphViewport.getStyleClass().add("no-window-drag");
        graphViewport.setAlignment(Pos.TOP_LEFT);
        graphViewport.setStyle("-fx-background-color: -brisk-bg; -fx-background-radius: 12;"
                + " -fx-border-color: -brisk-border-soft; -fx-border-radius: 12;");
        // 缩放/平移后不会盖到面板外面
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(graphViewport.widthProperty());
        clip.heightProperty().bind(graphViewport.heightProperty());
        graphViewport.setClip(clip);
        VBox.setVgrow(graphViewport, Priority.ALWAYS);
        enablePanZoom(graphPane, graphViewport);

        Label status = new Label(I18n.t("depgraph.loading"));
        status.getStyleClass().add("cat-hint");
        status.setWrapText(true);

        Button refresh = new Button(I18n.t("depgraph.refresh"));
        Button relayout = new Button(I18n.t("depgraph.relayout"));
        Button resetZoom = new Button(I18n.t("depgraph.reset_zoom"));
        Button openDownloadPage = new Button(I18n.t("depgraph.open_download"));

        Label legend = new Label(I18n.t("depgraph.legend") + "\n" + I18n.t("depgraph.pan_hint"));
        legend.getStyleClass().add("cat-hint");
        legend.setWrapText(true);

        Map<String, double[]> positions = new LinkedHashMap<>();
        Set<String> pinned = new LinkedHashSet<>();
        List<EdgeLine> edgeLines = new ArrayList<>();
        Map<String, Node> bubbles = new LinkedHashMap<>();
        ModDependencyGraph.Graph[] graphHolder = new ModDependencyGraph.Graph[1];

        Runnable render = () -> {
            ModDependencyGraph.Graph graph = graphHolder[0];
            if (graph == null) {
                return;
            }
            graphPane.getChildren().clear();
            edgeLines.clear();
            bubbles.clear();
            double[] layoutSize = layout(graph, positions, pinned,
                    graphPane.getWidth() <= 0 ? 860 : graphPane.getWidth(),
                    graphPane.getHeight() <= 0 ? 480 : graphPane.getHeight());
            for (ModDependencyGraph.Edge edge : graph.edges()) {
                double[] from = positions.get(edge.from());
                double[] to = positions.get(edge.to());
                if (from == null || to == null) {
                    continue;
                }
                ModDependencyGraph.Node target = graph.node(edge.to());
                boolean missing = target != null && target.missing();
                Line line = new Line(from[0] + NODE_WIDTH / 2, from[1] + NODE_HEIGHT / 2,
                        to[0] + NODE_WIDTH / 2, to[1] + NODE_HEIGHT / 2);
                line.setStroke(missing ? Color.web("#D98C8C") : Color.web("#A9D8D1"));
                line.setStrokeWidth(missing ? 1.8 : 1.2);
                if (missing) {
                    line.getStrokeDashArray().setAll(6.0, 5.0);
                }
                graphPane.getChildren().add(line);
                edgeLines.add(new EdgeLine(line, edge.from(), edge.to()));
            }
            for (ModDependencyGraph.Node node : graph.nodes()) {
                double[] position = positions.get(node.key());
                if (position == null) {
                    continue;
                }
                Node bubble = bubble(node, graph);
                bubble.setLayoutX(position[0]);
                bubble.setLayoutY(position[1]);
                installDrag(bubble, node, dialog, entry.id(), positions, pinned, edgeLines);
                bubbles.put(node.key(), bubble);
                graphPane.getChildren().add(bubble);
            }
            // 布局比视窗大时自动缩小到能看全；之后玩家可以自己放大/拖动
            double viewWidth = graphViewport.getWidth() <= 0 ? 860 : graphViewport.getWidth();
            double viewHeight = graphViewport.getHeight() <= 0 ? 480 : graphViewport.getHeight();
            double fit = Math.min(1.0, Math.min((viewWidth - 8) / layoutSize[0],
                    (viewHeight - 8) / layoutSize[1]));
            fit = Math.max(0.34, fit);
            // 让 pane 的「内容边界」等于布局尺寸，缩放/居中才好算
            graphPane.setPrefSize(layoutSize[0], layoutSize[1]);
            graphPane.setMinSize(layoutSize[0], layoutSize[1]);
            graphPane.setScaleX(fit);
            graphPane.setScaleY(fit);
            // JavaFX 的 scale 以节点中心为轴：位移要补上 C*(1-fit)，否则会被裁掉一角
            double offsetX = (layoutSize[0] / 2) * (1 - fit);
            double offsetY = (layoutSize[1] / 2) * (1 - fit);
            graphPane.setTranslateX(Math.max(0, (viewWidth - layoutSize[0] * fit) / 2) - offsetX);
            graphPane.setTranslateY(Math.max(0, (viewHeight - layoutSize[1] * fit) / 2) - offsetY);
            status.setText(I18n.t("depgraph.summary", graph.installedCount(), graph.missingCount(),
                    graph.unknownCount())
                    + (graph.note() == null || graph.note().isBlank() ? "" : " · " + graph.note()));
        };

        Runnable analyse = () -> {
            status.setText(I18n.t("depgraph.loading"));
            graphPane.getChildren().clear();
            positions.clear();
            pinned.clear();
            Thread.ofVirtual().name("mcl-dep-graph").start(() -> {
                ModDependencyGraph.Graph graph;
                try {
                    graph = ModDependencyGraph.build(context, entry, name ->
                            Platform.runLater(() -> status.setText(I18n.t("depgraph.analysing", name))));
                } catch (Throwable error) {
                    Platform.runLater(() -> status.setText(I18n.t("depgraph.failed",
                            String.valueOf(error.getMessage()))));
                    return;
                }
                Platform.runLater(() -> {
                    graphHolder[0] = graph;
                    render.run();
                    // 开发期：模拟双击某个气泡（验证「跳下载页 + 自动搜索」链路）
                    String devNav = System.getProperty("mcl.dev.depnav");
                    if (devNav != null && !devNav.isBlank()) {
                        for (ModDependencyGraph.Node node : graph.nodes()) {
                            if (devNav.equalsIgnoreCase(node.title())) {
                                Platform.runLater(() -> openDownload(node, dialog, entry.id()));
                                break;
                            }
                        }
                    }
                });
            });
        };

        refresh.setOnAction(e -> analyse.run());
        relayout.setOnAction(e -> {
            pinned.clear();
            positions.clear();
            render.run();
        });
        resetZoom.setOnAction(e -> resetPanZoom(graphPane));
        openDownloadPage.setOnAction(e -> {
            // 只切到「下载 → Mod」栏目，不要在这里就搜寻（搜寻要使用者自己按）
            com.brisklauncher.mcl.MclApplication.navigateToDownloadCategory("mod");
        });

        HBox toolbar = new HBox(8, refresh, relayout, resetZoom, openDownloadPage, spacer, status);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, header, toolbar, graphViewport, legend);
        root.setPadding(new Insets(16));
        // 不要在这个弹窗的 root 上挂 dropshadow：有阴影时 JavaFX 会把整个场景
        // 先渲染到离屏贴图再合成，拖动气泡时每一帧都会重新配置/回收这张贴图，
        // 记忆体就会一路往上爬（实测 500→950MB，手一停就停）。
        root.setStyle("-fx-background-color: -brisk-bg; -fx-background-radius: 12;"
                + " -fx-border-color: -brisk-border; -fx-border-radius: 12;");
        VBox.setVgrow(graphViewport, Priority.ALWAYS);

        Scene scene = new Scene(root, 900, 660);
        UiKit.applyTheme(scene);
        scene.setFill(Color.TRANSPARENT);
        dialog.setScene(scene);
        UiKit.escToClose(scene, dialog);
        UiKit.makeDraggable(dialog, header);
        UiKit.popIn(dialog, root);
        dialog.show();

        if (System.getProperty("mcl.dev.autoclose") != null) {
            // 开发期：N 秒后自动关闭，用来量「关闭后是否释放」
            try {
                int seconds = Integer.parseInt(System.getProperty("mcl.dev.autoclose").trim());
                javafx.animation.PauseTransition autoClose =
                        new javafx.animation.PauseTransition(javafx.util.Duration.seconds(seconds));
                autoClose.setOnFinished(e -> dialog.close());
                autoClose.play();
            } catch (Exception ignored) {
                // 参数不是数字就忽略
            }
        }
        // 关闭时把这张图的节点/集合全部丢掉：JavaFX 的纹理与 CSS 快取要等节点被回收才释放，
        // 只是关掉视窗的话，Scene 还被 Stage 持有，记忆体会一直挂著（实测关掉后仍 900MB+）。
        dialog.setOnHidden(e -> {
            graphPane.getChildren().clear();
            positions.clear();
            pinned.clear();
            edgeLines.clear();
            bubbles.clear();
            graphPane.setScaleX(1);
            graphPane.setScaleY(1);
            graphPane.setTranslateX(0);
            graphPane.setTranslateY(0);
            graphPane.getChildren().clear();
            javafx.scene.Scene currentScene = dialog.getScene();
            if (currentScene != null) {
                currentScene.setRoot(new javafx.scene.layout.Pane());
            }
            dialog.setScene(null);
        });

        // 开发期：-Dmcl.dev.dragstress=1 → 连续移动第一个气泡（模拟拖动，用来量记忆体）
        if (System.getProperty("mcl.dev.dragstress") != null) {
            javafx.animation.Timeline stress = new javafx.animation.Timeline(
                    new javafx.animation.KeyFrame(javafx.util.Duration.seconds(6), e -> {
                        if (bubbles.isEmpty()) {
                            return;
                        }
                        Node first = bubbles.values().iterator().next();
                        javafx.animation.Timeline move = new javafx.animation.Timeline();
                        for (int step = 0; step < 300; step++) {
                            double dx = (step % 2 == 0) ? 6 : -6;
                            double[] pos = positions.get(((java.util.Map.Entry<String, Node>)
                                    bubbles.entrySet().iterator().next()).getKey());
                            if (pos == null) {
                                continue;
                            }
                            final double targetX = pos[0] + dx;
                            move.getKeyFrames().add(new javafx.animation.KeyFrame(
                                    javafx.util.Duration.millis(step * 16L), e2 -> {
                                        first.setLayoutX(targetX);
                                        positions.put(((java.util.Map.Entry<String, Node>)
                                                bubbles.entrySet().iterator().next()).getKey(),
                                                new double[]{targetX, first.getLayoutY()});
                                    }));
                        }
                        move.play();
                    }));
            stress.play();
        }

        // 打开就分析（这张图的用途就是看现况；不想等就直接关掉）
        analyse.run();
    }

    // ---------- 气泡 ----------

    private static Node bubble(ModDependencyGraph.Node node,
                               ModDependencyGraph.Graph graph) {
        Label name = new Label(shortText(node.title(), 20));
        name.setStyle("-fx-font-weight: bold; -fx-text-fill: "
                + (node.missing() ? "#C0392B" : "#14453F") + ";");
        String metaText;
        if (node.missing()) {
            metaText = I18n.t("depgraph.state.missing");
        } else if (node.state() == ModDependencyGraph.State.UNKNOWN) {
            metaText = I18n.t("depgraph.state.unknown");
        } else {
            metaText = node.version() == null || node.version().isBlank()
                    ? I18n.t("depgraph.state.installed") : node.version();
        }
        Label meta = new Label(shortText(metaText, 22));
        meta.setStyle("-fx-font-size: 10px; -fx-text-fill: "
                + (node.missing() ? "#C0392B" : "#5E8B85") + ";");

        VBox text = new VBox(1, name, meta);
        StackPane bubble = new StackPane(text);
        bubble.setAlignment(Pos.CENTER_LEFT);
        bubble.setPadding(new Insets(6, 9, 6, 9));
        bubble.setPrefSize(NODE_WIDTH, NODE_HEIGHT);
        bubble.setMinSize(NODE_WIDTH, NODE_HEIGHT);
        bubble.setMaxSize(NODE_WIDTH, NODE_HEIGHT);
        // 状态配色：正常白、未知来源黄、缺失前置红
        String background = node.missing() ? "#FFE3DE"
                : (node.state() == ModDependencyGraph.State.UNKNOWN ? "#FFF1C4" : "#FFFFFF");
        String border = node.missing() ? "#D2695C"
                : (node.state() == ModDependencyGraph.State.UNKNOWN ? "#E0A93B" : "#BFE9E2");
        // 节点多（>50）时不挂阴影：每个 dropshadow 都是一张额外的原生贴图，
        // 128 个气泡加起来很可观；改用纯边框，视觉差异很小。
        boolean cheap = graph != null && graph.nodes().size() > 50;
        bubble.setStyle("-fx-background-color: " + background + "; -fx-background-radius: 12;"
                + " -fx-border-color: " + border + "; -fx-border-radius: 12;"
                + (cheap ? "" : " -fx-effect: dropshadow(gaussian, rgba(196,99,42,0.12), 6, 0, 0, 2);")
                + " -fx-cursor: hand;");
        Tooltip.install(bubble, new Tooltip(tooltipOf(node)));
        return bubble;
    }

    private static String tooltipOf(ModDependencyGraph.Node node) {
        StringBuilder sb = new StringBuilder(node.title());
        if (node.projectId() != null && !node.projectId().isBlank()) {
            sb.append("\nModrinth: ").append(node.projectId());
        }
        if (node.fileName() != null && !node.fileName().isBlank()) {
            sb.append("\n").append(node.fileName());
        }
        if (node.missing()) {
            sb.append("\n").append(I18n.t("depgraph.tip.missing"));
        } else if (node.projectId() != null && !node.projectId().isBlank()) {
            sb.append("\n").append(I18n.t("depgraph.tip.open"));
        }
        sb.append("\n").append(I18n.t("depgraph.tip.double_click"));
        return sb.toString();
    }

    private static String shortText(String text, int max) {
        if (text == null) {
            return "";
        }
        String value = text.strip();
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    // ---------- 拖动 ----------

    /** 一條邊對應的線段（記住兩端是哪個節點，拖動時才能只更新相關線）。 */
    private record EdgeLine(Line line, String fromKey, String toKey) {
    }

    /** 拖動氣泡（位移超過 6px 才算拖動）；雙擊才跳轉。 */
    private static void installDrag(Node bubble, ModDependencyGraph.Node node, Stage dialog,
                                    String instanceId, Map<String, double[]> positions,
                                    Set<String> pinned, List<EdgeLine> edgeLines) {
        double[] dragOffset = new double[2];
        double[] start = new double[2];
        boolean[] dragged = new boolean[1];
        bubble.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            dragOffset[0] = event.getSceneX() - bubble.getLayoutX();
            dragOffset[1] = event.getSceneY() - bubble.getLayoutY();
            start[0] = event.getSceneX();
            start[1] = event.getSceneY();
            dragged[0] = false;
        });
        bubble.addEventHandler(MouseEvent.MOUSE_DRAGGED, event -> {
            if (Math.abs(event.getSceneX() - start[0]) + Math.abs(event.getSceneY() - start[1]) > 6) {
                dragged[0] = true;
            }
            if (!dragged[0]) {
                return;
            }
            double x = event.getSceneX() - dragOffset[0];
            double y = event.getSceneY() - dragOffset[1];
            bubble.setLayoutX(x);
            bubble.setLayoutY(y);
            positions.put(node.key(), new double[]{x, y});
            pinned.add(node.key());
            updateEdges(node.key(), x, y, edgeLines);
        });
        bubble.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
            if (dragged[0]) {
                return;
            }
            if (event.getClickCount() >= 2) {
                event.consume();
                openDownload(node, dialog, instanceId);
            }
        });
    }

    private static void updateEdges(String key, double x, double y, List<EdgeLine> edgeLines) {
        double centerX = x + NODE_WIDTH / 2;
        double centerY = y + NODE_HEIGHT / 2;
        for (EdgeLine edgeLine : edgeLines) {
            if (key.equals(edgeLine.fromKey())) {
                edgeLine.line().setStartX(centerX);
                edgeLine.line().setStartY(centerY);
            }
            if (key.equals(edgeLine.toKey())) {
                edgeLine.line().setEndX(centerX);
                edgeLine.line().setEndY(centerY);
            }
        }
    }

    /** 雙擊氣泡 → 跳去下載頁並自動搜同名 Mod（目標實例就是這張圖所屬的實例）。 */
    private static void openDownload(ModDependencyGraph.Node node, Stage dialog, String instanceId) {
        if (node.projectId() == null || node.projectId().isBlank()) {
            MCLDialog.info(dialog, I18n.t("depgraph.title"), I18n.t("depgraph.no_source"));
            return;
        }
        if (dialog != null) {
            dialog.close();
        }
        com.brisklauncher.mcl.MclApplication.navigateToDownloadSearch(node.title(), instanceId);
    }

    // ---------- 平移 / 缩放 ----------

    private static void enablePanZoom(Pane pane, StackPane viewport) {
        double[] dragAnchor = new double[2];
        boolean[] panning = new boolean[1];

        viewport.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (event.getDeltaY() == 0) {
                return;
            }
            event.consume();
            double factor = event.getDeltaY() > 0 ? 1.1 : 1 / 1.1;
            double oldScale = pane.getScaleX();
            double nextScale = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, oldScale * factor));
            if (Math.abs(nextScale - oldScale) < 1e-4) {
                return;
            }
            Point2D pivot = pane.sceneToLocal(event.getSceneX(), event.getSceneY());
            double centerX = pane.getBoundsInLocal().getWidth() / 2;
            double centerY = pane.getBoundsInLocal().getHeight() / 2;
            pane.setScaleX(nextScale);
            pane.setScaleY(nextScale);
            pane.setTranslateX(pane.getTranslateX() + (oldScale - nextScale) * (pivot.getX() - centerX));
            pane.setTranslateY(pane.getTranslateY() + (oldScale - nextScale) * (pivot.getY() - centerY));
        });

        pane.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getTarget() != pane || !event.isPrimaryButtonDown()) {
                return;
            }
            panning[0] = true;
            dragAnchor[0] = event.getSceneX() - pane.getTranslateX();
            dragAnchor[1] = event.getSceneY() - pane.getTranslateY();
            pane.setCursor(Cursor.CLOSED_HAND);
        });
        pane.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (!panning[0]) {
                return;
            }
            event.consume();
            pane.setTranslateX(event.getSceneX() - dragAnchor[0]);
            pane.setTranslateY(event.getSceneY() - dragAnchor[1]);
        });
        pane.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            panning[0] = false;
            pane.setCursor(Cursor.OPEN_HAND);
        });
    }

    private static void resetPanZoom(Pane pane) {
        pane.setScaleX(1);
        pane.setScaleY(1);
        pane.setTranslateX(0);
        pane.setTranslateY(0);
    }

    // ---------- 布局 ----------

    /**
     * 布局：先用网格均匀铺开，再用「矩形分离 + 弱弹簧」微调。
     *
     * <p>只用弹簧力时节点会挤在中间（节点一多就叠在一起），所以：
     * 初始位置照网格铺满可见区域 → 每对节点按「矩形不重叠」互相推开 → 最后才让依赖关系把它们拉近。
     * 已经被拖过的节点（pinned）不参与移动。
     */
    /**
     * 布局用的节点顺序：**已安装 → 缺失 → 未知**，同组按名称排序。
     *
     * <p>这样 126 个 mod 排成网格时，一眼就能看出「哪些是我装的、哪些缺前置、哪些对不上来源」，
     * 而不是按档案顺序乱排。
     */
    private static List<ModDependencyGraph.Node> layoutOrderedNodes(ModDependencyGraph.Graph graph) {
        List<ModDependencyGraph.Node> ordered = new ArrayList<>(graph.nodes());
        ordered.sort((a, b) -> {
            int rankA = rank(a);
            int rankB = rank(b);
            if (rankA != rankB) {
                return Integer.compare(rankA, rankB);
            }
            return a.title().compareToIgnoreCase(b.title());
        });
        return ordered;
    }

    private static int rank(ModDependencyGraph.Node node) {
        if (node.missing()) {
            return 1;
        }
        return node.state() == ModDependencyGraph.State.UNKNOWN ? 2 : 0;
    }

    private static double[] layout(ModDependencyGraph.Graph graph, Map<String, double[]> positions,
                                   Set<String> pinned, double width, double height) {
        List<ModDependencyGraph.Node> nodes = layoutOrderedNodes(graph);
        int count = nodes.size();
        if (count == 0) {
            return new double[]{0, 0};
        }
        double padX = 16;
        double padY = 14;
        // 关键：**不要**把节点硬塞进视窗（那必然重叠）。
        // 先按固定间距铺开，铺多大都行，最后由呼叫方缩放到能看见全图。
        double gapX = 46;
        double gapY = 34;
        double stepX = NODE_WIDTH + gapX;
        double stepY = NODE_HEIGHT + gapY;
        int columns = Math.max(2, (int) Math.ceil(Math.sqrt((double) count)));
        columns = Math.min(columns, Math.max(1, count));
        int rows = (int) Math.ceil((double) count / columns);
        double blockW = stepX * Math.max(0, columns - 1);
        double blockH = stepY * Math.max(0, rows - 1);
        double originX = padX;
        double originY = padY;

        for (int index = 0; index < count; index++) {
            ModDependencyGraph.Node node = nodes.get(index);
            if (positions.containsKey(node.key()) && pinned.contains(node.key())) {
                continue;
            }
            int column = index % columns;
            int row = index / columns;
            positions.put(node.key(), new double[]{
                    originX + column * stepX,
                    originY + row * stepY});
        }

        double minDX = NODE_WIDTH + 18;
        double minDY = NODE_HEIGHT + 14;
        for (int pass = 0; pass < 260; pass++) {
            // 矩形分离：太近就沿重叠较小的方向推开
            for (int a = 0; a < count; a++) {
                ModDependencyGraph.Node na = nodes.get(a);
                double[] pa = positions.get(na.key());
                if (pa == null) {
                    continue;
                }
                for (int b = a + 1; b < count; b++) {
                    ModDependencyGraph.Node nb = nodes.get(b);
                    double[] pb = positions.get(nb.key());
                    if (pb == null) {
                        continue;
                    }
                    double dx = pb[0] - pa[0];
                    double dy = pb[1] - pa[1];
                    double overlapX = minDX - Math.abs(dx);
                    double overlapY = minDY - Math.abs(dy);
                    if (overlapX <= 0 || overlapY <= 0) {
                        continue;
                    }
                    double signX = dx == 0 ? (((a + b) % 2 == 0) ? 1 : -1) : Math.signum(dx);
                    double signY = dy == 0 ? (((a + b) % 2 == 0) ? -1 : 1) : Math.signum(dy);
                    if (overlapX < overlapY) {
                        double shift = overlapX * 0.55;
                        if (!pinned.contains(na.key())) {
                            pa[0] -= signX * shift;
                        }
                        if (!pinned.contains(nb.key())) {
                            pb[0] += signX * shift;
                        }
                    } else {
                        double shift = overlapY * 0.55;
                        if (!pinned.contains(na.key())) {
                            pa[1] -= signY * shift;
                        }
                        if (!pinned.contains(nb.key())) {
                            pb[1] += signY * shift;
                        }
                    }
                }
            }
            // 依赖弹簧：只做弱拉近
            for (ModDependencyGraph.Edge edge : graph.edges()) {
                double[] from = positions.get(edge.from());
                double[] to = positions.get(edge.to());
                if (from == null || to == null) {
                    continue;
                }
                double dx = to[0] - from[0];
                double dy = to[1] - from[1];
                double distance = Math.max(1, Math.hypot(dx, dy));
                double targetLength = minDX * 1.15;
                double pull = (distance - targetLength) * 0.0035;
                double forceX = (dx / distance) * pull;
                double forceY = (dy / distance) * pull;
                if (!pinned.contains(edge.from())) {
                    from[0] += forceX;
                    from[1] += forceY;
                }
                if (!pinned.contains(edge.to())) {
                    to[0] -= forceX;
                    to[1] -= forceY;
                }
            }
            // 边界：用「布局尺寸」而不是视窗尺寸（布局可以比视窗大，靠缩放/滚动看）
            for (ModDependencyGraph.Node node : nodes) {
                double[] position = positions.get(node.key());
                if (position == null || pinned.contains(node.key())) {
                    continue;
                }
                position[0] = Math.max(8, Math.min(blockW + padX, position[0]));
                position[1] = Math.max(8, Math.min(blockH + padY, position[1]));
            }
        }
        return new double[]{blockW + padX * 2 + NODE_WIDTH, blockH + padY * 2 + NODE_HEIGHT};
    }
}
