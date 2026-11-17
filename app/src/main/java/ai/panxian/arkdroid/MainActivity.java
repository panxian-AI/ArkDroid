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
    private boolean welcomed;

    private final Runnable termRefresh = new Runnable() {
        @Override
        public void run() {
            if (txtTerm == null) {
                return;
            }
            String text = Term.text();
            if (!text.contentEquals(txtTerm.getText())) {
                txtTerm.setText(text);
                termScroll.post(() -> termScroll.fullScroll(View.FOCUS_DOWN));
            }
        }
    };

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
                txtLog.setText(text);
                logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
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
        askNotificationPermission();

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
        findViewById(R.id.btnDlTermux).setOnClickListener(v -> startDownload(java.util.Collections.singletonList("termux")));
        findViewById(R.id.btnDlRootfs).setOnClickListener(v -> startDownload(java.util.Collections.singletonList("rootfs")));
        findViewById(R.id.btnDlAstrbot).setOnClickListener(v -> startDownload(java.util.Collections.singletonList("astrbot")));

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
                    + "要跑它得先装 Termux（F-Droid 版），再回来点「安装 / 更新」。");
            txtAstrState.setTextColor(getColor(R.color.warn));
        } else if (astrUp) {
            txtAstrState.setText("面板在线 · " + PANEL_URL);
            txtAstrState.setTextColor(getColor(R.color.ok));
        } else {
            txtAstrState.setText("Termux 已就绪，面板没响应（还没装完，或者没启动）");
            txtAstrState.setTextColor(getColor(R.color.muted));
        }

        refreshDownload();
    }

    private void refreshDownload() {
        boolean ready = Downloads.allReady(this);
        if (Downloads.busy) {
            progressDl.setProgress(Downloads.percent());
            txtDlState.setText(Downloads.fractionText());
            txtDlState.setTextColor(getColor(R.color.brand));
            return;
        }
        progressDl.setProgress(ready ? 100 : 0);
        txtDlState.setText(ready
                ? "三个包都下好了（Termux / Ubuntu / AstrBot）。下一步：装 Termux → 点「安装 / 更新」。"
                : Downloads.describe(this) + (Downloads.lastError.isEmpty() ? "" : "\n" + Downloads.lastError));
        txtDlState.setTextColor(getColor(ready ? R.color.ok : R.color.muted));
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
        toast("已保存，正在重启中继");
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
        if (!Termux.installed(this)) {
            showTermuxHelp();
            return;
        }
        inpCmd.setText("");
        Term.note("$ " + command);
        BridgeService.serve(this);
        if (!Termux.send(this, command, 900)) {
            toast("发不出去，检查 Termux 的 allow-external-apps");
        }
    }

    private void sendCommandAsync(String command) {
        if (!Termux.installed(this)) {
            showTermuxHelp();
            return;
        }
        BridgeService.serve(this);
        if (!Termux.astr(this, command.replaceFirst("^astr ", ""))) {
            toast("发不出去，检查 Termux 的 allow-external-apps");
        }
    }

    private void readCreds() {
        if (!Termux.installed(this)) {
            showTermuxHelp();
            return;
        }
        Creds.reset();
        txtCreds.setText("正在读 AstrBot 配置…");
        BridgeService.serve(this);
        Termux.send(this, "astr creds | curl -sS -m 60 -X POST --data-binary @- "
                + "\"$ARKDROID_LOCAL/p/cred\"", 120);
    }

    private void askResetPassword() {
        if (!Termux.installed(this)) {
            showTermuxHelp();
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
        if (!Termux.installed(this)) {
            showTermuxHelp();
            return;
        }
        boolean ok = Termux.run(this, command, true);
        toast(ok ? "已发给 Termux：" + command : "发送失败，Termux 可能没开 allow-external-apps");
    }

    private void openPanel() {
        openPanelInApp();
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
        final Catalog.Item apk = Catalog.find(this, "termux");
        final boolean apkReady = apk != null && apk.ready(this);
        String message = hasTermux
                ? "Termux 有了，但还差一步授权：\n\n"
                + "在 Termux 里执行一次（只需一次）：\n"
                + "   echo \"allow-external-apps=true\" >> ~/.termux/termux.properties\n\n"
                + "然后回到这里点「安装 / 更新」。"
                : "AstrBot 的依赖（faiss / pandas / watchfiles 等）只有 glibc 轮子，"
                + "Android 原生环境装不上，官方也是 Termux + proot Ubuntu 这条路。\n\n"
                + (apkReady
                ? "Termux 的安装包已经下好了，点下面直接装。"
                : "点「下载 Termux」，App 会自己挑最快的镜像下好再拉起安装器，不用去浏览器。");
        new AlertDialog.Builder(this)
                .setTitle(hasTermux ? "Termux 还需要授权" : "需要 Termux")
                .setMessage(message)
                .setNegativeButton("知道了", null)
                .setPositiveButton(hasTermux ? "打开 Termux" : (apkReady ? "开始安装" : "下载 Termux"),
                        (dialog, which) -> {
                            if (hasTermux) {
                                Termux.launch(this);
                                return;
                            }
                            if (apkReady) {
                                Termux.installApk(this, apk.target(this));
                            } else {
                                startDownload(java.util.Collections.singletonList("termux"));
                            }
                        })
                .setNeutralButton("复制配置命令", (dialog, which) -> {
                    ClipboardManager cm =
                            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("termux",
                                "echo \"allow-external-apps=true\" >> ~/.termux/termux.properties"));
                        toast("已复制，粘到 Termux 里执行");
                    }
                })
                .show();
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
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
