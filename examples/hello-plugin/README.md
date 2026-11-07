# Hello Plugin（示例插件）

这是 [`PLUGIN_DEV.md`](../../PLUGIN_DEV.md) 里用的可运行示例。它一次示范了当前插件 API 的全部主要能力：

| 能力 | 在哪里 | 说明 |
|---|---|---|
| 工具面板 | `HelloPlugin.createPanel` → `HelloPanel` | 挂在「工具」页里的面板 |
| 导航页 | `HelloPlugin.pages` → `HelloPage` | 顶栏多一个「👋 Hello」入口 |
| 插件语言包 | `i18n/messages*.properties` | 简中 / 繁中（TW、HK）/ 英文，缺键自动回退 |
| 插件配置 | `context.config()` | 写到 `data/plugin-data/hello-plugin/config.properties` |
| 插件数据夹 | `context.pluginDataDir()` | 存自己的文件，启动器已建好 |
| 插件服务 | `context.services().provide("hello.greeting", …)` | 让别的插件能找到这个插件 |
| 日志 | `context.log().info(...)` | 写进启动器日志，带 `[plugin:hello-plugin]` 前缀 |
| 对话框 | `context.info(...)` | 用启动器统一样式的对话框 |

## 构建

```powershell
# 先在项目根目录把启动器编译一次（二者之一）
mvn -o compile
gradle installDist

# 再打包示例插件
powershell -ExecutionPolicy Bypass -File build.ps1
# 直接装进 data\plugins\：
powershell -ExecutionPolicy Bypass -File build.ps1 -Install
```

产物是 `hello-plugin.jar`（`plugin.json` 与 `i18n/` 在 jar 根目录）。

## 试跑

1. 把 `hello-plugin.jar` 丢进 `data\plugins\`；
2. 打开启动器 →「工具」→「刷新」；
3. 首次载入会弹一次第三方插件风险说明（全局只弹一次，记在 `acks.json`）；
4. 「工具 → Hello Plugin」看工具面板，顶栏「👋 Hello」看导航页。

## 只改一处试试

把 `i18n/messages_zh_CN.properties` 里的 `hello.greeting=你好，{0}！` 改成别的，重新构建，
再点「刷新」——语言包会跟着 jar 一起换掉。要新增语言就加 `i18n/messages_<tag>.properties`
（例如 `messages_ja_JP.properties`），插件会自己发现。
