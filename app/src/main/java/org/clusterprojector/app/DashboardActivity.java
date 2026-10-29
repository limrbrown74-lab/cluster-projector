package org.clusterprojector.app;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;

import org.clusterprojector.display.ClusterDashboardView;
import org.clusterprojector.display.ClusterLog;
import org.clusterprojector.display.ClusterVehicleTelemetry;

/**
 * 特斯拉风格数字仪表盘预览页。
 *
 * <p>承载 {@link ClusterDashboardView} 全屏显示，支持日间/夜间手动切换
 * （默认按系统昼夜模式自动选择）。数据来自 {@link ClusterVehicleTelemetry}，
 * 无车机信号时显示 "--" 占位。
 */
public class DashboardActivity extends Activity {

    private ClusterDashboardView dashboardView;
    private Button themeButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);

        dashboardView = new ClusterDashboardView(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        lp.gravity = android.view.Gravity.CENTER;
        root.addView(dashboardView, lp);

        themeButton = new Button(this);
        themeButton.setText("日/夜切换");
        themeButton.setTextSize(12f);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        blp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        blp.topMargin = 8;
        blp.rightMargin = 8;
        root.addView(themeButton, blp);

        themeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int next = dashboardView.getThemeMode() == ClusterDashboardView.THEME_DAY
                        ? ClusterDashboardView.THEME_NIGHT
                        : ClusterDashboardView.THEME_DAY;
                dashboardView.setTheme(next);
                ClusterLog.i("Dashboard", "theme -> " + (next == ClusterDashboardView.THEME_DAY ? "day" : "night"));
            }
        });

        setContentView(root);
        applyAutoTheme();
        ClusterVehicleTelemetry.start();
        ClusterLog.i("Dashboard", "DashboardActivity started");
    }

    private void applyAutoTheme() {
        int nightMode = getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        int theme = (nightMode == Configuration.UI_MODE_NIGHT_YES)
                ? ClusterDashboardView.THEME_NIGHT
                : ClusterDashboardView.THEME_DAY;
        dashboardView.setTheme(theme);
        themeButton.setText(theme == ClusterDashboardView.THEME_DAY ? "日间" : "夜间");
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        ClusterVehicleTelemetry.start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ClusterLog.i("Dashboard", "DashboardActivity destroyed");
    }
}
