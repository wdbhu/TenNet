package com.fuckad.xx;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 统一日志出口。默认只写 logcat，调试开关打开后才输出 info 级别。
 * 调试模式下额外写一份到目标 App 的 cache/tunnet.log，方便崩溃后取证。
 * TAG 固定为 TunNet，方便 adb logcat -s TunNet。
 */
public final class Logs {

    private static final String TAG = "TunNet";
    private static final long MAX_FILE_BYTES = 512L * 1024;
    private static final Object FILE_LOCK = new Object();

    private static volatile boolean sEnabled = false;
    private static volatile File sFile;
    private static volatile Handler sFileHandler;
    private static boolean sCrashInstalled;

    private Logs() {
    }

    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    public static boolean isEnabled() {
        return sEnabled;
    }

    /** 调试模式下把日志同时写到这个文件（目标 App 的 cache 目录）。 */
    public static void setFile(File file) {
        if (file == null) return;
        sFile = file;
        synchronized (FILE_LOCK) {
            if (sFileHandler == null) {
                HandlerThread thread = new HandlerThread("TunNet-log");
                thread.setDaemon(true);
                thread.start();
                sFileHandler = new Handler(thread.getLooper());
            }
        }
    }

    public static void installCrashHandler() {
        synchronized (FILE_LOCK) {
            if (sCrashInstalled) return;
            sCrashInstalled = true;
        }
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    Log.e(TAG, "未捕获异常: " + t.getName(), e);
                } catch (Throwable ignored) {
                }
                try {
                    crashSync("未捕获异常: " + t.getName(), e);
                } catch (Throwable ignored) {
                }
                if (previous != null) previous.uncaughtException(t, e);
            }
        });
    }

    /** 日志文件是否已就绪（崩溃会写进去）。 */
    public static boolean isFileReady() {
        File file = sFile;
        return file != null && file.getParentFile() != null;
    }

    /** 调试信息，只有打开调试开关才输出。 */
    public static void d(String msg) {
        if (!sEnabled) return;
        try {
            Log.d(TAG, msg);
        } catch (Throwable ignored) {
        }
        append("D", msg, null);
    }

    /**
     * 重要流程信息：命中哪个 SDK、装了多少 hook、补发了几次奖励。
     *
     * 这条不能受调试开关控制——设置页的「日志」页展示的就是这些内容，
     * 一旦跟着 debug 开关走，用户关掉调试日志后日志页永远是空的。
     * 体积靠文件滚动上限（512KB）兜住，不会无限增长。
     */
    public static void i(String msg) {
        try {
            Log.i(TAG, msg);
        } catch (Throwable ignored) {
        }
        append("I", msg, null);
    }

    /** 无条件输出，用于模块加载等一两条关键信息。 */
    public static void raw(String msg) {
        try {
            Log.i(TAG, msg);
        } catch (Throwable ignored) {
        }
        append("I", msg, null);
    }

    public static void w(String msg) {
        if (!sEnabled) return;
        try {
            Log.w(TAG, msg);
        } catch (Throwable ignored) {
        }
        append("W", msg, null);
    }

    public static void e(String msg) {
        try {
            Log.e(TAG, msg);
        } catch (Throwable ignored) {
        }
        append("E", msg, null);
    }

    public static void e(String msg, Throwable t) {
        try {
            Log.e(TAG, msg, t);
        } catch (Throwable ignored) {
        }
        append("E", msg, t);
    }

    /** 崩溃时同步落盘，不能走异步队列（进程可能马上死）。 */
    public static void crashSync(String msg, Throwable t) {
        File file = sFile;
        if (file == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append(time()).append(" E ").append(msg).append('\n');
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            sb.append(sw.toString());
        }
        write(file, sb.toString());
    }

    private static void append(String level, String msg, Throwable t) {
        final File file = sFile;
        StringBuilder sb = new StringBuilder();
        sb.append(time()).append(' ').append(level).append(' ').append(msg).append('\n');
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            sb.append(sw.toString());
        }
        final String text = sb.toString();
        if (file == null) return;
        Handler handler = sFileHandler;
        if (handler != null) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    write(file, text);
                }
            });
        } else {
            write(file, text);
        }
    }

    private static void write(File file, String text) {
        synchronized (FILE_LOCK) {
            try {
                File dir = file.getParentFile();
                if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
                if (file.length() > MAX_FILE_BYTES) {
                    File old = new File(file.getPath() + ".1");
                    if (old.exists()) old.delete();
                    file.renameTo(old);
                }
                FileOutputStream out = new FileOutputStream(file, true);
                try {
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                } finally {
                    out.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static String time() {
        try {
            return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                    .format(new Date());
        } catch (Throwable t) {
            return "";
        }
    }
}
