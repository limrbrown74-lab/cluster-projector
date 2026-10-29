package org.clusterprojector.display;

import android.content.Context;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

/** FX11 仪表投屏控制器：顶层 Overlay 镜像优先，失败后回退 Presentation/直出。 */
public final class CastController {

    private static final String TAG = "ClusterCast";

    public static final int MODE_DIRECT = 0;
    public static final int MODE_MIRROR = 1;
    /** v1.3.0：FX11 厂商同款链路，Presentation 内 SurfaceView 直连 MediaProjection。 */
    public static final int MODE_VENDOR_MIRROR = 2;
    /**
     * v1.5.0：ECARX/VHAL 物理切屏链路（移植自 DiPlay/carlife_mod）。
     * 先通过 ECARX XUI AdaptAPI (0x20320500) 或 VHAL HIDL (0x21408636) 把仪表
     * 物理屏从 QNX 切到 Android 显示源，再走标准镜像管道。
     */
    public static final int MODE_ECARX_SWITCH = 3;

    private final Context context;
    private final Context ownerContext;
    private final DisplayTarget clusterInfo;
    private final Display clusterDisplay;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private MediaProjection mediaProjection;
    private VendorMirrorPresentation vendorPresentation;
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
    private boolean vendorReady;
    private boolean legacyPipelineStarted;
    private boolean switchingMode;
    private String pendingPackage;
    private Class<?> pendingTestActivity;
    private Runnable readinessWatchdog;
    private Listener listener;

    // v1.5.0: ECARX/VHAL 物理切屏
    private boolean switchRequested;
    private boolean switchActive;
    private String switchPath = "NONE";
    private Thread switchWorker;

    public interface Listener {
        void onVirtualDisplayReady(int mode, int virtualDisplayId);
        void onModeChanged(int mode, String reason);
        void onAppLaunched(String packageName, boolean success, String error);
        void onMirrorFirstFrame();
        void onPhysicalSwitchDone(boolean ok, String path);
        void onError(String message, Throwable cause);
    }

    public CastController(Context context, DisplayTarget clusterInfo, Display clusterDisplay) {
        this.ownerContext = context;
        this.context = context.getApplicationContext();
        this.clusterInfo = clusterInfo;
        this.clusterDisplay = clusterDisplay;
        DashboardConfig.init(this.context);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isRunning() { return active; }
    public boolean isSwitchActive() { return switchActive; }
    public String getSwitchPath() { return switchPath; }
    public boolean isVirtualReady() {
        if (mode == MODE_VENDOR_MIRROR) return vendorReady;
        return mode == MODE_DIRECT ? directReady : mirrorReady;
    }
    public int getMode() { return mode; }
    public WindowGeometry getBounds() { return bounds; }
    public float getAlpha() { return opacity; }
    public String getDiagnosticState() { return stateSnapshot(); }

    public String getHostName() {
        if (vendorPresentation != null) return "VENDOR_PRESENTATION";
        return overlay == null ? "NONE" : overlay.getHost().name();
    }

    public String getHostDetail() {
        if (vendorPresentation != null) {
            return "VENDOR_SURFACE_VIEW_DIRECT_MEDIA_PROJECTION ready=" + vendorReady
                    + " surfaceAlive=" + vendorPresentation.isSurfaceAlive();
        }
        return overlay == null ? "legacy overlay not created" : overlay.getHostDetail();
    }

    public int getVirtualDisplayId() {
        if (mode == MODE_VENDOR_MIRROR) {
            return vendorPresentation == null ? -1 : vendorPresentation.getVirtualDisplayId();
        }
        if (mode == MODE_MIRROR) {
            return mirrorCapture == null ? -1 : mirrorCapture.getDisplayId();
        }
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
        vendorReady = false;
        legacyPipelineStarted = false;
        switchingMode = false;
        mediaProjection = projection;
        bounds = Fx11DisplayProfile.defaultMirrorBounds();

        DisplayMetrics centerMetrics = getDefaultDisplayMetrics();
        ClusterLog.i(TAG, "start: target=" + clusterInfo
                + " resolvedDisplayId=" + clusterDisplay.getDisplayId()
                + " defaultDisplay=" + centerMetrics.widthPixels + "x" + centerMetrics.heightPixels
                + "@" + centerMetrics.densityDpi + " directBounds=" + bounds
                + " projection=" + (projection != null));
        // v1.5.0: 先物理切屏（ECARX 三屏优先，VHAL 叠加兜底），把仪表屏从 QNX
        // 切到 Android 显示源。这是 DiPlay/carlife_mod 已验证成功的先决条件：
        // 不切屏的话 Android 窗口/虚拟屏内容到不了 QNX 合成器。
        beginPhysicalSwitch();
        startOverlayMirrorOrFallback(centerMetrics);
    }

    // ------------------------------------------------------------------
    // v1.5.0: ECARX/VHAL 物理切屏（移植 DiPlay Fx11ThreeScreenController）
    // ------------------------------------------------------------------

    /** 请求物理切屏。子线程执行，不阻塞主流程。 */
    private void beginPhysicalSwitch() {
        if (switchRequested || switchActive) {
            ClusterLog.i(TAG, "physical switch already requested/active");
            return;
        }
        switchRequested = true;
        final Context appCtx = context;
        switchWorker = new Thread(() -> {
            boolean ok = ClusterProjectionSwitch.enable(appCtx, true, true);
            ClusterLog.i(TAG, "physical switch worker done ok=" + ok
                    + " path=" + ClusterProjectionSwitch.activeName()
                    + " active=" + ClusterProjectionSwitch.isActive());
            mainHandler.post(() -> {
                switchActive = ClusterProjectionSwitch.isActive();
                switchPath = ClusterProjectionSwitch.activeName();
                if (listener != null) {
                    listener.onPhysicalSwitchDone(ok, switchPath);
                }
            });
        }, "cluster-physical-switch");
        switchWorker.setDaemon(true);
        switchWorker.start();
    }

    /** 停止时恢复原厂仪表（QNX 渲染）。 */
    private void endPhysicalSwitch() {
        if (!switchRequested && !switchActive) {
            return;
        }
        final Context appCtx = context;
        Thread worker = new Thread(() -> {
            try {
                ClusterProjectionSwitch.disable();
                ClusterLog.i(TAG, "physical switch disabled (stock QNX restored)");
            } catch (Throwable t) {
                ClusterLog.w(TAG, "physical switch disable failed", t);
            }
        }, "cluster-physical-switch-off");
        worker.setDaemon(true);
        worker.start();
        switchRequested = false;
        switchActive = false;
        switchPath = "NONE";
    }

    /**
     * 普通第三方侧载主链路，严格对齐车机上已验证的“应用管家”思路：
     * 目标 Display 创建顶层 TYPE_APPLICATION_OVERLAY，窗口内 GLSurfaceView 接收
     * MediaProjection，OpenGL 只做缩放/裁剪，不经过视频编码器。
     */
    private void startOverlayMirrorOrFallback(DisplayMetrics centerMetrics) {
        if (!Fx11DisplayProfile.isClusterSize(clusterInfo.getWidth(), clusterInfo.getHeight())) {
            ClusterLog.i(TAG, "non-FX11 cluster size, use legacy pipeline: " + clusterInfo);
            startLegacyPipeline(centerMetrics, "非 FX11 1920x532 仪表目标");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(ownerContext)) {
            ClusterLog.w(TAG, "overlay permission missing, skip overlay mirror", null);
            startVendorMirrorOrFallback(centerMetrics, "缺少悬浮窗权限");
            return;
        }
        try {
            startOverlayPipeline(centerMetrics, OverlayWindowHost.Mode.MIRROR,
                    "FX11 顶层 Overlay + GL 镜像");
        } catch (Throwable t) {
            ClusterLog.w(TAG, "overlay mirror unavailable, fallback to vendor Presentation; "
                    + stateSnapshot(), t);
            safeStopOverlay();
            legacyPipelineStarted = false;
            startVendorMirrorOrFallback(centerMetrics,
                    "顶层 Overlay 镜像创建失败：" + t.getMessage());
        }
    }

    private void startOverlayPipeline(DisplayMetrics centerMetrics,
            OverlayWindowHost.Mode hostMode, String reason) {
        if (!active) return;
        legacyPipelineStarted = true;
        mode = hostMode == OverlayWindowHost.Mode.MIRROR ? MODE_MIRROR : MODE_DIRECT;
        directReady = false;
        directSurfaceAttached = false;
        mirrorReady = false;
        mirrorFrameReceived = false;
        vendorReady = false;
        bounds = hostMode == OverlayWindowHost.Mode.MIRROR
                ? Fx11DisplayProfile.defaultMirrorBounds()
                : Fx11DisplayProfile.defaultDirectBounds();
        ClusterLog.i(TAG, "start overlay pipeline: mode=" + modeName(mode)
                + " reason=" + reason + " " + stateSnapshot());
        overlay = new OverlayWindowHost(
                ownerContext,
                clusterDisplay,
                clusterInfo.getWidth(),
                clusterInfo.getHeight(),
                centerMetrics.widthPixels > 0
                        ? centerMetrics.widthPixels : Fx11DisplayProfile.CENTER_WIDTH,
                centerMetrics.heightPixels > 0
                        ? centerMetrics.heightPixels : Fx11DisplayProfile.CENTER_HEIGHT,
                bounds);
        overlay.setSurfaceCallback(createOverlaySurfaceCallback());
        overlay.start(hostMode);
        // 合并投屏：设置中开启"投屏叠加仪表"时，镜像之上叠加透明仪表元素层
        boolean merge = DashboardConfig.isMergeEnabled()
                && hostMode == OverlayWindowHost.Mode.MIRROR;
        ClusterLog.i(TAG, "merge dashboard overlay=" + merge);
        if (merge) {
            overlay.setMergeDashboard(true);
        }
        ClusterLog.i(TAG, "overlay.start returned: " + stateSnapshot());
        notifyModeChanged(mode, reason);
        scheduleReadinessWatchdog();
    }

    private OverlayWindowHost.SurfaceCallback createOverlaySurfaceCallback() {
        return new OverlayWindowHost.SurfaceCallback() {
            @Override public void onDirectSurfaceAvailable(Surface surface) {
                ClusterLog.i(TAG, "direct surface available: valid="
                        + (surface != null && surface.isValid()));
                attachDirectDisplay(surface);
            }

            @Override public void onDirectSurfaceChanged(Surface surface, int width, int height) {
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

            @Override public void onMirrorInputSurfaceReady(Surface surface) {
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
        };
    }

    /** 第二路线：标准 Presentation 内 SurfaceView 直连 MediaProjection。 */
    private void startVendorMirrorOrFallback(DisplayMetrics centerMetrics, String fallbackReason) {
        if (!Fx11DisplayProfile.isClusterSize(clusterInfo.getWidth(), clusterInfo.getHeight())) {
            ClusterLog.i(TAG, "non-FX11 cluster size, use legacy pipeline: " + clusterInfo);
            startLegacyPipeline(centerMetrics, "非 FX11 1920x532 仪表目标");
            return;
        }
        if (!(ownerContext instanceof android.app.Activity)) {
            startLegacyPipeline(centerMetrics, "Presentation owner 不是 Activity");
            return;
        }
        mode = MODE_VENDOR_MIRROR;
        try {
            vendorPresentation = new VendorMirrorPresentation(
                    ownerContext,
                    clusterDisplay,
                    clusterInfo.getWidth(),
                    clusterInfo.getHeight(),
                    clusterInfo.getDensityDpi(),
                    mediaProjection,
                    new VendorMirrorPresentation.Callback() {
                        @Override public void onVendorSurfaceReady(int virtualDisplayId) {
                            mainHandler.post(() -> {
                                if (!active || mode != MODE_VENDOR_MIRROR) return;
                                vendorReady = true;
                                if (mirrorCapture == null) {
                                    mirrorCapture = new MirrorCaptureHost(context, mediaProjection);
                                }
                                ClusterLog.i(TAG, "vendor mirror ready: vdId="
                                        + virtualDisplayId + " " + stateSnapshot());
                                if (listener != null) {
                                    listener.onVirtualDisplayReady(MODE_VENDOR_MIRROR, virtualDisplayId);
                                }
                                flushPendingLaunch();
                            });
                        }

                        @Override public void onVendorSurfaceFailed(Exception error) {
                            mainHandler.post(() -> fallbackFromVendor(
                                    "厂商 SurfaceView 直连被拒绝：" + error.getMessage()));
                        }
                    });
            vendorPresentation.show();
            ClusterLog.i(TAG, "vendor Presentation.show returned without exception: display="
                    + clusterDisplay.getDisplayId() + " " + stateSnapshot());
            notifyModeChanged(MODE_VENDOR_MIRROR,
                    "FX11 Presentation 回退（" + fallbackReason + "）");
            scheduleReadinessWatchdog();
        } catch (Throwable t) {
            ClusterLog.w(TAG, "vendor Presentation unavailable, fallback to legacy; "
                    + stateSnapshot(), t);
            fallbackFromVendor("厂商 Presentation 无法显示：" + t.getMessage());
        }
    }

    private void fallbackFromVendor(String reason) {
        if (!active || legacyPipelineStarted) {
            ClusterLog.i(TAG, "vendor fallback ignored: active=" + active
                    + " legacyStarted=" + legacyPipelineStarted + " mode=" + modeName(mode));
            return;
        }
        legacyPipelineStarted = true;
        vendorReady = false;
        if (vendorPresentation != null) {
            try {
                vendorPresentation.dismiss();
            } catch (Throwable t) {
                ClusterLog.w(TAG, "dismiss vendor Presentation failed", t);
            }
            vendorPresentation = null;
        }
        mode = MODE_DIRECT;
        startLegacyPipeline(getDefaultDisplayMetrics(), reason);
    }

    private void startLegacyPipeline(DisplayMetrics centerMetrics, String vendorFallbackReason) {
        startOverlayPipeline(centerMetrics, OverlayWindowHost.Mode.DIRECT,
                vendorFallbackReason == null ? "直出模式" : vendorFallbackReason);
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
            if (listener != null) {
                listener.onVirtualDisplayReady(MODE_MIRROR, mirrorCapture.getDisplayId());
            }
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

        if (mode == MODE_VENDOR_MIRROR) {
            if (mirrorCapture == null) mirrorCapture = new MirrorCaptureHost(context, mediaProjection);
            boolean ok = mirrorCapture.launchAppOnDefaultDisplay(packageName);
            String error = ok ? null : mirrorCapture.getLastError();
            ClusterLog.i(TAG, "vendor mirror launchApp result: " + packageName + " ok=" + ok
                    + (error == null ? "" : " error=" + error));
            if (listener != null) listener.onAppLaunched(packageName, ok, error);
            return ok;
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

        if (mode == MODE_VENDOR_MIRROR) {
            if (mirrorCapture == null) mirrorCapture = new MirrorCaptureHost(context, mediaProjection);
            boolean ok = mirrorCapture.startActivityOnDefaultDisplay(testActivityClass);
            String label = testActivityClass.getName();
            ClusterLog.i(TAG, "vendor mirror launchTest result: ok=" + ok
                    + " lastError=" + mirrorCapture.getLastError());
            if (listener != null) {
                listener.onAppLaunched(label, ok, ok ? null : mirrorCapture.getLastError());
            }
            return ok;
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
        if (!active || mode == MODE_MIRROR || mode == MODE_VENDOR_MIRROR) {
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
        if (mode == MODE_VENDOR_MIRROR) {
            ClusterLog.i(TAG, "setBounds deferred in vendor full-screen test mode: " + bounds);
            return;
        }
        if (overlay != null) overlay.setBounds(bounds);
    }

    public void setAlpha(float alpha) {
        opacity = Math.max(0f, Math.min(1f, alpha));
        if (mode == MODE_VENDOR_MIRROR) {
            ClusterLog.i(TAG, "setAlpha deferred in vendor test mode: " + opacity);
            return;
        }
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
        vendorReady = false;
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
        if (vendorPresentation != null) {
            try {
                vendorPresentation.dismiss();
            } catch (Throwable t) {
                ClusterLog.w(TAG, "dismiss vendor Presentation on stop failed", t);
            }
            vendorPresentation = null;
        }
        if (overlay != null) {
            overlay.stop();
            overlay = null;
        }
        mediaProjection = null;
        endPhysicalSwitch();
        ClusterLog.i(TAG, "stop completed");
    }

    private void safeStopOverlay() {
        if (overlay == null) return;
        try {
            overlay.stop();
        } catch (Throwable t) {
            ClusterLog.w(TAG, "stop failed overlay host", t);
        }
        overlay = null;
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
                boolean ready = isVirtualReady();
                ClusterLog.w(TAG, "readiness check after 8s: ready=" + ready + " "
                        + stateSnapshot(), null);
                if (!ready) {
                    if (mode == MODE_VENDOR_MIRROR && !legacyPipelineStarted) {
                        fallbackFromVendor("厂商 SurfaceView 8 秒内未创建虚拟屏");
                        return;
                    }
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
                + " host=" + getHostName()
                + " hostDetail=" + getHostDetail()
                + " directReady=" + directReady
                + " directSurfaceAttached=" + directSurfaceAttached
                + " mirrorCaptureReady=" + mirrorReady
                + " mirrorFrameReceived=" + mirrorFrameReceived
                + " vendorReady=" + vendorReady
                + " legacyStarted=" + legacyPipelineStarted
                + " switching=" + switchingMode
                + " pendingPkg=" + pendingPackage + " pendingTest="
                + (pendingTestActivity != null) + " vdId=" + getVirtualDisplayId();
    }

    private static String modeName(int mode) {
        if (mode == MODE_MIRROR) return "MIRROR";
        if (mode == MODE_VENDOR_MIRROR) return "VENDOR_MIRROR";
        if (mode == MODE_ECARX_SWITCH) return "ECARX_SWITCH";
        return "DIRECT";
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
