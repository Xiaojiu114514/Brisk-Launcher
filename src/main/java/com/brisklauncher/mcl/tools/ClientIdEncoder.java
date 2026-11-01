package com.brisklauncher.mcl.tools;

import com.brisklauncher.mcl.util.Obfuscated;

/**
 * 开发期小工具：把微软客户端 ID 混淆成 {@code enc:...}，再贴进源码。
 *
 * <p>用法（在项目根目录）：
 * <pre>
 * java -cp target/classes com.brisklauncher.mcl.tools.ClientIdEncoder 00000000-0000-0000-0000-000000000000
 * </pre>
 * 输出就是可以写进 {@code MicrosoftClientIdLocal.CLIENT_ID} 的值；
 * 运行时 {@code MsClientId} 会自动解码。
 */
public final class ClientIdEncoder {

    private ClientIdEncoder() {
    }

    public static void main(String[] args) {
        if (args.length == 0 || args[0].isBlank()) {
            System.out.println("用法: ClientIdEncoder <你的客户端ID>");
            return;
        }
        System.out.println(Obfuscated.encode(args[0].trim()));
    }
}
