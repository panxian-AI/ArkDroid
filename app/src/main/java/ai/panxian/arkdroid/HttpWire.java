package ai.panxian.arkdroid;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 极简 HTTP/1.1 报文解析 —— 只够本地回环用，但两种 body 编码都得认：
 *
 * <ul>
 *   <li>Termux 里 {@code curl --data-binary @- < 管道} 发的是 <b>chunked</b>；</li>
 *   <li>{@code curl -d @file} 发的是 Content-Length；</li>
 *   <li>大 body 时 curl 还会先发 {@code Expect: 100-continue} 等我们回话，不回它会卡住。</li>
 * </ul>
 *
 * <p>不依赖 Android，能在桌面上用真 curl 打到它身上测（tools/selftest_http.py）。
 */
final class HttpWire {

    /** body 上限：终端输出再长也不留全量，防止把内存吃光。 */
    static final int BODY_CAP = 4 * 1024 * 1024;

    static final class Head {
        String method = "";
        String path = "";
        long contentLength = -1;
        boolean chunked;
        boolean expectContinue;
        boolean keepAlive;
    }

    private HttpWire() {
    }

    /** 读请求头；连接已关闭返回 null。 */
    static Head readHead(InputStream in) throws IOException {
        String requestLine = readLine(in);
        if (requestLine == null || requestLine.isEmpty()) {
            return null;
        }
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            throw new IOException("请求行不对：" + requestLine);
        }
        Head head = new Head();
        head.method = parts[0];
        head.path = parts[1];
        head.keepAlive = true;

        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int idx = line.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            String key = line.substring(0, idx).trim().toLowerCase(Locale.US);
            String value = line.substring(idx + 1).trim();
            switch (key) {
                case "content-length":
                    try {
                        head.contentLength = Long.parseLong(value);
                    } catch (NumberFormatException e) {
                        head.contentLength = -1;
                    }
                    break;
                case "transfer-encoding":
                    head.chunked = value.toLowerCase(Locale.US).contains("chunked");
                    break;
                case "expect":
                    head.expectContinue = value.toLowerCase(Locale.US).contains("100-continue");
                    break;
                case "connection":
                    head.keepAlive = !value.toLowerCase(Locale.US).contains("close");
                    break;
                default:
                    break;
            }
        }
        return head;
    }

    /** 按 head 里声明的方式把 body 读完。 */
    static byte[] readBody(InputStream in, Head head) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (head.chunked) {
            readChunked(in, out);
        } else if (head.contentLength > 0) {
            copy(in, out, head.contentLength);
        }
        return out.toByteArray();
    }

    private static void readChunked(InputStream in, ByteArrayOutputStream out) throws IOException {
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) {
                return;
            }
            sizeLine = sizeLine.trim();
            if (sizeLine.isEmpty()) {
                continue;
            }
            int semi = sizeLine.indexOf(';');
            if (semi > 0) {
                sizeLine = sizeLine.substring(0, semi);
            }
            int size;
            try {
                size = Integer.parseInt(sizeLine, 16);
            } catch (NumberFormatException e) {
                throw new IOException("chunk 长度看不懂：" + sizeLine);
            }
            if (size == 0) {
                // 末尾还有可能跟 trailer，读到空行为止
                String trailer;
                while ((trailer = readLine(in)) != null && !trailer.isEmpty()) {
                    // 忽略
                }
                return;
            }
            long room = BODY_CAP - out.size();
            if (room <= 0) {
                skip(in, size);
            } else {
                copy(in, out, Math.min(size, room));
                long rest = size - Math.min(size, room);
                if (rest > 0) {
                    skip(in, rest);
                }
            }
            readLine(in); // 每个 chunk 后面的 CRLF
        }
    }

    private static void copy(InputStream in, ByteArrayOutputStream out, long length) throws IOException {
        byte[] buf = new byte[16 * 1024];
        long remaining = length;
        while (remaining > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (n < 0) {
                return;
            }
            out.write(buf, 0, n);
            remaining -= n;
        }
    }

    private static void skip(InputStream in, long length) throws IOException {
        byte[] buf = new byte[16 * 1024];
        long remaining = length;
        while (remaining > 0) {
            long n = in.skip(remaining);
            if (n <= 0) {
                int read = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (read < 0) {
                    return;
                }
                remaining -= read;
            } else {
                remaining -= n;
            }
        }
    }

    /** 读一行（去掉 CRLF）；EOF 且没读到东西返回 null。 */
    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                buf.write(c);
            }
            if (buf.size() > 8 * 1024) {
                throw new IOException("行太长");
            }
        }
        if (c == -1 && buf.size() == 0) {
            return null;
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }
}
