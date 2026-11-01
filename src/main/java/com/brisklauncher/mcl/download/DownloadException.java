package com.brisklauncher.mcl.download;

/** 批量下载失败：保留每一条失败原因，UI 只展示前几条。 */
public class DownloadException extends RuntimeException {

    private final int failedCount;

    public DownloadException(String message, int failedCount) {
        super(message);
        this.failedCount = failedCount;
    }

    public int failedCount() {
        return failedCount;
    }
}
