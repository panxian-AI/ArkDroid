package ai.panxian.arkdroid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 常驻服务：把 ArkBridge 的 Python 中继跑在 App 进程里，并对外暴露一份实时状态。
 */
public class BridgeService extends Service {

    static final String ACTION_START = "ai.panxian.arkdroid.action.START";
    static final String ACTION_STOP = "ai.panxian.arkdroid.action.STOP";
    static final String ACTION_RESTART = "ai.panxian.arkdroid.action.RESTART";
    /** 只保活 + 起本地文件服务，不碰 Python 中继（安装 AstrBot 时用）。 */
    static final String ACTION_SERVE = "ai.panxian.arkdroid.action.SERVE";

    private static final String CHANNEL = "arkdroid-run";
    private static final int NOTIF_ID = 1001;

    static volatile String statusJson = "{}";
    static volatile boolean running = false;
    static volatile boolean astrOnline = false;
    static volatile boolean arkOnline = false;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExecutorService worker;
    private boolean polling;
    private volatile boolean pythonWanted;
    private String lastNotification = "";

    @Override
    public void onCreate() {
        super.onCreate();
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "arkdroid-py");
            thread.setDaemon(true);
            return thread;
        });
        ensureChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null || intent.getAction() == null
                ? ACTION_START : intent.getAction();
        startForeground(NOTIF_ID, buildNotification());
        switch (action) {
            case ACTION_STOP:
                doStop();
                break;
            case ACTION_RESTART:
                doRestart();
                break;
            case ACTION_SERVE:
                pythonWanted = false;
                ensureServe();
                break;
            case ACTION_START:
            default:
                pythonWanted = true;
                doStart();
                break;
        }
        startPolling();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        polling = false;
        if (worker != null) {
            worker.shutdownNow();
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 动作

    private void doStart() {
        if (running) {
            LogBus.append("中继已经在跑了，没重复启动（要改配置用「保存并重启」）");
            return;
        }
        final File cfg = configFile(this);
        try {
            writeConfig(this, cfg);
        } catch (IOException e) {
            LogBus.append("写配置失败：" + e.getMessage());
            return;
        }
        LogBus.append("正在启动中继 → " + new Prefs(this).arklink());
        worker.execute(() -> {
            String msg = PyEngine.call(this, "start", cfg.getAbsolutePath());
            if (!msg.isEmpty()) {
                LogBus.append(msg);
            }
            refreshNotification();
        });
    }

    private void doStop() {
        worker.execute(() -> {
            String msg = PyEngine.call(this, "stop", null);
            if (!msg.isEmpty()) {
                LogBus.append(msg);
            }
            refreshNotification();
        });
    }

    private void doRestart() {
        worker.execute(() -> {
            PyEngine.call(this, "stop", null);
            try {
                Thread.sleep(400);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            doStart();
        });
    }

    /** 起本地文件服务，让 Termux 能来取包 —— 进程被回收的话 Termux 那边就拉不到了。 */
    private void ensureServe() {
        worker.execute(() -> {
            try {
                String url = LocalServer.ensure(this);
                LogBus.append("本地文件服务在跑：" + url);
            } catch (IOException e) {
                LogBus.append("本地文件服务起不来：" + e.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------ 轮询

    private void startPolling() {
        if (polling) {
            return;
        }
        polling = true;
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                pollOnce();
                ui.postDelayed(this, 900);
            }
        }, 300);
    }

    private void pollOnce() {
        worker.execute(() -> {
            if (pythonWanted) {
                String logs = PyEngine.call(BridgeService.this, "drain", null);
                if (logs != null && !logs.trim().isEmpty()) {
                    LogBus.append(logs);
                }
                String json = PyEngine.call(BridgeService.this, "stats", null);
                if (json != null && json.startsWith("{")) {
                    statusJson = json;
                    running = parseRunning(json);
                }
            }
            astrOnline = portOpen(6185);
            arkOnline = portOpen(6700);
            refreshNotification();
        });
    }

    private static boolean parseRunning(String json) {
        try {
            return new JSONObject(json).optBoolean("running", false);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean portOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 350);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 通知

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    private String describeState() {
        if (running) {
            return astrOnline ? "中继运行中 · AstrBot 在线" : "中继运行中 · 等 AstrBot 连上来";
        }
        if (!pythonWanted) {
            return "本地服务就绪 · 等 Termux 来取包";
        }
        return "中继已停止";
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(
                this, 0, open,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        : PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_status)
                .setContentTitle("ArkDroid")
                .setContentText(describeState())
                .setContentIntent(pending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void refreshNotification() {
        String state = describeState();
        if (state.equals(lastNotification)) {
            return;
        }
        lastNotification = state;
        ui.post(() -> {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIF_ID, buildNotification());
            }
        });
    }

    // ------------------------------------------------------------------ 配置

    static File configFile(Context ctx) {
        return new File(ctx.getFilesDir(), "bridge.toml");
    }

    static void writeConfig(Context ctx, File target) throws IOException {
        String text = new Prefs(ctx).toToml();
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    // ------------------------------------------------------------------ 供 UI 调用

    static void start(Context ctx) {
        Intent intent = new Intent(ctx, BridgeService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent);
        } else {
            ctx.startService(intent);
        }
    }

    static void stop(Context ctx) {
        Intent intent = new Intent(ctx, BridgeService.class).setAction(ACTION_STOP);
        ctx.startService(intent);
    }

    static void restart(Context ctx) {
        Intent intent = new Intent(ctx, BridgeService.class).setAction(ACTION_RESTART);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent);
        } else {
            ctx.startService(intent);
        }
    }

    /** 只为“别被系统杀掉 + 本地服务在跑”，不启动中继。 */
    static void serve(Context ctx) {
        Intent intent = new Intent(ctx, BridgeService.class).setAction(ACTION_SERVE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent);
        } else {
            ctx.startService(intent);
        }
    }

    /** 从状态 JSON 里取第一条桥的关键数字，UI 用。 */
    static int[] counters(String json) {
        // events, forwarded, api, reconnects, buffered
        int[] out = new int[]{0, 0, 0, 0, 0};
        try {
            JSONObject root = new JSONObject(json);
            JSONArray bridges = root.optJSONArray("bridges");
            if (bridges == null || bridges.length() == 0) {
                return out;
            }
            JSONObject stats = bridges.getJSONObject(0).optJSONObject("stats");
            if (stats == null) {
                return out;
            }
            out[0] = stats.optInt("events");
            out[1] = stats.optInt("forwarded");
            out[2] = stats.optInt("api_calls");
            out[3] = stats.optInt("reconnects");
            out[4] = stats.optInt("buffered");
        } catch (Exception ignored) {
            // 状态还没准备好就显示 0
        }
        return out;
    }

    static long selfId(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONArray bridges = root.optJSONArray("bridges");
            if (bridges == null || bridges.length() == 0) {
                return 0;
            }
            return bridges.getJSONObject(0).optLong("self_id");
        } catch (Exception e) {
            return 0;
        }
    }

    static String error(String json) {
        try {
            return new JSONObject(json).optString("error", "");
        } catch (Exception e) {
            return "";
        }
    }
}
