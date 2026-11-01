package com.brisklauncher.mcl.game.install;

import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/**
 * Forge / NeoForge 安装器的「无界面驱动」（对标 PCL 的做法，自行实作、不复制 PCL 的 jar）。
 *
 * <p><b>为什么不用 {@code java -jar installer.jar --installClient}？</b>
 * 官方安装器有两个致命问题（本专案实测）：
 * <ol>
 *   <li>下载依赖库时用的是 {@code HttpURLConnection} 且**没有连线超时**：
 *       目标主机（{@code maven.neoforged.net} 等）在国内被墙时，执行绪会永远卡在
 *       {@code SSLSocketImpl.connect}（jstack 实测），看起来就是「安装卡住」；</li>
 *   <li>它会跑 {@code DOWNLOAD_MOJMAPS} 处理器去下载上百 MB 的官方映射表。</li>
 * </ol>
 *
 * <p>这个类别用反射直接呼叫安装器的 {@code ClientInstall.run(...)}（headless），并做两件事：
 * <ul>
 *   <li>把 install profile 的 {@code mirror} 指向镜像（预设 BMCLAPI 的 {@code /maven/}），
 *       这样依赖库就不会去撞被墙的官方 maven；</li>
 *   <li>移除 {@code DOWNLOAD_MOJMAPS} 处理器（映射表由 NeoForge 自带的 processor 处理，
 *       客户端安装不需要官方 mojmaps）。</li>
 * </ul>
 *
 * <p><b>只依赖 JDK</b>：这个类别会被放进子进程的 {@code -cp}（我们的 jar + 安装器 jar），
 * 所以不能参考本专案任何其它类别，否则会 {@code NoClassDefFoundError}。
 *
 * <p>用法（子进程）：
 * <pre>
 * java -cp "&lt;brisk-launcher.jar&gt;;&lt;installer.jar&gt;" \
 *      com.brisklauncher.mcl.game.install.ForgeInstallDriver &lt;gameDir&gt; &lt;mirrorBase|-&gt;
 * </pre>
 * 退出码：0 = 成功；1 = 安装器回报失败；2 = 这个安装器不支援反射驱动（呼叫端可退回旧方式）；
 * 3 = 其它例外。
 */
public final class ForgeInstallDriver {

    private ForgeInstallDriver() {
    }

    public static void main(String[] args) {
        String gameDir = args.length > 0 ? args[0] : ".";
        String mirror = args.length > 1 ? args[1] : "";
        boolean dropMojmaps = args.length < 3 || !"keep-mojmaps".equalsIgnoreCase(args[2].trim());
        try {
            System.exit(run(gameDir, mirror, dropMojmaps));
        } catch (Throwable error) {
            error.printStackTrace();
            System.out.flush();
            System.exit(3);
        }
    }

    /**
     * @param gameDir     游戏根目录（安装器会往 {@code versions/} 写入）
     * @param mirror      镜像 maven 根地址（空字串 = 不覆写，走官方地址）
     * @param dropMojmaps 是否移除 {@code DOWNLOAD_MOJMAPS} 处理器
     *                    （呼叫端已抓好官方映射表时才移除；否则后面的 MERGE_MAPPING 会
     *                    {@code Right does not exist}）
     * @return 进程退出码
     */
    private static int run(String gameDir, String mirror, boolean dropMojmaps) throws Exception {
        Class<?> simpleInstaller;
        Class<?> installClass;
        Class<?> clientInstall;
        Class<?> progressCallback;
        try {
            simpleInstaller = Class.forName("net.minecraftforge.installer.SimpleInstaller");
            installClass = loadInstallClass();
            clientInstall = Class.forName("net.minecraftforge.installer.actions.ClientInstall");
            progressCallback = Class.forName("net.minecraftforge.installer.actions.ProgressCallback");
        } catch (ClassNotFoundException missing) {
            System.out.println("[driver] 这个安装器不是 Forge/NeoForge 的 SimpleInstaller 结构："
                    + missing.getMessage());
            return 2;
        }

        InstallerApi api = new InstallerApi(simpleInstaller, installClass, clientInstall, progressCallback);
        // headless：不要试图开 GUI
        try {
            Field headless = simpleInstaller.getField("headless");
            headless.setBoolean(null, true);
        } catch (NoSuchFieldException ignored) {
            // 有些版本的栏位名不同；下面仍会用 ClientInstall，不影响
        }

        Object install = api.loadInstallProfile();
        if (dropMojmaps) {
            api.dropMojmapsProcessor(install);
        } else {
            System.out.println("[driver] 保留 DOWNLOAD_MOJMAPS（官方映射表交给安装器下载）");
        }
        api.applyMirror(install, mirror);
        System.out.println("[driver] 已载入安装设定（镜像：" + (mirror.isBlank() ? "官方" : mirror) + "）");
        System.out.flush();

        File target = new File(gameDir).getAbsoluteFile();
        File installerJar = api.installerJar();
        if (installerJar != null) {
            System.out.println("[driver] 安装器：" + installerJar);
        }
        Object action = api.newClientInstall(install, progressCallback);
        Boolean result = api.invokeRun(action, target, installerJar);
        System.out.println(String.valueOf(result));
        System.out.flush();
        return Boolean.TRUE.equals(result) ? 0 : 1;
    }

    /** 新版是 {@code InstallV1}，舊版是 {@code Install}。 */
    private static Class<?> loadInstallClass() throws ClassNotFoundException {
        try {
            return Class.forName("net.minecraftforge.installer.json.InstallV1");
        } catch (ClassNotFoundException old) {
            return Class.forName("net.minecraftforge.installer.json.Install");
        }
    }

    /** 把安裝器的反射 API 包一層，讓主流程好讀。 */
    private record InstallerApi(Class<?> simpleInstaller, Class<?> installClass,
                                Class<?> clientInstall, Class<?> progressCallback) {

        Object loadInstallProfile() throws Exception {
            Class<?> util = Class.forName("net.minecraftforge.installer.json.Util");
            Method method = util.getDeclaredMethod("loadInstallProfile");
            method.setAccessible(true);
            return method.invoke(null);
        }

        /** 移除 {@code DOWNLOAD_MOJMAPS}（官方映射表很大，客戶端安裝不需要）。 */
        void dropMojmapsProcessor(Object install) throws Exception {
            Field field = field("processors");
            if (field == null) {
                return;
            }
            Object value = field.get(install);
            if (!(value instanceof List<?> list)) {
                return;
            }
            @SuppressWarnings("unchecked")
            List<Object> processors = (List<Object>) list;
            List<Object> remove = new ArrayList<>();
            for (Object processor : processors) {
                List<String> args = processorArgs(processor);
                if (args.contains("DOWNLOAD_MOJMAPS")) {
                    remove.add(processor);
                }
            }
            if (!remove.isEmpty()) {
                processors.removeAll(remove);
                System.out.println("[driver] 已略过 DOWNLOAD_MOJMAPS 处理器（" + remove.size() + " 个）");
            }
        }

        private List<String> processorArgs(Object processor) {
            try {
                Method getArgs = processor.getClass().getMethod("getArgs");
                Object args = getArgs.invoke(processor);
                if (args instanceof String[] array) {
                    return Arrays.asList(array);
                }
                if (args instanceof List<?> list) {
                    List<String> result = new ArrayList<>();
                    for (Object item : list) {
                        result.add(String.valueOf(item));
                    }
                    return result;
                }
            } catch (Exception ignored) {
                // 没有 getArgs 就当成不需要移除
            }
            return List.of();
        }

        /** 把 {@code mirror} 欄位指向鏡像：安裝器下載依賴庫時會改走這個地址。 */
        void applyMirror(Object install, String mirrorBase) throws Exception {
            if (mirrorBase == null || mirrorBase.isBlank()) {
                return;
            }
            Field field = field("mirror");
            if (field == null) {
                System.out.println("[driver] 这个安装器没有 mirror 栏位，依赖库会走官方地址");
                return;
            }
            Class<?> mirrorClass = Class.forName("net.minecraftforge.installer.json.Mirror");
            Object mirror = buildMirror(mirrorClass, mirrorBase);
            field.set(install, mirror);
            System.out.println("[driver] 依赖库镜像：" + mirrorBase);
        }

        private Object buildMirror(Class<?> mirrorClass, String base) throws Exception {
            String url = base.endsWith("/") ? base : base + "/";
            // 优先用安装器自带的 Gson 反序列化（Mirror 的栏位可能是 private + final）
            try {
                Class<?> gsonClass = Class.forName("com.google.gson.Gson");
                Object gson = gsonClass.getConstructor().newInstance();
                Method fromJson = gsonClass.getMethod("fromJson", String.class, Class.class);
                String json = "{\"name\":\"brisk-mirror\",\"url\":\"" + url + "\"}";
                return fromJson.invoke(gson, json, mirrorClass);
            } catch (Exception ignored) {
                // 没有 Gson 就退回反射建构
            }
            Object instance = mirrorClass.getDeclaredConstructor().newInstance();
            setField(mirrorClass, instance, "name", "brisk-mirror");
            setField(mirrorClass, instance, "url", url);
            return instance;
        }

        private void setField(Class<?> type, Object instance, String name, String value)
                throws Exception {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            field.set(instance, value);
        }

        Object newClientInstall(Object install, Class<?> progressCallbackClass) throws Exception {
            Object monitor;
            try {
                Method withOutputs = progressCallbackClass.getMethod(
                        "withOutputs", OutputStream[].class);
                monitor = withOutputs.invoke(null, (Object) new OutputStream[]{System.out});
            } catch (Exception error) {
                // 没有 withOutputs 的版本：用预设实作
                monitor = progressCallbackClass.getMethod("withOutputs", OutputStream[].class)
                        .invoke(null, (Object) new OutputStream[]{System.out});
            }
            // 新版是 ClientInstall(InstallV1, ProgressCallback)，旧版是 ClientInstall(Install, ...)
            Exception last = null;
            for (Class<?> parameter : new Class<?>[]{installClass, Class.forName(
                    "net.minecraftforge.installer.json.Install")}) {
                try {
                    Constructor<?> ctor = clientInstall.getDeclaredConstructor(parameter,
                            progressCallbackClass);
                    ctor.setAccessible(true);
                    return ctor.newInstance(install, monitor);
                } catch (Exception error) {
                    last = error;
                }
            }
            throw new IllegalStateException("无法建立安装器动作（ClientInstall）", last);
        }

        /** 安裝器的 {@code run} 有三種簽名，逐一嘗試。 */
        Boolean invokeRun(Object action, File target, File installerJar) throws Exception {
            Predicate<String> optionals = ignored -> true;
            Method fallback = null;
            for (Method method : clientInstall.getMethods()) {
                if (!"run".equals(method.getName())) {
                    continue;
                }
                Class<?>[] types = method.getParameterTypes();
                if (types.length == 2 && types[0] == File.class) {
                    try {
                        if (types[1] == Predicate.class) {
                            return (Boolean) method.invoke(action, target, optionals);
                        }
                        if (installerJar != null) {
                            return (Boolean) method.invoke(action, target, installerJar);
                        }
                    } catch (InvocationTargetException inner) {
                        throw rethrow(inner);
                    }
                } else if (types.length == 3 && installerJar != null) {
                    fallback = method;
                }
            }
            if (fallback != null) {
                try {
                    return (Boolean) fallback.invoke(action, target, optionals, installerJar);
                } catch (InvocationTargetException inner) {
                    throw rethrow(inner);
                }
            }
            throw new IllegalStateException("这个安装器没有可用的 run(...) 方法");
        }

        private static Exception rethrow(InvocationTargetException inner) {
            Throwable cause = inner.getCause();
            return cause instanceof Exception exception ? exception : new Exception(cause);
        }

        private Field field(String name) {
            for (Class<?> type = installClass; type != null; type = type.getSuperclass()) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException ignored) {
                    // 往父类找
                }
            }
            return null;
        }

        /** 安裝器 jar 本身（安裝器用它找自己的 install_profile 與 resources）。 */
        File installerJar() {
            try {
                java.security.CodeSource source = simpleInstaller.getProtectionDomain().getCodeSource();
                if (source == null || source.getLocation() == null) {
                    return null;
                }
                URI uri = source.getLocation().toURI();
                Path path = Path.of(uri);
                return path.toFile();
            } catch (Exception error) {
                return null;
            }
        }
    }
}
