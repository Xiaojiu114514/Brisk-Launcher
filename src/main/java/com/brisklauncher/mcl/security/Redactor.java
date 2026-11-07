package com.brisklauncher.mcl.security;

import java.util.regex.Pattern;

/**
 * 对「可能被使用者截图、贴到 issue、写进日志」的文字做统一打码。
 *
 * <p>为什么需要它：微软客户端 ID（appid）本身不是机密，但它出现在错误文本里会泄露
 * 「这个启动器用的是哪个 Azure 应用」；AADSTS 的 {@code error_description} 还经常把
 * application ID 原样回显。与其逐个错误码写黑名单（迟早漏），不如对文本统一过一遍。
 *
 * <p>只做两件事：把 GUID 形态的串与 {@code client_id=}/{@code device_code=}/{@code code=}
 * 这类查询参数的值替换掉。不做「半遮盖」以外的任何加工，保证原文结构还能用于排查。
 */
public final class Redactor {

    private static final String MASKED_GUID = "********-****-****-****-************";

    private static final Pattern GUID = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");

    /** 查询 / 表单里的敏感参数：值整体替换成 ***。 */
    private static final Pattern SECRET_PARAM = Pattern.compile(
            "(?i)\\b(client_id|device_code|code|code_verifier)=([^&\\s\"']+)");

    private Redactor() {
    }

    /** 打码；null / 空串原样返回。 */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = SECRET_PARAM.matcher(text).replaceAll(m -> m.group(1) + "=***");
        return GUID.matcher(masked).replaceAll(MASKED_GUID);
    }
}
