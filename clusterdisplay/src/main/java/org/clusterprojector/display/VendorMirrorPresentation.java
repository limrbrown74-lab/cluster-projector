package org.clusterprojector.display;

import android.app.Presentation;
import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.view.WindowManager;

/**
 * FX11/ecarx 实验链路：标准 Presentation + 全屏 SurfaceView + MediaProjection 直连。
 *
 * <p>对照车机上“应用管家 - 同步显示”的关键实现：不经过编码器、不经过 OpenGL 中转，
 * 也不把第三方 Activity 直接启动到目标 Display；而是把中控默认屏镜像到仪表
 * Presentation 内的 SurfaceView。普通彩色 View 预检不可见，不代表硬件 SurfaceView
 * 层一定不能进入 QNX/WFD 合成。
 */
public final class VendorMirrorPresentation extends Presentation {

    private static final String TAG = "ClusterVendor";
    private static final String VIRTUAL_DISPLAY_NAME = "SecondaryScreenMirror";

    /** 应用管家 1.8.1 实测使用的 flags：MediaProjection + PUBLIC。 */
    private static final int VENDOR_FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;

    private final MediaProjection mediaProjection;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final int targetWidth;
    private final int targetHeight;
    private final int targetDensityDpi;
    private final Callback callback;

    private SurfaceView surfaceView;
    private VirtualDisplay virtualDisplay;
    private boolean surfaceAlive;
    private boolean dismissed;

    public interface Callback {
        void onVendorSurfaceReady(int virtualDisplayId);
        void onVendorSurfaceFailed(Exception error);
    }

    public VendorMirrorPresentation(Context context, Display display,
            int targetWidth, int targetHeight, int targetDensityDpi,
            MediaProjection mediaProjection, Callback callback) {
        super(context, display);
        this.targetWidth = targetWidth;
        this.targetHeight = targetHeight;
        this.targetDensityDpi = targetDensityDpi;
        this.mediaProjection = mediaProjection;
        this.callback = callback;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ClusterLog.i(TAG, "vendor Presentation onCreate: display=" + getDisplay().getDisplayId()
                + " target=" + targetWidth + "x" + targetHeight + "@" + targetDensityDpi
                + " flags=0x" + Integer.toHexString(VENDOR_FLAGS));

        surfaceView = new SurfaceView(getContext());
        surfaceView.getHolder().setFormat(PixelFormat.OPAQUE);
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                surfaceAlive = true;
                ClusterLog.i(TAG, "vendor surfaceCreated valid="
                        + (holder.getSurface() != null && holder.getSurface().isValid())
                        + " frame=" + holder.getSurfaceFrame()
                        + " display=" + safeDisplayId(surfaceView));
                createVirtualDisplay(holder.getSurface());
            }

            @Override public void surfaceChanged(SurfaceHolder holder, int format,
                    int width, int height) {
                ClusterLog.i(TAG, "vendor surfaceChanged: " + width + "x" + height
                        + " format=" + format + " valid="
                        + (holder.getSurface() != null && holder.getSurface().isValid()));
                if (virtualDisplay != null && holder.getSurface() != null
                        && holder.getSurface().isValid()) {
                    try {
                        virtualDisplay.setSurface(holder.getSurface());
                        ClusterLog.i(TAG, "vendor VirtualDisplay surface replaced: id="
                                + virtualDisplay.getDisplay().getDisplayId());
                    } catch (Throwable t) {
                        ClusterLog.w(TAG, "vendor setSurface failed", t);
                    }
                }
            }

            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                surfaceAlive = false;
                ClusterLog.w(TAG, "vendor surfaceDestroyed: frame="
                        + holder.getSurfaceFrame() + " dismissed=" + dismissed);
                releaseVirtualDisplay("surface destroyed");
            }
        });
        setContentView(surfaceView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        WindowManager.LayoutParams windowAttrs = getWindow().getAttributes();
        windowAttrs.width = WindowManager.LayoutParams.MATCH_PARENT;
        windowAttrs.height = WindowManager.LayoutParams.MATCH_PARENT;
        windowAttrs.dimAmount = 0f;
        getWindow().setAttributes(windowAttrs);
    }

    public int getVirtualDisplayId() {
        return virtualDisplay == null || virtualDisplay.getDisplay() == null
                ? -1 : virtualDisplay.getDisplay().getDisplayId();
    }

    private void createVirtualDisplay(Surface outputSurface) {
        if (dismissed || mediaProjection == null) {
            notifyFailed(new IllegalStateException("投影会话已失效"));
            return;
        }
        if (outputSurface == null || !outputSurface.isValid()) {
            notifyFailed(new IllegalStateException("仪表 Surface 无效"));
            return;
        }
        try {
            releaseVirtualDisplay("replace before create");
            long begin = android.os.SystemClock.elapsedRealtime();
            virtualDisplay = mediaProjection.createVirtualDisplay(
                    VIRTUAL_DISPLAY_NAME,
                    targetWidth,
                    targetHeight,
                    targetDensityDpi,
                    VENDOR_FLAGS,
                    outputSurface,
                    new VirtualDisplay.Callback() {
                        @Override public void onPaused() {
                            ClusterLog.i(TAG, "vendor VirtualDisplay paused: id="
                                    + getVirtualDisplayId());
                        }

                        @Override public void onResumed() {
                            ClusterLog.i(TAG, "vendor VirtualDisplay resumed: id="
                                    + getVirtualDisplayId());
                        }

                        @Override public void onStopped() {
                            ClusterLog.w(TAG, "vendor VirtualDisplay stopped: id="
                                    + getVirtualDisplayId() + " dismissed=" + dismissed);
                        }
                    },
                    mainHandler);
            if (virtualDisplay == null || virtualDisplay.getDisplay() == null) {
                throw new IllegalStateException("createVirtualDisplay 返回空 Display");
            }
            int elapsed = (int) (android.os.SystemClock.elapsedRealtime() - begin);
            ClusterLog.i(TAG, "vendor VirtualDisplay ready in " + elapsed + "ms: id="
                    + virtualDisplay.getDisplay().getDisplayId()
                    + " name=" + virtualDisplay.getDisplay().getName()
                    + " state=" + virtualDisplay.getDisplay().getState()
                    + " flags=0x" + Integer.toHexString(virtualDisplay.getDisplay().getFlags())
                    + " size=" + targetWidth + "x" + targetHeight);
            if (callback != null) {
                callback.onVendorSurfaceReady(virtualDisplay.getDisplay().getDisplayId());
            }
        } catch (Exception e) {
            ClusterLog.e(TAG, "vendor VirtualDisplay create failed", e);
            notifyFailed(e);
        }
    }

    public void releaseVirtualDisplay(String reason) {
        VirtualDisplay current = virtualDisplay;
        virtualDisplay = null;
        if (current != null) {
            ClusterLog.i(TAG, "release vendor VirtualDisplay: reason=" + reason
                    + " id=" + (current.getDisplay() == null
                            ? -1 : current.getDisplay().getDisplayId()));
            try {
                current.release();
            } catch (Throwable t) {
                ClusterLog.w(TAG, "release vendor VirtualDisplay failed", t);
            }
        }
    }

    @Override
    public void dismiss() {
        dismissed = true;
        releaseVirtualDisplay("Presentation dismiss");
        super.dismiss();
    }

    public boolean isSurfaceAlive() {
        return surfaceAlive;
    }

    private void notifyFailed(Exception error) {
        if (callback != null) callback.onVendorSurfaceFailed(error);
    }

    private static int safeDisplayId(SurfaceView view) {
        try {
            return view.getDisplay() == null ? Integer.MIN_VALUE : view.getDisplay().getDisplayId();
        } catch (Throwable t) {
            return Integer.MIN_VALUE;
        }
    }
}
