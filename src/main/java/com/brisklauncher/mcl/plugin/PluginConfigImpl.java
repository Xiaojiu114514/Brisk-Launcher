package com.brisklauncher.mcl.plugin;

import com.brisklauncher.mcl.plugin.api.PluginConfig;
import com.brisklauncher.mcl.security.SafeLog;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * {@link PluginConfig} 的實現：{@code config.properties}（UTF-8）+ 原子落盤。
 */
final class PluginConfigImpl implements PluginConfig {

    private final Path file;
    private final Properties values = new Properties();
    private final Object lock = new Object();

    PluginConfigImpl(Path file) {
        this.file = file;
        load();
    }

    private void load() {
        synchronized (lock) {
            values.clear();
            if (!Files.isRegularFile(file)) {
                return;
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                values.load(reader);
            } catch (Exception error) {
                SafeLog.warn("[plugin] 读取插件配置失败：" + file.getFileName());
            }
        }
    }

    @Override
    public String get(String key, String fallback) {
        synchronized (lock) {
            String value = values.getProperty(key);
            return value == null ? fallback : value;
        }
    }

    @Override
    public int getInt(String key, int fallback) {
        try {
            return Integer.parseInt(get(key, Integer.toString(fallback)).trim());
        } catch (NumberFormatException bad) {
            return fallback;
        }
    }

    @Override
    public long getLong(String key, long fallback) {
        try {
            return Long.parseLong(get(key, Long.toString(fallback)).trim());
        } catch (NumberFormatException bad) {
            return fallback;
        }
    }

    @Override
    public boolean getBoolean(String key, boolean fallback) {
        String value = get(key, Boolean.toString(fallback)).trim();
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }

    @Override
    public void set(String key, String value) {
        if (key == null || key.isBlank()) {
            return;
        }
        synchronized (lock) {
            if (value == null) {
                values.remove(key);
            } else {
                values.setProperty(key, value);
            }
        }
        save();
    }

    @Override
    public void save() {
        synchronized (lock) {
            Path temp = null;
            try {
                Files.createDirectories(file.getParent());
                temp = Files.createTempFile(file.getParent(), "config-", ".tmp");
                try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                    values.store(writer, "Brisk Launcher plugin config");
                }
                try {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (java.io.IOException atomicFailure) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
                temp = null;
            } catch (Exception error) {
                SafeLog.warn("[plugin] 保存插件配置失败：" + file.getFileName());
            } finally {
                if (temp != null) {
                    try {
                        Files.deleteIfExists(temp);
                    } catch (Exception ignored) {
                        // 尽力而为
                    }
                }
            }
        }
    }

    @Override
    public Path file() {
        return file;
    }

    /** 這個鍵存在嗎（介面沒有，測試與內部用）。 */
    boolean has(String key) {
        synchronized (lock) {
            return values.getProperty(key) != null;
        }
    }
}
