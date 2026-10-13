package org.clusterprojector.display;

import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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
    private String lastError = "尚未启动镜像";

    public MirrorCaptureHost(Context context, MediaProjection projection) {
        this.context = context.getApplicationContext();
        this.projection = projection;
    }

    public void start(int sourceWidth, int sourceHeight, int sourceDensityDpi, Surface inputSurface) {
        stop();
        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;
        try {
            virtualDisplay = projection.createVirtualDisplay(
                    DISPLAY_NAME,
                    sourceWidth,
                    sourceHeight,
                    sourceDensityDpi,
                    flags,
                    inputSurface,
                    new VirtualDisplay.Callback() {
                        @Override public void onPaused() {
                            Log.i(TAG, "mirror display paused");
                        }

                        @Override public void onResumed() {
                            Log.i(TAG, "mirror display resumed");
                        }

                        @Override public void onStopped() {
                            Log.i(TAG, "mirror display stopped");
                        }
                    },
                    mainHandler);
            if (virtualDisplay == null) {
                throw new IllegalStateException("镜像 VirtualDisplay 返回 null");
            }
            Log.i(TAG, "mirror capture started: " + sourceWidth + "x" + sourceHeight
                    + "@" + sourceDensityDpi);
        } catch (Throwable t) {
            lastError = t.getMessage() == null ? t.toString() : t.getMessage();
            Log.e(TAG, "start mirror capture failed", t);
            throw new IllegalStateException("启动中控镜像失败: " + lastError, t);
        }
    }

    public boolean launchAppOnDefaultDisplay(String packageName) {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) {
            lastError = "找不到可启动 Activity: " + packageName;
            return false;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return startOnDefaultDisplay(packageName, launch);
    }

    public boolean startActivityOnDefaultDisplay(Class<?> activityClass) {
        Intent launch = new Intent(context, activityClass);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return startOnDefaultDisplay(activityClass.getName(), launch);
    }

    private boolean startOnDefaultDisplay(String label, Intent intent) {
        try {
            context.startActivity(intent);
            Log.i(TAG, "launch " + label + " on default display for mirror");
            return true;
        } catch (Throwable t) {
            lastError = t.getMessage() == null ? t.toString() : t.getMessage();
            Log.e(TAG, "launch " + label + " on default display failed", t);
            return false;
        }
    }

    public String getLastError() {
        return lastError;
    }

    public void stop() {
        if (virtualDisplay != null) {
            try {
                virtualDisplay.release();
            } catch (Throwable t) {
                Log.w(TAG, "release mirror display failed", t);
            }
            virtualDisplay = null;
        }
    }
}
