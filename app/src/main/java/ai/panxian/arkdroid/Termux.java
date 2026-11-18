package ai.panxian.arkdroid;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 和 Termux 打交道：发命令，并把输出捞回 App 的终端窗口。
 *
 * <p>推荐用户装的是 ArkTerm（我们重打包的 Termux，{@code versionName} 带 {@code -ark} 后缀），
 * {@code allow-external-apps} 授权已内置，装上即用。官方 Termux 也能用，
 * 但得手动在 {@code ~/.termux/termux.properties} 里加 {@code allow-external-apps=true}。
 *
 * <p>输出怎么回来：把 stdout/stderr POST 到 App 起的回环服务上。回传优先用 curl；
 * curl 要到 install.sh 第 1 步才装上，在那之前用 arknet.sh（bash /dev/tcp 原生 HTTP）兜底——
 * Termux bootstrap 必有 bash，未必有 curl，没这个兜底整个回传链会鸡生蛋地断掉。
 */
final class Termux {

    static final String PKG = "com.termux";
    private static final String BASH = "/data/data/com.termux/files/usr/bin/bash";
    private static final String HOME = "/data/data/com.termux/files/home";

    private Termux() {
    }

    /** 读 assets 里的 Termux 侧脚本（构建时由 syncTermux 从仓库 termux/ 目录同步）。 */
    static String readAsset(Context ctx, String name) {
        try (InputStream in = ctx.getAssets().open(name);
             ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    static boolean installed(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(PKG, 0) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** 装的是不是我们的 ArkTerm（versionName 以 -ark 结尾，授权内置）。 */
    static boolean isArkTerm(Context ctx) {
        try {
            String v = ctx.getPackageManager().getPackageInfo(PKG, 0).versionName;
            return v != null && v.endsWith("-ark");
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

    // ----------------------------------------------------- 首次启动引导标记

    /**
     * 刚装好的 ArkTerm（从未启动过）处于 stopped state，系统会拒绝其他 App 给它发命令；
     * 而且第一次启动要跑 bootstrap（装 bash/curl 等基础命令），install.sh 全靠它们。
     * 所以装完 APK 后记个标记，引导用户先打开一次 ArkTerm。
     */
    static void markNeedsFirstLaunch(Context ctx) {
        ctx.getSharedPreferences("arkdroid", Context.MODE_PRIVATE)
                .edit().putBoolean("arkterm_needs_first_launch", true).apply();
    }

    static void markLaunched(Context ctx) {
        ctx.getSharedPreferences("arkdroid", Context.MODE_PRIVATE)
                .edit().putBoolean("arkterm_needs_first_launch", false).apply();
    }

    static boolean needsFirstLaunch(Context ctx) {
        return ctx.getSharedPreferences("arkdroid", Context.MODE_PRIVATE)
                .getBoolean("arkterm_needs_first_launch", false);
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
                // 回传优先 curl，没有就 arknet（bash /dev/tcp）兜底
                + "; if command -v curl >/dev/null 2>&1; then"
                + " curl -sS -m " + timeoutSec + " -X POST --data-binary @\"$OUT\" \"$ARKDROID_LOCAL/p/term\";"
                + " elif [ -f \"$HOME/.arkdroid/arknet.sh\" ]; then"
                + " bash \"$HOME/.arkdroid/arknet.sh\" POST \"$ARKDROID_LOCAL/p/term\" - < \"$OUT\";"
                + " else cat \"$OUT\"; fi"
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
        // arknet.sh 先落进去：没有它，curl 没装好之前 install.sh 既下不下来也回传不了
        String arknet = readAsset(ctx, "termux/arknet.sh");
        if (arknet == null) {
            LogBus.append("arknet.sh 没打进 APK（构建问题），回传兜底不可用");
            return false;
        }
        String cmd = local
                + " mkdir -p ~/.arkdroid"
                + " && cat > ~/.arkdroid/arknet.sh <<'ARKNET_EOF'\n" + arknet + "\nARKNET_EOF\n"
                // App 刚启动时本地服务可能还没就绪：重试几次直到拿到非空脚本
                + " for i in 1 2 3 4 5; do"
                + " bash ~/.arkdroid/arknet.sh GET \"$ARKDROID_LOCAL/a/termux/install.sh\""
                + " > ~/.arkdroid/install.sh 2>/dev/null;"
                + " [ -s ~/.arkdroid/install.sh ] && break;"
                + " curl -fsSL -o ~/.arkdroid/install.sh \"$ARKDROID_LOCAL/a/termux/install.sh\" 2>/dev/null;"
                + " [ -s ~/.arkdroid/install.sh ] && break; sleep 2; done"
                + " ; echo \"=== install.sh 启动，输出开始实时回传 ===\""
                + " ; bash ~/.arkdroid/arknet.sh POST \"$ARKDROID_LOCAL/p/term\" \"=== install.sh 启动 ===\" >/dev/null 2>&1 || true"
                + " ; ( bash ~/.arkdroid/install.sh 2>&1 | tee -a ~/.arkdroid/install.log"
                // 输出逐行实时推回 App；curl 没有时走 arknet
                + " | while IFS= read -r l; do"
                + " if command -v curl >/dev/null 2>&1; then"
                + " curl -sS -m 10 -X POST --data-binary \"$l\" \"$ARKDROID_LOCAL/p/term\" >/dev/null 2>&1 || true;"
                + " else"
                + " bash ~/.arkdroid/arknet.sh POST \"$ARKDROID_LOCAL/p/term\" \"$l\" >/dev/null 2>&1 || true;"
                + " fi; done )";
        LogBus.append("开始安装：进度条在上面，实时输出进「终端」，Termux 窗口不用盯着");
        return run(ctx, cmd, true);
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
            markLaunched(ctx); // 命令能发出去 = bootstrap 已跑过，首启引导到此为止
            return true;
        } catch (Exception e) {
            Log.w("ArkDroid", "run command failed", e);
            String msg = String.valueOf(e.getMessage());
            String hint;
            if (msg.contains("app is in background") || msg.contains("Background")) {
                hint = "ArkTerm 刚装好还没打开过——先点「打开 ArkTerm」让它跑完首次启动"
                        + "（看到 ~ $ 提示符），再回来点一次。";
            } else if (msg.contains("without permission")) {
                hint = "缺 com.termux.permission.RUN_COMMAND 授权，回到 App 等它弹权限框。";
            } else {
                hint = "若反复失败，先打开一次 ArkTerm 再试。";
            }
            LogBus.append("发送 Termux 命令失败：" + msg + "。" + hint);
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
            LogBus.append("已打开系统安装器：" + apk.getName()
                    + "。装完先打开一次 ArkTerm（首次启动会自动装基础命令），再回来点安装");
            markNeedsFirstLaunch(ctx);
            return true;
        } catch (Exception e) {
            LogBus.append("打不开安装器：" + e.getMessage() + "（可以手动去文件夹里点）");
            return false;
        }
    }
}
