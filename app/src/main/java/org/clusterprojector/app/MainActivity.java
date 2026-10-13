package org.clusterprojector.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.clusterprojector.display.DisplayTarget;
import org.clusterprojector.display.DisplayEnumerator;
import org.clusterprojector.display.CastController;
import org.clusterprojector.display.WindowGeometry;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_OVERLAY = 1001;
    private static final int REQ_PROJECTION = 1002;

    private MediaProjectionManager projectionManager;
    private DisplayEnumerator displayManager;

    private TextView statusText;
    private ListView appList;
    private Spinner displaySpinner;
    private SeekBar seekX, seekY, seekW, seekH, seekAlpha;
    private Button testBtn, stopBtn;

    private CastController controller;
    private DisplayTarget targetDisplay;
    private Display targetDisplayObj;
    private String pendingPackage;
    private boolean projecting;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 安装全局崩溃捕获 + 初始化日志
        CrashHandler.install();
        AppLog.i("MainActivity", "onCreate start, displayId=" + getDisplay().getDisplayId());

        try {
        // If we were launched onto a non-default display (i.e. the user projected
        // this very app onto the cluster), show a simple placeholder instead of
        // re-initializing the whole control panel. The center-stack instance keeps
        // running untouched on the main display.
        if (getDisplay() != null && getDisplay().getDisplayId() != Display.DEFAULT_DISPLAY) {
            android.widget.FrameLayout holder = new android.widget.FrameLayout(this);
            holder.setBackgroundColor(0xFF0F1117);
            android.widget.TextView tv = new android.widget.TextView(this);
            tv.setText("仪表投屏\n（仪表盘端）");
            tv.setTextColor(0xFF00D4FF);
            tv.setTextSize(22);
            tv.setGravity(android.view.Gravity.CENTER);
            holder.addView(tv, new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
            setContentView(holder);
            return;
        }

        setContentView(R.layout.activity_main);

        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        displayManager = new DisplayEnumerator(this);

        statusText = findViewById(R.id.statusText);
        appList = findViewById(R.id.appList);
        displaySpinner = findViewById(R.id.displaySpinner);
        seekX = findViewById(R.id.seekX);
        seekY = findViewById(R.id.seekY);
        seekW = findViewById(R.id.seekW);
        seekH = findViewById(R.id.seekH);
        seekAlpha = findViewById(R.id.seekAlpha);
        testBtn = findViewById(R.id.testBtn);
        stopBtn = findViewById(R.id.stopBtn);
        Button logBtn = findViewById(R.id.logBtn);

        testBtn.setOnClickListener(v -> onTestClicked());
        stopBtn.setOnClickListener(v -> stopProjection());
        logBtn.setOnClickListener(v -> showLogs());

        SeekBar.OnSeekBarChangeListener sliderListener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) applySliders();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        };
        seekX.setOnSeekBarChangeListener(sliderListener);
        seekY.setOnSeekBarChangeListener(sliderListener);
        seekW.setOnSeekBarChangeListener(sliderListener);
        seekH.setOnSeekBarChangeListener(sliderListener);
        seekAlpha.setOnSeekBarChangeListener(sliderListener);

        ensureOverlayPermission();
        pickTargetDisplay();
        loadAppList();
        } catch (Throwable t) {
            AppLog.e("MainActivity", "onCreate 初始化异常", t);
            Toast.makeText(this, "初始化异常: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showLogs() {
        try {
            java.io.File[] files = AppLog.listLogs();
            StringBuilder sb = new StringBuilder("日志目录: /sdcard/Download/clusterprojector/\n\n");
            if (files.length == 0) {
                sb.append("暂无日志文件");
            } else {
                for (java.io.File f : files) {
                    sb.append(f.getName()).append("  (").append(f.length() / 1024).append("KB)\n");
                }
            }
            sb.append("\n点击确定后复制到剪贴板");
            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
            b.setTitle("日志文件").setMessage(sb.toString())
                    .setPositiveButton("确定", null)
                    .setNeutralButton("打开文件管理器", (d, w) -> {
                        Intent i = new Intent(android.content.Intent.ACTION_VIEW);
                        i.setData(android.net.Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2Fclusterprojector"));
                        try { startActivity(i); } catch (Exception ignored) {}
                    })
                    .show();
        } catch (Throwable t) {
            AppLog.e("MainActivity", "showLogs 异常", t);
        }
    }

    // -------------------------------------------------- app list

    private void loadAppList() {
        try {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> installed = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        List<AppEntry> entries = new ArrayList<>();
        for (ApplicationInfo info : installed) {
            if (info.packageName.equals(getPackageName())) continue;
            Intent launch = pm.getLaunchIntentForPackage(info.packageName);
            if (launch == null) continue;
            entries.add(new AppEntry(
                    info.packageName,
                    pm.getApplicationLabel(info).toString(),
                    pm.getApplicationIcon(info)));
        }
        appList.setAdapter(new AppListAdapter(entries));
        AppLog.i("MainActivity", "已加载应用列表: " + entries.size() + " 个");
        appList.setOnItemClickListener((parent, view, pos, id) -> {
            AppEntry entry = (AppEntry) parent.getItemAtPosition(pos);
            pendingPackage = entry.packageName;
            AppLog.i("MainActivity", "选择应用投屏: " + entry.packageName);
            requestProjection();
        });
        } catch (Throwable t) {
            AppLog.e("MainActivity", "loadAppList 异常", t);
        }
    }

    private static class AppEntry {
        String packageName;
        String label;
        Drawable icon;
        AppEntry(String p, String l, Drawable i) { packageName = p; label = l; icon = i; }
    }

    private class AppListAdapter extends BaseAdapter {
        private final List<AppEntry> items;
        AppListAdapter(List<AppEntry> items) { this.items = items; }
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int pos) { return items.get(pos); }
        @Override public long getItemId(int pos) { return pos; }
        @Override public View getView(int pos, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.app_list_item, parent, false);
            }
            AppEntry e = items.get(pos);
            ((ImageView) convertView.findViewById(R.id.appIcon)).setImageDrawable(e.icon);
            ((TextView) convertView.findViewById(R.id.appName)).setText(e.label);
            ((TextView) convertView.findViewById(R.id.appPkg)).setText(e.packageName);
            return convertView;
        }
    }

    // -------------------------------------------------- display

    private void pickTargetDisplay() {
        List<DisplayTarget> displays = displayManager.listDisplays();
        android.widget.ArrayAdapter<DisplayTarget> adapter =
                new android.widget.ArrayAdapter<>(this,
                        android.R.layout.simple_spinner_dropdown_item, displays);
        displaySpinner.setAdapter(adapter);
        // Pre-select a non-default display.
        for (int i = 0; i < displays.size(); i++) {
            if (!displays.get(i).isDefault()) {
                displaySpinner.setSelection(i);
                break;
            }
        }
        displaySpinner.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,
                                                   View v, int pos, long id) {
                targetDisplay = displays.get(pos);
                DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
                targetDisplayObj = null;
                if (dm != null) {
                    for (Display d : dm.getDisplays()) {
                        if (d.getDisplayId() == targetDisplay.getDisplayId()) {
                            targetDisplayObj = d;
                            break;
                        }
                    }
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
    }

    // -------------------------------------------------- projection flow

    private void ensureOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivityForResult(new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())), REQ_OVERLAY);
        }
    }

    private void requestProjection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show();
            ensureOverlayPermission();
            return;
        }
        if (targetDisplay == null || targetDisplayObj == null) {
            Toast.makeText(this, "未找到可用显示设备", Toast.LENGTH_LONG).show();
            return;
        }
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_PROJECTION);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY) return;
        if (requestCode == REQ_PROJECTION) {
            if (resultCode != RESULT_OK || data == null) {
                setStatus("录屏权限被拒绝");
                return;
            }
            startProjection(resultCode, data);
        }
    }

    private void startProjection(int resultCode, Intent data) {
        try {
        AppLog.i("MainActivity", "startProjection, target=" + targetDisplay);
        Intent svc = new Intent(this, ScreenCastService.class);
        svc.setAction(ScreenCastService.ACTION_START);
        svc.putExtra(ScreenCastService.EXTRA_RESULT_CODE, resultCode);
        svc.putExtra(ScreenCastService.EXTRA_RESULT_DATA, data);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc);
        else startService(svc);

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        controller = new CastController(this, targetDisplay, targetDisplayObj);
        controller.setListener(new CastController.Listener() {
            @Override public void onAppLaunched(String pkg, boolean ok) {
                setStatus(ok ? "已启动: " + pkg : "启动失败: " + pkg);
            }
            @Override public void onBoundsChanged(WindowGeometry b) {}
        });

        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            if (ScreenCastService.getProjection() != null) {
                controller.start(ScreenCastService.getProjection(), metrics);
                projecting = true;
                applySliders();
                if (pendingPackage != null) {
                    controller.launchApp(pendingPackage);
                    pendingPackage = null;
                }
                setStatus("投屏中 → " + targetDisplay.getName());
            } else {
                setStatus("服务未就绪，请重试");
                AppLog.w("MainActivity", "ScreenCastService.getProjection() 返回 null");
            }
        }, 300);
        } catch (Throwable t) {
            AppLog.e("MainActivity", "startProjection 异常", t);
            Toast.makeText(this, "启动投屏异常: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void onTestClicked() {
        if (!projecting || controller == null) {
            Toast.makeText(this, "请先选择一个应用开始投屏", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean ok = controller.launchTestScreen(TestCastActivity.class);
        setStatus(ok ? "测试画面已发送到仪表盘" : "测试画面发送失败");
    }

    private void stopProjection() {
        if (controller != null) { controller.stop(); controller = null; }
        projecting = false;
        startService(new Intent(this, ScreenCastService.class)
                .setAction(ScreenCastService.ACTION_STOP));
        setStatus("已停止");
    }

    // -------------------------------------------------- sliders → bounds

    private void applySliders() {
        if (controller == null || targetDisplay == null) return;
        int dw = targetDisplay.getWidth();
        int dh = targetDisplay.getHeight();
        if (dw <= 0 || dh <= 0) return;

        // Size: 10%..90% of display.
        int w = Math.max(80, dw * seekW.getProgress() / 100);
        int h = Math.max(60, dh * seekH.getProgress() / 100);
        // Position: 0..(display - window).
        int x = (dw - w) * seekX.getProgress() / 100;
        int y = (dh - h) * seekY.getProgress() / 100;

        controller.setBounds(new WindowGeometry(x, y, w, h));
        controller.setAlpha(seekAlpha.getProgress() / 100f);
    }

    private void setStatus(String s) { statusText.setText(s); }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (controller != null) { controller.stop(); controller = null; }
    }
}
