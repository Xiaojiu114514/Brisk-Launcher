package com.brisklauncher.mcl.util;

import com.brisklauncher.mcl.security.SafeLog;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 单实例锁：同一个资料目录只允许一个启动器写入（开发方案 8.1 的 {@code launcher.lock}）。
 *
 * <p>用作业系统档案锁（JVM 结束会自动释放），不用 PID 猜测，所以崩溃后不会留下死锁。
 * 拿不到锁不是致命错误：只提示使用者，不删资料、不改设定。
 */
public final class LauncherLock implements AutoCloseable {

    private final Path file;
    private final FileChannel channel;
    private final FileLock lock;

    private LauncherLock(Path file, FileChannel channel, FileLock lock) {
        this.file = file;
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * 尝试取得锁。
     *
     * @return 取得成功回传锁物件；已被别的实例持有回传 {@code null}；
     *         档案系统不支援锁时回传一个空壳锁（不阻止启动）
     */
    public static LauncherLock acquire(Path directory) {
        Path file = directory.resolve("launcher.lock");
        FileChannel channel = null;
        try {
            Files.createDirectories(directory);
            channel = FileChannel.open(file, StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock == null) {
                closeQuietly(channel);
                return null;
            }
            LauncherLock result = new LauncherLock(file, channel, lock);
            result.writeOwner();
            return result;
        } catch (java.nio.channels.OverlappingFileLockException e) {
            closeQuietly(channel);
            return null;
        } catch (IOException | RuntimeException e) {
            closeQuietly(channel);
            SafeLog.warn("无法建立单实例锁（不影响启动）");
            return new LauncherLock(file, null, null);
        }
    }

    private void writeOwner() {
        try {
            String text = "pid=" + ProcessHandle.current().pid() + "\n";
            channel.truncate(0);
            channel.write(ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
            channel.force(false);
        } catch (IOException ignored) {
            // 只是标记资讯，写不进去不影响锁本身
        }
    }

    @Override
    public void close() {
        try {
            if (lock != null && lock.isValid()) {
                lock.release();
            }
        } catch (IOException ignored) {
            // 退出流程尽力而为
        }
        closeQuietly(channel);
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 残留一个空档案无害，下次启动会被覆写
        }
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // 忽略
        }
    }
}
