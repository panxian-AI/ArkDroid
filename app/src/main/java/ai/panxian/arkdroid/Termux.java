package ai.panxian.arkdroid;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 通过 Termux 的 RUN_COMMAND 接口跑命令。
 *
 * <p>前提是 Termux 里把 {@code allow-external-apps=true} 打开（~/.termux/termux.properties），
 * 否则 Termux 会拒绝，界面上会给出提示。
 */
final class Termux {

    static final String PKG = "com.termux";

    /** 一键装 AstrBot + 托管脚本。中间产物落在 ~/arkdroid 下。 */
    static final String INSTALL_CMD =
            "curl -fsSL -o ~/arkdroid-install.sh "
                    + "https://raw.githubusercontent.com/panxian-AI/ArkDroid/main/termux/install.sh "
                    + "&& bash ~/arkdroid-install.sh";

    static final String START_CMD = "astr start";
    static final String STOP_CMD = "astr stop";

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

    static boolean run(Context ctx, String command, boolean background) {
        if (!installed(ctx)) {
            LogBus.append("没装 Termux，命令发不出去");
            return false;
        }
        try {
            Intent intent = new Intent();
            intent.setClassName(PKG, "com.termux.app.RunCommandService");
            intent.setAction("com.termux.RUN_COMMAND");
            intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash");
            intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-lc", command});
            intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home");
            intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", background);
            // 0 = 切到该 session，装东西时用户能直接看见进度
            intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", background ? "1" : "0");
            ctx.startService(intent);
            LogBus.append("已交给 Termux：" + command);
            return true;
        } catch (Exception e) {
            Log.w("ArkDroid", "run command failed", e);
            LogBus.append("发送 Termux 命令失败：" + e.getMessage());
            return false;
        }
    }
}
