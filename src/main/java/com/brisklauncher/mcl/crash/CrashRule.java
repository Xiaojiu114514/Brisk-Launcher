package com.brisklauncher.mcl.crash;

/**
 * 一条崩溃规则。
 *
 * @param id          规则 ID（远程更新时用于去重）
 * @param pattern     正则，匹配日志/崩溃报告
 * @param category    memory / mod / graphics / java / files / network …
 * @param explanation 面向玩家的原因说明
 * @param suggestion  建议操作
 */
public record CrashRule(String id, String pattern, String category,
                        String explanation, String suggestion) {

    public CrashRule {
        id = id == null ? "unknown" : id;
        pattern = pattern == null ? "" : pattern;
        category = category == null ? "unknown" : category;
        explanation = explanation == null ? "" : explanation;
        suggestion = suggestion == null ? "" : suggestion;
    }
}
