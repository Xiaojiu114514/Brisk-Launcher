# Brisk Launcher · 繁體中文

[English](README.md) · [简体中文](README.zh-CN.md) · **繁體中文**

以 Java 21 + JavaFX 撰寫的輕量 Minecraft Java 版啟動器。無遙測、預設版本隔離、多執行緒下載。

> 倉庫：[GitHub](https://github.com/Xiaojiu114514/Brisk-Launcher) · [Gitee](https://gitee.com/xiaojiu114514/Brisk-Launcher)

## 功能

- 多遊戲根與多實例，版本隔離目錄，實例層級設定（Java、記憶體、啟動參數）。
- Fabric / Forge / NeoForge 安裝器、整合包匯入匯出。
- **啟動器外掛**：把 jar 放進 `data/plugins/` 就會被載入（可新增頁面、工具、呼叫外掛 API）。
- **第三方主題**：在 `data/themes/<id>/` 放 `theme.css`（可選 `theme.json` / `logo.png`）。切換即時生效，原生啟動圖也會跟著換色。
- **更多語言**：在 `data/lang/<locale>/` 放 `messages.properties` 即可。
## 外掛開發

外掛就是一個放在 `data\plugins\` 下的 jar，可以加工具面板、導覽頁、自己的語言包、自己的設定資料夾、
給別的外掛用的服務，甚至帳號認證實作。API 參考、`plugin.json` 格式、生命週期、打包與可執行範例見
**[外掛開發指南 →](PLUGIN_DEV.md)** · [範例外掛](examples/hello-plugin/)

## 主題開發

主題就是 `data\themes\` 下的 CSS 包，可選 `theme.json`（多語言名稱、多份 CSS、背景圖與鋪法/壓暗、
設定頁縮圖）。啟動器自帶三個主題（`night`、`sakura`、`contrast`），切換即時生效。
**[主題開發指南 →](THEME_DEV.md)** · [範例主題](examples/my-theme/)

## 環境需求

- Windows 10 / 11 x64
- Java 21（啟動器可代為下載執行環境）

## 建置與執行

```bat
set GRADLE_USER_HOME=%CD%\.cache\gradle
gradle bootExe
```

產物：`build\distributions\BriskLauncher.exe` —— 一個自帶所有 jar 的單一檔案（約 30 MB），首次啟動會把 jar 釋放到 `runtime/lib/` 再拉起 JVM。

- 建置需要 JDK 21；產生的 exe 執行時也需要 Java 21，缺少時會自動下載到 `data/jre`。
- 目錄版（開發用）：`gradle installDist`，再執行 `build\install\brisk-launcher\Brisk Launcher.vbs`。
- 發佈：GitHub Release **只上傳** `BriskLauncher.exe`；內建更新器下載的也是這個 exe，結束後自動替換自己。
## 資料位置

除了帳號清單，所有執行期資料都在 exe 旁邊（可攜）：

| 路徑 | 內容 |
|---|---|
| `runtime/lib/` | 首次啟動從 exe 裡釋放出來的 jar（自動重用、自動清理舊版本） |
| `data/` | 啟動器設定、主題、語言包、外掛、日誌、快取 |
| `minecraft/` | 內建遊戲目錄（`versions/<id>/`） |
| `%LOCALAPPDATA%\BriskLauncher\` | `accounts.json` 與會話權杖（同一個 Windows 使用者的所有啟動器副本共用） |

帳號不在 `data/` 裡，所以把 exe 換到別的目錄執行也還是同一份帳號。
## 授權

見 [LICENSE](LICENSE)。
