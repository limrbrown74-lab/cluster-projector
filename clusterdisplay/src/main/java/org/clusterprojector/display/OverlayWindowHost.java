package org.clusterprojector.display;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * 仪表 Display 上的画面宿主。
 *
 * <p>FX11 普通侧载优先使用目标 Display 上的 TYPE_APPLICATION_OVERLAY 顶层窗口，
 * 再把 MediaProjection 的 Surface 输出到窗口内。该顺序对齐车机上已验证可用的
 * “应用管家”链路。只有 overlay 被系统拒绝时才回退到标准 {@link Presentation}。
 */
public final class OverlayWindowHost {

    private static final String TAG = "ClusterOverlay";

    public enum Mode { DIRECT, MIRROR }
    public enum Host { PRESENTATION, OVERLAY }

    private final Context ownerContext;
    private final Context appContext;
    private final Display display;
    private final int directBufferWidth;
    private final int directBufferHeight;
    private final int mirrorSourceWidth;
    private final int mirrorSourceHeight;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Host host = Host.OVERLAY;
    private String hostDetail = "NOT_STARTED";
    private ClusterPresentation presentation;
    private Context windowContext;
    private WindowManager windowMgr;
    private FrameLayout container;
    private WindowManager.LayoutParams windowParams;
    private WindowGeometry initialBounds;
    private boolean added;
    private float opacity = 1f;
    private Mode mode = Mode.DIRECT;
    private Mode initialMode = Mode.DIRECT;

    private SurfaceView directView;
    private MirrorGlSurfaceView mirrorView;
    private SurfaceCallback surfaceCallback;

    /** 合并投屏：应用画面（镜像）之上叠加透明自绘仪表层。 */
    private boolean mergeDashboard;
    private boolean mergeApplied;
    private boolean mergeBoundsNotified;
    private ClusterDashboardView dashboardOverlay;

    private final SurfaceHolder.Callback directHolderCallback = new SurfaceHolder.Callback() {
        @Override public void surfaceCreated(SurfaceHolder holder) {
            ClusterLog.i(TAG, "direct surfaceCreated valid="
                    + (holder.getSurface() != null && holder.getSurface().isValid())
                    + " frame=" + holder.getSurfaceFrame());
            post(() -> {
                if (surfaceCallback != null && mode == Mode.DIRECT) {
                    surfaceCallback.onDirectSurfaceAvailable(holder.getSurface());
                }
            });
        }

        @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            ClusterLog.i(TAG, "direct surfaceChanged " + width + "x" + height
                    + " format=" + format + " valid="
                    + (holder.getSurface() != null && holder.getSurface().isValid())
                    + " frame=" + holder.getSurfaceFrame());
            post(() -> {
                if (surfaceCallback != null && mode == Mode.DIRECT) {
                    surfaceCallback.onDirectSurfaceChanged(holder.getSurface(), width, height);
                }
            });
        }

        @Override public void surfaceDestroyed(SurfaceHolder holder) {
            ClusterLog.i(TAG, "direct surfaceDestroyed frame=" + holder.getSurfaceFrame());
            post(() -> {
                if (surfaceCallback != null && mode == Mode.DIRECT) {
                    surfaceCallback.onDirectSurfaceDestroyed();
                }
            });
        }
    };

    public interface SurfaceCallback {
        void onDirectSurfaceAvailable(android.view.Surface surface);
        void onDirectSurfaceChanged(android.view.Surface surface, int width, int height);
        void onDirectSurfaceDestroyed();

        void onMirrorInputSurfaceReady(android.view.Surface surface);
        void onMirrorInputSurfaceDestroyed();
        void onMirrorFirstFrame();
    }

    public OverlayWindowHost(Context context, Display display,
            int directBufferWidth, int directBufferHeight,
            int mirrorSourceWidth, int mirrorSourceHeight,
            WindowGeometry initialBounds) {
        this.ownerContext = context;
        this.appContext = context.getApplicationContext();
        this.display = display;
        this.directBufferWidth = directBufferWidth;
        this.directBufferHeight = directBufferHeight;
        this.mirrorSourceWidth = mirrorSourceWidth;
        this.mirrorSourceHeight = mirrorSourceHeight;
        this.initialBounds = initialBounds;
    }

    public void setSurfaceCallback(SurfaceCallback callback) {
        this.surfaceCallback = callback;
    }

    /**
     * 合并投屏：开启后窗口改为全屏（1920x532），镜像画面居中显示原 bounds，
     * 顶层叠加透明自绘仪表（背景关闭、只画元素）。关闭时恢复原样。
     */
    public synchronized void setMergeDashboard(boolean enabled) {
        mergeDashboard = enabled;
        tryApplyMergeLocked();
    }

    private void tryApplyMergeLocked() {
        if (!added || container == null) return;
        if (mergeDashboard && !mergeApplied) {
            applyMergeLayoutLocked();
        } else if (!mergeDashboard && mergeApplied) {
            revertMergeLayoutLocked();
        }
    }

    private void applyMergeLayoutLocked() {
        try {
            ClusterLog.i(TAG, "merge dashboard ON: display=" + display.getDisplayId()
                    + " host=" + host + " mode=" + mode);
            int fullW = Fx11DisplayProfile.CLUSTER_WIDTH;
            int fullH = Fx11DisplayProfile.CLUSTER_HEIGHT;
            if (host == Host.OVERLAY && windowParams != null) {
                windowParams.x = 0;
                windowParams.y = 0;
                windowParams.width = fullW;
                windowParams.height = fullH;
                windowMgr.updateViewLayout(container, windowParams);
            } else if (host == Host.PRESENTATION) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) container.getLayoutParams();
                lp.width = fullW;
                lp.height = fullH;
                lp.leftMargin = 0;
                lp.topMargin = 0;
                container.setLayoutParams(lp);
            }
            // 镜像画面居中显示原 bounds（contain 模式）
            if (mirrorView != null) {
                FrameLayout.LayoutParams lp =
                        (FrameLayout.LayoutParams) mirrorView.getLayoutParams();
                lp.width = initialBounds.width;
                lp.height = initialBounds.height;
                lp.gravity = Gravity.CENTER;
                mirrorView.setLayoutParams(lp);
            }
            if (directView != null) {
                FrameLayout.LayoutParams lp =
                        (FrameLayout.LayoutParams) directView.getLayoutParams();
                lp.width = initialBounds.width;
                lp.height = initialBounds.height;
                lp.gravity = Gravity.CENTER;
                directView.setLayoutParams(lp);
            }
            // 顶层透明仪表
            if (dashboardOverlay == null) {
                Context viewContext = host == Host.PRESENTATION
                        ? presentation.getContext() : windowContext;
                dashboardOverlay = new ClusterDashboardView(viewContext);
                dashboardOverlay.setBackgroundEnabled(false);
                dashboardOverlay.setVisibility(View.VISIBLE);
                container.addView(dashboardOverlay, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                ClusterVehicleTelemetry.start();
                ClusterLog.i(TAG, "transparent dashboard overlay attached on top");
            }
            mergeApplied = true;
        } catch (Throwable t) {
            ClusterLog.w(TAG, "merge layout apply failed", t);
        }
    }

    private void revertMergeLayoutLocked() {
        try {
            ClusterLog.i(TAG, "merge dashboard OFF: display=" + display.getDisplayId());
            if (dashboardOverlay != null) {
                container.removeView(dashboardOverlay);
                dashboardOverlay = null;
            }
            if (mirrorView != null) {
                FrameLayout.LayoutParams lp =
                        (FrameLayout.LayoutParams) mirrorView.getLayoutParams();
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.gravity = Gravity.TOP | Gravity.START;
                mirrorView.setLayoutParams(lp);
            }
            if (directView != null) {
                FrameLayout.LayoutParams lp =
                        (FrameLayout.LayoutParams) directView.getLayoutParams();
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.gravity = Gravity.TOP | Gravity.START;
                directView.setLayoutParams(lp);
            }
            if (host == Host.OVERLAY && windowParams != null) {
                windowParams.x = initialBounds.x;
                windowParams.y = initialBounds.y;
                windowParams.width = initialBounds.width;
                windowParams.height = initialBounds.height;
                windowMgr.updateViewLayout(container, windowParams);
            } else if (host == Host.PRESENTATION) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) container.getLayoutParams();
                lp.width = initialBounds.width;
                lp.height = initialBounds.height;
                lp.leftMargin = initialBounds.x;
                lp.topMargin = initialBounds.y;
                container.setLayoutParams(lp);
            }
            mergeApplied = false;
            mergeBoundsNotified = false;
        } catch (Throwable t) {
            ClusterLog.w(TAG, "merge layout revert failed", t);
        }
    }

    public synchronized void start() {
        start(Mode.DIRECT);
    }

    public synchronized void start(Mode hostMode) {
        if (added) return;
        initialMode = hostMode == null ? Mode.DIRECT : hostMode;
        boolean overlayAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(appContext);
        if (overlayAllowed && tryStartOverlay()) {
            tryApplyMergeLocked();
            return;
        }

        ClusterLog.w(TAG, "overlay unavailable, fallback to Presentation: display="
                + display.getDisplayId() + " mode=" + initialMode
                + " overlayAllowed=" + overlayAllowed, null);
        resetFailedHost();
        if (tryStartPresentation()) {
            tryApplyMergeLocked();
            return;
        }
        throw new IllegalStateException("无法在 Display " + display.getDisplayId()
                + " 创建仪表输出窗口，请检查悬浮窗权限和车机多屏策略");
    }

    private boolean tryStartOverlay() {
        try {
            startOverlayFallback();
            return true;
        } catch (Throwable t) {
            ClusterLog.w(TAG, "overlay host rejected: display=" + display.getDisplayId()
                    + " mode=" + initialMode + " detail=" + t.getMessage(), t);
            return false;
        }
    }

    private boolean tryStartPresentation() {
        if (!(ownerContext instanceof Activity)) {
            ClusterLog.w(TAG, "Presentation unavailable: owner is not Activity: "
                    + ownerContext.getClass().getName());
            return false;
        }
        try {
            host = Host.PRESENTATION;
            presentation = new ClusterPresentation(ownerContext, display);
            ClusterLog.i(TAG, "Presentation show begin: display=" + display.getDisplayId()
                    + " flags=0x" + Integer.toHexString(display.getFlags())
                    + " bounds=" + initialBounds);
            presentation.show();
            hostDetail = "PRESENTATION_SHOWN";
            container = presentation.rootContainer;
            added = true;
            ClusterLog.i(TAG, "Presentation shown on display=" + display.getDisplayId()
                    + " bounds=" + initialBounds);
            configureContainer();
            showInitialModeLocked();
            final FrameLayout postedContainer = container;
            postedContainer.post(() -> logContainerLayout("Presentation"));
            post(() -> logPresentationState("after-show-0ms"));
            mainHandler.postDelayed(() -> logPresentationState("after-show-500ms"), 500L);
            mainHandler.postDelayed(() -> logPresentationState("after-show-2000ms"), 2000L);
            return true;
        } catch (Throwable t) {
            host = Host.OVERLAY;
            hostDetail = "PRESENTATION_REJECTED: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage();
            ClusterLog.w(TAG, "Presentation rejected, fallback to overlay: display="
                    + display.getDisplayId() + " detail=" + hostDetail, t);
            safeDismissPresentation();
            return false;
        }
    }

    private void startOverlayFallback() {
        host = Host.OVERLAY;
        hostDetail = "OVERLAY_FALLBACK_BUILDING";
        buildWindowContextAndManager();
        container = new FrameLayout(windowContext);
        container.setClipChildren(true);
        buildWindowParams();
        configureContainer();
        showInitialModeLocked();
        try {
            ClusterLog.i(TAG, "addView begin: display=" + display.getDisplayId()
                    + " type=" + overlayType() + " bounds=" + initialBounds
                    + " context=" + windowContext.getClass().getName()
                    + " flags=0x" + Integer.toHexString(windowParams.flags)
                    + " token=" + windowParams.token);
            windowMgr.addView(container, windowParams);
            added = true;
            hostDetail = "OVERLAY_ADDED";
            ClusterLog.i(TAG, "overlay added to display=" + display.getDisplayId()
                    + " bounds=" + initialBounds);
            final FrameLayout postedContainer = container;
            postedContainer.post(() -> logContainerLayout("Overlay"));
        } catch (Throwable t) {
            added = false;
            ClusterLog.e(TAG, "addView to display " + display.getDisplayId() + " failed", t);
            throw new IllegalStateException("无法在 Display " + display.getDisplayId()
                    + " 创建仪表输出窗口，请检查副屏 Presentation/悬浮窗权限和车机多屏策略", t);
        }
    }

    private void resetFailedHost() {
        try {
            if (windowMgr != null && container != null && container.isAttachedToWindow()) {
                windowMgr.removeView(container);
            }
        } catch (Throwable t) {
            ClusterLog.w(TAG, "remove failed overlay host", t);
        }
        added = false;
        container = null;
        directView = null;
        mirrorView = null;
        windowParams = null;
        windowMgr = null;
        windowContext = null;
    }

    public boolean isAdded() {
        return added;
    }

    public Host getHost() {
        return host;
    }

    public String getHostDetail() {
        return hostDetail;
    }

    public synchronized void stop() {
        ClusterLog.i(TAG, "stop output host: added=" + added + " host=" + host
                + " mode=" + mode + " display=" + display.getDisplayId());
        if (!added) return;
        try {
            removeDashboardOverlayLocked();
            removeMirrorLocked();
            removeDirectLocked();
            if (host == Host.PRESENTATION) {
                safeDismissPresentation();
            } else if (windowMgr != null && container != null) {
                windowMgr.removeView(container);
            }
        } catch (Throwable t) {
            ClusterLog.w(TAG, "remove output host failed", t);
        } finally {
            added = false;
            ClusterLog.i(TAG, "output host resources cleared");
            container = null;
            directView = null;
            mirrorView = null;
            windowParams = null;
        }
    }

    public synchronized void showDirect() {
        if (!added) return;
        showDirectLocked();
    }

    public synchronized void showMirror() {
        if (!added) return;
        showMirrorLocked();
    }

    public synchronized Mode getMode() {
        return mode;
    }

    public synchronized void setBounds(WindowGeometry bounds) {
        if (!added || container == null) {
            ClusterLog.w(TAG, "setBounds ignored: added=" + added + " bounds=" + bounds);
            return;
        }
        if (mergeApplied) {
            if (!mergeBoundsNotified) {
                mergeBoundsNotified = true;
                ClusterLog.i(TAG, "setBounds ignored during merge layout (window is fullscreen)");
            }
            return;
        }
        initialBounds = bounds;
        if (host == Host.PRESENTATION) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) container.getLayoutParams();
            lp.width = bounds.width;
            lp.height = bounds.height;
            lp.leftMargin = bounds.x;
            lp.topMargin = bounds.y;
            container.setLayoutParams(lp);
            ClusterLog.i(TAG, "update Presentation bounds: " + bounds + " display="
                    + display.getDisplayId());
        } else if (windowParams != null) {
            ClusterLog.i(TAG, "update overlay bounds: " + bounds + " display="
                    + display.getDisplayId());
            windowParams.x = bounds.x;
            windowParams.y = bounds.y;
            windowParams.width = bounds.width;
            windowParams.height = bounds.height;
            windowMgr.updateViewLayout(container, windowParams);
        }
    }

    public synchronized void setAlpha(float alpha) {
        opacity = Math.max(0f, Math.min(1f, alpha));
        if (!added || container == null) return;
        container.setAlpha(opacity);
        if (host == Host.OVERLAY && windowParams != null) {
            windowParams.alpha = opacity;
            windowMgr.updateViewLayout(container, windowParams);
        }
    }

    private void configureContainer() {
        container.setClipChildren(true);
        container.setAlpha(opacity);
    }

    private void showInitialModeLocked() {
        if (initialMode == Mode.MIRROR) {
            showMirrorLocked();
        } else {
            showDirectLocked();
        }
    }

    private void showDirectLocked() {
        mode = Mode.DIRECT;
        ClusterLog.i(TAG, "show DIRECT view: host=" + host + " buffer="
                + directBufferWidth + "x" + directBufferHeight);
        removeMirrorLocked();
        if (directView != null) {
            ClusterLog.i(TAG, "direct view already attached");
            return;
        }
        Context viewContext = host == Host.PRESENTATION ? presentation.getContext() : windowContext;
        directView = new SurfaceView(viewContext);
        SurfaceHolder holder = directView.getHolder();
        holder.addCallback(directHolderCallback);
        holder.setFormat(PixelFormat.OPAQUE);
        ClusterLog.i(TAG, "create DIRECT SurfaceView: requestedBuffer="
                + directBufferWidth + "x" + directBufferHeight);
        holder.setFixedSize(directBufferWidth, directBufferHeight);
        directView.setZOrderOnTop(true);
        directView.setVisibility(android.view.View.VISIBLE);
        container.addView(directView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        final SurfaceView postedDirectView = directView;
        postedDirectView.post(() -> ClusterLog.i(TAG, "direct SurfaceView layout: "
                + postedDirectView.getWidth() + "x" + postedDirectView.getHeight()
                + " attached=" + postedDirectView.isAttachedToWindow()
                + " visibility=" + postedDirectView.getVisibility()
                + " holderFrame=" + postedDirectView.getHolder().getSurfaceFrame()
                + " viewDisplay=" + safeDisplayId(postedDirectView)));
    }

    private void showMirrorLocked() {
        mode = Mode.MIRROR;
        ClusterLog.i(TAG, "show MIRROR GL view: host=" + host + " source="
                + mirrorSourceWidth + "x" + mirrorSourceHeight);
        removeDirectLocked();
        if (mirrorView != null) {
            ClusterLog.i(TAG, "mirror GL view already attached");
            return;
        }

        Context viewContext = host == Host.PRESENTATION ? presentation.getContext() : windowContext;
        mirrorView = new MirrorGlSurfaceView(viewContext, mirrorSourceWidth, mirrorSourceHeight);
        ClusterLog.i(TAG, "create MIRROR GLSurfaceView: source="
                + mirrorSourceWidth + "x" + mirrorSourceHeight);
        mirrorView.setMirrorCallback(new MirrorGlSurfaceView.Callback() {
            @Override public void onInputSurfaceReady(android.view.Surface surface) {
                post(() -> {
                    if (surfaceCallback != null && mode == Mode.MIRROR) {
                        surfaceCallback.onMirrorInputSurfaceReady(surface);
                    }
                });
            }

            @Override public void onInputSurfaceDestroyed() {
                post(() -> {
                    if (surfaceCallback != null && mode == Mode.MIRROR) {
                        surfaceCallback.onMirrorInputSurfaceDestroyed();
                    }
                });
            }

            @Override public void onFirstFrame() {
                post(() -> {
                    ClusterLog.i(TAG, "MIRROR first real captured frame delivered to " + host);
                    if (surfaceCallback != null && mode == Mode.MIRROR) {
                        surfaceCallback.onMirrorFirstFrame();
                    }
                });
            }
        });
        mirrorView.setVisibility(android.view.View.VISIBLE);
        container.addView(mirrorView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        final MirrorGlSurfaceView postedMirrorView = mirrorView;
        postedMirrorView.post(() -> ClusterLog.i(TAG, "mirror GLSurfaceView layout: "
                + postedMirrorView.getWidth() + "x" + postedMirrorView.getHeight()
                + " attached=" + postedMirrorView.isAttachedToWindow()
                + " visibility=" + postedMirrorView.getVisibility()
                + " viewDisplay=" + safeDisplayId(postedMirrorView)));
        mirrorView.onResume();
    }

    private void removeDirectLocked() {
        if (directView == null) return;
        try {
            directView.getHolder().removeCallback(directHolderCallback);
            container.removeView(directView);
        } catch (Throwable t) {
            ClusterLog.w(TAG, "remove direct view failed", t);
        }
        directView = null;
    }

    private void removeMirrorLocked() {
        if (mirrorView == null) return;
        try {
            mirrorView.onPause();
            container.removeView(mirrorView);
        } catch (Throwable t) {
            ClusterLog.w(TAG, "remove mirror view failed", t);
        }
        mirrorView = null;
    }

    private void removeDashboardOverlayLocked() {
        if (dashboardOverlay == null) return;
        try {
            container.removeView(dashboardOverlay);
        } catch (Throwable t) {
            ClusterLog.w(TAG, "remove dashboard overlay failed", t);
        }
        dashboardOverlay = null;
        mergeApplied = false;
    }

    private void buildWindowContextAndManager() {
        int type = overlayType();
        ClusterLog.i(TAG, "build window context: sdk=" + Build.VERSION.SDK_INT
                + " display=" + display.getDisplayId() + " type=" + type
                + " appContext=" + appContext.getClass().getName());
        // FX11 Android 11 的 framework 实测可能缺少 createWindowContext；
        // createDisplayContext + TYPE_APPLICATION_OVERLAY 是应用管家已验证的兼容路径。
        windowContext = appContext.createDisplayContext(display);
        ClusterLog.i(TAG, "using DisplayContext for display=" + display.getDisplayId());
        ClusterLog.i(TAG, "window context class=" + windowContext.getClass().getName()
                + " display=" + display.getDisplayId());
        windowMgr = (WindowManager) windowContext.getSystemService(Context.WINDOW_SERVICE);
        if (windowMgr == null) {
            throw new IllegalStateException("Display " + display.getDisplayId() + " WindowManager 不可用");
        }
    }

    private void buildWindowParams() {
        windowParams = new WindowManager.LayoutParams(
                initialBounds.width,
                initialBounds.height,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE);
        windowParams.gravity = Gravity.TOP | Gravity.LEFT;
        windowParams.x = initialBounds.x;
        windowParams.y = initialBounds.y;
        windowParams.alpha = opacity;
        ClusterLog.i(TAG, "window params: type=" + overlayType() + " bounds="
                + initialBounds + " alpha=" + opacity
                + " flags=0x" + Integer.toHexString(windowParams.flags));
    }

    private int overlayType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void safeDismissPresentation() {
        if (presentation == null) return;
        try {
            presentation.dismiss();
        } catch (Throwable t) {
            ClusterLog.w(TAG, "dismiss Presentation failed", t);
        }
        presentation = null;
    }

    private void logPresentationState(String stage) {
        if (presentation == null) {
            ClusterLog.i(TAG, "Presentation state[" + stage + "]: presentation=null");
            return;
        }
        try {
            View decor = presentation.getWindow() == null ? null
                    : presentation.getWindow().peekDecorView();
            int[] location = new int[]{Integer.MIN_VALUE, Integer.MIN_VALUE};
            if (decor != null && decor.isAttachedToWindow()) {
                decor.getLocationOnScreen(location);
            }
            Display actual = container != null && container.getDisplay() != null
                    ? container.getDisplay() : display;
            ClusterLog.i(TAG, "Presentation state[" + stage + "] showing="
                    + presentation.isShowing()
                    + " host=" + host
                    + " display=" + actual.getDisplayId()
                    + " displayState=" + actual.getState()
                    + " flags=0x" + Integer.toHexString(actual.getFlags())
                    + " containerAttached=" + (container != null && container.isAttachedToWindow())
                    + " containerSize=" + (container == null ? -1 : container.getWidth()) + "x"
                    + (container == null ? -1 : container.getHeight())
                    + " decorAttached=" + (decor != null && decor.isAttachedToWindow())
                    + " decorSize=" + (decor == null ? -1 : decor.getWidth()) + "x"
                    + (decor == null ? -1 : decor.getHeight())
                    + " decorLoc=" + location[0] + "," + location[1]
                    + " detail=" + hostDetail);
        } catch (Throwable t) {
            ClusterLog.w(TAG, "Presentation state log failed stage=" + stage, t);
        }
    }

    private void logContainerLayout(String hostName) {
        ClusterLog.i(TAG, hostName + " container layout: added=" + added
                + " host=" + host + " width=" + container.getWidth()
                + " height=" + container.getHeight()
                + " isAttached=" + container.isAttachedToWindow()
                + " visibility=" + container.getVisibility()
                + " windowVisibility=" + container.getWindowVisibility()
                + " viewDisplay=" + safeDisplayId(container)
                + " requestedDisplay=" + display.getDisplayId());
    }

    private static int safeDisplayId(android.view.View view) {
        try {
            Display d = view.getDisplay();
            return d == null ? Integer.MIN_VALUE : d.getDisplayId();
        } catch (Throwable t) {
            return Integer.MIN_VALUE;
        }
    }

    private void post(Runnable runnable) {
        mainHandler.post(runnable);
    }

    private final class ClusterPresentation extends Presentation {
        private FrameLayout rootContainer;

        ClusterPresentation(Context context, Display display) {
            super(context, display);
        }

        @Override protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            ClusterLog.i(TAG, "Presentation onCreate begin: display="
                    + getDisplay().getDisplayId());
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.MATCH_PARENT;
            // 保持 Android 官方 Presentation 的默认焦点/触摸策略，只保留全屏布局和亮屏。
            // ecarx 对非焦点 overlay 可能有额外过滤；标准 Presentation 不应主动加上 overlay 风格 flags。
            lp.flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            getWindow().setAttributes(lp);
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            getWindow().setFormat(PixelFormat.TRANSLUCENT);
            getWindow().setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);

            FrameLayout presentationRoot = new FrameLayout(getContext());
            presentationRoot.setBackgroundColor(Color.TRANSPARENT);
            rootContainer = new FrameLayout(getContext());
            rootContainer.setBackgroundColor(Color.TRANSPARENT);
            FrameLayout.LayoutParams contentLp = new FrameLayout.LayoutParams(
                    initialBounds.width, initialBounds.height);
            contentLp.leftMargin = initialBounds.x;
            contentLp.topMargin = initialBounds.y;
            contentLp.gravity = Gravity.TOP | Gravity.LEFT;
            presentationRoot.addView(rootContainer, contentLp);
            setContentView(presentationRoot);
            ClusterLog.i(TAG, "Presentation onCreate: display=" + getDisplay().getDisplayId()
                    + " displayFlags=0x" + Integer.toHexString(getDisplay().getFlags())
                    + " bounds=" + initialBounds);
        }

        @Override protected void onStart() {
            super.onStart();
            ClusterLog.i(TAG, "Presentation onStart: display=" + getDisplay().getDisplayId());
        }

        @Override protected void onStop() {
            ClusterLog.i(TAG, "Presentation onStop: display=" + getDisplay().getDisplayId());
            super.onStop();
        }

        @Override public void onDisplayChanged() {
            super.onDisplayChanged();
            ClusterLog.i(TAG, "Presentation onDisplayChanged: display="
                    + getDisplay().getDisplayId() + " state=" + getDisplay().getState()
                    + " flags=0x" + Integer.toHexString(getDisplay().getFlags()));
        }

        @Override public void onDisplayRemoved() {
            ClusterLog.w(TAG, "Presentation onDisplayRemoved: display="
                    + getDisplay().getDisplayId());
            super.onDisplayRemoved();
        }
    }
}
