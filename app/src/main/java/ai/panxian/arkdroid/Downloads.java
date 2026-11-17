package ai.panxian.arkdroid;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一键下载：并排测几个镜像的速度，挑最快的下，断了续传，坏了换下一个。
 *
 * <p>UI 只轮询这里的静态字段，不直接回调 —— 免得 Activity 重建后回调打到旧对象上。
 */
final class Downloads {

    static volatile boolean busy;
    static volatile String label = "";
    static volatile long done;
    static volatile long total = -1;
    static volatile long bps = -1;
    static volatile String mirrorHost = "";
    static volatile String lastError = "";

    private static final AtomicBoolean CANCEL = new AtomicBoolean(false);
    private static volatile Thread worker;

    private Downloads() {
    }

    static String fractionText() {
        if (!busy) {
            return label;
        }
        StringBuilder sb = new StringBuilder(label);
        sb.append(' ').append(Downloader.humanSize(done));
        if (total > 0) {
            sb.append(" / ").append(Downloader.humanSize(total));
            sb.append(" · ").append(Math.min(100, done * 100 / Math.max(1, total))).append('%');
        }
        if (bps > 0) {
            sb.append(" · ").append(Downloader.humanSize(bps)).append("/s");
        }
        if (!mirrorHost.isEmpty()) {
            sb.append(" · ").append(mirrorHost);
        }
        return sb.toString();
    }

    static int percent() {
        if (total <= 0) {
            return busy ? 0 : 100;
        }
        return (int) Math.min(100, done * 100 / total);
    }

    static void cancel() {
        CANCEL.set(true);
        Thread t = worker;
        if (t != null) {
            t.interrupt();
        }
    }

    /** ids 为空表示“全部”。已经在的组件会跳过。 */
    static void start(Context ctx, List<String> ids) {
        if (busy) {
            LogBus.append("还在下，别急");
            return;
        }
        final Context app = ctx.getApplicationContext();
        final List<Catalog.Item> all = Catalog.items(app);
        final List<Catalog.Item> todo = new ArrayList<>();
        for (Catalog.Item it : all) {
            if (ids == null || ids.isEmpty() || ids.contains(it.id)) {
                if (!it.ready(app)) {
                    todo.add(it);
                }
            }
        }
        if (todo.isEmpty()) {
            LogBus.append("要的都下好了，直接点「安装 / 更新」就行");
            return;
        }
        CANCEL.set(false);
        busy = true;
        lastError = "";
        worker = new Thread(() -> run(app, todo), "arkdroid-dl");
        worker.setDaemon(true);
        worker.start();
    }

    private static void run(Context app, List<Catalog.Item> todo) {
        int ok = 0;
        try {
            for (Catalog.Item item : todo) {
                if (CANCEL.get()) {
                    LogBus.append("下载已取消（已下的部分留着，下次续）");
                    return;
                }
                label = item.title;
                done = 0;
                bps = -1;
                mirrorHost = "";
                File target = item.target(app);
                LogBus.append("== " + item.title + "（" + item.sizeLabel() + "）开始测速…");

                List<Downloader.Ranked> order = Downloader.rank(item.mirrors);
                for (Downloader.Ranked r : order) {
                    LogBus.append("   " + Downloader.host(r.url) + " → "
                            + (r.bps > 0 ? Downloader.humanSize(r.bps) + "/s" : "没测出速度"));
                }

                try {
                    Downloader.Outcome out = Downloader.fetch(order, target, item.sha256,
                            new Downloader.Progress() {
                                @Override
                                public void onProgress(long d, long t, long speed) {
                                    done = d;
                                    total = t;
                                    if (speed > 0) {
                                        bps = speed;
                                    }
                                }

                                @Override
                                public void onNote(String note) {
                                    LogBus.append("   " + note);
                                }
                            }, CANCEL);
                    mirrorHost = Downloader.host(out.mirror);
                    done = out.bytes;
                    total = out.bytes;
                    ok++;
                    LogBus.append("✓ " + item.title + " 下好了（"
                            + Downloader.humanSize(out.bytes) + "，来自 " + mirrorHost + "）");
                    Term.note(item.title + " 下载完成");
                } catch (IOException e) {
                    lastError = item.title + " 失败：" + e.getMessage();
                    LogBus.append("✗ " + lastError);
                }
            }
            LogBus.append(ok == todo.size()
                    ? "全部就绪，可以点「安装 / 更新」了"
                    : "下好 " + ok + "/" + todo.size() + " 个，剩下的再点一次会续上");
        } finally {
            busy = false;
            label = "";
            bps = -1;
            mirrorHost = "";
            if (ok > 0) {
                Term.note("下载完成 " + ok + " 项");
            }
        }
    }

    static String describe(Context ctx) {
        StringBuilder sb = new StringBuilder();
        for (Catalog.Item it : Catalog.items(ctx)) {
            if (sb.length() > 0) {
                sb.append("   ");
            }
            sb.append(it.ready(ctx) ? "✓ " : "○ ").append(it.title).append(' ').append(it.sizeLabel());
        }
        return sb.toString();
    }

    static boolean allReady(Context ctx) {
        List<Catalog.Item> items = Catalog.items(ctx);
        if (items.isEmpty()) {
            return false;
        }
        for (Catalog.Item it : items) {
            if (!it.ready(ctx)) {
                return false;
            }
        }
        return true;
    }

    static String humanBps(long bps) {
        if (bps <= 0) {
            return "";
        }
        return String.format(Locale.US, "%s/s", Downloader.humanSize(bps));
    }
}
