package org.clusterprojector.display;

import android.app.Presentation;
import android.content.Context;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * 仪表地图直通宿主。
 *
 * <p>在仪表 Display 上创建一个 {@link Presentation}，内部放一个 {@link SurfaceView}。
 * Surface 就绪后：
 * <ol>
 *   <li>调用 {@link AvmServiceClient#naviAddSurface} 让原厂地图渲染到该 Surface</li>
 *   <li>调用 {@link AvmServiceClient#avmSetDisplaySurface} 把 Surface 投到仪表物理屏</li>
 * </ol>
 *
 * <p>这就是"游戏模式黑屏后可以投屏"的官方通道。
 */
public final class ClusterMapHost {

    private static final String TAG = "ClusterMapHost";

    private final Context ownerContext;
    private final Display clusterDisplay;
    private final AvmServiceClient avmClient;

    private MapPresentation presentation;
    private SurfaceView surfaceView;
    private boolean surfaceReady;
    private boolean mapAttached;
    private boolean surfaceSubmitted;

    public ClusterMapHost(Context context, Display clusterDisplay, AvmServiceClient avmClient) {
        this.ownerContext = context;
        this.clusterDisplay = clusterDisplay;
        this.avmClient = avmClient;
    }

    /** 启动仪表地图直通。 */
    public synchronized void start() {
        if (presentation != null) {
            Log.w(TAG, "already started");
            return;
        }
        try {
            presentation = new MapPresentation(ownerContext, clusterDisplay);
            presentation.show();
            Log.i(TAG, "MapPresentation shown on display=" + clusterDisplay.getDisplayId());
        } catch (Exception e) {
            Log.e(TAG, "start failed", e);
        }
    }

    /** 停止仪表地图直通。 */
    public synchronized void stop() {
        detachSurface();
        if (presentation != null) {
            try {
                presentation.dismiss();
            } catch (Exception ignored) {}
            presentation = null;
        }
        surfaceView = null;
        surfaceReady = false;
        mapAttached = false;
        surfaceSubmitted = false;
    }

    public boolean isRunning() {
        return presentation != null;
    }

    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("Presentation=").append(presentation != null ? "YES" : "NO");
        sb.append(" Surface=").append(surfaceReady ? "READY" : "WAIT");
        sb.append(" Map=").append(mapAttached ? "ATTACHED" : "NOT");
        sb.append(" Submitted=").append(surfaceSubmitted ? "YES" : "NO");
        return sb.toString();
    }

    // ------------------------------------------------------------------

    private void onSurfaceReady(SurfaceHolder holder) {
        surfaceReady = true;
        Log.i(TAG, "Surface ready: " + holder.getSurface());

        // 第一步：让原厂地图渲染到这个 Surface
        attachMap();

        // 第二步：把 Surface 投到仪表物理屏
        submitSurface();
    }

    private void onSurfaceDestroyed() {
        surfaceReady = false;
        detachSurface();
    }

    private void attachMap() {
        if (surfaceView == null || !surfaceReady) return;
        if (!avmClient.isNaviReady()) {
            Log.w(TAG, "Navi not ready, skip attachMap");
            return;
        }
        DisplayMetrics metrics = new DisplayMetrics();
        clusterDisplay.getMetrics(metrics);
        boolean ok = avmClient.naviAddSurface(surfaceView.getHolder().getSurface(),
                0, metrics.widthPixels, metrics.heightPixels);
        mapAttached = ok;
        Log.i(TAG, "attachMap ok=" + ok + " size=" + metrics.widthPixels + "x" + metrics.heightPixels);
    }

    private void submitSurface() {
        if (surfaceView == null || !surfaceReady) return;
        if (!avmClient.isParkingReady()) {
            Log.w(TAG, "Parking not ready, skip submitSurface");
            return;
        }
        // 先打开 AVM 模式，让 QNX 停止渲染原厂仪表
        avmClient.openAvm();
        // 把 Surface 投到仪表屏
        boolean ok = avmClient.avmSetDisplaySurface(surfaceView.getHolder().getSurface());
        surfaceSubmitted = ok;
        Log.i(TAG, "submitSurface ok=" + ok);
    }

    private void detachSurface() {
        if (surfaceView != null && avmClient.isNaviReady()) {
            avmClient.naviRemoveSurface(surfaceView.getHolder().getSurface(), 0);
        }
        if (avmClient.isParkingReady()) {
            avmClient.avmSetDisplaySurface(null);
            avmClient.closeAvm();
        }
        mapAttached = false;
        surfaceSubmitted = false;
    }

    // ------------------------------------------------------------------

    private final class MapPresentation extends Presentation {

        MapPresentation(Context outerContext, Display display) {
            super(outerContext, display);
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            FrameLayout container = new FrameLayout(getContext());
            surfaceView = new SurfaceView(getContext());
            surfaceView.setZOrderOnTop(true);
            surfaceView.getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
            surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder holder) {
                    Log.i(TAG, "surfaceCreated");
                    onSurfaceReady(holder);
                }

                @Override
                public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                    Log.i(TAG, "surfaceChanged " + width + "x" + height);
                }

                @Override
                public void surfaceDestroyed(SurfaceHolder holder) {
                    Log.i(TAG, "surfaceDestroyed");
                    onSurfaceDestroyed();
                }
            });
            container.addView(surfaceView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            setContentView(container, new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT));
        }
    }
}
