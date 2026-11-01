package com.brisklauncher.mcl.modpack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.brisklauncher.mcl.net.Http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * mrpack 读取与路径预检。
 *
 * <p>安全红线：所有路径（index 里的 files 与 overrides 内的条目）必须在
 * 解压/下载之前逐条校验，任何一条失败整包中止。
 */
public final class MrpackReader {

    private static final String INDEX_ENTRY = "modrinth.index.json";
    private static final String OVERRIDES = "overrides/";
    private static final String CLIENT_OVERRIDES = "client-overrides/";
    private static final ObjectMapper MAPPER = Http.mapper();

    public ModpackIndex read(Path mrpack) throws IOException {
        try (ZipFile zip = new ZipFile(mrpack.toFile())) {
            ZipEntry index = zip.getEntry(INDEX_ENTRY);
            if (index == null) {
                throw new IOException("不是有效的 mrpack：缺少 modrinth.index.json");
            }
            // 安全审计 v2 A-01：索引是一个 zip 条目，1KB 的 deflate 条目能展开成数百 MB JSON。
            // 先看声明大小，再限制实际读取的字节数，双保险。
            if (index.getSize() > com.brisklauncher.mcl.security.InputBudget.MAX_INDEX_JSON) {
                throw new IOException("mrpack 索引过大（"
                        + com.brisklauncher.mcl.security.InputBudget.human(index.getSize())
                        + "），已拒绝导入");
            }
            ModpackIndex parsed;
            try (InputStream raw = zip.getInputStream(index);
                 java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream()) {
                com.brisklauncher.mcl.security.InputBudget.copyBounded(raw, buffer,
                        com.brisklauncher.mcl.security.InputBudget.MAX_INDEX_JSON);
                parsed = ModpackIndex.parse(MAPPER.readTree(buffer.toByteArray()));
            }
            preflight(parsed, zip);
            return parsed;
        }
    }

    /** 预检：index.files 的 path + overrides 内的全部 entry（含总量/条目数上限）。 */
    private void preflight(ModpackIndex index, ZipFile zip) throws IOException {
        if (index.mcVersion().isBlank()) {
            throw new IOException("mrpack 缺少 minecraft 依赖，无法确定游戏版本");
        }
        for (ModpackIndex.FileEntry file : index.files()) {
            try {
                MrpackPathValidator.validate(file.path());
            } catch (SecurityException e) {
                throw new IOException("mrpack 路径校验失败，已中止导入：" + e.getMessage(), e);
            }
        }
        Enumeration<? extends ZipEntry> entries = zip.entries();
        int count = 0;
        long declaredTotal = 0;
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            // 安全审计 v2 A-10：条目数 / 解压总量 / 备用数据流（冒号）都要挡
            if (++count > com.brisklauncher.mcl.security.InputBudget.MAX_ARCHIVE_ENTRIES) {
                throw new IOException("mrpack 条目过多（> "
                        + com.brisklauncher.mcl.security.InputBudget.MAX_ARCHIVE_ENTRIES + "），已拒绝导入");
            }
            try {
                com.brisklauncher.mcl.security.InputBudget.safeArchiveEntryName(name);
            } catch (SecurityException e) {
                throw new IOException(e.getMessage(), e);
            }
            if (entry.isDirectory() || name.equals(INDEX_ENTRY)) {
                continue;
            }
            if (entry.getSize() > 0) {
                declaredTotal += entry.getSize();
                if (declaredTotal > com.brisklauncher.mcl.security.InputBudget.MAX_ARCHIVE_TOTAL) {
                    throw new IOException("mrpack 解压总量过大（> "
                            + com.brisklauncher.mcl.security.InputBudget.human(
                                    com.brisklauncher.mcl.security.InputBudget.MAX_ARCHIVE_TOTAL)
                            + "），已拒绝导入");
                }
            }
            String relative = stripOverridePrefix(name);
            if (relative == null) {
                // mrpack 规范里其它顶层文件（如 README）不参与安装
                continue;
            }
            try {
                MrpackPathValidator.validate(relative);
            } catch (SecurityException e) {
                throw new IOException("mrpack overrides 路径校验失败，已中止导入：" + e.getMessage(), e);
            }
        }
    }

    /** 返回去掉 overrides/ 或 client-overrides/ 前缀的相对路径；不是覆盖层返回 null。 */
    static String stripOverridePrefix(String entryName) {
        String normalized = entryName.replace('\\', '/');
        if (normalized.startsWith(OVERRIDES)) {
            return normalized.substring(OVERRIDES.length());
        }
        if (normalized.startsWith(CLIENT_OVERRIDES)) {
            return normalized.substring(CLIENT_OVERRIDES.length());
        }
        return null;
    }

    public List<String> listOverrideEntries(Path mrpack) throws IOException {
        List<String> result = new ArrayList<>();
        try (ZipFile zip = new ZipFile(mrpack.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String relative = stripOverridePrefix(entry.getName());
                if (relative != null && !relative.isBlank()) {
                    result.add(relative);
                }
            }
        }
        return result;
    }

    /** CurseForge 整合包的基本信息（manifest.json），用于提示用户改用 mrpack。 */
    public static boolean looksLikeCurseForgeZip(Path zipFile) {
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            return zip.getEntry("manifest.json") != null;
        } catch (IOException e) {
            return false;
        }
    }
}
