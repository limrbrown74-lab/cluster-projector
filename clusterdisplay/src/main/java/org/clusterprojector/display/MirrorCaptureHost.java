package org.clusterprojector.display;

import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

/**
 * 兼容兜底：无编码镜像中控 Display 0。
 *
 * <p>VirtualDisplay 的输出 Surface 来自 SurfaceTexture，后续由 OpenGL 直接搬到仪表窗口。
 */
public final class MirrorCaptureHost {

    private static final String TAG = "ClusterMirror";
    private static final String DISPLAY_NAME = "SecondaryScreenMirror-Fallback";

    private final Context context;
    private final MediaProjection projection;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private VirtualDisplay virtualDisplay;
    private boolean released;
    private String lastError = "尚未启动镜像";

    public MirrorCaptureHost(Context context, MediaProjection projection) {
        this.context = context.getApplicationContext();
        this.projection = projection;
    }

    public void start(int sourceWidth, int sourceHeight, int sourceDensityDpi, Surface inputSurface) {
        stop();
        released = false;
        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;
        try {
            ClusterLog.i(TAG, "create AUTO_MIRROR begin: source=" + sourceWidth + "x"
                    + sourceHeight + "@" + sourceDensityDpi + " surfaceValid="
                    + (inputSurface != null && inputSurface.isValid())
                    + " flags=0x" + Integer.toHexString(flags));
            virtualDisplay = projection.createVirtualDisplay(
                    DISPLAY_NAME,
                    sourceWidth,
                    sourceHeight,
                    sourceDensityDpi,
                    flags,
                    inputSurface,
                    new VirtualDisplay.Callback() {
                        @Override public void onPaused() {
                            ClusterLog.i(TAG, "mirror display paused: id=" + getDisplayId()
                                    + " state=" + displayStateName(virtualDisplay));
                        }

                        @Override public void onResumed() {
                            ClusterLog.i(TAG, "mirror display resumed: id=" + getDisplayId()
                                    + " state=" + displayStateName(virtualDisplay));
                        }

                        @Override public void onStopped() {
                            ClusterLog.w(TAG, "mirror display stopped: id=" + getDisplayId()
                                    + " state=" + displayStateName(virtualDisplay)
                                    + " released=" + released);
                        }
                    },
                    mainHandler);
            if (virtualDisplay == null) {
                throw new IllegalStateException("镜像 VirtualDisplay 返回 null");
            }
            ClusterLog.i(TAG, "mirror capture started: " + sourceWidth + "x" + sourceHeight
                    + "@" + sourceDensityDpi + " id=" + virtualDisplay.getDisplay().getDisplayId()
                    + " state=" + displayStateName(virtualDisplay)
                    + " flags=0x" + Integer.toHexString(virtualDisplay.getDisplay().getFlags()));
        } catch (Throwable t) {
            lastError = t.getMessage() == null ? t.toString() : t.getMessage();
            ClusterLog.e(TAG, "start mirror capture failed", t);
            throw new IllegalStateException("启动中控镜像失败: " + lastError, t);
        }
    }

    public boolean launchAppOnDefaultDisplay(String packageName) {
        ClusterLog.i(TAG, "mirror launchApp request: " + packageName);
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) {
            lastError = "找不到可启动 Activity: " + packageName;
            return false;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        android.content.ComponentName resolved = launch.resolveActivity(context.getPackageManager());
        ClusterLog.i(TAG, "mirror launch intent: pkg=" + packageName
                + " component=" + launch.getComponent() + " resolved=" + resolved
                + " flags=0x" + Integer.toHexString(launch.getFlags()));
        return startOnDefaultDisplay(packageName, launch);
    }

    public boolean startActivityOnDefaultDisplay(Class<?> activityClass) {
        Intent launch = new Intent(context, activityClass);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return startOnDefaultDisplay(activityClass.getName(), launch);
    }

    private boolean startOnDefaultDisplay(String label, Intent intent) {
        try {
            ClusterLog.i(TAG, "startActivity on default display: " + label + " intent=" + intent);
            context.startActivity(intent);
            ClusterLog.i(TAG, "startActivity returned: " + label);
            return true;
        } catch (Throwable t) {
            lastError = t.getMessage() == null ? t.toString() : t.getMessage();
            ClusterLog.e(TAG, "launch " + label + " on default display failed", t);
            return false;
        }
    }

    public String getLastError() {
        return lastError;
    }

    public int getDisplayId() {
        return virtualDisplay != null && virtualDisplay.getDisplay() != null
                ? virtualDisplay.getDisplay().getDisplayId() : -1;
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

    public void stop() {
        released = true;
        if (virtualDisplay != null) {
            ClusterLog.i(TAG, "stop mirror display: id=" + getDisplayId()
                    + " state=" + displayStateName(virtualDisplay));
            try {
                virtualDisplay.release();
            } catch (Throwable t) {
                ClusterLog.w(TAG, "release mirror display failed", t);
            }
            virtualDisplay = null;
        }
    }
}
