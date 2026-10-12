package org.clusterprojector.display;

import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.view.Surface;

/**
 * 虚拟屏管理：创建 {@link VirtualDisplay} 并把指定应用启动到该屏上。
 *
 * <p>虚拟屏的输出 Surface 与仪表盘悬浮窗渲染的 Surface 相同，
 * 因此被启动应用的画面会直接呈现在仪表盘上。
 *
 * <p>典型用法：
 * <pre>
 *   VirtualScreenHost vd = new VirtualScreenHost(ctx, projection);
 *   vd.create(width, height, density, surface);
 *   vd.launchApp(packageName);
 *   vd.release();
 * </pre>
 *
 * <p>本模块由 AI 辅助原创开发。
 */
public final class VirtualScreenHost {

    private final Context ctx;
    private final MediaProjection mediaProjection;
    private VirtualDisplay vd;

    public VirtualScreenHost(Context context, MediaProjection projection) {
        this.ctx = context.getApplicationContext();
        this.mediaProjection = projection;
    }

    /** 创建虚拟屏并把输出路由到 {@code surface}。 */
    public void create(int width, int height, int densityDpi, Surface surface) {
        release();
        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
        vd = mediaProjection.createVirtualDisplay(
                "cluster-vd",
                width, height, densityDpi,
                flags,
                surface, null, null);
    }

    /** @return 虚拟屏 Display id，未创建时返回 -1。 */
    public int getDisplayId() {
        return vd != null ? vd.getDisplay().getDisplayId() : -1;
    }

    /** 在虚拟屏上启动指定应用。 */
    public boolean launchApp(String packageName) {
        if (vd == null) return false;
        Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) return false;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        try {
            android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(vd.getDisplay().getDisplayId());
            ctx.startActivity(launch, opts.toBundle());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 启动测试画面 Activity，验证投屏链路。 */
    public boolean launchTestScreen(Class<?> testActivityClass) {
        if (vd == null) return false;
        Intent launch = new Intent(ctx, testActivityClass);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(vd.getDisplay().getDisplayId());
            ctx.startActivity(launch, opts.toBundle());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 更新输出 Surface（如悬浮窗重建后）。 */
    public void setSurface(Surface surface) {
        if (vd != null) {
            vd.setSurface(surface);
        }
    }

    public void release() {
        if (vd != null) {
            vd.release();
            vd = null;
        }
    }
}
