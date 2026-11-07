package com.brisklauncher.mcl.net;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.brisklauncher.mcl.security.SafeLog;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * 全局 HTTP 入口：统一 UA、超时、代理与镜像回退。
 *
 * <p>设计要点（参考 HMCL/PCL 的网络层）：
 * <ul>
 *   <li>强制 HTTP/1.1：国内部分中间设备对 h2 支持残缺，会导致握手后卡住。</li>
 *   <li>代理只认设置里显式填写的地址，或“自动探测”开启时探测常见本地端口，
 *       绝不偷偷改系统代理。</li>
 *   <li>TLS 校验默认开启；{@code insecureTls} 是用户显式打开的最后手段。</li>
 *   <li>镜像回退在 {@link #getJsonAny}/{@link #getBytesAny} 里完成，调用方只给候选列表。</li>
 * </ul>
 */
public final class Http {

    private static final String USER_AGENT =
            "BriskLauncher/1.1.0 (Minecraft launcher; +https://github.com/brisklauncher/brisk-launcher)";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static volatile HttpClient client;
    private static volatile HttpClient tls12;
    private static volatile HttpClient auth;
    private static volatile String clientKey = "";

    private Http() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** 代理或 TLS 设置变化后调用，下一次请求自动重建客户端。 */
    public static synchronized void configure(String proxySpec, boolean insecureTls) {
        String key = (proxySpec == null ? "" : proxySpec.trim()) + "|" + insecureTls;
        if (!key.equals(clientKey)) {
            client = null;
            tls12 = null;
            auth = null;
            clientKey = key;
        }
    }

    /** 這些主機的請求**永遠**校驗證書，insecureTls 對它們無效（安全審計 M3）。 */
    private static final java.util.Set<String> STRICT_HOSTS = java.util.Set.of(
            "login.microsoftonline.com", "login.live.com", "api.minecraftservices.com",
            "sessionserver.mojang.com", "user.auth.xboxlive.com", "xsts.auth.xboxlive.com",
            "api.mojang.com", "piston-meta.mojang.com");

    static boolean isStrictHost(URI uri) {
        String host = uri == null || uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
        return STRICT_HOSTS.contains(host);
    }

    /**
     * 认证用：**永远**严格校验证书 + **不跟随重定向**（免得 307/308 把令牌转发到别的主机）。
     *
     * <p>注册/登录的 POST 与带 {@code Authorization} 的 GET 都必须走这个客户端，
     * 否则使用者开了「跳过 TLS 验证」时，账号令牌就可能明文上路。
     * 代理设定仍然生效（公司网络里要能登录）。
     */
    public static HttpClient authClient() {
        HttpClient local = auth;
        if (local == null) {
            synchronized (Http.class) {
                local = auth;
                if (local == null) {
                    HttpClient.Builder builder = HttpClient.newBuilder()
                            .version(HttpClient.Version.HTTP_1_1)
                            .connectTimeout(Duration.ofSeconds(15))
                            .followRedirects(HttpClient.Redirect.NEVER);
                    ProxySelector proxy = proxySelector();
                    if (proxy != null) {
                        builder.proxy(proxy);
                    }
                    local = builder.build();
                    auth = local;
                }
            }
        }
        return local;
    }

    public static HttpClient client() {
        HttpClient local = client;
        if (local == null) {
            synchronized (Http.class) {
                local = client;
                if (local == null) {
                    local = build();
                    client = local;
                }
            }
        }
        return local;
    }

    private static HttpClient build() {
        HttpClient.Builder builder = baseBuilder();
        if (insecureTlsEnabled()) {
            try {
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, new TrustManager[]{new X509TrustManager() {
                    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                    @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                }}, new SecureRandom());
                builder.sslContext(context);
            } catch (Exception e) {
                SafeLog.warn("初始化宽松 TLS 失败，回退系统默认校验");
            }
        }
        return builder.build();
    }

    /**
     * 只启用 TLS 1.2 的客户端。
     * 部分国内网络/中间设备会阻断 TLS 1.3 的 SNI，握手失败时用它重试一次。
     */
    public static HttpClient tls12Client() {
        HttpClient local = tls12;
        if (local == null) {
            synchronized (Http.class) {
                local = tls12;
                if (local == null) {
                    HttpClient.Builder builder = baseBuilder();
                    try {
                        javax.net.ssl.SSLParameters parameters = new javax.net.ssl.SSLParameters();
                        parameters.setProtocols(new String[]{"TLSv1.2"});
                        builder.sslParameters(parameters);
                    } catch (Exception e) {
                        SafeLog.warn("无法设置 TLS 1.2 参数");
                    }
                    local = builder.build();
                    tls12 = local;
                }
            }
        }
        return local;
    }

    private static HttpClient.Builder baseBuilder() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.ALWAYS);
        ProxySelector proxy = proxySelector();
        if (proxy != null) {
            builder.proxy(proxy);
        }
        return builder;
    }

    /** 异常链里是否包含 TLS 握手错误。 */
    public static boolean isSslProblem(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 8) {
            if (current instanceof javax.net.ssl.SSLException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean insecureTlsEnabled() {
        return Boolean.getBoolean("mcl.tls.insecure");
    }

    /** 解析 "host:port"、"http://host:port"、"socks5://host:port" 三种写法。 */
    private static ProxySelector proxySelector() {
        String spec = System.getProperty("mcl.proxy", "").trim();
        if (spec.isEmpty()) {
            return null;
        }
        try {
            Proxy.Type type = Proxy.Type.HTTP;
            String value = spec;
            int scheme = value.indexOf("://");
            if (scheme > 0) {
                String protocol = value.substring(0, scheme).toLowerCase(Locale.ROOT);
                value = value.substring(scheme + 3);
                if (protocol.startsWith("socks")) {
                    type = Proxy.Type.SOCKS;
                }
            }
            int colon = value.lastIndexOf(':');
            if (colon <= 0) {
                SafeLog.warn("代理地址格式不正确，已忽略");
                return null;
            }
            String host = value.substring(0, colon);
            int port = Integer.parseInt(value.substring(colon + 1));
            Proxy proxy = new Proxy(type, InetSocketAddress.createUnresolved(host, port));
            return new ProxySelector() {
                @Override public List<Proxy> select(URI uri) { return List.of(proxy); }
                @Override public void connectFailed(URI uri, SocketAddress sa, IOException ioe) { }
            };
        } catch (Exception e) {
            SafeLog.warn("代理地址解析失败，已忽略");
            return null;
        }
    }

    /**
     * 探测常见本地代理端口（Clash / V2Ray 等）。
     * 仅当设置里显式开启 mcl.proxy.auto=true 时才会走到这里。
     */
    public static Optional<String> detectLocalProxy() {
        int[] ports = {7890, 7897, 10809, 10808, 1080, 2080};
        for (int port : ports) {
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 120);
                return Optional.of("127.0.0.1:" + port);
            } catch (Exception ignored) {
                // 尝试下一个端口
            }
        }
        return Optional.empty();
    }

    // ---------- 单地址请求 ----------

    /**
     * 带硬超时的请求。
     *
     * <p>{@link HttpClient} 自己的 timeout 覆盖不到 DNS 解析（Windows 上解析被劫持时
     * 可能卡住几分钟），所以元数据请求统一再套一层“外部截止时间”：
     * 超时立即抛 {@link HttpException}，让 UI 回到可操作状态，而不是无限转圈。
     */
    public static byte[] getBytes(URI uri, Duration timeout) {
        return withDeadline(() -> getBytesBlocking(uri, timeout), timeout.plusSeconds(10), "请求");
    }

    public interface CallableAction<T> {
        T run() throws Exception;
    }

    public static <T> T withDeadline(CallableAction<T> action, Duration deadline, String what) {
        // 注意不能用 try-with-resources：ExecutorService.close() 会等待任务结束，
        // 而 DNS 卡住的任务可能永远不结束，反而让“超时”失效。
        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        try {
            var future = executor.submit(action::run);
            try {
                return future.get(deadline.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                future.cancel(true);
                throw new HttpException(what + "超时（" + deadline.toSeconds() + "s）");
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof HttpException httpException) {
                    throw httpException;
                }
                throw new HttpException(describe(cause), cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                throw new HttpException(what + "被中断", e);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static byte[] getBytesBlocking(URI uri, Duration timeout) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "*/*")
                    .timeout(timeout)
                    .GET()
                    .build();
            // 安全审计 L-06：以前这里还写了个 request.method().equals("POST")，
            // 但这两个方法都只发 GET，那个条件恒为 false（看起来有保护其实没有），已删除。
            HttpResponse<byte[]> response = isStrictHost(uri)
                    ? authClient().send(request, HttpResponse.BodyHandlers.ofByteArray())
                    : sendWithTlsFallback(request);
            if (response.statusCode() != 200) {
                throw new HttpException("HTTP " + response.statusCode() + "（" + hostOf(uri) + "）");
            }
            return response.body();
        } catch (HttpException e) {
            throw e;
        } catch (IOException e) {
            throw new HttpException(describe(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HttpException("请求被中断", e);
        }
    }

    /** 首次用默认 TLS；握手失败时自动改用 TLS 1.2 重试一次（国内网络常见）。 */
    private static HttpResponse<byte[]> sendWithTlsFallback(HttpRequest request)
            throws IOException, InterruptedException {
        try {
            return client().send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            if (!isSslProblem(e)) {
                throw e;
            }
            SafeLog.warn("TLS 握手失败，改用 TLS 1.2 重试（" + hostOf(request.uri()) + "）");
            return tls12Client().send(request, HttpResponse.BodyHandlers.ofByteArray());
        }
    }

    public static String getString(URI uri, Duration timeout) {
        return new String(getBytes(uri, timeout), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** POST 一段 JSON，回傳解析後的 JSON（Modrinth 的批量查詢用）。 */
    public static JsonNode postJson(URI uri, String jsonBody, Duration timeout) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody,
                            java.nio.charset.StandardCharsets.UTF_8))
                    .build();
            // 安全审计 L-06：以前这里还写了个 request.method().equals("POST")，
            // 但这两个方法都只发 GET，那个条件恒为 false（看起来有保护其实没有），已删除。
            HttpResponse<byte[]> response = isStrictHost(uri)
                    ? authClient().send(request, HttpResponse.BodyHandlers.ofByteArray())
                    : sendWithTlsFallback(request);
            if (response.statusCode() != 200) {
                throw new HttpException("HTTP " + response.statusCode() + "（" + hostOf(uri) + "）");
            }
            return MAPPER.readTree(response.body());
        } catch (HttpException e) {
            throw e;
        } catch (IOException e) {
            throw new HttpException(describe(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HttpException("请求被中断", e);
        }
    }

    public static JsonNode getJson(URI uri, Duration timeout) {
        byte[] body = getBytes(uri, timeout);
        try {
            return MAPPER.readTree(body);
        } catch (IOException e) {
            throw new HttpException("响应不是合法 JSON（" + hostOf(uri) + "）", e);
        }
    }

    // ---------- 多地址回退 ----------

    /**
     * 同一个 host 的失败日志最多每 60 秒印一次。
     *
     * <p>为什么：下载 3888 个资源档时，某个镜像回 429/567 会连续失败几百次，
     * 每次都印一行 WARNING 会把日志刷满，玩家看起来就像「一直在报错」。
     * 真正的错误仍然会保留（每分钟一次 + 最后一次由呼叫方处理）。
     */
    private static final java.util.Map<String, Long> FAIL_LOG_AT =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long FAIL_LOG_INTERVAL_MS = 60_000L;

    private static void logFailure(URI uri, HttpException error) {
        String host = hostOf(uri);
        long now = System.currentTimeMillis();
        Long last = FAIL_LOG_AT.get(host);
        if (last != null && now - last < FAIL_LOG_INTERVAL_MS) {
            return;
        }
        FAIL_LOG_AT.put(host, now);
        SafeLog.warn("请求失败 [" + host + "]：" + error.getMessage()
                + "（同一来源 60 秒内不再重复提示）");
    }

    public static byte[] getBytesAny(List<URI> candidates, Duration timeout) {
        HttpException last = null;
        for (URI uri : candidates) {
            try {
                return getBytes(uri, timeout);
            } catch (HttpException e) {
                last = e;
                logFailure(uri, e);
            }
        }
        throw last == null ? new HttpException("没有可用地址") : last;
    }

    public static JsonNode getJsonAny(List<URI> candidates, Duration timeout) {
        byte[] body = getBytesAny(candidates, timeout);
        try {
            return MAPPER.readTree(body);
        } catch (IOException e) {
            throw new HttpException("响应不是合法 JSON", e);
        }
    }

    public static String getStringAny(List<URI> candidates, Duration timeout) {
        return new String(getBytesAny(candidates, timeout), java.nio.charset.StandardCharsets.UTF_8);
    }

    // ---------- 工具 ----------

    public static String hostOf(URI uri) {
        if (uri == null) {
            return "unknown";
        }
        String host = uri.getHost();
        return host == null || host.isBlank() ? uri.toString() : host;
    }

    public static String describe(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (root instanceof java.net.http.HttpConnectTimeoutException) {
            return "连接超时";
        }
        if (root instanceof java.net.ConnectException) {
            return "无法建立连接（网络被拦截或服务不可用）";
        }
        if (root instanceof java.net.UnknownHostException) {
            return "DNS 解析失败";
        }
        if (root instanceof javax.net.ssl.SSLException) {
            return "TLS 握手失败";
        }
        if (root instanceof java.net.http.HttpTimeoutException) {
            return "请求超时";
        }
        String message = root.getMessage();
        if (message == null || message.isBlank()) {
            return root.getClass().getSimpleName();
        }
        return message.length() > 120 ? message.substring(0, 120) + "…" : message;
    }
}
