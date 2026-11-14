# Plugin API reference (signatures)

**English** · [简体中文 ↓](#简体中文) · [繁體中文 ↓](#繁體中文)

## English

This is the complete signature reference for the third-party plugin API. The tutorial, lifecycle,
examples and security notes live in the [Plugin Development Guide](PLUGIN_DEV.md); this file is the
lookup table when you need an exact type, method or default value.

> API version: **2** (`com.brisklauncher.mcl.plugin.api.PluginApi.VERSION`).
> Stable packages: `com.brisklauncher.mcl.plugin.api` and `com.brisklauncher.mcl.auth.api`.
> The launcher module also exports `com.brisklauncher.mcl` and `com.brisklauncher.mcl.i18n` for
> historical reasons; those are **not** part of the compatibility contract.

**There is no permission manifest.** The launcher decides whether a plugin may be loaded (disabled
list, SHA-256 trust record, one-time risk notice); after that, the plugin runs with the same Windows
user rights as the launcher. This API has no Android-style permission model and is not a sandbox.

---

### 1. `plugin.json`

The file must be at the **jar root** (not under `META-INF/`), UTF-8, and is read without executing
plugin code.

| Field | Type | Required | Default | Notes |
|---|---|---|---|---|
| `id` | string | recommended | jar file name | Sanitized to `[A-Za-z0-9._-]`; used for `data\plugin-data\<id>\` and `context.pluginId()`. For external jars this manifest id wins over `LauncherPlugin.id()`. |
| `class` | string | **yes** | — | Main class; must implement `LauncherPlugin`, be `public`, and have a public no-arg constructor. |
| `name` | string | no | jar file name | Fallback display name when `LauncherPlugin.name()` is empty. |
| `description` | string | no | `""` | One-line description in the Tools list. |
| `version` | string | no | `""` | Fallback when `LauncherPlugin.version()` is empty. |
| `author` | string | no | `""` | Shown as metadata in the Tools page. |
| `homepage` | string | no | `""` | Informational only. |
| `icon` | string | no | `"🧩"` | Emoji fallback for `LauncherPlugin.icon()`. |
| `apiVersion` | integer | no | `2` | Declares the minimum API. A launcher with `PluginApi.VERSION < apiVersion` refuses the plugin. |
| `requires` | array of strings | no | `[]` | Plugin ids that must load first. Missing/disabled/cyclic dependencies produce a visible load error. |

Unknown fields are ignored. There is no `permissions` field; see the warning above.

---

### 2. `LauncherPlugin`

```java
package com.brisklauncher.mcl.plugin.api;

public interface LauncherPlugin {
    String id();
    String name();
    default String description() { return ""; }
    default String icon() { return "🧩"; }
    default String version() { return ""; }
    default void onLoad(PluginContext context) { }
    default List<LauncherPage> pages() { return List.of(); }
    default Node createPanel(PluginContext context) { return null; }
    default void onUnload() { }
}
```

| Member | Contract |
|---|---|
| `id()` | Stable plugin id. External jars: keep it identical to `plugin.json`; the manifest is what the launcher uses. |
| `name()` | Display name. Read once at load; re-read only when the plugin is reloaded (Refresh). |
| `description()` | Read once at load. |
| `icon()` | Emoji; read once at load. |
| `version()` | Read once at load; falls back to `plugin.json` when blank. |
| `onLoad(context)` | Called once per plugin instance. At startup this runs on a background thread; on Refresh it runs on the FX thread. Do not build JavaFX nodes here. |
| `pages()` | Called once at load, on the same thread as `onLoad`. Return navigation pages; do not build JavaFX nodes here. |
| `createPanel(context)` | Called on the FX thread whenever the Tools-page panel is (re)created. Return `null` to mean "this plugin has no tool panel". |
| `onUnload()` | Called once by `PluginManager.unloadAll()` on disable/remove/refresh/launcher exit. **Not** called when leaving the Tools page. Stop threads and close files here. |

If your plugin only contributes pages, do not override `createPanel`; the launcher detects it and
shows a hint instead of an empty panel.

---

### 3. `LauncherPage`

```java
public interface LauncherPage {
    String id();
    String title();
    default String icon() { return ""; }
    Node createContent(PluginContext context);
    default void onShown() { }
    default void onHidden() { }
    default void onDispose() { }
}
```

| Member | Contract |
|---|---|
| `id()` | `[A-Za-z0-9._-]`; the internal page key is `p:<pluginId>:<pageId>`. |
| `title()` | Top-bar label; re-read when the UI is rebuilt on language switch. |
| `icon()` | Optional emoji prefix. |
| `createContent(context)` | Called on the FX thread for each page view. A revisit builds a new view, so it may run again. |
| `onShown()` / `onHidden()` | Called on the FX thread when the page becomes visible / is switched away. |
| `onDispose()` | Called on the FX thread when the page view is discarded (leaving the page, language switch, window close). |

Pages are not cached across visits (the launcher's own launch page is the exception). Store reusable
state in plugin fields or in `config()`, not in the view.

---

### 4. `PluginContext`

```java
public interface PluginContext {
    String pluginId();
    Path dataDir();
    Path pluginDataDir();
    String launcherVersion();
    String locale();
    Window ownerWindow();
    void openUrl(String url);
    void openFolder(Path path);
    byte[] httpGet(String url) throws Exception;
    String httpGetText(String url) throws Exception;
    PluginHttp http();
    ObjectMapper json();
    PluginLog log();
    PluginMessages messages();
    PluginServices services();
    PluginConfig config();
    Runnable addLocaleListener(Runnable listener);
    void runLater(Runnable action);
    void registerAuthProvider(AuthProvider provider);
    ThemeInfo theme();
    List<InstanceInfo> instances();
    Path selectedInstanceGameDir();
    String selectedInstanceLabel();
    void info(String title, String message);
    void error(String title, String message);
    void confirm(String title, String message, Runnable onConfirm);

    record InstanceInfo(String id, String mcVersion, Path gameDir) { }
    record ThemeInfo(String id, String name, boolean dark, String accent, Path background) { }
}
```

#### Methods

| Method | Notes |
|---|---|
| `pluginId()` | Same id the launcher used for `plugin-data`. |
| `dataDir()` | `<launcher>/data`; read by convention, not enforced. |
| `pluginDataDir()` | `<launcher>/data/plugin-data/<id>`; created for you, writable. |
| `launcherVersion()` | Launcher version string (for display/diagnostics). |
| `locale()` | `zh_CN`, `zh_TW`, `en_US`, or a custom language-pack id. |
| `ownerWindow()` | Main window, or `null`; may return a dialog window in some cases. |
| `openUrl(url)` | Opens the system browser. |
| `openFolder(path)` | Opens the system file manager. |
| `httpGet(url)` / `httpGetText(url)` | Blocking GET (20 s timeout), shared client/User-Agent/proxy; may throw. |
| `http()` | Full HTTP client; see §5. |
| `json()` | Shared Jackson `ObjectMapper`. |
| `log()` | Launcher log with `[plugin:<id>]` prefix; see §9. |
| `messages()` | Plugin language pack; see §7. |
| `services()` | Plugin-to-plugin registry; see §8. |
| `config()` | `data/plugin-data/<id>/config.properties`, auto-saved; see §6. |
| `addLocaleListener(listener)` | Runs on the FX thread when the UI language changes; returns a `Runnable` that unregisters. The launcher does **not** auto-remove it; keep the returned `Runnable` and call it in `onUnload()`. |
| `runLater(action)` | Runs on the FX thread; if already on it, runs immediately. |
| `registerAuthProvider(provider)` | See §10; the provider is unregistered on plugin unload. |
| `theme()` | Active theme pack; see `ThemeInfo` below. |
| `instances()` | All game instances; may scan the repository. |
| `selectedInstanceGameDir()` | Game dir of the remembered instance; falls back to the first available instance. |
| `selectedInstanceLabel()` | Same fallback; returns the instance id, or `""` when there is no instance. |
| `info/error/confirm` | Non-blocking launcher dialogs; `confirm` runs `onConfirm` only on OK. |

#### Records

| Type | Components | Notes |
|---|---|---|
| `InstanceInfo` | `String id`, `String mcVersion`, `Path gameDir` | One installed Java Edition instance. |
| `ThemeInfo` | `String id`, `String name`, `boolean dark`, `String accent`, `Path background` | `accent` may be `""`; `background` may be `null`. |

Threading: all methods may be called from any thread, but `runLater` is the only one that explicitly
hops to the FX thread. UI-producing callbacks (`pages()`, `createPanel()`, `createContent()`,
`onShown()`, `onHidden()`, `onDispose()`) run on the FX thread. `onLoad()` and `pages()` are called on
a background thread at startup and on the FX thread during Refresh.

---

### 5. `PluginHttp`

```java
public interface PluginHttp {
    Response request(Request request) throws Exception;

    default String getText(String url) throws Exception;
    default byte[] getBytes(String url) throws Exception;

    record Request(String method, String url, Map<String, String> headers, byte[] body,
                   Duration timeout) {
        public static Request get(String url);
        public static Request post(String url, byte[] body, String contentType);
        public Request withHeader(String name, String value);
        public Request withTimeout(Duration value);
    }

    record Response(int status, Map<String, List<String>> headers, byte[] body) {
        public boolean ok();
        public String text();
    }
}
```

- `Request.get` default timeout: 20 s; `Request.post` default: 30 s.
- The shared client sends `BriskLauncher/<version> (plugin)` as User-Agent unless you set your own.
- `Response.text()` decodes the body as UTF-8; `Response.body()` is the raw bytes.
- `getText` / `getBytes` throw `IOException` for non-2xx responses; `request` exposes the status.
- Do not call blocking HTTP on the FX thread.

---

### 6. `PluginConfig`

```java
public interface PluginConfig {
    String get(String key, String fallback);
    int getInt(String key, int fallback);
    long getLong(String key, long fallback);
    boolean getBoolean(String key, boolean fallback);
    void set(String key, String value);
    void save();
    Path file();
}
```

- Backed by `data/plugin-data/<id>/config.properties` (UTF-8).
- `set` writes immediately and atomically (temp file + replace); `null` removes the key.
- `getBoolean` returns `fallback` when the key is absent **or** contains an unrecognized value;
  recognized true values are `true`/`1`/`yes`, false values are `false`/`0`/`no` (case-insensitive).
- `save()` is only needed if you batch changes; `file()` returns the expected path even before it exists.

---

### 7. `PluginMessages`

```java
public interface PluginMessages {
    String t(String key);
    String t(String key, Object... args);
    boolean has(String key);
    List<String> locales();
}
```

- Files live at `i18n/messages.properties` and `i18n/messages_<tag>.properties` in the jar.
- Lookup order: current tag → base language (`zh` from `zh_TW`) → `en_US` → `en` → default
  `messages.properties` → the key itself.
- `t(key, args)` uses `MessageFormat` (`{0}`, `{1}`); a broken pattern returns the raw value.
- `locales()` lists the tags found in the jar (the default bundle is not a tag).

---

### 8. `PluginServices`

```java
public interface PluginServices {
    void provide(String name, Object service);
    <T> T find(String name, Class<T> type);
    <T> T require(String name, Class<T> type);
    boolean has(String name);
}
```

- Global namespace; prefix names (`myplugin.api`).
- `provide(name, null)` unregisters; later providers win.
- `find` returns `null` when absent or when the type does not match.
- `require` throws `IllegalStateException` when absent/mismatched.
- Services registered by a plugin are removed when that plugin unloads; look them up lazily
  (e.g. in `createPanel`/`onShown`), never cache another plugin's object across pages.
- `requires` in `plugin.json` controls load order; it does not make services type-safe.

---

### 9. `PluginLog`

```java
public interface PluginLog {
    void info(String message);
    void warn(String message);
    void error(String message, Throwable error);
}
```

- Every line is written through the launcher logger with a `[plugin:<id>]` prefix.
- `error` records the exception class + message (it does not print a full stack trace).
- Never log tokens, passwords, cookies or player personal data. The launcher's logger also redacts
  credential-looking messages, but do not rely on that.

---

### 10. Auth API (`com.brisklauncher.mcl.auth.api`)

> The account page's interactive login still uses the launcher's built-in Microsoft/offline/Yggdrasil
> providers. A plugin provider registered here affects the **launch/refresh** path (the last registered
> provider of that `type()` wins), not the login buttons. Its `displayName()`/`icon()` are not shown in
> the account UI. Built-in providers are restored after plugins unload.

#### `AccountType`

```java
public enum AccountType { MICROSOFT, OFFLINE, THIRD_PARTY }
```

#### `AuthProvider`

```java
public interface AuthProvider {
    AccountType type();
    default String id();          // default: type name lower-case
    default String displayName(); // default: type name
    default String icon();        // default: ""
    LoginResult login(LoginCredentials credentials);
    LoginResult refresh(StoredSession session);
    boolean validate(StoredSession session);
    GameSession toGameSession(StoredSession session);
}
```

Registration: `META-INF/services/com.brisklauncher.mcl.auth.api.AuthProvider` in the jar, or
`context.registerAuthProvider(...)` in `onLoad`.

#### `LoginCredentials`

```java
public record LoginCredentials(AccountType type, String username, char[] secret, URI endpoint) {
    public char[] secret(); // defensive clone
    public void wipe();
}
```

- `secret` is the password/token for the login; call `wipe()` after use.
- `endpoint` is the Yggdrasil authserver base URI for third-party accounts; may be `null`.

#### `LoginResult`

```java
public record LoginResult(boolean success, List<StoredSession> sessions, String errorMessage) {
    public LoginResult(boolean success, StoredSession session, String errorMessage);
    public static LoginResult ok(StoredSession session);
    public static LoginResult ok(List<StoredSession> sessions);
    public static LoginResult fail(String safeMessage);
    public StoredSession session(); // first session, or null
}
```

- `errorMessage` must be safe to show to the user; never include tokens/passwords.
- Multiple sessions are supported (one account may expose several characters).

#### `StoredSession`

```java
public record StoredSession(AccountType type, String uuid, String name,
                            char[] refreshToken, char[] accessToken,
                            long expiresAt, String endpoint) {
    public char[] refreshToken(); // defensive clone
    public char[] accessToken();  // defensive clone
    public boolean isExpired();
    public void wipe();
}
```

- `expiresAt` is epoch milliseconds.
- The launcher persists sessions in Windows Credential Manager; `StoredSession` is the in-memory view.
- Treat the token arrays as secrets; do not log them or keep them longer than needed.

#### `GameSession`

```java
public record GameSession(String username, String uuid, char[] accessToken,
                          String userType, String userProperties, String endpoint) {
    public char[] accessToken(); // defensive clone
    public boolean isThirdParty();
    public boolean isOffline();
    public void wipe();
}
```

- `endpoint` is non-null for third-party (Yggdrasil) sessions.
- `isOffline()` checks `userType == "legacy"`.
- `wipe()` clears the access token array when you are done with it.

#### `AuthException`

```java
public class AuthException extends RuntimeException {
    public AuthException(String safeMessage);
    public AuthException(String safeMessage, Throwable cause);
}
```

Use a message that is safe to show to the user; never put tokens/passwords in it.

---

### 11. `PluginApi`

```java
public final class PluginApi {
    public static final int VERSION = 2;
    public static final String MANIFEST_API = "apiVersion";
}
```

- `VERSION` is a compile-time constant; a plugin compiled against API 2 does not automatically see a
  newer runtime value.
- `plugin.json`'s `apiVersion` is the runtime gate: a launcher whose `VERSION` is lower refuses to load
  the plugin with a "needs a newer launcher" error.

---

### 12. Threading, errors and cleanup

| Situation | Contract |
|---|---|
| `onLoad()` | Startup: background thread. Refresh: FX thread. Do not create JavaFX nodes. |
| `pages()` | Same thread as `onLoad`; do not create JavaFX nodes. |
| `createPanel()` / `createContent()` | FX thread; build nodes here. |
| `onShown()` / `onHidden()` / `onDispose()` | FX thread. |
| `onUnload()` | Called once per plugin load by `unloadAll()`; stop threads, close files, unregister global listeners. |
| Leaving a page | Calls `LauncherPage.onDispose()` (for navigation pages) or discards the Tools panel; does **not** call `onUnload()`. |
| Blocking work | Never block the FX thread; use your own thread/executor and `context.runLater(...)` for UI. |
| Exceptions in callbacks | The launcher catches and logs plugin failures where it can; do not rely on it for cleanup — use `try/finally`. |
| Refresh | Rebuilds the launcher toolbar/navigation pages and reloads every external plugin. |

---

### 13. Self-tests

After `gradle installDist` (or with the Gradle build output on the classpath):

```bat
java -cp "build\classes\java\main;build\install\brisk-launcher\lib\*" ^
     com.brisklauncher.mcl.plugin.PluginSelfTest examples\hello-plugin\hello-plugin.jar
```

The sample plugin and theme are under `examples/`. See the [Plugin Development Guide](PLUGIN_DEV.md)
for the tutorial, `plugin.json` walkthrough, packaging, security model and troubleshooting.

---

## 简体中文

> 这是第三方插件 API 的完整签名参考。教学、生命周期、示例与安全说明在
> [插件开发指南](PLUGIN_DEV.md#简体中文)；本文件是查类型、方法与默认值的对照表。
>
> API 版本：**2**（`com.brisklauncher.mcl.plugin.api.PluginApi.VERSION`）。
> 稳定包：`com.brisklauncher.mcl.plugin.api` 与 `com.brisklauncher.mcl.auth.api`。
> 启动器 module 也历史性地 export 了 `com.brisklauncher.mcl` 与
> `com.brisklauncher.mcl.i18n`；那些**不在**兼容承诺内。

**没有权限声明清单。** 启动器只决定「这个插件要不要加载」（disabled 名单、SHA-256 信任记录、
一次性风险提示）；一旦加载，插件就与启动器拥有相同的 Windows 用户权限。这个 API 没有
Android 式的权限模型，也不是沙箱。

---

### 1. `plugin.json`

文件必须在 **jar 根目录**（不是 `META-INF/`），UTF-8，且读取时不会执行插件代码。

| 字段 | 类型 | 必需 | 默认 | 说明 |
|---|---|---|---|---|
| `id` | string | 建议填 | jar 文件名 | 会清洗成 `[A-Za-z0-9._-]`；用于 `data\plugin-data\<id>\` 与 `context.pluginId()`。外部 jar 以 manifest 的 id 为准，`LauncherPlugin.id()` 不参与。 |
| `class` | string | **是** | — | 主类；必须实现 `LauncherPlugin`、是 `public`，并有 public 无参构造器。 |
| `name` | string | 否 | jar 文件名 | `LauncherPlugin.name()` 为空时的备用显示名。 |
| `description` | string | 否 | `""` | 工具页列表的一行说明。 |
| `version` | string | 否 | `""` | `LauncherPlugin.version()` 为空时的备用版本。 |
| `author` | string | 否 | `""` | 工具页显示的作者信息。 |
| `homepage` | string | 否 | `""` | 仅作说明。 |
| `icon` | string | 否 | `"🧩"` | `LauncherPlugin.icon()` 的备用 emoji。 |
| `apiVersion` | integer | 否 | `2` | 声明最低 API。启动器的 `PluginApi.VERSION < apiVersion` 时拒绝加载。 |
| `requires` | array of strings | 否 | `[]` | 必须先加载的插件 id。缺少／被停用／循环依赖会产生可见的加载错误。 |

未知字段会被忽略。没有 `permissions` 字段；请看上面的警告。

---

### 2. `LauncherPlugin`

```java
package com.brisklauncher.mcl.plugin.api;

public interface LauncherPlugin {
    String id();
    String name();
    default String description() { return ""; }
    default String icon() { return "🧩"; }
    default String version() { return ""; }
    default void onLoad(PluginContext context) { }
    default List<LauncherPage> pages() { return List.of(); }
    default Node createPanel(PluginContext context) { return null; }
    default void onUnload() { }
}
```

| 成员 | 契约 |
|---|---|
| `id()` | 稳定的插件 id。外部 jar 请与 `plugin.json` 一致；启动器用的是 manifest。 |
| `name()` | 显示名。加载时读一次；只有重新加载（Refresh）才会再读。 |
| `description()` | 加载时读一次。 |
| `icon()` | Emoji；加载时读一次。 |
| `version()` | 加载时读一次；空字符串时回退到 `plugin.json`。 |
| `onLoad(context)` | 每个插件实例调用一次。启动时在后台线程；Refresh 时在 FX 线程。不要在这里创建 JavaFX 节点。 |
| `pages()` | 加载时调用一次，与 `onLoad` 同一线程。返回导航页；不要在这里创建 JavaFX 节点。 |
| `createPanel(context)` | 工具页面板每次创建／重建时在 FX 线程调用。返回 `null` 表示「这个插件没有工具面板」。 |
| `onUnload()` | 由 `PluginManager.unloadAll()` 在停用／移除／Refresh／启动器退出时调用一次。**离开工具页不会调用**。在这里停线程、关文件。 |

只提供导航页的插件不要覆写 `createPanel`；启动器会检测到，并显示提示而不是空白面板。

---

### 3. `LauncherPage`

```java
public interface LauncherPage {
    String id();
    String title();
    default String icon() { return ""; }
    Node createContent(PluginContext context);
    default void onShown() { }
    default void onHidden() { }
    default void onDispose() { }
}
```

| 成员 | 契约 |
|---|---|
| `id()` | `[A-Za-z0-9._-]`；内部页面 key 是 `p:<pluginId>:<pageId>`。 |
| `title()` | 顶栏文字；切换语言重建 UI 时会重新读取。 |
| `icon()` | 可选 emoji 前缀。 |
| `createContent(context)` | 每次创建页面视图时在 FX 线程调用。离开再回来会创建新视图，所以可能再跑一次。 |
| `onShown()` / `onHidden()` | 页面变为可见／被切走时在 FX 线程调用。 |
| `onDispose()` | 页面视图被丢弃时在 FX 线程调用（离开页面、切换语言、关窗）。 |

页面不跨访问缓存（启动器自己的启动页除外）。可复用状态放插件字段或 `config()`，不要放在视图里。

---

### 4. `PluginContext`

```java
public interface PluginContext {
    String pluginId();
    Path dataDir();
    Path pluginDataDir();
    String launcherVersion();
    String locale();
    Window ownerWindow();
    void openUrl(String url);
    void openFolder(Path path);
    byte[] httpGet(String url) throws Exception;
    String httpGetText(String url) throws Exception;
    PluginHttp http();
    ObjectMapper json();
    PluginLog log();
    PluginMessages messages();
    PluginServices services();
    PluginConfig config();
    Runnable addLocaleListener(Runnable listener);
    void runLater(Runnable action);
    void registerAuthProvider(AuthProvider provider);
    ThemeInfo theme();
    List<InstanceInfo> instances();
    Path selectedInstanceGameDir();
    String selectedInstanceLabel();
    void info(String title, String message);
    void error(String title, String message);
    void confirm(String title, String message, Runnable onConfirm);

    record InstanceInfo(String id, String mcVersion, Path gameDir) { }
    record ThemeInfo(String id, String name, boolean dark, String accent, Path background) { }
}
```

#### 方法

| 方法 | 说明 |
|---|---|
| `pluginId()` | 启动器用于 `plugin-data` 的同一个 id。 |
| `dataDir()` | `<启动器>/data`；约定只读，没有强制。 |
| `pluginDataDir()` | `<启动器>/data/plugin-data/<id>`；已创建，可写。 |
| `launcherVersion()` | 启动器版本字符串（显示／诊断用）。 |
| `locale()` | `zh_CN`、`zh_TW`、`en_US`，或自定义语言包 id。 |
| `ownerWindow()` | 主窗口，或 `null`；某些情况下可能返回对话框窗口。 |
| `openUrl(url)` | 用系统浏览器打开。 |
| `openFolder(path)` | 用系统文件管理器打开。 |
| `httpGet(url)` / `httpGetText(url)` | 同步 GET（20 秒超时），共用 client／User-Agent／代理；可能抛异常。 |
| `http()` | 完整 HTTP client；见 §5。 |
| `json()` | 共用的 Jackson `ObjectMapper`。 |
| `log()` | 启动器日志，带 `[plugin:<id>]` 前缀；见 §9。 |
| `messages()` | 插件语言包；见 §7。 |
| `services()` | 插件之间的服务登记处；见 §8。 |
| `config()` | `data/plugin-data/<id>/config.properties`，自动落盘；见 §6。 |
| `addLocaleListener(listener)` | UI 语言切换时在 FX 线程执行；返回可取消注册的 `Runnable`。启动器**不会**自动移除；请保存返回值并在 `onUnload()` 调用。 |
| `runLater(action)` | 排到 FX 线程；已在 FX 上会立即执行。 |
| `registerAuthProvider(provider)` | 见 §10；插件卸载时自动注销。 |
| `theme()` | 当前主题包；见下面的 `ThemeInfo`。 |
| `instances()` | 所有游戏实例；可能会扫描 repository。 |
| `selectedInstanceGameDir()` | 记住的实例游戏目录；找不到时回退到第一个可用实例。 |
| `selectedInstanceLabel()` | 同样的回退规则；返回实例 id，没有实例时返回 `""`。 |
| `info/error/confirm` | 非阻塞的启动器对话框；`confirm` 只有按下确定才执行 `onConfirm`。 |

#### Record 类型

| 类型 | 组件 | 说明 |
|---|---|---|
| `InstanceInfo` | `String id`、`String mcVersion`、`Path gameDir` | 一个已安装的 Java 版实例。 |
| `ThemeInfo` | `String id`、`String name`、`boolean dark`、`String accent`、`Path background` | `accent` 可能是 `""`；`background` 可能是 `null`。 |

线程：所有方法都可以从任意线程调用，但 `runLater` 是唯一明确跳回 FX 的方法。
会产生 UI 的回调（`pages()`、`createPanel()`、`createContent()`、`onShown()`、`onHidden()`、
`onDispose()`）在 FX 线程；`onLoad()` 与 `pages()` 在启动时是后台线程，Refresh 时是 FX。

---

### 5. `PluginHttp`

```java
public interface PluginHttp {
    Response request(Request request) throws Exception;

    default String getText(String url) throws Exception;
    default byte[] getBytes(String url) throws Exception;

    record Request(String method, String url, Map<String, String> headers, byte[] body,
                   Duration timeout) {
        public static Request get(String url);
        public static Request post(String url, byte[] body, String contentType);
        public Request withHeader(String name, String value);
        public Request withTimeout(Duration value);
    }

    record Response(int status, Map<String, List<String>> headers, byte[] body) {
        public boolean ok();
        public String text();
    }
}
```

- `Request.get` 默认超时 20 秒；`Request.post` 默认 30 秒。
- 共用 client 默认送 `BriskLauncher/<version> (plugin)` 的 User-Agent，除非你自己设置。
- `Response.text()` 以 UTF-8 解码 body；`Response.body()` 是原始 bytes。
- `getText` / `getBytes` 对非 2xx 响应抛 `IOException`；`request` 则让你自己看 status。
- 不要在 FX 线程做阻塞 HTTP。

---

### 6. `PluginConfig`

```java
public interface PluginConfig {
    String get(String key, String fallback);
    int getInt(String key, int fallback);
    long getLong(String key, long fallback);
    boolean getBoolean(String key, boolean fallback);
    void set(String key, String value);
    void save();
    Path file();
}
```

- 背后文件是 `data/plugin-data/<id>/config.properties`（UTF-8）。
- `set` 会立刻落盘并以原子替换（临时文件 + 替换）；`null` 表示删除该键。
- `getBoolean` 在键不存在**或**值无法识别时返回 `fallback`；可识别的真值为
  `true`/`1`/`yes`，假值为 `false`/`0`/`no`（大小写不敏感）。
- 只有批量写入时才需要 `save()`；`file()` 在文件尚未创建前也会返回预期路径。

---

### 7. `PluginMessages`

```java
public interface PluginMessages {
    String t(String key);
    String t(String key, Object... args);
    boolean has(String key);
    List<String> locales();
}
```

- 文件在 jar 内的 `i18n/messages.properties` 与 `i18n/messages_<tag>.properties`。
- 查找顺序：当前 tag → 主语言（`zh_TW` → `zh`）→ `en_US` → `en` → 默认
  `messages.properties` → 键名本身。
- `t(key, args)` 使用 `MessageFormat`（`{0}`、`{1}`）；pattern 坏掉时返回原字符串。
- `locales()` 列出 jar 内找到的 tag（默认 bundle 不算 tag）。

---

### 8. `PluginServices`

```java
public interface PluginServices {
    void provide(String name, Object service);
    <T> T find(String name, Class<T> type);
    <T> T require(String name, Class<T> type);
    boolean has(String name);
}
```

- 全局命名空间；请加前缀（`myplugin.api`）。
- `provide(name, null)` 取消登记；后登记的覆盖先登记的。
- `find` 找不到或类型不符时返回 `null`。
- `require` 找不到／类型不符时抛 `IllegalStateException`。
- 插件登记的服务会在该插件卸载时清掉；请延迟查找（例如在 `createPanel`/`onShown`），
  不要把别的插件对象缓存过页面。
- `plugin.json` 的 `requires` 只控制加载顺序，不提供服务类型安全。

---

### 9. `PluginLog`

```java
public interface PluginLog {
    void info(String message);
    void warn(String message);
    void error(String message, Throwable error);
}
```

- 每一行都经启动器日志，带 `[plugin:<id>]` 前缀。
- `error` 记录异常类 + 消息（不会打印完整堆栈）。
- 绝不要记录令牌、密码、Cookie 或玩家个人数据。启动器日志也会过滤疑似凭据内容，但不要依赖它。

---

### 10. 认证 API（`com.brisklauncher.mcl.auth.api`）

> 账号页的交互式登录仍使用启动器内置的 Microsoft／离线／Yggdrasil 实现。这里注册的插件
> provider 只影响**启动／刷新**路径（同一个 `type()` 最后注册者赢），不是登录按钮；
> `displayName()`/`icon()` 也不会显示在账号 UI。插件卸载后内置 provider 会补回。

#### `AccountType`

```java
public enum AccountType { MICROSOFT, OFFLINE, THIRD_PARTY }
```

#### `AuthProvider`

```java
public interface AuthProvider {
    AccountType type();
    default String id();          // 默认：类型名小写
    default String displayName(); // 默认：类型名
    default String icon();        // 默认：""
    LoginResult login(LoginCredentials credentials);
    LoginResult refresh(StoredSession session);
    boolean validate(StoredSession session);
    GameSession toGameSession(StoredSession session);
}
```

注册方式：jar 内放 `META-INF/services/com.brisklauncher.mcl.auth.api.AuthProvider`，或在
`onLoad` 调用 `context.registerAuthProvider(...)`。

#### `LoginCredentials`

```java
public record LoginCredentials(AccountType type, String username, char[] secret, URI endpoint) {
    public char[] secret(); // 防御性副本
    public void wipe();
}
```

- `secret` 是登录用的密码／令牌；用完请调用 `wipe()`。
- `endpoint` 是第三方账号的 Yggdrasil authserver base URI；可能是 `null`。

#### `LoginResult`

```java
public record LoginResult(boolean success, List<StoredSession> sessions, String errorMessage) {
    public LoginResult(boolean success, StoredSession session, String errorMessage);
    public static LoginResult ok(StoredSession session);
    public static LoginResult ok(List<StoredSession> sessions);
    public static LoginResult fail(String safeMessage);
    public StoredSession session(); // 第一个 session，没有就 null
}
```

- `errorMessage` 必须是可安全显示给用户的消息；不要包含令牌／密码。
- 支持多个 session（一个账号可能有多个角色）。

#### `StoredSession`

```java
public record StoredSession(AccountType type, String uuid, String name,
                            char[] refreshToken, char[] accessToken,
                            long expiresAt, String endpoint) {
    public char[] refreshToken(); // 防御性副本
    public char[] accessToken();  // 防御性副本
    public boolean isExpired();
    public void wipe();
}
```

- `expiresAt` 是 epoch milliseconds。
- 启动器把 session 存在 Windows 凭据管理器；`StoredSession` 是内存中的视图。
- 把令牌数组当秘密处理；不要记录，也不要保留超过必要时间。

#### `GameSession`

```java
public record GameSession(String username, String uuid, char[] accessToken,
                          String userType, String userProperties, String endpoint) {
    public char[] accessToken(); // 防御性副本
    public boolean isThirdParty();
    public boolean isOffline();
    public void wipe();
}
```

- 第三方（Yggdrasil）session 的 `endpoint` 非 null。
- `isOffline()` 判断 `userType == "legacy"`。
- 用完时调用 `wipe()` 清掉 access token 数组。

#### `AuthException`

```java
public class AuthException extends RuntimeException {
    public AuthException(String safeMessage);
    public AuthException(String safeMessage, Throwable cause);
}
```

消息必须可安全显示给用户；不要把令牌／密码放进去。

---

### 11. `PluginApi`

```java
public final class PluginApi {
    public static final int VERSION = 2;
    public static final String MANIFEST_API = "apiVersion";
}
```

- `VERSION` 是编译期常量；用 API 2 编译的插件不会自动看到较新的运行期值。
- `plugin.json` 的 `apiVersion` 是运行期闸门：启动器的 `VERSION` 较低时拒绝加载，
  并显示「需要更新版本的启动器」。

---

### 12. 线程、错误与清理

| 情况 | 契约 |
|---|---|
| `onLoad()` | 启动：后台线程。Refresh：FX 线程。不要创建 JavaFX 节点。 |
| `pages()` | 与 `onLoad` 同一线程；不要创建 JavaFX 节点。 |
| `createPanel()` / `createContent()` | FX 线程；在这里创建节点。 |
| `onShown()` / `onHidden()` / `onDispose()` | FX 线程。 |
| `onUnload()` | 每次插件加载由 `unloadAll()` 调用一次；停线程、关文件、注销全局监听。 |
| 离开页面 | 导航页会收到 `LauncherPage.onDispose()`；工具页只丢弃面板；**不会**调用 `onUnload()`。 |
| 阻塞工作 | 不要阻塞 FX 线程；用自己的线程／executor，UI 更新用 `context.runLater(...)`。 |
| 回调中的异常 | 启动器会尽量拦截并记录；不要依赖它做清理，请用 `try/finally`。 |
| Refresh | 重建启动器顶栏／导航页，并重新加载所有外部插件。 |

---

### 13. 自检

先 `gradle installDist`（或把 Gradle 构建输出放进 classpath）：

```bat
java -cp "build\classes\java\main;build\install\brisk-launcher\lib\*" ^
     com.brisklauncher.mcl.plugin.PluginSelfTest examples\hello-plugin\hello-plugin.jar
```

示例插件与主题在 `examples/`。教学、`plugin.json` 逐步说明、打包、安全模型与排查请看
[插件开发指南](PLUGIN_DEV.md#简体中文)。

---

## 繁體中文

> 這是第三方外掛 API 的完整簽名參考。教學、生命週期、範例與安全說明在
> [外掛開發指南](PLUGIN_DEV.md#繁體中文)；本文件是查型別、方法與預設值的對照表。
>
> API 版本：**2**（`com.brisklauncher.mcl.plugin.api.PluginApi.VERSION`）。
> 穩定套件：`com.brisklauncher.mcl.plugin.api` 與 `com.brisklauncher.mcl.auth.api`。
> 啟動器 module 也歷史性地 export 了 `com.brisklauncher.mcl` 與
> `com.brisklauncher.mcl.i18n`；那些**不在**相容承諾內。

**沒有權限宣告清單。** 啟動器只決定「這個外掛要不要載入」（disabled 名單、SHA-256 信任記錄、
一次性風險提示）；一旦載入，外掛就與啟動器擁有相同的 Windows 使用者權限。這個 API 沒有
Android 式的權限模型，也不是沙箱。

---

### 1. `plugin.json`

檔案必須在 **jar 根目錄**（不是 `META-INF/`），UTF-8，且讀取時不會執行外掛程式碼。

| 欄位 | 型別 | 必需 | 預設 | 說明 |
|---|---|---|---|---|
| `id` | string | 建議填 | jar 檔名 | 會清洗成 `[A-Za-z0-9._-]`；用於 `data\plugin-data\<id>\` 與 `context.pluginId()`。外部 jar 以 manifest 的 id 為準，`LauncherPlugin.id()` 不參與。 |
| `class` | string | **是** | — | 主類；必須實作 `LauncherPlugin`、是 `public`，並有 public 無參建構子。 |
| `name` | string | 否 | jar 檔名 | `LauncherPlugin.name()` 為空時的備用顯示名。 |
| `description` | string | 否 | `""` | 工具頁清單的一行說明。 |
| `version` | string | 否 | `""` | `LauncherPlugin.version()` 為空時的備用版本。 |
| `author` | string | 否 | `""` | 工具頁顯示的作者資訊。 |
| `homepage` | string | 否 | `""` | 僅作說明。 |
| `icon` | string | 否 | `"🧩"` | `LauncherPlugin.icon()` 的備用 emoji。 |
| `apiVersion` | integer | 否 | `2` | 宣告最低 API。啟動器的 `PluginApi.VERSION < apiVersion` 時拒絕載入。 |
| `requires` | array of strings | 否 | `[]` | 必須先載入的外掛 id。缺少／被停用／循環依賴會產生可見的載入錯誤。 |

未知欄位會被忽略。沒有 `permissions` 欄位；請看上面的警告。

---

### 2. `LauncherPlugin`

```java
package com.brisklauncher.mcl.plugin.api;

public interface LauncherPlugin {
    String id();
    String name();
    default String description() { return ""; }
    default String icon() { return "🧩"; }
    default String version() { return ""; }
    default void onLoad(PluginContext context) { }
    default List<LauncherPage> pages() { return List.of(); }
    default Node createPanel(PluginContext context) { return null; }
    default void onUnload() { }
}
```

| 成員 | 契約 |
|---|---|
| `id()` | 穩定的外掛 id。外部 jar 請與 `plugin.json` 一致；啟動器用的是 manifest。 |
| `name()` | 顯示名。載入時讀一次；只有重新載入（Refresh）才會再讀。 |
| `description()` | 載入時讀一次。 |
| `icon()` | Emoji；載入時讀一次。 |
| `version()` | 載入時讀一次；空字串時回退到 `plugin.json`。 |
| `onLoad(context)` | 每個外掛實例呼叫一次。啟動時在背景執行緒；Refresh 時在 FX 執行緒。不要在這裡建立 JavaFX 節點。 |
| `pages()` | 載入時呼叫一次，與 `onLoad` 同一執行緒。回傳導覽頁；不要在這裡建立 JavaFX 節點。 |
| `createPanel(context)` | 工具頁面板每次建立／重建時在 FX 執行緒呼叫。回傳 `null` 表示「這個外掛沒有工具面板」。 |
| `onUnload()` | 由 `PluginManager.unloadAll()` 在停用／移除／Refresh／啟動器結束時呼叫一次。**離開工具頁不會呼叫**。在這裡停執行緒、關檔案。 |

只提供導覽頁的外掛不要覆寫 `createPanel`；啟動器會偵測到，並顯示提示而不是空白面板。

---

### 3. `LauncherPage`

```java
public interface LauncherPage {
    String id();
    String title();
    default String icon() { return ""; }
    Node createContent(PluginContext context);
    default void onShown() { }
    default void onHidden() { }
    default void onDispose() { }
}
```

| 成員 | 契約 |
|---|---|
| `id()` | `[A-Za-z0-9._-]`；內部頁面 key 是 `p:<pluginId>:<pageId>`。 |
| `title()` | 頂欄文字；切換語言重建 UI 時會重新讀取。 |
| `icon()` | 可選 emoji 前綴。 |
| `createContent(context)` | 每次建立頁面視圖時在 FX 執行緒呼叫。離開再回來會建立新視圖，所以可能再跑一次。 |
| `onShown()` / `onHidden()` | 頁面變成可見／被切走時在 FX 執行緒呼叫。 |
| `onDispose()` | 頁面視圖被丟棄時在 FX 執行緒呼叫（離開頁面、切換語言、關窗）。 |

頁面不跨訪問快取（啟動器自己的啟動頁除外）。可重用狀態放外掛欄位或 `config()`，不要放在視圖裡。

---

### 4. `PluginContext`

```java
public interface PluginContext {
    String pluginId();
    Path dataDir();
    Path pluginDataDir();
    String launcherVersion();
    String locale();
    Window ownerWindow();
    void openUrl(String url);
    void openFolder(Path path);
    byte[] httpGet(String url) throws Exception;
    String httpGetText(String url) throws Exception;
    PluginHttp http();
    ObjectMapper json();
    PluginLog log();
    PluginMessages messages();
    PluginServices services();
    PluginConfig config();
    Runnable addLocaleListener(Runnable listener);
    void runLater(Runnable action);
    void registerAuthProvider(AuthProvider provider);
    ThemeInfo theme();
    List<InstanceInfo> instances();
    Path selectedInstanceGameDir();
    String selectedInstanceLabel();
    void info(String title, String message);
    void error(String title, String message);
    void confirm(String title, String message, Runnable onConfirm);

    record InstanceInfo(String id, String mcVersion, Path gameDir) { }
    record ThemeInfo(String id, String name, boolean dark, String accent, Path background) { }
}
```

#### 方法

| 方法 | 說明 |
|---|---|
| `pluginId()` | 啟動器用於 `plugin-data` 的同一個 id。 |
| `dataDir()` | `<啟動器>/data`；約定唯讀，沒有強制。 |
| `pluginDataDir()` | `<啟動器>/data/plugin-data/<id>`；已建立，可寫。 |
| `launcherVersion()` | 啟動器版本字串（顯示／診斷用）。 |
| `locale()` | `zh_CN`、`zh_TW`、`en_US`，或自訂語言包 id。 |
| `ownerWindow()` | 主視窗，或 `null`；某些情況下可能回傳對話框視窗。 |
| `openUrl(url)` | 用系統瀏覽器開啟。 |
| `openFolder(path)` | 用系統檔案管理器開啟。 |
| `httpGet(url)` / `httpGetText(url)` | 同步 GET（20 秒逾時），共用 client／User-Agent／代理；可能拋例外。 |
| `http()` | 完整 HTTP client；見 §5。 |
| `json()` | 共用的 Jackson `ObjectMapper`。 |
| `log()` | 啟動器日誌，帶 `[plugin:<id>]` 前綴；見 §9。 |
| `messages()` | 外掛語言包；見 §7。 |
| `services()` | 外掛之間的服務登記處；見 §8。 |
| `config()` | `data/plugin-data/<id>/config.properties`，自動落盤；見 §6。 |
| `addLocaleListener(listener)` | UI 語言切換時在 FX 執行緒執行；回傳可取消註冊的 `Runnable`。啟動器**不會**自動移除；請保存回傳值並在 `onUnload()` 呼叫。 |
| `runLater(action)` | 排到 FX 執行緒；已在 FX 上會立即執行。 |
| `registerAuthProvider(provider)` | 見 §10；外掛卸載時自動註銷。 |
| `theme()` | 目前主題包；見下面的 `ThemeInfo`。 |
| `instances()` | 所有遊戲實例；可能會掃描 repository。 |
| `selectedInstanceGameDir()` | 記住的實例遊戲目錄；找不到時回退到第一個可用實例。 |
| `selectedInstanceLabel()` | 同樣的回退規則；回傳實例 id，沒有實例時回傳 `""`。 |
| `info/error/confirm` | 非阻塞的啟動器對話框；`confirm` 只有按下確定才執行 `onConfirm`。 |

#### Record 型別

| 型別 | 元件 | 說明 |
|---|---|---|
| `InstanceInfo` | `String id`、`String mcVersion`、`Path gameDir` | 一個已安裝的 Java 版實例。 |
| `ThemeInfo` | `String id`、`String name`、`boolean dark`、`String accent`、`Path background` | `accent` 可能是 `""`；`background` 可能是 `null`。 |

執行緒：所有方法都可以從任意執行緒呼叫，但 `runLater` 是唯一明確跳回 FX 的方法。
會產生 UI 的回呼（`pages()`、`createPanel()`、`createContent()`、`onShown()`、`onHidden()`、
`onDispose()`）在 FX 執行緒；`onLoad()` 與 `pages()` 在啟動時是背景執行緒，Refresh 時是 FX。

---

### 5. `PluginHttp`

```java
public interface PluginHttp {
    Response request(Request request) throws Exception;

    default String getText(String url) throws Exception;
    default byte[] getBytes(String url) throws Exception;

    record Request(String method, String url, Map<String, String> headers, byte[] body,
                   Duration timeout) {
        public static Request get(String url);
        public static Request post(String url, byte[] body, String contentType);
        public Request withHeader(String name, String value);
        public Request withTimeout(Duration value);
    }

    record Response(int status, Map<String, List<String>> headers, byte[] body) {
        public boolean ok();
        public String text();
    }
}
```

- `Request.get` 預設逾時 20 秒；`Request.post` 預設 30 秒。
- 共用 client 預設送 `BriskLauncher/<version> (plugin)` 的 User-Agent，除非你自己設定。
- `Response.text()` 以 UTF-8 解碼 body；`Response.body()` 是原始 bytes。
- `getText` / `getBytes` 對非 2xx 回應拋 `IOException`；`request` 則讓你自己看 status。
- 不要在 FX 執行緒做阻塞 HTTP。

---

### 6. `PluginConfig`

```java
public interface PluginConfig {
    String get(String key, String fallback);
    int getInt(String key, int fallback);
    long getLong(String key, long fallback);
    boolean getBoolean(String key, boolean fallback);
    void set(String key, String value);
    void save();
    Path file();
}
```

- 背後檔案是 `data/plugin-data/<id>/config.properties`（UTF-8）。
- `set` 會立刻落盤並以原子替換（暫存檔 + 取代）；`null` 表示刪除該鍵。
- `getBoolean` 在鍵不存在**或**值無法辨識時回傳 `fallback`；可辨識的真值為
  `true`/`1`/`yes`，假值為 `false`/`0`/`no`（大小寫不敏感）。
- 只有批次寫入時才需要 `save()`；`file()` 在檔案尚未建立前也會回傳預期路徑。

---

### 7. `PluginMessages`

```java
public interface PluginMessages {
    String t(String key);
    String t(String key, Object... args);
    boolean has(String key);
    List<String> locales();
}
```

- 檔案在 jar 內的 `i18n/messages.properties` 與 `i18n/messages_<tag>.properties`。
- 查找順序：目前 tag → 主語言（`zh_TW` → `zh`）→ `en_US` → `en` → 預設
  `messages.properties` → 鍵名本身。
- `t(key, args)` 使用 `MessageFormat`（`{0}`、`{1}`）；pattern 壞掉時回傳原字串。
- `locales()` 列出 jar 內找到的 tag（預設 bundle 不算 tag）。

---

### 8. `PluginServices`

```java
public interface PluginServices {
    void provide(String name, Object service);
    <T> T find(String name, Class<T> type);
    <T> T require(String name, Class<T> type);
    boolean has(String name);
}
```

- 全域命名空間；請加前綴（`myplugin.api`）。
- `provide(name, null)` 取消登記；後登記的覆蓋先登記的。
- `find` 找不到或型別不符時回傳 `null`。
- `require` 找不到／型別不符時拋 `IllegalStateException`。
- 外掛登記的服務會在該外掛卸載時清掉；請延遲查找（例如在 `createPanel`/`onShown`），
  不要把別的外掛物件快取過頁面。
- `plugin.json` 的 `requires` 只控制載入順序，不提供服務型別安全。

---

### 9. `PluginLog`

```java
public interface PluginLog {
    void info(String message);
    void warn(String message);
    void error(String message, Throwable error);
}
```

- 每一行都經啟動器日誌，帶 `[plugin:<id>]` 前綴。
- `error` 記錄例外類別 + 訊息（不會印完整堆疊）。
- 絕不要記錄權杖、密碼、Cookie 或玩家個資。啟動器日誌也會過濾疑似憑證內容，但不要依賴它。

---

### 10. 認證 API（`com.brisklauncher.mcl.auth.api`）

> 帳號頁的互動式登入仍使用啟動器內建的 Microsoft／離線／Yggdrasil 實作。這裡註冊的外掛
> provider 只影響**啟動／刷新**路徑（同一個 `type()` 最後註冊者贏），不是登入按鈕；
> `displayName()`/`icon()` 也不會顯示在帳號 UI。外掛卸載後內建 provider 會補回。

#### `AccountType`

```java
public enum AccountType { MICROSOFT, OFFLINE, THIRD_PARTY }
```

#### `AuthProvider`

```java
public interface AuthProvider {
    AccountType type();
    default String id();          // 預設：型別名小寫
    default String displayName(); // 預設：型別名
    default String icon();        // 預設：""
    LoginResult login(LoginCredentials credentials);
    LoginResult refresh(StoredSession session);
    boolean validate(StoredSession session);
    GameSession toGameSession(StoredSession session);
}
```

註冊方式：jar 內放 `META-INF/services/com.brisklauncher.mcl.auth.api.AuthProvider`，或在
`onLoad` 呼叫 `context.registerAuthProvider(...)`。

#### `LoginCredentials`

```java
public record LoginCredentials(AccountType type, String username, char[] secret, URI endpoint) {
    public char[] secret(); // 防禦性複本
    public void wipe();
}
```

- `secret` 是登入用的密碼／權杖；用完請呼叫 `wipe()`。
- `endpoint` 是第三方帳號的 Yggdrasil authserver base URI；可能是 `null`。

#### `LoginResult`

```java
public record LoginResult(boolean success, List<StoredSession> sessions, String errorMessage) {
    public LoginResult(boolean success, StoredSession session, String errorMessage);
    public static LoginResult ok(StoredSession session);
    public static LoginResult ok(List<StoredSession> sessions);
    public static LoginResult fail(String safeMessage);
    public StoredSession session(); // 第一個 session，沒有就 null
}
```

- `errorMessage` 必須是可安全顯示給使用者的訊息；不要包含權杖／密碼。
- 支援多個 session（一個帳號可能有多個角色）。

#### `StoredSession`

```java
public record StoredSession(AccountType type, String uuid, String name,
                            char[] refreshToken, char[] accessToken,
                            long expiresAt, String endpoint) {
    public char[] refreshToken(); // 防禦性複本
    public char[] accessToken();  // 防禦性複本
    public boolean isExpired();
    public void wipe();
}
```

- `expiresAt` 是 epoch milliseconds。
- 啟動器把 session 存在 Windows 認證管理員；`StoredSession` 是記憶體中的視圖。
- 把權杖陣列當秘密處理；不要記錄，也不要保留超過必要時間。

#### `GameSession`

```java
public record GameSession(String username, String uuid, char[] accessToken,
                          String userType, String userProperties, String endpoint) {
    public char[] accessToken(); // 防禦性複本
    public boolean isThirdParty();
    public boolean isOffline();
    public void wipe();
}
```

- 第三方（Yggdrasil）session 的 `endpoint` 非 null。
- `isOffline()` 判斷 `userType == "legacy"`。
- 用完時呼叫 `wipe()` 清掉 access token 陣列。

#### `AuthException`

```java
public class AuthException extends RuntimeException {
    public AuthException(String safeMessage);
    public AuthException(String safeMessage, Throwable cause);
}
```

訊息必須可安全顯示給使用者；不要把權杖／密碼放進去。

---

### 11. `PluginApi`

```java
public final class PluginApi {
    public static final int VERSION = 2;
    public static final String MANIFEST_API = "apiVersion";
}
```

- `VERSION` 是編譯期常數；用 API 2 編譯的外掛不會自動看到較新的執行期值。
- `plugin.json` 的 `apiVersion` 是執行期閘門：啟動器的 `VERSION` 較低時拒絕載入，
  並顯示「需要更新版本的啟動器」。

---

### 12. 執行緒、錯誤與清理

| 情況 | 契約 |
|---|---|
| `onLoad()` | 啟動：背景執行緒。Refresh：FX 執行緒。不要建立 JavaFX 節點。 |
| `pages()` | 與 `onLoad` 同一執行緒；不要建立 JavaFX 節點。 |
| `createPanel()` / `createContent()` | FX 執行緒；在這裡建立節點。 |
| `onShown()` / `onHidden()` / `onDispose()` | FX 執行緒。 |
| `onUnload()` | 每次外掛載入由 `unloadAll()` 呼叫一次；停執行緒、關檔案、註銷全域監聽。 |
| 離開頁面 | 導覽頁會收到 `LauncherPage.onDispose()`；工具頁只丟棄面板；**不會**呼叫 `onUnload()`。 |
| 阻塞工作 | 不要阻塞 FX 執行緒；用自己的執行緒／executor，UI 更新用 `context.runLater(...)`。 |
| 回呼中的例外 | 啟動器會盡量攔截並記錄；不要依賴它做清理，請用 `try/finally`。 |
| Refresh | 重建啟動器頂欄／導覽頁，並重新載入所有外部外掛。 |

---

### 13. 自我測試

先 `gradle installDist`（或把 Gradle 建置輸出放進 classpath）：

```bat
java -cp "build\classes\java\main;build\install\brisk-launcher\lib\*" ^
     com.brisklauncher.mcl.plugin.PluginSelfTest examples\hello-plugin\hello-plugin.jar
```

範例外掛與主題在 `examples/`。教學、`plugin.json` 逐步說明、打包、安全模型與排查請看
[外掛開發指南](PLUGIN_DEV.md#繁體中文)。