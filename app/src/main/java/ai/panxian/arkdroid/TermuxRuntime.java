package ai.panxian.arkdroid;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 嵌入式 Termux 运行时（v0.4.0 起不再单独装 ArkTerm）。
 *
 * <p>原理：Termux 的 bootstrap（bash/apt/coreutils）是按编译期前缀
 * {@code /data/data/com.termux/files} 构建的，搬到包名不同的 App 里，ELF 和脚本里的
 * 路径就全悬空了。我们不重写 bootstrap（二进制里的路径改不动），而是用 proot 做一层
 * bind：把 App 私有目录里的 {@code files/termux/usr} 映射成 guest 视图里的
 * {@code /data/data/com.termux/files/usr}——bootstrap 的一切都按原样工作。
 *
 * <p>随之消失的问题：RUN_COMMAND 危险权限、allow-external-apps 授权、ArkTerm 未启动时
 * 系统拒发命令（stopped state）、"Display over other apps"——这些全是跨 App 通信造成的。
 * 现在同一进程内直接 {@link ProcessBuilder} 起 proot + bash，输出实时回「终端」。
 *
 * <p>为什么 targetSdk 必须是 28：Android 10+ 禁止 App 执行自己私有目录里的二进制
 * （W^X），Termux 官方也是钉在 28 绕开的。
 */
final class TermuxRuntime {

    /** guest 视图里的固定前缀（bootstrap 编译期的路径，不能改）。 */
    static final String GUEST_PREFIX = "/data/data/com.termux/files/usr";
    static final String GUEST_HOME = "/data/data/com.termux/files/home";

    private TermuxRuntime() {
    }

    // ------------------------------------------------------------------ 路径

    static File usr(Context ctx) {
        return new File(ctx.getFilesDir(), "termux/usr");
    }

    static File home(Context ctx) {
        return new File(ctx.getFilesDir(), "termux/home");
    }

    /** proot 和它的依赖库（assets/native/ 拷出来的真实路径，host 侧直接 exec 用）。 */
    static File nativeBin(Context ctx) {
        return new File(ctx.getFilesDir(), "termux/native");
    }

    /** bootstrap 解压完成（bash 就位）。 */
    static boolean ready(Context ctx) {
        return new File(usr(ctx), "bin/bash").isFile();
    }

    /** 装到一半（bootstrap 在下载或解压中）？用于 UI 区分提示。 */
    static boolean partial(Context ctx) {
        return usr(ctx).isDirectory() && !ready(ctx);
    }

    // ------------------------------------------------------------------ 初始化

    /**
     * 解压 bootstrap zip 到 usr/。
     *
     * <p>bootstrap 的 zip 不是普通 zip：符号链接被存成「文件内容 = 目标路径」+ 一份
     * SYMLINKS.txt 清单（Termux 打包工具的格式）。解压时要把链接还原出来；
     * 目标路径全是 guest 前缀（/data/data/com.termux/...），在 proot 视图里有效，不用改写。
     * Java 的 java.nio.Files 是 API 26 才有的，而 minSdk 是 24，所以用 toybox 的 ln 兜底。
     */
    static void installBootstrap(Context ctx, File zip) throws IOException, InterruptedException {
        File usr = usr(ctx);
        File home = home(ctx);
        usr.mkdirs();
        home.mkdirs();
        // 干净重装：旧的解一半的目录直接清掉
        deleteTree(usr);
        usr.mkdirs();

        LogBus.append("解压 Termux bootstrap（" + (zip.length() / 1024 / 1024) + " MB）…");
        // SYMLINKS.txt 是链接清单（bootstrap 的打包工具不把 symlink 存成 zip symlink，
        // 而是存成普通文件：内容 = 目标路径，另有这份清单）。java.util.zip 拿不到
        // Unix mode（external attributes 不暴露），所以完全按清单来。
        java.util.Map<String, String> linkMap = new java.util.LinkedHashMap<>();
        try (ZipFile zf = new ZipFile(zip)) {
            ZipEntry sl = zf.getEntry("SYMLINKS.txt");
            if (sl != null) {
                for (String line : new String(readAll(zf.getInputStream(sl)), StandardCharsets.UTF_8)
                        .split("\n")) {
                    line = line.trim();
                    if (line.isEmpty() || !line.contains("←")) {
                        continue;
                    }
                    String[] p = line.split("←", 2);
                    String target = p[0].trim();
                    String linkName = p[1].trim();
                    if (linkName.startsWith("./")) {
                        linkName = linkName.substring(2);
                    }
                    linkMap.put(linkName, target);
                }
            }
        }
        int files = 0;
        try (ZipFile zf = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) {
                    new File(usr, e.getName()).mkdirs();
                    continue;
                }
                if (e.getName().equals("SYMLINKS.txt") || linkMap.containsKey(e.getName())) {
                    continue; // 链接 entry 跳过，内容就是目标路径字符串，没有真实数据
                }
                File out = new File(usr, e.getName());
                File parent = out.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                try (InputStream in = zf.getInputStream(e); OutputStream w = new FileOutputStream(out)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        w.write(buf, 0, n);
                    }
                }
                String name = e.getName();
                if (name.startsWith("bin/") || name.startsWith("libexec/")
                        || name.startsWith("sbin/")) {
                    out.setExecutable(true, false);
                }
                files++;
            }
        }
        int links = 0;
        for (Map.Entry<String, String> e : linkMap.entrySet()) {
            File out = new File(usr, e.getKey());
            out.getParentFile().mkdirs();
            symlink(ctx, e.getValue(), out.getPath());
            links++;
        }
        provisionProot(ctx);
        new File(usr, "tmp").mkdirs();
        new File(usr, "cache").mkdirs();
        LogBus.append("bootstrap 就位（解出 " + files + " 个文件、链接 " + links
                + " 个），proot 已配好");
    }

    /** 把 assets/native/ 里的 proot + loader + 依赖库拷出来并加上执行位。 */
    private static void provisionProot(Context ctx) throws IOException {
        File dst = nativeBin(ctx);
        dst.mkdirs();
        for (String name : new String[]{"proot", "loader", "loader32",
                "libtalloc.so.2", "libandroid-shmem.so"}) {
            File out = new File(dst, name);
            try (InputStream in = ctx.getAssets().open("native/" + name);
                 OutputStream w = new FileOutputStream(out)) {
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    w.write(buf, 0, n);
                }
            }
            out.setExecutable(true, false);
            out.setReadable(true, false);
        }
    }

    private static void symlink(Context ctx, String target, String link) {
        try {
            Process p = new ProcessBuilder("/system/bin/ln", "-sfn", target, link).start();
            p.waitFor();
        } catch (Exception e) {
            LogBus.append("建符号链接失败 " + link + "：" + e.getMessage());
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        f.delete();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[16 * 1024];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    // ------------------------------------------------------------------ 执行

    /**
     * 在 bootstrap 环境里跑一条 bash 命令（异步，输出实时进「终端」）。
     *
     * <p>命令在 guest 视图里执行：$PREFIX/$HOME 都是 Termux 的标准路径，
     * 所以 install.sh、astr 这些脚本一行都不用改。
     */
    static boolean exec(Context ctx, String command) {
        if (!ready(ctx)) {
            LogBus.append("Termux 运行时还没就绪（先下载并安装 bootstrap）");
            return false;
        }
        String local;
        try {
            local = LocalServer.ensure(ctx);
        } catch (IOException e) {
            LogBus.append("本地服务起不来：" + e.getMessage());
            return false;
        }
        ProcessBuilder pb = new ProcessBuilder(
                new File(nativeBin(ctx), "proot").getAbsolutePath(),
                "-0",
                "-w", GUEST_HOME,
                "-b", usr(ctx).getAbsolutePath() + ":" + GUEST_PREFIX,
                "-b", home(ctx).getAbsolutePath() + ":" + GUEST_HOME,
                GUEST_PREFIX + "/bin/bash", "-lc", command);
        Map<String, String> env = pb.environment();
        env.put("PATH", GUEST_PREFIX + "/bin");
        env.put("HOME", GUEST_HOME);
        env.put("PREFIX", GUEST_PREFIX);
        env.put("TMPDIR", GUEST_PREFIX + "/tmp");
        env.put("LANG", "en_US.UTF-8");
        env.put("LD_PRELOAD", GUEST_PREFIX + "/lib/libtermux-exec.so");
        env.put("PROOT_LOADER", new File(nativeBin(ctx), "loader").getAbsolutePath());
        env.put("PROOT_LOADER32", new File(nativeBin(ctx), "loader32").getAbsolutePath());
        env.put("PROOT_TMP_DIR", new File(ctx.getCacheDir(), "proot").getAbsolutePath());
        env.put("PROOT_NO_SECCOMP", "1");
        env.put("LD_LIBRARY_PATH", nativeBin(ctx).getAbsolutePath());
        env.put("ARKDROID_LOCAL", local);
        env.put("TERM", "xterm-256color");
        pb.directory(home(ctx));
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            Term.note("$ " + command);
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        Term.append(line);
                    }
                } catch (IOException ignored) {
                }
                try {
                    int code = p.waitFor();
                    Term.append("[退出码 " + code + "]");
                } catch (InterruptedException ignored) {
                }
            }, "arkdroid-exec");
            reader.setDaemon(true);
            reader.start();
            return true;
        } catch (IOException e) {
            LogBus.append("起不了 proot：" + e.getMessage()
                    + "（targetSdk 必须是 28，否则 Android 禁止执行私有目录里的二进制）");
            return false;
        }
    }
}
