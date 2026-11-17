package ai.panxian.arkdroid;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;

/**
 * 和 Termux 打交道：发命令，并把输出捞回 App 的终端窗口。
 *
 * <p>前提是 Termux 里开了 {@code allow-external-apps=true}（~/.termux/termux.properties）。
 *
 * <p>输出怎么回来：Termux 执行 {@code ( 命令 ) 2>&1 | curl -X POST ... $ARKDROID_LOCAL/p/term}，
 * 也就是把 stdout/stderr 直接 POST 到 App 起的回环服务上。curl 从管道读时用 chunked，
 * 所以 App 那边能一边跑一边看到输出，不是等结束了才刷一大坨。
 */
final class Termux {

    static final String PKG = "com.termux";
    private static final String BASH = "/data/data/com.termux/files/usr/bin/bash";
    private static final String HOME = "/data/data/com.termux/files/home";

    private Termux() {
    }

    static boolean installed(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(PKG, 0) != null;
        } catch (Exception e) {
            return false;
        }
    }

    static void launch(Context ctx) {
        Intent intent = ctx.getPackageManager().getLaunchIntentForPackage(PKG);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        }
    }

    // ------------------------------------------------------------------ 通道

    /** 前缀：把本地服务地址塞进环境变量，后面所有命令都靠它回传输出。 */
    private static String base(Context ctx) throws IOException {
        String url = LocalServer.ensure(ctx);
        return "export ARKDROID_LOCAL='" + url + "';";
    }

    /**
     * 把命令的输出（含 stderr）捞回 App。
     *
     * <p>注意：不能用 {@code cmd | curl --data-binary @-} —— curl 会先把 stdin 全部读进内存
     * 才发请求，看着像卡住，安装那种十几分钟的命令尤其难受。所以先落文件、再按文件 POST，
     * 大小确定、能续传、也不受 curl 缓冲策略影响。
     */
    private static String piped(Context ctx, String command, int timeoutSec) throws IOException {
        return base(ctx)
                + " mkdir -p ~/.arkdroid"
                + "; OUT=$HOME/.arkdroid/term.out"
                + "; ( " + command + " ) > \"$OUT\" 2>&1"
                + "; curl -sS -m " + timeoutSec
                + " -X POST --data-binary @\"$OUT\" \"$ARKDROID_LOCAL/p/term\""
                + "; rm -f \"$OUT\"";
    }

    // ------------------------------------------------------------------ 动作

    /** 一条命令装完 Termux 侧的一切：装依赖 → 解 rootfs → 装 AstrBot → 注册 astr。 */
    static boolean install(Context ctx) {
        String local;
        try {
            local = base(ctx);
        } catch (IOException e) {
            LogBus.append("本地服务起不来，没法把安装包递给 Termux：" + e.getMessage());
            return false;
        }
        String cmd = local
                + " mkdir -p ~/.arkdroid"
                + " && curl -fsSL -o ~/.arkdroid/install.sh \"$ARKDROID_LOCAL/a/termux/install.sh\""
                + " && ( bash ~/.arkdroid/install.sh 2>&1 | tee -a ~/.arkdroid/install.log )"
                + "; curl -sS -m 7200 -X POST --data-binary @$HOME/.arkdroid/install.log"
                + " \"$ARKDROID_LOCAL/p/term\"";
        LogBus.append("开始安装：App 里下好的包走 127.0.0.1 递给 Termux，"
                + "Termux 窗口里能看实时进度，装完这里会显示完整日志");
        return run(ctx, cmd, false);
    }

    /** 跑 astr 子命令（start/stop/status/creds/log…），输出进终端。 */
    static boolean astr(Context ctx, String args) {
        return send(ctx, "astr " + args, 600);
    }

    /** 在 Termux 里跑任意命令，输出进终端。 */
    static boolean send(Context ctx, String command, int timeoutSec) {
        try {
            return run(ctx, piped(ctx, command, timeoutSec), true);
        } catch (IOException e) {
            LogBus.append("命令发不出去：" + e.getMessage());
            return false;
        }
    }

    /** 重置面板密码：AstrBot 认 ASTRBOT_RESET_DASHBOARD_PASSWORD 这个环境变量。 */
    static boolean resetPassword(Context ctx, String password) {
        String safe = password.replace("'", "'\\''");
        return send(ctx, "astr resetpw '" + safe + "'", 300);
    }

    static boolean run(Context ctx, String command, boolean background) {
        if (!installed(ctx)) {
            LogBus.append("没装 Termux，命令发不出去");
            return false;
        }
        try {
            Intent intent = new Intent();
            intent.setClassName(PKG, "com.termux.app.RunCommandService");
            intent.setAction("com.termux.RUN_COMMAND");
            intent.putExtra("com.termux.RUN_COMMAND_PATH", BASH);
            intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-lc", command});
            intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME);
            intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", background);
            // 0 = 切到该 session，装东西时用户能直接看见进度
            intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", background ? "1" : "0");
            ctx.startService(intent);
            return true;
        } catch (Exception e) {
            Log.w("ArkDroid", "run command failed", e);
            LogBus.append("发送 Termux 命令失败：" + e.getMessage()
                    + "（Termux 里得开 allow-external-apps）");
            return false;
        }
    }

    // ------------------------------------------------------------------ 装 APK

    /** 拉起系统安装器装 App 自己下好的 APK。 */
    static boolean installApk(Context ctx, File apk) {
        if (apk == null || !apk.isFile()) {
            LogBus.append("APK 还没下好，先点「下载 Termux」");
            return false;
        }
        try {
            Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".files", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            LogBus.append("已打开系统安装器：" + apk.getName());
            return true;
        } catch (Exception e) {
            LogBus.append("打不开安装器：" + e.getMessage() + "（可以手动去文件夹里点）");
            return false;
        }
    }
}
