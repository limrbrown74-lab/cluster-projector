package org.clusterprojector.display;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 投屏核心库诊断日志：同时写 logcat 和本地文件，便于无电脑时从 App 分享。 */
public final class ClusterLog {

    private static final String DIR_NAME = "clusterprojector";
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    private static final SimpleDateFormat DAY =
            new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    private static volatile File appSpecificDir;

    private ClusterLog() {}

    public static void init(Context context) {
        Context app = context.getApplicationContext();
        File base = app.getExternalFilesDir(null);
        appSpecificDir = base != null
                ? new File(base, DIR_NAME + "-logs")
                : new File(app.getFilesDir(), DIR_NAME + "-logs");
        i("ClusterLog", "diagnostic logger initialized: public="
                + publicLogDir() + " appSpecific=" + appSpecificDir);
    }

    public static void d(String tag, String msg) { write("D", tag, msg, null); }
    public static void i(String tag, String msg) { write("I", tag, msg, null); }
    public static void w(String tag, String msg) { write("W", tag, msg, null); }
    public static void w(String tag, String msg, Throwable t) { write("W", tag, msg, t); }
    public static void e(String tag, String msg) { write("E", tag, msg, null); }
    public static void e(String tag, String msg, Throwable t) { write("E", tag, msg, t); }

    private static int priorityOf(String level) {
        if ("E".equals(level)) return android.util.Log.ERROR;
        if ("W".equals(level)) return android.util.Log.WARN;
        if ("D".equals(level)) return android.util.Log.DEBUG;
        return android.util.Log.INFO;
    }

    private static synchronized void write(String level, String tag, String msg, Throwable t) {
        String line = String.format(Locale.US, "%s %s/%s[%s]: %s",
                TS.format(new Date()), level, tag, Thread.currentThread().getName(), msg);
        int priority = priorityOf(level);
        if (t != null) {
            android.util.Log.println(priority, tag, msg + '\n'
                    + android.util.Log.getStackTraceString(t));
        } else {
            android.util.Log.println(priority, tag, msg);
        }

        String fileName = "app-" + DAY.format(new Date()) + ".log";
        File publicDir = publicLogDir();
        if (writeToDir(publicDir, fileName, line, t)) return;
        writeToDir(appSpecificLogDir(), fileName, line, t);
    }

    private static File publicLogDir() {
        File dir = new File(Environment.getExternalStorageDirectory(), "Download/" + DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) return null;
        return dir;
    }

    private static File appSpecificLogDir() {
        if (appSpecificDir == null) return null;
        if (!appSpecificDir.exists() && !appSpecificDir.mkdirs()) return null;
        return appSpecificDir;
    }

    private static boolean writeToDir(File dir, String fileName, String text, Throwable t) {
        if (dir == null) return false;
        try {
            File f = new File(dir, fileName);
            try (FileWriter fw = new FileWriter(f, true);
                 PrintWriter pw = new PrintWriter(fw)) {
                pw.println(text);
                if (t != null) t.printStackTrace(pw);
                pw.flush();
            }
            return true;
        } catch (Exception e) {
            android.util.Log.w("ClusterLog", "write diagnostic log failed: " + dir, e);
            return false;
        }
    }
}
