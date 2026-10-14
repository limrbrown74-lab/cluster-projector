package org.clusterprojector.display;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 不使用 MediaProjection 的官方副屏 Presentation 预检。
 *
 * <p>目的：在进入 SurfaceView / VirtualDisplay / 镜像链路之前，先确认 ecarx
 * 仪表物理合成层是否真的接受普通第三方 App 的标准 Presentation 窗口。
 */
public final class ClusterPresentationProbe {

    private static final String TAG = "ClusterProbe";

    private static final int[] COLORS = {
            Color.parseColor("#D50000"),
            Color.parseColor("#00C853"),
            Color.parseColor("#2962FF"),
            Color.parseColor("#FFAB00"),
    };

    private final Activity activity;
    private final Display display;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ProbePresentation presentation;
    private int tick;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (presentation == null || !presentation.isShowing()) return;
            presentation.renderTick(tick);
            tick++;
            handler.postDelayed(this, 1000L);
        }
    };

    public ClusterPresentationProbe(Activity activity, Display display) {
        this.activity = activity;
        this.display = display;
    }

    public void show() {
        if (activity == null) throw new IllegalArgumentException("Activity 为空，无法创建 Presentation");
        if (display == null) throw new IllegalArgumentException("目标 Display 为空");

        logDisplayInfo("show begin");
        presentation = new ProbePresentation(activity, display);
        presentation.setCancelable(false);
        presentation.show();

        ClusterLog.i(TAG, "Presentation.show() returned without exception: display="
                + display.getDisplayId());
        handler.post(() -> logPresentationState("after-show-0ms"));
        handler.postDelayed(() -> logPresentationState("after-show-500ms"), 500L);
        handler.postDelayed(() -> logPresentationState("after-show-2000ms"), 2000L);
        handler.post(ticker);
    }

    public boolean isShowing() {
        return presentation != null && presentation.isShowing();
    }

    public void dismiss(String reason) {
        ClusterLog.i(TAG, "dismiss probe: reason=" + reason + " ticks=" + tick);
        handler.removeCallbacksAndMessages(null);
        if (presentation != null) {
            try {
                presentation.dismiss();
            } catch (Throwable t) {
                ClusterLog.w(TAG, "dismiss probe failed", t);
            }
            presentation = null;
        }
    }

    private void logDisplayInfo(String stage) {
        DisplayMetrics metrics = new DisplayMetrics();
        try {
            display.getRealMetrics(metrics);
        } catch (Throwable t) {
            ClusterLog.w(TAG, "probe getRealMetrics failed", t);
        }
        ClusterLog.i(TAG, stage + ": displayId=" + display.getDisplayId()
                + " name=" + display.getName()
                + " state=" + display.getState()
                + " flags=0x" + Integer.toHexString(display.getFlags())
                + " rotation=" + display.getRotation()
                + " real=" + metrics.widthPixels + "x" + metrics.heightPixels
                + "@" + metrics.densityDpi
                + " isValid=" + display.isValid());
    }

    private void logPresentationState(String stage) {
        if (presentation == null) {
            ClusterLog.i(TAG, "probe state " + stage + ": presentation=null");
            return;
        }
        presentation.logState(stage);
    }

    private final class ProbePresentation extends Presentation {
        private FrameLayout root;
        private TextView titleView;
        private TextView subtitleView;

        ProbePresentation(Context context, Display display) {
            super(context, display);
        }

        @Override protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            try {
                getWindow().requestFeature(android.view.Window.FEATURE_NO_TITLE);
            } catch (Throwable t) {
                ClusterLog.w(TAG, "probe request no title failed", t);
            }

            android.view.Window window = getWindow();
            window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setFormat(PixelFormat.OPAQUE);

            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.MATCH_PARENT;
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
            window.setAttributes(lp);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);

            root = new FrameLayout(getContext());
            root.setBackgroundColor(COLORS[0]);

            titleView = new TextView(getContext());
            titleView.setTextColor(Color.WHITE);
            titleView.setGravity(Gravity.CENTER);
            titleView.setShadowLayer(10f, 0f, 2f, Color.BLACK);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 38f);
            titleView.setText("仪表副屏预检\nDisplay " + getDisplay().getDisplayId());

            subtitleView = new TextView(getContext());
            subtitleView.setTextColor(Color.WHITE);
            subtitleView.setGravity(Gravity.CENTER);
            subtitleView.setShadowLayer(8f, 0f, 2f, Color.BLACK);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22f);
            subtitleView.setText("看到全屏闪烁色块，说明官方 Presentation 可用");

            FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            titleLp.gravity = Gravity.CENTER;
            root.addView(titleView, titleLp);

            FrameLayout.LayoutParams subtitleLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            subtitleLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            subtitleLp.bottomMargin = 36;
            root.addView(subtitleView, subtitleLp);

            setContentView(root);
            renderTick(0);
            ClusterLog.i(TAG, "probe Presentation onCreate: display="
                    + getDisplay().getDisplayId()
                    + " flags=0x" + Integer.toHexString(getDisplay().getFlags()));
        }

        @Override protected void onStart() {
            super.onStart();
            ClusterLog.i(TAG, "probe Presentation onStart: display="
                    + getDisplay().getDisplayId());
            logState("onStart");
        }

        @Override protected void onStop() {
            ClusterLog.i(TAG, "probe Presentation onStop: display="
                    + getDisplay().getDisplayId());
            super.onStop();
        }

        @Override public void onDisplayChanged() {
            super.onDisplayChanged();
            ClusterLog.i(TAG, "probe Presentation onDisplayChanged: display="
                    + getDisplay().getDisplayId() + " state=" + getDisplay().getState()
                    + " flags=0x" + Integer.toHexString(getDisplay().getFlags()));
            logState("display-changed");
        }

        @Override public void onDisplayRemoved() {
            ClusterLog.w(TAG, "probe Presentation onDisplayRemoved: display="
                    + getDisplay().getDisplayId());
            super.onDisplayRemoved();
        }

        void renderTick(int index) {
            if (root == null) return;
            int colorIndex = index % COLORS.length;
            root.setBackgroundColor(COLORS[colorIndex]);
            titleView.setText("仪表副屏预检\nDisplay " + getDisplay().getDisplayId()
                    + " / 颜色 " + (colorIndex + 1));
            subtitleView.setText("看到这个闪烁画面才点“看到了”\n"
                    + "tick=" + index + " uptime=" + SystemClock.uptimeMillis());
            logState("tick-" + index);
        }

        void logState(String stage) {
            try {
                View decor = getWindow() == null ? null : getWindow().peekDecorView();
                int[] location = new int[]{Integer.MIN_VALUE, Integer.MIN_VALUE};
                if (decor != null && decor.isAttachedToWindow()) {
                    decor.getLocationOnScreen(location);
                }
                Display actualDisplay = root != null && root.getDisplay() != null
                        ? root.getDisplay() : getDisplay();
                ClusterLog.i(TAG, "probe state[" + stage + "] showing=" + isShowing()
                        + " display=" + actualDisplay.getDisplayId()
                        + " state=" + actualDisplay.getState()
                        + " flags=0x" + Integer.toHexString(actualDisplay.getFlags())
                        + " rootAttached=" + (root != null && root.isAttachedToWindow())
                        + " rootVisibility=" + (root == null ? -1 : root.getVisibility())
                        + " rootSize=" + (root == null ? -1 : root.getWidth()) + "x"
                        + (root == null ? -1 : root.getHeight())
                        + " decorAttached=" + (decor != null && decor.isAttachedToWindow())
                        + " decorVisibility=" + (decor == null ? -1 : decor.getVisibility())
                        + " decorSize=" + (decor == null ? -1 : decor.getWidth()) + "x"
                        + (decor == null ? -1 : decor.getHeight())
                        + " decorLoc=" + location[0] + "," + location[1]);
            } catch (Throwable t) {
                ClusterLog.w(TAG, "probe logState failed stage=" + stage, t);
            }
        }
    }
}
