package ai.panxian.arkdroid;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 桌面侧验证 {@link HttpWire}：收 count 个请求，把解析结果打出来。
 *
 * <p>配合 tools/selftest_http.py 用真 curl 打过来（管道 chunked / 文件 Content-Length /
 * 大 body 触发 Expect: 100-continue），确保 Termux 真的能把输出传回来。
 */
public final class HttpWireCheck {

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--chunked".equals(args[0])) {
            chunkedSelfTest();
            return;
        }
        int port = Integer.parseInt(args[0]);
        int count = Integer.parseInt(args[1]);
        try (ServerSocket server = new ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))) {
            System.out.println("READY " + server.getLocalPort());
            System.out.flush();
            for (int i = 0; i < count; i++) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(20000);
                    InputStream in = socket.getInputStream();
                    OutputStream out = socket.getOutputStream();
                    HttpWire.Head head = HttpWire.readHead(in);
                    if (head == null) {
                        System.out.println("REQ <empty>");
                        continue;
                    }
                    if (head.expectContinue) {
                        out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                    }
                    byte[] body = HttpWire.readBody(in, head);
                    out.write(("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK")
                            .getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    System.out.println("REQ " + head.method + " " + head.path
                            + " chunked=" + head.chunked
                            + " declared=" + head.contentLength
                            + " got=" + body.length
                            + " sha=" + shortSha(body));
                    System.out.flush();
                }
            }
        }
    }

    private static String shortSha(byte[] body) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] raw = md.digest(body);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            sb.append(Character.forDigit((raw[i] >> 4) & 0xF, 16));
            sb.append(Character.forDigit(raw[i] & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * 手工构造 chunked 报文喂给解析器。
     *
     * <p>真 curl 从管道上传时会自己算好 Content-Length 再发（不是流式），
     * 所以 chunked 这条分支只能这样确定性地验。
     */
    private static void chunkedSelfTest() throws Exception {
        StringBuilder raw = new StringBuilder("POST /t/x/p/term HTTP/1.1\r\n"
                + "Host: 127.0.0.1\r\nTransfer-Encoding: chunked\r\n\r\n");
        StringBuilder expect = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            String piece = "第" + i + "段输出，" + "x".repeat(3000);
            expect.append(piece);
            raw.append(Integer.toHexString(piece.getBytes(StandardCharsets.UTF_8).length)).append("\r\n")
                    .append(piece).append("\r\n");
        }
        raw.append("0\r\n\r\n");

        InputStream in = new java.io.ByteArrayInputStream(
                raw.toString().getBytes(StandardCharsets.UTF_8));
        HttpWire.Head head = HttpWire.readHead(in);
        byte[] body = HttpWire.readBody(in, head);
        System.out.println("HEAD chunked=" + head.chunked + " method=" + head.method);
        System.out.println("BODY got=" + body.length + " expect=" + expect.toString()
                .getBytes(StandardCharsets.UTF_8).length);
        System.out.println("MATCH " + (new String(body, StandardCharsets.UTF_8).equals(expect.toString())));

        // 截断的 chunked（连接中途断掉）：不能抛异常把服务搞挂
        String broken = "POST /t/x/p/term HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "10\r\nabc";
        InputStream in2 = new java.io.ByteArrayInputStream(
                broken.getBytes(StandardCharsets.UTF_8));
        HttpWire.Head head2 = HttpWire.readHead(in2);
        byte[] body2 = HttpWire.readBody(in2, head2);
        System.out.println("TRUNCATED got=" + body2.length);
    }
}
