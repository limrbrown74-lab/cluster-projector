package org.clusterprojector.app;

import android.os.Environment;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 本地日志工具：所有日志写入 /sdcard/Download/clusterprojector/ 目录。
 * 本模块由 AI 辅助原创开发。
 */
public final class AppLog {

    private static final String DIR_NAME = "clusterprojector";
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    private static final SimpleDateFormat DAY =
            new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    private AppLog() {}

    private static File logDir() {
        File base = Environment.getExternalStorageDirectory();
        File dir = new File(base, "Download/" + DIR_NAME);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static void d(String tag, String msg) { write("D", tag, msg, null); }
    public static void i(String tag, String msg) { write("I", tag, msg, null); }
    public static void w(String tag, String msg) { write("W", tag, msg, null); }
    public static void e(String tag, String msg) { write("E", tag, msg, null); }
    public static void e(String tag, String msg, Throwable t) { write("E", tag, msg, t); }

    private static synchronized void write(String level, String tag, String msg, Throwable t) {
        String line = String.format(Locale.US, "%s %s/%s: %s",
                TS.format(new Date()), level, tag, msg);
        android.util.Log.println(android.util.Log.DEBUG, tag, msg + (t != null ? "\n" : ""));

        try {
            File f = new File(logDir(), "app-" + DAY.format(new Date()) + ".log");
            try (FileWriter fw = new FileWriter(f, true);
                 PrintWriter pw = new PrintWriter(fw)) {
                pw.println(line);
                if (t != null) t.printStackTrace(pw);
            }
        } catch (Exception ignored) {}
    }

    /** 把一次完整的崩溃堆栈写入 crash 文件。 */
    public static synchronized void writeCrash(Throwable t) {
        try {
            File f = new File(logDir(),
                    "crash-" + DAY.format(new Date()) + "-" +
                            new SimpleDateFormat("HHmmss", Locale.US).format(new Date()) + ".txt");
            try (PrintWriter pw = new PrintWriter(new FileWriter(f, true))) {
                pw.println("=== CRASH at " + new Date() + " ===");
                pw.println("Device: " + android.os.Build.MODEL +
                        " Android " + android.os.Build.VERSION.RELEASE +
                        (" userdebug".equals("") ? "" : ""));
                t.printStackTrace(pw);
            }
        } catch (Exception ignored) {}
    }

    /** 列出所有日志文件，供 UI 展示。 */
    public static File[] listLogs() {
        File[] files = logDir().listFiles();
        return files != null ? files : new File[0];
    }
}
