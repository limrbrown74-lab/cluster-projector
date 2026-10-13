package org.clusterprojector.display;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * 仪表 Display 上的置顶窗口。
 *
 * <p>支持两种内容：
 * <ul>
 *     <li>DIRECT：第三方 App 直出到普通 SurfaceView；</li>
 *     <li>MIRROR：MediaProjection 经 OpenGL 零编码镜像到 GLSurfaceView。</li>
 * </ul>
 */
public final class OverlayWindowHost {

    private static final String TAG = "ClusterOverlay";

    public enum Mode { DIRECT, MIRROR }

    private final Context appContext;
    private final android.view.Display display;
    private final int directBufferWidth;
    private final int directBufferHeight;
    private final int mirrorSourceWidth;
    private final int mirrorSourceHeight;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Context windowContext;
    private WindowManager windowMgr;
    private FrameLayout container;
    private WindowManager.LayoutParams windowParams;
    private boolean added;
    private float opacity = 1f;
    private Mode mode = Mode.DIRECT;

    private SurfaceView directView;
    private MirrorGlSurfaceView mirrorView;
    private SurfaceCallback surfaceCallback;

    private final SurfaceHolder.Callback directHolderCallback = new SurfaceHolder.Callback() {
        @Override public void surfaceCreated(SurfaceHolder holder) {
            Log.i(TAG, "direct surfaceCreated");
            post(() -> {
                if (surfaceCallback != null && mode == Mode.DIRECT) {
                    surfaceCallback.onDirectSurfaceAvailable(holder.getSurface());
                }
            });
        }

        @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            Log.i(TAG, "direct surfaceChanged " + width + "x" + height);
            post(() -> {
                if (surfaceCallback != null && mode == Mode.DIRECT) {
                    surfaceCallback.onDirectSurfaceChanged(holder.getSurface(), width, height);
                }
            });
        }

        @Override public void surfaceDestroyed(SurfaceHolder holder) {
            Log.i(TAG, "direct surfaceDestroyed");
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
    }

    public OverlayWindowHost(Context context, android.view.Display display,
            int directBufferWidth, int directBufferHeight,
            int mirrorSourceWidth, int mirrorSourceHeight,
            WindowGeometry initialBounds) {
        this.appContext = context.getApplicationContext();
        this.display = display;
        this.directBufferWidth = directBufferWidth;
        this.directBufferHeight = directBufferHeight;
        this.mirrorSourceWidth = mirrorSourceWidth;
        this.mirrorSourceHeight = mirrorSourceHeight;
        setInitialBounds(initialBounds);
    }

    private WindowGeometry initialBounds;

    private void setInitialBounds(WindowGeometry bounds) {
        this.initialBounds = bounds;
    }

    public void setSurfaceCallback(SurfaceCallback callback) {
        this.surfaceCallback = callback;
    }

    public synchronized void start() {
        if (added) return;
        buildWindowContextAndManager();
        container = new FrameLayout(windowContext);
        container.setClipChildren(true);
        buildWindowParams();
        showDirectLocked();
        try {
            windowMgr.addView(container, windowParams);
            added = true;
            Log.i(TAG, "overlay added to display=" + display.getDisplayId()
                    + " bounds=" + initialBounds);
        } catch (Throwable t) {
            added = false;
            Log.e(TAG, "addView to display " + display.getDisplayId() + " failed", t);
            throw new IllegalStateException("无法在 Display " + display.getDisplayId()
                    + " 创建仪表悬浮窗，请检查悬浮窗权限和车机多屏策略", t);
        }
    }

    public synchronized void stop() {
        if (!added) return;
        try {
            removeMirrorLocked();
            removeDirectLocked();
            windowMgr.removeView(container);
        } catch (Throwable t) {
            Log.w(TAG, "remove overlay failed", t);
        } finally {
            added = false;
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
        if (!added || windowParams == null) return;
        windowParams.x = bounds.x;
        windowParams.y = bounds.y;
        windowParams.width = bounds.width;
        windowParams.height = bounds.height;
        windowMgr.updateViewLayout(container, windowParams);
    }

    public synchronized void setAlpha(float alpha) {
        opacity = Math.max(0f, Math.min(1f, alpha));
        if (!added || windowParams == null) return;
        windowParams.alpha = opacity;
        windowMgr.updateViewLayout(container, windowParams);
    }

    private void showDirectLocked() {
        mode = Mode.DIRECT;
        removeMirrorLocked();
        if (directView != null) return;
        directView = new SurfaceView(windowContext);
        SurfaceHolder holder = directView.getHolder();
        holder.addCallback(directHolderCallback);
        holder.setFormat(PixelFormat.OPAQUE);
        holder.setFixedSize(directBufferWidth, directBufferHeight);
        directView.setZOrderOnTop(true);
        container.addView(directView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void showMirrorLocked() {
        mode = Mode.MIRROR;
        removeDirectLocked();
        if (mirrorView != null) return;

        mirrorView = new MirrorGlSurfaceView(windowContext, mirrorSourceWidth, mirrorSourceHeight);
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
        });
        container.addView(mirrorView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        mirrorView.onResume();
    }

    private void removeDirectLocked() {
        if (directView == null) return;
        try {
            directView.getHolder().removeCallback(directHolderCallback);
            container.removeView(directView);
        } catch (Throwable t) {
            Log.w(TAG, "remove direct view failed", t);
        }
        directView = null;
    }

    private void removeMirrorLocked() {
        if (mirrorView == null) return;
        try {
            mirrorView.onPause();
            container.removeView(mirrorView);
        } catch (Throwable t) {
            Log.w(TAG, "remove mirror view failed", t);
        }
        mirrorView = null;
    }

    private void buildWindowContextAndManager() {
        int type = overlayType();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                // 标准 Android 11 公开 API。窗口类型必须与 addView 的 LayoutParams.type 一致。
                windowContext = appContext.createWindowContext(display, type, null);
                Log.i(TAG, "using WindowContext for display=" + display.getDisplayId());
            } catch (LinkageError | RuntimeException e) {
                // 部分车机虽然声明 sdk=30，但厂商裁剪/改造 framework.jar 后会缺失该方法，
                // 实车日志表现为 NoSuchMethodError。此时回退到 DisplayContext。
                Log.w(TAG, "createWindowContext unavailable, fallback to DisplayContext"
                        + " display=" + display.getDisplayId(), e);
                windowContext = appContext.createDisplayContext(display);
            }
        } else {
            windowContext = appContext.createDisplayContext(display);
            Log.i(TAG, "using DisplayContext for display=" + display.getDisplayId());
        }
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
    }

    private int overlayType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void post(Runnable runnable) {
        mainHandler.post(runnable);
    }
}
