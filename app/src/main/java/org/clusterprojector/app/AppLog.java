package org.clusterprojector.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.FileWriter;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
    private static final SimpleDateFormat EXPORT_TS =
            new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
    private static final long MAX_TOTAL_EXPORT_BYTES = 40L * 1024L * 1024L;

    private static volatile File appSpecificDir;

    private AppLog() {}

    public static void init(Context context) {
        Context app = context.getApplicationContext();
        File base = app.getExternalFilesDir(null);
        appSpecificDir = base != null
                ? new File(base, DIR_NAME + "-logs")
                : new File(app.getFilesDir(), DIR_NAME + "-logs");
        i("AppLog", "diagnostic logger initialized: public=" + publicLogDir()
                + " appSpecific=" + appSpecificDir);
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

    public static String getLogDirectoryHint() {
        return "/sdcard/Download/clusterprojector/\nAndroid/data/"
                + "org.clusterprojector.app/files/clusterprojector-logs/";
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
                + "Device: " + Build.MODEL
                + " Android " + Build.VERSION.RELEASE + "\n";
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
                pw.flush();
            }
            return true;
        } catch (Exception e) {
            android.util.Log.w("AppLog", "write diagnostic log failed: " + dir, e);
            return false;
        }
    }

    /** 列出公共 Download 和应用专属目录中的日志文件，去重并按时间排序。 */
    public static File[] listLogs() {
        ArrayList<File> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        addFiles(all, seen, publicLogDir());
        addFiles(all, seen, appSpecificLogDir());
        Collections.sort(all, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                int byTime = Long.compare(a.lastModified(), b.lastModified());
                return byTime != 0 ? byTime : a.getName().compareTo(b.getName());
            }
        });
        return all.toArray(new File[0]);
    }

    private static void addFiles(List<File> out, Set<String> seen, File dir) {
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.isFile()) continue;
            String name = f.getName().toLowerCase(Locale.US);
            if ((name.endsWith(".log") || name.endsWith(".txt"))
                    && !f.getName().startsWith("clusterprojector-diagnostic-")
                    && seen.add(f.getAbsolutePath())) {
                out.add(f);
            }
        }
    }

    /** 生成供系统分享面板发送的完整诊断包。 */
    public static File exportDiagnosticBundle(Context context, String runtimeInfo) {
        Context app = context.getApplicationContext();
        File dir = new File(app.getCacheDir(), "diagnostic");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建诊断包目录: " + dir);
        }
        pruneOldBundles(dir);

        File out = new File(dir, "clusterprojector-diagnostic-"
                + EXPORT_TS.format(new Date()) + ".log");
        long total = 0L;
        try (Writer writer = new OutputStreamWriter(new java.io.FileOutputStream(out), "UTF-8");
             PrintWriter pw = new PrintWriter(writer)) {
            pw.println("===== ClusterProjector 诊断日志 =====");
            pw.println("导出时间: " + new Date());
            pw.println("包名: " + app.getPackageName());
            pw.println("版本: " + getVersionName(app) + " (" + getVersionCode(app) + ")");
            pw.println("Brand=" + Build.BRAND + " Manufacturer=" + Build.MANUFACTURER);
            pw.println("Model=" + Build.MODEL + " Device=" + Build.DEVICE
                    + " Product=" + Build.PRODUCT);
            pw.println("Android=" + Build.VERSION.RELEASE + " SDK=" + Build.VERSION.SDK_INT);
            pw.println("Display=" + Build.DISPLAY);
            pw.println("Fingerprint=" + Build.FINGERPRINT);
            pw.println("日志目录:");
            pw.println(getLogDirectoryHint());
            pw.println();
            pw.println("===== 运行状态 =====");
            pw.println(runtimeInfo == null ? "" : runtimeInfo);
            pw.println();

            File[] logs = listLogs();
            pw.println("发现日志文件数: " + logs.length);
            for (File f : logs) {
                pw.println("- " + f.getAbsolutePath() + " size=" + f.length()
                        + " modified=" + new Date(f.lastModified()));
            }
            pw.println();

            for (File f : logs) {
                if (total + f.length() > MAX_TOTAL_EXPORT_BYTES) {
                    pw.println("===== " + f.getName() + " =====");
                    pw.println("已跳过：超过诊断包大小上限 " + MAX_TOTAL_EXPORT_BYTES);
                    pw.println();
                    continue;
                }
                pw.println("===== " + f.getName() + " =====");
                pw.flush();
                appendFile(pw, f);
                total += f.length();
                pw.println();
                pw.flush();
            }
        } catch (Exception e) {
            throw new IllegalStateException("导出诊断日志失败: " + e.getMessage(), e);
        }
        return out;
    }


    /**
     * 把诊断包额外复制到公共 Download 目录，方便 LocalSend 等文件工具在系统分享面板被阉割时直接选择。
     * 失败不影响通过应用内 ContentProvider 分享。
     */
    public static File publishDiagnosticBundle(File cachedBundle) {
        File dir = publicLogDir();
        if (dir == null) return null;
        pruneOldBundles(dir);
        File out = new File(dir, cachedBundle.getName());
        byte[] buffer = new byte[16 * 1024];
        try (FileInputStream in = new FileInputStream(cachedBundle);
             FileOutputStream fos = new FileOutputStream(out)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
            }
            fos.getFD().sync();
        } catch (Exception e) {
            android.util.Log.w("AppLog", "publish diagnostic bundle failed: " + dir, e);
            return null;
        }
        return out;
    }

    private static void appendFile(PrintWriter pw, File file) throws Exception {
        char[] buffer = new char[16 * 1024];
        try (FileInputStream fis = new FileInputStream(file);
             Reader in = new InputStreamReader(fis, "UTF-8")) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                pw.write(buffer, 0, read);
            }
        }
    }

    private static void pruneOldBundles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        List<File> bundles = new ArrayList<>();
        for (File f : files) {
            if (f.isFile() && f.getName().startsWith("clusterprojector-diagnostic-")) {
                bundles.add(f);
            }
        }
        Collections.sort(bundles, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        for (int i = 3; i < bundles.size(); i++) {
            //noinspection ResultOfMethodCallIgnored
            bundles.get(i).delete();
        }
    }

    private static String getVersionName(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return info.versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static long getVersionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode() : info.versionCode;
        } catch (Exception e) {
            return -1;
        }
    }
}
