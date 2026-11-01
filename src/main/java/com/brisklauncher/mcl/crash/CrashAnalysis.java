package com.brisklauncher.mcl.crash;

import java.nio.file.Path;
import java.util.List;

/**
 * 崩溃分析结果：命中的规则 + 缺少的前置 Mod + 可疑 Mod + 日志尾部。
 *
 * <p>{@code matches} 已经按「可信度」排好序（第 0 个就是最可能的原因）；
 * {@code missingMods} 是从日志里直接抓到「缺哪个前置 Mod」的 id（可能为空）。
 */
public record CrashAnalysis(List<Match> matches, List<String> missingMods,
                            List<String> suspectMods, String logTail, Path crashReport) {

    public record Match(CrashRule rule, String evidence) {
    }

    public static CrashAnalysis empty() {
        return new CrashAnalysis(List.of(), List.of(), List.of(), "", null);
    }

    public boolean hasMatches() {
        return !matches.isEmpty();
    }

    public String title() {
        if (matches.isEmpty()) {
            return "未命中已知规则";
        }
        return matches.get(0).rule().explanation();
    }

    /** 給彈窗用的多行文字。 */
    public String toDisplayText() {
        StringBuilder sb = new StringBuilder();
        if (matches.isEmpty()) {
            sb.append("没有命中内建规则。可以查看日志尾部，或导出日志到社群求助。\n");
        } else {
            sb.append("可能的原因：\n");
            for (Match match : matches) {
                sb.append("· [").append(match.rule().category()).append("] ")
                        .append(match.rule().explanation()).append('\n');
                sb.append("  建议：").append(match.rule().suggestion()).append('\n');
            }
        }
        if (!missingMods.isEmpty()) {
            sb.append("\n缺少的前置 Mod：").append(String.join("、", missingMods)).append('\n');
        }
        if (!suspectMods.isEmpty()) {
            sb.append("\n疑似相关的 Mod：").append(String.join("、", suspectMods)).append('\n');
        }
        if (logTail != null && !logTail.isBlank()) {
            sb.append("\n日志尾部：\n").append(logTail);
        }
        return sb.toString();
    }
}
