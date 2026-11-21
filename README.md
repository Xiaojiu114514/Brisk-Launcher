# Brisk Launcher

**English** · [简体中文](docs/README.zh-CN.md) · [繁體中文](docs/README.zh-TW.md)

A lightweight Minecraft: Java Edition launcher built with Java 21 + JavaFX. No telemetry, version
isolation by default, multi-threaded downloads.

> Repository: [GitHub](https://github.com/Xiaojiu114514/Brisk-Launcher) · [Gitee](https://gitee.com/xiaojiu114514/Brisk-Launcher)

## Features

- Multiple game roots and instances, with version-isolated folders and per-instance settings (Java, memory, arguments).
- Fabric / Forge / NeoForge installers, modpack import and export.
- **Launcher plugins**: drop a jar into `data/plugins/` and the launcher loads it (new pages, tools, plugin API).
- **Third-party themes**: put a folder under `data/themes/<id>/` with `theme.css` (optionally `theme.json` / `logo.png`). Themes switch instantly and are also applied to the native splash image.
- **Extra languages**: put `messages.properties` under `data/lang/<locale>/`.
## Plugin development

A plugin is a jar in `data\plugins\`. It can add a tool panel, a navigation page, its own language pack,
its own config folder, services for other plugins, and even account (auth) providers.
API reference, `plugin.json` format, lifecycle, packaging and a working sample:
**[Plugin development guide →](docs/PLUGIN_DEV.md)** ·
**[API signatures →](docs/PLUGIN_API.md)** · [sample plugin](examples/hello-plugin/)

> Third-party plugins and themes run at your own risk. The launcher warns before the first third-party
> plugin load, but there is no sandbox and no per-capability permission model.

## Theme development

Themes are CSS packs in `data\themes\` with an optional `theme.json` (multi-language name, multiple
CSS files, background image with fit/dim, settings thumbnail). Three themes ship with the launcher
(`night`, `sakura`, `contrast`) and switching is instant.
**[Theme development guide →](docs/THEME_DEV.md)** · [sample theme](examples/my-theme/)

## Requirements

- Windows 10 / 11 x64
- Java 21 (the launcher can download a runtime for you)

## Build & run

```bat
set GRADLE_USER_HOME=%CD%\.cache\gradle
gradle bootExe
```

Output: `build\distributions\BriskLauncher.exe` — one self-contained file (~30 MB) with all jars embedded. It extracts them to `runtime/lib/` on first launch and starts the JVM.

- Building needs JDK 21; the produced exe also needs Java 21 at runtime and downloads Oracle JDK 21 to `data/jdk` on first launch if missing.
- Directory build (for development): `gradle installDist`, then `build\install\brisk-launcher\Brisk Launcher.vbs`.
- Releasing: upload **only** `BriskLauncher.exe` to GitHub Releases. The built-in updater downloads that same exe and replaces itself after the launcher exits.
## Data locations

Game data (themes, language packs, plugins, the downloaded JDK) lives next to the exe (portable).
Launcher-private files — account list, session tokens, `launcher.json`, logs and the trust/ack records —
live in `%LOCALAPPDATA%\BriskLauncher\`:

| Path | Contents |
|---|---|
| `runtime/lib/` | jars extracted from the exe on first launch; reused and pruned automatically |
| `data/` | themes, language packs, plugins, `plugin-data/`, cache, downloaded JDK |
| `minecraft/` | the built-in game directory (`versions/<id>/`) |
| `%LOCALAPPDATA%\BriskLauncher\` | `accounts.json`, session tokens, `launcher.json`, `secret.key`, `acks.json`, `plugin-trust.json`, `logs/` — shared by every copy of the launcher on this Windows user |

Because the private files live outside `data/`, running the exe from another folder still sees the same
accounts and settings. Logs are one file per start (`logs/yyyy-MM-dd-HHmmss.log`), not a rotating
`launcher.log`.
## License

See [LICENSE](LICENSE).
