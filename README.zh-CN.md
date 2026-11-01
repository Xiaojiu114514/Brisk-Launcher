# Brisk Launcher · 简体中文

[English](README.md) · **简体中文** · [繁體中文](README.zh-TW.md)

基于 Java 21 + JavaFX 的轻量 Minecraft Java 版启动器。无遥测，默认版本隔离，多线程下载。

> 仓库：[GitHub](https://github.com/Xiaojiu114514/Brisk-Launcher) · [Gitee](https://gitee.com/xiaojiu114514/Brisk-Launcher)

## 功能

- 原版 / Forge / NeoForge / Fabric 安装，加载器并入版本，默认版本隔离
- 整合包导入导出，Mod / 资源包 / 光影 / 存档管理
- 微软 / 离线 / 第三方账号，多语言界面，spark 报告与崩溃分析

## 插件开发

插件 API、`plugin.json` 格式、生命周期与打包说明见 **[插件开发指南 →](PLUGIN_DEV.md)**

## 环境要求

- Windows 10 / 11 x64
- Java 21（启动器可代为下载运行时）

## 构建与运行

```bat
set JAVA_HOME=C:\Program Files\Java\jdk-21
mvn -o compile
gradle installDist installLauncherScripts
build\install\brisk-launcher\Brisk Launcher.vbs
```

需要看日志时用 `build\install\brisk-launcher\Brisk Launcher Debug.cmd`（保留控制台）。

## 数据位置

| 路径 | 内容 |
|---|---|
| `%LOCALAPPDATA%\BriskLauncher\` | 账号与会话、启动器设置（私密目录，仅当前用户可访问） |
| `<启动器目录>\data\` | 缓存、日志、下载的 JDK、主题包、插件、`crash-rules.json` |
| `versions/<实例名>/` | 每个实例一个文件夹（隔离的 mods / config / saves） |

## 许可证

见 [LICENSE](LICENSE)。
