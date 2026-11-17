package ai.panxian.arkdroid;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 桌面侧的下载内核自测入口（不属于 APK）。
 *
 * <p>用法：DownloaderCheck &lt;目标文件&gt; &lt;sha256|-> &lt;镜像1&gt; &lt;镜像2&gt; ...
 * 输出第一行是选中的镜像，第二行是结果，方便 shell 断言。
 */
public final class DownloaderCheck {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("用法: DownloaderCheck <file> <sha|-> <mirror>...");
            System.exit(2);
        }
        File target = new File(args[0]);
        String sha = "-".equals(args[1]) ? "" : args[1];
        List<String> mirrors = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            mirrors.add(args[i]);
        }

        long t0 = System.currentTimeMillis();
        List<Downloader.Ranked> order = Downloader.rank(mirrors);
        for (Downloader.Ranked r : order) {
            System.out.println("RANK " + r.bps + " " + r.url);
        }

        Downloader.Outcome out = Downloader.fetch(order, target, sha,
                new Downloader.Progress() {
                    @Override
                    public void onProgress(long done, long total, long bps) {
                        // 桌面测试不要刷屏
                    }

                    @Override
                    public void onNote(String note) {
                        System.out.println("NOTE " + note);
                    }
                }, new AtomicBoolean(false));

        System.out.println("CHOSEN " + out.mirror);
        System.out.println("RESULT " + out.bytes + " " + Downloader.sha256(out.file)
                + " " + (System.currentTimeMillis() - t0) + "ms");
    }
}
