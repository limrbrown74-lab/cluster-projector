package org.clusterprojector.display;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * 仪表盘悬浮窗控制器。
 * 通过 WindowManager 把窗口路由到目标仪表盘 Display，悬浮在原车仪表界面之上。
 *
 * <p>由于仪表盘无触控，窗口位置/尺寸/透明度全部由中控屏控制面板程序化驱动。
 * 窗口内嵌 SurfaceView，其 Surface 由虚拟屏（{@link VirtualScreenHost}）输出。
 *
 * <p>本模块由 AI 辅助原创开发。
 */
public final class OverlayWindowHost implements SurfaceHolder.Callback {

    private final Context ctx;
    private final WindowManager windowMgr;
    private final int displayId;
    private final WindowGeometry initialBounds;

    private FrameLayout container;
    private SurfaceView surfaceView;
    private WindowManager.LayoutParams windowParams;
    private boolean added;
    private boolean surfaceReady;
    private float opacity = 1f;

    /** Surface 就绪回调。 */
    public interface SurfaceCallback {
        void onSurfaceReady(android.view.Surface surface);
    }

    private SurfaceCallback surfaceCb;

    public OverlayWindowHost(Context context, int displayId,
                                   WindowGeometry initialBounds) {
        this.ctx = context.getApplicationContext();
        this.windowMgr = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        this.displayId = displayId;
        this.initialBounds = initialBounds;
    }

    public void setSurfaceCallback(SurfaceCallback cb) { this.surfaceCb = cb; }

    /** 在目标仪表盘 Display 上添加悬浮窗。 */
    public synchronized void start() {
        if (added) return;
        buildViewTree();
        buildWindowParams();
        windowMgr.addView(container, windowParams);
        added = true;
    }

    /** 移除悬浮窗。 */
    public synchronized void stop() {
        if (!added) return;
        surfaceView.getHolder().removeCallback(this);
        windowMgr.removeView(container);
        added = false;
        surfaceReady = false;
    }

    /** 程序化更新窗口位置/尺寸（来自中控屏）。 */
    public synchronized void setBounds(WindowGeometry bounds) {
        if (!added || windowParams == null) return;
        windowParams.x = bounds.x;
        windowParams.y = bounds.y;
        windowParams.width = bounds.width;
        windowParams.height = bounds.height;
        windowMgr.updateViewLayout(container, windowParams);
    }

    /** 设置窗口不透明度：0.0 全透明，1.0 不透明。 */
    public synchronized void setAlpha(float alpha) {
        this.opacity = Math.max(0f, Math.min(1f, alpha));
        if (!added || windowParams == null) return;
        windowParams.alpha = this.opacity;
        windowMgr.updateViewLayout(container, windowParams);
    }

    public float getAlpha() { return opacity; }
    public boolean isSurfaceReady() { return surfaceReady; }

    public android.view.Surface getSurface() {
        return surfaceReady ? surfaceView.getHolder().getSurface() : null;
    }

    // ------------------------------------------------------------------ views

    private void buildViewTree() {
        container = new FrameLayout(ctx);
        container.setClipChildren(true);

        surfaceView = new SurfaceView(ctx);
        surfaceView.getHolder().addCallback(this);
        surfaceView.setZOrderOnTop(true);
        surfaceView.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        container.addView(surfaceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void buildWindowParams() {
        int type;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            type = WindowManager.LayoutParams.TYPE_PHONE;
        }

        windowParams = new WindowManager.LayoutParams(
                initialBounds.width,
                initialBounds.height,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        windowParams.gravity = Gravity.TOP | Gravity.LEFT;
        windowParams.x = initialBounds.x;
        windowParams.y = initialBounds.y;
        windowParams.alpha = opacity;

        try {
            WindowManager.LayoutParams.class
                    .getMethod("setDisplayId", int.class)
                    .invoke(windowParams, displayId);
        } catch (Throwable ignored) {
        }
    }

    // -------------------------------------------------- SurfaceHolder.Callback

    @Override public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        if (surfaceCb != null) {
            surfaceCb.onSurfaceReady(holder.getSurface());
        }
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
    }
}
