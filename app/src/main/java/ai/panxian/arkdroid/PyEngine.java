package ai.panxian.arkdroid;

import android.content.Context;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

/**
 * Chaquopy 的入口。Python 侧只有一份 arkdroid.py + 内置的 arkbridge（纯标准库）。
 *
 * <p>所有调用都串行化：Chaquopy 的解释器是进程内单例，并发进来只会互相干扰。
 */
final class PyEngine {

    private static final Object LOCK = new Object();
    private static boolean broken;
    private static String brokenReason = "";

    private PyEngine() {
    }

    static String call(Context ctx, String function, String arg) {
        synchronized (LOCK) {
            if (broken) {
                return "Python 不可用：" + brokenReason;
            }
            try {
                if (!Python.isStarted()) {
                    Python.start(new AndroidPlatform(ctx.getApplicationContext()));
                }
                PyObject module = Python.getInstance().getModule("arkdroid");
                PyObject result = arg == null
                        ? module.callAttr(function)
                        : module.callAttr(function, arg);
                return result == null ? "" : result.toString();
            } catch (Throwable t) {
                broken = true;
                brokenReason = String.valueOf(t);
                return "Python 调用失败（" + function + "）：" + t;
            }
        }
    }
}
