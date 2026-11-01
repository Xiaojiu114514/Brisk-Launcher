package com.brisklauncher.mcl.download;

import com.brisklauncher.mcl.setting.LauncherSettings;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 下载源解析：把官方 URL 展开成“首选源 → 备用源 → 官方”的候选列表。
 *
 * <p>BMCLAPI 的原理是域名替换（见 <a href="https://bmclapidoc.bangbang93.com/">BMCLAPI 文档</a>）：
 * <pre>
 * launchermeta.mojang.com / piston-meta.mojang.com / piston-data.mojang.com / launcher.mojang.com
 *        → bmclapi2.bangbang93.com（路径不变）
 * libraries.minecraft.net/xxx        → bmclapi2.bangbang93.com/maven/xxx
 * resources.download.minecraft.net/x → bmclapi2.bangbang93.com/assets/x
 * </pre>
 * 这样同一份版本 JSON 不需要改写，只在真正发请求时换域名。
 */
public final class MirrorResolver {

    public static final String BMCLAPI_HOST = "bmclapi2.bangbang93.com";
    /** 備用網域：同源鏡像，專治 bmclapi2 的 DNS 抽風。 */
    public static final String BMCLAPI_ALT_HOST = "bmclapi.bangbang93.com";

    private final LauncherSettings settings;

    public MirrorResolver(LauncherSettings settings) {
        this.settings = settings == null ? LauncherSettings.defaults() : settings;
    }

    /**
     * 元资料（版本清单 / 版本 JSON）候选来源。
     *
     * <p><b>为什么不是「官方第一」</b>：第 136 轮曾为了 H-04 把元资料改成官方优先，
     * 结果在国内**官方 launchermeta / piston-meta 常常直接连不上**：
     * 每次取版本清单都要先等官方逾时，玩家看到的是「版本清单空白」与「启动明显变慢」。
     * 取版本 JSON 的 sha1 依赖同一份 JSON，属于审计 v2 的 B 类（镜像方可控 = 已能改下发内容，
     * 无法承诺），所以这里回到「镜像优先、官方保底」，与下载热路径一致。
     */
    public List<URI> metadataCandidates(URI official) {
        return resolve(official);
    }

    /**
     * @param official 官方地址（Mojang / Fabric maven / Modrinth 等都行）
     * @return 去重后的候选 URI 列表，至少包含原地址
     */
    public List<URI> resolve(URI official) {
        if (official == null) {
            return List.of();
        }
        Set<URI> result = new LinkedHashSet<>();
        switch (settings.source()) {
            case AUTO -> {
                // 下载热路径：只用快取排序，没有就用预设顺序立刻返回；
                // 真正的探测交给背景执行绪（下次下载才生效），不再卡住每一个档案。
                java.util.List<URI> candidates = new java.util.ArrayList<>();
                URI custom = mapCustom(official);
                URI bmcl = mapBmcl(official);
                URI bmclAlt = mapBmclAlt(official);
                if (custom != null) {
                    candidates.add(custom);
                }
                if (bmcl != null) {
                    candidates.add(bmcl);
                }
                if (bmclAlt != null) {
                    candidates.add(bmclAlt);
                }
                candidates.add(official);
                java.util.List<String> hosts = new java.util.ArrayList<>();
                for (URI uri : candidates) {
                    hosts.add(com.brisklauncher.mcl.download.NetSpeed.hostOf(uri));
                }
                java.util.List<String> ranked = com.brisklauncher.mcl.download.NetSpeed.cachedRank(hosts);
                if (ranked == null) {
                    // 还没测过：镜像优先、官方保底。
                    // 以前是「官方优先」，在国内等于每次都要先等官方超时（玩家感觉启动变慢、
                    // 版本列表偶尔直接空白）；镜像本来就是为这种情况准备的，先试它才是对的。
                    com.brisklauncher.mcl.download.NetSpeed.warmUpAsync(hosts);
                    if (custom != null) {
                        result.add(custom);
                    }
                    if (bmcl != null) {
                        result.add(bmcl);
                    }
                    if (bmclAlt != null) {
                        result.add(bmclAlt);
                    }
                    result.add(official);
                } else {
                    for (String host : ranked) {
                        for (URI uri : candidates) {
                            if (host.equals(com.brisklauncher.mcl.download.NetSpeed.hostOf(uri))) {
                                result.add(uri);
                            }
                        }
                    }
                }
            }
            case OFFICIAL -> {
                result.add(official);
                URI bmcl = mapBmcl(official);
                if (bmcl != null) {
                    result.add(bmcl);
                }
            }
        }
        return new ArrayList<>(result);
    }

    /** 便于调用方直接给字符串地址。 */
    public List<URI> resolve(String official) {
        try {
            return resolve(URI.create(official));
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }

    /**
     * 给 Forge / NeoForge 安装器用的「maven 镜像根地址」。
     *
     * <p>官方安装器下载依赖库时**没有连线超时**，撞到被墙的 {@code maven.neoforged.net} 会永远卡住；
     * 把 install profile 的 {@code mirror} 指到 BMCLAPI 的 {@code /maven/} 就能绕过
     * （PCL 用的是同一招）。使用者选「官方」时回空字串（不覆写 mirror）。
     */
    public String installerMavenMirror() {
        return switch (settings.source()) {
            case AUTO, BMCLAPI -> "https://" + BMCLAPI_HOST + "/maven/";
            case OFFICIAL, CUSTOM -> "";
        };
    }

    // ---------- BMCLAPI 域名替换 ----------

    static URI mapBmcl(URI official) {
        return mapBmclHost(official, BMCLAPI_HOST);
    }

    /**
     * BMCLAPI 的备用域名（{@code bmclapi.bangbang93.com}）。
     *
     * <p>为什么要两个：{@code bmclapi2} 在国内会偶发解析到连不上的节点（实测 NXDOMAIN），
     * 只有一个域名时一次 DNS 抽风就让整条下载链失败。两个域名同源，
     * 多一个候选纯粹是提高可用性。
     */
    static URI mapBmclAlt(URI official) {
        return mapBmclHost(official, BMCLAPI_ALT_HOST);
    }

    private static URI mapBmclHost(URI official, String mirrorHost) {
        String host = official.getHost();
        if (host == null) {
            return null;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        String path = official.getRawPath() == null ? "" : official.getRawPath();
        String query = official.getRawQuery() == null ? "" : "?" + official.getRawQuery();
        try {
            switch (lower) {
                case "launchermeta.mojang.com":
                case "piston-meta.mojang.com":
                case "piston-data.mojang.com":
                case "launcher.mojang.com":
                case "resources.download.minecraft.net":
                case "libraries.minecraft.net":
                case "files.minecraftforge.net":
                case "maven.minecraftforge.net":
                case "maven.neoforged.net":
                    return bmclUri(path, query, lower, mirrorHost);
                default:
                    return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static URI bmclUri(String path, String query, String host, String mirrorHost) {
        String prefix = "";
        if ("libraries.minecraft.net".equals(host)
                || "maven.minecraftforge.net".equals(host)
                || "maven.neoforged.net".equals(host)) {
            prefix = "/maven";
        } else if ("resources.download.minecraft.net".equals(host)) {
            prefix = "/assets";
        }
        // BMCLAPI 的 /maven/ 对应「maven 仓库根」：官方的 /releases、/snapshots 必须拿掉，
        // 否则 /maven/releases/net/neoforged/... 一律 404（实测；NeoForge 的 URL 全都带 /releases）。
        if ("maven.neoforged.net".equals(host) || "maven.minecraftforge.net".equals(host)) {
            path = stripMavenScope(path);
        }
        return URI.create("https://" + mirrorHost + prefix + path + query);
    }

    /** 去掉 maven 倉庫的 scope 前綴（{@code /releases}、{@code /snapshots}）。 */
    private static String stripMavenScope(String path) {
        for (String scope : new String[]{"/releases", "/snapshots"}) {
            if (path.equals(scope)) {
                return "/";
            }
            if (path.startsWith(scope + "/")) {
                return path.substring(scope.length());
            }
        }
        return path;
    }

    /**
     * 给「预先下载安装器依赖库」用的候选清单：镜像优先、官方备援。
     *
     * <p>一般下载走 {@link #resolve} 的 AUTO 排序（有快取就照测速结果，没快取常常官方优先），
     * 但安装器依赖库的官方来源在国内**根本连不上**，先撞官方只是白等，
     * 所以这里固定「镜像 → 官方」。
     */
    public List<URI> mirrorFirst(String official) {
        java.util.LinkedHashSet<URI> result = new java.util.LinkedHashSet<>();
        try {
            URI uri = URI.create(official);
            if (settings.source() != LauncherSettings.Source.OFFICIAL) {
                URI bmcl = mapBmcl(uri);
                if (bmcl != null) {
                    result.add(bmcl);
                }
            }
            result.add(uri);
        } catch (Exception ignored) {
            // 位址有问题就回空清单，呼叫端自己处理
        }
        return new ArrayList<>(result);
    }

    // ---------- 自定义镜像 ----------

    URI mapCustom(URI official) {
        String base = settings.customMirror();
        if (base == null || base.isBlank()) {
            return null;
        }
        base = base.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            base = "https://" + base;
        }
        try {
            String path = official.getRawPath() == null ? "" : official.getRawPath();
            String query = official.getRawQuery() == null ? "" : "?" + official.getRawQuery();
            return URI.create(base + path + query);
        } catch (Exception e) {
            return null;
        }
    }
}
