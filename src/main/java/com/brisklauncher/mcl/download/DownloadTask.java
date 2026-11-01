package com.brisklauncher.mcl.download;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

/**
 * 单个下载任务。
 *
 * @param mirrors     候选地址，按顺序回退（第一个通常是首选下载源）
 * @param destination 目标文件（父目录会自动创建）
 * @param sha1        期望 SHA-1，可为 null（无哈希时只校验大小）
 * @param size        期望字节数，未知传 -1
 */
public record DownloadTask(List<URI> mirrors, Path destination, String sha1, long size) {

    public DownloadTask {
        mirrors = List.copyOf(mirrors);
        if (mirrors.isEmpty()) {
            throw new IllegalArgumentException("下载任务缺少地址: " + destination);
        }
        sha1 = sha1 == null || sha1.isBlank() ? null : sha1.trim();
    }

    public String fileName() {
        Path name = destination.getFileName();
        return name == null ? destination.toString() : name.toString();
    }

    /** 校验本地已有文件是否可直接使用。 */
    public boolean alreadySatisfied() {
        return HashUtils.matches(destination, sha1, size);
    }
}
