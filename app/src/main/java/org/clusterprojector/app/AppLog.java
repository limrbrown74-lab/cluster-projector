package org.clusterprojector.app;

import android.content.Context;
import android.os.Environment;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 本地日志工具：优先写入 /sdcard/Download/clusterprojector/，失败时写入应用专属目录。
 * 本模块由 AI 辅助原创开发。
 */
public final class AppLog {

    private static final String DIR_NAME = "clusterprojector";
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    private static final SimpleDateFormat DAY =
            new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    private static volatile File appSpecificDir;

    private AppLog() {}

    public static void init(Context context) {
        Context app = context.getApplicationContext();
        File base = app.getExternalFilesDir(null);
        appSpecificDir = base != null
                ? new File(base, DIR_NAME + "-logs")
                : new File(app.getFilesDir(), DIR_NAME + "-logs");
    }

    private static File publicLogDir() {
        File base = Environment.getExternalStorageDirectory();
        File dir = new File(base, "Download/" + DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        return dir;
    }

    private static File appSpecificLogDir() {
        if (appSpecificDir == null) return null;
        if (!appSpecificDir.exists() && !appSpecificDir.mkdirs()) return null;
        return appSpecificDir;
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

        File publicDir = publicLogDir();
        if (writeToDir(publicDir, "app-" + DAY.format(new Date()) + ".log", line, t)) {
            return;
        }
        // Android 11+ 未授权/不允许直接写公共 Download 时，退到应用专属外部存储，无需运行时存储权限。
        writeToDir(appSpecificLogDir(), "app-" + DAY.format(new Date()) + ".log", line, t);
    }

    /** 把一次完整的崩溃堆栈写入 crash 文件。 */
    public static synchronized void writeCrash(Throwable t) {
        String name = "crash-" + DAY.format(new Date()) + "-"
                + new SimpleDateFormat("HHmmss", Locale.US).format(new Date()) + ".txt";
        String header = "=== CRASH at " + new Date() + " ===\n"
                + "Device: " + android.os.Build.MODEL
                + " Android " + android.os.Build.VERSION.RELEASE + "\n";
        File publicDir = publicLogDir();
        if (writeToDir(publicDir, name, header, t)) return;
        writeToDir(appSpecificLogDir(), name, header, t);
    }

    private static boolean writeToDir(File dir, String fileName, String text, Throwable t) {
        if (dir == null) return false;
        try {
            File f = new File(dir, fileName);
            try (FileWriter fw = new FileWriter(f, true);
                 PrintWriter pw = new PrintWriter(fw)) {
                pw.println(text);
                if (t != null) t.printStackTrace(pw);
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 列出公共 Download 和应用专属目录中的日志文件。 */
    public static File[] listLogs() {
        java.util.ArrayList<File> all = new java.util.ArrayList<>();
        addFiles(all, publicLogDir());
        addFiles(all, appSpecificLogDir());
        return all.toArray(new File[0]);
    }

    private static void addFiles(java.util.List<File> out, File dir) {
        File[] files = dir == null ? null : dir.listFiles();
        if (files != null) java.util.Collections.addAll(out, files);
    }
}
