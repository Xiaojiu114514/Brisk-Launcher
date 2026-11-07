package com.brisklauncher.mcl.setting;

import java.util.Locale;

/**
 * 启动器全局设置（不可变）。
 * 与实例级设置（{@code InstanceSettings}）分开：这里是“跟着启动器走”的下载源、网络、语言等。
 */
public record LauncherSettings(
        Source source,
        String customMirror,
        int concurrency,
        int retries,
        int timeoutSeconds,
        String language,
        boolean autoLanguage,
        int defaultMemoryMb,
        boolean crashRuleRemoteUpdate,
        String frpPath,
        String proxy,
        boolean insecureTls,
        String microsoftClientId,
        boolean autoUpdate,
        boolean autoInstallUpdate,
        String updateChannel,
        String updateRepo) {

    /** 自動更新的倉庫（寫死，不提供自訂）。 */
    public static final String DEFAULT_UPDATE_REPO = "Xiaojiu114514/Brisk-Launcher";
    /** Gitee 鏡像倉庫（渠道選 gitee 時使用）。 */
    public static final String GITEE_UPDATE_REPO = "xiaojiu114514/Brisk-Launcher";

    /** 依更新渠道回傳倉庫（地址寫死）。 */
    public static String repoForChannel(String channel) {
        return "gitee".equalsIgnoreCase(channel) ? GITEE_UPDATE_REPO : DEFAULT_UPDATE_REPO;
    }

    /** 下载源。镜像只是“优先”，官方地址永远保留为回退。 */
    public enum Source {
        /** 自動：下載前測速，選最快的源（預設）。 */
        AUTO("Auto"),
        OFFICIAL("Official"),
        BMCLAPI("BMCLAPI"),
        CUSTOM("Custom");

        private final String display;

        Source(String display) {
            this.display = display;
        }

        public String display() {
            return display;
        }

        public static Source parse(String raw) {
            if (raw == null) {
                return AUTO;
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return AUTO;
            }
        }
    }

    public static LauncherSettings defaults() {
        return new LauncherSettings(
                Source.AUTO,
                "",
                4,
                3,
                30,
                "zh_CN",
                true,
                4096,
                true,
                "",
                "",
                false,
                // 注意：这一位是 microsoftClientId。历史上这里被误填成更新仓库名，
                // 导致第一次启动就带着一个「格式不正确的客户端 ID」，登录必失败。
                "",
                true,
                true,
                "github",
                DEFAULT_UPDATE_REPO);
    }

    public LauncherSettings {
        if (source == null) {
            source = Source.BMCLAPI;
        }
        customMirror = customMirror == null ? "" : customMirror.trim();
        concurrency = clamp(concurrency, 1, 32);
        retries = clamp(retries, 0, 10);
        timeoutSeconds = clamp(timeoutSeconds, 5, 300);
        language = language == null || language.isBlank() ? "zh_CN" : language;
        defaultMemoryMb = clamp(defaultMemoryMb, 512, 65536);
        frpPath = frpPath == null ? "" : frpPath.trim();
        proxy = proxy == null ? "" : proxy.trim();
        microsoftClientId = microsoftClientId == null ? "" : microsoftClientId.trim();
        updateChannel = "gitee".equalsIgnoreCase(updateChannel) ? "gitee" : "github";
        // 仓库地址写死（不让使用者自订，避免指向任意来源）
        updateRepo = repoForChannel(updateChannel);
    }

    public boolean hasCustomMirror() {
        return source == Source.CUSTOM && !customMirror.isBlank();
    }

    /** 只改语言，其余设置保持不动。 */
    /** 覆盖微软客户端 ID（从环境变量/本地文件读取时用，不进源码）。 */
    public LauncherSettings withMicrosoftClientId(String clientId) {
        return new LauncherSettings(source, customMirror, concurrency, retries, timeoutSeconds,
                language, autoLanguage, defaultMemoryMb, crashRuleRemoteUpdate, frpPath, proxy, insecureTls,
                clientId, autoUpdate, autoInstallUpdate, updateChannel, updateRepo);
    }
    public LauncherSettings withInsecureTls(boolean insecure) {
        return new LauncherSettings(source, customMirror, concurrency, retries, timeoutSeconds,
                language, autoLanguage, defaultMemoryMb, crashRuleRemoteUpdate, frpPath, proxy, insecure,
                microsoftClientId, autoUpdate, autoInstallUpdate, updateChannel, updateRepo);
    }

    public LauncherSettings withCrashRuleRemoteUpdate(boolean enabled) {
        return new LauncherSettings(source, customMirror, concurrency, retries, timeoutSeconds,
                language, autoLanguage, defaultMemoryMb, enabled, frpPath, proxy, insecureTls,
                microsoftClientId, autoUpdate, autoInstallUpdate, updateChannel, updateRepo);
    }

    public LauncherSettings withAutoUpdate(boolean enabled) {
        return new LauncherSettings(source, customMirror, concurrency, retries, timeoutSeconds,
                language, autoLanguage, defaultMemoryMb, crashRuleRemoteUpdate, frpPath, proxy, insecureTls,
                microsoftClientId, enabled, autoInstallUpdate, updateChannel, updateRepo);
    }

    /** 自动覆盖更新：检查到新版本就直接下载、覆盖并重新启动。 */
    public LauncherSettings withAutoInstallUpdate(boolean enabled) {
        return new LauncherSettings(source, customMirror, concurrency, retries, timeoutSeconds,
                language, autoLanguage, defaultMemoryMb, crashRuleRemoteUpdate, frpPath, proxy, insecureTls,
                microsoftClientId, autoUpdate, enabled, updateChannel, updateRepo);
    }

    public LauncherSettings withLanguage(String tag) {
        return new LauncherSettings(source, customMirror, concurrency, retries, timeoutSeconds,
                tag, autoLanguage, defaultMemoryMb, crashRuleRemoteUpdate, frpPath, proxy, insecureTls,
                microsoftClientId, autoUpdate, autoInstallUpdate, updateChannel, updateRepo);
    }

    /** 更新渠道是否可用（倉庫地址還沒填時不檢查更新）。 */
    public boolean updateConfigured() {
        return !updateRepo.isBlank();
    }

    /** UI 下拉框里的显示名（Custom 附带地址，便于识别）。 */
    public String sourceDisplay() {
        if (source == Source.CUSTOM && !customMirror.isBlank()) {
            return Source.CUSTOM.display() + " (" + customMirror + ")";
        }
        return source.display();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
