package ai.panxian.arkdroid;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * AstrBot 面板的账号信息。
 *
 * <p>AstrBot 4.30 起面板密码是 pbkdf2 哈希存的，读不出明文；只有“还没设过”或
 * “旧版明文存”的情况能直接显示。所以这里除了账号密码，还记三件事：
 * 有没有哈希、是不是首次登录、能不能直接进。
 */
final class Creds {

    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    static volatile String user = "";
    static volatile String pass = "";
    static volatile boolean hashed;
    static volatile boolean firstRun;
    static volatile boolean known;   // App 记录的密码验证过是当前密码
    static volatile boolean chgRequired; // 面板要求登录后再改一次密码
    static volatile boolean present;
    static volatile long updatedAt;

    private Creds() {
    }

    /** 解析 {@code astr creds} 打回来的 KEY=VALUE 行。 */
    static void absorb(String body) {
        for (String raw : body.split("\n")) {
            String line = raw.trim();
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim().toUpperCase(java.util.Locale.US);
            String value = line.substring(eq + 1).trim();
            switch (key) {
                case "USER":
                    user = value;
                    break;
                case "PASS":
                    pass = value;
                    break;
                case "HASHED":
                    hashed = "1".equals(value) || "true".equalsIgnoreCase(value);
                    break;
                case "KNOWN":
                    known = "1".equals(value) || "true".equalsIgnoreCase(value);
                    break;
                case "CHG":
                    chgRequired = "1".equals(value) || "true".equalsIgnoreCase(value);
                    break;
                case "FIRST_RUN":
                    firstRun = "1".equals(value) || "true".equalsIgnoreCase(value);
                    break;
                case "OK":
                    present = "1".equals(value) || "true".equalsIgnoreCase(value);
                    break;
                default:
                    break;
            }
        }
        updatedAt = System.currentTimeMillis();
        present = present || !user.isEmpty();
        fire();
    }

    static void reset() {
        user = "";
        pass = "";
        hashed = false;
        firstRun = false;
        known = false;
        chgRequired = false;
        present = false;
        fire();
    }

    /** 给界面用的一句话。 */
    static String summary() {
        if (!present) {
            if (firstRun) {
                return "AstrBot 还没初始化：点「启动」让它生成配置，再点一次「读取账号」";
            }
            return "还没读到（点「读取账号」，AstrBot 得先装好）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("账号 ").append(user.isEmpty() ? "astrbot" : user);
        if (!pass.isEmpty()) {
            sb.append(" · 密码 ").append(pass);
            sb.append(known ? "（App 记录，验证可用）" : "");
            if (chgRequired) {
                sb.append("\n面板会要求登录后再改一次密码——改完这里就看不到新密码了，"
                        + "忘了随时点「重置面板密码」");
            }
        } else if (firstRun) {
            sb.append(" · 密码还没设过：第一次打开面板会让你设一个");
        } else if (hashed) {
            sb.append("\n密码已加密保存（看不回明文）。忘了就点「重置面板密码」："
                    + "会生成一个新密码并显示在这里，用它能直接登录");
        } else {
            sb.append(" · 密码为空");
        }
        return sb.toString();
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
                // 界面异常不影响数据
            }
        }
    }
}
