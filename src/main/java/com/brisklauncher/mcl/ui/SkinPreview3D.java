package com.brisklauncher.mcl.ui;

import javafx.geometry.Insets;
import javafx.scene.DepthTest;
import javafx.scene.Group;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;
import javafx.scene.AmbientLight;
import javafx.scene.PointLight;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 可交互的 3D 皮肤预览（SubScene）。
 *
 * <p>用 6 个长方体拼出 Steve/Alex 的基础体型，UV 按 64×64 皮肤布局取样；
 * 鼠标拖动旋转、滚轮缩放，走路动画让手臂/腿轻微摆动。
 * 没有皮肤或未登录时使用程序生成的 Steve 占位纹理。
 */
public final class SkinPreview3D extends StackPane {

    private static final double TEX = 64.0;
    private static final Image BUNDLED_STEVE = loadBundledSteve();
    private static final Image UPSCALED_STEVE = upscale(BUNDLED_STEVE, 4);

    /**
     * 每个预览自己的材质。
     *
     * <p>旧版把它做成 static：一处 {@code dispose()} 就把所有预览的贴图清掉，
     * 而且材质永远被类别持有。现在跟实例同生命周期，关闭就跟著回收。
     */
    private final PhongMaterial material = new PhongMaterial();

    /** 瘦模型（Alex，3px 手臂）标记；由皮肤贴图判断。 */
    private boolean slim;

    private final Group figure = new Group();
    private final Rotate yaw = new Rotate(0, Rotate.Y_AXIS);
    private final Rotate pitch = new Rotate(0, Rotate.X_AXIS);
    private final Rotate armRight = new Rotate(0, Rotate.X_AXIS);
    private final Rotate armLeft = new Rotate(0, Rotate.X_AXIS);
    private final Rotate legRight = new Rotate(0, Rotate.X_AXIS);
    private final Rotate legLeft = new Rotate(0, Rotate.X_AXIS);
    private final javafx.scene.PerspectiveCamera camera = new javafx.scene.PerspectiveCamera(true);
    private SubScene subScene;

    private double dragX;
    private double dragY;
    private double draggingYaw;
    private double draggingPitch;

    public SkinPreview3D() {
        setPrefSize(330, 180);
        setMinSize(240, 170);
        setPadding(Insets.EMPTY);
        setPickOnBounds(true);

        material.setDiffuseMap(UPSCALED_STEVE);
        // 皮肤是哑光的：以前给 40,40,40 的高光会让脸/额头泛白，直接关掉高光
        material.setSpecularColor(Color.rgb(0, 0, 0));
        // 默认让正面朝向摄像机（JavaFX 摄像机沿 +Z 看，模型正面也是 +Z）
        yaw.setAngle(180);

        buildFigure();

        Group root = new Group();
        // 光照总量要压在 1.0 附近，超过 1 就会被压成纯白（以前环境光 235 + 点光 255 ≈ 2.0，所以一片泛白）。
        // 参考 3d-Skin-Layers：环境光打底 + 一盏主光 + 一盏很弱的补光，没有高光。
        root.getChildren().addAll(new AmbientLight(Color.rgb(138, 138, 145)),
                pointLight(-40, -50, -40, Color.rgb(128, 126, 122)),
                pointLight(45, -30, 45, Color.rgb(40, 40, 45)),
                pointLight(-10, -45, 50, Color.rgb(30, 30, 34)),
                figure);
        figure.getTransforms().addAll(yaw, pitch);
        figure.setTranslateY(-2);

        SubScene scene3d = new SubScene(root, 330, 180, true, SceneAntialiasing.BALANCED);
        subScene = scene3d;
        scene3d.setFill(Color.TRANSPARENT);
        scene3d.setDepthTest(DepthTest.ENABLE);
        camera.setFieldOfView(35);
        camera.setTranslateZ(-58);
        scene3d.setCamera(camera);
        scene3d.widthProperty().bind(widthProperty().subtract(4));
        scene3d.heightProperty().bind(heightProperty().subtract(4));
        getChildren().add(scene3d);

        setOnMousePressed(event -> {
            dragX = event.getSceneX();
            dragY = event.getSceneY();
            draggingYaw = yaw.getAngle();
            draggingPitch = pitch.getAngle();
        });
        setOnMouseDragged(event -> {
            yaw.setAngle(draggingYaw + (event.getSceneX() - dragX) * 0.55);
            double nextPitch = draggingPitch - (event.getSceneY() - dragY) * 0.35;
            pitch.setAngle(Math.max(-35, Math.min(35, nextPitch)));
        });
        setOnScroll(event -> {
            double z = camera.getTranslateZ() + event.getDeltaY() * 0.08;
            camera.setTranslateZ(Math.max(-85, Math.min(-35, z)));
        });
    }

    private PointLight pointLight(double x, double y, double z, Color color) {
        PointLight light = new PointLight(color);
        light.setTranslateX(x);
        light.setTranslateY(y);
        light.setTranslateZ(z);
        return light;
    }

    private void buildFigure() {
        // 外层（帽子/外套/袖子/裤腿）：MC 的 3D 皮肤有两层，第二层比第一层大 0.5px。
        // 参考 3D-Skin-Layers 的效果：内层实体 + 外层半透明叠加。
        final double inflate = 0.5;

        MeshView head = box(8, 8, 8, new double[]{
                8, 8, 8, 8, 24, 8, 8, 8, 16, 8, 8, 8, 0, 8, 8, 8,
                8, 0, 8, 8, 16, 0, 8, 8});
        MeshView hat = box(8 + inflate, 8 + inflate, 8 + inflate, new double[]{
                40, 8, 8, 8, 56, 8, 8, 8, 48, 8, 8, 8, 32, 8, 8, 8,
                40, 0, 8, 8, 48, 0, 8, 8});

        MeshView body = box(8, 12, 4, new double[]{
                20, 20, 8, 12, 32, 20, 8, 12, 28, 20, 4, 12, 16, 20, 4, 12,
                20, 16, 8, 4, 28, 16, 8, 4});
        MeshView jacket = box(8 + inflate, 12 + inflate, 4 + inflate, new double[]{
                20, 36, 8, 12, 32, 36, 8, 12, 28, 36, 4, 12, 16, 36, 4, 12,
                20, 32, 8, 4, 28, 32, 8, 4});

        double armW = slim ? 3 : 4;
        MeshView rightArm = slim
                ? box(3, 12, 4, new double[]{
                        44, 20, 3, 12, 51, 20, 3, 12, 47, 20, 4, 12, 40, 20, 4, 12,
                        44, 16, 3, 4, 47, 16, 3, 4})
                : box(armW, 12, 4, new double[]{
                        44, 20, 4, 12, 52, 20, 4, 12, 48, 20, 4, 12, 40, 20, 4, 12,
                        44, 16, 4, 4, 48, 16, 4, 4});
        MeshView rightSleeve = box(armW + inflate, 12 + inflate, 4 + inflate, new double[]{
                44, 36, 4, 12, 52, 36, 4, 12, 48, 36, 4, 12, 40, 36, 4, 12,
                44, 32, 4, 4, 48, 32, 4, 4});

        MeshView leftArm = slim
                ? box(3, 12, 4, new double[]{
                        36, 52, 3, 12, 43, 52, 3, 12, 39, 52, 4, 12, 32, 52, 4, 12,
                        36, 48, 3, 4, 39, 48, 3, 4})
                : box(armW, 12, 4, new double[]{
                        36, 52, 4, 12, 44, 52, 4, 12, 40, 52, 4, 12, 32, 52, 4, 12,
                        36, 48, 4, 4, 40, 48, 4, 4});
        MeshView leftSleeve = box(armW + inflate, 12 + inflate, 4 + inflate, new double[]{
                52, 52, 4, 12, 60, 52, 4, 12, 56, 52, 4, 12, 48, 52, 4, 12,
                52, 48, 4, 4, 56, 48, 4, 4});

        MeshView rightLeg = box(4, 12, 4, new double[]{
                4, 20, 4, 12, 12, 20, 4, 12, 8, 20, 4, 12, 0, 20, 4, 12,
                4, 16, 4, 4, 8, 16, 4, 4});
        MeshView rightPant = box(4 + inflate, 12 + inflate, 4 + inflate, new double[]{
                4, 36, 4, 12, 12, 36, 4, 12, 8, 36, 4, 12, 0, 36, 4, 12,
                4, 32, 4, 4, 8, 32, 4, 4});

        MeshView leftLeg = box(4, 12, 4, new double[]{
                20, 52, 4, 12, 28, 52, 4, 12, 24, 52, 4, 12, 16, 52, 4, 12,
                20, 48, 4, 4, 24, 48, 4, 4});
        MeshView leftPant = box(4 + inflate, 12 + inflate, 4 + inflate, new double[]{
                4, 52, 4, 12, 12, 52, 4, 12, 8, 52, 4, 12, 0, 52, 4, 12,
                4, 48, 4, 4, 8, 48, 4, 4});

        head.setTranslateY(-10);
        hat.setTranslateY(-10);
        body.setTranslateY(0);
        jacket.setTranslateY(0);

        double armX = slim ? 5.5 : 6;   // 瘦模型手臂窄 0.5px，贴着身体
        Group rightArmPivot = limbPivot(rightArm, armX, -6, armRight, rightSleeve);
        Group leftArmPivot = limbPivot(leftArm, -armX, -6, armLeft, leftSleeve);
        Group rightLegPivot = limbPivot(rightLeg, 2, 6, legRight, rightPant);
        Group leftLegPivot = limbPivot(leftLeg, -2, 6, legLeft, leftPant);

        figure.getChildren().addAll(head, hat, body, jacket,
                rightArmPivot, leftArmPivot, rightLegPivot, leftLegPivot);
    }

    private static Group limbPivot(MeshView view, double x, double y, Rotate rotation,
                                   MeshView overlay) {
        view.setTranslateY(6);
        overlay.setTranslateY(6);
        Group pivot = new Group(view, overlay);
        pivot.setTranslateX(x);
        pivot.setTranslateY(y);
        pivot.getTransforms().add(rotation);
        return pivot;
    }

    private static Group limbPivot(MeshView view, double x, double y, Rotate rotation) {
        view.setTranslateY(6);
        Group pivot = new Group(view);
        pivot.setTranslateX(x);
        pivot.setTranslateY(y);
        pivot.getTransforms().add(rotation);
        return pivot;
    }

    private MeshView box(double w, double h, double d, double[] uv) {
        float[] points = new float[6 * 4 * 3];
        float[] texCoords = new float[6 * 4 * 2];
        int[] faces = new int[6 * 2 * 6];
        int pointIndex = 0;
        int faceIndex = 0;
        for (int face = 0; face < 6; face++) {
            double[][] corners = switch (face) {
                case 0 -> new double[][]{{-w / 2, -h / 2, d / 2}, {w / 2, -h / 2, d / 2},
                        {w / 2, h / 2, d / 2}, {-w / 2, h / 2, d / 2}};
                case 1 -> new double[][]{{w / 2, -h / 2, -d / 2}, {-w / 2, -h / 2, -d / 2},
                        {-w / 2, h / 2, -d / 2}, {w / 2, h / 2, -d / 2}};
                case 2 -> new double[][]{{-w / 2, -h / 2, d / 2}, {-w / 2, -h / 2, -d / 2},
                        {-w / 2, h / 2, -d / 2}, {-w / 2, h / 2, d / 2}};
                case 3 -> new double[][]{{w / 2, -h / 2, -d / 2}, {w / 2, -h / 2, d / 2},
                        {w / 2, h / 2, d / 2}, {w / 2, h / 2, -d / 2}};
                case 4 -> new double[][]{{-w / 2, -h / 2, -d / 2}, {w / 2, -h / 2, -d / 2},
                        {w / 2, -h / 2, d / 2}, {-w / 2, -h / 2, d / 2}};
                default -> new double[][]{{-w / 2, h / 2, d / 2}, {w / 2, h / 2, d / 2},
                        {w / 2, h / 2, -d / 2}, {-w / 2, h / 2, -d / 2}};
            };
            double u0 = uv[face * 4] / TEX;
            double v0 = uv[face * 4 + 1] / TEX;
            double u1 = (uv[face * 4] + uv[face * 4 + 2]) / TEX;
            double v1 = (uv[face * 4 + 1] + uv[face * 4 + 3]) / TEX;
            int base = face * 4;
            for (int i = 0; i < 4; i++) {
                points[pointIndex++] = (float) corners[i][0];
                points[pointIndex++] = (float) corners[i][1];
                points[pointIndex++] = (float) corners[i][2];
            }
            double[][] uvCoords = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};
            for (int i = 0; i < 4; i++) {
                texCoords[(base + i) * 2] = (float) uvCoords[i][0];
                texCoords[(base + i) * 2 + 1] = (float) uvCoords[i][1];
            }
            int[] tri = {base, base, base + 1, base + 1, base + 2, base + 2,
                    base, base, base + 2, base + 2, base + 3, base + 3};
            System.arraycopy(tri, 0, faces, faceIndex, tri.length);
            faceIndex += tri.length;
        }
        TriangleMesh mesh = new TriangleMesh();
        mesh.getPoints().setAll(points);
        mesh.getTexCoords().setAll(texCoords);
        mesh.getFaces().setAll(faces);
        MeshView view = new MeshView(mesh);
        view.setMaterial(material);
        view.setCullFace(CullFace.NONE);
        return view;
    }

    /** 没有皮肤时显示 Steve 占位。 */
    public void setSkin(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            usePlaceholder();
            return;
        }
        Image image = com.brisklauncher.mcl.security.GuardedImages.fromFileOriginal(file);
        if (image.isError() || image.getWidth() < 64 || image.getHeight() < 32) {
            usePlaceholder();
            return;
        }
        boolean slimNow = isSlimSkin(file);
        if (slimNow != slim) {
            slim = slimNow;
            figure.getChildren().clear();
            buildFigure();
        }
        material.setDiffuseMap(upscale(image, image.getWidth() < 96 ? 4 : 2));
    }

    /**
     * 判断是不是瘦模型（Alex，3px 手臂）。
     *
     * <p>64×64 皮肤里，宽模型第 4 列手臂像素是不透明的；瘦模型那一列留透明。
     * 采样 (54,20) / (55,20) 两个位置，透明就按瘦模型处理（64×32 的老皮肤一律算宽模型）。
     */
    private static boolean isSlimSkin(java.nio.file.Path file) {
        try {
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(file.toFile());
            if (image == null || image.getWidth() < 64 || image.getHeight() < 64) {
                return false;
            }
            int first = image.getRGB(54, 20) >>> 24;
            int second = image.getRGB(55, 20) >>> 24;
            return first == 0 || second == 0;
        } catch (Exception error) {
            return false;
        }
    }

    public void usePlaceholder() {
        if (slim) {
            slim = false;
            figure.getChildren().clear();
            buildFigure();
        }
        material.setDiffuseMap(UPSCALED_STEVE);
    }

    /** 3D 预览关闭时释放网格/纹理/SubScene 引用。 */
    public void dispose() {
        if (subScene != null) {
            subScene.widthProperty().unbind();
            subScene.heightProperty().unbind();
            subScene.setRoot(new Group());
            subScene.setCamera(null);
            subScene = null;
        }
        material.setDiffuseMap(null);
        figure.getTransforms().clear();
        figure.getChildren().clear();
        getChildren().clear();
    }

    /** 最近邻放大，降低 3D 纹理在线性采样下的模糊感。 */
    private static Image upscale(Image source, int scale) {
        if (source == null || source.isError()) {
            return source;
        }
        int width = (int) source.getWidth() * scale;
        int height = (int) source.getHeight() * scale;
        WritableImage target = new WritableImage(width, height);
        PixelWriter writer = target.getPixelWriter();
        javafx.scene.image.PixelReader reader = source.getPixelReader();
        if (reader == null) {
            return source;
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                writer.setArgb(x, y, reader.getArgb(x / scale, y / scale));
            }
        }
        return target;
    }

    private static Image loadBundledSteve() {
        try (var in = SkinPreview3D.class.getResourceAsStream("/styles/steve.png")) {
            if (in != null) {
                return com.brisklauncher.mcl.security.GuardedImages.fromStreamOriginal(in);
            }
        } catch (Exception ignored) {
            // 回退到程序生成
        }
        return placeholderSkin();
    }

    /** 程序生成的 Steve 风格 64×64 皮肤（不依赖外部资源）。 */
    private static Image placeholderSkin() {
        WritableImage image = new WritableImage(64, 64);
        PixelWriter writer = image.getPixelWriter();
        Color skin = Color.web("#C68642");
        Color hair = Color.web("#3B2A1A");
        Color shirt = Color.web("#00A8A8");
        Color pants = Color.web("#3B3B8F");
        Color shoes = Color.web("#4A4A4A");
        Color eye = Color.WHITE;
        Color iris = Color.web("#2B3BAA");

        fill(writer, 0, 0, 64, 64, skin);
        // 头：顶部/背面/两侧头发，正面脸
        fill(writer, 8, 0, 8, 8, hair);
        fill(writer, 24, 8, 8, 8, hair);
        fill(writer, 16, 8, 8, 8, hair);
        fill(writer, 0, 8, 8, 8, hair);
        fill(writer, 8, 8, 8, 8, skin);
        fill(writer, 8, 8, 8, 2, hair);
        fill(writer, 10, 12, 1, 1, eye);
        fill(writer, 13, 12, 1, 1, eye);
        fill(writer, 10, 12, 1, 1, iris);
        fill(writer, 13, 12, 1, 1, iris);
        fill(writer, 11, 15, 2, 1, Color.web("#8A4B2A"));
        // 身体
        for (int[] rect : new int[][]{{20, 20, 8, 12}, {32, 20, 8, 12},
                {28, 20, 4, 12}, {16, 20, 4, 12}}) {
            fill(writer, rect[0], rect[1], rect[2], rect[3], shirt);
        }
        // 手臂：短袖 + 皮肤
        fill(writer, 44, 20, 4, 4, shirt);
        fill(writer, 44, 24, 4, 8, skin);
        fill(writer, 52, 20, 4, 4, shirt);
        fill(writer, 52, 24, 4, 8, skin);
        fill(writer, 36, 52, 4, 4, shirt);
        fill(writer, 36, 56, 4, 8, skin);
        fill(writer, 44, 52, 4, 4, shirt);
        fill(writer, 44, 56, 4, 8, skin);
        // 腿：裤子 + 鞋
        for (int[] rect : new int[][]{{4, 20, 4, 12}, {12, 20, 4, 12},
                {8, 20, 4, 12}, {0, 20, 4, 12},
                {20, 52, 4, 12}, {28, 52, 4, 12},
                {24, 52, 4, 12}, {16, 52, 4, 12}}) {
            fill(writer, rect[0], rect[1], rect[2], rect[3], pants);
            fill(writer, rect[0], rect[1] + 10, rect[2], 2, shoes);
        }
        return image;
    }

    private static void fill(PixelWriter writer, int x, int y, int w, int h, Color color) {
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                writer.setColor(x + i, y + j, color);
            }
        }
    }
}
