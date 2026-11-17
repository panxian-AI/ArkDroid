package ai.panxian.arkdroid;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 镜像测速 + 断点续传的下载内核。
 *
 * <p>刻意不依赖 Android：可以直接在桌面上用 javac 编出来跑测试
 * （见 {@code tools/selftest_downloader.sh}），手机上的行为才可信。
 *
 * <p>策略：把同一个文件的多个镜像并发探一次（各读 256 KB 就断开），按实测速度排序，
 * 从最快的开始下；中途断了就续传，续不上就换下一个镜像。
 */
final class Downloader {

    /** 探速时每个镜像最多读这么多字节。四个镜像并发也就 1 MB 流量。 */
    static final int PROBE_BYTES = 256 * 1024;

    /** 探速的时间上限：慢镜像不该拖着用户等。 */
    static final long PROBE_BUDGET_MS = 3500L;

    private static final int BUF = 64 * 1024;

    interface Progress {
        /** bps < 0 表示这轮还没测出速度。 */
        void onProgress(long done, long total, long bps);

        void onNote(String note);
    }

    static final class Outcome {
        final File file;
        final String mirror;
        final long bytes;

        Outcome(File file, String mirror, long bytes) {
            this.file = file;
            this.mirror = mirror;
            this.bytes = bytes;
        }
    }

    /** 单个镜像的测速结果。 */
    static final class Ranked {
        final String url;
        final long bps;
        final int order;

        Ranked(String url, long bps, int order) {
            this.url = url;
            this.bps = bps;
            this.order = order;
        }
    }

    private Downloader() {
    }

    // ------------------------------------------------------------------ 测速

    /**
     * 并发测速，返回按速度从快到慢排好的镜像。
     *
     * <p>没测出速度（连不上、404、超时）的镜像排在最后——仍然保留，因为测速失败
     * 有时候只是那个时刻的抖动，真下的时候值得再试一次。
     */
    static List<Ranked> rank(List<String> mirrors) {
        List<Ranked> out = new ArrayList<>();
        if (mirrors == null || mirrors.isEmpty()) {
            return out;
        }
        int workers = Math.min(4, mirrors.size());
        ExecutorService pool = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r, "dl-probe");
            t.setDaemon(true);
            return t;
        });
        List<Future<Long>> futures = new ArrayList<>();
        for (final String url : mirrors) {
            futures.add(pool.submit(new Callable<Long>() {
                @Override
                public Long call() {
                    return probe(url);
                }
            }));
        }
        long deadline = System.currentTimeMillis() + PROBE_BUDGET_MS;
        for (int i = 0; i < futures.size(); i++) {
            long bps = -1;
            try {
                long left = Math.max(200, deadline - System.currentTimeMillis());
                bps = futures.get(i).get(left, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                futures.get(i).cancel(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                // probe 内部已经吞掉异常，这里兜底
            }
            out.add(new Ranked(mirrors.get(i), bps, i));
        }
        pool.shutdownNow();
        Collections.sort(out, (a, b) -> Long.compare(b.bps, a.bps));
        return out;
    }

    /** 读一小段测速度；返回字节/秒，失败返回 -1。 */
    private static long probe(String url) {
        HttpURLConnection conn = null;
        try {
            conn = open(url, PROBE_BYTES - 1, 6000, 6000);
            if (conn.getResponseCode() / 100 != 2) {
                return -1;
            }
            long start = System.nanoTime();
            long got = 0;
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while (got < PROBE_BYTES && (n = in.read(buf, 0, (int) Math.min(buf.length, PROBE_BYTES - got))) > 0) {
                    got += n;
                }
            }
            long elapsed = Math.max(1, (System.nanoTime() - start) / 1_000_000L);
            if (got <= 0) {
                return -1;
            }
            return got * 1000L / elapsed;
        } catch (IOException e) {
            return -1;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ------------------------------------------------------------------ 下载

    /**
     * 下到 target；已存在的部分会被续传。全部镜像都失败才抛异常。
     *
     * @param order   {@link #rank} 的结果，通常直接用
     * @param expectSha 非空则校验，不匹配就换镜像重来
     */
    static Outcome fetch(List<Ranked> order, File target, String expectSha,
                         Progress progress, AtomicBoolean cancel) throws IOException {
        if (order.isEmpty()) {
            throw new IOException("没有可用镜像");
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("建不了目录：" + parent);
        }

        IOException last = null;
        for (int i = 0; i < order.size(); i++) {
            Ranked r = order.get(i);
            if (cancel.get()) {
                throw new IOException("已取消");
            }
            try {
                long bytes = transfer(r.url, target, progress, cancel);
                if (expectSha != null && !expectSha.isEmpty()) {
                    String got = sha256(target);
                    if (!expectSha.equalsIgnoreCase(got)) {
                        // 镜像给了旧版本或坏包：删掉，别拿它去安装
                        //noinspection ResultOfMethodCallIgnored
                        target.delete();
                        throw new IOException("校验和不符（" + got.substring(0, 8) + "…）");
                    }
                }
                return new Outcome(target, r.url, bytes);
            } catch (IOException e) {
                last = e;
                if (cancel.get()) {
                    throw e;
                }
                progress.onNote(host(r.url) + " 失败：" + shortMsg(e) + "，换下一个镜像");
            }
        }
        throw new IOException("所有镜像都试过了，最后一个错误：" + shortMsg(last), last);
    }

    private static long transfer(String url, File target, Progress progress, AtomicBoolean cancel)
            throws IOException {
        long have = target.exists() ? target.length() : 0;
        HttpURLConnection conn = open(url, -1, 15000, 20000);
        if (have > 0) {
            conn.setRequestProperty("Range", "bytes=" + have + "-");
        }
        int code = conn.getResponseCode();
        if (code == 416) {
            // 服务器说这个范围不合法，多半是本地文件已经完整
            conn.disconnect();
            return have;
        }
        if (code / 100 != 2) {
            conn.disconnect();
            throw new IOException("HTTP " + code);
        }
        if (have > 0 && code == 200) {
            // 服务器不支持断点续传：从头来，别把两段拼在一起
            have = 0;
            //noinspection ResultOfMethodCallIgnored
            target.delete();
        }

        long total = conn.getContentLengthLong();
        if (total > 0) {
            total += have;
        } else {
            total = -1;
        }

        long done = have;
        long lastReport = 0;
        long winStart = System.nanoTime();
        long winBytes = 0;
        long bps = -1;

        try (InputStream in = new BufferedInputStream(conn.getInputStream(), BUF);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(target, true), BUF)) {
            byte[] buf = new byte[BUF];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancel.get()) {
                    throw new IOException("已取消");
                }
                out.write(buf, 0, n);
                done += n;
                winBytes += n;

                long now = System.nanoTime();
                if (now - winStart >= 400_000_000L) {
                    bps = winBytes * 1_000_000_000L / Math.max(1, now - winStart);
                    winStart = now;
                    winBytes = 0;
                }
                if (System.currentTimeMillis() - lastReport >= 250) {
                    lastReport = System.currentTimeMillis();
                    progress.onProgress(done, total, bps);
                }
            }
        } finally {
            conn.disconnect();
        }

        if (total > 0 && done < total) {
            // 连接被中途掐断：保留已下部分，交给下一个镜像续
            throw new IOException("只下到 " + done + "/" + total + " 字节");
        }
        progress.onProgress(done, total < 0 ? done : total, bps);
        return done;
    }

    private static HttpURLConnection open(String url, long rangeEnd, int connectMs, int readMs)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(connectMs);
        conn.setReadTimeout(readMs);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "ArkDroid/0.2 (+https://github.com/panxian-AI/ArkDroid)");
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Accept-Encoding", "identity");
        if (rangeEnd >= 0) {
            conn.setRequestProperty("Range", "bytes=0-" + rangeEnd);
        }
        return conn;
    }

    // ------------------------------------------------------------------ 工具

    static String sha256(File file) throws IOException {
        try (InputStream in = new BufferedInputStream(new java.io.FileInputStream(file), BUF)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[BUF];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return hex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("没有 SHA-256", e);
        }
    }

    static String hex(byte[] raw) {
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    static String humanSize(long bytes) {
        if (bytes < 0) {
            return "未知";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(java.util.Locale.US, "%.0f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(java.util.Locale.US, "%.1f MB", mb);
        }
        return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0);
    }

    static String host(String url) {
        try {
            String host = new URL(url).getHost();
            return host == null ? url : host;
        } catch (Exception e) {
            return url;
        }
    }

    private static String shortMsg(Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }

    /** 给续传用的：把一个残缺文件截断到指定长度（测试与自愈用）。 */
    static void truncate(File file, long length) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.setLength(length);
        }
    }
}
