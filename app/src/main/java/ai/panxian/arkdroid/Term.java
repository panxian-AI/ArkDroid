package ai.panxian.arkdroid;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 终端回显：Termux 那边跑的命令，输出经回环服务推回来，攒在这里。 */
final class Term {

    private static final int MAX_LINES = 1500;
    private static final Deque<String> LINES = new ArrayDeque<>();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private Term() {
    }

    static void append(String text) {
        if (text == null) {
            return;
        }
        String cleaned = text.replace("\r\n", "\n").replace("\r", "\n");
        boolean changed = false;
        synchronized (LINES) {
            for (String line : cleaned.split("\n", -1)) {
                if (line.isEmpty()) {
                    continue;
                }
                LINES.addLast(line);
                changed = true;
            }
            while (LINES.size() > MAX_LINES) {
                LINES.removeFirst();
            }
        }
        if (changed) {
            fire();
        }
    }

    /** App 这边的提示也进同一个窗口，用户看到的是“一条时间线”。 */
    static void note(String text) {
        append("· " + text);
    }

    static String text() {
        synchronized (LINES) {
            StringBuilder sb = new StringBuilder(LINES.size() * 40);
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
        for (Runnable listener : new ArrayList<>(LISTENERS)) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // UI 出问题不该拖累终端
            }
        }
    }
}
