package org.clusterprojector.display;

import android.app.Presentation;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.Display;
import android.view.WindowManager;

/**
 * 仪表盘投物理屏宿主（切屏链路）。
 *
 * <p>把 {@link ClusterDashboardView}（特斯拉风格数字仪表）通过 {@link Presentation}
 * 投到仪表物理屏（FX11 1920x532）。启动流程：
 * <ol>
 *   <li>子线程调用 {@link ClusterProjectionSwitch#enable} 物理切屏
 *       （ECARX 三屏优先、VHAL 叠加兜底），把仪表屏从 QNX 切到 Android 显示源；</li>
 *   <li>在目标 Display 上 {@link Presentation#show()}，内容为仪表盘视图。</li>
 * </ol>
 *
 * <p>停止时：dismiss Presentation 并 {@link ClusterProjectionSwitch#disable} 恢复原厂仪表。
 */
public final class ClusterDashboardHost {

    private static final String TAG = "ClusterDashboardHost";

    private final Context ownerContext;
    private final Display clusterDisplay;

    private DashboardPresentation presentation;
    private ClusterDashboardView dashboardView;
    private boolean switchDone;
    private String switchPath = "未切换";
    private Thread switchWorker;
    private boolean stopping;

    /** 切屏结果回调（主线程）。 */
    public interface Callback {
        void onSwitchDone(boolean ok, String path);
    }

    private Callback callback;

    public ClusterDashboardHost(Context context, Display clusterDisplay) {
        this.ownerContext = context;
        this.clusterDisplay = clusterDisplay;
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    /** 启动：物理切屏（异步）+ 仪表 Presentation。 */
    public synchronized void start() {
        if (presentation != null) {
            ClusterLog.i(TAG, "start ignored: already running");
            return;
        }
        stopping = false;
        switchDone = false;
        switchPath = "切换中";

        beginSwitch();

        try {
            presentation = new DashboardPresentation(ownerContext, clusterDisplay);
            presentation.show();
            ClusterLog.i(TAG, "DashboardPresentation shown on display="
                    + clusterDisplay.getDisplayId());
        } catch (Throwable t) {
            ClusterLog.e(TAG, "dashboard presentation show failed", t);
            presentation = null;
        }
    }

    /** 停止：恢复原厂仪表并关闭 Presentation。 */
    public synchronized void stop() {
        stopping = true;
        if (presentation != null) {
            try {
                presentation.dismiss();
            } catch (Throwable t) {
                ClusterLog.w(TAG, "dismiss dashboard presentation failed", t);
            }
            presentation = null;
        }
        dashboardView = null;
        if (switchWorker != null) {
            switchWorker.interrupt();
            switchWorker = null;
        }
        if (switchDone || ClusterProjectionSwitch.isActive()) {
            try {
                ClusterProjectionSwitch.disable();
                ClusterLog.i(TAG, "physical switch disabled (stock QNX restored)");
            } catch (Throwable t) {
                ClusterLog.w(TAG, "physical switch disable failed", t);
            }
        }
        switchDone = false;
        switchPath = "未切换";
    }

    public boolean isRunning() {
        return presentation != null;
    }

    public boolean isSwitchDone() {
        return switchDone;
    }

    public String getSwitchPath() {
        return switchPath;
    }

    public String getStatus() {
        return "Presentation=" + (presentation != null ? "YES" : "NO")
                + " Switch=" + switchPath;
    }

    // ------------------------------------------------------------------

    /** 子线程物理切屏，不阻塞主流程。 */
    private void beginSwitch() {
        final Context ctx = ownerContext;
        switchWorker = new Thread(() -> {
            boolean ok = ClusterProjectionSwitch.enable(ctx, true, true);
            ClusterLog.i(TAG, "physical switch worker done ok=" + ok
                    + " path=" + ClusterProjectionSwitch.activeName()
                    + " active=" + ClusterProjectionSwitch.isActive());
            if (stopping) {
                return;
            }
            if (ownerContext instanceof android.app.Activity) {
                ((android.app.Activity) ownerContext).runOnUiThread(() -> {
                    switchDone = ClusterProjectionSwitch.isActive();
                    switchPath = ClusterProjectionSwitch.activeName();
                    if (callback != null) {
                        callback.onSwitchDone(ok, switchPath);
                    }
                    ClusterLog.i(TAG, "switch done ok=" + ok + " path=" + switchPath);
                });
            }
        }, "cluster-dashboard-switch");
        switchWorker.setDaemon(true);
        switchWorker.start();
    }

    // ------------------------------------------------------------------

    private final class DashboardPresentation extends Presentation {

        DashboardPresentation(Context outerContext, Display display) {
            super(outerContext, display);
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            try {
                dashboardView = new ClusterDashboardView(getContext());
                // 按系统昼夜模式自动选主题
                int nightMode = getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK;
                int theme = (nightMode == Configuration.UI_MODE_NIGHT_YES)
                        ? ClusterDashboardView.THEME_NIGHT
                        : ClusterDashboardView.THEME_DAY;
                dashboardView.setTheme(theme);
                setContentView(dashboardView, new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT));
                ClusterLog.i(TAG, "dashboard view attached, theme="
                        + (theme == ClusterDashboardView.THEME_DAY ? "day" : "night"));
            } catch (Throwable t) {
                ClusterLog.e(TAG, "dashboard presentation create failed", t);
            }
        }
    }
}
