package ai.panxian.arkdroid;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/** 进程内的环形日志，UI 与通知都从这里取。 */
final class LogBus {

    private static final int MAX_LINES = 1200;
    private static final Deque<String> LINES = new ArrayDeque<>();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static final SimpleDateFormat CLOCK = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private LogBus() {
    }

    static void append(String text) {
        if (text == null) {
            return;
        }
        String trimmed = text.replace("\r", "");
        if (trimmed.isEmpty()) {
            return;
        }
        synchronized (LINES) {
            for (String line : trimmed.split("\n", -1)) {
                if (line.isEmpty()) {
                    continue;
                }
                LINES.addLast(CLOCK.format(new Date()) + "  " + line);
            }
            while (LINES.size() > MAX_LINES) {
                LINES.removeFirst();
            }
        }
        fire();
    }

    static String text() {
        synchronized (LINES) {
            if (LINES.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder(LINES.size() * 48);
            for (String line : LINES) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    static void clear() {
        synchronized (LINES) {
            LINES.clear();
        }
        fire();
    }

    static void addListener(Runnable listener) {
        if (!LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
        }
    }

    static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    private static void fire() {
        // append 可能来自后台线程（proot 输出、本地服务等），listener 会碰 UI，
        // 必须切回主线程——否则 CalledFromWrongThreadException 被下面吞掉，日志不上屏
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            for (Runnable listener : new ArrayList<>(LISTENERS)) {
                try {
                    listener.run();
                } catch (RuntimeException ignored) {
                    // UI 挂了不该影响日志写入
                }
            }
        });
    }
}
