package com.brisklauncher.mcl.setting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.brisklauncher.mcl.AppPaths;
import com.brisklauncher.mcl.security.SafeLog;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code <launcher>/launcher.json} 读写。
 * 只放非敏感配置；令牌永远在 Windows 凭据管理器。
 */
public final class SettingsStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private LauncherSettings cached;

    public SettingsStore() {
        this(AppPaths.launcherConfig());
    }

    public SettingsStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public synchronized LauncherSettings load() {
        if (cached != null) {
            return cached;
        }
        cached = read();
        return cached;
    }

    /** 只在使用者显式保存时覆盖内存缓存。 */
    public synchronized void save(LauncherSettings settings) {
        cached = settings;
        try {
            Files.createDirectories(file.getParent());
            ObjectNode root = MAPPER.createObjectNode();
            root.put("version", 2);
            root.put("source", settings.source().name());
            root.put("customMirror", settings.customMirror());
            root.put("concurrency", settings.concurrency());
            root.put("retries", settings.retries());
            root.put("timeoutSeconds", settings.timeoutSeconds());
            root.put("language", settings.language());
            root.put("defaultMemoryMb", settings.defaultMemoryMb());
            root.put("crashRuleRemoteUpdate", settings.crashRuleRemoteUpdate());
            root.put("frpPath", settings.frpPath());
            root.put("proxy", settings.proxy());
            root.put("insecureTls", settings.insecureTls());
            // 不把客户端 ID 明文写盘（静态加密；换机后解不开时回退为空）
            // 安全审计 M-03：加密失败时 SecretBox 返回 null —— 那就干脆不写这个字段，
            // 绝不以明文落盘。
            String encryptedClientId = com.brisklauncher.mcl.security.SecretBox.encrypt(
                    settings.microsoftClientId());
            root.put("microsoftClientId", encryptedClientId == null ? "" : encryptedClientId);
            // CurseForge API key 同样不明文落盘
            String encryptedCurseForgeKey = com.brisklauncher.mcl.security.SecretBox.encrypt(
                    settings.curseforgeApiKey());
            root.put("curseforgeApiKey", encryptedCurseForgeKey == null ? "" : encryptedCurseForgeKey);
            root.put("autoLanguage", settings.autoLanguage());
        root.put("autoUpdate", settings.autoUpdate());
            root.put("autoInstallUpdate", settings.autoInstallUpdate());
            root.put("updateChannel", settings.updateChannel());
            root.put("updateRepo", settings.updateRepo());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
        } catch (Exception e) {
            SafeLog.warn("保存 launcher.json 失败");
        }
    }

    /** 真正第一次啟動時（沒有 launcher.json）用系統語系當預設，之後不再讀系統。 */
    private static String systemLanguage() {
        String tag = java.util.Locale.getDefault().toLanguageTag();
        String lower = tag == null ? "" : tag.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("tw") || lower.contains("hant")) {
            return "zh_TW";
        }
        if (lower.contains("hk") || lower.contains("mo")) {
            return "zh_TW";
        }
        if (lower.startsWith("zh")) {
            return "zh_CN";
        }
        return lower.startsWith("en") ? "en_US" : "zh_CN";
    }

    private LauncherSettings read() {
        LauncherSettings defaults = LauncherSettings.defaults();
        if (!Files.isRegularFile(file)) {
            // 真正第一次启动：用系统语言当预设并写入设定，之后一律读设定
            String lang = systemLanguage();
            LauncherSettings first = lang == null || lang.isBlank()
                    ? defaults : defaults.withLanguage(lang);
            save(first);
            return first;
        }
        try {
            JsonNode node = MAPPER.readTree(file.toFile());
            // 历史 bug：LauncherSettings.defaults() 曾把更新仓库名写进 microsoftClientId，
            // 于是首次启动的 launcher.json 里就带着一个「格式不正确的客户端 ID」，登录必失败。
            // 读到时直接丢弃（下次保存会写回空值），避免使用者被这条假数据卡住。
            String storedClientId = com.brisklauncher.mcl.security.SecretBox.decrypt(
                    node.path("microsoftClientId").asText(""));
            String storedCurseForgeKey = com.brisklauncher.mcl.security.SecretBox.decrypt(
                    node.path("curseforgeApiKey").asText(""));
            if (storedClientId.equals(LauncherSettings.DEFAULT_UPDATE_REPO)
                    || storedClientId.equals(LauncherSettings.GITEE_UPDATE_REPO)) {
                SafeLog.info("已丢弃误写入的客户端 ID（旧版本 defaults 参数顺序错误）");
                storedClientId = "";
            }
            return new LauncherSettings(
                    LauncherSettings.Source.parse(node.path("source").asText(defaults.source().name())),
                    node.path("customMirror").asText(defaults.customMirror()),
                    node.path("concurrency").asInt(defaults.concurrency()),
                    node.path("retries").asInt(defaults.retries()),
                    node.path("timeoutSeconds").asInt(defaults.timeoutSeconds()),
                    node.path("language").asText(defaults.language()),
                    node.path("autoLanguage").asBoolean(defaults.autoLanguage()),
                    node.path("defaultMemoryMb").asInt(defaults.defaultMemoryMb()),
                    node.path("crashRuleRemoteUpdate").asBoolean(defaults.crashRuleRemoteUpdate()),
                    node.path("frpPath").asText(defaults.frpPath()),
                    node.path("proxy").asText(defaults.proxy()),
                    node.path("insecureTls").asBoolean(defaults.insecureTls()),
                    storedClientId,
                    node.path("autoUpdate").asBoolean(defaults.autoUpdate()),
                    node.path("autoInstallUpdate").asBoolean(defaults.autoInstallUpdate()),
                    node.path("updateChannel").asText(defaults.updateChannel()),
                    node.path("updateRepo").asText(defaults.updateRepo()),
                    storedCurseForgeKey);
        } catch (Exception e) {
            SafeLog.warn("读取 launcher.json 失败，回退默认设置");
            return defaults;
        }
    }
}
