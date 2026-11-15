package com.brisklauncher.mcl.util;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 構建期注入的版本資訊。
 *
 * <p>{@code build.gradle} 的 {@code generateBuildInfo} 會把 {@code gradle.properties} 的
 * {@code version} 寫進 jar 內的 {@code assets/brisk-build.properties}（和微軟客戶端 ID 同一份），
 * Java 端只讀這裡 —— 換版時不用再改 {@code UpdateService}、User-Agent、{@code GameLauncher} 等
 * 十幾個地方。
 *
 * <p>非 Gradle 執行（例如直接 {@code mvn compile} 後跑 classes 目錄）讀不到建置檔時回退
 * {@code "dev"}；官方 exe / installDist 一定有這份資源。
 */
public final class BuildInfo {

    private static final String RESOURCE = "/assets/brisk-build.properties";
    private static final String FALLBACK = "dev";
    private static final String VERSION = loadVersion();

    private BuildInfo() {
    }

    /** 例如 {@code 1.1.6}；建置資源缺失時為 {@code dev}。 */
    public static String version() {
        return VERSION;
    }

    /** 統一的 User-Agent 前綴：{@code BriskLauncher/<version>}。 */
    public static String userAgent() {
        return "BriskLauncher/" + VERSION;
    }

    /** 是否是沒有建置版本資源的開發執行（Maven/IDE 直接跑 classes）。 */
    public static boolean isDevBuild() {
        return FALLBACK.equals(VERSION);
    }

    private static String loadVersion() {
        try (InputStream input = BuildInfo.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                return FALLBACK;
            }
            Properties properties = new Properties();
            properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            String value = properties.getProperty("build.version", "").trim();
            return value.isEmpty() ? FALLBACK : value;
        } catch (Exception error) {
            return FALLBACK;
        }
    }
}
