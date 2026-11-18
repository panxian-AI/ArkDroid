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

    /** Ubuntu 根文件系统（装 AstrBot / QQ 的 glibc 环境）。 */
    static File rootfs(Context ctx) {
        return new File(home(ctx), ".arkdroid/rootfs");
    }

    /** 安装器状态目录（rootfs 外的那些脚本、安装包、日志）。 */
    static File state(Context ctx) {
        return new File(home(ctx), ".arkdroid");
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
        // proot 需要一个可写的临时目录放 glue rootfs；不 mkdir 它会直接罢工
        File prootTmp = new File(ctx.getCacheDir(), "proot");
        if (!prootTmp.isDirectory() && !prootTmp.mkdirs()) {
            LogBus.append("建不了 proot 临时目录：" + prootTmp);
        }
        ProcessBuilder pb = new ProcessBuilder(
                new File(nativeBin(ctx), "proot").getAbsolutePath(),
                // Android 的文件系统/SELinux 不允许普通 App 建硬链接，解 Ubuntu rootfs 时
                // tar 一遇到硬链接就 "Cannot hard link to ... Permission denied" 并整体失败。
                // --link2symlink 让 proot 把 link() 改写成符号链接，tar 就能正常走完。
                // Termux 官方的 proot-distro 也是这么干的。
                "--link2symlink",
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
        // 不设 LD_PRELOAD：老版 libtermux-exec.so 在新 bootstrap 里已不存在，preload 缺库
        // 会让 proot 自己都加载不起来（CANNOT LINK EXECUTABLE）。guest 内部的 shebang /
        // 路径翻译由 proot 在 syscall 层完成，不需要 termux-exec。
        env.put("PROOT_LOADER", new File(nativeBin(ctx), "loader").getAbsolutePath());
        env.put("PROOT_LOADER32", new File(nativeBin(ctx), "loader32").getAbsolutePath());
        env.put("PROOT_TMP_DIR", new File(ctx.getCacheDir(), "proot").getAbsolutePath());
        // link2symlink 的改写产物放这里（默认放在链接所在目录，也是可写的，只是统一到
        // 一个地方更好清）
        env.put("PROOT_L2S_DIR", prootTmp.getAbsolutePath());
        env.put("PROOT_NO_SECCOMP", "1");
        // proot 本体用 nativeBin 里的 libtalloc/libandroid-shmem；guest 里的 Termux
        // 二进制（bash/curl/…）靠 $PREFIX/lib 找自己的依赖库（链接器的 open 会被
        // proot 翻译到真实路径，所以 guest 前缀写在 host 环境里同样生效）
        env.put("LD_LIBRARY_PATH",
                nativeBin(ctx).getAbsolutePath() + ":" + GUEST_PREFIX + "/lib");
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

    // ------------------------------------------------------------------ Ubuntu 侧
    //
    // 为什么 Ubuntu 的命令必须由 App 起进程：上面那条 exec() 本身已经在 proot 里了，
    // 再在 guest 里调 proot 就是「proot 套 proot」——外层 proot 已经通过 ptrace 接管了
    // 所有子孙进程，内层 proot 的 PTRACE_TRACEME 必然失败。所以 Ubuntu 环境统一由 App
    // 起一个和 Termux 环境平级的 proot 进程（sbiling，不是 child of proot）。

    private static ProcessBuilder ubuntuProcess(Context ctx, String command) {
        File prootTmp = new File(ctx.getCacheDir(), "proot");
        if (!prootTmp.isDirectory() && !prootTmp.mkdirs()) {
            LogBus.append("建不了 proot 临时目录：" + prootTmp);
        }
        ProcessBuilder pb = new ProcessBuilder(
                new File(nativeBin(ctx), "proot").getAbsolutePath(),
                "--link2symlink",
                "-0",
                "-r", rootfs(ctx).getAbsolutePath(),
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                // 安装包、控制脚本都在 rootfs 外的 ~/.arkdroid 里，挂进 Ubuntu 给脚本用
                "-b", state(ctx).getAbsolutePath() + ":/root/.arkdroid",
                "-w", "/root",
                "/usr/bin/env", "-i",
                "HOME=/root",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "LANG=C.UTF-8",
                "LC_ALL=C.UTF-8",
                "TERM=xterm-256color",
                "/bin/bash", "-c", command);
        Map<String, String> env = pb.environment();
        env.put("PROOT_LOADER", new File(nativeBin(ctx), "loader").getAbsolutePath());
        env.put("PROOT_LOADER32", new File(nativeBin(ctx), "loader32").getAbsolutePath());
        env.put("PROOT_TMP_DIR", prootTmp.getAbsolutePath());
        env.put("PROOT_NO_SECCOMP", "1");
        env.put("LD_LIBRARY_PATH", nativeBin(ctx).getAbsolutePath());
        pb.directory(state(ctx));
        pb.redirectErrorStream(true);
        return pb;
    }

    /** 同步跑一段 Ubuntu 命令：输出逐行进「终端」+ install.log，返回退出码。 */
    static int execUbuntu(Context ctx, String command) {
        if (!new File(rootfs(ctx), "etc/os-release").isFile()) {
            LogBus.append("Ubuntu 根文件系统还没解压（安装器的 2/9 步），先跑安装");
            return 127;
        }
        try {
            Process p = ubuntuProcess(ctx, command).start();
            Term.note("(ubuntu) " + firstLine(command));
            pump(p.getInputStream(), new File(state(ctx), "install.log"));
            int code = p.waitFor();
            Term.note("(ubuntu) 退出码 " + code);
            return code;
        } catch (IOException e) {
            LogBus.append("起不了 Ubuntu 环境：" + e.getMessage());
            return 126;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 130;
        }
    }

    /** 后台起一个 Ubuntu 长驻进程（AstrBot / QQ / ArkLink），输出写日志文件，返回 pid。 */
    static int startUbuntuBg(Context ctx, String command, File log) {
        if (!new File(rootfs(ctx), "etc/os-release").isFile()) {
            LogBus.append("Ubuntu 根文件系统还没解压，起不了 " + command);
            return -1;
        }
        try {
            Process p = ubuntuProcess(ctx, command).start();
            long pid = pidOf(p);
            Thread t = new Thread(() -> pump(p.getInputStream(), log), "arkdroid-ubuntu-bg");
            t.setDaemon(true);
            t.start();
            Term.note("后台启动（pid " + pid + "）：" + firstLine(command));
            return (int) pid;
        } catch (IOException e) {
            LogBus.append("起不了 Ubuntu 进程：" + e.getMessage());
            return -1;
        }
    }

    /** 流式转出：同时进「终端」和日志文件（日志给 astr log 之类的命令看）。 */
    private static void pump(InputStream in, File log) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8));
             java.io.FileWriter w = new java.io.FileWriter(log, true)) {
            String line;
            while ((line = r.readLine()) != null) {
                Term.append(line);
                w.write(line);
                w.write('\n');
                w.flush();
            }
        } catch (IOException ignored) {
            // 对端关了或者日志写不进去，都不该影响进程本身
        }
    }

    private static long pidOf(Process p) {
        try {
            java.lang.reflect.Field f = p.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            return ((Number) f.get(p)).longValue();
        } catch (Exception e) {
            return -1;
        }
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i) + " …";
    }
}
