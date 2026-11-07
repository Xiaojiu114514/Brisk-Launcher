package com.brisklauncher.mcl.plugin.api;

import java.time.Duration;
import java.util.Map;

/**
 * 插件用的 HTTP 用户端。
 *
 * <p>默认会带启动器统一的 User-Agent、遵循代理设定；所有方法都是同步的，
 * <b>不要在 JavaFX 执行绪上调用</b>（会卡界面）。
 *
 * <p>响应只给字节 / 文字：需要状态码就用 {@link #request(Request)}。
 */
public interface PluginHttp {

    /** 一次 HTTP 请求。 */
    record Request(String method, String url, Map<String, String> headers, byte[] body,
                   Duration timeout) {

        public static Request get(String url) {
            return new Request("GET", url, Map.of(), null, Duration.ofSeconds(20));
        }

        public static Request post(String url, byte[] body, String contentType) {
            return new Request("POST", url, Map.of("Content-Type", contentType), body,
                    Duration.ofSeconds(30));
        }

        public Request withHeader(String name, String value) {
            Map<String, String> copy = new java.util.LinkedHashMap<>(headers);
            if (value == null) {
                copy.remove(name);
            } else {
                copy.put(name, value);
            }
            return new Request(method, url, copy, body, timeout);
        }

        public Request withTimeout(Duration value) {
            return new Request(method, url, headers, body, value);
        }
    }

    /** 一次 HTTP 响应（body 已读成字节，插件自己决定要不要转字串）。 */
    record Response(int status, Map<String, java.util.List<String>> headers, byte[] body) {

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public String text() {
            return body == null ? "" : new String(body, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** 发一个请求并读完整响应。 */
    Response request(Request request) throws Exception;

    /** GET 文字（UTF-8，状态码非 2xx 会抛例外）。 */
    default String getText(String url) throws Exception {
        Response response = request(Request.get(url));
        if (!response.ok()) {
            throw new java.io.IOException("HTTP " + response.status() + ": " + url);
        }
        return response.text();
    }

    /** GET 字节（状态码非 2xx 会抛例外）。 */
    default byte[] getBytes(String url) throws Exception {
        Response response = request(Request.get(url));
        if (!response.ok()) {
            throw new java.io.IOException("HTTP " + response.status() + ": " + url);
        }
        return response.body() == null ? new byte[0] : response.body();
    }
}
