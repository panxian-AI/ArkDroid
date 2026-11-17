package ai.panxian.arkdroid;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 组件清单：谁要下载、从哪几个镜像下、下多大、校验和多少。
 *
 * <p>清单随 APK 带一份（assets/manifest.json），同时会尝试从线上拉更新的版本存到
 * filesDir —— 镜像挂了或者版本换了，改清单就行，不用重发 APK。
 */
final class Catalog {

    /** 清单本身也走多镜像：GitHub 直连在国内经常不通。 */
    private static final String[] MANIFEST_MIRRORS = {
            "https://ghfast.top/https://raw.githubusercontent.com/panxian-AI/ArkDroid/main/dist/manifest.json",
            "https://gh-proxy.com/https://raw.githubusercontent.com/panxian-AI/ArkDroid/main/dist/manifest.json",
            "https://raw.githubusercontent.com/panxian-AI/ArkDroid/main/dist/manifest.json",
    };

    static final class Item {
        String id;
        String title;
        String desc;
        String fileName;
        String kind;
        long size;
        String sha256;
        final List<String> mirrors = new ArrayList<>();

        File target(Context ctx) {
            return new File(dir(ctx), fileName);
        }

        boolean ready(Context ctx) {
            File f = target(ctx);
            if (!f.exists() || f.length() == 0) {
                return false;
            }
            return size <= 0 || f.length() == size;
        }

        String sizeLabel() {
            return size > 0 ? Downloader.humanSize(size) : "大小未知";
        }
    }

    private Catalog() {
    }

    static File dir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "dl");
        if (!dir.exists() && !dir.mkdirs()) {
            LogBus.append("建不了下载目录：" + dir);
        }
        return dir;
    }

    /** 有线上清单就用线上的，否则用 APK 里带的那份。 */
    static List<Item> items(Context ctx) {
        File cached = new File(ctx.getFilesDir(), "manifest.json");
        if (cached.exists()) {
            try {
                return parse(new String(readAll(new FileInputStream(cached)), StandardCharsets.UTF_8));
            } catch (Exception e) {
                LogBus.append("本地清单读坏了，改用内置的：" + e.getMessage());
            }
        }
        try (InputStream in = ctx.getAssets().open("manifest.json")) {
            return parse(new String(readAll(in), StandardCharsets.UTF_8));
        } catch (IOException e) {
            LogBus.append("内置清单读不到：" + e.getMessage());
            return new ArrayList<>();
        }
    }

    static Item find(Context ctx, String id) {
        for (Item it : items(ctx)) {
            if (it.id.equals(id)) {
                return it;
            }
        }
        return null;
    }

    /** 后台调用：拉一次线上清单，比本地新才落盘。失败就算了，不影响用内置的。 */
    static void refresh(Context ctx) {
        int localVersion = version(ctx);
        for (String url : MANIFEST_MIRRORS) {
            try {
                String text = httpGet(url, 6000);
                JSONObject root = new JSONObject(text);
                int remote = root.optInt("version", 0);
                List<Item> parsed = parse(text);
                if (parsed.isEmpty()) {
                    continue;
                }
                if (remote <= localVersion) {
                    return;
                }
                try (FileOutputStream out = new FileOutputStream(new File(ctx.getFilesDir(), "manifest.json"))) {
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                }
                LogBus.append("组件清单已更新到 v" + remote + "（" + Downloader.host(url) + "）");
                return;
            } catch (Exception ignored) {
                // 换个镜像继续
            }
        }
    }

    private static int version(Context ctx) {
        File cached = new File(ctx.getFilesDir(), "manifest.json");
        try {
            String text = cached.exists()
                    ? new String(readAll(new FileInputStream(cached)), StandardCharsets.UTF_8)
                    : new String(readAll(ctx.getAssets().open("manifest.json")), StandardCharsets.UTF_8);
            return new JSONObject(text).optInt("version", 0);
        } catch (Exception e) {
            return 0;
        }
    }

    static List<Item> parse(String json) throws IOException {
        List<Item> out = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(json);
            JSONArray arr = root.optJSONArray("components");
            if (arr == null) {
                return out;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Item it = new Item();
                it.id = o.optString("id", "");
                it.title = o.optString("title", it.id);
                it.desc = o.optString("desc", "");
                it.fileName = o.optString("file", it.id);
                it.kind = o.optString("kind", "file");
                it.size = o.optLong("size", 0);
                it.sha256 = o.optString("sha256", "");
                JSONArray m = o.optJSONArray("mirrors");
                if (m != null) {
                    for (int j = 0; j < m.length(); j++) {
                        String u = m.optString(j, "");
                        if (!u.isEmpty()) {
                            it.mirrors.add(u);
                        }
                    }
                }
                if (!it.id.isEmpty() && !it.mirrors.isEmpty()) {
                    out.add(it);
                }
            }
        } catch (org.json.JSONException e) {
            throw new IOException("清单格式不对：" + e.getMessage(), e);
        }
        return out;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream src = in) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = src.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            return buf.toByteArray();
        }
    }

    private static String httpGet(String url, int timeoutMs) throws IOException {
        java.net.HttpURLConnection conn =
                (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "ArkDroid/0.2");
        try {
            if (conn.getResponseCode() / 100 != 2) {
                throw new IOException("HTTP " + conn.getResponseCode());
            }
            return new String(readAll(conn.getInputStream()), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }
}
