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
    private boolean welcomed;

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
            ui.postDelayed(this, 1000);
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

        if (!welcomed) {
            welcomed = true;
            LogBus.append("ArkDroid 已就绪。中继直接跑在本 App 里（Chaquopy + ArkBridge）。");
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
        ui.post(ticker);
    }

    @Override
    protected void onStop() {
        super.onStop();
        LogBus.removeListener(logRefresh);
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
        findViewById(R.id.btnAstrStart).setOnClickListener(v -> sendToTermux(Termux.START_CMD));
        findViewById(R.id.btnAstrStop).setOnClickListener(v -> sendToTermux(Termux.STOP_CMD));
        findViewById(R.id.btnPanel).setOnClickListener(v -> openPanel());
        findViewById(R.id.btnCopyLog).setOnClickListener(v -> copyLog());
        findViewById(R.id.btnClearLog).setOnClickListener(v -> LogBus.clear());
        swAutostart.setOnCheckedChangeListener((button, checked) -> prefs.setAutostart(checked));
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
        if (!Termux.installed(this)) {
            showTermuxHelp();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("安装 / 更新 AstrBot")
                .setMessage("会在 Termux 里建一个 Ubuntu（proot），装 Python 3.12 + uv，"
                        + "再拉 AstrBot 并装依赖。首次几百 MB，挂 WiFi。\n\n"
                        + "装完会自动注册 astr 命令（start/stop/status/log）。")
                .setNegativeButton("取消", null)
                .setPositiveButton("开始", (dialog, which) -> {
                    boolean ok = Termux.run(this, Termux.INSTALL_CMD, false);
                    toast(ok ? "已交给 Termux，切过去能看到进度" : "发送失败，看看日志");
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
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(PANEL_URL)));
        } catch (ActivityNotFoundException e) {
            toast("没有浏览器能打开 " + PANEL_URL);
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
        new AlertDialog.Builder(this)
                .setTitle(hasTermux ? "Termux 还需要授权" : "需要 Termux")
                .setMessage("AstrBot 的依赖（faiss / pandas / watchfiles 等）只有 glibc 轮子，"
                        + "Android 原生环境里装不上，官方也是走 Termux + proot Ubuntu。\n\n"
                        + "1. 装 Termux（推荐 F-Droid 版，别用 Play 上的旧版）\n"
                        + "2. 在 Termux 里执行：\n"
                        + "   echo \"allow-external-apps=true\" >> ~/.termux/termux.properties\n"
                        + "3. 回来点「安装 / 更新」\n\n"
                        + "只想让手机当中继（AstrBot 在电脑/服务器上）的话，不用装 Termux。")
                .setNegativeButton("知道了", null)
                .setPositiveButton(hasTermux ? "打开 Termux" : "去 F-Droid", (dialog, which) -> {
                    if (Termux.installed(this)) {
                        Termux.launch(this);
                        return;
                    }
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://f-droid.org/packages/com.termux/")));
                    } catch (ActivityNotFoundException e) {
                        toast("没有浏览器，手动搜 Termux 吧");
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
