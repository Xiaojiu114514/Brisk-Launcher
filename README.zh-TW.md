# Brisk Launcher · 繁體中文

[English](README.md) · [简体中文](README.zh-CN.md) · **繁體中文**

以 Java 21 + JavaFX 撰寫的輕量 Minecraft Java 版啟動器。無遙測、預設版本隔離、多執行緒下載。

> 倉庫：[GitHub](https://github.com/Xiaojiu114514/Brisk-Launcher) · [Gitee](https://gitee.com/xiaojiu114514/Brisk-Launcher)

## 功能

- 原版 / Forge / NeoForge / Fabric 安裝，載入器併入版本，預設版本隔離
- 整合包匯入匯出，Mod / 資源包 / 光影 / 存檔管理
- 微軟 / 離線 / 第三方帳號，多語言介面，spark 報告與崩潰分析

## 外掛開發

外掛 API、`plugin.json` 格式、生命週期與打包說明見 **[外掛開發指南 →](PLUGIN_DEV.md)**

## 環境需求

- Windows 10 / 11 x64
- Java 21（啟動器可代為下載執行環境）

## 建置與執行

```bat
set JAVA_HOME=C:\Program Files\Java\jdk-21
mvn -o compile
gradle installDist installLauncherScripts
build\install\brisk-launcher\Brisk Launcher.vbs
```

要看日誌時用 `build\install\brisk-launcher\Brisk Launcher Debug.cmd`（保留主控台）。

## 資料位置

| 路徑 | 內容 |
|---|---|
| `%LOCALAPPDATA%\BriskLauncher\` | 帳號與會話、啟動器設定（私密目錄，僅目前使用者可存取） |
| `<啟動器目錄>\data\` | 快取、日誌、下載的 JDK、主題包、外掛、`crash-rules.json` |
| `versions/<實例名稱>/` | 每個實例一個資料夾（隔離的 mods / config / saves） |

## 授權

見 [LICENSE](LICENSE)。
