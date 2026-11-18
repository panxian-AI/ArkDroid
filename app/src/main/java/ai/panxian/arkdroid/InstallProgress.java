package ai.panxian.arkdroid;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 安装进度：Termux 里的 install.sh 每做完一步，就把「3/9 Ubuntu 里的 Python」这样的
 * 进度行实时 POST 到 App（本地回环 p/prog）。这里攒住最新状态，UI 轮询它画进度条。
 *
 * <p>为什么不用下载那套静态字段：安装是脚本自己推的，App 是被动收，状态简单得多。
 */
final class InstallProgress {

    static volatile int step;          // 当前步（0 = 还没开始推）
    static volatile int total = 9;
    static volatile String desc = "";  // 步骤描述
    static volatile boolean done;      // 脚本报了“全部完成”
    static volatile boolean failed;    // 脚本报了 "fail ..."（安装中断）
    static volatile long doneAt;       // 完成时间，UI 过段时间自动收起

    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private InstallProgress() {
    }

    /** body 形如「3/9 Ubuntu 里的 Python」「done 全部装完了」「fail 安装中断（6/9）：…」。 */
    static void absorb(String body) {
        if (body == null || body.isEmpty()) {
            return;
        }
        String line = body.replace("\n", " ").trim();
        if (line.startsWith("done")) {
            done = true;
            failed = false;
            doneAt = System.currentTimeMillis();
            step = total;
            desc = line.length() > 4 ? line.substring(4).trim() : "全部完成 ✓";
        } else if (line.startsWith("fail")) {
            done = false;
            failed = true;
            doneAt = System.currentTimeMillis();
            desc = line.length() > 4 ? line.substring(4).trim() : "安装中断";
        } else {
            done = false;
            failed = false;
            int slash = line.indexOf('/');
            if (slash > 0) {
                StringBuilder num = new StringBuilder();
                for (int i = slash - 1; i >= 0 && Character.isDigit(line.charAt(i)); i--) {
                    num.insert(0, line.charAt(i));
                }
                int after = slash + 1;
                StringBuilder den = new StringBuilder();
                for (int i = after; i < line.length() && Character.isDigit(line.charAt(i)); i++) {
                    den.append(line.charAt(i));
                }
                try {
                    step = Integer.parseInt(num.toString());
                } catch (NumberFormatException ignored) {
                }
                if (den.length() > 0) {
                    try {
                        total = Integer.parseInt(den.toString());
                    } catch (NumberFormatException ignored) {
                    }
                }
                int space = line.indexOf(' ');
                desc = space > 0 ? line.substring(space + 1).trim() : line;
            } else {
                desc = line;
            }
        }
        fire();
    }

    static boolean visible() {
        if (step == 0 && !done && !failed) {
            return false;
        }
        if ((done || failed) && System.currentTimeMillis() - doneAt > 5 * 60 * 1000L) {
            return false;
        }
        return true;
    }

    /** 安装正在跑（没完成也没失败）——App 用它把「安装 / 更新」按钮按住，防止连点。 */
    static boolean busy() {
        return visible() && !done && !failed;
    }

    static int percent() {
        if (total <= 0) {
            return 0;
        }
        return (int) Math.min(100, step * 100L / total);
    }

    static String line() {
        if (failed) {
            return "✗ " + desc;
        }
        if (done) {
            return "✓ " + desc;
        }
        return "安装中 · 第 " + Math.max(0, step) + "/" + total + " 步：" + desc;
    }

    static String hint() {
        if (failed) {
            return "安装中断了。看下面「终端」的 ✗ 输出（或 astr log），修好后重新点「安装 / 更新」，会从断掉的地方接着装。";
        }
        if (done) {
            return "装完了。回到对应卡片点「启动」就行。";
        }
        if (step >= 6 && step <= 7) {
            return "装依赖 / QQ 这一步最花时间，没有新进度输出不代表卡死，喝口水等等。";
        }
        return "详细输出在下面「终端」里滚动，装完这里会变成 ✓。";
    }

    static void addListener(Runnable r) {
        if (!LISTENERS.contains(r)) {
            LISTENERS.add(r);
        }
    }

    static void removeListener(Runnable r) {
        LISTENERS.remove(r);
    }

    private static void fire() {
        for (Runnable r : LISTENERS) {
            try {
                r.run();
            } catch (RuntimeException ignored) {
            }
        }
    }
}
