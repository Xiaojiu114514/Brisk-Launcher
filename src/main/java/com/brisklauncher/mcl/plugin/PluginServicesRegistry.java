package com.brisklauncher.mcl.plugin;

import com.brisklauncher.mcl.plugin.api.PluginServices;
import com.brisklauncher.mcl.security.SafeLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件之間共用的服務登記處（全域一份，依插件 id 記錄歸屬，卸載時自動清掉）。
 */
final class PluginServicesRegistry {

    private record Holder(String pluginId, Object service) {
    }

    private final Map<String, Holder> services = new ConcurrentHashMap<>();

    PluginServices view(String pluginId) {
        return new PluginServices() {
            @Override
            public void provide(String name, Object service) {
                PluginServicesRegistry.this.provide(pluginId, name, service);
            }

            @Override
            public <T> T find(String name, Class<T> type) {
                return PluginServicesRegistry.this.find(name, type);
            }

            @Override
            public <T> T require(String name, Class<T> type) {
                T found = find(name, type);
                if (found == null) {
                    throw new IllegalStateException("插件服务不存在或类型不符：" + name);
                }
                return found;
            }

            @Override
            public boolean has(String name) {
                return services.containsKey(name);
            }
        };
    }

    void provide(String pluginId, String name, Object service) {
        if (name == null || name.isBlank()) {
            return;
        }
        if (service == null) {
            services.remove(name);
            return;
        }
        services.put(name, new Holder(pluginId, service));
    }

    @SuppressWarnings("unchecked")
    <T> T find(String name, Class<T> type) {
        if (name == null || type == null) {
            return null;
        }
        Holder holder = services.get(name);
        if (holder == null) {
            return null;
        }
        if (!type.isInstance(holder.service())) {
            SafeLog.warn("[plugin] 服务类型不符：" + name + " 需要 " + type.getSimpleName()
                    + "，实际是 " + holder.service().getClass().getName());
            return null;
        }
        return (T) holder.service();
    }

    /** 清掉某個插件登記的全部服務。 */
    void clear(String pluginId) {
        if (pluginId == null) {
            return;
        }
        List<String> owned = new ArrayList<>();
        for (Map.Entry<String, Holder> entry : services.entrySet()) {
            if (pluginId.equals(entry.getValue().pluginId())) {
                owned.add(entry.getKey());
            }
        }
        for (String name : owned) {
            services.remove(name);
        }
    }

    /** 目前登記了哪些服務（除錯 / 工具頁用）。 */
    List<String> names() {
        return List.copyOf(services.keySet());
    }

    void clearAll() {
        services.clear();
    }
}
