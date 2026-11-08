package com.brisklauncher.mcl.auth.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.brisklauncher.mcl.auth.api.AccountType;
import com.brisklauncher.mcl.auth.api.AuthException;
import com.brisklauncher.mcl.auth.api.AuthProvider;
import com.brisklauncher.mcl.auth.api.GameSession;
import com.brisklauncher.mcl.auth.api.LoginCredentials;
import com.brisklauncher.mcl.auth.api.LoginResult;
import com.brisklauncher.mcl.auth.api.StoredSession;
import com.brisklauncher.mcl.i18n.I18n;
import com.brisklauncher.mcl.net.Http;
import com.brisklauncher.mcl.security.Redactor;
import com.brisklauncher.mcl.security.SafeLog;
import com.brisklauncher.mcl.ui.DesktopActions;
import com.brisklauncher.mcl.util.MsClientId;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 微软（Minecraft Java 版）正版登录。
 *
 * <h2>只有一条登录路径：系统浏览器 + loopback 回呼</h2>
 * 设备码（「复制代码去网页输入」）已整条移除。原因：设备码流的本质是
 * 「谁在网页上输入代码，令牌就发给索取代码的那台机器」——这正是设备码钓鱼的手法和
 * 攻击面；appid 一旦泄漏，它就成了这条链的信誉背书。移除之后，授权码只会送到
 * <b>使用者自己机器的 loopback 端口</b>，攻击者无法隔空收取（RFC 8252）。
 *
 * <h2>回呼通道的 12 条校驗</h2>
 * 只绑 loopback、随机端口、PKCE(S256)、state 常数时间比对、一次性 latch、
 * 远端位址必须是 loopback、Host 标头白名单（防 DNS rebinding）、只接受 GET /、
 * 参数必须恰好一次、安全回应标头、单执行绪执行器、失败不把 code/state 写进日志与界面。
 *
 * <h2>关于 appid 的定位</h2>
 * 客户端 ID 是 public client 标识，<b>不是机密</b>。本类不尝试隐藏它，
 * 只保证它不出现在错误文本、日志与界面里（见 {@link Redactor}）。
 */
public final class MicrosoftAuthProvider implements AuthProvider {

    /**
     * 申请 offline_access：Microsoft 才会下发 refresh_token。
     *
     * <p>以前刻意不要它，结果 access token 一过期就只能让玩家重新登录。
     * 现在 refresh_token 加密存在 Windows 凭据管理器里，过期后自动续期。
     */
    private static final String SCOPE = "XboxLive.signin offline_access";
    private static final String AUTHORIZE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize";
    private static final String TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    private static final String XBL_URL = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_LOGIN_URL = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MC_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";
    private static final String USER_AGENT = "BriskLauncher/1.1.2";
    private static final Duration TIMEOUT = Duration.ofSeconds(30L);

    /** 等使用者完成网页登录的上限（秒）。 */
    private static final int CALLBACK_TIMEOUT_SECONDS = 240;

    private static final String CSP = "default-src 'none'; style-src 'unsafe-inline'; img-src data:";

    private final ObjectMapper mapper;
    private final String clientId;

    public MicrosoftAuthProvider() {
        this("");
    }

    public MicrosoftAuthProvider(String clientId) {
        this.mapper = new ObjectMapper();
        String trimmed = clientId == null ? "" : clientId.trim();
        this.clientId = trimmed;
    }

    public AccountType type() {
        return AccountType.MICROSOFT;
    }

    /** 正版登录只走浏览器回呼；这里保留位只是为了满足 {@link AuthProvider} 接口。 */
    public LoginResult login(LoginCredentials credentials) {
        return LoginResult.fail(I18n.t("account.ms.browser_only"));
    }

    // ================================================================
    //  登录主流程
    // ================================================================

    public void loginWithBrowser(Consumer<String> onStateChange, Consumer<LoginResult> onComplete) {
        loginWithBrowser(onStateChange, url -> {
        }, onComplete);
    }

    /**
     * 同 {@link #loginWithBrowser(Consumer, Consumer)}，但额外把授权 URL 交给调用方。
     *
     * <p>为什么需要：自动打开浏览器可能失败（默认浏览器被卸载 / 被组策略挡住 / 开在别的配置里），
     * 或者使用者把登录页关掉了。这时界面上要有一个「手动打开登录页」的按钮 —— 它需要这个 URL。
     *
     * <p>URL 只在内存里传给界面，<b>不显示、不写日志</b>（含 client_id 与一次性 state，
     * 显示出来等于把一个公开识别码 + 本次流程的 state 摆进截图；真要给使用者只能进剪贴板）。
     */
    public void loginWithBrowser(Consumer<String> onStateChange,
                                 Consumer<String> onAuthorizeUrl,
                                 Consumer<LoginResult> onComplete) {
        Thread.ofVirtual().name("mcl-ms-browser").start(() -> {
            try {
                onStateChange.accept(I18n.t("account.ms.state.opening"));
                String cid = effectiveClientId();
                String problem = describeClientIdProblem(cid);
                if (problem != null) {
                    onComplete.accept(LoginResult.fail(problem));
                    return;
                }

                String msAccessToken;
                try (LoopbackCallback callback = LoopbackCallback.start()) {
                    String authorizeUrl = AUTHORIZE_URL
                            + "?client_id=" + encode(cid)
                            + "&response_type=code"
                            + "&redirect_uri=" + encode(callback.redirectUri())
                            + "&scope=" + encode(SCOPE)
                            + "&prompt=select_account"
                            + "&code_challenge=" + encode(callback.challenge())
                            + "&code_challenge_method=S256"
                            + "&state=" + encode(callback.state());

                    onStateChange.accept(I18n.t("account.ms.state.waiting"));
                    onAuthorizeUrl.accept(authorizeUrl);
                    // 打不开浏览器不再直接失败：界面上的「手动打开登录页」还能救回来，
                    // 回呼服务器继续等（240 秒内完成授权都算成功）。
                    // mcl.dev.msnobrowser 只给截图/回归用：跳过真的开浏览器（用 != null，与其它 dev 开关一致）。
                    if (System.getProperty("mcl.dev.msnobrowser") != null
                            || !DesktopActions.openUrl(authorizeUrl)) {
                        onStateChange.accept(I18n.t("account.ms.open_failed"));
                    }

                    String code;
                    try {
                        code = callback.code().get(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    } catch (TimeoutException timeout) {
                        throw new AuthException(I18n.t("account.ms.callback_timeout"));
                    }

                    onStateChange.accept(I18n.t("account.ms.state.exchanging"));
                    String tokenBody = "grant_type=authorization_code"
                            + "&code=" + encode(code)
                            + "&redirect_uri=" + encode(callback.redirectUri())
                            + "&client_id=" + encode(cid)
                            + "&scope=" + encode(SCOPE)
                            + "&code_verifier=" + encode(callback.verifier());
                    JsonNode tokenNode = postForm(TOKEN_URL, tokenBody);
                    msAccessToken = tokenNode.path("access_token").asText("");
                    msRefreshToken = tokenNode.path("refresh_token").asText("");
                    if (msAccessToken.isEmpty()) {
                        throw new AuthException(I18n.t("account.ms.token_failed",
                                describeOAuthError(tokenNode)));
                    }
                }

                finishXboxLogin(msAccessToken, msRefreshToken, onStateChange, onComplete);
            } catch (AuthException e) {
                onComplete.accept(LoginResult.fail(e.getMessage()));
            } catch (Exception e) {
                onComplete.accept(LoginResult.fail(safeError(e)));
            }
        });
    }

    /** Microsoft refresh token（scope 含 offline_access 才有）。 */
    private String msRefreshToken;

    private String effectiveClientId() {
        return MsClientId.resolve(this.clientId);
    }

    private static String describeClientIdProblem(String id) {
        if (id == null || id.isBlank()) {
            return I18n.t("account.ms.client_id_missing");
        }
        boolean guid = id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
        boolean xboxLegacy = id.matches("000000004[0-9A-Fa-f]{7}");
        return guid || xboxLegacy ? null : I18n.t("account.ms.client_id_bad_format");
    }

    // ================================================================
    //  loopback 回呼（专属于本进程的一次性通道）
    // ================================================================

    /**
     * 一次性回调通道。
     *
     * <p>同时绑 {@code 127.0.0.1} 与 {@code ::1}（能绑就绑）：URL 里写的是
     * {@code http://localhost:port}，浏览器解析成 IPv4 / IPv6 都能连上。
     * 之前「绑 127.0.0.1、URL 写 localhost」在 IPv6 优先的机器上会直接连不上。
     */
    private static final class LoopbackCallback implements AutoCloseable {

        private static final SecureRandom RANDOM = new SecureRandom();
        private static final int PORT_MIN = 49152;
        private static final int PORT_RANGE = 16384;

        private final String verifier = Pkce.verifier();
        private final String challenge = Pkce.challenge(verifier);
        private final String state = Pkce.state();
        private final CompletableFuture<String> code = new CompletableFuture<>();
        private final AtomicBoolean consumed = new AtomicBoolean(false);
        private final List<HttpServer> servers = new ArrayList<>();
        private final List<ExecutorService> executors = new ArrayList<>();
        private final String redirectUri;
        private final Set<String> allowedHosts;

        private LoopbackCallback(int port) {
            this.redirectUri = "http://localhost:" + port;
            this.allowedHosts = Set.of(
                    "localhost:" + port,
                    "127.0.0.1:" + port,
                    "[::1]:" + port);
        }

        static LoopbackCallback start() throws IOException {
            IOException last = null;
            for (int attempt = 0; attempt < 10; attempt++) {
                int port = PORT_MIN + RANDOM.nextInt(PORT_RANGE);
                HttpServer ipv4;
                try {
                    ipv4 = HttpServer.create(
                            new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
                } catch (IOException busy) {
                    last = busy;
                    continue;
                }
                LoopbackCallback callback = new LoopbackCallback(ipv4.getAddress().getPort());
                callback.install(ipv4);
                HttpServer ipv6 = null;
                try {
                    ipv6 = HttpServer.create(
                            new InetSocketAddress(InetAddress.getByName("::1"), port), 0);
                    callback.install(ipv6);
                } catch (Exception ignored) {
                    // 没有 IPv6 / 被占用：只跑 IPv4。浏览器连 localhost 失败会自动回退。
                    ipv6 = null;
                }
                callback.servers.forEach(HttpServer::start);
                return callback;
            }
            throw last != null ? last : new IOException("no free loopback port");
        }

        private void install(HttpServer server) {
            ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "mcl-oauth-callback");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            servers.add(server);
            executors.add(executor);
        }

        String verifier() {
            return verifier;
        }

        String challenge() {
            return challenge;
        }

        String state() {
            return state;
        }

        String redirectUri() {
            return redirectUri;
        }

        CompletableFuture<String> code() {
            return code;
        }

        private void handle(HttpExchange exchange) {
            try {
                // ① Host 白名单：防 DNS rebinding（攻击者把 evil.com 指到 127.0.0.1）
                String host = exchange.getRequestHeaders().getFirst("Host");
                if (host == null || !allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
                    SafeLog.warn("OAuth 回呼被拒（Host 不在白名单）");
                    reply(exchange, 421, "text/plain; charset=utf-8", bytes("Bad Host"));
                    return;
                }
                // ② 远端必须是 loopback（将来绑错也不会变成对外服务）
                if (exchange.getRemoteAddress() == null
                        || !exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {
                    SafeLog.warn("OAuth 回呼被拒（远端不是 loopback）");
                    reply(exchange, 403, "text/plain; charset=utf-8", bytes("Forbidden"));
                    return;
                }
                // ③ 只接受 GET /（浏览器预取、OPTIONS 之类不参与流程）
                if (!"/".equals(exchange.getRequestURI().getPath())
                        || !"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    reply(exchange, 404, "text/plain; charset=utf-8", new byte[0]);
                    return;
                }
                // ④ 一次性：已经出过终态就 410
                if (consumed.get()) {
                    reply(exchange, 410, "text/plain; charset=utf-8", bytes("Gone"));
                    return;
                }

                Map<String, List<String>> params = parseQuery(exchange.getRequestURI().getRawQuery());
                List<String> codes = params.getOrDefault("code", List.of());
                List<String> errors = params.getOrDefault("error", List.of());
                List<String> states = params.getOrDefault("state", List.of());

                // ⑤ 参数必须恰好一次（重复 code 是典型的参数污染）
                if (codes.size() + errors.size() != 1 || states.size() != 1) {
                    replyPage(exchange, "fail", 400);
                    return;
                }
                // ⑥ state 常数时间比对；缺失或不符一律拒绝
                if (!Pkce.stateMatches(state, states.get(0))) {
                    SafeLog.warn("OAuth 回呼被拒（state 不匹配）");
                    replyPage(exchange, "fail", 400);
                    if (consumed.compareAndSet(false, true)) {
                        code.completeExceptionally(
                                new AuthException(I18n.t("account.ms.callback_bad_state")));
                        stopLater();
                    }
                    return;
                }
                // ⑦ 抢 latch：并发/重放的第二个请求拿不到终态
                if (!consumed.compareAndSet(false, true)) {
                    reply(exchange, 410, "text/plain; charset=utf-8", bytes("Gone"));
                    return;
                }

                if (!codes.isEmpty()) {
                    SafeLog.info("OAuth 回呼完成（Host/远端/state 校验通过）");
                    replyPage(exchange, "ok", 200);
                    code.complete(codes.get(0));
                } else {
                    String error = errors.get(0);
                    boolean denied = "access_denied".equalsIgnoreCase(error);
                    SafeLog.info("OAuth 回呼结束（error=" + (denied ? "access_denied" : "other") + "）");
                    replyPage(exchange, denied ? "cancel" : "fail", 200);
                    code.completeExceptionally(new AuthException(denied
                            ? I18n.t("account.ms.callback_cancelled")
                            : I18n.t("account.ms.callback_bad_request")));
                }
                stopLater();
            } catch (Exception error) {
                try {
                    reply(exchange, 500, "text/plain; charset=utf-8", bytes("Error"));
                } catch (Exception ignored) {
                    // 连回应都写不出去就放弃
                }
            }
        }

        /** 从别的线程关闭，避免在自己的 handler 里 stop 造成等待。 */
        private void stopLater() {
            Thread.ofVirtual().name("mcl-oauth-stop").start(this::close);
        }

        private static void replyPage(HttpExchange exchange, String kind, int status) {
            reply(exchange, status, "text/html; charset=utf-8", resultPage(kind));
        }

        private static void reply(HttpExchange exchange, int status, String contentType, byte[] body) {
            try {
                var headers = exchange.getResponseHeaders();
                headers.set("Content-Type", contentType);
                headers.set("Cache-Control", "no-store");
                headers.set("Referrer-Policy", "no-referrer");
                headers.set("X-Content-Type-Options", "nosniff");
                headers.set("Content-Security-Policy", CSP);
                if (body.length == 0) {
                    exchange.sendResponseHeaders(status, -1);
                } else {
                    exchange.sendResponseHeaders(status, body.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                }
            } catch (IOException ignored) {
                // 浏览器提前关页面是常态
            } finally {
                exchange.close();
            }
        }

        private static Map<String, List<String>> parseQuery(String rawQuery) {
            Map<String, List<String>> result = new LinkedHashMap<>();
            if (rawQuery == null || rawQuery.isEmpty()) {
                return result;
            }
            for (String pair : rawQuery.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                String key = eq < 0 ? pair : pair.substring(0, eq);
                String value = eq < 0 ? "" : pair.substring(eq + 1);
                try {
                    key = URLDecoder.decode(key, StandardCharsets.UTF_8);
                    value = URLDecoder.decode(value, StandardCharsets.UTF_8);
                } catch (IllegalArgumentException badEncoding) {
                    continue;
                }
                result.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
            }
            return result;
        }

        @Override
        public void close() {
            for (HttpServer server : servers) {
                try {
                    server.stop(0);
                } catch (Exception ignored) {
                    // 尽力而为
                }
            }
            for (ExecutorService executor : executors) {
                executor.shutdownNow();
            }
            servers.clear();
            executors.clear();
        }
    }

    // ================================================================
    //  定制结果页（成功 / 失败 / 取消）
    // ================================================================

    private static final String RESULT_TEMPLATE = "/assets/auth/result.html";
    private static volatile String cachedTemplate;

    /**
     * 生成结果页。模板里的占位符只用 {@code %%X%%}（不用 {@code {}}），
     * 因为要嵌 CSS；替换值只来自本项目的语言包，不来自 query 参数。
     */
    private static byte[] resultPage(String kind) {
        String accent = switch (kind) {
            case "ok" -> "#3fb950";
            case "cancel" -> "#d29922";
            default -> "#f85149";
        };
        String icon = switch (kind) {
            case "ok" -> "<path d=\"M4 12.5 9.5 18 20 6.5\"/>";
            case "cancel" -> "<path d=\"M5 12h14\"/>";
            default -> "<path d=\"M6 6l12 12M18 6L6 18\"/>";
        };
        String title = escapeHtml(I18n.t("auth.page." + kind + ".title"));
        String body = escapeHtml(I18n.t("auth.page." + kind + ".body"));
        String hint = escapeHtml(I18n.t("auth.page.hint"));
        String brand = escapeHtml(I18n.t("auth.page.brand"));

        String template = cachedTemplate;
        if (template == null) {
            template = loadTemplate();
            cachedTemplate = template;
        }
        if (template == null) {
            // 资源丢了也要能完成登录（fail-safe）
            return bytes("<!doctype html><meta charset='utf-8'><title>" + brand + "</title>"
                    + "<h2>" + title + "</h2><p>" + body + "</p><p>" + hint + "</p>");
        }
        return template
                .replace("%%BRAND%%", brand)
                .replace("%%TITLE%%", title)
                .replace("%%BODY%%", body)
                .replace("%%HINT%%", hint)
                .replace("%%ACCENT%%", accent)
                .replace("%%ICON%%", icon)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String loadTemplate() {
        try (InputStream in = MicrosoftAuthProvider.class.getResourceAsStream(RESULT_TEMPLATE)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception error) {
            return null;
        }
    }

    private static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    // ================================================================
    //  Xbox / Minecraft 换令牌
    // ================================================================

    private void finishXboxLogin(String msAccessToken, String refreshToken,
                                 Consumer<String> onStateChange,
                                 Consumer<LoginResult> onComplete) throws Exception {
        onStateChange.accept(I18n.t("account.ms.state.xbox"));
        Map<String, Object> xblPayload = Map.of(
                "Properties", Map.of("AuthMethod", "RPS",
                        "SiteName", "user.auth.xboxlive.com",
                        "RpsTicket", "d=" + msAccessToken),
                "RelyingParty", "http://auth.xboxlive.com",
                "TokenType", "JWT");
        JsonNode xblNode = this.postJson(XBL_URL, xblPayload);
        String xblToken = xblNode.path("Token").asText("");
        String uhs = firstXuiUhs(xblNode);
        if (xblToken.isEmpty() || uhs.isEmpty()) {
            throw new AuthException(I18n.t("account.ms.xbox_failed", responseHint(xblNode)));
        }

        onStateChange.accept(I18n.t("account.ms.state.xsts"));
        Map<String, Object> xstsPayload = Map.of(
                "Properties", Map.of("SandboxId", "RETAIL", "UserTokens", List.of(xblToken)),
                "RelyingParty", "rp://api.minecraftservices.com/",
                "TokenType", "JWT");
        JsonNode xstsNode = this.postJson(XSTS_URL, xstsPayload);
        if (xstsNode.has("XErr")) {
            throw new AuthException(xstsErrorMessage(xstsNode.path("XErr").asLong()));
        }

        String xstsToken = xstsNode.path("Token").asText("");
        String xstsUhs = firstXuiUhs(xstsNode);
        if (xstsToken.isEmpty() || xstsUhs.isEmpty()) {
            throw new AuthException(I18n.t("account.ms.xsts_failed", responseHint(xstsNode)));
        }

        onStateChange.accept(I18n.t("account.ms.state.minecraft"));
        Map<String, Object> mcPayload = Map.of(
                "identityToken", "XBL3.0 x=" + xstsUhs + ";" + xstsToken);

        JsonNode mcNode;
        try {
            mcNode = this.postJson(MC_LOGIN_URL, mcPayload);
        } catch (AuthException mcError) {
            logXstsClaims(xstsToken);
            String message = mcError.getMessage() == null ? "" : mcError.getMessage();
            if (message.contains("429")) {
                throw new AuthException(I18n.t("account.ms.rate_limited"));
            }
            if (!message.contains("Invalid app registration") && !message.contains("AppRegInfo")) {
                throw new AuthException(message + "\n\n" + xstsClaimsSummary(xstsToken));
            }
            throw new AuthException(I18n.t("account.ms.app_rejected",
                    message + "\n\n" + xstsClaimsSummary(xstsToken)));
        }

        String mcAccessToken = mcNode.path("access_token").asText("");
        if (mcAccessToken.isEmpty()) {
            throw new AuthException(I18n.t("account.ms.no_java_edition", responseHint(mcNode)));
        }

        long expiresIn = mcNode.path("expires_in").asLong(86400L);
        onStateChange.accept(I18n.t("account.ms.state.profile"));
        HttpRequest profileReq = HttpRequest.newBuilder(URI.create(MC_PROFILE_URL))
                .header("Authorization", "Bearer " + mcAccessToken)
                .header("User-Agent", USER_AGENT)
                .timeout(TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> profileResp = Http.authClient()
                .send(profileReq, BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (profileResp.statusCode() != 200) {
            throw new AuthException(I18n.t("account.ms.profile_failed",
                    String.valueOf(profileResp.statusCode())));
        }

        JsonNode profileNode = this.mapper.readTree(profileResp.body());
        String uuid = profileNode.path("id").asText("");
        String name = profileNode.path("name").asText("");
        if (uuid.isEmpty() || name.isEmpty()) {
            throw new AuthException(I18n.t("account.ms.profile_missing"));
        }

        char[] refreshChars = refreshToken == null || refreshToken.isBlank()
                ? null : refreshToken.toCharArray();
        StoredSession session = new StoredSession(AccountType.MICROSOFT, uuid, name,
                refreshChars, mcAccessToken.toCharArray(),
                System.currentTimeMillis() + expiresIn * 1000L, (String) null);
        SafeLog.loginSuccess(AccountType.MICROSOFT);
        onComplete.accept(LoginResult.ok(session));
    }

    // ================================================================
    //  HTTP / JSON 工具
    // ================================================================

    private JsonNode postForm(String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .timeout(TIMEOUT)
                .POST(BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = Http.authClient()
                .send(request, BodyHandlers.ofString(StandardCharsets.UTF_8));
        return this.mapper.readTree(response.body());
    }

    private JsonNode postJson(String url, Object payload) throws Exception {
        String body = this.mapper.writeValueAsString(payload);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .timeout(TIMEOUT)
                .POST(BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = Http.authClient()
                .send(request, BodyHandlers.ofString(StandardCharsets.UTF_8));
        String raw = response.body();
        JsonNode node = this.mapper.readTree(raw != null && !raw.isBlank() ? raw : "{}");
        if (response.statusCode() != 200 && !node.has("XErr")) {
            if (raw != null && raw.length() > 1200) {
                raw = raw.substring(0, 1200) + "…";
            }
            // 原始回应体可能包含 application ID（AADSTS 就会回显）→ 统一打码
            throw new AuthException("HTTP " + response.statusCode() + " @ "
                    + URI.create(url).getHost() + "：" + responseHint(node)
                    + "\n\n" + I18n.t("account.ms.raw_response") + "\n" + Redactor.mask(raw));
        }
        return node;
    }

    private static String firstXuiUhs(JsonNode node) {
        JsonNode xui = node.path("DisplayClaims").path("xui");
        return xui.isArray() && !xui.isEmpty() ? xui.get(0).path("uhs").asText("") : "";
    }

    private static String describeOAuthError(JsonNode node) {
        String description = node.path("error_description").asText("");
        String error = node.path("error").asText("");
        if ("unauthorized_client".equals(error) || description.contains("AADSTS700016")) {
            return I18n.t("account.ms.client_id_invalid");
        }
        String text = !description.isBlank() ? description : (!error.isBlank() ? error : responseHint(node));
        return Redactor.mask(text);
    }

    private static String responseHint(JsonNode node) {
        for (String key : List.of("error_description", "errorMessage", "error", "message", "path")) {
            String value = node.path(key).asText("");
            if (!value.isBlank()) {
                return Redactor.mask(value.length() > 200 ? value.substring(0, 200) + "…" : value);
            }
        }
        return I18n.t("account.ms.bad_response");
    }

    /**
     * 展示给使用者的 XSTS 摘要。
     *
     * <p>安全审计 L-05：以前会把 appid / xtid 的值拼进错误文本，使用者截图求助就泄漏账号标识。
     * 现在只给「有没有」这种布林讯息（与 {@link #logXstsClaims} 口径一致）。
     */
    private static String xstsClaimsSummary(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) {
                return I18n.t("account.ms.claims_unparsable");
            }
            JsonNode claims = Http.mapper().readTree(Base64.getUrlDecoder().decode(parts[1]));
            return I18n.t("account.ms.claims_summary",
                    claims.has("rp") ? I18n.t("common.yes") : I18n.t("common.no"),
                    claims.has("xui") ? I18n.t("common.yes") : I18n.t("common.no"),
                    claims.has("xtid") ? I18n.t("common.yes") : I18n.t("common.no"));
        } catch (Exception error) {
            return I18n.t("account.ms.claims_failed");
        }
    }

    private static void logXstsClaims(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) {
                return;
            }
            JsonNode claims = Http.mapper().readTree(Base64.getUrlDecoder().decode(parts[1]));
            // 只记录「有没有」这种布林讯息，不打印 appid/xtid 的值
            SafeLog.info("XSTS 声明校验通过（rp=" + (claims.has("rp") ? "有" : "无")
                    + ", xui=" + (claims.has("xui") ? "有" : "无")
                    + ", xtid=" + (claims.has("xtid") ? "有" : "无") + "）");
        } catch (Exception ignored) {
            // 解析失败不影响登录
        }
    }

    private static String xstsErrorMessage(long xerr) {
        if (xerr == 2148916233L) {
            return I18n.t("account.ms.xerr.no_xbox_account");
        } else if (xerr == 2148916235L) {
            return I18n.t("account.ms.xerr.region");
        } else if (xerr == 2148916236L || xerr == 2148916237L) {
            return I18n.t("account.ms.xerr.age_verify");
        } else if (xerr == 2148916238L) {
            return I18n.t("account.ms.xerr.child");
        }
        return I18n.t("account.ms.xerr.other", String.valueOf(xerr));
    }

    private static String safeError(Exception error) {
        String message = error.getMessage();
        if (message != null && !message.isBlank()) {
            return Redactor.mask(message.length() > 300 ? message.substring(0, 300) + "…" : message);
        }
        return I18n.t("account.ms.failed_generic", error.getClass().getSimpleName());
    }

    private static String encode(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    /**
     * 用 refresh_token 续期：refresh_token → 新 MSA token（顺带轮换）→ 复用登录链路。
     */
    @Override
    public LoginResult refresh(StoredSession session) {
        if (session == null || session.refreshToken() == null) {
            return LoginResult.fail(I18n.t("account.ms.refresh_unsupported"));
        }
        String cid = effectiveClientId();
        if (cid == null || cid.isBlank()) {
            return LoginResult.fail(I18n.t("account.ms.client_id_missing"));
        }
        try {
            String body = "grant_type=refresh_token"
                    + "&client_id=" + encode(cid)
                    + "&refresh_token=" + encode(new String(session.refreshToken()))
                    + "&scope=" + encode(SCOPE);
            JsonNode tokenNode = postForm(TOKEN_URL, body);
            String access = tokenNode.path("access_token").asText("");
            if (access.isEmpty()) {
                SafeLog.warn("[auth] 刷新令牌失败：" + describeOAuthError(tokenNode));
                return LoginResult.fail(I18n.t("account.ms.refresh_unsupported"));
            }
            String rotated = tokenNode.path("refresh_token").asText("");
            String finalRefresh = rotated.isEmpty()
                    ? new String(session.refreshToken()) : rotated;
            LoginResult[] holder = new LoginResult[1];
            finishXboxLogin(access, finalRefresh, state -> { }, result -> holder[0] = result);
            if (holder[0] == null) {
                return LoginResult.fail(I18n.t("account.ms.refresh_unsupported"));
            }
            SafeLog.info("[auth] 已用 refresh token 续期");
            return holder[0];
        } catch (Exception error) {
            SafeLog.warn("[auth] 刷新令牌异常：" + error.getClass().getSimpleName());
            return LoginResult.fail(safeError(error));
        }
    }

    public boolean validate(StoredSession session) {
        return session != null && !session.isExpired();
    }

    public GameSession toGameSession(StoredSession session) {
        return new GameSession(session.name(), session.uuid(), session.accessToken(), "msa", "{}", (String) null);
    }
}
