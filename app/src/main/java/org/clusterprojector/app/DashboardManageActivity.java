package org.clusterprojector.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import org.clusterprojector.display.ClusterDashboardView;
import org.clusterprojector.display.ClusterLog;
import org.clusterprojector.display.DashboardConfig;

/**
 * 仪表管理：控制自绘仪表每个元素的显示/隐藏与位置偏移（-100% ~ +100%），
 * 以及"应用投屏时叠加透明仪表元素"总开关。修改即时保存，下次绘制生效。
 * 顶部带实时预览，配置变动立刻可见。
 */
public class DashboardManageActivity extends Activity {

    private LinearLayout elementRows;
    private ClusterDashboardView previewView;
    private Button previewThemeBtn;
    private int previewTheme = ClusterDashboardView.THEME_NIGHT;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard_manage);

        Button backBtn = findViewById(R.id.manageBackBtn);
        backBtn.setOnClickListener(v -> finish());

        // 合并总开关
        CheckBox mergeCheck = findViewById(R.id.mergeCheck);
        mergeCheck.setChecked(DashboardConfig.isMergeEnabled());
        mergeCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            DashboardConfig.setMergeEnabled(this, isChecked);
            ClusterLog.i("DashboardManage", "merge enabled -> " + isChecked);
        });

        elementRows = findViewById(R.id.elementRows);
        buildElementRows();

        // 实时预览：主题切换（日/夜），配置改动即时刷新
        previewView = findViewById(R.id.previewView);
        previewThemeBtn = findViewById(R.id.previewThemeBtn);
        previewView.setTheme(previewTheme);
        previewThemeBtn.setText(previewTheme == ClusterDashboardView.THEME_DAY ? "日间" : "夜间");
        previewThemeBtn.setOnClickListener(v -> {
            previewTheme = previewTheme == ClusterDashboardView.THEME_DAY
                    ? ClusterDashboardView.THEME_NIGHT : ClusterDashboardView.THEME_DAY;
            previewView.setTheme(previewTheme);
            previewThemeBtn.setText(previewTheme == ClusterDashboardView.THEME_DAY ? "日间" : "夜间");
            ClusterLog.i("DashboardManage", "preview theme -> " + previewTheme);
        });
    }

    private void buildElementRows() {
        elementRows.removeAllViews();
        DashboardConfig.Element[] elements = DashboardConfig.ELEMENTS;
        for (int i = 0; i < elements.length; i++) {
            elementRows.addView(buildElementRow(elements[i], i));
        }
    }

    private View buildElementRow(DashboardConfig.Element element, int index) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.card_bg);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(10);
        row.setLayoutParams(rowLp);

        // 名称 + 显示开关
        LinearLayout headRow = new LinearLayout(this);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(Gravity.CENTER_VERTICAL);
        headRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView name = new TextView(this);
        name.setText(element.label);
        name.setTextColor(0xFFB8BDD2);
        name.setTextSize(15);
        name.setTextDirection(View.TEXT_DIRECTION_LTR);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        name.setLayoutParams(nameLp);
        headRow.addView(name);

        CheckBox visibleCheck = new CheckBox(this);
        visibleCheck.setText("显示");
        visibleCheck.setTextColor(0xFFB8BDD2);
        visibleCheck.setButtonTintList(android.content.res.ColorStateList.valueOf(0xFF00D4FF));
        visibleCheck.setMinHeight(dp(40));
        visibleCheck.setChecked(DashboardConfig.elementVisibleOf(index));
        headRow.addView(visibleCheck);
        row.addView(headRow);

        // X 偏移滑块
        LinearLayout xRow = buildSliderRow("X 位置", index, true);
        row.addView(xRow);
        // Y 偏移滑块
        LinearLayout yRow = buildSliderRow("Y 位置", index, false);
        row.addView(yRow);

        final String elId = element.id;
        visibleCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            DashboardConfig.setElement(this, elId, isChecked,
                    DashboardConfig.elementDxOf(index), DashboardConfig.elementDyOf(index));
            ClusterLog.i("DashboardManage", "element " + elId + " visible -> " + isChecked);
            refreshPreview();
        });
        return row;
    }

    /** 配置变更后立即重绘预览。 */
    private void refreshPreview() {
        if (previewView != null) {
            previewView.invalidate();
        }
    }

    private LinearLayout buildSliderRow(String label, int index, final boolean isX) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView labelText = new TextView(this);
        labelText.setText(label);
        labelText.setTextColor(0xFF8B8FA3);
        labelText.setTextSize(13);
        row.addView(labelText);

        SeekBar bar = new SeekBar(this);
        bar.setMax(200);
        int value = isX ? DashboardConfig.elementDxOf(index) : DashboardConfig.elementDyOf(index);
        bar.setProgress(value + 100);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bar.setLayoutParams(barLp);
        row.addView(bar);

        TextView valueText = new TextView(this);
        valueText.setText((value > 0 ? "+" : "") + value + "%");
        valueText.setTextColor(0xFFB8BDD2);
        valueText.setTextSize(13);
        valueText.setMinWidth(dp(56));
        valueText.setGravity(Gravity.END);
        row.addView(valueText);

        final int idx = index;
        final String elId = DashboardConfig.ELEMENTS[index].id;
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int v = progress - 100;
                valueText.setText((v > 0 ? "+" : "") + v + "%");
                if (fromUser) {
                    DashboardConfig.setElement(DashboardManageActivity.this, elId,
                            DashboardConfig.elementVisibleOf(idx),
                            isX ? v : DashboardConfig.elementDxOf(idx),
                            isX ? DashboardConfig.elementDyOf(idx) : v);
                    refreshPreview();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        return row;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
