package ai.panxian.arkdroid;

import android.content.Context;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * 只监听 127.0.0.1 的小文件服务：把 App 下好的包和内置脚本递给 Termux。
 *
 * <p>为什么绕这一圈：Termux 读不到 App 的私有目录（/data/data/ai.panxian.arkdroid），
 * 但能 curl 本机回环。这样“下载”这件事只发生在 App 里（带进度、能续传、会挑镜像），
 * Termux 那边不用再碰 GitHub。
 *
 * <p>路径都带一段随机 token，别的 App 猜不到；服务只绑回环，外网连不上。
 */
final class LocalServer {

    private static final int[] PORTS = {18787, 18788, 18789, 18797, 18887};
    private static final SecureRandom RANDOM = new SecureRandom();

    private static LocalServer instance;

    private final Context app;
    private final String token;
    private ServerSocket server;
    private int port;
    private volatile boolean running;

    private LocalServer(Context ctx, String token) {
        this.app = ctx.getApplicationContext();
        this.token = token;
    }

    // ------------------------------------------------------------------ 生命周期

    /** 起服务（幂等），返回带 token 的根地址，例如 http://127.0.0.1:18787/t/ab12cd。 */
    static synchronized String ensure(Context ctx) throws IOException {
        if (instance != null && instance.running) {
            return instance.baseUrl();
        }
        String token = new Prefs(ctx).localToken();
        LocalServer s = new LocalServer(ctx, token);
        s.start();
        instance = s;
        return s.baseUrl();
    }

    /** 已经起过就返回地址，没起返回 null（不顺手起一个）。 */
    static synchronized String baseUrlOrNull() {
        return instance != null && instance.running ? instance.baseUrl() : null;
    }

    static synchronized void stop() {
        if (instance != null) {
            instance.shutdown();
            instance = null;
        }
    }

    static String newToken() {
        byte[] raw = new byte[8];
        RANDOM.nextBytes(raw);
        return Downloader.hex(raw);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port + "/t/" + token;
    }

    private void start() throws IOException {
        IOException last = null;
        for (int candidate : PORTS) {
            try {
                server = new ServerSocket(candidate, 16, InetAddress.getByName("127.0.0.1"));
                port = candidate;
                break;
            } catch (IOException e) {
                last = e;
            }
        }
        if (server == null) {
            throw last == null ? new IOException("端口都被占了") : last;
        }
        running = true;
        Thread loop = new Thread(this::acceptLoop, "arkdroid-local");
        loop.setDaemon(true);
        loop.start();
        LogBus.append("本地文件服务已就绪：" + baseUrl() + "（只给 Termux 用，外网连不上）");
    }

    private void shutdown() {
        running = false;
        try {
            if (server != null) {
                server.close();
            }
        } catch (IOException ignored) {
            // 关就关了
        }
        server = null;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = server.accept();
                Thread t = new Thread(() -> handle(client), "arkdroid-http");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) {
                    LogBus.append("本地服务 accept 出错：" + e.getMessage());
                }
                return;
            }
        }
    }

    // ------------------------------------------------------------------ 路由

    private void handle(Socket client) {
        try (Socket socket = client) {
            socket.setSoTimeout(30000);
            InputStream in = socket.getInputStream();
            HttpWire.Head head = HttpWire.readHead(in);
            if (head == null) {
                return;
            }
            if (head.expectContinue) {
                // curl 发大 body 前会先问一句，不回它就一直等
                OutputStream out = socket.getOutputStream();
                out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }

            String rest = head.path.startsWith("/t/" + token + "/")
                    ? head.path.substring(("/t/" + token + "/").length())
                    : null;
            if (rest == null) {
                respond(socket, 403, "text/plain", "forbidden".getBytes(StandardCharsets.UTF_8), null, 0);
                return;
            }
            if ("POST".equals(head.method) && rest.startsWith("p/ubuntu")) {
                // 安装器/控制脚本把「要在 Ubuntu 里跑的命令」POST 过来，由 App 起平级
                // proot 进程执行（外层 Termux 环境本身已是 proot，不能再套一层）
                byte[] body = HttpWire.readBody(in, head);
                String command = new String(body, StandardCharsets.UTF_8);
                String reply;
                if (rest.startsWith("p/ubuntu-bg")) {
                    File log = guestPath(queryParam(head.path, "log"));
                    if (log == null) {
                        log = new File(Catalog.dir(app), "ubuntu-bg.log");
                    }
                    int pid = TermuxRuntime.startUbuntuBg(app, command, log);
                    reply = "pid=" + pid;
                } else {
                    reply = "exit=" + TermuxRuntime.execUbuntu(app, command);
                }
                byte[] payload = reply.getBytes(StandardCharsets.UTF_8);
                respond(socket, 200, "text/plain", payload, null, payload.length);
                return;
            }
            if ("POST".equals(head.method) && rest.startsWith("p/")) {
                byte[] body = HttpWire.readBody(in, head);
                Push.handle(rest.substring(2), new String(body, StandardCharsets.UTF_8));
                respond(socket, 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8), null, 0);
                return;
            }
            if (!"GET".equals(head.method) && !"HEAD".equals(head.method)) {
                respond(socket, 405, "text/plain", "method".getBytes(StandardCharsets.UTF_8), null, 0);
                return;
            }
            if (rest.equals("health")) {
                respond(socket, 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8), null, 0);
                return;
            }
            if (rest.startsWith("a/")) {
                serveAsset(socket, rest.substring(2), "HEAD".equals(head.method));
                return;
            }
            if (rest.startsWith("f/")) {
                serveFile(socket, rest.substring(2), "HEAD".equals(head.method));
                return;
            }
            respond(socket, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8), null, 0);
        } catch (Exception e) {
            // 客户端断开是常态（curl 拿完就走），不记日志免得刷屏
        }
    }

    private void serveAsset(Socket socket, String name, boolean headOnly) throws IOException {
        if (name.contains("..")) {
            respond(socket, 400, "text/plain", "bad".getBytes(StandardCharsets.UTF_8), null, 0);
            return;
        }
        InputStream in;
        try {
            in = app.getAssets().open(name);
        } catch (IOException e) {
            respond(socket, 404, "text/plain", ("没有 " + name).getBytes(StandardCharsets.UTF_8), null, 0);
            return;
        }
        byte[] body = readAll(in);
        respond(socket, 200, contentType(name), body, null, headOnly ? 0 : body.length);
    }

    private void serveFile(Socket socket, String name, boolean headOnly) throws IOException {
        if (name.contains("..") || name.contains("/")) {
            respond(socket, 400, "text/plain", "bad".getBytes(StandardCharsets.UTF_8), null, 0);
            return;
        }
        File file = new File(Catalog.dir(app), name);
        if (!file.isFile()) {
            respond(socket, 404, "text/plain",
                    ("还没下好：" + name).getBytes(StandardCharsets.UTF_8), null, 0);
            return;
        }
        long total = file.length();

        // 回环 + 同一台机器，不需要断点续传：一次推完更省事
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + contentType(name) + "\r\n"
                + "Content-Length: " + total + "\r\n"
                + "Accept-Ranges: none\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        if (headOnly) {
            out.flush();
            return;
        }
        try (FileInputStream fin = new FileInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = fin.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        out.flush();
    }

    // ------------------------------------------------------------------ HTTP 基础件

    /** 从 path 里取 ?k=v 的值（只处理我们自己的简单场景）。 */
    private static String queryParam(String path, String key) {
        int q = path.indexOf('?');
        if (q < 0) {
            return null;
        }
        for (String pair : path.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }

    /**
     * guest 里的绝对路径 → App 私有目录里的真实路径。
     * Termux 环境（proot）里看到的是 /data/data/com.termux/files/...，
     * 实际落在 files/termux/...，两边后半段结构一致。
     */
    private File guestPath(String p) {
        if (p == null || p.isEmpty()) {
            return null;
        }
        String prefix = "/data/data/com.termux/files";
        if (p.startsWith(prefix)) {
            return new File(new File(app.getFilesDir(), "termux"), p.substring(prefix.length()));
        }
        return new File(p);
    }

    private static void respond(Socket socket, int code, String type, byte[] body,
                                String extraHeaders, int length) throws IOException {
        OutputStream out = new BufferedOutputStream(socket.getOutputStream());
        String reason = code == 200 ? "OK" : code == 206 ? "Partial Content"
                : code == 403 ? "Forbidden" : code == 404 ? "Not Found" : "Error";
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        sb.append("Content-Type: ").append(type).append("\r\n");
        sb.append("Content-Length: ").append(length == 0 ? 0 : body.length).append("\r\n");
        sb.append("Connection: close\r\n");
        if (extraHeaders != null) {
            sb.append(extraHeaders);
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        if (length != 0) {
            out.write(body);
        }
        out.flush();
    }

    private static String contentType(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".apk")) {
            return "application/vnd.android.package-archive";
        }
        if (n.endsWith(".tar.gz") || n.endsWith(".tgz")) {
            return "application/gzip";
        }
        if (n.endsWith(".json")) {
            return "application/json";
        }
        return "text/plain; charset=utf-8";
    }



    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream src = in) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            int n;
            while ((n = src.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            return buf.toByteArray();
        }
    }

    /** Termux 那边 curl 上来的内容往哪儿去。 */
    static final class Push {
        static void handle(String topic, String body) {
            if (body == null || body.isEmpty()) {
                return;
            }
            if (topic.startsWith("cred")) {
                Creds.absorb(body);
            } else if (topic.startsWith("prog")) {
                // install.sh 每步实时推的进度行（日志全文还是走 p/term，跑完一次到）
                InstallProgress.absorb(body);
            } else {
                Term.append(body);
            }
        }
    }
}
