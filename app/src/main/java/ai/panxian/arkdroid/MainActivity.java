package ai.panxian.arkdroid;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String PANEL_URL = "http://127.0.0.1:6185";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private Prefs prefs;
    private TextView chipBridge;
    private TextView chipAccount;
    private TextView chipAstr;
    private TextView txtBridgeState;
    private TextView txtAstrState;
    private TextView txtStats;
    private TextView txtLog;
    private ScrollView logScroll;
    private SwitchMaterial swAutostart;
    private TextInputEditText inpArklink;
    private TextInputEditText inpAstrbot;
    private TextInputEditText inpSelfId;
    private TextInputEditText inpAstrbotToken;
    private TextInputEditText inpArklinkToken;
    private MaterialButton btnCheck;
    private ProgressBar progressDl;
    private TextView txtDlState;
    private TextView txtCreds;
    private TextView txtTerm;
    private ScrollView termScroll;
    private TextInputEditText inpCmd;
    private NestedScrollView scroller;
    private View cardBridge;
    private View cardDl;
    private View cardAstr;
    private View cardTerm;
    private View cardQq;
    private View cardProgress;
    private ProgressBar progressInstall;
    private TextView txtInstallStep;
    private TextView txtInstallHint;
    private android.widget.LinearLayout dlList;
    private final java.util.Map<String, TextView> dlRows = new java.util.HashMap<>();
    private boolean welcomed;
    private boolean credsAuto;
    private boolean dlDoneNotice;

    private final Runnable termRefresh = new Runnable() {
        @Override
        public void run() {
            if (txtTerm == null) {
                return;
            }
            String text = Term.text();
            if (!text.contentEquals(txtTerm.getText())) {
                boolean bottom = atBottom(termScroll);
                txtTerm.setText(text);
                if (bottom) {
                    termScroll.post(() -> termScroll.fullScroll(View.FOCUS_DOWN));
                }
            }
        }
    };

    /** 用户停在底部附近才算"在看最新"，翻上去看历史就不动他的位置。 */
    private boolean atBottom(ScrollView sv) {
        if (sv == null || sv.getChildCount() == 0) {
            return true;
        }
        int bottom = sv.getChildAt(0).getBottom();
        return bottom - (sv.getHeight() + sv.getScrollY()) < 100;
    }

    private final Runnable credsRefresh = new Runnable() {
        @Override
        public void run() {
            if (txtCreds != null) {
                txtCreds.setText(Creds.summary());
            }
        }
    };

    private final Runnable logRefresh = new Runnable() {
        @Override
        public void run() {
            String text = LogBus.text();
            if (!text.contentEquals(txtLog.getText())) {
                boolean bottom = atBottom(logScroll);
                txtLog.setText(text);
                if (bottom) {
                    logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
                }
            }
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 600);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);
        bind();
        fill();
        wire();
        showVersion();
        askNotificationPermission();
        askRunCommandPermission();

        bg.execute(() -> {
            try {
                LocalServer.ensure(this);
            } catch (IOException e) {
                LogBus.append("本地服务起不来（安装时会退化成走网络）：" + e.getMessage());
            }
            Catalog.refresh(this);
        });

        if (!welcomed) {
            welcomed = true;
            LogBus.append("ArkDroid 已就绪。中继直接跑在本 App 里（Chaquopy + ArkBridge）。");
            Term.note("第一次用：点「一键下载全部」→ 装 Termux → 点「安装 / 更新」。");
            if (!Termux.installed(this)) {
                LogBus.append("没检测到 Termux：只想手机当中继可以不管；要跑 AstrBot 就得装。");
            }
        }
        if (prefs.autostart() && !BridgeService.running) {
            BridgeService.start(this);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        LogBus.addListener(logRefresh);
        logRefresh.run();
        Term.addListener(termRefresh);
        termRefresh.run();
        Creds.addListener(credsRefresh);
        credsRefresh.run();
        ui.post(ticker);
    }

    @Override
    protected void onStop() {
        super.onStop();
        LogBus.removeListener(logRefresh);
        Term.removeListener(termRefresh);
        Creds.removeListener(credsRefresh);
        ui.removeCallbacks(ticker);
    }

    // ------------------------------------------------------------------ 初始化

    private void bind() {
        chipBridge = findViewById(R.id.chipBridge);
        chipAccount = findViewById(R.id.chipAccount);
        chipAstr = findViewById(R.id.chipAstr);
        txtBridgeState = findViewById(R.id.txtBridgeState);
        txtAstrState = findViewById(R.id.txtAstrState);
        txtStats = findViewById(R.id.txtStats);
        txtLog = findViewById(R.id.txtLog);
        logScroll = findViewById(R.id.logScroll);
        swAutostart = findViewById(R.id.swAutostart);
        inpArklink = findViewById(R.id.inpArklink);
        inpAstrbot = findViewById(R.id.inpAstrbot);
        inpSelfId = findViewById(R.id.inpSelfId);
        inpAstrbotToken = findViewById(R.id.inpAstrbotToken);
        inpArklinkToken = findViewById(R.id.inpArklinkToken);
        btnCheck = findViewById(R.id.btnCheck);
        progressDl = findViewById(R.id.progressDl);
        txtDlState = findViewById(R.id.txtDlState);
        txtCreds = findViewById(R.id.txtCreds);
        txtTerm = findViewById(R.id.txtTerm);
        termScroll = findViewById(R.id.termScroll);
        inpCmd = findViewById(R.id.inpCmd);

        scroller = findViewById(R.id.scroller);
        cardBridge = findViewById(R.id.cardBridge);
        cardDl = findViewById(R.id.cardDl);
        cardAstr = findViewById(R.id.cardAstr);
        cardTerm = findViewById(R.id.cardTerm);
        cardQq = findViewById(R.id.cardQq);
        cardProgress = findViewById(R.id.cardProgress);
        progressInstall = findViewById(R.id.progressInstall);
        txtInstallStep = findViewById(R.id.txtInstallStep);
        txtInstallHint = findViewById(R.id.txtInstallHint);
        dlList = findViewById(R.id.dlList);

        // 外层 NestedScrollView 会把触摸事件抢走，内层终端/日志就滑不动。
        // 按到内层时告诉父级别拦截，事件继续正常分发。
        View.OnTouchListener passThrough = (v, ev) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            return false;
        };
        termScroll.setOnTouchListener(passThrough);
        logScroll.setOnTouchListener(passThrough);
    }

    /** 顶部导航：把对应卡片滚到可视区，别让人靠猜「功能在哪」。 */
    private void jumpTo(View card) {
        if (scroller == null || card == null) {
            return;
        }
        scroller.post(() -> scroller.smoothScrollTo(0, Math.max(0, card.getTop() - 6)));
    }

    private void showVersion() {
        TextView tv = findViewById(R.id.txtVersion);
        if (tv == null) {
            return;
        }
        String v = "?";
        try {
            v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        tv.setText("v" + v);
    }

    private void fill() {
        inpArklink.setText(prefs.arklink());
        inpAstrbot.setText(prefs.astrbot());
        inpSelfId.setText(prefs.selfId());
        inpAstrbotToken.setText(prefs.astrbotToken());
        inpArklinkToken.setText(prefs.arklinkToken());
        swAutostart.setChecked(prefs.autostart());
    }

    private void wire() {
        findViewById(R.id.btnStart).setOnClickListener(v -> {
            BridgeService.start(this);
            toast("已下发启动");
        });
        findViewById(R.id.btnStop).setOnClickListener(v -> {
            BridgeService.stop(this);
            toast("已下发停止");
        });
        btnCheck.setOnClickListener(v -> runProbe());
        findViewById(R.id.btnSave).setOnClickListener(v -> saveAndRestart());
        findViewById(R.id.btnBattery).setOnClickListener(v -> requestBatteryWhitelist());
        findViewById(R.id.btnInstall).setOnClickListener(v -> installAstrBot());
        findViewById(R.id.btnAstrStart).setOnClickListener(v -> {
            Term.note("$ astr start");
            sendCommandAsync("astr start");
        });
        findViewById(R.id.btnAstrStop).setOnClickListener(v -> {
            Term.note("$ astr stop");
            sendCommandAsync("astr stop");
        });
        findViewById(R.id.btnPanel).setOnClickListener(v -> openPanel());
        findViewById(R.id.btnCopyLog).setOnClickListener(v -> copyLog());
        findViewById(R.id.btnClearLog).setOnClickListener(v -> LogBus.clear());
        swAutostart.setOnCheckedChangeListener((button, checked) -> prefs.setAutostart(checked));

        findViewById(R.id.btnDlAll).setOnClickListener(v -> startDownload(null));
        buildDlList();

        findViewById(R.id.btnCreds).setOnClickListener(v -> readCreds());
        findViewById(R.id.btnResetPw).setOnClickListener(v -> askResetPassword());
        findViewById(R.id.btnPanelWeb).setOnClickListener(v -> openPanelInApp());

        findViewById(R.id.btnCmdSend).setOnClickListener(v -> sendCommand(value(inpCmd)));
        findViewById(R.id.btnTermStatus).setOnClickListener(v -> {
            Term.note("$ astr status");
            sendCommandAsync("astr status");
        });
        findViewById(R.id.btnTermLog).setOnClickListener(v -> {
            Term.note("$ tail install.log astr.log");
            sendCommand("tail -n 40 ~/.arkdroid/install.log ~/.arkdroid/astr.log 2>/dev/null");
        });
        findViewById(R.id.btnTermClear).setOnClickListener(v -> Term.clear());

        findViewById(R.id.btnQqInstall).setOnClickListener(v -> {
            if (!readyToSend()) {
                return;
            }
            jumpTo(cardQq);
            Term.note("$ bash install.sh --qq-only   # QQ deb + NapCat 注入 + ArkLink");
            toast("开始装本机 QQ（约 250 MB，进度在「终端」里看）");
            NapCat.install(this);
            ui.postDelayed(() -> NapCat.seedOneBotConfig(this), 5000);
        });
        findViewById(R.id.btnQqStart).setOnClickListener(v -> {
            if (!readyToSend()) {
                return;
            }
            jumpTo(cardQq);
            Term.note("$ qq start; arklink start");
            NapCat.start(this);
            toast("QQ（无头）和 ArkLink 正在启动，稍后看状态");
        });
        findViewById(R.id.btnQqWeb).setOnClickListener(v -> openWeb(NapCat.WEBUI_URL, "NapCat WebUI"));
        findViewById(R.id.btnQqFill).setOnClickListener(v -> {
            inpArklink.setText(NapCat.ARKLINK_URL);
            prefs.save(NapCat.ARKLINK_URL, value(inpAstrbot), value(inpSelfId),
                    value(inpArklinkToken), value(inpAstrbotToken));
            BridgeService.restart(this);
            toast("中继已改成连本机 ArkLink：" + NapCat.ARKLINK_URL);
        });

        findViewById(R.id.navQq).setOnClickListener(v -> jumpTo(cardQq));
        findViewById(R.id.navBridge).setOnClickListener(v -> jumpTo(cardBridge));
        findViewById(R.id.navDl).setOnClickListener(v -> jumpTo(cardDl));
        findViewById(R.id.navAstr).setOnClickListener(v -> jumpTo(cardAstr));
        findViewById(R.id.navTerm).setOnClickListener(v -> jumpTo(cardTerm));
        findViewById(R.id.navPanel).setOnClickListener(v -> openPanelInApp());
    }

    // ------------------------------------------------------------------ 状态刷新

    private void refresh() {
        String json = BridgeService.statusJson;
        boolean running = BridgeService.running;
        boolean astrUp = BridgeService.astrOnline;

        chipBridge.setText(running ? "中继 · 运行中" : "中继 · 已停止");
        chipBridge.setTextColor(getColor(running ? R.color.ok : R.color.muted));

        long selfId = BridgeService.selfId(json);
        chipAccount.setText(selfId > 0 ? "账号 · " + selfId : "账号 · 待学习");
        chipAccount.setTextColor(getColor(selfId > 0 ? R.color.text : R.color.muted));

        chipAstr.setText(astrUp ? "AstrBot · 在线" : "AstrBot · 离线");
        chipAstr.setTextColor(getColor(astrUp ? R.color.ok : R.color.muted));

        int[] c = BridgeService.counters(json);
        txtStats.setText("事件 " + c[0] + " · 转发 " + c[1] + " · API " + c[2]
                + " · 重连 " + c[3] + " · 缓冲 " + c[4]);

        String err = BridgeService.error(json);
        if (running) {
            txtBridgeState.setText(astrUp
                    ? "ArkLink ↔ AstrBot 搬运中，AstrBot 已连上"
                    : "ArkLink ↔ AstrBot 搬运中，等 AstrBot 连上来");
            txtBridgeState.setTextColor(getColor(R.color.muted));
        } else if (!err.isEmpty()) {
            txtBridgeState.setText("未启动：" + err);
            txtBridgeState.setTextColor(getColor(R.color.bad));
        } else {
            txtBridgeState.setText("未启动，点「启动」开始搬运");
            txtBridgeState.setTextColor(getColor(R.color.muted));
        }

        if (!Termux.installed(this)) {
            txtAstrState.setText("没装 Termux。AstrBot 依赖的是 glibc 轮子，Android 原生装不上，"
                    + "要跑它得先装下载卡里的 ArkTerm，再回来点「安装 / 更新」。");
            txtAstrState.setTextColor(getColor(R.color.warn));
        } else if (astrUp) {
            txtAstrState.setText("面板在线 · " + PANEL_URL);
            txtAstrState.setTextColor(getColor(R.color.ok));
        } else {
            txtAstrState.setText("Termux 已就绪，面板没响应（还没装完，或者没启动）");
            txtAstrState.setTextColor(getColor(R.color.muted));
        }

        refreshDownload();
        refreshQq();

        // AstrBot 一起来就自己去把面板账号读出来，不用人再点一次「读取账号」
        if (astrUp && !credsAuto) {
            credsAuto = true;
            readCreds();
        }
        // 三个包都齐了，把人带到下一步
        if (!dlDoneNotice && Downloads.allReady(this)) {
            dlDoneNotice = true;
            jumpTo(cardAstr);
            toast("组件都下好了，点「装 Termux」继续");
        }

        // 安装进度条（install.sh 实时推的步骤）
        boolean showProgress = InstallProgress.visible();
        cardProgress.setVisibility(showProgress ? View.VISIBLE : View.GONE);
        if (showProgress) {
            progressInstall.setProgress(InstallProgress.percent());
            txtInstallStep.setText(InstallProgress.line());
            txtInstallHint.setText(InstallProgress.hint());
        }
    }

    private void refreshDownload() {
        boolean ready = Downloads.allReady(this);
        if (Downloads.busy) {
            progressDl.setProgress(Downloads.percent());
            txtDlState.setText(Downloads.fractionText());
            txtDlState.setTextColor(getColor(R.color.brand));
        } else {
            progressDl.setProgress(ready ? 100 : 0);
            java.util.List<Catalog.Item> items = Catalog.items(this);
            int ok = 0;
            for (Catalog.Item it : items) {
                if (it.ready(this)) {
                    ok++;
                }
            }
            txtDlState.setText(ready
                    ? "全部 " + items.size() + " 个组件都下好了。下一步：装 Termux → 点「安装 / 更新」。"
                    : (Downloads.lastError.isEmpty()
                        ? "已就绪 " + ok + "/" + items.size() + " 个组件。点下面的行补下缺的，或点「一键下载全部」。"
                        : "已就绪 " + ok + "/" + items.size() + " 个。\n" + Downloads.lastError));
            txtDlState.setTextColor(getColor(ready ? R.color.ok : R.color.muted));
        }
        // 每个组件一行：✓ 已就绪 / ○ 待下载 xx MB / ⏳ 下载中
        String current = Downloads.busy ? Downloads.label : "";
        for (java.util.Map.Entry<String, TextView> e : dlRows.entrySet()) {
            Catalog.Item it = Catalog.find(this, e.getKey());
            if (it == null) {
                continue;
            }
            String status;
            if (Downloads.busy && it.title.equals(current)) {
                status = "⏳ " + Math.min(100, Downloads.percent()) + "%";
            } else if (it.ready(this)) {
                status = "✓ 已就绪";
            } else {
                status = "○ 待下载 " + it.sizeLabel();
            }
            e.getValue().setText(it.title + "  ·  " + status);
        }
    }

    /** 下载卡的全组件清单：manifest 里有什么就列什么，点行下单个，一眼看出缺没缺。 */
    private void buildDlList() {
        java.util.List<Catalog.Item> items = Catalog.items(this);
        dlList.removeAllViews();
        dlRows.clear();
        int padV = (int) (10 * getResources().getDisplayMetrics().density);
        for (Catalog.Item it : items) {
            TextView row = new TextView(this);
            row.setPadding(0, padV, 0, padV);
            row.setTextSize(13);
            row.setTextColor(getColor(R.color.text));
            row.setText(it.title);
            row.setOnClickListener(v -> startDownload(java.util.Collections.singletonList(it.id)));
            dlList.addView(row);
            dlRows.put(it.id, row);
        }
    }

    // ------------------------------------------------------------------ 动作

    private void saveAndRestart() {
        String arklink = value(inpArklink);
        String astrbot = value(inpAstrbot);
        if (arklink.isEmpty() || astrbot.isEmpty()) {
            toast("ArkLink 和 AstrBot 地址不能空");
            return;
        }
        prefs.save(arklink, astrbot, value(inpSelfId), value(inpArklinkToken), value(inpAstrbotToken));
        BridgeService.restart(this);
        if (arklink.contains("127.0.0.1") || arklink.contains("localhost")) {
            toast(NapCat.webuiUp
                    ? "已保存。连的是手机本机，NapCat 在线，应该能通"
                    : "已保存。但本机现在没有协议端在跑——127.0.0.1 指手机自己，"
                      + "要么先装本机 QQ，要么填电脑的局域网 IP");
        } else {
            toast("已保存，正在重启中继");
        }
    }

    private void runProbe() {
        final File cfg = BridgeService.configFile(this);
        try {
            BridgeService.writeConfig(this, cfg);
        } catch (IOException e) {
            toast("写配置失败：" + e.getMessage());
            return;
        }
        btnCheck.setEnabled(false);
        toast("自检中…");
        bg.execute(() -> {
            final String result = PyEngine.call(this, "probe", cfg.getAbsolutePath());
            ui.post(() -> {
                LogBus.append("—— 自检 ——\n" + result);
                btnCheck.setEnabled(true);
            });
        });
    }

    private void installAstrBot() {
        Catalog.Item apk = Catalog.find(this, "termux");
        if (!Termux.installed(this)) {
            if (apk != null && apk.ready(this)) {
                Termux.installApk(this, apk.target(this));
                toast("装好 Termux 后回来点「安装 / 更新」");
                return;
            }
            showTermuxHelp();
            return;
        }
        if (!hasRunCommandPermission()) {
            requestPermissions(new String[]{PERM_RUN_COMMAND}, 9);
            toast("Termux 刚装好或重装过，允许弹出的「RUN_COMMAND」权限后，再点一次");
            return;
        }
        boolean allReady = Downloads.allReady(this);
        String extra = allReady
                ? "要用的三个包都已经下好，走本机回环递给 Termux，不再走流量。\n\n"
                : "还有包没下：Termux 会自己去镜像下（也能装完，就是慢点，建议先点「一键下载全部」）。\n\n";
        new AlertDialog.Builder(this)
                .setTitle("安装 / 更新 AstrBot")
                .setMessage(extra
                        + "流程：解 Ubuntu 根文件系统到手机（proot，不用 root）→ 装 Python 3.12 + uv → "
                        + "解 AstrBot → 装依赖。总占用约 2.5 GB，挂 WiFi。\n\n"
                        + "装完会注册 astr 命令。Termux 窗口里能看到实时进度，这里在装完后显示完整日志。")
                .setNegativeButton("取消", null)
                .setPositiveButton("开始", (dialog, which) -> {
                    Term.note("=== 开始安装 AstrBot ===");
                    boolean ok = Termux.install(this);
                    toast(ok ? "已交给 Termux，进度看下面终端" : "发送失败，看日志");
                })
                .show();
    }

    // ------------------------------------------------------------------ 内置下载

    private void startDownload(java.util.List<String> ids) {
        if (Downloads.busy) {
            toast("还在下，等这批完");
            return;
        }
        Downloads.start(this, ids);
        toast("开始测速并下载，进度会显示在这张卡上");
    }

    // ------------------------------------------------------------------ 终端 / 账号

    private void sendCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return;
        }
        if (!readyToSend()) {
            return;
        }
        inpCmd.setText("");
        Term.note("$ " + command);
        BridgeService.serve(this);
        if (!Termux.send(this, command, 900)) {
            toastSendFail();
        }
    }

    /** 发命令前统一检查：Termux 在不在 + RUN_COMMAND 权限还在不在（重装 Termux 会被撤销）。 */
    private boolean readyToSend() {
        if (!Termux.installed(this)) {
            showTermuxHelp();
            return false;
        }
        if (!hasRunCommandPermission()) {
            requestPermissions(new String[]{PERM_RUN_COMMAND}, 9);
            toast("Termux 刚装好或重装过，系统把授权撤了——允许弹出的「RUN_COMMAND」权限后，再点一次");
            return false;
        }
        return true;
    }

    private void sendCommandAsync(String command) {
        if (!readyToSend()) {
            return;
        }
        BridgeService.serve(this);
        if (!Termux.astr(this, command.replaceFirst("^astr ", ""))) {
            toastSendFail();
        }
    }

    /** 发命令失败的统一提示：装官方 Termux 的引导去换 ArkTerm。 */
    private void toastSendFail() {
        if (Termux.installed(this) && !Termux.isArkTerm(this)) {
            toast("命令没发出去。装的是官方 Termux（默认拒外部命令），"
                    + "点 Termux 卡的「帮助」按引导换 ArkTerm");
        } else {
            toast("命令没发出去，ArkTerm 可能还没启动过一次，先打开它再回这里");
        }
    }

    private void readCreds() {
        if (!readyToSend()) {
            return;
        }
        Creds.reset();
        txtCreds.setText("正在读 AstrBot 配置…");
        BridgeService.serve(this);
        // 落文件再 POST：管道喂 curl 会先缓冲，这条命令又依赖 $ARKDROID_LOCAL，绕开更稳
        Termux.send(this,
                "mkdir -p ~/.arkdroid"
                        + "; astr creds > ~/.arkdroid/cred.txt 2>&1"
                        + "; curl -sS -m 60 -X POST --data-binary @\"$HOME/.arkdroid/cred.txt\""
                        + " \"$ARKDROID_LOCAL/p/cred\""
                        + "; cat ~/.arkdroid/cred.txt",
                120);
    }

    private void askResetPassword() {
        if (!readyToSend()) {
            return;
        }
        final TextInputEditText input = new TextInputEditText(this);
        input.setHint("新的面板密码（至少 8 位）");
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(this)
                .setTitle("重置 AstrBot 面板密码")
                .setMessage("AstrBot 支持用环境变量重置：会用新密码重启一次，然后写进配置。")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("重置", (dialog, which) -> {
                    String pw = value(input);
                    if (pw.length() < 8) {
                        toast("太短了，至少 8 位");
                        return;
                    }
                    Term.note("=== 重置面板密码 ===");
                    BridgeService.serve(this);
                    Termux.resetPassword(this, pw);
                })
                .show();
    }

    private void sendToTermux(String command) {
        if (!readyToSend()) {
            return;
        }
        boolean ok = Termux.run(this, command, true);
        toast(ok ? "已发给 Termux：" + command : "发送失败，看看 Termux 卡里的帮助说明");
    }

    private void openPanel() {
        openPanelInApp();
    }

    private void refreshQq() {
        TextView tv = findViewById(R.id.txtQqState);
        if (tv == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - NapCat.probedAt > 3000) {
            NapCat.probedAt = now;              // 先占位，避免每 600ms 都压一个探测
            bg.execute(NapCat::probe);
        }
        if (NapCat.arklinkUp && NapCat.webuiUp) {
            tv.setText("链路在线：ArkLink 6700（中继连它）+ NapCat WebUI 6099");
            tv.setTextColor(getColor(R.color.ok));
        } else if (NapCat.webuiUp) {
            tv.setText("NapCat 在线（WebUI 6099），ArkLink 没起来——点「启动」或终端跑 arklink start。\n"
                    + "记得在 WebUI「网络配置」新建 Milky 服务器：127.0.0.1:3000");
            tv.setTextColor(getColor(R.color.warn));
        } else {
            tv.setText("没响应。第一次：先点「装 NapCat」（会一并装 QQ + ArkLink），\n"
                    + "装完点「启动」→「扫码登录」在 WebUI 里登录 QQ 并新建 Milky 服务器 127.0.0.1:3000，\n"
                    + "最后点「填本机地址」，中继走 ws://127.0.0.1:6700/onebot/v11/ws");
            tv.setTextColor(getColor(R.color.muted));
        }
    }

    private void openWeb(String url, String title) {
        try {
            Intent intent = new Intent(this, PanelActivity.class);
            intent.putExtra(PanelActivity.EXTRA_URL, url);
            intent.putExtra(PanelActivity.EXTRA_TITLE, title);
            startActivity(intent);
        } catch (Exception e) {
            toast("打不开内置浏览器：" + e.getMessage());
        }
    }

    private void openPanelInApp() {
        try {
            startActivity(new Intent(this, PanelActivity.class));
        } catch (Exception e) {
            toast("内置浏览器打不开：" + e.getMessage());
        }
    }

    private void copyLog() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText("ArkDroid log", LogBus.text()));
        toast("日志已复制");
    }

    private void requestBatteryWhitelist() {
        try {
            Intent intent = new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                toast("这台机器不支持直接跳转，去系统设置里手动放行");
            }
        }
    }

    private void showTermuxHelp() {
        boolean hasTermux = Termux.installed(this);
        boolean isArk = Termux.isArkTerm(this);
        final Catalog.Item apk = Catalog.find(this, "termux");
        final boolean apkReady = apk != null && apk.ready(this);
        String message;
        String posTitle;
        if (isArk) {
            // ArkTerm 已装：授权内置，什么都不用配
            message = "ArkTerm 已装好，外部应用授权是内置的，\n"
                    + "什么都不用配，直接点「安装 / 更新」或发命令就行。";
            posTitle = "打开 ArkTerm";
        } else if (hasTermux) {
            // 官方 Termux：最省事的路线是卸掉换 ArkTerm
            message = "检测到装的是官方 Termux，它默认不允许外部应用发命令，"
                    + "所以所有命令都会被拒（allow-external-apps 没开）。\n\n"
                    + "最省事的做法：卸载官方 Termux，然后从 App 的「下载」卡装 ArkTerm"
                    + "（我们重打包的 Termux，授权已内置，装上即用零配置）。\n\n"
                    + "或者保留官方 Termux，在它里面执行一次：\n"
                    + "   echo \"allow-external-apps=true\" >> ~/.termux/termux.properties\n"
                    + "然后点 Termux 设置里的 reload（或重启 Termux）。";
            posTitle = apkReady ? "装 ArkTerm" : "下载 ArkTerm";
        } else {
            // 什么都没装：直接引导装 ArkTerm
            message = "AstrBot 的依赖（faiss / pandas / watchfiles 等）只有 glibc 轮子，"
                    + "Android 原生环境装不上，官方也是 Termux + proot Ubuntu 这条路。\n\n"
                    + "推荐装 ArkTerm：我们重打包的 Termux，外部应用授权已内置，"
                    + "装上即用，不用敲任何配置命令。\n\n"
                    + (apkReady
                    ? "ArkTerm 的安装包已经下好了，点下面直接装。"
                    : "点下面的按钮，App 会自己挑最快的镜像下好再拉起安装器，不用去浏览器。");
            posTitle = apkReady ? "开始安装" : "下载 ArkTerm";
        }
        new AlertDialog.Builder(this)
                .setTitle(isArk ? "ArkTerm 已就绪" : (hasTermux ? "官方 Termux 缺授权" : "装 ArkTerm（内置版 Termux）"))
                .setMessage(message)
                .setNegativeButton("知道了", null)
                .setPositiveButton(posTitle, (dialog, which) -> {
                    if (isArk) {
                        Termux.launch(this);
                        return;
                    }
                    if (apkReady) {
                        Termux.installApk(this, apk.target(this));
                    } else {
                        startDownload(java.util.Collections.singletonList("termux"));
                    }
                })
                .setNeutralButton("打开 Termux", (dialog, which) -> Termux.launch(this))
                .show();
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
    }

    /**
     * Termux 的 RUN_COMMAND 是危险权限：卸载重装 Termux 后，本 App 拿到的授权会被系统
     * 静默撤销（日志表现为 SecurityException "without permission
     * com.termux.permission.RUN_COMMAND"）。所以启动时和每次发命令前都查一遍，缺就弹窗。
     */
    static final String PERM_RUN_COMMAND = "com.termux.permission.RUN_COMMAND";

    private boolean hasRunCommandPermission() {
        return checkSelfPermission(PERM_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED;
    }

    private void askRunCommandPermission() {
        if (Termux.installed(this) && !hasRunCommandPermission()) {
            requestPermissions(new String[]{PERM_RUN_COMMAND}, 9);
        }
    }

    // ------------------------------------------------------------------ 小工具

    private static String value(TextInputEditText input) {
        CharSequence text = input.getText();
        return text == null ? "" : text.toString().trim();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
