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

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Yggdrasil 第三方登录（LittleSkin / Blessing Skin 等）。
 *
 * 安全：密码只在本次请求体内流转，不写日志、不落盘。
 * authlib-injector 注入由 GameLauncher 负责，本类只负责拿 token。
 */
public final class YggdrasilAuthProvider implements AuthProvider {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public AccountType type() {
        return AccountType.THIRD_PARTY;
    }

    @Override
    public LoginResult login(LoginCredentials credentials) {
        try {
            String endpoint = normalizeEndpoint(credentials.endpoint());
            String username = credentials.username();
            char[] secret = credentials.secret();
            if (username == null || username.isBlank() || secret == null || secret.length == 0) {
                return LoginResult.fail("用户名或密码为空");
            }

            String clientToken = UUID.randomUUID().toString();
            Map<String, Object> payload = Map.of(
                    "username", username,
                    "password", new String(secret),
                    "clientToken", clientToken,
                    "agent", Map.of("name", "Minecraft", "version", 1),
                    "requestUser", true);

            JsonNode node = postJson(endpoint + "/authserver/authenticate", payload, null);
            String accessToken = node.path("accessToken").asText("");
            if (accessToken.isEmpty()) {
                String hint = firstText(node, "errorMessage", "error", "message");
                return LoginResult.fail(hint.isEmpty()
                        ? "认证服务器没有返回 accessToken（请确认地址是 Yggdrasil API，例如 "
                        + "https://littleskin.cn/api/yggdrasil）"
                        : "认证失败：" + hint);
            }
            JsonNode profile = node.path("selectedProfile");
            if (!profile.hasNonNull("id") || !profile.hasNonNull("name")) {
                // 部分皮肤站（LittleSkin）不会替账号自动选角色，只返回 availableProfiles
                JsonNode available = node.path("availableProfiles");
                if (available.isArray()) {
                    for (JsonNode candidate : available) {
                        if (candidate.hasNonNull("id") && candidate.hasNonNull("name")) {
                            profile = candidate;
                            break;
                        }
                    }
                }
            }
            if (!profile.hasNonNull("id") || !profile.hasNonNull("name")) {
                return LoginResult.fail("该账号没有可用的 Minecraft 角色。"
                        + "请确认角色已创建并绑定到当前账号，或直接在皮肤站把该角色设为默认角色。");
            }
            String serverClientToken = node.path("clientToken").asText("");

            List<com.brisklauncher.mcl.auth.api.StoredSession> sessions = new java.util.ArrayList<>();
            JsonNode available = node.path("availableProfiles");
            if (available.isArray()) {
                for (JsonNode candidate : available) {
                    if (candidate.hasNonNull("id") && candidate.hasNonNull("name")) {
                        sessions.add(sessionFor(candidate, serverClientToken.isEmpty()
                                ? clientToken : serverClientToken, accessToken, endpoint));
                    }
                }
            }
            String selectedId = profile.path("id").asText();
            if (sessions.stream().noneMatch(session -> session.uuid().equals(selectedId))) {
                sessions.add(0, sessionFor(profile, serverClientToken.isEmpty()
                        ? clientToken : serverClientToken, accessToken, endpoint));
            } else {
                for (int i = 0; i < sessions.size(); i++) {
                    if (sessions.get(i).uuid().equals(selectedId) && i > 0) {
                        sessions.add(0, sessions.remove(i));
                        break;
                    }
                }
            }

            SafeLog.loginSuccess(AccountType.THIRD_PARTY);
            return LoginResult.ok(sessions);
        } catch (AuthException e) {
            return LoginResult.fail(e.getMessage());
        } catch (Exception e) {
            SafeLog.warn("第三方登录异常：" + e.getClass().getSimpleName());
            String message = e.getMessage();
            return LoginResult.fail(message == null || message.isBlank()
                    ? "认证服务异常（网络或地址错误）"
                    : "认证服务异常：" + message);
        }
    }

    /**
     * 把远端返回的角色建档转成会话。
     *
     * <p>安全审计 H-01 / M-04：{@code id} 与 {@code name} 都是**远端不可信输入**——
     * {@code id} 会被拿去拼皮肤缓存路径（路径穿越），{@code name} 会被塞进启动命令行的
     * {@code --username}（曾能让 {@code ${auth_access_token}} 二次展开）。这里从源头掐断：
     * 不合规的角色直接拒登，不给上层任何「先存下来再说」的机会。
     */
    private static StoredSession sessionFor(JsonNode profile, String clientToken,
                                            String accessToken, String endpoint) {
        String id = profile.path("id").asText("");
        String name = profile.path("name").asText("");
        String bareId = id.replace("-", "");
        if (!bareId.matches("[0-9a-fA-F]{32}")) {
            throw new AuthException(com.brisklauncher.mcl.i18n.I18n.t(
                    "account.yggdrasil.bad_profile_id"));
        }
        if (!name.matches("[A-Za-z0-9_]{1,16}")) {
            throw new AuthException(com.brisklauncher.mcl.i18n.I18n.t(
                    "account.yggdrasil.bad_profile_name", name));
        }
        return new StoredSession(
                AccountType.THIRD_PARTY,
                id,
                name,
                clientToken.toCharArray(),
                accessToken.toCharArray(),
                System.currentTimeMillis() + 24L * 3600 * 1000,
                endpoint);
    }

    @Override
    public LoginResult refresh(StoredSession session) {
        try {
            String endpoint = session.endpoint();
            if (endpoint == null) {
                return LoginResult.fail("缺少认证服务器地址");
            }
            Map<String, Object> payload = Map.of(
                    "accessToken", new String(session.accessToken()),
                    "clientToken", new String(session.refreshToken()));
            JsonNode node = postJson(endpoint + "/authserver/refresh", payload, null);
            String newAccess = node.path("accessToken").asText("");
            if (newAccess.isEmpty()) {
                return LoginResult.fail("刷新失败");
            }
            StoredSession refreshed = new StoredSession(
                    AccountType.THIRD_PARTY, session.uuid(), session.name(),
                    session.refreshToken(), newAccess.toCharArray(),
                    System.currentTimeMillis() + 24L * 3600 * 1000, endpoint);
            SafeLog.tokenRefresh(AccountType.THIRD_PARTY);
            return LoginResult.ok(refreshed);
        } catch (Exception e) {
            return LoginResult.fail("刷新失败");
        }
    }

    @Override
    public boolean validate(StoredSession session) {
        try {
            String endpoint = session.endpoint();
            if (endpoint == null) {
                return false;
            }
            Map<String, Object> payload = Map.of(
                    "accessToken", new String(session.accessToken()),
                    "clientToken", new String(session.refreshToken()));
            JsonNode node = postJson(endpoint + "/authserver/validate", payload, null);
            return node.isBoolean() && node.asBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public GameSession toGameSession(StoredSession session) {
        return new GameSession(
                session.name(), session.uuid(),
                session.accessToken(), "mojang", "{}", session.endpoint());
    }

    /** 支持 "ALI" 简写、裸域名，以及误粘贴 /authserver 后缀的情况。 */
    private static String normalizeEndpoint(java.net.URI raw) {
        if (raw == null) {
            throw new AuthException("未指定认证服务器");
        }
        String trimmed = raw.toString().trim();
        if ("ALI".equalsIgnoreCase(trimmed)) {
            return "https://littleskin.cn/api/yggdrasil";
        }
        if (!trimmed.startsWith("http")) {
            trimmed = "https://" + trimmed;
        }
        // 安全审计 M2：凭据只能走 https（本机回环除外），不然密码会明文上网
        java.net.URI check;
        try {
            check = java.net.URI.create(trimmed);
        } catch (IllegalArgumentException bad) {
            throw new AuthException("认证服务器地址无效：" + trimmed);
        }
        String scheme = check.getScheme() == null ? "" : check.getScheme().toLowerCase(java.util.Locale.ROOT);
        String host = check.getHost() == null ? "" : check.getHost();
        boolean loopback = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)
                || "::1".equals(host) || "[::1]".equals(host);
        // 安全审计 M-06：回环 http 原本无条件放行，成了口令钓鱼入口
        // （诱导用户填 http://localhost:8080，本机任意进程就能拿到明文口令）。
        // 现在只有开发者显式打开 -Dmcl.dev.authloopback=true 才允许。
        boolean allowLoopback = Boolean.getBoolean("mcl.dev.authloopback");
        if (!"https".equals(scheme) && !(loopback && "http".equals(scheme) && allowLoopback)) {
            if (loopback && "http".equals(scheme)) {
                throw new AuthException("本机回环的 http 认证地址仅在开发者模式下允许（"
                        + "-Dmcl.dev.authloopback=true），正式使用请填 https 地址");
            }
            throw new AuthException("认证服务器必须使用 https");
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.endsWith("/authserver/authenticate")) {
            trimmed = trimmed.substring(0, trimmed.length() - "/authserver/authenticate".length());
        } else if (trimmed.endsWith("/authserver")) {
            trimmed = trimmed.substring(0, trimmed.length() - "/authserver".length());
        }
        return trimmed;
    }

    private JsonNode postJson(String url, Object payload, String bearer) throws Exception {
        String body = mapper.writeValueAsString(payload);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .header("User-Agent", "BriskLauncher/1.1.2")
                .timeout(java.time.Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        HttpResponse<String> resp = Http.authClient().send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String text = resp.body() == null ? "" : resp.body();
        JsonNode node;
        try {
            node = text.isBlank() ? mapper.createObjectNode() : mapper.readTree(text);
        } catch (Exception e) {
            throw new AuthException("认证服务器返回了非 JSON 内容（HTTP " + resp.statusCode()
                    + "），请检查地址是否正确");
        }
        if (resp.statusCode() != 200) {
            String error = firstText(node, "errorMessage", "error", "message");
            throw new AuthException(error.isEmpty()
                    ? "认证服务器返回 HTTP " + resp.statusCode()
                    : error);
        }
        return node;
    }

    private static String firstText(JsonNode node, String... keys) {
        for (String key : keys) {
            String value = node.path(key).asText("");
            if (!value.isBlank()) {
                return value.length() > 200 ? value.substring(0, 200) + "…" : value;
            }
        }
        return "";
    }
}