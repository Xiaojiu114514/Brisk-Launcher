package com.brisklauncher.mcl.game;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Locale;

/**
 * 版本 JSON 的 rules 求值（Mojang 官方语义）。
 *
 * <p>规则列表为空 → 允许；规则列表非空 → 初始不允许，按顺序把每条匹配规则的动作
 * 覆盖上去，最后一条匹配规则说了算。这是官方启动器的行为，也是 HMCL/PCL 的实现方式。
 */
public final class Rules {

    /** 启动时可能影响规则判定的特性开关。 */
    public record Features(boolean demoUser, int width, int height) {

        public static Features none() {
            return new Features(false, 0, 0);
        }

        public boolean hasCustomResolution() {
            return width > 0 && height > 0;
        }
    }

    public static final String OS_NAME;
    public static final String OS_ARCH;
    public static final String OS_VERSION;

    static {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            OS_NAME = "windows";
        } else if (os.contains("mac") || os.contains("darwin")) {
            OS_NAME = "osx";
        } else {
            OS_NAME = "linux";
        }

        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            OS_ARCH = "arm64";
        } else if (arch.contains("64")) {
            OS_ARCH = "x86_64";
        } else {
            OS_ARCH = "x86";
        }
        OS_VERSION = System.getProperty("os.version", "");
    }

    private Rules() {
    }

    /** 库/参数是否适用于当前环境。 */
    public static boolean allows(JsonNode element, Features features) {
        return allowsRules(element.path("rules"), features);
    }

    public static boolean allowsRules(JsonNode rules, Features features) {
        if (rules == null || !rules.isArray() || rules.isEmpty()) {
            return true;
        }
        boolean allow = false;
        for (JsonNode rule : rules) {
            if (matches(rule, features)) {
                allow = "allow".equals(rule.path("action").asText("allow"));
            }
        }
        return allow;
    }

    private static boolean matches(JsonNode rule, Features features) {
        JsonNode os = rule.path("os");
        if (os.isObject()) {
            if (os.has("name")) {
                String expected = os.path("name").asText("").toLowerCase(Locale.ROOT);
                if (!expected.equals(OS_NAME) && !(expected.equals("macos") && "osx".equals(OS_NAME))) {
                    return false;
                }
            }
            if (os.has("arch")) {
                String expected = os.path("arch").asText("").toLowerCase(Locale.ROOT);
                if (!archMatches(expected)) {
                    return false;
                }
            }
            if (os.has("version")) {
                try {
                    if (!OS_VERSION.matches(os.path("version").asText(""))) {
                        return false;
                    }
                } catch (Exception e) {
                    return false;
                }
            }
        }

        JsonNode featureNode = rule.path("features");
        if (featureNode.isObject()) {
            for (var it = featureNode.fields(); it.hasNext(); ) {
                var entry = it.next();
                boolean expected = entry.getValue().asBoolean(false);
                boolean actual = switch (entry.getKey()) {
                    case "is_demo_user" -> features.demoUser();
                    case "has_custom_resolution" -> features.hasCustomResolution();
                    default -> false;
                };
                if (expected != actual) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean archMatches(String expected) {
        if ("x86".equals(expected)) {
            return "x86".equals(OS_ARCH);
        }
        // 官方 JSON 里 64 位通常写 x86_64（Windows/Linux）或 arm64
        if ("x86_64".equals(expected) || "amd64".equals(expected) || "x64".equals(expected)) {
            return "x86_64".equals(OS_ARCH);
        }
        if ("arm64".equals(expected) || "aarch64".equals(expected)) {
            return "arm64".equals(OS_ARCH);
        }
        return expected.equals(OS_ARCH);
    }

    /** 32/64 位字符串，供老版本 natives.classifiers 里的 ${arch} 替换。 */
    public static String archBits() {
        return "x86".equals(OS_ARCH) ? "32" : "64";
    }

    /** 把所有 action=allow 的规则展开成可读文本（调试用）。 */
    public static String describe(JsonNode rules) {
        if (rules == null || !rules.isArray()) {
            return "no-rules";
        }
        List<String> parts = new java.util.ArrayList<>();
        for (JsonNode rule : rules) {
            parts.add(rule.path("action").asText("allow") + " " + rule.path("os").path("name").asText("any"));
        }
        return String.join(", ", parts);
    }
}
