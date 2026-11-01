package com.brisklauncher.mcl.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.brisklauncher.mcl.AppPaths;
import com.brisklauncher.mcl.auth.api.AccountType;
import com.brisklauncher.mcl.security.SafeLog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 账号元数据持久化。
 *
 * <p>只存 uuid / name / type / endpoint，绝不存任何令牌（令牌在 Windows 凭据管理器）。
 * 同时保存“当前账号”，重启后仍然生效（原实现每次都退化成第一个账号）。
 */
public final class AccountStore {

    public record Account(String uuid, String name, AccountType type, String endpoint) {
    }

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    public AccountStore() {
        this(AppPaths.launcherRoot().resolve("accounts.json"));
    }

    public AccountStore(Path file) {
        this.file = file;
    }

    public List<Account> load() {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            JsonNode root = mapper.readTree(file.toFile());
            JsonNode accounts = root.path("accounts");
            List<Account> result = new ArrayList<>();
            for (JsonNode node : accounts) {
                String uuid = node.path("uuid").asText("");
                String name = node.path("name").asText("");
                if (uuid.isEmpty() || name.isEmpty()) {
                    continue;
                }
                AccountType type;
                try {
                    type = AccountType.valueOf(node.path("type").asText("OFFLINE"));
                } catch (IllegalArgumentException e) {
                    type = AccountType.OFFLINE;
                }
                String endpoint = node.hasNonNull("endpoint") ? node.get("endpoint").asText(null) : null;
                // 安全审计 H-01：endpoint 也会被拿去拼请求地址（皮肤 / 会话服务器），
                // 读回时校验一次，只接受 https（本机回环允许 http），其余丢弃该条。
                if (type == AccountType.THIRD_PARTY && !isAcceptableEndpoint(endpoint)) {
                    SafeLog.warn("账号端点不合法，已忽略该条记录");
                    continue;
                }
                result.add(new Account(uuid, name, type, endpoint));
            }
            return result;
        } catch (Exception e) {
            SafeLog.warn("读取账号列表失败");
            return List.of();
        }
    }

    public String loadCurrentUuid() {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return mapper.readTree(file.toFile()).path("current").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean save(List<Account> accounts, String currentUuid) {
        Path temp = null;
        try {
            Files.createDirectories(file.getParent());
            ObjectNode root = mapper.createObjectNode();
            root.put("version", 2);
            if (currentUuid != null) {
                root.put("current", currentUuid);
            }
            ArrayNode array = root.putArray("accounts");
            for (Account account : accounts) {
                ObjectNode node = array.addObject();
                node.put("uuid", account.uuid());
                node.put("name", account.name());
                node.put("type", account.type().name());
                if (account.endpoint() != null) {
                    node.put("endpoint", account.endpoint());
                }
            }
            // 先写临时文件再替换，避免半截 JSON 或与其它进程的写冲突
            temp = Files.createTempFile(file.getParent(), "accounts-", ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), root);
            try {
                Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.io.IOException atomicFailure) {
                // Windows 上目标文件被杀软/另一个实例短暂占用时，原子替换会失败；退回普通替换
                Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            SafeLog.warn("保存账号列表失败：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " - " + e.getMessage()));
            return false;
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 尽力而为
                }
            }
        }
    }

    /**
     * 第三方认证服务器地址是否可接受：必须 https，或本机回环的 http。
     *
     * <p>与 {@code YggdrasilAuthProvider.normalizeEndpoint} 同一口径——读回磁盘内容时再校验一次，
     * 防止被人手工改 {@code accounts.json} 塞进 {@code http://evil} 或本地探针地址。
     */
    static boolean isAcceptableEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return false;
        }
        try {
            java.net.URI uri = java.net.URI.create(endpoint.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
            boolean loopback = "127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host);
            if ("https".equals(scheme)) {
                return host.length() > 0;
            }
            return loopback && "http".equals(scheme);
        } catch (IllegalArgumentException bad) {
            return false;
        }
    }
}
