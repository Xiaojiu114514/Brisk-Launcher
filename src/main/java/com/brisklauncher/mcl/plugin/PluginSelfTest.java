package com.brisklauncher.mcl.plugin;

import com.brisklauncher.mcl.auth.api.AccountType;
import com.brisklauncher.mcl.auth.api.AuthProvider;
import com.brisklauncher.mcl.auth.api.GameSession;
import com.brisklauncher.mcl.auth.api.LoginCredentials;
import com.brisklauncher.mcl.auth.api.LoginResult;
import com.brisklauncher.mcl.auth.api.StoredSession;
import com.brisklauncher.mcl.auth.impl.AuthProviderRegistry;
import com.brisklauncher.mcl.plugin.api.LauncherPage;
import com.brisklauncher.mcl.plugin.api.LauncherPlugin;
import com.brisklauncher.mcl.plugin.api.PluginApi;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 插件 API 自检（不需要启动 JavaFX、不需要真的开启动器）。
 *
 * <p>验证的是「外部插件契约」里最容易悄悄坏掉的那几段：
 * plugin.json 解析、apiVersion 闸门、classloader 实例化、插件语言包、
 * 插件配置落盘、插件服务登记、认证实现注册/注销。
 *
 * <p>跑法（先 {@code mvn -o compile}）：
 * <pre>
 *   java -cp "target\classes;build\install\brisk-launcher\lib\*" \
 *        com.brisklauncher.mcl.plugin.PluginSelfTest examples\hello-plugin\hello-plugin.jar
 * </pre>
 * 结束码 0 = 全过。
 */
public final class PluginSelfTest {

    private static int failures;
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args.length > 0 ? args[0] : "examples/hello-plugin/hello-plugin.jar");
        Path work = Path.of("target", "plugin-selftest");
        Files.createDirectories(work);

        testManifest(jar);
        testMissingManifest(work);
        testInstantiate(jar);
        testConfig(work);
        testServices(work);
        testMessages(jar);
        testAuthRegistry();

        System.out.println();
        System.out.println("插件 API 自检：" + (checks - failures) + "/" + checks + " 通过");
        if (failures > 0) {
            System.out.println("失败 " + failures + " 项");
            System.exit(1);
        }
    }

    // ---------- 各项检查 ----------

    private static void testManifest(Path jar) throws Exception {
        check("manifest: 檔案存在", Files.isRegularFile(jar));
        PluginManifest.Data data = PluginManifest.read(jar);
        check("manifest: id", "hello-plugin".equals(data.id()), data.id());
        check("manifest: class", "com.example.hello.HelloPlugin".equals(data.className()), data.className());
        check("manifest: apiVersion <= 当前", data.apiVersion() <= PluginApi.VERSION,
                Integer.toString(data.apiVersion()));
        check("manifest: author", "Brisk Launcher".equals(data.author()), data.author());
        check("manifest: 自带语言包", data.localeTags().contains("zh_CN") && data.localeTags().contains("zh_TW"),
                data.localeTags().toString());
    }

    private static void testMissingManifest(Path work) throws Exception {
        Path jar = work.resolve("broken-no-manifest.jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("nothing.txt"));
            out.write("x".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        try {
            PluginManifest.read(jar);
            check("manifest: 缺 plugin.json 要报错", false);
        } catch (IllegalArgumentException expected) {
            check("manifest: 缺 plugin.json 要报错", expected.getMessage().contains("plugin.json"),
                    expected.getMessage());
        }
    }

    private static void testInstantiate(Path jar) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()},
                LauncherPlugin.class.getClassLoader())) {
            PluginManifest.Data data = PluginManifest.read(jar);
            Class<?> type = Class.forName(data.className(), true, loader);
            check("加载: 主类实现 LauncherPlugin", LauncherPlugin.class.isAssignableFrom(type));
            LauncherPlugin plugin = (LauncherPlugin) type.getDeclaredConstructor().newInstance();
            check("加载: id 一致", "hello-plugin".equals(plugin.id()), plugin.id());
            check("加载: 无参构造 + icon", "👋".equals(plugin.icon()), plugin.icon());
            List<LauncherPage> pages = plugin.pages();
            check("导航页: 有 1 个", pages.size() == 1, Integer.toString(pages.size()));
            if (!pages.isEmpty()) {
                LauncherPage page = pages.get(0);
                check("导航页: id 合法", PluginManager.sanitizeId(page.id()).equals(page.id()), page.id());
                check("导航页: 有标题", page.title() != null && !page.title().isBlank(), page.title());
            }
            // 主类有没有覆写 createPanel（决定要不要出现在工具页）
            boolean hasPanel = type.getMethod("createPanel",
                    com.brisklauncher.mcl.plugin.api.PluginContext.class)
                    .getDeclaringClass() != LauncherPlugin.class;
            check("工具页: 检测到面板", hasPanel);
        }
    }

    private static void testConfig(Path work) {
        Path dir = work.resolve("config-check");
        PluginConfigImpl config = new PluginConfigImpl(dir.resolve("config.properties"));
        config.set("greeting", "你好");
        config.set("counter", "7");
        check("配置: 立刻落盘", Files.isRegularFile(dir.resolve("config.properties")));
        PluginConfigImpl reloaded = new PluginConfigImpl(dir.resolve("config.properties"));
        check("配置: 重新读取", "你好".equals(reloaded.get("greeting", "")), reloaded.get("greeting", ""));
        check("配置: 整数", reloaded.getInt("counter", 0) == 7, Integer.toString(reloaded.getInt("counter", 0)));
        check("配置: 缺省值", reloaded.getInt("missing", 42) == 42);
        reloaded.set("greeting", null);
        check("配置: 删除键", !new PluginConfigImpl(dir.resolve("config.properties")).has("greeting"));
    }

    private static void testServices(Path work) {
        PluginServicesRegistry registry = new PluginServicesRegistry();
        var alice = registry.view("alice");
        var bob = registry.view("bob");
        alice.provide("demo.api", "hello");
        check("服务: 跨插件可见", "hello".equals(bob.find("demo.api", String.class)));
        check("服务: 类型不符回 null", bob.find("demo.api", Integer.class) == null);
        check("服务: has", bob.has("demo.api"));
        try {
            bob.require("missing", String.class);
            check("服务: require 缺项要抛", false);
        } catch (IllegalStateException expected) {
            check("服务: require 缺项要抛", true);
        }
        registry.clear("alice");
        check("服务: 卸载后清空", !bob.has("demo.api"));
    }

    private static void testMessages(Path jar) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()},
                LauncherPlugin.class.getClassLoader())) {
            PluginMessagesImpl messages = new PluginMessagesImpl(loader, List.of("zh_CN", "zh_TW"));
            check("语言包: 当前语言（zh_CN）", "示例插件".equals(messages.t("hello.name")), messages.t("hello.name"));
            check("语言包: 带参数", messages.t("hello.greeting", "Steve").contains("Steve"),
                    messages.t("hello.greeting", "Steve"));
            check("语言包: 缺键回退成键名", "hello.unknown".equals(messages.t("hello.unknown")));
            check("语言包: has", messages.has("hello.desc") && !messages.has("hello.unknown"));
            check("语言包: 列出标签", messages.locales().contains("zh_TW"), messages.locales().toString());
        }
    }

    private static void testAuthRegistry() {
        String before = AuthProviderRegistry.get(AccountType.OFFLINE)
                .map(AuthProviderRegistry::idOf).orElse("");
        AuthProvider demo = new DemoProvider();
        AuthProviderRegistry.register(demo);
        check("认证: 插件实现覆盖同类型",
                "demo-offline".equals(AuthProviderRegistry.get(AccountType.OFFLINE)
                        .map(AuthProviderRegistry::idOf).orElse("")));
        check("认证: byId 找得到", AuthProviderRegistry.byId("demo-offline").isPresent());
        AuthProviderRegistry.unregister("demo-offline");
        check("认证: 注销后回退内建",
                before.equals(AuthProviderRegistry.get(AccountType.OFFLINE)
                        .map(AuthProviderRegistry::idOf).orElse("")));
    }

    // ---------- 工具 ----------

    private static void check(String label, boolean ok) {
        check(label, ok, "");
    }

    private static void check(String label, boolean ok, String detail) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL  " + label + (detail.isEmpty() ? "" : "  → " + detail));
        } else {
            System.out.println("ok    " + label + (detail.isEmpty() ? "" : "  → " + detail));
        }
    }

    /** 测试用认证实现：只实现四个方法，验证注册表的覆盖/回退。 */
    private static final class DemoProvider implements AuthProvider {

        @Override
        public AccountType type() {
            return AccountType.OFFLINE;
        }

        @Override
        public String id() {
            return "demo-offline";
        }

        @Override
        public LoginResult login(LoginCredentials credentials) {
            return null;
        }

        @Override
        public LoginResult refresh(StoredSession session) {
            return null;
        }

        @Override
        public boolean validate(StoredSession session) {
            return false;
        }

        @Override
        public GameSession toGameSession(StoredSession session) {
            return null;
        }
    }
}
