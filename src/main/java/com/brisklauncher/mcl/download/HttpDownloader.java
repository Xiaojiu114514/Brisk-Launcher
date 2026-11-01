package com.brisklauncher.mcl.download;

import com.brisklauncher.mcl.net.Http;
import com.brisklauncher.mcl.security.SafeLog;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 下载引擎：临时文件 → 校验 → 原子移动，镜像顺序回退，单任务多轮重试。
 *
 * <p>与 PCL2 / HMCL 的下载逻辑对齐的几点：
 * <ul>
 *   <li><b>先校验再下载</b>：本地文件 SHA-1（或大小）已经匹配就不再产生流量。</li>
 *   <li><b>不存在“部分成功”</b>：任何文件失败都会在最后汇总抛出，由安装流程决定回滚。</li>
 *   <li><b>原子落盘</b>：只写 {@code .part} 临时文件，校验通过后才移动到目标位置，
 *       避免游戏读到半截 jar 报 {@code zip END header not found}。</li>
 *   <li><b>虚拟线程 + 信号量</b>：并发上限严格等于设置页的“并发数”，不会因为线程池排队失真。</li>
 * </ul>
 */
public final class HttpDownloader implements AutoCloseable {

    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore permits;
    private final int retries;
    private final Duration timeout;

    public HttpDownloader(int concurrency, int retries, int timeoutSeconds) {
        this.permits = new Semaphore(Math.max(1, concurrency));
        this.retries = Math.max(0, retries);
        this.timeout = Duration.ofSeconds(Math.max(5, timeoutSeconds));
    }

    /**
     * 批量下载。已满足条件的任务会被跳过（但仍计入进度）。
     *
     * @throws DownloadException 有任意文件最终失败时抛出
     */
    public List<DownloadTask> downloadAll(Collection<DownloadTask> tasks, Consumer<DownloadProgress> progress) {
        // 同一目标地址只保留一条，避免并发写同一个文件
        Map<Path, DownloadTask> deduped = new LinkedHashMap<>();
        for (DownloadTask task : tasks) {
            deduped.putIfAbsent(task.destination().toAbsolutePath().normalize(), task);
        }
        List<DownloadTask> list = new ArrayList<>(deduped.values());
        if (list.isEmpty()) {
            return list;
        }

        int total = list.size();
        AtomicInteger completed = new AtomicInteger();
        AtomicLong bytesDone = new AtomicLong();
        AtomicLong bytesTotal = new AtomicLong();
        AtomicLong lastReport = new AtomicLong();
        AtomicReference<String> currentFile = new AtomicReference<>("");
        List<String> failures = java.util.Collections.synchronizedList(new ArrayList<>());

        for (DownloadTask task : list) {
            if (task.size() > 0) {
                bytesTotal.addAndGet(task.size());
            }
        }

        Consumer<DownloadProgress> safeProgress = progress == null ? p -> { } : progress;
        Runnable report = () -> safeProgress.accept(new DownloadProgress(
                completed.get(), total, bytesDone.get(), bytesTotal.get(), currentFile.get()));

        Runnable reportThrottled = () -> {
            long now = System.currentTimeMillis();
            long last = lastReport.get();
            if (now - last >= 90 && lastReport.compareAndSet(last, now)) {
                report.run();
            }
        };

        report.run();

        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (DownloadTask task : list) {
            futures.add(pool.submit(() -> {
                try {
                    permits.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failures.add(task.fileName() + "：下载被中断");
                    return;
                }
                try {
                    currentFile.set(task.fileName());
                    if (task.alreadySatisfied()) {
                        // 已存在 → 算完成，但**不计入 bytesDone**（那些位元组没经过网路，
                        // 计进去会让速度虚高：实测 6.6MB/s 而工作管理员只有 1.2Mbps）
                        completed.incrementAndGet();
                        report.run();
                        return;
                    }
                    download(task, bytesDone, reportThrottled);
                    completed.incrementAndGet();
                    report.run();
                } catch (Exception e) {
                    String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    SafeLog.warn("下载失败 [" + task.fileName() + "]：" + reason);
                    failures.add(task.fileName() + "：" + reason);
                } finally {
                    permits.release();
                }
            }));
        }

        for (var future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // 取消所有还在跑的下载线程（虚拟线程会被 interrupt，fetch 里会检查）
                futures.forEach(item -> item.cancel(true));
                failures.add("下载已取消");
                break;
            } catch (Exception e) {
                failures.add("下载线程异常：" + Http.describe(e));
            }
        }

        report.run();

        if (!failures.isEmpty()) {
            StringBuilder message = new StringBuilder("有 " + failures.size() + " 个文件下载失败");
            int shown = 0;
            for (String failure : failures) {
                if (shown++ >= 3) {
                    break;
                }
                message.append("\n · ").append(failure);
            }
            if (failures.size() > 3) {
                message.append("\n · …");
            }
            throw new DownloadException(message.toString(), failures.size());
        }
        return list;
    }

    /** 单文件下载：下载一个文件并等待完成，失败直接抛出。 */
    public void downloadOne(DownloadTask task) {
        try {
            if (task.alreadySatisfied()) {
                return;
            }
            download(task, new AtomicLong(), () -> { });
        } catch (IOException e) {
            throw new DownloadException(e.getMessage() == null ? "下载失败" : e.getMessage(), 1);
        }
    }

    private void download(DownloadTask task, AtomicLong bytesDone, Runnable onByteProgress) throws IOException {
        Files.createDirectories(task.destination().getParent());
        Path temp = Files.createTempFile(task.destination().getParent(), ".mcl-", ".part");
        IOException last = null;
        try {
            List<URI> mirrors = HostHealth.sorted(task.mirrors());
            for (int attempt = 0; attempt <= retries; attempt++) {
                for (URI mirror : mirrors) {
                    try {
                        fetch(mirror, temp, bytesDone, onByteProgress);
                        verify(temp, task);
                        move(temp, task.destination());
                        HostHealth.success(mirror);
                        return;
                    } catch (IOException e) {
                        last = e;
                        HostHealth.failure(mirror);
                        if (Thread.currentThread().isInterrupted()) {
                            throw new IOException("下载被取消", e);
                        }
                        if (Files.exists(temp)) {
                            Files.deleteIfExists(temp);
                        }
                    }
                }
                if (attempt < retries) {
                    try {
                        Thread.sleep(300L * (attempt + 1));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("下载被取消", e);
                    }
                }
            }
            throw last == null ? new IOException("没有可用下载地址") : last;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void fetch(URI uri, Path temp, AtomicLong bytesDone, Runnable onByteProgress) throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("User-Agent", "BriskLauncher/1.0.0")
                    .header("Accept", "*/*")
                    .timeout(Duration.ofMinutes(3))
                    .GET()
                    .build();
            HttpResponse<InputStream> response;
            try {
                response = Http.client().send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                if (!Http.isSslProblem(e)) {
                    throw e;
                }
                SafeLog.warn("TLS 握手失败，改用 TLS 1.2 重试（" + Http.hostOf(uri) + "）");
                response = Http.tls12Client().send(request, HttpResponse.BodyHandlers.ofInputStream());
            }
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + "（" + Http.hostOf(uri) + "）");
            }
            try (InputStream in = response.body();
                 java.io.OutputStream out = Files.newOutputStream(temp,
                         java.nio.file.StandardOpenOption.CREATE,
                         java.nio.file.StandardOpenOption.APPEND)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                long total = 0;
                while ((read = in.read(buffer)) > 0) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IOException("下载被取消");
                    }
                    out.write(buffer, 0, read);
                    total += read;
                    bytesDone.addAndGet(read);
                    onByteProgress.run();
                }
                if (total == 0) {
                    throw new IOException("响应为空（" + Http.hostOf(uri) + "）");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("下载被中断", e);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(Http.describe(e), e);
        }
    }

    private static void verify(Path file, DownloadTask task) throws IOException {
        long size = Files.size(file);
        if (task.size() > 0 && size != task.size()) {
            throw new IOException("大小不符（期望 " + task.size() + "，实际 " + size + "）");
        }
        if (task.sha1() != null) {
            String actual = HashUtils.sha1(file);
            if (!task.sha1().equalsIgnoreCase(actual)) {
                throw new IOException("SHA-1 校验失败");
            }
        }
    }

    private static void move(Path temp, Path destination) throws IOException {
        try {
            Files.move(temp, destination,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
