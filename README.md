# Brisk Launcher

**English** · [简体中文](README.zh-CN.md) · [繁體中文](README.zh-TW.md)

A lightweight Minecraft: Java Edition launcher built with Java 21 + JavaFX. No telemetry, version
isolation by default, multi-threaded downloads.

> Repository: [GitHub](https://github.com/Xiaojiu114514/Brisk-Launcher) · [Gitee](https://gitee.com/xiaojiu114514/Brisk-Launcher)

## Features

- Vanilla / Forge / NeoForge / Fabric installs, the loader is merged into the version, isolation by default
- Modpack import and export; mods, resource packs, shaders, worlds and configs
- Microsoft / offline / third-party accounts, multi-language UI, spark report and crash analysis

## Plugin development

Plugin API, `plugin.json` format, lifecycle and packaging: **[Plugin development guide →](PLUGIN_DEV.md)**

## Requirements

- Windows 10 / 11 x64
- Java 21 (the launcher can download a runtime for you)

## Build & run

```bat
set JAVA_HOME=C:\Program Files\Java\jdk-21
mvn -o compile
gradle installDist installLauncherScripts
build\install\brisk-launcher\Brisk Launcher.vbs
```

Debug (console visible): `build\install\brisk-launcher\Brisk Launcher Debug.cmd`

## Data locations

| Path | Content |
|---|---|
| `%LOCALAPPDATA%\BriskLauncher\` | accounts and sessions, launcher settings (private, owner-only ACL) |
| `<launcher>\data\` | cache, logs, downloaded JDK, themes, plugins, `crash-rules.json` |
| `versions/<name>/` | one folder per instance (isolated mods / config / saves) |

## License

See [LICENSE](LICENSE).
