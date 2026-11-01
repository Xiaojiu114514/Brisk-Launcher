# Plugin Development Guide

**English** · [简体中文 ↓](#简体中文)

[← Back to README](README.md) · [简体中文版 README](README.zh-CN.md) · [繁體中文版 README](README.zh-TW.md)

---

## English

### 1. What a plugin can do

A plugin is a standalone jar placed in `<launcher data>/plugins/` and loaded at startup. It receives a
`PluginContext` through the `LauncherPlugin` interface and can register tools on the Tools page and use
the launcher's public data directory.

> **Risk notice**: plugins run with your Windows user account and can read saved account data, files and
> the network. The launcher takes responsibility only for its own code; third-party plugins are used at
> your own risk and are outside our support scope. The first external plugin load shows a one-time notice
> recorded in `acks.json`; it will not be shown again.

### 2. Minimal plugin

```
my-plugin/
  plugin.json
  com/example/MyPlugin.class
```

`plugin.json` (required: `id`, `class`):

```json
{
  "id": "my-plugin",
  "name": "My Plugin",
  "class": "com.example.MyPlugin",
  "description": "One line",
  "version": "1.0.0"
}
```

Main class implements `com.brisklauncher.mcl.plugin.api.LauncherPlugin`:

```java
package com.example;

import com.brisklauncher.mcl.plugin.api.LauncherPlugin;
import com.brisklauncher.mcl.plugin.api.PluginContext;

public final class MyPlugin implements LauncherPlugin {

    @Override
    public String name() {
        return "My Plugin";
    }

    @Override
    public String description() {
        return "Example plugin";
    }

    @Override
    public void onLoad(PluginContext context) {
        // init: read config, register tools
    }

    @Override
    public void onUnload() {
        // release resources
    }
}
```

### 3. Lifecycle

| Method | When | Notes |
|---|---|---|
| `onLoad(PluginContext)` | after the jar is loaded | do not block for long |
| `onUnload()` | plugin disabled or launcher exit | close threads and file handles |

### 4. Packaging

```bat
javac -cp "build\install\brisk-launcher\lib\*" -d out src\com\example\MyPlugin.java
jar --create --file my-plugin.jar -C out .
```

Drop `my-plugin.jar` into `data\plugins\` and restart the launcher.

### 5. Disabling & troubleshooting

- Disabled plugins are **neither loaded nor executed**. `data\plugins\disabled.txt` accepts three forms:
  the **plugin id**, the **jar file name**, or the **jar SHA-256** (so a plugin cannot bypass the list by
  reporting a different id).
- Load failures appear on the Tools page; logs are in `data\logs\launcher.log.0`.

### 6. Compatibility contract

- Compile against Java 21, same as the launcher.
- Use only types under `com.brisklauncher.mcl.plugin.api`; other internals are not guaranteed stable.
- Never modify the launcher's private data (`accounts.json` / `launcher.json` / `secret.key`).

---

## 简体中文

[English ↑](#plugin-development-guide) · [← 返回 README（简体）](README.zh-CN.md) · [繁體中文版 README](README.zh-TW.md)

### 1. 插件能做什么

插件是独立的 jar，放在 `<启动器数据目录>/plugins/` 下，启动时自动加载。通过 `LauncherPlugin` 接口拿到
`PluginContext`，可以注册工具页里的工具、读写启动器公开的数据目录等。

> **风险提示**：插件会以你的 Windows 用户身份运行，可以读取启动器保存的账号信息、访问文件与网络。
> 启动器只对自身代码负责；第三方插件属于使用者自担风险，不在支持范围内。首次加载外部插件时会弹一次说明，
> 确认后写入 `acks.json`，之后不再提示。

### 2. 最小插件

目录结构：

```
my-plugin/
  plugin.json
  com/example/MyPlugin.class
```

`plugin.json`（必需字段：`id`、`class`）：

```json
{
  "id": "my-plugin",
  "name": "我的插件",
  "class": "com.example.MyPlugin",
  "description": "一行说明",
  "version": "1.0.0"
}
```

主类实现 `com.brisklauncher.mcl.plugin.api.LauncherPlugin`：

```java
package com.example;

import com.brisklauncher.mcl.plugin.api.LauncherPlugin;
import com.brisklauncher.mcl.plugin.api.PluginContext;

public final class MyPlugin implements LauncherPlugin {

    @Override
    public String name() {
        return "我的插件";
    }

    @Override
    public String description() {
        return "示例插件";
    }

    @Override
    public void onLoad(PluginContext context) {
        // 初始化：读取配置、注册工具
    }

    @Override
    public void onUnload() {
        // 释放资源
    }
}
```

### 3. 生命周期

| 方法 | 时机 | 注意事项 |
|---|---|---|
| `onLoad(PluginContext)` | 启动时加载插件后 | 不要在这里做长时间阻塞操作 |
| `onUnload()` | 禁用插件或启动器退出 | 释放线程、文件句柄 |

### 4. 打包

```bat
javac -cp "build\install\brisk-launcher\lib\*" -d out src\com\example\MyPlugin.java
jar --create --file my-plugin.jar -C out .
```

把 `my-plugin.jar` 放进 `data\plugins\`，重启启动器即可。

### 5. 禁用与排查

- 被禁用的插件**不会加载也不会执行**；`data\plugins\disabled.txt` 支持三种写法：
  **插件 id**、**jar 文件名**、**jar 的 SHA-256**（防止插件自报 id 绕过）。
- 加载失败会显示在工具页列表里；日志在 `data\logs\launcher.log.0`。

### 6. 兼容性约定

- 插件编译目标建议 Java 21，与启动器一致。
- 只使用 `com.brisklauncher.mcl.plugin.api` 包下的类型；其它内部类不保证跨版本兼容。
- 不要修改启动器自己的数据文件（`accounts.json` / `launcher.json` / `secret.key`）。
