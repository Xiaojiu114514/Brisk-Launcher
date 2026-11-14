# Brisk Launcher · 简体中文

[English](../README.md) · **简体中文** · [繁體中文](README.zh-TW.md)

基于 Java 21 + JavaFX 的轻量 Minecraft Java 版启动器。无遥测，默认版本隔离，多线程下载。

> 仓库：[GitHub](https://github.com/Xiaojiu114514/Brisk-Launcher) · [Gitee](https://gitee.com/xiaojiu114514/Brisk-Launcher)

## 功能

- 多游戏根与多实例，版本隔离目录，实例级设置（Java、内存、启动参数）。
- Fabric / Forge / NeoForge 安装器、整合包导入导出。
- **启动器插件**：把 jar 放进 `data/plugins/` 就会被加载（可新增页面、工具、调用插件 API）。
- **第三方主题**：在 `data/themes/<id>/` 放 `theme.css`（可选 `theme.json` / `logo.png`）。切换即时生效，原生启动图也会跟着换色。
- **更多语言**：在 `data/lang/<locale>/` 放 `messages.properties` 即可。
## 插件开发

插件就是一个放在 `data\plugins\` 下的 jar，可以加工具面板、导航页、自己的语言包、自己的配置目录、
给别的插件用的服务，甚至账号认证实现。API 参考、`plugin.json` 格式、生命周期、打包与可运行示例见
**[插件开发指南 →](PLUGIN_DEV.md)** ·
**[API 签名 →](PLUGIN_API.md#简体中文)** · [示例插件](../examples/hello-plugin/)

> 第三方插件与主题由使用者自行承担风险。启动器会在第一次载入第三方插件前提示，但没有沙箱，
> 也没有逐项权限模型。

## 主题开发

主题就是 `data\themes\` 下的 CSS 包，可选 `theme.json`（多语言名称、多份 CSS、背景图与铺法/压暗、
设置页缩图）。启动器自带三个主题（`night`、`sakura`、`contrast`），切换即时生效。
**[主题开发指南 →](THEME_DEV.md)** · [示例主题](../examples/my-theme/)

## 环境要求

- Windows 10 / 11 x64
- Java 21（启动器可代为下载运行时）

## 构建与运行

```bat
set GRADLE_USER_HOME=%CD%\.cache\gradle
gradle bootExe
```

产物：`build\distributions\BriskLauncher.exe` —— 一个自包含的单文件（约 30 MB，jar 全部内嵌），首次启动会把 jar 释放到 `runtime/lib/` 再拉起 JVM。

- 构建需要 JDK 21；生成的 exe 运行时也需要 Java 21，缺失时会自动下载到 `data/jre`。
- 目录版（开发用）：`gradle installDist`，然后运行 `build\install\brisk-launcher\Brisk Launcher.vbs`。
- 发版：GitHub Release **只上传** `BriskLauncher.exe`；内置更新器下载的也是这个 exe，退出后自动替换自己。
## 数据位置

游戏数据（主题、语言包、插件、下载的 JRE）在 exe 旁边（便携）。启动器私有文件 —— 账号清单、
会话令牌、`launcher.json`、日志、信任/确认记录 —— 在 `%LOCALAPPDATA%\BriskLauncher\`：

| 路径 | 内容 |
|---|---|
| `runtime/lib/` | 首次启动从 exe 里释放出来的 jar（自动复用、自动清理旧版本） |
| `data/` | 主题、语言包、插件、`plugin-data/`、缓存、下载的 JRE |
| `minecraft/` | 内置游戏目录（`versions/<id>/`） |
| `%LOCALAPPDATA%\BriskLauncher\` | `accounts.json`、会话令牌、`launcher.json`、`secret.key`、`acks.json`、`plugin-trust.json`、`logs/` —— 同一个 Windows 用户的所有启动器副本共用 |

私有文件不在 `data/` 里，所以把 exe 换到别的目录运行也还是同一份账号与设置。日志每次启动一个文件
（`logs/yyyy-MM-dd-HHmmss.log`），不是轮转的 `launcher.log`。
## 许可证

见 [LICENSE](../LICENSE)。
