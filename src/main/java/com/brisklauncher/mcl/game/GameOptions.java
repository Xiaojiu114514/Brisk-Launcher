package com.brisklauncher.mcl.game;

import com.brisklauncher.mcl.security.SafeLog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 实例的 {@code options.txt}（游戏自己的设定档）读写。
 *
 * <p>目前只做一件事：**第一次安装的实例默认把游戏语言设成简体中文**。
 * 玩家的预期是「装完直接就是中文」，而原版游戏第一次启动时会跟著作业系统语言走，
 * 英文系统上就是英文介面（PCL/HMCL 也都会在首次启动时改语言）。
 *
 * <p>规则与 PCL 一致：
 * <ul>
 *   <li>没有 {@code options.txt} → 新建，写入 {@code lang:zh_cn}；</li>
 *   <li>已有档案且已经有 {@code lang:} 这一项 → **完全不动**（玩家自己选过语言就尊重他）；</li>
 *   <li>已有档案但缺 {@code lang:} → 追加一行。</li>
 * </ul>
 */
public final class GameOptions {

    /** 簡體中文的語言代號（遊戲內寫法是小寫 zh_cn）。 */
    public static final String SIMPLIFIED_CHINESE = "zh_cn";

    private GameOptions() {
    }

    public static Path fileOf(GameInstance entry) {
        return entry.gameDir().resolve("options.txt");
    }

    /**
     * 首次安装后把语言设成简体中文（已有语言设定时不动）。
     *
     * @return true = 实际写入了档案
     */
    public static boolean ensureSimplifiedChinese(GameInstance entry) {
        if (entry == null) {
            return false;
        }
        Path file = fileOf(entry);
        try {
            Files.createDirectories(file.getParent());
            if (!Files.isRegularFile(file)) {
                Files.writeString(file, "lang:" + SIMPLIFIED_CHINESE + System.lineSeparator(),
                        StandardCharsets.UTF_8);
                SafeLog.info("新实例已预设游戏语言：简体中文");
                return true;
            }
            List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
            for (String line : lines) {
                if (line.startsWith("lang:")) {
                    // 已经有语言设定：可能是玩家自己选的，也可能是整合包指定，一律不覆盖
                    return false;
                }
            }
            lines.add("lang:" + SIMPLIFIED_CHINESE);
            Files.write(file, lines, StandardCharsets.UTF_8);
            SafeLog.info("实例缺少语言设定，已补上简体中文");
            return true;
        } catch (Exception error) {
            // 写不进去不影响启动（游戏会用默认语言）
            SafeLog.warn("写入 options.txt 语言失败：" + error.getClass().getSimpleName());
            return false;
        }
    }

    /** 讀出目前的遊戲語言（沒有設定時回傳空字串）。 */
    public static String language(GameInstance entry) {
        Path file = fileOf(entry);
        if (!Files.isRegularFile(file)) {
            return "";
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.startsWith("lang:")) {
                    return line.substring("lang:".length()).trim();
                }
            }
        } catch (Exception ignored) {
            // 读不到就当作没设定
        }
        return "";
    }

    /** 明確把語言改成簡體中文（設定頁/工具頁的「一鍵改中文」按鈕用）。 */
    public static boolean forceSimplifiedChinese(GameInstance entry) throws Exception {
        Path file = fileOf(entry);
        Files.createDirectories(file.getParent());
        List<String> lines = Files.isRegularFile(file)
                ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8))
                : new ArrayList<>();
        boolean replaced = false;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("lang:")) {
                lines.set(i, "lang:" + SIMPLIFIED_CHINESE);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            lines.add("lang:" + SIMPLIFIED_CHINESE);
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
        return true;
    }
}
