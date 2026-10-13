package org.clusterprojector.display;

import android.content.Context;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

/** FX11 仪表投屏控制器：直出优先，失败后自动切到无编码 GL 镜像。 */
public final class CastController {

    private static final String TAG = "ClusterCast";

    public static final int MODE_DIRECT = 0;
    public static final int MODE_MIRROR = 1;

    private final Context context;
    private final DisplayTarget clusterInfo;
    private final Display clusterDisplay;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private MediaProjection mediaProjection;
    private OverlayWindowHost overlay;
    private VirtualScreenHost directDisplay;
    private MirrorCaptureHost mirrorCapture;

    private WindowGeometry bounds;
    private float opacity = 1f;
    private boolean active;
    private int mode = MODE_DIRECT;
    private boolean directReady;
    private boolean directSurfaceAttached;
    private boolean mirrorReady;
    private boolean switchingMode;
    private String pendingPackage;
    private Class<?> pendingTestActivity;
    private Listener listener;

    public interface Listener {
        void onVirtualDisplayReady(int mode, int virtualDisplayId);
        void onModeChanged(int mode, String reason);
        void onAppLaunched(String packageName, boolean success, String error);
        void onError(String message, Throwable cause);
    }

    public CastController(Context context, DisplayTarget clusterInfo, Display clusterDisplay) {
        this.context = context.getApplicationContext();
        this.clusterInfo = clusterInfo;
        this.clusterDisplay = clusterDisplay;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isRunning() { return active; }
    public boolean isVirtualReady() { return mode == MODE_DIRECT ? directReady : mirrorReady; }
    public int getMode() { return mode; }
    public WindowGeometry getBounds() { return bounds; }
    public float getAlpha() { return opacity; }

    public int getVirtualDisplayId() {
        if (mode == MODE_MIRROR) return Display.DEFAULT_DISPLAY;
        return directDisplay == null ? -1 : directDisplay.getDisplayId();
    }

    public void start(MediaProjection projection) {
        if (active) return;
        active = true;
        mode = MODE_DIRECT;
        directReady = false;
        directSurfaceAttached = false;
        mirrorReady = false;
        mediaProjection = projection;
        bounds = Fx11DisplayProfile.defaultDirectBounds();

        DisplayMetrics centerMetrics = getDefaultDisplayMetrics();
        overlay = new OverlayWindowHost(
                context,
                clusterDisplay,
                clusterInfo.getWidth(),
                clusterInfo.getHeight(),
                centerMetrics.widthPixels > 0 ? centerMetrics.widthPixels : Fx11DisplayProfile.CENTER_WIDTH,
                centerMetrics.heightPixels > 0 ? centerMetrics.heightPixels : Fx11DisplayProfile.CENTER_HEIGHT,
                bounds);
        overlay.setSurfaceCallback(new OverlayWindowHost.SurfaceCallback() {
            @Override public void onDirectSurfaceAvailable(android.view.Surface surface) {}

            @Override public void onDirectSurfaceChanged(android.view.Surface surface, int width, int height) {
                attachDirectDisplay(surface);
            }

            @Override public void onDirectSurfaceDestroyed() {
                Log.i(TAG, "direct surface destroyed");
                directReady = false;
                directSurfaceAttached = false;
            }

            @Override public void onMirrorInputSurfaceReady(android.view.Surface surface) {
                attachMirrorCapture(surface);
            }

            @Override public void onMirrorInputSurfaceDestroyed() {
                mirrorReady = false;
                if (mirrorCapture != null) mirrorCapture.stop();
            }
        });
        overlay.start();
        notifyModeChanged(MODE_DIRECT, "默认直出模式");
    }

    private void attachDirectDisplay(android.view.Surface surface) {
        if (!active || mode != MODE_DIRECT || switchingMode) return;
        try {
            if (directDisplay == null || !directSurfaceAttached) {
                if (directDisplay != null) directDisplay.release();
                directDisplay = new VirtualScreenHost(context, mediaProjection);
                directDisplay.create(
                        clusterInfo.getWidth(),
                        clusterInfo.getHeight(),
                        clusterInfo.getDensityDpi(),
                        surface);
            } else {
                // SurfaceView 尺寸固定；正常只会 attach 一次。异常重建时只替换 Surface，避免重建任务栈。
                directDisplay.setSurface(surface);
            }
            directSurfaceAttached = true;
            directReady = true;
            if (listener != null) {
                listener.onVirtualDisplayReady(MODE_DIRECT, directDisplay.getDisplayId());
            }
            flushPendingLaunch();
        } catch (Throwable t) {
            Log.w(TAG, "direct display unavailable, fallback to mirror", t);
            fallbackToMirror(null, "直出虚拟屏创建失败：" + t.getMessage());
        }
    }

    private void attachMirrorCapture(android.view.Surface inputSurface) {
        if (!active || mode != MODE_MIRROR) return;
        try {
            DisplayMetrics metrics = getDefaultDisplayMetrics();
            int sourceWidth = metrics.widthPixels > 0
                    ? metrics.widthPixels : Fx11DisplayProfile.CENTER_WIDTH;
            int sourceHeight = metrics.heightPixels > 0
                    ? metrics.heightPixels : Fx11DisplayProfile.CENTER_HEIGHT;
            int sourceDpi = metrics.densityDpi > 0
                    ? metrics.densityDpi : Fx11DisplayProfile.CENTER_DENSITY_DPI;

            if (mirrorCapture == null) mirrorCapture = new MirrorCaptureHost(context, mediaProjection);
            mirrorCapture.start(sourceWidth, sourceHeight, sourceDpi, inputSurface);
            mirrorReady = true;
            if (listener != null) listener.onVirtualDisplayReady(MODE_MIRROR, Display.DEFAULT_DISPLAY);
            flushPendingLaunch();
        } catch (Throwable t) {
            active = false;
            if (listener != null) listener.onError(t.getMessage(), t);
        }
    }

    private void flushPendingLaunch() {
        Class<?> test = pendingTestActivity;
        String pkg = pendingPackage;
        pendingTestActivity = null;
        pendingPackage = null;
        if (test != null) launchTestScreen(test);
        else if (pkg != null) launchApp(pkg);
    }

    public boolean launchApp(String packageName) {
        if (!active) return false;
        if (!isVirtualReady()) {
            pendingPackage = packageName;
            Log.i(TAG, "display not ready, pending " + packageName);
            return true;
        }

        if (mode == MODE_DIRECT) {
            boolean ok = directDisplay.launchApp(packageName);
            if (ok) {
                if (listener != null) listener.onAppLaunched(packageName, true, null);
                return true;
            }
            fallbackToMirror(packageName, "直出启动目标 App 失败（可能是系统/预装应用多屏策略限制）：" + directDisplay.getLastError());
            return true;
        }

        boolean ok = mirrorCapture.launchAppOnDefaultDisplay(packageName);
        String error = ok ? null : mirrorCapture.getLastError();
        if (listener != null) listener.onAppLaunched(packageName, ok, error);
        return ok;
    }

    public boolean launchTestScreen(Class<?> testActivityClass) {
        if (!active) return false;
        if (!isVirtualReady()) {
            pendingTestActivity = testActivityClass;
            return true;
        }

        if (mode == MODE_DIRECT) {
            boolean ok = directDisplay.launchActivity(testActivityClass);
            String label = testActivityClass.getName();
            if (ok) {
                if (listener != null) listener.onAppLaunched(label, true, null);
                return true;
            }
            fallbackToMirror(null, "直出测试画面失败：" + directDisplay.getLastError());
            return launchTestScreen(testActivityClass);
        }

        boolean ok = mirrorCapture.startActivityOnDefaultDisplay(testActivityClass);
        String label = testActivityClass.getName();
        if (listener != null) listener.onAppLaunched(label, ok, ok ? null : mirrorCapture.getLastError());
        return ok;
    }

    private void fallbackToMirror(String packageName, String reason) {
        if (!active || mode == MODE_MIRROR) return;
        switchingMode = true;
        // null 表示直出虚拟屏创建阶段失败，不能覆盖此前已经排队的 App。
        if (packageName != null) pendingPackage = packageName;
        Log.w(TAG, "fallback to mirror: " + reason);
        directReady = false;
        directSurfaceAttached = false;
        if (directDisplay != null) {
            directDisplay.release();
            directDisplay = null;
        }
        mode = MODE_MIRROR;
        bounds = Fx11DisplayProfile.defaultMirrorBounds();
        overlay.setBounds(bounds);
        overlay.showMirror();
        switchingMode = false;
        notifyModeChanged(MODE_MIRROR, reason);
        if (mirrorReady) flushPendingLaunch();
    }

    public void setBounds(WindowGeometry newBounds) {
        int width = Math.max(1, Math.min(newBounds.width, clusterInfo.getWidth()));
        int height = Math.max(1, Math.min(newBounds.height, clusterInfo.getHeight()));
        int x = Math.max(0, Math.min(newBounds.x, clusterInfo.getWidth() - width));
        int y = Math.max(0, Math.min(newBounds.y, clusterInfo.getHeight() - height));
        bounds = new WindowGeometry(x, y, width, height);
        if (overlay != null) overlay.setBounds(bounds);
    }

    public void setAlpha(float alpha) {
        opacity = Math.max(0f, Math.min(1f, alpha));
        if (overlay != null) overlay.setAlpha(opacity);
    }

    public void stop() {
        if (!active && overlay == null) return;
        active = false;
        directReady = false;
        directSurfaceAttached = false;
        mirrorReady = false;
        pendingPackage = null;
        pendingTestActivity = null;
        if (directDisplay != null) {
            directDisplay.release();
            directDisplay = null;
        }
        if (mirrorCapture != null) {
            mirrorCapture.stop();
            mirrorCapture = null;
        }
        if (overlay != null) {
            overlay.stop();
            overlay = null;
        }
        mediaProjection = null;
    }

    private DisplayMetrics getDefaultDisplayMetrics() {
        DisplayMetrics metrics = new DisplayMetrics();
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) wm.getDefaultDisplay().getRealMetrics(metrics);
        } catch (Throwable t) {
            Log.w(TAG, "get default display metrics failed", t);
        }
        return metrics;
    }

    private void notifyModeChanged(int newMode, String reason) {
        mainHandler.post(() -> {
            if (listener != null) listener.onModeChanged(newMode, reason);
        });
    }
}
