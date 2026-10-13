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

/** 创建 FX11 仪表虚拟显示，并尝试把指定 Activity 启动到该 Display。 */
public final class VirtualScreenHost {

    private static final String TAG = "ClusterVirtual";
    private static final String DISPLAY_NAME = "SecondaryScreenMirror";

    private final Context ctx;
    private final MediaProjection mediaProjection;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private VirtualDisplay vd;
    private String lastError = "尚未启动";

    public VirtualScreenHost(Context context, MediaProjection projection) {
        this.ctx = context.getApplicationContext();
        this.mediaProjection = projection;
    }

    public void create(int width, int height, int densityDpi, Surface surface) {
        release();
        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;

        try {
            vd = mediaProjection.createVirtualDisplay(
                    DISPLAY_NAME,
                    width,
                    height,
                    densityDpi,
                    flags,
                    surface,
                    new VirtualDisplay.Callback() {
                        @Override public void onPaused() {
                            Log.i(TAG, "virtual display paused");
                        }

                        @Override public void onResumed() {
                            Log.i(TAG, "virtual display resumed");
                        }

                        @Override public void onStopped() {
                            Log.i(TAG, "virtual display stopped");
                        }
                    },
                    mainHandler);
            if (vd == null) {
                lastError = "createVirtualDisplay 返回 null";
                throw new IllegalStateException(lastError);
            }
            Log.i(TAG, "created " + width + "x" + height + "@" + densityDpi
                    + " id=" + vd.getDisplay().getDisplayId());
        } catch (Throwable t) {
            lastError = t.getMessage() == null ? t.toString() : t.getMessage();
            Log.e(TAG, "create virtual display failed", t);
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
        Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) {
            lastError = "找不到可启动 Activity: " + packageName;
            Log.w(TAG, lastError);
            return false;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return launchOnVirtualDisplay(packageName, launch);
    }

    public boolean launchActivity(Class<?> activityClass) {
        if (vd == null) {
            lastError = "虚拟屏尚未创建";
            return false;
        }
        Intent launch = new Intent(ctx, activityClass);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return launchOnVirtualDisplay(activityClass.getName(), launch);
    }

    private boolean launchOnVirtualDisplay(String label, Intent launch) {
        try {
            android.app.ActivityOptions options = android.app.ActivityOptions.makeBasic();
            options.setLaunchDisplayId(vd.getDisplay().getDisplayId());
            ctx.startActivity(launch, options.toBundle());
            lastError = null;
            Log.i(TAG, "launch " + label + " on displayId=" + vd.getDisplay().getDisplayId());
            return true;
        } catch (Throwable t) {
            lastError = t.getMessage() == null ? t.toString() : t.getMessage();
            Log.e(TAG, "launch " + label + " on virtual display failed", t);
            return false;
        }
    }

    public void setSurface(Surface surface) {
        if (vd != null) {
            vd.setSurface(surface);
        }
    }

    public void release() {
        if (vd != null) {
            try {
                vd.release();
            } catch (Throwable t) {
                Log.w(TAG, "release virtual display failed", t);
            }
            vd = null;
        }
    }
}
