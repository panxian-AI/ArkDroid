package ai.panxian.arkdroid;

import android.content.Context;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 手机本机的 QQ 协议端链路（一台手机自运行）：
 *
 * <pre>
 * QQ arm64(proot, Xvfb 无头) → NapCat(Milky 3000) → ArkLink(OneBot 6700) → ArkBridge → AstrBot
 * </pre>
 *
 * <p>安装全部由 termux/install.sh 的 --qq-only 段完成：apt 装 QQ arm64 deb、把
 * NapCat.Shell 注入 QQ 的 app_launcher、部署 ArkLink。我们只负责下发命令和读状态。
 * NapCat 的压缩包由用户提供（官方 GitHub Releases 已清空），固定放在
 * ArkLink 仓库的 napcat-shell Release。
 */
final class NapCat {

    /** NapCat WebUI 端口，扫码登录、配置 Milky 都在这个页面里。 */
    static final int WEBUI_PORT = 6099;

    /** NapCat 的 Milky 端点（ArkLink 连它）。 */
    static final int MILKY_PORT = 3000;

    /** ArkLink 的 OneBot 11 服务端端口——中继（ArkBridge）连的是它，不是 NapCat。 */
    static final int ARKLINK_PORT = 6700;

    /** NapCat 自己的 OneBot 正向 WS（备用直连，不走 ArkLink 时用）。 */
    static final int ONEBOT_PORT = 3001;

    static final String WEBUI_URL = "http://127.0.0.1:" + WEBUI_PORT + "/webui";
    static final String ARKLINK_URL = "ws://127.0.0.1:" + ARKLINK_PORT + "/onebot/v11/ws";
    static final String ONEBOT_URL = "ws://127.0.0.1:" + ONEBOT_PORT;

    /** 最近一次探测结果。 */
    static volatile boolean webuiUp;
    static volatile boolean arklinkUp;
    static volatile String lastError = "";
    static volatile long probedAt;

    private NapCat() {
    }

    static String dir(Context ctx) {
        return ctx.getFilesDir().getParent() + "/files/napcat";
    }

    /**
     * 装/补本机 QQ：跑 install.sh --qq-only。
     *
     * <p>要求 App 已经把「QQ 安装包」和「NapCat 压缩包」下载到本地
     * （下载卡里点单项），脚本拿不到包会退回镜像，再不行就明确报错。
     */
    static boolean install(Context ctx) {
        BridgeService.serve(ctx); // 让 Termux 能把日志 POST 回来
        LogBus.append("装本机 QQ：QQ deb + NapCat 注入 + ArkLink（install.sh --qq-only）");
        return Termux.send(ctx,
                "mkdir -p ~/.arkdroid"
                        // 装 QQ 时 curl 一般已就位（第 1 步装的），没有就 arknet 兜底
                        + "; for i in 1 2 3; do"
                        + " if command -v curl >/dev/null 2>&1; then"
                        + " curl -fsSL -m 120 -o ~/.arkdroid/install.sh \"$ARKDROID_LOCAL/a/termux/install.sh\";"
                        + " else"
                        + " bash ~/.arkdroid/arknet.sh GET \"$ARKDROID_LOCAL/a/termux/install.sh\" > ~/.arkdroid/install.sh;"
                        + " fi"
                        + " && [ -s ~/.arkdroid/install.sh ] && break; sleep 2; done"
                        + "; echo \"--- 开始：本机 QQ + ArkLink ---\""
                        // 输出逐行实时推回 App，不等装完
                        + "; ( bash ~/.arkdroid/install.sh --qq-only 2>&1 | tee -a ~/.arkdroid/qq-install.log"
                        + " | while IFS= read -r l; do"
                        + " if command -v curl >/dev/null 2>&1; then"
                        + " curl -sS -m 10 -X POST --data-binary \"$l\" \"$ARKDROID_LOCAL/p/term\" >/dev/null 2>&1 || true;"
                        + " elif [ -f \"$HOME/.arkdroid/arknet.sh\" ]; then"
                        + " bash ~/.arkdroid/arknet.sh POST \"$ARKDROID_LOCAL/p/term\" \"$l\" >/dev/null 2>&1 || true;"
                        + " fi; done )"
                        + "; echo \"--- 结束，退出码 $? ---\"",
                3600);
    }

    /** 启动整条本机链路（QQ 无头 + NapCat + ArkLink）。 */
    static boolean start(Context ctx) {
        BridgeService.serve(ctx);
        return Termux.send(ctx,
                "~/.arkdroid/qq start; ~/.arkdroid/arklink start",
                300);
    }

    /** 探测 NapCat WebUI(6099) 与 ArkLink(6700) 是否在线。要在后台线程调。 */
    static void probe() {
        webuiUp = alive(WEBUI_PORT);
        arklinkUp = alive(ARKLINK_PORT);
        probedAt = System.currentTimeMillis();
    }

    private static boolean alive(int port) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/").openConnection();
            c.setConnectTimeout(700);
            c.setReadTimeout(900);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            // 401 也算在线——NapCat 的 WebUI 要 token，会先回 401
            return code > 0;
        } catch (IOException e) {
            lastError = e.getClass().getSimpleName();
            return false;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    static String summary() {
        if (arklinkUp && webuiUp) {
            return "链路在线 · ArkLink " + ARKLINK_PORT + " · NapCat WebUI " + WEBUI_PORT;
        }
        if (webuiUp) {
            return "NapCat 在线（WebUI " + WEBUI_PORT + "），ArkLink 没起来（arklink start）";
        }
        return "没响应（还没装，或者没启动）";
    }

    /**
     * 预置 NapCat 的 OneBot 正向 WS 配置（备用直连 3001）。
     *
     * <p>只在目录已经存在时写，不猜安装路径；写不进去也不影响使用（还能在 WebUI 里配）。
     * Milky（3000，ArkLink 连的那条）要在 WebUI 的「网络配置」里新建——格式经常变，
     * 不在这里硬写。
     */
    static void seedOneBotConfig(Context ctx) {
        String cmd = "for d in ~/.arkdroid/rootfs/opt/QQ/resources/app/app_launcher/napcat/config; do "
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
                + "        \"name\": \"arkdroid-direct\",\n"
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
                + "echo \"已写入 OneBot 配置（备用直连 3001）：$F\"; fi; done";
        Termux.send(ctx, cmd, 120);
    }
}
