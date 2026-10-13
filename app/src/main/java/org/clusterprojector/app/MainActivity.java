package org.clusterprojector.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.clusterprojector.display.CastController;
import org.clusterprojector.display.DisplayEnumerator;
import org.clusterprojector.display.DisplayTarget;
import org.clusterprojector.display.WindowGeometry;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_OVERLAY = 1001;
    private static final int REQ_PROJECTION = 1002;

    private MediaProjectionManager projectionManager;
    private DisplayEnumerator displayEnumerator;

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
        CrashHandler.install();
        AppLog.i("MainActivity", "onCreate, sdk=" + Build.VERSION.SDK_INT
                + " displayId=" + (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                        ? String.valueOf(getDisplay().getDisplayId()) : "n/a"));

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    && getDisplay() != null
                    && getDisplay().getDisplayId() != Display.DEFAULT_DISPLAY) {
                android.widget.FrameLayout holder = new android.widget.FrameLayout(this);
                holder.setBackgroundColor(0xFF0F1117);
                TextView tv = new TextView(this);
                tv.setText("FX11 仪表投屏端");
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
            displayEnumerator = new DisplayEnumerator(this);

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
            stopBtn.setOnClickListener(v -> stopProjection("手动停止"));
            logBtn.setOnClickListener(v -> showLogs());

            SeekBar.OnSeekBarChangeListener sliderListener = new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) applySliders();
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
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
            sb.append("\n点击确定后关闭");
            new android.app.AlertDialog.Builder(this)
                    .setTitle("日志文件")
                    .setMessage(sb)
                    .setPositiveButton("确定", null)
                    .setNeutralButton("打开文件管理器", (d, w) -> {
                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setData(Uri.parse(
                                "content://com.android.externalstorage.documents/document/primary%3ADownload%2Fclusterprojector"));
                        try { startActivity(i); } catch (Exception ignored) {}
                    })
                    .show();
        } catch (Throwable t) {
            AppLog.e("MainActivity", "showLogs 异常", t);
        }
    }

    private void loadAppList() {
        try {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> installed = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            List<AppEntry> entries = new ArrayList<>();
            for (ApplicationInfo info : installed) {
                if (info.packageName.equals(getPackageName())) continue;
                if (pm.getLaunchIntentForPackage(info.packageName) == null) continue;
                entries.add(new AppEntry(
                        info.packageName,
                        pm.getApplicationLabel(info).toString(),
                        pm.getApplicationIcon(info)));
            }
            java.util.Collections.sort(entries, (a, b) -> a.label.compareToIgnoreCase(b.label));
            appList.setAdapter(new AppListAdapter(entries));
            AppLog.i("MainActivity", "已加载应用列表: " + entries.size());
            appList.setOnItemClickListener((parent, view, pos, id) -> {
                AppEntry entry = (AppEntry) parent.getItemAtPosition(pos);
                pendingPackage = entry.packageName;
                AppLog.i("MainActivity", "选择应用: " + entry.packageName);
                if (projecting && controller != null && controller.isVirtualReady()) {
                    controller.launchApp(entry.packageName);
                } else {
                    requestProjection();
                }
            });
        } catch (Throwable t) {
            AppLog.e("MainActivity", "loadAppList 异常", t);
        }
    }

    private static class AppEntry {
        final String packageName;
        final String label;
        final Drawable icon;
        AppEntry(String packageName, String label, Drawable icon) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
        }
    }

    private class AppListAdapter extends BaseAdapter {
        private final List<AppEntry> items;
        AppListAdapter(List<AppEntry> items) { this.items = items; }
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.app_list_item, parent, false);
            }
            AppEntry entry = items.get(position);
            ((ImageView) convertView.findViewById(R.id.appIcon)).setImageDrawable(entry.icon);
            ((TextView) convertView.findViewById(R.id.appName)).setText(entry.label);
            ((TextView) convertView.findViewById(R.id.appPkg)).setText(entry.packageName);
            return convertView;
        }
    }

    private void pickTargetDisplay() {
        List<DisplayTarget> displays = displayEnumerator.listDisplays();
        ArrayAdapter<DisplayTarget> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, displays);
        displaySpinner.setAdapter(adapter);

        int preferred = -1;
        for (int i = 0; i < displays.size(); i++) {
            DisplayTarget item = displays.get(i);
            if (item.getScore() >= 80) {
                preferred = i;
                break;
            }
        }
        if (preferred < 0) {
            for (int i = 0; i < displays.size(); i++) {
                if (!displays.get(i).isDefault()) {
                    preferred = i;
                    break;
                }
            }
        }
        if (preferred >= 0) displaySpinner.setSelection(preferred);

        displaySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                targetDisplay = displays.get(pos);
                targetDisplayObj = displayEnumerator.resolveDisplay(targetDisplay);
                AppLog.i("MainActivity", "选择 Display: " + targetDisplay
                        + " resolved=" + (targetDisplayObj != null));
                setStatus(targetDisplay.toString());
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

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
        if (targetDisplay == null || targetDisplay.isDefault()) {
            Toast.makeText(this, "未选择仪表 Display（FX11 应为 1920x532）", Toast.LENGTH_LONG).show();
            return;
        }
        targetDisplayObj = displayEnumerator.resolveDisplay(targetDisplay);
        if (targetDisplayObj == null) {
            Toast.makeText(this, "当前进程看不到 Display " + targetDisplay.getDisplayId()
                    + "，可能被车机多屏策略限制", Toast.LENGTH_LONG).show();
            AppLog.e("MainActivity", "Display 不可见: " + targetDisplay);
            return;
        }
        if (projectionManager == null) {
            Toast.makeText(this, "MediaProjection 服务不可用", Toast.LENGTH_LONG).show();
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
            startProjectionService(resultCode, data);
        }
    }

    private void startProjectionService(int resultCode, Intent data) {
        try {
            setStatus("正在启动投屏服务...");
            ScreenCastService.setProjectionListener(new ScreenCastService.ProjectionListener() {
                @Override public void onReady(MediaProjection projection) {
                    runOnUiThread(() -> beginCasting(projection));
                }

                @Override public void onError(String message) {
                    runOnUiThread(() -> {
                        projecting = false;
                        setStatus("投屏服务失败: " + message);
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                    });
                }

                @Override public void onStopped() {
                    runOnUiThread(() -> stopProjection("系统停止了投屏会话"));
                }
            });

            Intent service = new Intent(this, ScreenCastService.class);
            service.setAction(ScreenCastService.ACTION_START);
            service.putExtra(ScreenCastService.EXTRA_RESULT_CODE, resultCode);
            service.putExtra(ScreenCastService.EXTRA_RESULT_DATA, data);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service);
            } else {
                startService(service);
            }
        } catch (Throwable t) {
            AppLog.e("MainActivity", "startProjectionService 异常", t);
            Toast.makeText(this, "启动服务异常: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void beginCasting(MediaProjection projection) {
        try {
            if (targetDisplay == null) {
                targetDisplay = displayEnumerator.findRecommendedDisplay();
            }
            targetDisplayObj = displayEnumerator.resolveDisplay(targetDisplay);
            if (targetDisplay == null || targetDisplayObj == null || targetDisplay.isDefault()) {
                setStatus("未找到 FX11 仪表 Display");
                return;
            }

            if (controller != null) controller.stop();
            controller = new CastController(this, targetDisplay, targetDisplayObj);
            controller.setListener(new CastController.Listener() {
                @Override public void onVirtualDisplayReady(int virtualDisplayId) {
                    runOnUiThread(() -> setStatus("仪表虚拟屏已就绪: vd=" + virtualDisplayId
                            + "，目标 Display " + targetDisplay.getDisplayId()));
                }

                @Override public void onAppLaunched(String packageName, boolean success, String error) {
                    runOnUiThread(() -> setStatus(success
                            ? "已输出: " + packageName
                            : "启动失败: " + packageName + " / " + error));
                }

                @Override public void onError(String message, Throwable cause) {
                    runOnUiThread(() -> {
                        AppLog.e("MainActivity", message, cause);
                        stopProjection(message);
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                    });
                }
            });

            controller.start(projection);
            projecting = true;
            applySliders();
            if (pendingPackage != null) {
                String pkg = pendingPackage;
                pendingPackage = null;
                controller.launchApp(pkg);
            }
            setStatus("仪表 Surface 已创建，等待虚拟屏...");
        } catch (Throwable t) {
            AppLog.e("MainActivity", "beginCasting 异常", t);
            stopProjection(t.getMessage());
            Toast.makeText(this, "投屏失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void onTestClicked() {
        if (!projecting || controller == null) {
            Toast.makeText(this, "请先选择一个应用开始投屏", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean queued = controller.launchTestScreen(TestCastActivity.class);
        setStatus(queued ? "测试画面已排队/发送" : "测试画面发送失败");
    }

    private void stopProjection(String reason) {
        if (controller != null) {
            controller.stop();
            controller = null;
        }
        projecting = false;
        try {
            startService(new Intent(this, ScreenCastService.class)
                    .setAction(ScreenCastService.ACTION_STOP));
        } catch (Throwable ignored) {
        }
        setStatus(reason == null ? "已停止" : reason);
    }

    private void applySliders() {
        if (controller == null || targetDisplay == null) return;
        int displayWidth = targetDisplay.getWidth();
        int displayHeight = targetDisplay.getHeight();
        if (displayWidth <= 0 || displayHeight <= 0) return;

        int width = Math.max(80, displayWidth * seekW.getProgress() / 100);
        int height = Math.max(60, displayHeight * seekH.getProgress() / 100);
        int x = (displayWidth - width) * seekX.getProgress() / 100;
        int y = (displayHeight - height) * seekY.getProgress() / 100;

        controller.setBounds(new WindowGeometry(x, y, width, height));
        controller.setAlpha(seekAlpha.getProgress() / 100f);
    }

    private void setStatus(String text) {
        if (statusText != null) statusText.setText(text);
    }

    @Override protected void onResume() {
        super.onResume();
        if (displayEnumerator != null) pickTargetDisplay();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (controller != null) {
            controller.stop();
            controller = null;
        }
        ScreenCastService.setProjectionListener(null);
    }
}
