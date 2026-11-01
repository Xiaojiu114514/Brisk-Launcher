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
import com.brisklauncher.mcl.net.Http;
import com.brisklauncher.mcl.security.SafeLog;
import com.brisklauncher.mcl.ui.DesktopActions;
import com.brisklauncher.mcl.util.MsClientId;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class MicrosoftAuthProvider implements AuthProvider {
   private static final String SCOPE = "XboxLive.signin offline_access";
   private static final String DEVICE_CODE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
   private static final String TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
   private static final String XBL_URL = "https://user.auth.xboxlive.com/user/authenticate";
   private static final String XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
   private static final String MC_LOGIN_URL = "https://api.minecraftservices.com/authentication/login_with_xbox";
   private static final String MC_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";
   private static final String USER_AGENT = "BriskLauncher/1.0.0";
   private static final Duration TIMEOUT = Duration.ofSeconds(30L);
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

   public LoginResult login(LoginCredentials credentials) {
      return LoginResult.fail("正版登录必须使用设备码流程");
   }

   public void loginWithDeviceCode(Consumer<DeviceCodeFlow> onDeviceCode, Consumer<String> onStateChange, Consumer<LoginResult> onComplete) {
      Thread.ofVirtual().name("mcl-microsoft-login").start(() -> {
         try {
            onStateChange.accept("正在申请设备码…");
            String effectiveId = this.effectiveClientId();
            String problem = describeClientIdProblem(effectiveId);
            if (problem != null) {
               onComplete.accept(LoginResult.fail(problem));
               return;
            }

            String var10000 = encode(effectiveId);
            String body = "client_id=" + var10000 + "&scope=" + encode("XboxLive.signin offline_access");
            JsonNode codeNode = this.postForm("https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode", body);
            if (codeNode.hasNonNull("error")) {
               throw new AuthException("微软拒绝了设备码申请：" + describeOAuthError(codeNode));
            }

            String userCode = codeNode.path("user_code").asText("");
            String deviceCode = codeNode.path("device_code").asText("");
            String verification = codeNode.path("verification_uri").asText("");
            if (userCode.isEmpty() || deviceCode.isEmpty() || verification.isEmpty()) {
               throw new AuthException("微软返回的设备码不完整，请稍后重试");
            }

            DeviceCodeFlow flow = new DeviceCodeFlow(userCode, URI.create(verification), deviceCode, codeNode.path("interval").asInt(5), codeNode.path("expires_in").asInt(900));
            onDeviceCode.accept(flow);
            onStateChange.accept("等待网页授权…（复制代码后打开登录页）");
            long deadline = System.currentTimeMillis() + (long)flow.expiresIn() * 1000L;
            int interval = Math.max(flow.interval(), 3);
            String msAccessToken = null;

            while(System.currentTimeMillis() < deadline) {
               Thread.sleep((long)interval * 1000L);
               var10000 = encode(this.effectiveClientId());
               String pollBody = "grant_type=urn:ietf:params:oauth:grant-type:device_code&client_id=" + var10000 + "&device_code=" + encode(flow.deviceCode());
               JsonNode pollNode = this.postForm("https://login.microsoftonline.com/consumers/oauth2/v2.0/token", pollBody);
               String error = pollNode.path("error").asText("");
               if (error.isEmpty()) {
                  msAccessToken = pollNode.path("access_token").asText("");
                  if (!msAccessToken.isEmpty()) {
                     break;
                  }
               } else if (!"authorization_pending".equals(error)) {
                  if (!"slow_down".equals(error)) {
                     if ("expired_token".equals(error)) {
                        throw new AuthException("设备码已过期，请重新点击登录");
                     }

                     if ("authorization_declined".equals(error)) {
                        throw new AuthException("你拒绝了本次授权");
                     }

                     throw new AuthException("网页授权失败：" + describeOAuthError(pollNode));
                  }

                  interval += 3;
               }
            }

            if (msAccessToken == null || msAccessToken.isEmpty()) {
               throw new AuthException("等待授权超时，请重新登录并在网页上完成授权");
            }

            onStateChange.accept("正在登录 Xbox Live…");
            Map<String, Object> xblPayload = Map.of("Properties", Map.of("AuthMethod", "RPS", "SiteName", "user.auth.xboxlive.com", "RpsTicket", "d=" + msAccessToken), "RelyingParty", "http://auth.xboxlive.com", "TokenType", "JWT");
            JsonNode xblNode = this.postJson("https://user.auth.xboxlive.com/user/authenticate", xblPayload);
            String xblToken = xblNode.path("Token").asText("");
            String uhs = firstXuiUhs(xblNode);
            if (xblToken.isEmpty() || uhs.isEmpty()) {
               throw new AuthException("Xbox Live 登录失败：" + responseHint(xblNode));
            }

            onStateChange.accept("正在获取 XSTS 令牌…");
            Map<String, Object> xstsPayload = Map.of("Properties", Map.of("SandboxId", "RETAIL", "UserTokens", List.of(xblToken)), "RelyingParty", "rp://api.minecraftservices.com/", "TokenType", "JWT");
            JsonNode xstsNode = this.postJson("https://xsts.auth.xboxlive.com/xsts/authorize", xstsPayload);
            if (xstsNode.has("XErr")) {
               throw new AuthException(xstsErrorMessage(xstsNode.path("XErr").asLong()));
            }

            String xstsToken = xstsNode.path("Token").asText("");
            String xstsUhs = firstXuiUhs(xstsNode);
            if (xstsToken.isEmpty() || xstsUhs.isEmpty()) {
               throw new AuthException("XSTS 授权失败：" + responseHint(xstsNode));
            }

            onStateChange.accept("正在登录 Minecraft…");
            Map<String, Object> mcPayload = Map.of("identityToken", "XBL3.0 x=" + xstsUhs + ";" + xstsToken);

            JsonNode mcNode;
            try {
               mcNode = this.postJson("https://api.minecraftservices.com/authentication/login_with_xbox", mcPayload);
            } catch (AuthException mcError) {
               logXstsClaims(xstsToken);
               String var10002 = mcError.getMessage();
               throw new AuthException(var10002 + "\n\n" + xstsClaimsSummary(xstsToken) + "\nuhs 长度=" + xstsUhs.length());
            }

            String mcAccessToken = mcNode.path("access_token").asText("");
            if (mcAccessToken.isEmpty()) {
               throw new AuthException("Minecraft 登录失败（该账号可能没有购买 Java 版）：" + responseHint(mcNode));
            }

            long expiresIn = mcNode.path("expires_in").asLong(86400L);
            onStateChange.accept("正在获取账户信息…");
            HttpRequest profileReq = HttpRequest.newBuilder(URI.create("https://api.minecraftservices.com/minecraft/profile")).header("Authorization", "Bearer " + mcAccessToken).header("User-Agent", "BriskLauncher/1.0.0").timeout(TIMEOUT).GET().build();
            HttpResponse<String> profileResp = Http.authClient().send(profileReq, BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (profileResp.statusCode() != 200) {
               throw new AuthException("获取 Minecraft 档案失败（HTTP " + profileResp.statusCode() + "），请确认该账号已购买 Java 版并创建过角色");
            }

            JsonNode profileNode = this.mapper.readTree((String)profileResp.body());
            String uuid = profileNode.path("id").asText("");
            String name = profileNode.path("name").asText("");
            if (uuid.isEmpty() || name.isEmpty()) {
               throw new AuthException("Minecraft 档案缺少角色信息，请先在官方启动器创建角色");
            }

            StoredSession session = new StoredSession(AccountType.MICROSOFT, uuid, name, (char[])null, mcAccessToken.toCharArray(), System.currentTimeMillis() + expiresIn * 1000L, (String)null);
            SafeLog.loginSuccess(AccountType.MICROSOFT);
            onComplete.accept(LoginResult.ok(session));
         } catch (AuthException e) {
            onComplete.accept(LoginResult.fail(e.getMessage()));
         } catch (Exception e) {
            onComplete.accept(LoginResult.fail(safeError(e)));
         }

      });
   }

   private String effectiveClientId() {
      return MsClientId.resolve(this.clientId);
   }

   private static String describeClientIdProblem(String id) {
      if (id != null && !id.isBlank()) {
         return !id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") && !id.matches("000000004[0-9A-Fa-f]{7}") ? "客户端 ID 格式不正确（应为 8-4-4-4-12 的 GUID）。请确认复制的是「应用程序(客户端) ID」，而不是「对象 ID」或「租户 ID」。" : null;
      } else {
         // 用户可见的文字走 I18n（简中写死会在繁中界面漏出简体）
         return com.brisklauncher.mcl.i18n.I18n.t("account.ms.client_id_missing");
      }
   }

   private JsonNode postForm(String url, String body) throws Exception {
      HttpRequest request = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json").header("User-Agent", "BriskLauncher/1.0.0").timeout(TIMEOUT).POST(BodyPublishers.ofString(body)).build();
      HttpResponse<String> response = Http.authClient().send(request, BodyHandlers.ofString(StandardCharsets.UTF_8));
      return this.mapper.readTree((String)response.body());
   }

   /**
    * 展示给用户的 XSTS 摘要。
    *
    * <p>安全审计 L-05：以前会把 appid / xtid 的值拼进错误文本，用户截图求助就泄露账号标识。
    * 现在只给「有没有」这种布尔信息（与 logXstsClaims 口径一致）。
    */
   private static String xstsClaimsSummary(String jwt) {
      try {
         String[] parts = jwt.split("\\.");
         if (parts.length < 2) {
            return "XSTS 声明：无法解析";
         }
         JsonNode claims = Http.mapper().readTree(Base64.getUrlDecoder().decode(parts[1]));
         return "XSTS 声明：rp=" + (claims.has("rp") ? "有" : "无")
                 + "，xui=" + (claims.has("xui") ? "有" : "无")
                 + "，xtid=" + (claims.has("xtid") ? "有" : "无");
      } catch (Exception var3) {
         return "XSTS 声明：解析失败";
      }
   }

   private static void logXstsClaims(String jwt) {
      try {
         String[] parts = jwt.split("\\.");
         if (parts.length < 2) {
            return;
         }

         byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
         JsonNode claims = Http.mapper().readTree(payload);
         String var10000 = claims.path("appid").asText("(无)");
         // 安全：只记录「有没有 rp/xui/xtid」这种布林讯息，不打印 appid/xtid 的值
          SafeLog.info("XSTS 声明校验通过（rp=" + (claims.has("rp") ? "有" : "无")
                  + ", xui=" + (claims.has("xui") ? "有" : "无")
                  + ", xtid=" + (claims.has("xtid") ? "有" : "无") + "）");
      } catch (Exception var4) {
      }

   }

   public void loginWithBrowser(Consumer<String> onStateChange, Consumer<LoginResult> onComplete) {
      Thread.ofVirtual().name("mcl-ms-browser").start(() -> {
         HttpServer server = null;

         try {
            String problem = describeClientIdProblem(this.effectiveClientId());
            if (problem == null) {
               // PKCE（公开客户端必备）：verifier 只留在本机，challenge 才给浏览器；
               // 宣告在回呼 handler 之前，lambda 才能比对 state。
               String codeVerifier = Pkce.verifier();
               String codeChallenge = Pkce.challenge(codeVerifier);
               String state = Pkce.state();
               server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
               int port = server.getAddress().getPort();
               String redirect = "http://localhost:" + port;
               CompletableFuture<String> codeFuture = new CompletableFuture();
               server.createContext("/", (exchange) -> {
                  String query = exchange.getRequestURI().getQuery();
                  String code = null;
                  String error = null;
                  String callbackState = null;
                  if (query != null) {
                     for(String pair : query.split("&")) {
                        String[] kv = pair.split("=", 2);
                        if (kv.length == 2 && "code".equals(kv[0])) {
                           code = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                        } else if (kv.length == 2 && "error".equals(kv[0])) {
                           error = kv[1];
                        } else if (kv.length == 2 && "state".equals(kv[0])) {
                           callbackState = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                        }
                     }
                  }
                  // CSRF 防护（安全审计 M1）：state 必须存在且一致，缺了也要拒绝；
                  // 而且只处理根路径的 GET，避免本机其他程序/浏览器预取把流程打断。
                  if (!"/".equals(exchange.getRequestURI().getPath())
                          || !"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                     exchange.sendResponseHeaders(404, -1);
                     exchange.close();
                     return;
                  }
                  if (callbackState == null || !Pkce.stateMatches(state, callbackState)) {
                     byte[] deny = ("<html><head><meta charset='utf-8'></head><body style='font-family:sans-serif;"
                             + "padding:40px'><h2>授权被拒绝</h2><p>state 校验失败，请回到启动器重试。</p></body></html>")
                             .getBytes(StandardCharsets.UTF_8);
                     exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                     exchange.sendResponseHeaders(400, (long) deny.length);
                     exchange.getResponseBody().write(deny);
                     exchange.close();
                     codeFuture.completeExceptionally(new AuthException("登录回调的 state 缺失或不匹配，已拒绝本次授权"));
                     return;
                  }
                  // L13：只有明确带 code 或 error 的请求才算结束（浏览器预取不再打断登录）
                  if (code == null && error == null) {
                     exchange.sendResponseHeaders(400, -1);
                     exchange.close();
                     return;
                  }

                  byte[] body = "<html><head><meta charset='utf-8'><title>Brisk Launcher</title></head><body style='font-family:sans-serif;padding:40px'><h2>登录完成</h2><p>可以关闭此页面，回到启动器。</p></body></html>".getBytes(StandardCharsets.UTF_8);
                  exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                  exchange.sendResponseHeaders(200, (long)body.length);
                  exchange.getResponseBody().write(body);
                  exchange.close();
                  if (code != null) {
                     codeFuture.complete(code);
                  } else {
                     codeFuture.completeExceptionally(new AuthException("授权被取消或失败：" + error));
                  }

               });
               server.start();
               String cid = this.effectiveClientId();
               String var10000 = encode(cid);
               String scope = "XboxLive.signin offline_access";
               String authorizeUrl = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize?client_id="
                  + encode(cid) + "&response_type=code&redirect_uri=" + encode(redirect)
                  + "&scope=" + encode(scope) + "&prompt=select_account"
                  + "&code_challenge=" + encode(codeChallenge) + "&code_challenge_method=S256"
                  + "&state=" + encode(state);
               onStateChange.accept("已在浏览器打开登录页，请完成微软登录…");
               if (!DesktopActions.openUrl(authorizeUrl)) {
                  onComplete.accept(LoginResult.fail("无法打开系统浏览器。请手动访问：\n" + authorizeUrl));
                  return;
               }

               String code = (String)codeFuture.get(240L, TimeUnit.SECONDS);
               server.stop(0);
               server = null;
               onStateChange.accept("正在换取令牌…");
               var10000 = encode(code);
               String tokenBody = "grant_type=authorization_code&code=" + var10000 + "&redirect_uri=" + encode(redirect) + "&client_id=" + encode(cid) + "&scope=" + encode(scope) + "&code_verifier=" + encode(codeVerifier);
               JsonNode tokenNode = this.postForm("https://login.microsoftonline.com/consumers/oauth2/v2.0/token", tokenBody);
               String msToken = tokenNode.path("access_token").asText("");
               if (msToken.isEmpty()) {
                  throw new AuthException("换取微软令牌失败：" + describeOAuthError(tokenNode));
               }

               this.finishXboxLogin(msToken, onStateChange, onComplete);
               return;
            }

            onComplete.accept(LoginResult.fail(problem));
         } catch (AuthException e) {
            onComplete.accept(LoginResult.fail(e.getMessage()));
            return;
         } catch (Exception e) {
            String var10001 = e.getClass().getSimpleName();
            onComplete.accept(LoginResult.fail("浏览器登录失败：" + var10001 + (e.getMessage() == null ? "" : " - " + e.getMessage())));
            return;
         } finally {
            if (server != null) {
               server.stop(0);
            }

         }

      });
   }

   private void finishXboxLogin(String msAccessToken, Consumer<String> onStateChange, Consumer<LoginResult> onComplete) throws Exception {
      onStateChange.accept("正在登录 Xbox Live…");
      Map<String, Object> xblPayload = Map.of("Properties", Map.of("AuthMethod", "RPS", "SiteName", "user.auth.xboxlive.com", "RpsTicket", "d=" + msAccessToken), "RelyingParty", "http://auth.xboxlive.com", "TokenType", "JWT");
      JsonNode xblNode = this.postJson("https://user.auth.xboxlive.com/user/authenticate", xblPayload);
      String xblToken = xblNode.path("Token").asText("");
      String uhs = firstXuiUhs(xblNode);
      if (!xblToken.isEmpty() && !uhs.isEmpty()) {
         onStateChange.accept("正在获取 XSTS 令牌…");
         Map<String, Object> xstsPayload = Map.of("Properties", Map.of("SandboxId", "RETAIL", "UserTokens", List.of(xblToken)), "RelyingParty", "rp://api.minecraftservices.com/", "TokenType", "JWT");
         JsonNode xstsNode = this.postJson("https://xsts.auth.xboxlive.com/xsts/authorize", xstsPayload);
         if (xstsNode.has("XErr")) {
            throw new AuthException(xstsErrorMessage(xstsNode.path("XErr").asLong()));
         } else {
            String xstsToken = xstsNode.path("Token").asText("");
            String xstsUhs = firstXuiUhs(xstsNode);
            if (!xstsToken.isEmpty() && !xstsUhs.isEmpty()) {
               onStateChange.accept("正在登录 Minecraft…");
               Map<String, Object> mcPayload = Map.of("identityToken", "XBL3.0 x=" + xstsUhs + ";" + xstsToken);

               JsonNode mcNode;
               try {
                  mcNode = this.postJson("https://api.minecraftservices.com/authentication/login_with_xbox", mcPayload);
               } catch (AuthException mcError) {
                  String message = mcError.getMessage() == null ? "" : mcError.getMessage();
                  if (message.contains("429")) {
                     throw new AuthException("Minecraft 登入被限流（HTTP 429）。\n这是同一个应用 ID 短时间内请求过多导致的，不是账号或权限问题。\n请等待 15~20 分钟后再试一次（期间不要重复点击登录）。");
                  }

                  if (!message.contains("Invalid app registration") && !message.contains("AppRegInfo")) {
                     throw new AuthException(message + "\n\n" + xstsClaimsSummary(xstsToken));
                  }

                  throw new AuthException("Minecraft 服务拒绝了当前应用 ID（403）。\n\n" + message + "\n\n" + xstsClaimsSummary(xstsToken));
               }

               String mcAccessToken = mcNode.path("access_token").asText("");
               if (mcAccessToken.isEmpty()) {
                  throw new AuthException("Minecraft 登录失败（该账号可能没有购买 Java 版）：" + responseHint(mcNode));
               } else {
                  long expiresIn = mcNode.path("expires_in").asLong(86400L);
                  onStateChange.accept("正在获取账户信息…");
                  HttpRequest profileReq = HttpRequest.newBuilder(URI.create("https://api.minecraftservices.com/minecraft/profile")).header("Authorization", "Bearer " + mcAccessToken).header("User-Agent", "BriskLauncher/1.0.0").timeout(TIMEOUT).GET().build();
                  HttpResponse<String> profileResp = Http.authClient().send(profileReq, BodyHandlers.ofString(StandardCharsets.UTF_8));
                  if (profileResp.statusCode() != 200) {
                     throw new AuthException("获取 Minecraft 档案失败（HTTP " + profileResp.statusCode() + "），请确认该账号已购买 Java 版并创建过角色");
                  } else {
                     JsonNode profileNode = this.mapper.readTree((String)profileResp.body());
                     String uuid = profileNode.path("id").asText("");
                     String name = profileNode.path("name").asText("");
                     if (!uuid.isEmpty() && !name.isEmpty()) {
                        StoredSession session = new StoredSession(AccountType.MICROSOFT, uuid, name, (char[])null, mcAccessToken.toCharArray(), System.currentTimeMillis() + expiresIn * 1000L, (String)null);
                        SafeLog.loginSuccess(AccountType.MICROSOFT);
                        onComplete.accept(LoginResult.ok(session));
                     } else {
                        throw new AuthException("Minecraft 档案缺少角色信息，请先在官方启动器创建角色");
                     }
                  }
               }
            } else {
               throw new AuthException("XSTS 授权失败：" + responseHint(xstsNode));
            }
         }
      } else {
         throw new AuthException("Xbox Live 登录失败：" + responseHint(xblNode));
      }
   }

   private JsonNode postJson(String url, Object payload) throws Exception {
      String body = this.mapper.writeValueAsString(payload);
      HttpRequest request = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json").header("Accept", "application/json").header("User-Agent", "BriskLauncher/1.0.0").timeout(TIMEOUT).POST(BodyPublishers.ofString(body)).build();
      HttpResponse<String> response = Http.authClient().send(request, BodyHandlers.ofString(StandardCharsets.UTF_8));
      JsonNode node = this.mapper.readTree(response.body() != null && !((String)response.body()).isBlank() ? (String)response.body() : "{}");
      if (response.statusCode() != 200 && !node.has("XErr")) {
         int var10002 = response.statusCode();
         String raw = (String)response.body();
         if (raw != null && raw.length() > 1200) {
            raw = raw.substring(0, 1200) + "…";
         }

         throw new AuthException("HTTP " + var10002 + " @ " + URI.create(url).getHost() + "：" + responseHint(node) + "\n\n原始响应：\n" + raw);
      } else {
         return node;
      }
   }

   private static String firstXuiUhs(JsonNode node) {
      JsonNode xui = node.path("DisplayClaims").path("xui");
      return xui.isArray() && !xui.isEmpty() ? xui.get(0).path("uhs").asText("") : "";
   }

   private static String describeOAuthError(JsonNode node) {
      String description = node.path("error_description").asText("");
      String error = node.path("error").asText("");
      if (!description.contains("AADSTS700016") && !"unauthorized_client".equals(error)) {
         if (!description.isBlank()) {
            return description;
         } else {
            return error.isBlank() ? responseHint(node) : error;
         }
      } else {
         return "微软客户端 ID 无效或已被停用。请在“设置 → 网络”里填写你自己的 Azure 应用客户端 ID（需启用设备代码流并申请 Minecraft API 权限），具体步骤见设置页的帮助链接。";
      }
   }

   private static String responseHint(JsonNode node) {
      for(String key : List.of("error_description", "errorMessage", "error", "message", "path")) {
         String value = node.path(key).asText("");
         if (!value.isBlank()) {
            return value.length() > 200 ? value.substring(0, 200) + "…" : value;
         }
      }

      return "响应格式不正确";
   }

   private static String xstsErrorMessage(long xerr) {
      if (xerr == 2148916233L) {
         return "该 Microsoft 账号还没有 Xbox 账号，请先在 xbox.com 创建";
      } else if (xerr == 2148916235L) {
         return "Xbox Live 在你所在地区不可用";
      } else if (xerr != 2148916236L && xerr != 2148916237L) {
         return xerr == 2148916238L ? "这是一个儿童账号，需要先加入家庭组" : "XSTS 授权失败（XErr=" + xerr + "）";
      } else {
         return "该账号需要完成年龄验证";
      }
   }

   private static String safeError(Exception error) {
      String message = error.getMessage();
      if (message != null && !message.isBlank()) {
         return message.length() > 300 ? message.substring(0, 300) + "…" : message;
      } else {
         return "登录失败：" + error.getClass().getSimpleName();
      }
   }

   private static String encode(String raw) {
      return URLEncoder.encode(raw, StandardCharsets.UTF_8);
   }

   public LoginResult refresh(StoredSession session) {
      // 刻意不保存 refresh token（见 README 安全说明），所以只能请用户重新登录
      return LoginResult.fail(com.brisklauncher.mcl.i18n.I18n.t("account.ms.refresh_unsupported"));
   }

   public boolean validate(StoredSession session) {
      return session != null && !session.isExpired();
   }

   public GameSession toGameSession(StoredSession session) {
      return new GameSession(session.name(), session.uuid(), session.accessToken(), "msa", "{}", (String)null);
   }

   public static record DeviceCodeFlow(String userCode, URI verificationUri, String deviceCode, int interval, int expiresIn) {
   }
}
