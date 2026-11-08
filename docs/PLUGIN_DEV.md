# Plugin Development Guide

**English** · [简体中文 ↓](#简体中文) · [繁體中文 ↓](#繁體中文)

[← Back to README](../README.md) · [简体中文 README](README.zh-CN.md) · [繁體中文 README](README.zh-TW.md)

> Working sample: [`examples/hello-plugin/`](../examples/hello-plugin/) — tool panel, navigation page,
> language pack, config, services. Build it with `build.ps1`, drop the jar in `data\plugins\`, done.
>
> API version: **2** (`com.brisklauncher.mcl.plugin.api.PluginApi.VERSION`)

---

## English

### 1. What a plugin can do

A plugin is a plain jar placed in `<launcher>\data\plugins\`. It is loaded at launcher start and can:

| Capability | Entry point | Notes |
|---|---|---|
| Tool panel | `LauncherPlugin.createPanel` | One panel per plugin, shown on the **Tools** page |
| Navigation page | `LauncherPlugin.pages` | As many as you like; each becomes a top-bar entry |
| Own language pack | `i18n/messages*.properties` in the jar | Read through `PluginContext.messages()` |
| Auth provider | `META-INF/services/...AuthProvider` or `context.registerAuthProvider` | Can replace a built-in provider per account type |
| Own config & storage | `context.config()` / `context.pluginDataDir()` | `data\plugin-data\<id>\`, writable |
| Services for other plugins | `context.services()` | Voluntary contract; cleared on unload |
| Launcher services | `context.http()`, `context.log()`, `context.info()`, `context.instances()`… | See §4 |

What it **cannot** do (by design, see §13): add pipeline hooks (download / install / launch), inject CSS,
replace launcher pages, or reach the launcher's private directory.

### 2. Quick start (5 minutes)

```
hello-plugin/
  plugin.json                  <- required, must be at the jar root
  src/com/example/hello/…java  <- your code
  i18n/messages.properties     <- optional language pack (default/English)
  i18n/messages_zh_CN.properties
```

`plugin.json`:

```json
{
  "id": "hello-plugin",
  "name": "Hello Plugin",
  "class": "com.example.hello.HelloPlugin",
  "description": "One line, shown on the Tools page",
  "version": "1.0.0",
  "author": "you",
  "homepage": "https://example.com",
  "icon": "👋",
  "apiVersion": 2,
  "requires": []
}
```

Main class (`public`, public no-arg constructor):

```java
package com.example.hello;

import com.brisklauncher.mcl.plugin.api.LauncherPlugin;
import com.brisklauncher.mcl.plugin.api.PluginContext;
import javafx.scene.Node;
import javafx.scene.control.Label;

public final class HelloPlugin implements LauncherPlugin {

    @Override
    public String id() {
        return "hello-plugin";            // same as plugin.json, [A-Za-z0-9._-] only
    }

    @Override
    public String name() {
        return "Hello Plugin";
    }

    @Override
    public void onLoad(PluginContext context) {
        context.log().info("loaded, data dir = " + context.pluginDataDir());
    }

    @Override
    public Node createPanel(PluginContext context) {
        return new Label("Hello from " + context.pluginDataDir());
    }

    @Override
    public void onUnload() {
        // stop threads, close files
    }
}
```

Build (JDK 21 only — see §8 for details), drop the jar into `data\plugins\`, then press
**Refresh** on the Tools page. No launcher restart needed.

### 3. `plugin.json` reference

| Field | Required | Meaning |
|---|---|---|
| `id` | recommended | Unique id; sanitized to `[A-Za-z0-9._-]`; also the folder name under `data\plugin-data\`. Missing → jar file name. |
| `class` | **yes** | Main class implementing `LauncherPlugin` (must be `public` with a public no-arg constructor). |
| `name` | no | Display name used when `LauncherPlugin.name()` is not a better source. |
| `description` | no | One-liner in the plugin list. |
| `version` | no | Shown in the Tools page (`LauncherPlugin.version()` wins if overridden). |
| `author` | no | Shown in the Tools page. |
| `homepage` | no | Informational only. |
| `icon` | no | Emoji fallback for `LauncherPlugin.icon()`. |
| `apiVersion` | no | Plugin API version you compiled against. `> 2` → refused with a "needs a newer launcher" message. Default = 2. |
| `requires` | no | Ids of other plugins that must load first (dependency order + "missing dependency" error). |

Unknown fields are ignored. The file must be UTF-8 and at the jar **root** (not under `META-INF/`).

### 4. API reference

Everything you may use lives in `com.brisklauncher.mcl.plugin.api` (plus `com.brisklauncher.mcl.auth.api`
for auth, JDK and JavaFX). Anything else in the launcher is internal and may change without notice.

#### 4.1 `LauncherPlugin`

| Method | Default | When it is called |
|---|---|---|
| `String id()` | — (required) | Load time |
| `String name()` | — (required) | Load time **and** on language switch |
| `String description()` | `""` | Load time, language switch |
| `String icon()` | `"🧩"` | Load time, language switch |
| `String version()` | `""` | Load time (falls back to `plugin.json`) |
| `void onLoad(PluginContext)` | no-op | Once, right after the instance is created |
| `List<LauncherPage> pages()` | `List.of()` | Once, at load time |
| `Node createPanel(PluginContext)` | `null` | Every time the panel is (re)created — FX thread |
| `void onUnload()` | no-op | Disable / remove / refresh / launcher exit |

Do not override `createPanel` if your plugin is page-only: the launcher detects it and will not show an
empty tool entry.

#### 4.2 `LauncherPage`

| Member | Meaning |
|---|---|
| `String id()` | `[A-Za-z0-9._-]`; becomes `p:<pluginId>:<pageId>` internally |
| `String title()` | Top-bar label; called on language switch |
| `String icon()` | Optional emoji prefix |
| `Node createContent(PluginContext)` | Called once per page instance — FX thread |
| `void onShown()` / `onHidden()` | Page became visible / was switched away |
| `void onDispose()` | Page thrown away (leaving the page, language switch, window close) |

Pages are **not** cached across visits (except the launcher's own launch page), so `createContent` may run
again after you navigate away and back. Put reusable state in fields of the plugin or in `config()`.

#### 4.3 `PluginContext`

| Group | Method | Notes |
|---|---|---|
| Identity | `pluginId()`, `locale()`, `launcherVersion()` | `locale()` is `zh_CN` / `zh_TW` / `en_US` / custom pack id |
| Paths | `dataDir()` | Launcher data dir — **read only by convention** |
| | `pluginDataDir()` | `data\plugin-data\<id>\`, created for you, writable |
| HTTP | `httpGet(url)`, `httpGetText(url)` | Blocking, 20 s timeout |
| | `http()` | Full client: any method, headers, body, timeout, status code |
| | `json()` | Shared Jackson `ObjectMapper` |
| UI | `ownerWindow()`, `openUrl(url)`, `openFolder(path)` | |
| | `info(title, msg)`, `error(title, msg)`, `confirm(title, msg, onYes)` | Non-blocking, launcher styling |
| Instances | `instances()`, `selectedInstanceGameDir()`, `selectedInstanceLabel()` | `InstanceInfo(id, mcVersion, gameDir)` |
| Theme | `theme()` | `ThemeInfo(id, name, dark, accent, background)` — match the active theme pack |

> **Styling tip**: the launcher stylesheet defines design tokens (`-brisk-bg`, `-brisk-surface`,
> `-brisk-text`, `-brisk-accent`, …). Use them in your inline styles
> (`label.setStyle("-fx-text-fill: -brisk-text-muted;")`) and your panel follows every theme
> automatically. Full list: [theme guide, Appendix A](THEME_DEV.md#15-appendix-a-design-tokens).
| Messaging | `messages()` | Your `i18n/messages*.properties` |
| Storage | `config()` | `config.properties`, auto-saved, atomic |
| Services | `services()` | `provide` / `find` / `require` / `has` |
| Auth | `registerAuthProvider(AuthProvider)` | Unregistered automatically on unload |
| Threads | `runLater(Runnable)` | Hop back to the FX thread |
| | `addLocaleListener(Runnable)` | Returns a `Runnable` that unregisters |

#### 4.4 `PluginHttp`

```java
var response = context.http().request(PluginHttp.Request.get("https://api.example.com/ping")
        .withHeader("Accept", "application/json")
        .withTimeout(Duration.ofSeconds(10)));
if (response.ok()) {
    var node = context.json().readTree(response.body());
}
var posted = context.http().request(PluginHttp.Request.post(url, bytes, "application/json"));
```

`Response` exposes `status()`, `headers()`, `body()`, `text()`, `ok()`.

#### 4.5 `PluginConfig`, `PluginMessages`, `PluginServices`, `PluginLog`

```java
context.config().set("last_server", "play.example.com");   // written immediately
int port = context.config().getInt("port", 25565);

String text = context.messages().t("myplugin.hello", playerName);   // MessageFormat
if (!context.messages().has("myplugin.hello")) { /* fall back */ }

context.services().provide("myplugin.api", myApiObject);            // null = unregister
MyApi api = context.services().find("myplugin.api", MyApi.class);   // null if absent/type mismatch
MyApi must = context.services().require("myplugin.api", MyApi.class); // throws if absent

context.log().info("hello");               // launcher log, [plugin:<id>] prefix
context.log().error("failed", throwable);
```

### 5. Lifecycle and threading

```
launcher start
  └─ scan data\plugins\*.jar            (plugin.json, one SHA-256 per jar)
      └─ dependency order by requires
          └─ disabled.txt / trust check / risk notice
              └─ new MyPlugin()  →  onLoad(context)      [FX thread, keep it short]
                  └─ pages()                              [FX thread]
                      └─ createPanel(context)             [FX thread, on Tools page]
                          └─ … user interaction …
                      └─ onUnload()                       [disable/remove/refresh/exit]
```

* Everything except `onLoad` may also run on the FX thread — **never block it**. Long work:
  `new Thread(...)` (or an executor) and `context.runLater(...)` for UI updates.
* `dataDir()` is shared: read what you need, but write only to `pluginDataDir()`.
* `onUnload()` must stop threads and close files; the launcher calls it before unloading your jar.

### 6. Files and folders

| Path | Content |
|---|---|
| `data\plugins\*.jar` | Your plugin |
| `data\plugins\disabled.txt` | One id / jar file name / jar SHA-256 per line, `#` for comments |
| `data\plugins\README.txt` | Generated reminder |
| `data\plugin-data\<id>\` | **Your** writable folder (config, caches, exports) |
| `%LOCALAPPDATA%\BriskLauncher\` | Launcher-private: accounts, `secret.key`, `acks.json`, `plugin-trust.json` — do not touch |
| `%LOCALAPPDATA%\BriskLauncher\logs\launcher.log` | Logs (`launcher.log.0` / `.1` are rotations) |

Risk notice: the first third-party plugin load shows a one-time notice (recorded in `acks.json`).
`plugin-trust.json` remembers the SHA-256 per plugin id, so **updating** a plugin re-asks once
("content changed") — that is expected, not a bug.

### 7. Auth provider

```java
public final class MySkinSiteProvider implements AuthProvider {

    @Override public AccountType type() { return AccountType.THIRD_PARTY; }
    @Override public String id() { return "myskinsite"; }          // default: type name lower-case
    @Override public String displayName() { return "My Skin Site"; }

    @Override public LoginResult login(LoginCredentials credentials) { … }
    @Override public LoginResult refresh(StoredSession session) { … }
    @Override public boolean validate(StoredSession session) { … }
    @Override public GameSession toGameSession(StoredSession session) { … }
}
```

Register either way:

```
# inside the jar
META-INF/services/com.brisklauncher.mcl.auth.api.AuthProvider
  -> one line: com.example.hello.MySkinSiteProvider
```

```java
@Override
public void onLoad(PluginContext context) {
    context.registerAuthProvider(new MySkinSiteProvider());
}
```

Same `id()` overrides; same `type()` makes your provider the one used for that account type
(launch / refresh path resolves through the registry). Unloaded → automatically unregistered.

### 8. Packaging

```bat
rem 1) build the launcher once, so the API jar exists
mvn -o compile

rem 2) compile the plugin (Java 21; classpath = launcher classes + its lib)
set JAVA_HOME=C:\Program Files\Java\jdk-21
"%JAVA_HOME%\bin\javac" -encoding UTF-8 --release 21 ^
    -cp "target\classes;build\install\brisk-launcher\lib\*" ^
    -d out src\com\example\hello\HelloPlugin.java

rem 3) plugin.json + i18n must be at the jar root
copy plugin.json out
xcopy /E /I i18n out\i18n

"%JAVA_HOME%\bin\jar" --create --file hello-plugin.jar -C out .
```

Rules that bite if you miss them:

* `plugin.json` **at the jar root** (a copy under `META-INF/` is ignored);
* main class `public`, **public no-arg constructor**;
* Java 21 bytecode or older (`--release 21`); newer class files fail with `UnsupportedClassVersionError`;
* compile against the launcher's classes on the **classpath** — but only `…plugin.api` / `…auth.api` /
  JavaFX / Jackson / JDK are reachable at runtime. Touching launcher internals compiles fine and then
  throws `IllegalAccessError` at load time.
* no shading of launcher classes into your jar — the launcher's own loader is the parent.

### 9. Compatibility contract

* API version: `PluginApi.VERSION` (currently `2`). Adding methods bumps it; removals bump the major.
* `plugin.json` `"apiVersion"` lets you declare the minimum you need; a newer launcher accepts older
  plugins, an older launcher refuses newer `apiVersion` instead of crashing.
* Only the `plugin.api` / `auth.api` packages are stable. Everything else (`com.brisklauncher.mcl.*`,
  internals of `ToolsPage`, …) may change in any release.
* Your `config.properties` and files under `plugin-data\<id>\` are yours: migrate them yourself when the
  format changes. Never write to `launcher.json` / `accounts.json` / `secret.key`.

### 10. Debugging

| Symptom | Cause / fix |
|---|---|
| Plugin not in the list | jar not in `data\plugins\`, or `disabled.txt` contains its id/file name/SHA-256 |
| `缺少 plugin.json` | `plugin.json` not at the jar root |
| `主类没有实现 LauncherPlugin` | Wrong `class`, or still importing the old `.plugin.LauncherPlugin` |
| `需要更新版本的启动器` | `apiVersion` higher than the launcher's `PluginApi.VERSION` |
| `缺少依赖插件：x` | `requires` names a plugin that is not installed (or is disabled) |
| `依赖循环` | Plugins require each other |
| `插件内容已变更` | You replaced the jar; confirm once, digest is re-pinned |
| `NoSuchMethodError` / `AbstractMethodError` | Compiled against a newer API than the launcher provides |
| `IllegalAccessError` | You referenced launcher internals instead of `plugin.api` |
| `IllegalStateException: Toolkit not initialized` | You touched JavaFX off the FX thread or before the UI exists |
| Nothing on the Tools page | Plugin only has pages (no `createPanel`) — that is shown as a hint, not an error |

Useful switches:

```bat
java -Dmcl.plugins.quiet=1 …      rem do not log every plugin load
java -Dmcl.dev.tool=hello-plugin … rem open this tool automatically on the Tools page
```

Headless contract test (no UI needed):

```bat
java -cp "target\classes;build\install\brisk-launcher\lib\*" ^
     com.brisklauncher.mcl.plugin.PluginSelfTest examples\hello-plugin\hello-plugin.jar
```

### 11. Plugin-to-plugin

* Declare `"requires": ["other-id"]` in `plugin.json` → load order is handled, missing dependencies are
  reported instead of silently misbehaving.
* Publish with `context.services().provide("myplugin.api", obj)`; consume with
  `find`/`require`. Names are global: prefix them.
* Services and auth providers of a plugin are removed when it unloads — always look them up lazily
  (in `createPanel` / `onShown`), never cache another plugin's object across pages.

### 12. Security

Plugins run **in the launcher process** with the same Windows user, so they can read your files, use the
network and read account data that is already in memory. There is no sandbox:

* the launcher only refuses to load what `disabled.txt` says, pins SHA-256 per id, and warns once;
* the plugin API deliberately does not hand out the private directory (accounts, `secret.key`, logs);
* never log tokens, passwords, cookies or player personal data;
* an auth provider you register is used for real logins — treat it like a password field.

### 13. Limits (on purpose)

* One tool panel per plugin, and it lives inside the Tools page.
* No hooks into download / install / launch pipelines, no context menus, no custom top-bar widgets,
  no CSS injection (themes are for `data\themes\`).
* Language and theme packs are **declarative files**, not code — they cannot localise your plugin.
* Plugins cannot be hot-reloaded per-plugin: Refresh reloads everything.

---

## 简体中文

### 1. 插件能做什么

插件就是一个 jar，放在 `<启动器>\data\plugins\` 下，启动器启动时载入，可以：

| 能力 | 入口 | 说明 |
|---|---|---|
| 工具面板 | `LauncherPlugin.createPanel` | 每个插件一个面板，显示在「工具」页 |
| 导航页 | `LauncherPlugin.pages` | 数量不限，每个都会变成顶栏入口 |
| 自带语言包 | jar 里的 `i18n/messages*.properties` | 通过 `PluginContext.messages()` 读取 |
| 认证实现 | `META-INF/services/...AuthProvider` 或 `context.registerAuthProvider` | 可以按账号类型替换内建实现 |
| 自己的配置与存储 | `context.config()` / `context.pluginDataDir()` | `data\plugin-data\<id>\`，可写 |
| 给别的插件用的服务 | `context.services()` | 自愿契约，卸载时自动清掉 |
| 启动器能力 | `context.http()`、`context.log()`、`context.info()`、`context.instances()`…… | 见 §4 |

**做不到**的（有意为之，见 §13）：挂进下载 / 安装 / 启动流程、注入 CSS、替换启动器页面、
访问启动器私有目录。

### 2. 五分钟上手

```
hello-plugin/
  plugin.json                  <- 必需，必须在 jar 根目录
  src/com/example/hello/…java  <- 你的代码
  i18n/messages.properties     <- 可选语言包（默认 / 英文）
  i18n/messages_zh_CN.properties
```

`plugin.json`：

```json
{
  "id": "hello-plugin",
  "name": "Hello Plugin",
  "class": "com.example.hello.HelloPlugin",
  "description": "一行说明，显示在工具页",
  "version": "1.0.0",
  "author": "你",
  "homepage": "https://example.com",
  "icon": "👋",
  "apiVersion": 2,
  "requires": []
}
```

主类（`public` + public 无参构造器）：

```java
package com.example.hello;

import com.brisklauncher.mcl.plugin.api.LauncherPlugin;
import com.brisklauncher.mcl.plugin.api.PluginContext;
import javafx.scene.Node;
import javafx.scene.control.Label;

public final class HelloPlugin implements LauncherPlugin {

    @Override
    public String id() {
        return "hello-plugin";            // 与 plugin.json 一致，只允许 [A-Za-z0-9._-]
    }

    @Override
    public String name() {
        return "Hello Plugin";
    }

    @Override
    public void onLoad(PluginContext context) {
        context.log().info("已载入，数据目录 = " + context.pluginDataDir());
    }

    @Override
    public Node createPanel(PluginContext context) {
        return new Label("来自 " + context.pluginDataDir());
    }

    @Override
    public void onUnload() {
        // 停线程、关文件
    }
}
```

打包（只用 JDK 21，详见 §8），把 jar 丢进 `data\plugins\`，然后在「工具」页按**刷新**，
不需要重启启动器。

### 3. `plugin.json` 字段

| 字段 | 必需 | 含义 |
|---|---|---|
| `id` | 建议写 | 唯一 id；会清洗成 `[A-Za-z0-9._-]`；同时是 `data\plugin-data\` 下的文件夹名。不写就用 jar 文件名。 |
| `class` | **必需** | 实现 `LauncherPlugin` 的主类（必须 `public` + public 无参构造器）。 |
| `name` | 否 | 显示名；`LauncherPlugin.name()` 优先。 |
| `description` | 否 | 插件列表里的一行说明。 |
| `version` | 否 | 工具页显示（`LauncherPlugin.version()` 优先）。 |
| `author` | 否 | 工具页显示。 |
| `homepage` | 否 | 仅作说明。 |
| `icon` | 否 | `LauncherPlugin.icon()` 的备用 emoji。 |
| `apiVersion` | 否 | 编译时用的 API 版本；大于 `2` 会被拒绝并提示「需要更新版本的启动器」。默认 2。 |
| `requires` | 否 | 依赖的其它插件 id（决定载入顺序，缺依赖会明确报错）。 |

未知字段会被忽略。文件必须是 UTF-8，且放在 jar **根目录**（不是 `META-INF/`）。

### 4. API 参考

能用的东西都在 `com.brisklauncher.mcl.plugin.api`（做认证再加 `com.brisklauncher.mcl.auth.api`），
外加 JDK 与 JavaFX。启动器其它包都是内部实现，不保证兼容。

#### 4.1 `LauncherPlugin`

| 方法 | 默认值 | 调用时机 |
|---|---|---|
| `String id()` | —（必需） | 载入时 |
| `String name()` | —（必需） | 载入时 **和** 切换语言时 |
| `String description()` | `""` | 载入、切换语言 |
| `String icon()` | `"🧩"` | 载入、切换语言 |
| `String version()` | `""` | 载入时（回退到 `plugin.json`） |
| `void onLoad(PluginContext)` | 空 | 实例建立后一次 |
| `List<LauncherPage> pages()` | `List.of()` | 载入时一次 |
| `Node createPanel(PluginContext)` | `null` | 面板每次重建时（FX 线程） |
| `void onUnload()` | 空 | 停用 / 移除 / 刷新 / 启动器退出 |

只提供导航页的插件**不要**覆写 `createPanel`：启动器会检测到，不会给你留一个空工具条目。

#### 4.2 `LauncherPage`

| 成员 | 说明 |
|---|---|
| `String id()` | `[A-Za-z0-9._-]`；内部 key 是 `p:<插件id>:<页面id>` |
| `String title()` | 顶栏文字；切换语言时会重新调用 |
| `String icon()` | 可选 emoji 前缀 |
| `Node createContent(PluginContext)` | 每个页面实例调用一次（FX 线程） |
| `void onShown()` / `onHidden()` | 页面显示 / 被切走 |
| `void onDispose()` | 页面被丢弃（离开页面、切语言、关窗） |

页面**不跨访问缓存**（启动页除外），所以离开再回来 `createContent` 可能再跑一次；
需要保留的状态放插件字段或 `config()` 里。

#### 4.3 `PluginContext`

| 分组 | 方法 | 说明 |
|---|---|---|
| 身份 | `pluginId()`、`locale()`、`launcherVersion()` | `locale()` 是 `zh_CN` / `zh_TW` / `en_US` / 自订语言包 id |
| 路径 | `dataDir()` | 启动器数据目录，**约定只读** |
| | `pluginDataDir()` | `data\plugin-data\<id>\`，已建好，可写 |
| 网络 | `httpGet(url)`、`httpGetText(url)` | 同步，20 秒超时 |
| | `http()` | 完整客户端：任意方法、标头、body、超时、状态码 |
| | `json()` | 共用启动器的 Jackson `ObjectMapper` |
| 界面 | `ownerWindow()`、`openUrl(url)`、`openFolder(path)` | |
| | `info(title, msg)`、`error(title, msg)`、`confirm(title, msg, onYes)` | 非阻塞，统一风格 |
| 实例 | `instances()`、`selectedInstanceGameDir()`、`selectedInstanceLabel()` | `InstanceInfo(id, mcVersion, gameDir)` |
| 主题 | `theme()` | `ThemeInfo(id, name, dark, accent, background)` —— 跟着当前主题配色 |

> **配色建议**：启动器样式里定义了一套设计变量（`-brisk-bg`、`-brisk-surface`、`-brisk-text`、
> `-brisk-accent` …）。在自己写的 inline 样式里用它们
> （`label.setStyle("-fx-text-fill: -brisk-text-muted;")`），面板就会自动跟着主题走。
> 清单见[主题开发指南附录 A](THEME_DEV.md#15-appendix-a-design-tokens)。
| 多语言 | `messages()` | 你的 `i18n/messages*.properties` |
| 存储 | `config()` | `config.properties`，写入即落盘（原子替换） |
| 服务 | `services()` | `provide` / `find` / `require` / `has` |
| 认证 | `registerAuthProvider(AuthProvider)` | 卸载时自动注销 |
| 线程 | `runLater(Runnable)` | 回到 FX 线程 |
| | `addLocaleListener(Runnable)` | 回传的 `Runnable` 用来取消注册 |

#### 4.4 `PluginHttp`

```java
var response = context.http().request(PluginHttp.Request.get("https://api.example.com/ping")
        .withHeader("Accept", "application/json")
        .withTimeout(Duration.ofSeconds(10)));
if (response.ok()) {
    var node = context.json().readTree(response.body());
}
var posted = context.http().request(PluginHttp.Request.post(url, bytes, "application/json"));
```

`Response` 提供 `status()`、`headers()`、`body()`、`text()`、`ok()`。

#### 4.5 `PluginConfig` / `PluginMessages` / `PluginServices` / `PluginLog`

```java
context.config().set("last_server", "play.example.com");   // 立刻落盘
int port = context.config().getInt("port", 25565);

String text = context.messages().t("myplugin.hello", playerName);   // MessageFormat
if (!context.messages().has("myplugin.hello")) { /* 自己兜底 */ }

context.services().provide("myplugin.api", myApiObject);            // 传 null = 注销
MyApi api = context.services().find("myplugin.api", MyApi.class);   // 没有或类型不符回 null
MyApi must = context.services().require("myplugin.api", MyApi.class); // 没有就抛

context.log().info("hello");               // 写进启动器日志，带 [plugin:<id>] 前缀
context.log().error("failed", throwable);
```

### 5. 生命周期与线程

```
启动器启动
  └─ 扫描 data\plugins\*.jar           （plugin.json，每支 jar 一次 SHA-256）
      └─ 按 requires 排序
          └─ disabled.txt / 信任检查 / 风险提示
              └─ new MyPlugin()  →  onLoad(context)      [FX 线程，要快]
                  └─ pages()                              [FX 线程]
                      └─ createPanel(context)             [FX 线程，点开工具时]
                          └─ … 使用者操作 …
                      └─ onUnload()                       [停用/移除/刷新/退出]
```

* 除 `onLoad` 外的回调也在 FX 线程上，**绝对不要阻塞**。长活儿自己开线程，
  更新界面用 `context.runLater(...)`。
* `dataDir()` 是共用的：读没问题，写请只写 `pluginDataDir()`。
* `onUnload()` 必须停掉线程、关掉文件；启动器在卸下你的 jar 之前会调用它。

### 6. 文件位置

| 路径 | 内容 |
|---|---|
| `data\plugins\*.jar` | 你的插件 |
| `data\plugins\disabled.txt` | 每行一个 id / jar 文件名 / jar 的 SHA-256，`#` 是注释 |
| `data\plugins\README.txt` | 自动生成的说明 |
| `data\plugin-data\<id>\` | **你的**可写目录（配置、缓存、导出） |
| `%LOCALAPPDATA%\BriskLauncher\` | 启动器私有：账号、`secret.key`、`acks.json`、`plugin-trust.json`，不要动 |
| `%LOCALAPPDATA%\BriskLauncher\logs\launcher.log` | 日志（`launcher.log.0` / `.1` 是轮替） |

风险提示：首次载入第三方插件会弹一次说明（记在 `acks.json`）。
`plugin-trust.json` 按插件 id 记住 SHA-256，所以**更新**插件时会再问一次「内容已变更」——
这是设计行为，不是 bug。

### 7. 认证实现

```java
public final class MySkinSiteProvider implements AuthProvider {

    @Override public AccountType type() { return AccountType.THIRD_PARTY; }
    @Override public String id() { return "myskinsite"; }          // 默认是类型名小写
    @Override public String displayName() { return "我的皮肤站"; }

    @Override public LoginResult login(LoginCredentials credentials) { … }
    @Override public LoginResult refresh(StoredSession session) { … }
    @Override public boolean validate(StoredSession session) { … }
    @Override public GameSession toGameSession(StoredSession session) { … }
}
```

两种注册方式：

```
# jar 里放
META-INF/services/com.brisklauncher.mcl.auth.api.AuthProvider
  -> 一行：com.example.hello.MySkinSiteProvider
```

```java
@Override
public void onLoad(PluginContext context) {
    context.registerAuthProvider(new MySkinSiteProvider());
}
```

同 `id()` 会互相覆盖；同 `type()` 的实现在该账号类型的登入 / 刷新路径里生效。
插件卸载时自动注销，不会留下悬空实现。

### 8. 打包

```bat
rem 1) 先把启动器编译一次，API jar 才存在
mvn -o compile

rem 2) 编译插件（Java 21）
set JAVA_HOME=C:\Program Files\Java\jdk-21
"%JAVA_HOME%\bin\javac" -encoding UTF-8 --release 21 ^
    -cp "target\classes;build\install\brisk-launcher\lib\*" ^
    -d out src\com\example\hello\HelloPlugin.java

rem 3) plugin.json 与 i18n 必须在 jar 根目录
copy plugin.json out
xcopy /E /I i18n out\i18n

"%JAVA_HOME%\bin\jar" --create --file hello-plugin.jar -C out .
```

最容易踩的几条：

* `plugin.json` 必须在 **jar 根目录**（放 `META-INF/` 下无效）；
* 主类 `public` 且**有 public 无参构造器**；
* Java 21 或更旧的字节码（`--release 21`）；更新的类文件会 `UnsupportedClassVersionError`；
* 用 **classpath** 编译没问题，但运行时只有 `…plugin.api` / `…auth.api` / JavaFX / Jackson / JDK
  可达。引用启动器内部类能编过，载入时会 `IllegalAccessError`；
* 不要把启动器类 shade 进你的 jar —— 父载入器就是启动器自己。

### 9. 兼容性约定

* API 版本：`PluginApi.VERSION`（目前 `2`）。加方法升次版本，删方法升主版本。
* `plugin.json` 的 `"apiVersion"` 用来声明你需要的最低版本；新启动器接受旧插件，
  旧启动器遇到更高的 `apiVersion` 会明确拒绝而不是崩掉。
* 只有 `plugin.api` / `auth.api` 两个包是稳定的；其它（`com.brisklauncher.mcl.*`、`ToolsPage` 内部等）
  任何版本都可能改。
* `config.properties` 与 `plugin-data\<id>\` 里的文件是你自己的：格式变了请自己迁移。
  永远不要写 `launcher.json` / `accounts.json` / `secret.key`。

### 10. 排查

| 现象 | 原因 / 处理 |
|---|---|
| 列表里没有插件 | jar 不在 `data\plugins\`，或 `disabled.txt` 里有它的 id / 文件名 / SHA-256 |
| `缺少 plugin.json` | `plugin.json` 不在 jar 根目录 |
| `主类没有实现 LauncherPlugin` | `class` 写错，或还在 import 旧的 `.plugin.LauncherPlugin` |
| `需要更新版本的启动器` | `apiVersion` 比启动器的 `PluginApi.VERSION` 高 |
| `缺少依赖插件：x` | `requires` 里的插件没装（或被停用） |
| `依赖循环` | 插件互相依赖 |
| `插件内容已变更` | 你换了 jar；确认一次即可，摘要会重新钉住 |
| `NoSuchMethodError` / `AbstractMethodError` | 用了比启动器更新的 API |
| `IllegalAccessError` | 引用了启动器内部类，应该只用 `plugin.api` |
| `IllegalStateException: Toolkit not initialized` | 在非 FX 线程或界面存在前碰了 JavaFX |
| 工具页什么都没有 | 插件只有导航页（没覆写 `createPanel`），会显示提示而不是报错 |

调试开关：

```bat
java -Dmcl.plugins.quiet=1 …      rem 不记录每次插件载入
java -Dmcl.dev.tool=hello-plugin … rem 工具页自动打开这个插件
```

无界面的契约自检（不需要开启动器）：

```bat
java -cp "target\classes;build\install\brisk-launcher\lib\*" ^
     com.brisklauncher.mcl.plugin.PluginSelfTest examples\hello-plugin\hello-plugin.jar
```

### 11. 插件之间

* 在 `plugin.json` 写 `"requires": ["other-id"]` → 载入顺序自动处理，缺依赖会明确报错而不是行为异常。
* 用 `context.services().provide("myplugin.api", obj)` 开放，用 `find` / `require` 取用。
  名字是全局的，请加前缀。
* 插件的服务与认证实现随卸载一起清掉 —— 请延迟查找（在 `createPanel` / `onShown` 里），
  不要把别的插件对象缓存过页面。

### 12. 安全

插件与启动器在**同一个进程**里、同一个 Windows 用户下运行，所以它能读写你的文件、用网络、
读到已经载入内存的账号数据。没有沙箱：

* 启动器只做三件事：按 `disabled.txt` 拒绝、按 id 钉住 SHA-256、第一次弹风险说明；
* 插件 API 有意不把私有目录（账号、`secret.key`、日志）交出去；
* 不要记录令牌、密码、Cookie 或玩家个资；
* 你注册的认证实现会用于真实登入，请当成密码框来对待。

### 13. 有意为之的限制

* 每个插件只有一个工具面板，而且只能出现在「工具」页里。
* 不能挂进下载 / 安装 / 启动流程，不能加右键菜单或顶栏自订控件，不能注入 CSS
  （主题是 `data\themes\` 的事）。
* 语言包与主题包是**声明式文件**，不是代码，不能用来本地化你的插件。
* 插件不能单独热重载：「刷新」会重载全部插件。

---

## 繁體中文

### 1. 外掛能做什麼

外掛就是一個 jar，放在 `<啟動器>\data\plugins\` 底下，啟動器啟動時載入，可以：

| 能力 | 入口 | 說明 |
|---|---|---|
| 工具面板 | `LauncherPlugin.createPanel` | 每個外掛一個面板，顯示在「工具」頁 |
| 導覽頁 | `LauncherPlugin.pages` | 數量不限，每個都會變成頂欄入口 |
| 自帶語言包 | jar 裡的 `i18n/messages*.properties` | 透過 `PluginContext.messages()` 讀取 |
| 認證實作 | `META-INF/services/...AuthProvider` 或 `context.registerAuthProvider` | 可以按帳號類型替換內建實作 |
| 自己的設定與儲存 | `context.config()` / `context.pluginDataDir()` | `data\plugin-data\<id>\`，可寫 |
| 給別的插件用的服務 | `context.services()` | 自願契約，卸載時自動清掉 |
| 啟動器能力 | `context.http()`、`context.log()`、`context.info()`、`context.instances()`…… | 見 §4 |

**做不到**的（刻意如此，見 §13）：掛進下載 / 安裝 / 啟動流程、注入 CSS、替換啟動器頁面、
存取啟動器私有資料夾。

### 2. 五分鐘上手

```
hello-plugin/
  plugin.json                  <- 必需，必須在 jar 根目錄
  src/com/example/hello/…java  <- 你的程式碼
  i18n/messages.properties     <- 可選語言包（預設 / 英文）
  i18n/messages_zh_TW.properties
```

`plugin.json`：

```json
{
  "id": "hello-plugin",
  "name": "Hello Plugin",
  "class": "com.example.hello.HelloPlugin",
  "description": "一行說明，顯示在工具頁",
  "version": "1.0.0",
  "author": "你",
  "homepage": "https://example.com",
  "icon": "👋",
  "apiVersion": 2,
  "requires": []
}
```

主類（`public` + public 無參建構子）：

```java
package com.example.hello;

import com.brisklauncher.mcl.plugin.api.LauncherPlugin;
import com.brisklauncher.mcl.plugin.api.PluginContext;
import javafx.scene.Node;
import javafx.scene.control.Label;

public final class HelloPlugin implements LauncherPlugin {

    @Override
    public String id() {
        return "hello-plugin";            // 與 plugin.json 一致，只允許 [A-Za-z0-9._-]
    }

    @Override
    public String name() {
        return "Hello Plugin";
    }

    @Override
    public void onLoad(PluginContext context) {
        context.log().info("已載入，資料夾 = " + context.pluginDataDir());
    }

    @Override
    public Node createPanel(PluginContext context) {
        return new Label("來自 " + context.pluginDataDir());
    }

    @Override
    public void onUnload() {
        // 停執行緒、關檔案
    }
}
```

打包（只用 JDK 21，詳見 §8），把 jar 丟進 `data\plugins\`，然後在「工具」頁按**重新整理**，
不需要重啟啟動器。

### 3. `plugin.json` 欄位

| 欄位 | 必需 | 含意 |
|---|---|---|
| `id` | 建議填 | 唯一 id；會清洗成 `[A-Za-z0-9._-]`；同時是 `data\plugin-data\` 下的資料夾名。沒填就用 jar 檔名。 |
| `class` | **必需** | 實作 `LauncherPlugin` 的主類（必須 `public` + public 無參建構子）。 |
| `name` | 否 | 顯示名；`LauncherPlugin.name()` 優先。 |
| `description` | 否 | 外掛清單裡的一行說明。 |
| `version` | 否 | 工具頁顯示（`LauncherPlugin.version()` 優先）。 |
| `author` | 否 | 工具頁顯示。 |
| `homepage` | 否 | 僅作說明。 |
| `icon` | 否 | `LauncherPlugin.icon()` 的備用 emoji。 |
| `apiVersion` | 否 | 編譯時用的 API 版本；大於 `2` 會被拒絕並提示「需要更新版本的啟動器」。預設 2。 |
| `requires` | 否 | 依賴的其它外掛 id（決定載入順序，缺依賴會明確報錯）。 |

未知欄位會被忽略。檔案必須是 UTF-8，且放在 jar **根目錄**（不是 `META-INF/`）。

### 4. API 參考

能用的東西都在 `com.brisklauncher.mcl.plugin.api`（做認證再加 `com.brisklauncher.mcl.auth.api`），
外加 JDK 與 JavaFX。啟動器其它套件都是內部實作，不保證相容。

#### 4.1 `LauncherPlugin`

| 方法 | 預設值 | 呼叫時機 |
|---|---|---|
| `String id()` | —（必需） | 載入時 |
| `String name()` | —（必需） | 載入時 **和** 切換語言時 |
| `String description()` | `""` | 載入、切換語言 |
| `String icon()` | `"🧩"` | 載入、切換語言 |
| `String version()` | `""` | 載入時（回退到 `plugin.json`） |
| `void onLoad(PluginContext)` | 空 | 實例建立後一次 |
| `List<LauncherPage> pages()` | `List.of()` | 載入時一次 |
| `Node createPanel(PluginContext)` | `null` | 面板每次重建時（FX 執行緒） |
| `void onUnload()` | 空 | 停用 / 移除 / 重新整理 / 啟動器結束 |

只提供導覽頁的外掛**不要**覆寫 `createPanel`：啟動器會偵測到，不會留一個空的工具項目。

#### 4.2 `LauncherPage`

| 成員 | 說明 |
|---|---|
| `String id()` | `[A-Za-z0-9._-]`；內部 key 是 `p:<外掛id>:<頁面id>` |
| `String title()` | 頂欄文字；切換語言時會重新呼叫 |
| `String icon()` | 可選 emoji 前綴 |
| `Node createContent(PluginContext)` | 每個頁面實例呼叫一次（FX 執行緒） |
| `void onShown()` / `onHidden()` | 頁面顯示 / 被切走 |
| `void onDispose()` | 頁面被丟棄（離開頁面、切語言、關窗） |

頁面**不跨訪問快取**（啟動頁除外），所以離開再回來 `createContent` 可能再跑一次；
要保留的狀態放外掛欄位或 `config()` 裡。

#### 4.3 `PluginContext`

| 分組 | 方法 | 說明 |
|---|---|---|
| 身分 | `pluginId()`、`locale()`、`launcherVersion()` | `locale()` 是 `zh_CN` / `zh_TW` / `en_US` / 自訂語言包 id |
| 路徑 | `dataDir()` | 啟動器資料目錄，**約定唯讀** |
| | `pluginDataDir()` | `data\plugin-data\<id>\`，已建好，可寫 |
| 網路 | `httpGet(url)`、`httpGetText(url)` | 同步，20 秒逾時 |
| | `http()` | 完整用戶端：任意方法、標頭、body、逾時、狀態碼 |
| | `json()` | 共用啟動器的 Jackson `ObjectMapper` |
| 介面 | `ownerWindow()`、`openUrl(url)`、`openFolder(path)` | |
| | `info(title, msg)`、`error(title, msg)`、`confirm(title, msg, onYes)` | 非阻塞，統一樣式 |
| 實例 | `instances()`、`selectedInstanceGameDir()`、`selectedInstanceLabel()` | `InstanceInfo(id, mcVersion, gameDir)` |
| 主題 | `theme()` | `ThemeInfo(id, name, dark, accent, background)` —— 跟著目前主題配色 |

> **配色建議**：啟動器樣式裡定義了一套設計變數（`-brisk-bg`、`-brisk-surface`、`-brisk-text`、
> `-brisk-accent` …）。在自己寫的 inline 樣式裡用它們
> （`label.setStyle("-fx-text-fill: -brisk-text-muted;")`），面板就會自動跟著主題走。
> 清單見[主題開發指南附錄 A](THEME_DEV.md#15-appendix-a-design-tokens)。
| 多語言 | `messages()` | 你的 `i18n/messages*.properties` |
| 儲存 | `config()` | `config.properties`，寫入即落盤（原子替換） |
| 服務 | `services()` | `provide` / `find` / `require` / `has` |
| 認證 | `registerAuthProvider(AuthProvider)` | 卸載時自動註銷 |
| 執行緒 | `runLater(Runnable)` | 回到 FX 執行緒 |
| | `addLocaleListener(Runnable)` | 回傳的 `Runnable` 用來取消註冊 |

#### 4.4 `PluginHttp`

```java
var response = context.http().request(PluginHttp.Request.get("https://api.example.com/ping")
        .withHeader("Accept", "application/json")
        .withTimeout(Duration.ofSeconds(10)));
if (response.ok()) {
    var node = context.json().readTree(response.body());
}
var posted = context.http().request(PluginHttp.Request.post(url, bytes, "application/json"));
```

`Response` 提供 `status()`、`headers()`、`body()`、`text()`、`ok()`。

#### 4.5 `PluginConfig` / `PluginMessages` / `PluginServices` / `PluginLog`

```java
context.config().set("last_server", "play.example.com");   // 立刻落盤
int port = context.config().getInt("port", 25565);

String text = context.messages().t("myplugin.hello", playerName);   // MessageFormat
if (!context.messages().has("myplugin.hello")) { /* 自己兜底 */ }

context.services().provide("myplugin.api", myApiObject);            // 傳 null = 註銷
MyApi api = context.services().find("myplugin.api", MyApi.class);   // 沒有或型別不符回 null
MyApi must = context.services().require("myplugin.api", MyApi.class); // 沒有就拋

context.log().info("hello");               // 寫進啟動器日誌，帶 [plugin:<id>] 前綴
context.log().error("failed", throwable);
```

### 5. 生命週期與執行緒

```
啟動器啟動
  └─ 掃描 data\plugins\*.jar           （plugin.json，每支 jar 一次 SHA-256）
      └─ 依 requires 排序
          └─ disabled.txt / 信任檢查 / 風險提示
              └─ new MyPlugin()  →  onLoad(context)      [FX 執行緒，要快]
                  └─ pages()                              [FX 執行緒]
                      └─ createPanel(context)             [FX 執行緒，點開工具時]
                          └─ … 使用者操作 …
                      └─ onUnload()                       [停用/移除/重新整理/結束]
```

* 除 `onLoad` 外的回呼也在 FX 執行緒上，**絕對不要阻塞**。長工作自己開執行緒，
  更新介面用 `context.runLater(...)`。
* `dataDir()` 是共用的：讀沒問題，寫請只寫 `pluginDataDir()`。
* `onUnload()` 必須停掉執行緒、關掉檔案；啟動器在卸下你的 jar 之前會呼叫它。

### 6. 檔案位置

| 路徑 | 內容 |
|---|---|
| `data\plugins\*.jar` | 你的外掛 |
| `data\plugins\disabled.txt` | 每行一個 id / jar 檔名 / jar 的 SHA-256，`#` 是註解 |
| `data\plugins\README.txt` | 自動產生的說明 |
| `data\plugin-data\<id>\` | **你的**可寫資料夾（設定、快取、匯出） |
| `%LOCALAPPDATA%\BriskLauncher\` | 啟動器私有：帳號、`secret.key`、`acks.json`、`plugin-trust.json`，不要動 |
| `%LOCALAPPDATA%\BriskLauncher\logs\launcher.log` | 日誌（`launcher.log.0` / `.1` 是輪替） |

風險提示：首次載入第三方外掛會彈一次說明（記在 `acks.json`）。
`plugin-trust.json` 依外掛 id 記住 SHA-256，所以**更新**外掛時會再問一次「內容已變更」——
這是設計行為，不是 bug。

### 7. 認證實作

```java
public final class MySkinSiteProvider implements AuthProvider {

    @Override public AccountType type() { return AccountType.THIRD_PARTY; }
    @Override public String id() { return "myskinsite"; }          // 預設是型別名小寫
    @Override public String displayName() { return "我的皮膚站"; }

    @Override public LoginResult login(LoginCredentials credentials) { … }
    @Override public LoginResult refresh(StoredSession session) { … }
    @Override public boolean validate(StoredSession session) { … }
    @Override public GameSession toGameSession(StoredSession session) { … }
}
```

兩種註冊方式：

```
# jar 裡放
META-INF/services/com.brisklauncher.mcl.auth.api.AuthProvider
  -> 一行：com.example.hello.MySkinSiteProvider
```

```java
@Override
public void onLoad(PluginContext context) {
    context.registerAuthProvider(new MySkinSiteProvider());
}
```

同 `id()` 會互相覆蓋；同 `type()` 的實作在該帳號類型的登入 / 重新整理路徑裡生效。
外掛卸載時自動註銷，不會留下懸空實作。

### 8. 打包

```bat
rem 1) 先把啟動器編譯一次，API jar 才存在
mvn -o compile

rem 2) 編譯外掛（Java 21）
set JAVA_HOME=C:\Program Files\Java\jdk-21
"%JAVA_HOME%\bin\javac" -encoding UTF-8 --release 21 ^
    -cp "target\classes;build\install\brisk-launcher\lib\*" ^
    -d out src\com\example\hello\HelloPlugin.java

rem 3) plugin.json 與 i18n 必須在 jar 根目錄
copy plugin.json out
xcopy /E /I i18n out\i18n

"%JAVA_HOME%\bin\jar" --create --file hello-plugin.jar -C out .
```

最容易踩的幾條：

* `plugin.json` 必須在 **jar 根目錄**（放 `META-INF/` 底下無效）；
* 主類 `public` 且**有 public 無參建構子**；
* Java 21 或更舊的位元組碼（`--release 21`）；更新的類別檔案會 `UnsupportedClassVersionError`；
* 用 **classpath** 編譯沒問題，但執行時只有 `…plugin.api` / `…auth.api` / JavaFX / Jackson / JDK
  可達。引用啟動器內部類別編得過，載入時會 `IllegalAccessError`；
* 不要把啟動器類別 shade 進你的 jar —— 父載入器就是啟動器自己。

### 9. 相容性約定

* API 版本：`PluginApi.VERSION`（目前 `2`）。加方法升次版本，刪方法升主版本。
* `plugin.json` 的 `"apiVersion"` 用來宣告你需要的最低版本；新啟動器接受舊外掛，
  舊啟動器遇到更高的 `apiVersion` 會明確拒絕而不是崩掉。
* 只有 `plugin.api` / `auth.api` 兩個套件是穩定的；其它（`com.brisklauncher.mcl.*`、`ToolsPage` 內部等）
  任何版本都可能改。
* `config.properties` 與 `plugin-data\<id>\` 裡的檔案是你自己的：格式變了請自己遷移。
  永遠不要寫 `launcher.json` / `accounts.json` / `secret.key`。

### 10. 排查

| 現象 | 原因 / 處理 |
|---|---|
| 清單裡沒有外掛 | jar 不在 `data\plugins\`，或 `disabled.txt` 裡有它的 id / 檔名 / SHA-256 |
| `缺少 plugin.json` | `plugin.json` 不在 jar 根目錄 |
| `主类没有实现 LauncherPlugin` | `class` 寫錯，或還在 import 舊的 `.plugin.LauncherPlugin` |
| `需要更新版本的启动器` | `apiVersion` 比啟動器的 `PluginApi.VERSION` 高 |
| `缺少依赖插件：x` | `requires` 裡的外掛沒裝（或被停用） |
| `依赖循环` | 外掛互相依賴 |
| `插件内容已变更` | 你換了 jar；確認一次即可，摘要會重新釘住 |
| `NoSuchMethodError` / `AbstractMethodError` | 用了比啟動器更新的 API |
| `IllegalAccessError` | 引用了啟動器內部類別，應該只用 `plugin.api` |
| `IllegalStateException: Toolkit not initialized` | 在非 FX 執行緒或介面存在前碰了 JavaFX |
| 工具頁什麼都沒有 | 外掛只有導覽頁（沒覆寫 `createPanel`），會顯示提示而不是報錯 |

除錯開關：

```bat
java -Dmcl.plugins.quiet=1 …      rem 不記錄每次外掛載入
java -Dmcl.dev.tool=hello-plugin … rem 工具頁自動開啟這個外掛
```

無介面的契約自檢（不需要開啟動器）：

```bat
java -cp "target\classes;build\install\brisk-launcher\lib\*" ^
     com.brisklauncher.mcl.plugin.PluginSelfTest examples\hello-plugin\hello-plugin.jar
```

### 11. 外掛之間

* 在 `plugin.json` 寫 `"requires": ["other-id"]` → 載入順序自動處理，缺依賴會明確報錯而不是行為異常。
* 用 `context.services().provide("myplugin.api", obj)` 開放，用 `find` / `require` 取用。
  名字是全域的，請加前綴。
* 外掛的服務與認證實作隨卸載一起清掉 —— 請延遲查找（在 `createPanel` / `onShown` 裡），
  不要把別的外掛物件快取過頁面。

### 12. 安全

外掛與啟動器在**同一個行程**裡、同一個 Windows 使用者下執行，所以它能讀寫你的檔案、用網路、
讀到已經載入記憶體的帳號資料。沒有沙箱：

* 啟動器只做三件事：依 `disabled.txt` 拒絕、依 id 釘住 SHA-256、第一次彈風險說明；
* 外掛 API 刻意不把私有資料夾（帳號、`secret.key`、日誌）交出去；
* 不要記錄權杖、密碼、Cookie 或玩家個資；
* 你註冊的認證實作會用於真實登入，請當成密碼欄看待。

### 13. 刻意為之的限制

* 每個外掛只有一個工具面板，而且只能出現在「工具」頁裡。
* 不能掛進下載 / 安裝 / 啟動流程，不能加右鍵選單或頂欄自訂控件，不能注入 CSS
  （主題是 `data\themes\` 的事）。
* 語言包與主題包是**宣告式檔案**，不是程式碼，不能用來本地化你的外掛。
* 外掛不能單獨熱重載：「重新整理」會重載全部外掛。

---

## License

Plugins are your own work. The launcher is licensed under the terms in [LICENSE](../LICENSE).
