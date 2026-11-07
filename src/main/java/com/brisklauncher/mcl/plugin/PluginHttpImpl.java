package com.brisklauncher.mcl.plugin;

import com.brisklauncher.mcl.net.Http;
import com.brisklauncher.mcl.plugin.api.PluginHttp;
import com.brisklauncher.mcl.update.UpdateService;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link PluginHttp} 的實現：复用啟動器自己的 {@link java.net.http.HttpClient}
 * （共用連線池 / 代理 / TLS 設定 / 重新導向）。
 */
final class PluginHttpImpl implements PluginHttp {

    private static final String USER_AGENT = "BriskLauncher/" + UpdateService.CURRENT_VERSION + " (plugin)";

    @Override
    public Response request(Request request) throws Exception {
        URI uri = URI.create(request.url());
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(request.timeout() == null ? Duration.ofSeconds(20) : request.timeout());
        Map<String, String> headers = request.headers() == null ? Map.of() : request.headers();
        boolean hasAgent = headers.keySet().stream().anyMatch(name -> "user-agent".equalsIgnoreCase(name));
        if (!hasAgent) {
            builder.header("User-Agent", USER_AGENT);
        }
        for (Map.Entry<String, String> header : headers.entrySet()) {
            try {
                builder.header(header.getKey(), header.getValue());
            } catch (IllegalArgumentException rejected) {
                // 受限制的標頭（Host / Content-Length …）由 JDK 自己填，跳過即可
            }
        }
        HttpRequest.BodyPublisher publisher = request.body() == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(request.body());
        builder.method(request.method() == null ? "GET" : request.method(), publisher);

        HttpResponse<byte[]> response = Http.client().send(builder.build(),
                HttpResponse.BodyHandlers.ofByteArray());
        Map<String, List<String>> received = new LinkedHashMap<>();
        response.headers().map().forEach((name, values) -> received.put(name, List.copyOf(values)));
        return new Response(response.statusCode(), received, response.body());
    }
}
