package ai.panxian.arkdroid;

import android.content.Context;
import android.content.SharedPreferences;

/** 极简配置存取，最终落成一份 bridge.toml 交给 Python 侧。 */
final class Prefs {

    static final String DEF_ARKLINK = "ws://127.0.0.1:6700/onebot/v11/ws";
    static final String DEF_ASTRBOT = "ws://127.0.0.1:6199/ws";

    private static final String FILE = "arkdroid";

    private final SharedPreferences sp;

    Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    String arklink() { return sp.getString("arklink", DEF_ARKLINK); }

    String astrbot() { return sp.getString("astrbot", DEF_ASTRBOT); }

    String selfId() { return sp.getString("self_id", ""); }

    String arklinkToken() { return sp.getString("arklink_token", ""); }

    String astrbotToken() { return sp.getString("astrbot_token", ""); }

    boolean autostart() { return sp.getBoolean("autostart", true); }

    void setAutostart(boolean value) { sp.edit().putBoolean("autostart", value).apply(); }

    /** 本地文件服务的路径口令：别的 App 猜不到，外网也连不上（只绑回环）。 */
    String localToken() {
        String token = sp.getString("local_token", "");
        if (token == null || token.isEmpty()) {
            token = LocalServer.newToken();
            sp.edit().putString("local_token", token).apply();
        }
        return token;
    }

    void save(String arklink, String astrbot, String selfId, String arklinkToken, String astrbotToken) {
        sp.edit()
                .putString("arklink", arklink.trim())
                .putString("astrbot", astrbot.trim())
                .putString("self_id", selfId.trim())
                .putString("arklink_token", arklinkToken.trim())
                .putString("astrbot_token", astrbotToken.trim())
                .apply();
    }

    /** 生成 arkbridge 认的 TOML。空字段直接不写，交给 Python 侧用默认值。 */
    String toToml() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 由 ArkDroid 生成；在 App 里改比改这个文件省事\n");
        sb.append("[[bridge]]\n");
        sb.append("name = \"main\"\n");
        sb.append("arklink = \"").append(esc(arklink())).append("\"\n");
        sb.append("astrbot = \"").append(esc(astrbot())).append("\"\n");

        long sid = 0;
        try {
            sid = Long.parseLong(selfId().trim());
        } catch (Exception ignored) {
            // 留空或写了非数字都当 0：让 Python 侧自己去问 ArkLink
        }
        sb.append("self_id = ").append(Math.max(sid, 0)).append("\n");

        if (!arklinkToken().isEmpty()) {
            sb.append("arklink_token = \"").append(esc(arklinkToken())).append("\"\n");
        }
        if (!astrbotToken().isEmpty()) {
            sb.append("astrbot_token = \"").append(esc(astrbotToken())).append("\"\n");
        }
        sb.append("\n[log]\nlevel = \"INFO\"\n");
        return sb.toString();
    }

    private static String esc(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
