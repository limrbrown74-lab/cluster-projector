package org.clusterprojector.display;

import android.content.Context;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
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
    private boolean mirrorFrameReceived;
    private boolean switchingMode;
    private String pendingPackage;
    private Class<?> pendingTestActivity;
    private Runnable readinessWatchdog;
    private Listener listener;

    public interface Listener {
        void onVirtualDisplayReady(int mode, int virtualDisplayId);
        void onModeChanged(int mode, String reason);
        void onAppLaunched(String packageName, boolean success, String error);
        void onMirrorFirstFrame();
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
    public String getDiagnosticState() { return stateSnapshot(); }

    public int getVirtualDisplayId() {
        if (mode == MODE_MIRROR) return Display.DEFAULT_DISPLAY;
        return directDisplay == null ? -1 : directDisplay.getDisplayId();
    }

    public void start(MediaProjection projection) {
        if (active) {
            ClusterLog.i(TAG, "start ignored: controller already active");
            return;
        }
        active = true;
        mode = MODE_DIRECT;
        directReady = false;
        directSurfaceAttached = false;
        mirrorReady = false;
        mirrorFrameReceived = false;
        switchingMode = false;
        mediaProjection = projection;
        bounds = Fx11DisplayProfile.defaultDirectBounds();

        DisplayMetrics centerMetrics = getDefaultDisplayMetrics();
        ClusterLog.i(TAG, "start: target=" + clusterInfo
                + " resolvedDisplayId=" + clusterDisplay.getDisplayId()
                + " defaultDisplay=" + centerMetrics.widthPixels + "x" + centerMetrics.heightPixels
                + "@" + centerMetrics.densityDpi + " directBounds=" + bounds
                + " projection=" + (projection != null));
        overlay = new OverlayWindowHost(
                context,
                clusterDisplay,
                clusterInfo.getWidth(),
                clusterInfo.getHeight(),
                centerMetrics.widthPixels > 0 ? centerMetrics.widthPixels : Fx11DisplayProfile.CENTER_WIDTH,
                centerMetrics.heightPixels > 0 ? centerMetrics.heightPixels : Fx11DisplayProfile.CENTER_HEIGHT,
                bounds);
        overlay.setSurfaceCallback(new OverlayWindowHost.SurfaceCallback() {
            @Override public void onDirectSurfaceAvailable(android.view.Surface surface) {
                ClusterLog.i(TAG, "direct surface available: valid="
                        + (surface != null && surface.isValid()));
                attachDirectDisplay(surface);
            }

            @Override public void onDirectSurfaceChanged(android.view.Surface surface, int width, int height) {
                ClusterLog.i(TAG, "direct surface changed: " + width + "x" + height
                        + " valid=" + (surface != null && surface.isValid())
                        + " attached=" + directSurfaceAttached);
                attachDirectDisplay(surface);
            }

            @Override public void onDirectSurfaceDestroyed() {
                ClusterLog.w(TAG, "direct surface destroyed: state=" + stateSnapshot());
                directReady = false;
                directSurfaceAttached = false;
            }

            @Override public void onMirrorInputSurfaceReady(android.view.Surface surface) {
                ClusterLog.i(TAG, "mirror input surface ready: valid="
                        + (surface != null && surface.isValid()));
                attachMirrorCapture(surface);
            }

            @Override public void onMirrorInputSurfaceDestroyed() {
                ClusterLog.w(TAG, "mirror input surface destroyed: state=" + stateSnapshot());
                mirrorReady = false;
                mirrorFrameReceived = false;
                if (mirrorCapture != null) mirrorCapture.stop();
            }

            @Override public void onMirrorFirstFrame() {
                boolean first = !mirrorFrameReceived;
                mirrorFrameReceived = true;
                ClusterLog.i(TAG, "mirror first frame received" + (first ? " (FIRST)" : "")
                        + " state=" + stateSnapshot());
                if (first && readinessWatchdog != null) {
                    mainHandler.removeCallbacks(readinessWatchdog);
                    ClusterLog.i(TAG, "readiness watchdog cancelled after first mirror frame");
                }
                if (first && listener != null) {
                    mainHandler.post(() -> {
                        if (listener != null) listener.onMirrorFirstFrame();
                    });
                }
            }
        });
        overlay.start();
        ClusterLog.i(TAG, "overlay.start returned: " + stateSnapshot());
        notifyModeChanged(MODE_DIRECT, "默认直出模式");
        scheduleReadinessWatchdog();
    }

    private void attachDirectDisplay(android.view.Surface surface) {
        if (!active || mode != MODE_DIRECT || switchingMode) {
            ClusterLog.i(TAG, "skip attach direct: active=" + active + " mode=" + mode
                    + " switching=" + switchingMode);
            return;
        }
        if (surface == null || !surface.isValid()) {
            ClusterLog.w(TAG, "skip attach direct: surface invalid", null);
            return;
        }
        try {
            if (directDisplay == null || !directSurfaceAttached) {
                ClusterLog.i(TAG, "creating direct VirtualDisplay: "
                        + clusterInfo.getWidth() + "x" + clusterInfo.getHeight()
                        + "@" + clusterInfo.getDensityDpi());
                if (directDisplay != null) directDisplay.release();
                directDisplay = new VirtualScreenHost(context, mediaProjection);
                directDisplay.create(
                        clusterInfo.getWidth(),
                        clusterInfo.getHeight(),
                        clusterInfo.getDensityDpi(),
                        surface);
            } else {
                // SurfaceView 尺寸固定；正常只会 attach 一次。异常重建时只替换 Surface，避免重建任务栈。
                ClusterLog.i(TAG, "replacing direct VirtualDisplay surface");
                directDisplay.setSurface(surface);
            }
            directSurfaceAttached = true;
            directReady = true;
            ClusterLog.i(TAG, "direct VirtualDisplay ready: id="
                    + directDisplay.getDisplayId() + " " + stateSnapshot());
            if (listener != null) {
                listener.onVirtualDisplayReady(MODE_DIRECT, directDisplay.getDisplayId());
            }
            flushPendingLaunch();
        } catch (Throwable t) {
            ClusterLog.w(TAG, "direct display unavailable, fallback to mirror; "
                    + stateSnapshot(), t);
            fallbackToMirror(null, "直出虚拟屏创建失败：" + t.getMessage());
        }
    }

    private void attachMirrorCapture(android.view.Surface inputSurface) {
        if (!active || mode != MODE_MIRROR) {
            ClusterLog.i(TAG, "skip attach mirror: active=" + active + " mode=" + mode);
            return;
        }
        if (inputSurface == null || !inputSurface.isValid()) {
            ClusterLog.w(TAG, "skip attach mirror: surface invalid", null);
            return;
        }
        try {
            ClusterLog.i(TAG, "attaching mirror capture: " + stateSnapshot());
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
            ClusterLog.i(TAG, "mirror capture ready: source=" + sourceWidth + "x"
                    + sourceHeight + "@" + sourceDpi);
            if (listener != null) listener.onVirtualDisplayReady(MODE_MIRROR, Display.DEFAULT_DISPLAY);
            flushPendingLaunch();
        } catch (Throwable t) {
            ClusterLog.e(TAG, "attach mirror capture failed; " + stateSnapshot(), t);
            active = false;
            if (listener != null) listener.onError("镜像捕获启动失败: " + t.getMessage(), t);
        }
    }

    private void flushPendingLaunch() {
        Class<?> test = pendingTestActivity;
        String pkg = pendingPackage;
        pendingTestActivity = null;
        pendingPackage = null;
        ClusterLog.i(TAG, "flush pending launch: test=" + test + " pkg=" + pkg
                + " mode=" + modeName(mode) + " state=" + stateSnapshot());
        if (test != null) launchTestScreen(test);
        else if (pkg != null) launchApp(pkg);
        else ClusterLog.i(TAG, "no pending launch target");
    }

    public boolean launchApp(String packageName) {
        ClusterLog.i(TAG, "launchApp request: " + packageName + " " + stateSnapshot());
        if (!active) {
            ClusterLog.w(TAG, "launchApp rejected: controller inactive", null);
            return false;
        }
        if (!isVirtualReady()) {
            pendingPackage = packageName;
            ClusterLog.i(TAG, "display not ready, pending " + packageName);
            return true;
        }

        if (mode == MODE_DIRECT) {
            boolean ok;
            try {
                ok = directDisplay.launchApp(packageName);
            } catch (Throwable t) {
                ok = false;
                ClusterLog.e(TAG, "direct launchApp unexpected throw: " + packageName, t);
            }
            ClusterLog.i(TAG, "direct launchApp result: " + packageName + " ok=" + ok
                    + " lastError=" + directDisplay.getLastError());
            if (ok) {
                scheduleDirectStateObservation(packageName);
                if (listener != null) listener.onAppLaunched(packageName, true, null);
                return true;
            }
            ClusterLog.w(TAG, "direct launchApp failed, will fallback: "
                    + packageName + " error=" + directDisplay.getLastError(), null);
            fallbackToMirror(packageName, "直出启动目标 App 失败（可能是系统/预装应用多屏策略限制）：" + directDisplay.getLastError());
            return true;
        }

        boolean ok = mirrorCapture.launchAppOnDefaultDisplay(packageName);
        String error = ok ? null : mirrorCapture.getLastError();
        ClusterLog.i(TAG, "mirror launchApp result: " + packageName + " ok=" + ok
                + (error == null ? "" : " error=" + error));
        if (listener != null) listener.onAppLaunched(packageName, ok, error);
        return ok;
    }

    public boolean launchTestScreen(Class<?> testActivityClass) {
        ClusterLog.i(TAG, "launchTestScreen request: " + testActivityClass + " "
                + stateSnapshot());
        if (!active) {
            ClusterLog.w(TAG, "launchTestScreen rejected: controller inactive", null);
            return false;
        }
        if (!isVirtualReady()) {
            pendingTestActivity = testActivityClass;
            ClusterLog.i(TAG, "display not ready, pending test screen");
            return true;
        }

        if (mode == MODE_DIRECT) {
            boolean ok;
            try {
                ok = directDisplay.launchActivity(testActivityClass);
            } catch (Throwable t) {
                ok = false;
                ClusterLog.e(TAG, "direct launchTest unexpected throw", t);
            }
            String label = testActivityClass.getName();
            ClusterLog.i(TAG, "direct launchTest result: ok=" + ok
                    + " lastError=" + directDisplay.getLastError());
            if (ok) {
                scheduleDirectStateObservation(label);
                if (listener != null) listener.onAppLaunched(label, true, null);
                return true;
            }
            fallbackToMirror(null, "直出测试画面失败：" + directDisplay.getLastError());
            return launchTestScreen(testActivityClass);
        }

        boolean ok = mirrorCapture.startActivityOnDefaultDisplay(testActivityClass);
        String label = testActivityClass.getName();
        ClusterLog.i(TAG, "mirror launchTest result: ok=" + ok
                + " lastError=" + mirrorCapture.getLastError());
        if (listener != null) listener.onAppLaunched(label, ok, ok ? null : mirrorCapture.getLastError());
        return ok;
    }

    private void fallbackToMirror(String packageName, String reason) {
        if (!active || mode == MODE_MIRROR) {
            ClusterLog.i(TAG, "fallback ignored: active=" + active + " mode=" + modeName(mode));
            return;
        }
        switchingMode = true;
        // null 表示直出虚拟屏创建阶段失败，不能覆盖此前已经排队的 App。
        if (packageName != null) pendingPackage = packageName;
        ClusterLog.w(TAG, "fallback to mirror: " + reason + " before=" + stateSnapshot(), null);
        directReady = false;
        directSurfaceAttached = false;
        if (directDisplay != null) {
            directDisplay.release();
            directDisplay = null;
        }
        mode = MODE_MIRROR;
        mirrorFrameReceived = false;
        bounds = Fx11DisplayProfile.defaultMirrorBounds();
        try {
            overlay.setBounds(bounds);
            overlay.showMirror();
            ClusterLog.i(TAG, "overlay switched to mirror view, waiting GL surface; bounds="
                    + bounds);
        } catch (Throwable t) {
            switchingMode = false;
            active = false;
            ClusterLog.e(TAG, "switch overlay to mirror failed; " + stateSnapshot(), t);
            if (listener != null) listener.onError("切换镜像窗口失败: " + t.getMessage(), t);
            return;
        }
        switchingMode = false;
        notifyModeChanged(MODE_MIRROR, reason);
        scheduleReadinessWatchdog();
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
        ClusterLog.i(TAG, "stop requested: " + stateSnapshot());
        if (!active && overlay == null) return;
        if (readinessWatchdog != null) mainHandler.removeCallbacks(readinessWatchdog);
        active = false;
        directReady = false;
        directSurfaceAttached = false;
        mirrorReady = false;
        mirrorFrameReceived = false;
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
        ClusterLog.i(TAG, "stop completed");
    }

    private void scheduleDirectStateObservation(String label) {
        Runnable observe = new Runnable() {
            @Override public void run() {
                if (!active || mode != MODE_DIRECT || directDisplay == null) return;
                ClusterLog.i(TAG, "post-direct-launch observation: target=" + label
                        + " vdId=" + directDisplay.getDisplayId()
                        + " state=" + directDisplay.getDisplayStateName()
                        + " " + stateSnapshot());
            }
        };
        mainHandler.postDelayed(observe, 500);
        mainHandler.postDelayed(observe, 3000);
        mainHandler.postDelayed(observe, 8000);
    }

    private void scheduleReadinessWatchdog() {
        if (readinessWatchdog != null) mainHandler.removeCallbacks(readinessWatchdog);
        readinessWatchdog = new Runnable() {
            @Override public void run() {
                if (!active) return;
                boolean ready = mode == MODE_DIRECT ? directReady : mirrorFrameReceived;
                ClusterLog.w(TAG, "readiness check after 8s: ready=" + ready + " "
                        + stateSnapshot(), null);
                if (!ready) {
                    active = false;
                    String message = "仪表窗口 8 秒内未就绪，请点击“查看日志文件”分享完整诊断日志；"
                            + stateSnapshot();
                    if (listener != null) listener.onError(message, null);
                }
            }
        };
        mainHandler.postDelayed(readinessWatchdog, 8000);
        ClusterLog.i(TAG, "readiness watchdog scheduled for 8s");
    }

    private String stateSnapshot() {
        return "active=" + active + " mode=" + modeName(mode)
                + " overlay=" + (overlay != null && overlay.isAdded())
                + " directReady=" + directReady
                + " directSurfaceAttached=" + directSurfaceAttached
                + " mirrorCaptureReady=" + mirrorReady
                + " mirrorFrameReceived=" + mirrorFrameReceived + " switching=" + switchingMode
                + " pendingPkg=" + pendingPackage + " pendingTest="
                + (pendingTestActivity != null) + " vdId=" + getVirtualDisplayId();
    }

    private static String modeName(int mode) {
        return mode == MODE_MIRROR ? "MIRROR" : "DIRECT";
    }

    private DisplayMetrics getDefaultDisplayMetrics() {
        DisplayMetrics metrics = new DisplayMetrics();
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) wm.getDefaultDisplay().getRealMetrics(metrics);
        } catch (Throwable t) {
            ClusterLog.w(TAG, "get default display metrics failed", t);
        }
        return metrics;
    }

    private void notifyModeChanged(int newMode, String reason) {
        mainHandler.post(() -> {
            if (listener != null) listener.onModeChanged(newMode, reason);
        });
    }
}
