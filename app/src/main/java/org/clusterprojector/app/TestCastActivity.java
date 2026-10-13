package org.clusterprojector.app;

import android.app.Activity;
import android.graphics.Color;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.view.Gravity;

/**
 * Full-screen test activity launched on the virtual display to verify projection.
 * Cycles through solid colors so you can see the overlay is live.
 *
 * <p>本模块由 AI 辅助原创开发。
 */
public class TestCastActivity extends Activity {

    private static final int[] COLORS = {
            Color.parseColor("#E53935"),
            Color.parseColor("#43A047"),
            Color.parseColor("#1E88E5"),
            Color.parseColor("#FB8C00"),
    };
    private int colorIndex = 0;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(COLORS[0]);

        TextView tv = new TextView(this);
        tv.setText("投屏测试\n看到这个画面说明投屏成功\n点击切换颜色");
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(20);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(40, 40, 40, 40);
        root.addView(tv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        root.setOnClickListener(v -> {
            colorIndex = (colorIndex + 1) % COLORS.length;
            root.setBackgroundColor(COLORS[colorIndex]);
        });

        setContentView(root);
    }
}
