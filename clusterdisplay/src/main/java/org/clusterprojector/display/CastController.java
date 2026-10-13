package org.clusterprojector.display;

import android.content.Context;
import android.media.projection.MediaProjection;
import android.util.Log;
import android.view.Display;

/** FX11 仪表投屏控制器。 */
public final class CastController {

    private static final String TAG = "ClusterCast";

    private final Context context;
    private final DisplayTarget clusterInfo;
    private final Display clusterDisplay;

    private MediaProjection mediaProjection;
    private OverlayWindowHost overlay;
    private VirtualScreenHost virtualScreen;
    private WindowGeometry bounds;
    private float opacity = 1f;
    private boolean active;
    private boolean virtualReady;
    private String pendingPackage;
    private Class<?> pendingTestActivity;
    private Listener listener;

    public interface Listener {
        void onVirtualDisplayReady(int virtualDisplayId);
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
    public boolean isVirtualReady() { return virtualReady; }
    public WindowGeometry getBounds() { return bounds; }
    public float getAlpha() { return opacity; }
    public int getVirtualDisplayId() { return virtualScreen == null ? -1 : virtualScreen.getDisplayId(); }

    public void start(MediaProjection projection) {
        if (active) return;
        active = true;
        virtualReady = false;
        mediaProjection = projection;

        // FX11 实测云盘链路的窗口为 960x266，位于 1920x532 屏幕中央。
        bounds = WindowGeometry.centered(clusterInfo.getWidth(), clusterInfo.getHeight());
        overlay = new OverlayWindowHost(
                context,
                clusterDisplay,
                clusterInfo.getWidth(),
                clusterInfo.getHeight(),
                bounds);
        overlay.setSurfaceCallback(new OverlayWindowHost.SurfaceCallback() {
            @Override public void onSurfaceAvailable(android.view.Surface surface) {
                Log.i(TAG, "surface available");
            }

            @Override public void onSurfaceChanged(android.view.Surface surface, int width, int height) {
                attachOrUpdateVirtualDisplay(surface);
            }

            @Override public void onSurfaceDestroyed() {
                Log.i(TAG, "surface destroyed");
            }
        });
        overlay.start();
    }

    private void attachOrUpdateVirtualDisplay(android.view.Surface surface) {
        try {
            if (virtualScreen == null) {
                virtualScreen = new VirtualScreenHost(context, mediaProjection);
                virtualScreen.create(
                        clusterInfo.getWidth(),
                        clusterInfo.getHeight(),
                        clusterInfo.getDensityDpi(),
                        surface);
                virtualReady = true;
                if (listener != null) listener.onVirtualDisplayReady(virtualScreen.getDisplayId());
                flushPendingLaunch();
            } else {
                virtualScreen.setSurface(surface);
            }
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

        if (test != null) {
            launchTestScreen(test);
        } else if (pkg != null) {
            launchApp(pkg);
        }
    }

    public boolean launchApp(String packageName) {
        if (!active) return false;
        if (!virtualReady || virtualScreen == null) {
            pendingPackage = packageName;
            Log.i(TAG, "virtual display not ready, pending " + packageName);
            return true;
        }
        boolean ok = virtualScreen.launchApp(packageName);
        String error = ok ? null : virtualScreen.getLastError();
        if (listener != null) listener.onAppLaunched(packageName, ok, error);
        return ok;
    }

    public boolean launchTestScreen(Class<?> testActivityClass) {
        if (!active) return false;
        if (!virtualReady || virtualScreen == null) {
            pendingTestActivity = testActivityClass;
            return true;
        }
        boolean ok = virtualScreen.launchActivity(testActivityClass);
        String label = testActivityClass.getName();
        if (listener != null) listener.onAppLaunched(label, ok, ok ? null : virtualScreen.getLastError());
        return ok;
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
        if (!active && overlay == null && virtualScreen == null) return;
        active = false;
        virtualReady = false;
        pendingPackage = null;
        pendingTestActivity = null;
        if (virtualScreen != null) {
            virtualScreen.release();
            virtualScreen = null;
        }
        if (overlay != null) {
            overlay.stop();
            overlay = null;
        }
        mediaProjection = null;
    }
}
