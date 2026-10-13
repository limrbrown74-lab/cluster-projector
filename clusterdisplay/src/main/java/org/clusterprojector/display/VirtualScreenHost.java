package org.clusterprojector.display;

import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

/** 创建 FX11 仪表虚拟显示，并尝试把指定 Activity 启动到该 Display。 */
public final class VirtualScreenHost {

    private static final String TAG = "ClusterVirtual";
    private static final String DISPLAY_NAME = "SecondaryScreenMirror";

    private final Context ctx;
    private final MediaProjection mediaProjection;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private VirtualDisplay vd;
    private boolean released;
    private String lastError = "尚未启动";

    public VirtualScreenHost(Context context, MediaProjection projection) {
        this.ctx = context.getApplicationContext();
        this.mediaProjection = projection;
    }

    public void create(int width, int height, int densityDpi, Surface surface) {
        release();
        released = false;
        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;

        try {
            ClusterLog.i(TAG, "createVirtualDisplay begin: " + width + "x" + height
                    + "@" + densityDpi + " surfaceValid=" + (surface != null && surface.isValid())
                    + " flags=0x" + Integer.toHexString(flags));
            vd = mediaProjection.createVirtualDisplay(
                    DISPLAY_NAME,
                    width,
                    height,
                    densityDpi,
                    flags,
                    surface,
                    new VirtualDisplay.Callback() {
                        @Override public void onPaused() {
                            ClusterLog.i(TAG, "virtual display paused: id=" + getDisplayId()
                                    + " state=" + displayStateName(vd) + " released=" + released);
                        }

                        @Override public void onResumed() {
                            ClusterLog.i(TAG, "virtual display resumed: id=" + getDisplayId()
                                    + " state=" + displayStateName(vd) + " released=" + released);
                        }

                        @Override public void onStopped() {
                            ClusterLog.w(TAG, "virtual display stopped: id=" + getDisplayId()
                                    + " state=" + displayStateName(vd) + " released=" + released);
                        }
                    },
                    mainHandler);
            if (vd == null) {
                lastError = "createVirtualDisplay 返回 null";
                throw new IllegalStateException(lastError);
            }
            ClusterLog.i(TAG, "created " + width + "x" + height + "@" + densityDpi
                    + " id=" + vd.getDisplay().getDisplayId()
                    + " state=" + displayStateName(vd)
                    + " flags=0x" + Integer.toHexString(vd.getDisplay().getFlags())
                    + " name=" + vd.getDisplay().getName());
        } catch (Throwable t) {
            lastError = t.getMessage() == null ? t.toString() : t.getMessage();
            ClusterLog.e(TAG, "create virtual display failed", t);
            throw new IllegalStateException("创建仪表虚拟屏失败: " + lastError, t);
        }
    }

    public int getDisplayId() {
        return vd != null ? vd.getDisplay().getDisplayId() : -1;
    }

    public String getLastError() {
        return lastError;
    }

    public boolean launchApp(String packageName) {
        if (vd == null) {
            lastError = "虚拟屏尚未创建";
            return false;
        }
        ClusterLog.i(TAG, "launchApp request: " + packageName + " vdId="
                + (vd == null ? "null" : vd.getDisplay().getDisplayId()));
        try {
            Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(packageName);
            if (launch == null) {
                lastError = "找不到可启动 Activity: " + packageName;
                ClusterLog.w(TAG, lastError);
                return false;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            ComponentName resolved = launch.resolveActivity(ctx.getPackageManager());
            ComponentName component = launch.getComponent() != null
                    ? launch.getComponent() : resolved;
            ClusterLog.i(TAG, "launch intent: pkg=" + packageName + " component=" + component
                    + " resolved=" + resolved + " action=" + launch.getAction()
                    + " data=" + launch.getData() + " flags=0x"
                    + Integer.toHexString(launch.getFlags()));
            return launchOnVirtualDisplay(packageName, launch);
        } catch (Throwable t) {
            lastError = classifyLaunchError(t);
            ClusterLog.e(TAG, "launchApp on virtual display threw: " + packageName
                    + " -> " + lastError, t);
            return false;
        }
    }

    public boolean launchActivity(Class<?> activityClass) {
        if (vd == null) {
            lastError = "虚拟屏尚未创建";
            ClusterLog.w(TAG, "launchActivity skipped: virtual display missing");
            return false;
        }
        ClusterLog.i(TAG, "launchActivity request: " + activityClass + " vdId="
                + vd.getDisplay().getDisplayId());
        try {
            Intent launch = new Intent(ctx, activityClass);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            return launchOnVirtualDisplay(activityClass.getName(), launch);
        } catch (Throwable t) {
            lastError = classifyLaunchError(t);
            ClusterLog.e(TAG, "launchActivity on virtual display threw: "
                    + activityClass.getName() + " -> " + lastError, t);
            return false;
        }
    }

    private boolean launchOnVirtualDisplay(String label, Intent launch) {
        try {
            android.app.ActivityOptions options = android.app.ActivityOptions.makeBasic();
            int launchDisplayId = vd.getDisplay().getDisplayId();
            options.setLaunchDisplayId(launchDisplayId);
            Bundle optionsBundle = options.toBundle();
            ClusterLog.i(TAG, "startActivity begin: " + label + " displayId="
                    + launchDisplayId + " intent=" + launch + " options=" + optionsBundle);
            ctx.startActivity(launch, optionsBundle);
            lastError = null;
            ClusterLog.i(TAG, "startActivity returned without exception: " + label
                    + " displayId=" + launchDisplayId + " state="
                    + displayStateName(vd) + " note=no_exception_does_not_prove_actual_display");
            scheduleStateProbe(label, launchDisplayId, 500);
            scheduleStateProbe(label, launchDisplayId, 3000);
            scheduleStateProbe(label, launchDisplayId, 8000);
            scheduleSystemDiagnostics(label, 3000);
            return true;
        } catch (Throwable t) {
            lastError = classifyLaunchError(t);
            ClusterLog.e(TAG, "launch " + label + " on virtual display failed: " + lastError, t);
            return false;
        }
    }

    private static String classifyLaunchError(Throwable t) {
        String type = t.getClass().getName();
        String message = t.getMessage() == null ? t.toString() : t.getMessage();
        String compact = type + ": " + message;
        String lower = message.toLowerCase(java.util.Locale.US);
        if (lower.contains("permission") || lower.contains("denied")
                || lower.contains("securityexception") || t instanceof SecurityException) {
            return "系统拒绝跨 Display 启动（SecurityException）：" + compact;
        }
        if (lower.contains("not allowed") || lower.contains("not permitted")
                || lower.contains("display")) {
            return "车机多 Display 启动策略拒绝：" + compact;
        }
        return compact;
    }

    public String getDisplayStateName() {
        return displayStateName(vd);
    }

    private void scheduleStateProbe(String label, int launchDisplayId, long delayMs) {
        mainHandler.postDelayed(() -> {
            if (released || vd == null) return;
            ClusterLog.i(TAG, "state probe +" + delayMs + "ms: target=" + label
                    + " requestedDisplayId=" + launchDisplayId
                    + " actualVdId=" + getDisplayId()
                    + " state=" + displayStateName(vd)
                    + " flags=0x" + Integer.toHexString(vd.getDisplay().getFlags()));
        }, delayMs);
    }

    private static String displayStateName(VirtualDisplay display) {
        if (display == null || display.getDisplay() == null) return "null";
        switch (display.getDisplay().getState()) {
            case android.view.Display.STATE_OFF: return "OFF";
            case android.view.Display.STATE_ON: return "ON";
            case android.view.Display.STATE_DOZE: return "DOZE";
            case android.view.Display.STATE_DOZE_SUSPEND: return "DOZE_SUSPEND";
            case android.view.Display.STATE_UNKNOWN: return "UNKNOWN";
            default: return "OTHER(" + display.getDisplay().getState() + ")";
        }
    }

    private void scheduleSystemDiagnostics(String label, long delayMs) {
        mainHandler.postDelayed(() -> {
            if (released || vd == null) return;
            Thread worker = new Thread(() -> captureSystemDiagnostics(label),
                    "cluster-dumpsys");
            worker.setDaemon(true);
            worker.start();
        }, delayMs);
    }

    /** 最佳努力读取系统诊断；无 root、不绕过权限，失败也写入日志，便于判断车机策略是否拦截。 */
    private void captureSystemDiagnostics(String label) {
        String token = label == null ? "" : label.contains("/")
                ? label.substring(0, label.indexOf('/')) : label;
        String parentToken = "";
        int dot = token.lastIndexOf('.');
        if (dot > 0) parentToken = token.substring(0, dot);
        ClusterLog.i(TAG, "system diagnostics begin: target=" + label + " filter="
                + token + " parentFilter=" + parentToken);
        String activityDump = readFilteredCommand("dumpsys activity activities", token,
                parentToken, 120 * 1024);
        String displayDump = readFilteredCommand("dumpsys display", token,
                parentToken, 80 * 1024);
        ClusterLog.i(TAG, "system diagnostics activity dump >>>\n" + activityDump);
        ClusterLog.i(TAG, "system diagnostics display dump >>>\n" + displayDump);
        ClusterLog.i(TAG, "system diagnostics end: target=" + label);
    }

    private String readFilteredCommand(String command, String targetToken,
            String parentToken, int maxBytes) {
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/sh", "-c", command)
                    .redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder(Math.min(maxBytes + 1024, 16 * 1024));
            byte[] buffer = new byte[4096];
            int read;
            int total = 0;
            while (total < maxBytes && (read = process.getInputStream().read(buffer)) != -1) {
                String chunk = new String(buffer, 0, read, "UTF-8");
                appendRelevantLines(out, chunk, targetToken, parentToken);
                total += read;
            }
            boolean finished = process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            int exit = finished ? process.exitValue() : Integer.MIN_VALUE;
            if (!finished) process.destroy();
            String text = out.toString().trim();
            if (text.isEmpty()) text = "(no matching output; exit=" + exit + ")";
            return "command=" + command + " exit=" + exit + " bytesRead=" + total
                    + "\n" + text;
        } catch (Throwable t) {
            return "command=" + command + " failed: " + android.util.Log.getStackTraceString(t);
        } finally {
            if (process != null) {
                try { process.destroy(); } catch (Throwable ignored) {}
            }
        }
    }

    private void appendRelevantLines(StringBuilder out, String chunk,
            String targetToken, String parentToken) {
        String[] lines = chunk.split("\\r?\\n");
        for (String line : lines) {
            String lower = line.toLowerCase(java.util.Locale.US);
            boolean relevant = lower.contains("display") || lower.contains("resumed")
                    || lower.contains("mfocus") || lower.contains("topactivity")
                    || lower.contains(DISPLAY_NAME.toLowerCase(java.util.Locale.US))
                    || (targetToken != null && lower.contains(targetToken.toLowerCase(java.util.Locale.US)))
                    || (parentToken != null && parentToken.length() > 0
                        && lower.contains(parentToken.toLowerCase(java.util.Locale.US)));
            if (relevant && out.length() < 130 * 1024) {
                out.append(line.trim()).append('\n');
            }
        }
    }

    public void setSurface(Surface surface) {
        if (vd != null) {
            vd.setSurface(surface);
        }
    }

    public void release() {
        ClusterLog.i(TAG, "release virtual display: currentId=" + getDisplayId()
                + " state=" + displayStateName(vd));
        released = true;
        if (vd != null) {
            try {
                vd.release();
            } catch (Throwable t) {
                ClusterLog.w(TAG, "release virtual display failed", t);
            }
            vd = null;
        }
    }
}
