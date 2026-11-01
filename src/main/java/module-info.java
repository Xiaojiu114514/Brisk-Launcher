module com.brisklauncher.mcl {
    requires org.glavo.webp;

    requires java.logging;
    requires jdk.httpserver;
    requires java.management;
    requires java.net.http;
    requires java.desktop;
    requires javafx.controls;
    requires com.fasterxml.jackson.databind;
    requires com.sun.jna;
    requires com.sun.jna.platform;


    exports com.brisklauncher.mcl;
    exports com.brisklauncher.mcl.auth.api;
    exports com.brisklauncher.mcl.i18n;
    // 插件 API：外部插件 jar 只认这个包（工俱页 / data/plugins）
    exports com.brisklauncher.mcl.plugin;

    opens com.brisklauncher.mcl to javafx.graphics;
    opens com.brisklauncher.mcl.auth.api    to com.fasterxml.jackson.databind;
    opens com.brisklauncher.mcl.modpack     to com.fasterxml.jackson.databind;
    // JNA 的 Structure 需要反射读取 CREDENTIALW 的字段
    opens com.brisklauncher.mcl.security    to com.fasterxml.jackson.databind, com.sun.jna, com.sun.jna.platform;
    // RecycleBin 里的 SHFILEOPSTRUCT 同样需要 JNA 反射读写字段
    opens com.brisklauncher.mcl.util        to com.sun.jna;

    // ResourceBundle 加载需要 open 语言包所在包
    opens com.brisklauncher.mcl.i18n;

    uses com.brisklauncher.mcl.auth.api.AuthProvider;
}