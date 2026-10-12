package org.clusterprojector.display;

import android.content.Context;
import android.media.projection.MediaProjection;
import android.util.DisplayMetrics;
import android.view.Display;

/**
 * 仪表盘投屏统一控制器。
 * 创建仪表盘悬浮窗与虚拟屏，把已启动应用的画面路由到悬浮窗 Surface。
 *
 * <p>所有窗口几何参数（位置 / 尺寸 / 透明度）均由中控屏控制面板驱动，
 * 因为仪表盘本身无触控能力。
 *
 * <p>本模块由 AI 辅助原创开发。
 */
public final class CastController {

    private final Context context;
    private final DisplayTarget clusterInfo;
    private final Display clusterDisplay;

    private MediaProjection mediaProjection;
    private OverlayWindowHost overlayCtrl;
    private VirtualScreenHost virtualDisplay;
    private WindowGeometry windowBounds;
    private float opacity = 1f;
    private boolean active;

    /** 投屏事件回调。 */
    public interface Listener {
        /** 应用启动结果回调。 */
        void onAppLaunched(String packageName, boolean success);
        /** 窗口几何参数变化回调。 */
        void onBoundsChanged(WindowGeometry bounds);
    }

    private Listener eventListener;

    public CastController(Context context, DisplayTarget clusterInfo,
                                       Display clusterDisplay) {
        this.context = context.getApplicationContext();
        this.clusterInfo = clusterInfo;
        this.clusterDisplay = clusterDisplay;
    }

    public void setListener(Listener listener) { this.eventListener = listener; }
    public boolean isRunning() { return active; }
    public WindowGeometry getBounds() { return windowBounds; }
    public float getAlpha() { return opacity; }

    /**
     * 在仪表盘上创建悬浮窗。虚拟屏在悬浮窗 Surface 就绪后懒加载创建。
     */
    public void start(MediaProjection projection, DisplayMetrics metrics) {
        if (active) return;
        active = true;
        this.mediaProjection = projection;

        windowBounds = WindowGeometry.centered(
                clusterInfo.getWidth(), clusterInfo.getHeight());

        overlayCtrl = new OverlayWindowHost(context, clusterInfo.getDisplayId(), windowBounds);
        overlayCtrl.setSurfaceCallback(surface -> {
            virtualDisplay = new VirtualScreenHost(context, mediaProjection);
            virtualDisplay.create(
                    clusterInfo.getWidth(),
                    clusterInfo.getHeight(),
                    metrics.densityDpi,
                    surface);
        });
        overlayCtrl.start();
    }

    /** 在仪表盘悬浮窗上启动指定应用。 */
    public boolean launchApp(String packageName) {
        if (virtualDisplay == null) return false;
        boolean ok = virtualDisplay.launchApp(packageName);
        if (eventListener != null) eventListener.onAppLaunched(packageName, ok);
        return ok;
    }

    /** 启动内置测试画面，验证投屏链路。 */
    public boolean launchTestScreen(Class<?> testActivityClass) {
        if (virtualDisplay == null) return false;
        return virtualDisplay.launchTestScreen(testActivityClass);
    }

    /** 从中控屏控制面板更新悬浮窗位置/尺寸。 */
    public void setBounds(WindowGeometry newBounds) {
        this.windowBounds = newBounds;
        if (overlayCtrl != null) overlayCtrl.setBounds(newBounds);
        if (eventListener != null) eventListener.onBoundsChanged(newBounds);
    }

    /** 更新悬浮窗不透明度（0..1）。 */
    public void setAlpha(float alpha) {
        this.opacity = alpha;
        if (overlayCtrl != null) overlayCtrl.setAlpha(alpha);
    }

    public void stop() {
        if (!active) return;
        active = false;
        if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
        if (overlayCtrl != null) { overlayCtrl.stop(); overlayCtrl = null; }
        mediaProjection = null;
    }
}
