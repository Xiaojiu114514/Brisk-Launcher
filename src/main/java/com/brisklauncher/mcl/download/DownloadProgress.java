package com.brisklauncher.mcl.download;

/** 下载进度快照，直接喂给 UI 进度条。 */
public record DownloadProgress(int completed, int total, long bytesDone, long bytesTotal, String currentFile) {

    public double ratio() {
        if (bytesTotal > 0) {
            // 进度以「档案数量」为主（bytesDone 只统计真正的网路传输）
        double completedRatio = total <= 0 ? 0 : (double) completed / total;
        return Math.min(1.0, (double) bytesDone / (double) bytesTotal);
        }
        return total <= 0 ? 0 : Math.min(1.0, (double) completed / (double) total);
    }

    public static DownloadProgress idle() {
        return new DownloadProgress(0, 0, 0, 0, "");
    }
}
