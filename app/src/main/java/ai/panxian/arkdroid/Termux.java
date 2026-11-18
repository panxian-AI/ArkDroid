package ai.panxian.arkdroid;

import android.content.Context;
import android.util.Log;

import java.io.IOException;

/**
 * Termux 侧的门面：现在不再依赖单独安装的 ArkTerm，一切走 {@link TermuxRuntime}。
 *
 * <p>历史包袱清掉了：RUN_COMMAND 危险权限申请、allow-external-apps 授权、
 * 「先打开一次 ArkTerm」引导、官方 Termux 兼容提示——它们存在的唯一原因
 * 是跨 App 通信；把运行时嵌进本 App 之后这些坑就不存在了。
 */
final class Termux {

    private Termux() {
    }

    /** bootstrap 已解压就绪（能跑 bash）。 */
    static boolean installed(Context ctx) {
        return TermuxRuntime.ready(ctx);
    }

    /** bootstrap 在下载/解压中（目录有但 bash 没出来）。 */
    static boolean installing(Context ctx) {
        return TermuxRuntime.partial(ctx);
    }

    /**
     * 一键安装：解压 bootstrap → 拉 Termux 侧脚本 → 后台跑 install.sh。
     *
     * <p>所有输出实时进「终端」；App 被杀安装也继续（install.sh 由 setsid 脱离进程组）。
     */
    static boolean install(Context ctx) {
        Catalog.Item bs = Catalog.find(ctx, "bootstrap");
        if (bs == null || !bs.ready(ctx)) {
            LogBus.append("bootstrap 还没下好：先去下载卡点「一键下载全部」（约 32 MB）");
            return false;
        }
        if (installing(ctx)) {
            LogBus.append("bootstrap 上次解压没完成，正在重新解压…");
        }
        final java.io.File zip = bs.target(ctx);
        LogBus.append("开始安装：解压 bootstrap → 解 rootfs → 装 AstrBot，进度看下面");
        bg(ctx, () -> {
            try {
                TermuxRuntime.installBootstrap(ctx, zip);
            } catch (Exception e) {
                LogBus.append("解压 bootstrap 失败：" + e.getMessage());
                return;
            }
            String scripts = ""
                    // 六个 Termux 侧脚本全部从 assets 端点拿（bootstrap 自带 curl 或用 arknet 兜底）
                    + " mkdir -p \"$HOME/.arkdroid\"; all=1"
                    + "; for f in arknet.sh install.sh astr qq arklink creds.py setpw.py; do"
                    + " if command -v curl >/dev/null 2>&1; then"
                    + " curl -fsSL -m 60 -o \"$HOME/.arkdroid/$f\" \"$ARKDROID_LOCAL/a/termux/$f\""
                    + "; else bash \"$HOME/.arkdroid/arknet.sh\" GET \"$ARKDROID_LOCAL/a/termux/$f\""
                    + " > \"$HOME/.arkdroid/$f\" 2>/dev/null; fi"
                    + "; if [ -s \"$HOME/.arkdroid/$f\" ]; then echo \"✓ $f\""
                    + "; else all=0; echo \"✗ $f 没拿到\"; fi; done"
                    + "; chmod +x \"$HOME/.arkdroid/install.sh\" \"$HOME/.arkdroid/astr\""
                    + " \"$HOME/.arkdroid/qq\" \"$HOME/.arkdroid/arklink\" 2>/dev/null"
                    + "; for c in astr qq arklink; do ln -sf \"$HOME/.arkdroid/$c\" \"$PREFIX/bin/$c\"; done"
                    + "; if [ \"$all\" = 1 ]; then echo \"✓ 脚本就位，启动安装器\""
                    // setsid 脱离 App 进程组：App 被杀安装继续
                    + "; setsid bash \"$HOME/.arkdroid/install.sh\" </dev/null >/dev/null 2>&1 &"
                    + " echo \"✓ 安装器已后台启动，输出会实时流回这里\""
                    + "; else echo \"✗ 脚本没下齐，安装器没有启动\"; fi";
            TermuxRuntime.exec(ctx, scripts);
        });
        return true;
    }

    /** 跑 astr 子命令（start/stop/status/creds/log…），输出进终端。 */
    static boolean astr(Context ctx, String args) {
        return TermuxRuntime.exec(ctx, "astr " + args);
    }

    /** 在 Termux 运行时里跑任意命令，输出进终端。 */
    static boolean send(Context ctx, String command, int timeoutSec) {
        return TermuxRuntime.exec(ctx, command);
    }

    static boolean run(Context ctx, String command, boolean background) {
        return TermuxRuntime.exec(ctx, command);
    }

    /** 重置面板密码：AstrBot 认 ASTRBOT_RESET_DASHBOARD_PASSWORD 这个环境变量。 */
    static boolean resetPassword(Context ctx, String password) {
        String safe = password.replace("'", "'\\''");
        return send(ctx, "astr resetpw '" + safe + "'", 300);
    }

    // ------------------------------------------------------------------ 内部

    private static void bg(Context ctx, Runnable r) {
        new Thread(r, "arkdroid-install").start();
    }
}
