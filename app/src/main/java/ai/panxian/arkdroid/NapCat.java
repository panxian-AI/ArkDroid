package ai.panxian.arkdroid;

import android.content.Context;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 手机本机的 QQ 协议端（NapCat）。
 *
 * <p>为什么不在 APK 里自己注入 QQ：NapCat 要在 Electron 里加载、还要处理 proot 下
 * {@code dlopen} 从 memfd 加载 .so 这类问题，官方已经把这套兼容做好了，并且明确提供
 * Termux 安装脚本。所以我们只做三件事——下载官方脚本、把它跑起来、把结果读回来。
 * 上游怎么改版本，这里都不用跟着改。
 */
final class NapCat {

    /** NapCat 官方 Termux 安装脚本（文档里给的就是这个地址）。 */
    static final String SCRIPT_URL =
            "https://nclatest.znin.net/NapNeko/NapCat-Installer/main/script/install.termux.sh";

    /** WebUI 端口，扫码登录、配置 OneBot 都在这个页面里。 */
    static final int WEBUI_PORT = 6099;

    /** OneBot 11 正向 WebSocket 的默认端口，中继连的就是它。 */
    static final int ONEBOT_PORT = 3001;

    static final String WEBUI_URL = "http://127.0.0.1:" + WEBUI_PORT + "/webui";
    static final String ONEBOT_URL = "ws://127.0.0.1:" + ONEBOT_PORT;

    /** 最近一次探测结果：6099 有没有人在听。 */
    static volatile boolean webuiUp;
    static volatile String lastError = "";
    static volatile long probedAt;

    private NapCat() {
    }

    static String dir(Context ctx) {
        return ctx.getFilesDir().getParent() + "/files/napcat";
    }

    /** 装：下载官方脚本再执行。真正的安装动作全交给上游脚本。 */
    static boolean install(Context ctx) {
        BridgeService.serve(ctx); // 让 Termux 能把日志 POST 回来
        LogBus.append("准备装 NapCat：先下官方脚本，再执行");
        return Termux.send(ctx,
                "mkdir -p ~/.arkdroid"
                        + "; curl -fsSL --connect-timeout 20 -o ~/.arkdroid/napcat.termux.sh "
                        + "'" + SCRIPT_URL + "'"
                        + "; echo \"--- 脚本已下载，开始执行 ---\""
                        + "; bash ~/.arkdroid/napcat.termux.sh"
                        + "; echo \"--- NapCat 安装脚本结束，退出码 $? ---\"",
                1800);
    }

    /** 探测 WebUI 是否在线。要在后台线程调。 */
    static void probe() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://127.0.0.1:" + WEBUI_PORT + "/").openConnection();
            c.setConnectTimeout(700);
            c.setReadTimeout(900);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            // 401 也算在线——NapCat 的 WebUI 要 token，会先回 401
            webuiUp = code > 0;
            lastError = "";
        } catch (IOException e) {
            webuiUp = false;
            lastError = e.getClass().getSimpleName();
        } finally {
            if (c != null) {
                c.disconnect();
            }
            probedAt = System.currentTimeMillis();
        }
    }

    static String summary() {
        if (webuiUp) {
            return "NapCat 在线 · WebUI " + WEBUI_URL;
        }
        return "NapCat 没响应（还没装，或者没启动）";
    }

    /**
     * 预置 OneBot 正向 WS 配置，省掉在 WebUI 里手点一遍。
     *
     * <p>只在目录已经存在时写，不猜安装路径；写不进去也不影响使用（还能在 WebUI 里配）。
     */
    static void seedOneBotConfig(Context ctx) {
        String cmd = "for d in ~/napcat ~/.napcat ~/NapCat ~/.arkdroid/napcat/config; do "
                + "if [ -d \"$d\" ]; then "
                + "F=\"$d/onebot11.json\"; "
                + "[ -f \"$F\" ] || cat > \"$F\" <<'JSON'\n"
                + "{\n"
                + "  \"network\": {\n"
                + "    \"httpServers\": [],\n"
                + "    \"httpSseServers\": [],\n"
                + "    \"httpClients\": [],\n"
                + "    \"websocketServers\": [\n"
                + "      {\n"
                + "        \"name\": \"arkdroid\",\n"
                + "        \"enable\": true,\n"
                + "        \"host\": \"127.0.0.1\",\n"
                + "        \"port\": " + ONEBOT_PORT + ",\n"
                + "        \"messagePostFormat\": \"array\",\n"
                + "        \"reportSelfMessage\": false,\n"
                + "        \"token\": \"\",\n"
                + "        \"enableForcePushEvent\": true,\n"
                + "        \"debug\": false,\n"
                + "        \"heartInterval\": 30000\n"
                + "      }\n"
                + "    ],\n"
                + "    \"websocketClients\": [],\n"
                + "    \"plugins\": []\n"
                + "  },\n"
                + "  \"musicSignUrl\": \"\",\n"
                + "  \"enableLocalFile2Url\": false,\n"
                + "  \"parseMultMsg\": false\n"
                + "}\n"
                + "JSON\n"
                + "echo \"已写入 OneBot 配置：$F\"; fi; done";
        Termux.send(ctx, cmd, 120);
    }
}
