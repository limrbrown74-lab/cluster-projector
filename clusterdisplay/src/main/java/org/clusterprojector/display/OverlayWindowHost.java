package org.clusterprojector.display;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * 在真实仪表 Display 上创建承载 Surface 的窗口。
 *
 * <p>Android 11 使用公开的 createWindowContext(Display, type, options)，
 * 不再反射 LayoutParams#setDisplayId，避免窗口静默落回中控屏。
 */
public final class OverlayWindowHost implements SurfaceHolder.Callback {

    private static final String TAG = "ClusterOverlay";

    private final Context appContext;
    private final android.view.Display display;
    private final int bufferWidth;
    private final int bufferHeight;
    private final WindowGeometry initialBounds;

    private Context windowContext;
    private WindowManager windowMgr;
    private FrameLayout container;
    private SurfaceView surfaceView;
    private WindowManager.LayoutParams windowParams;
    private boolean added;
    private float opacity = 1f;
    private SurfaceCallback surfaceCallback;

    public interface SurfaceCallback {
        void onSurfaceAvailable(android.view.Surface surface);
        void onSurfaceChanged(android.view.Surface surface, int width, int height);
        void onSurfaceDestroyed();
    }

    public OverlayWindowHost(Context context, android.view.Display display,
            int bufferWidth, int bufferHeight, WindowGeometry initialBounds) {
        this.appContext = context.getApplicationContext();
        this.display = display;
        this.bufferWidth = bufferWidth;
        this.bufferHeight = bufferHeight;
        this.initialBounds = initialBounds;
    }

    public void setSurfaceCallback(SurfaceCallback callback) {
        this.surfaceCallback = callback;
    }

    public synchronized void start() {
        if (added) return;
        buildWindowContextAndManager();
        buildViewTree();
        buildWindowParams();
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
            if (surfaceView != null) surfaceView.getHolder().removeCallback(this);
            windowMgr.removeView(container);
        } catch (Throwable t) {
            Log.w(TAG, "remove overlay failed", t);
        } finally {
            added = false;
            container = null;
            surfaceView = null;
            windowParams = null;
        }
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

    private void buildWindowContextAndManager() {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        // Android 11 / API 30 公开 API。窗口类型必须与 addView 的 LayoutParams.type 一致。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowContext = appContext.createWindowContext(display, type, null);
        } else {
            windowContext = appContext.createDisplayContext(display);
        }
        windowMgr = (WindowManager) windowContext.getSystemService(Context.WINDOW_SERVICE);
        if (windowMgr == null) {
            throw new IllegalStateException("Display " + display.getDisplayId() + " WindowManager 不可用");
        }
    }

    private void buildViewTree() {
        container = new FrameLayout(windowContext);
        container.setClipChildren(true);

        surfaceView = new SurfaceView(windowContext);
        SurfaceHolder holder = surfaceView.getHolder();
        holder.addCallback(this);
        holder.setFormat(PixelFormat.OPAQUE);
        // 缓冲固定为仪表屏逻辑分辨率，窗口缩放交给 SurfaceFlinger。
        holder.setFixedSize(bufferWidth, bufferHeight);
        surfaceView.setZOrderOnTop(true);
        container.addView(surfaceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void buildWindowParams() {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        windowParams = new WindowManager.LayoutParams(
                initialBounds.width,
                initialBounds.height,
                type,
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

    @Override public void surfaceCreated(SurfaceHolder holder) {
        Log.i(TAG, "surfaceCreated");
        if (surfaceCallback != null) surfaceCallback.onSurfaceAvailable(holder.getSurface());
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.i(TAG, "surfaceChanged " + width + "x" + height);
        if (surfaceCallback != null) surfaceCallback.onSurfaceChanged(holder.getSurface(), width, height);
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        Log.i(TAG, "surfaceDestroyed");
        if (surfaceCallback != null) surfaceCallback.onSurfaceDestroyed();
    }
}
