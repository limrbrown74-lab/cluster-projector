package org.clusterprojector.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.clusterprojector.display.CastController;
import org.clusterprojector.display.ClusterPresentationProbe;
import org.clusterprojector.display.DisplayEnumerator;
import org.clusterprojector.display.DisplayTarget;
import org.clusterprojector.display.WindowGeometry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_OVERLAY = 1001;
    private static final int REQ_PROJECTION = 1002;
    private static final int REQ_STARTUP_PERMISSIONS = 1003;
    private static final int REQ_PROJECTION_PERMISSIONS = 1004;

    private MediaProjectionManager projectionManager;
    private DisplayEnumerator displayEnumerator;

    private TextView statusText;
    private TextView permissionStatusText;
    private TextView permissionSummaryText;
    private ListView appList;
    private CheckBox showSystemAppsCheck;
    private Spinner displaySpinner;
    private View controlsContent;
    private SeekBar seekX, seekY, seekW, seekH, seekAlpha;
    private Button testBtn, stopBtn, permissionButton;

    private CastController controller;
    private DisplayTarget targetDisplay;
    private Display targetDisplayObj;
    private String pendingPackage;
    private boolean projecting;
    private boolean projectionPermissionPending;
    private boolean mediaProjectionGranted;
    private boolean mediaProjectionRejected;
    private boolean startupPermissionPending;
    private boolean startupPermissionsRequested;
    private boolean startTestAfterProjection;
    private boolean projectionAfterOverlay;
    private boolean displayListInitialized;
    private int launchableAppCount = -1;
    private int presentationProbePassedDisplayId = Integer.MIN_VALUE;
    private ClusterPresentationProbe presentationProbe;
    private int systemAppCount;
    private final List<AppEntry> allLaunchableApps = new ArrayList<>();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashHandler.install();
        logRuntimeVersion();

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
            permissionStatusText = findViewById(R.id.permissionStatusText);
            permissionSummaryText = findViewById(R.id.permissionSummaryText);
            permissionButton = findViewById(R.id.permissionButton);
            appList = findViewById(R.id.appList);
            appList.setEmptyView(findViewById(R.id.emptyAppList));
            showSystemAppsCheck = findViewById(R.id.showSystemAppsCheck);
            showSystemAppsCheck.setOnCheckedChangeListener((button, checked) -> applyAppListFilter());
            appList.setOnItemClickListener((parent, view, pos, id) -> {
                AppEntry entry = (AppEntry) parent.getItemAtPosition(pos);
                pendingPackage = entry.packageName;
                startTestAfterProjection = false;
                AppLog.i("MainActivity", "选择应用: " + entry.packageName
                        + " label=" + entry.label + " system=" + entry.systemApp
                        + " targetDisplay=" + targetDisplay + " projecting=" + projecting);
                // 投屏会话已经存在时，即使正在切换/降级，也交给 CastController 排队，
                // 不应该再次弹出 MediaProjection 授权框。
                if (projecting && controller != null && controller.isRunning()) {
                    controller.launchApp(entry.packageName);
                } else {
                    startProjectionFlow();
                }
            });
            displaySpinner = findViewById(R.id.displaySpinner);
            controlsContent = findViewById(R.id.controlsContent);
            findViewById(R.id.controlCard).setOnClickListener(v -> toggleControls());
            seekX = findViewById(R.id.seekX);
            seekY = findViewById(R.id.seekY);
            seekW = findViewById(R.id.seekW);
            seekH = findViewById(R.id.seekH);
            seekAlpha = findViewById(R.id.seekAlpha);
            testBtn = findViewById(R.id.testBtn);
            stopBtn = findViewById(R.id.stopBtn);
            Button logBtn = findViewById(R.id.logBtn);

            permissionButton.setOnClickListener(v -> onPermissionButtonClicked());
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

            pickTargetDisplay();
            displayListInitialized = true;
            loadAppList();
            refreshPermissionStatus();
        } catch (Throwable t) {
            AppLog.e("MainActivity", "onCreate 初始化异常", t);
            Toast.makeText(this, "初始化异常: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }


    private void logRuntimeVersion() {
        String version = "unknown";
        long versionCode = -1;
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            version = info.versionName;
            versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode() : info.versionCode;
        } catch (Throwable t) {
            AppLog.w("MainActivity", "read package version failed: " + t);
        }
        String displayId = "n/a";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && getDisplay() != null) {
            displayId = String.valueOf(getDisplay().getDisplayId());
        }
        AppLog.i("MainActivity", "runtime version=" + version + "(" + versionCode + ")"
                + " sdk=" + Build.VERSION.SDK_INT
                + " displayId=" + displayId
                + " brand=" + Build.BRAND
                + " model=" + Build.MODEL
                + " device=" + Build.DEVICE
                + " product=" + Build.PRODUCT
                + " fingerprint=" + Build.FINGERPRINT);
    }

    private void toggleControls() {
        if (controlsContent == null) return;
        controlsContent.setVisibility(controlsContent.getVisibility() == View.VISIBLE
                ? View.GONE : View.VISIBLE);
    }

    private void showLogs() {
        try {
            java.io.File[] files = AppLog.listLogs();
            StringBuilder sb = new StringBuilder("日志会同时写入：\n")
                    .append(AppLog.getLogDirectoryHint()).append("\n");
            if (files.length == 0) {
                sb.append("\n暂无日志文件");
            } else {
                sb.append("\n发现 ").append(files.length).append(" 个日志/崩溃文件：\n");
                for (java.io.File f : files) {
                    sb.append("• ").append(f.getName())
                            .append("  (").append(Math.max(1, f.length() / 1024)).append("KB)\n");
                }
            }
            sb.append("\n请点击“分享完整日志”，选择 LocalSend/微信等按文件发送；");
            sb.append("\n诊断包扩展名应为 .log，并会额外保存到 Download/clusterprojector/，");
            sb.append("\n方便在银河 NOS 分享面板受限时用 LocalSend 直接选择文件。");
            new android.app.AlertDialog.Builder(this)
                    .setTitle("诊断日志")
                    .setMessage(sb)
                    .setPositiveButton("分享完整日志", (d, w) -> shareDiagnosticLog())
                    .setNeutralButton("文件管理器", (d, w) -> openLogFileManager())
                    .setNegativeButton("关闭", null)
                    .show();
        } catch (Throwable t) {
            AppLog.e("MainActivity", "showLogs 异常", t);
            Toast.makeText(this, "打开日志失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openLogFileManager() {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setData(Uri.parse(
                "content://com.android.externalstorage.documents/document/primary%3ADownload%2Fclusterprojector"));
        try {
            startActivity(i);
            AppLog.i("MainActivity", "open log file manager requested");
        } catch (Throwable t) {
            AppLog.e("MainActivity", "打开文件管理器失败", t);
            Toast.makeText(this, "当前系统没有可打开该目录的文件管理器", Toast.LENGTH_LONG).show();
        }
    }

    private void shareDiagnosticLog() {
        Toast.makeText(this, "正在整理完整诊断日志文件...", Toast.LENGTH_SHORT).show();
        AppLog.i("MainActivity", "export diagnostic bundle requested");
        String runtimeInfo = buildDiagnosticInfo();
        new Thread(() -> {
            try {
                // 先写一条明确的边界日志并等待落盘，确保本次分享文件包含点击分享前的最新状态。
                String boundary = "diagnostic bundle boundary " + System.currentTimeMillis();
                AppLog.i("MainActivity", boundary);
                SystemClock.sleep(120L);

                java.io.File file = AppLog.exportDiagnosticBundle(this, runtimeInfo);
                java.io.File publicFile = AppLog.publishDiagnosticBundle(file);
                AppLog.i("MainActivity", "diagnostic public copy: "
                        + (publicFile == null ? "unavailable" : publicFile.getAbsolutePath()));
                Uri uri = new Uri.Builder()
                        .scheme("content")
                        .authority(getPackageName() + ".logprovider")
                        .appendPath("diagnostic")
                        .appendPath(file.getName())
                        .build();
                verifySharedLogFile(file, uri);

                Intent send = new Intent(Intent.ACTION_SEND);
                // 关键：只发二进制文件附件，不能放 EXTRA_TEXT；否则部分微信/车机分享器只发送文字。
                send.setType("application/octet-stream");
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                send.setClipData(android.content.ClipData.newUri(
                        getContentResolver(), file.getName(), uri));

                Intent chooser = Intent.createChooser(send, "发送完整诊断日志文件");
                chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_ACTIVITY_NEW_TASK);
                AppLog.i("MainActivity", "diagnostic file ready to share: "
                        + file.getAbsolutePath() + " size=" + file.length()
                        + " uri=" + uri + " mime=" + getContentResolver().getType(uri));
                runOnUiThread(() -> {
                    try {
                        startActivity(chooser);
                        String savedHint = publicFile == null ? ""
                                : "\n备用文件位置：Download/clusterprojector/" + publicFile.getName();
                        Toast.makeText(this, "请选择 LocalSend/微信等发送文件，文件名："
                                + file.getName() + savedHint, Toast.LENGTH_LONG).show();
                    } catch (Throwable t) {
                        AppLog.e("MainActivity", "调起系统分享失败", t);
                        Toast.makeText(this, "无法调起分享：" + t.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Throwable t) {
                AppLog.e("MainActivity", "导出诊断日志失败", t);
                runOnUiThread(() -> Toast.makeText(this,
                        "导出诊断日志失败: " + t.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "diagnostic-export").start();
    }

    /** 发送前通过 ContentResolver 反向校验，确保外部应用拿到的是真实文件而不是空 Uri。 */
    private void verifySharedLogFile(java.io.File file, Uri uri) throws Exception {
        if (!file.isFile() || file.length() <= 0) {
            throw new IllegalStateException("诊断文件不存在或为空: " + file);
        }

        String providerType = getContentResolver().getType(uri);
        String providerName = null;
        long providerSize = -1L;
        android.database.Cursor cursor = getContentResolver().query(
                uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME,
                        android.provider.OpenableColumns.SIZE},
                null, null, null);
        try {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (nameIndex >= 0) providerName = cursor.getString(nameIndex);
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) providerSize = cursor.getLong(sizeIndex);
            }
        } finally {
            if (cursor != null) cursor.close();
        }

        byte[] firstBytes = new byte[64];
        int read;
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("Provider 返回空输入流: " + uri);
            read = in.read(firstBytes);
        }
        if (read <= 0) throw new IllegalStateException("无法通过分享 Provider 读取日志内容: " + uri);
        if (!file.getName().equals(providerName)) {
            throw new IllegalStateException("Provider 文件名不匹配: file=" + file.getName()
                    + ", provider=" + providerName);
        }
        if (providerSize != file.length()) {
            throw new IllegalStateException("Provider 文件大小不匹配: file=" + file.length()
                    + ", provider=" + providerSize);
        }
        AppLog.i("MainActivity", "share provider verified: name=" + providerName
                + " size=" + providerSize + " mime=" + providerType
                + " firstByteCount=" + read);
    }

    private String buildDiagnosticInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("selectedDisplay=").append(targetDisplay).append('\n')
                .append("resolvedDisplay=").append(targetDisplayObj == null
                        ? "null" : targetDisplayObj.getDisplayId()).append('\n')
                .append("projecting=").append(projecting).append('\n')
                .append("presentationPreflightPassedDisplay=")
                .append(presentationProbePassedDisplayId == Integer.MIN_VALUE
                        ? "none" : presentationProbePassedDisplayId).append('\n')
                .append("presentationProbeShowing=")
                .append(presentationProbe != null && presentationProbe.isShowing()).append('\n')
                .append("controllerRunning=").append(controller != null && controller.isRunning()).append('\n')
                .append("controllerState=").append(controller == null
                        ? "null" : controller.getDiagnosticState()).append('\n')
                .append("overlayGranted=").append(!(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                        || Settings.canDrawOverlays(this)).append('\n')
                .append("projectionGrantedInUi=").append(mediaProjectionGranted).append('\n')
                .append("projectionRejected=").append(mediaProjectionRejected).append('\n')
                .append("serviceProjectionActive=").append(ScreenCastService.isRunning()).append('\n')
                .append("pendingPackage=").append(pendingPackage).append('\n')
                .append("pendingTest=").append(startTestAfterProjection).append('\n')
                .append("launchableApps=").append(launchableAppCount)
                .append(" system=").append(systemAppCount).append('\n')
                .append("showSystemApps=").append(showSystemAppsCheck != null
                        && showSystemAppsCheck.isChecked()).append('\n')
                .append("missingOptionalPermissions=").append(collectOptionalRuntimePermissions()).append('\n');
        if (displayEnumerator != null) {
            sb.append("visibleDisplays:\n");
            for (DisplayTarget d : displayEnumerator.listDisplays()) {
                sb.append("  - ").append(d).append('\n');
            }
        }
        return sb.toString();
    }

    private void loadAppList() {
        try {
            PackageManager pm = getPackageManager();
            java.util.LinkedHashMap<String, AppEntry> appsByPackage = new java.util.LinkedHashMap<>();

            // targetSdk 28 + QUERY_ALL_PACKAGES 下优先枚举所有已安装/预装应用。
            // 只保留存在 Launch Intent 的包，避免列出 Settings Provider、SystemUI 无界面组件等。
            List<ApplicationInfo> installedApplications = pm.getInstalledApplications(0);
            for (ApplicationInfo appInfo : installedApplications) {
                addLaunchableApp(pm, appsByPackage, appInfo);
            }

            // 再用 Launcher Intent 兜底，合并部分 ROM 对 getInstalledApplications 的隐私裁剪。
            Intent launcherIntent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> launcherActivities = pm.queryIntentActivities(launcherIntent, 0);
            for (ResolveInfo info : launcherActivities) {
                ApplicationInfo appInfo = info.activityInfo != null
                        ? info.activityInfo.applicationInfo : null;
                if (appInfo != null) addLaunchableApp(pm, appsByPackage, appInfo);
            }

            List<AppEntry> entries = new ArrayList<>(appsByPackage.values());
            java.util.Collections.sort(entries, (a, b) -> {
                if (a.systemApp != b.systemApp) return a.systemApp ? -1 : 1;
                return a.label.compareToIgnoreCase(b.label);
            });

            allLaunchableApps.clear();
            allLaunchableApps.addAll(entries);
            systemAppCount = 0;
            for (AppEntry entry : allLaunchableApps) {
                if (entry.systemApp) systemAppCount++;
            }
            applyAppListFilter();
            AppLog.i("MainActivity", "可启动应用: total=" + allLaunchableApps.size()
                    + " system=" + systemAppCount
                    + " thirdParty=" + (allLaunchableApps.size() - systemAppCount));
        } catch (Throwable t) {
            allLaunchableApps.clear();
            systemAppCount = 0;
            launchableAppCount = 0;
            appList.setAdapter(new AppListAdapter(new ArrayList<>()));
            refreshPermissionStatus();
            AppLog.e("MainActivity", "loadAppList 异常", t);
        }
    }

    private void addLaunchableApp(PackageManager pm,
            java.util.Map<String, AppEntry> appsByPackage, ApplicationInfo appInfo) {
        if (appInfo == null) return;
        String packageName = appInfo.packageName;
        if (packageName == null || packageName.equals(getPackageName())
                || appsByPackage.containsKey(packageName)) {
            return;
        }
        // 只列出能够作为前台 Activity 启动的应用；系统私有无界面包不会进入列表。
        if (pm.getLaunchIntentForPackage(packageName) == null) return;

        boolean systemApp = (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                || (appInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        CharSequence label = appInfo.loadLabel(pm);
        Drawable icon = appInfo.loadIcon(pm);
        appsByPackage.put(packageName, new AppEntry(
                packageName,
                label != null && label.length() > 0 ? label.toString() : packageName,
                icon,
                systemApp));
    }

    private void applyAppListFilter() {
        if (appList == null) return;
        boolean showSystemApps = showSystemAppsCheck == null || showSystemAppsCheck.isChecked();
        List<AppEntry> visibleApps = new ArrayList<>();
        for (AppEntry entry : allLaunchableApps) {
            if (showSystemApps || !entry.systemApp) visibleApps.add(entry);
        }
        appList.setAdapter(new AppListAdapter(visibleApps));
        launchableAppCount = allLaunchableApps.size();
        refreshPermissionStatus();
    }

    private static class AppEntry {
        final String packageName;
        final String label;
        final Drawable icon;
        final boolean systemApp;
        AppEntry(String packageName, String label, Drawable icon, boolean systemApp) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
            this.systemApp = systemApp;
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
            ((TextView) convertView.findViewById(R.id.appPkg))
                    .setText((entry.systemApp ? "系统 · " : "三方 · ") + entry.packageName);
            return convertView;
        }
    }

    private void pickTargetDisplay() {
        List<DisplayTarget> displays = displayEnumerator.listDisplays();
        ArrayAdapter<DisplayTarget> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, displays);
        displaySpinner.setAdapter(adapter);

        int preferred = -1;
        if (targetDisplay != null) {
            for (int i = 0; i < displays.size(); i++) {
                if (displays.get(i).getDisplayId() == targetDisplay.getDisplayId()) {
                    preferred = i;
                    break;
                }
            }
        }
        if (preferred < 0) {
            for (int i = 0; i < displays.size(); i++) {
                DisplayTarget item = displays.get(i);
                if (item.getScore() >= 80) {
                    preferred = i;
                    break;
                }
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
                if (targetDisplay.getDisplayId() != presentationProbePassedDisplayId) {
                    presentationProbePassedDisplayId = Integer.MIN_VALUE;
                }
                AppLog.i("MainActivity", "选择 Display: " + targetDisplay
                        + " resolved=" + (targetDisplayObj != null));
                setStatus(targetDisplay.toString());
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    /** 首屏展示完整权限状态；缺失项给出直达设置或重新授权入口。 */
    private void refreshPermissionStatus() {
        if (permissionStatusText == null || permissionButton == null) return;

        boolean overlayGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(this);
        List<String> optionalMissing = collectOptionalRuntimePermissions();
        boolean appListMissing = launchableAppCount == 0;

        StringBuilder sb = new StringBuilder();
        sb.append(overlayGranted ? "✅ 悬浮窗：已授予（仪表窗口必需）"
                : "❌ 悬浮窗：未授予（仪表窗口必需）").append('\n');

        if (mediaProjectionGranted) {
            sb.append("✅ 投屏授权：本次运行已获取\n");
        } else if (mediaProjectionRejected) {
            sb.append("❌ 投屏授权：未完成，点按钮或测试投屏可再次发起系统确认\n");
        } else {
            sb.append("ℹ️ 投屏授权：开始投屏时需要在系统弹窗中确认\n");
        }

        if (optionalMissing.isEmpty()) {
            sb.append("✅ 通知/日志：已就绪（不影响核心投屏）\n");
        } else {
            sb.append("⚠️ 通知/日志：未授予（可选，拒绝不影响投屏）\n");
        }

        if (launchableAppCount < 0) {
            sb.append("⏳ 应用列表：正在加载...\n");
        } else if (appListMissing) {
            sb.append("❌ 应用列表：未获取到可启动应用，请到应用详情允许“获取应用列表”\n");
        } else {
            sb.append("✅ 应用列表：已发现 ").append(launchableAppCount)
                    .append(" 个可启动应用（含 ").append(systemAppCount).append(" 个系统/预装）\n");
        }
        if (isMiuiLikeRom()) {
            sb.append("ℹ️ MIUI/HyperOS：如列表不全，请到应用详情检查“获取应用列表”\n");
        }

        boolean showDetails = !overlayGranted || mediaProjectionRejected || appListMissing
                || !optionalMissing.isEmpty() || launchableAppCount < 0 || isMiuiLikeRom();
        permissionStatusText.setVisibility(showDetails ? View.VISIBLE : View.GONE);
        permissionStatusText.setText(sb.toString().trim());

        String summary;
        int summaryColor;
        if (!overlayGranted) {
            summary = "缺少悬浮窗权限";
            summaryColor = 0xFFFF6B6B;
            permissionButton.setText("去授予悬浮窗权限");
        } else if (mediaProjectionRejected) {
            summary = "投屏授权未完成";
            summaryColor = 0xFFFF6B6B;
            permissionButton.setText("重新发起投屏授权");
        } else if (appListMissing) {
            summary = "应用列表不可用";
            summaryColor = 0xFFFF6B6B;
            permissionButton.setText("打开应用详情，允许应用列表");
        } else if (!optionalMissing.isEmpty()) {
            summary = "通知/日志可选权限未开";
            summaryColor = 0xFFFFC857;
            permissionButton.setText("授予通知/日志可选权限");
        } else if (launchableAppCount < 0) {
            summary = "应用列表加载中";
            summaryColor = 0xFFFFC857;
            permissionButton.setText("重新检查权限");
        } else if (isMiuiLikeRom()) {
            summary = "核心权限就绪";
            summaryColor = 0xFF4ADE80;
            permissionButton.setText("打开应用详情检查手机权限");
        } else {
            summary = "核心权限就绪";
            summaryColor = 0xFF4ADE80;
            permissionButton.setText("重新检查权限");
        }
        permissionSummaryText.setText(summary);
        permissionSummaryText.setTextColor(summaryColor);
    }

    private void onPermissionButtonClicked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            ensureOverlayPermission();
            return;
        }

        if (mediaProjectionRejected) {
            // MediaProjection 没有普通应用可直接进入的设置页，只能再次发起系统授权弹窗。
            pendingPackage = null;
            startTestAfterProjection = true;
            startProjectionFlow();
            return;
        }

        if (launchableAppCount == 0) {
            openApplicationDetails();
            return;
        }

        List<String> missing = collectOptionalRuntimePermissions();
        if (!missing.isEmpty()) {
            if (startupPermissionsRequested && shouldOpenSettingsForRuntimePermissions(missing)) {
                openApplicationDetails();
                return;
            }
            startupPermissionPending = true;
            requestPermissions(missing.toArray(new String[0]), REQ_STARTUP_PERMISSIONS);
            return;
        }

        if (isMiuiLikeRom()) {
            openApplicationDetails();
            return;
        }
        Toast.makeText(this, "核心权限已就绪，可以选择测试画面或应用开始投屏", Toast.LENGTH_SHORT).show();
    }

    private boolean isMiuiLikeRom() {
        String fingerprint = (Build.BRAND + " " + Build.MANUFACTURER + " " + Build.DISPLAY)
                .toLowerCase(Locale.ROOT);
        return fingerprint.contains("xiaomi") || fingerprint.contains("redmi")
                || fingerprint.contains("miui") || fingerprint.contains("hyperos");
    }

    private List<String> collectOptionalRuntimePermissions() {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2
                && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return missing;
    }

    private boolean shouldOpenSettingsForRuntimePermissions(List<String> permissions) {
        for (String permission : permissions) {
            // 首次请求前 shouldShow=false 不代表永久拒绝；这里只在本界面已经发起过请求后判断。
            if (!shouldShowRequestPermissionRationale(permission)) return true;
        }
        return false;
    }

    private void openApplicationDetails() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            Toast.makeText(this, "请在应用详情中检查权限，尤其是“获取应用列表”", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            AppLog.e("MainActivity", "打开应用详情失败", t);
            Toast.makeText(this, "无法打开应用详情：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        for (int i = 0; i < permissions.length && i < grantResults.length; i++) {
            boolean granted = grantResults[i] == PackageManager.PERMISSION_GRANTED;
            AppLog.i("MainActivity", "权限 " + permissions[i] + "="
                    + (granted ? "GRANTED" : "DENIED"));
        }

        if (requestCode == REQ_STARTUP_PERMISSIONS && startupPermissionPending) {
            startupPermissionPending = false;
            startupPermissionsRequested = true;
            refreshPermissionStatus();
            return;
        }
        if (requestCode == REQ_PROJECTION_PERMISSIONS && projectionPermissionPending) {
            projectionPermissionPending = false;
            // 通知/日志权限被拒绝时仍允许投屏，只是通知或公共日志可能不可用。
            launchProjectionIntent();
        }
    }

    private void ensureOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请授予“显示在其他应用上层”权限，否则无法在仪表盘创建窗口",
                    Toast.LENGTH_LONG).show();
            startActivityForResult(new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())), REQ_OVERLAY);
        }
    }

    private boolean prepareProjectionPermissions() {
        List<String> missing = collectOptionalRuntimePermissions();
        if (missing.isEmpty()) return true;
        projectionPermissionPending = true;
        AppLog.i("MainActivity", "投屏前申请权限: " + missing);
        requestPermissions(missing.toArray(new String[0]), REQ_PROJECTION_PERMISSIONS);
        return false;
    }

    private void startProjectionFlow() {
        targetDisplayObj = displayEnumerator.resolveDisplay(targetDisplay);
        if (targetDisplay == null || targetDisplay.isDefault() || targetDisplayObj == null) {
            clearPendingProjectionAction();
            Toast.makeText(this, "未选择可用的仪表 Display（FX11 应为 1920x532）",
                    Toast.LENGTH_LONG).show();
            AppLog.e("MainActivity", "Display 不可见或不是仪表: " + targetDisplay);
            return;
        }
        if (presentationProbePassedDisplayId == targetDisplayObj.getDisplayId()) {
            AppLog.i("MainActivity", "Presentation preflight already passed: display="
                    + targetDisplayObj.getDisplayId());
            requestProjection();
            return;
        }
        showPresentationPreflight();
    }

    private void showPresentationPreflight() {
        dismissPresentationProbe("start new preflight");
        if (targetDisplayObj == null) {
            setStatus("未选择可用的仪表 Display");
            return;
        }
        try {
            AppLog.i("MainActivity", "Presentation preflight begin: target=" + targetDisplay
                    + " display=" + targetDisplayObj.getDisplayId());
            presentationProbe = new ClusterPresentationProbe(this, targetDisplayObj);
            presentationProbe.show();
            setStatus("请看仪表盘：正在显示全屏副屏预检画面");

            new AlertDialog.Builder(this)
                    .setTitle("仪表盘预检")
                    .setMessage("请保持车辆安全停放，观察仪表盘是否出现全屏闪烁色块和“仪表副屏预检”文字。\n\n"
                            + "看到了：说明官方副屏窗口可用，再继续录屏授权；\n"
                            + "没看到：说明仪表合成层拦截了标准 Presentation，不能继续误判为投屏成功。")
                    .setCancelable(false)
                    .setPositiveButton("看到了，继续", (dialog, which) -> {
                        if (targetDisplayObj != null) {
                            presentationProbePassedDisplayId = targetDisplayObj.getDisplayId();
                        }
                        dismissPresentationProbe("user confirmed visible");
                        setStatus("预检通过，正在发起录屏授权…");
                        requestProjection();
                    })
                    .setNegativeButton("没看到", (dialog, which) -> {
                        presentationProbePassedDisplayId = Integer.MIN_VALUE;
                        dismissPresentationProbe("user confirmed invisible");
                        clearPendingProjectionAction();
                        setStatus("预检失败：仪表盘没有显示标准 Presentation");
                        Toast.makeText(this, "仪表盘未显示预检画面，已停止继续投屏，请分享完整日志",
                                Toast.LENGTH_LONG).show();
                        showLogs();
                    })
                    .setNeutralButton("取消", (dialog, which) -> {
                        presentationProbePassedDisplayId = Integer.MIN_VALUE;
                        dismissPresentationProbe("user cancelled preflight");
                        clearPendingProjectionAction();
                        setStatus("已取消仪表预检");
                    })
                    .show();
        } catch (Throwable t) {
            presentationProbePassedDisplayId = Integer.MIN_VALUE;
            dismissPresentationProbe("preflight show failed");
            clearPendingProjectionAction();
            AppLog.e("MainActivity", "Presentation preflight failed", t);
            setStatus("仪表预检被系统拒绝：" + t.getMessage());
            Toast.makeText(this, "仪表预检被系统拒绝：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void dismissPresentationProbe(String reason) {
        if (presentationProbe != null) {
            presentationProbe.dismiss(reason);
            presentationProbe = null;
        }
    }

    private void launchProjectionIntent() {
        if (projectionManager == null) {
            Toast.makeText(this, "MediaProjection 服务不可用", Toast.LENGTH_LONG).show();
            return;
        }
        AppLog.i("MainActivity", "launch system MediaProjection consent");
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_PROJECTION);
    }

    private void requestProjection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            // 从应用点击或测试按钮进来时，保留本次动作；授权返回后继续弹 MediaProjection。
            projectionAfterOverlay = true;
            Toast.makeText(this, "请先授予悬浮窗权限，授权后会继续发起投屏", Toast.LENGTH_LONG).show();
            ensureOverlayPermission();
            return;
        }
        if (targetDisplay == null || targetDisplay.isDefault()) {
            clearPendingProjectionAction();
            Toast.makeText(this, "未选择仪表 Display（FX11 应为 1920x532）", Toast.LENGTH_LONG).show();
            return;
        }
        targetDisplayObj = displayEnumerator.resolveDisplay(targetDisplay);
        if (targetDisplayObj == null) {
            clearPendingProjectionAction();
            Toast.makeText(this, "当前进程看不到 Display " + targetDisplay.getDisplayId()
                    + "，可能被车机多屏策略限制", Toast.LENGTH_LONG).show();
            AppLog.e("MainActivity", "Display 不可见: " + targetDisplay);
            return;
        }
        AppLog.i("MainActivity", "request projection: target=" + targetDisplay
                + " resolved=" + (targetDisplayObj != null) + " pendingPkg=" + pendingPackage
                + " pendingTest=" + startTestAfterProjection);
        if (!prepareProjectionPermissions()) return;
        launchProjectionIntent();
    }

    private void clearPendingProjectionAction() {
        pendingPackage = null;
        startTestAfterProjection = false;
        projectionAfterOverlay = false;
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY) {
            refreshPermissionStatus();
            if (projectionAfterOverlay
                    && (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this))) {
                projectionAfterOverlay = false;
                requestProjection();
            }
            return;
        }
        if (requestCode == REQ_PROJECTION) {
            AppLog.i("MainActivity", "MediaProjection onActivityResult: resultCode=" + resultCode
                    + " ok=" + RESULT_OK + " data=" + (data != null)
                    + " action=" + (data == null ? null : data.getAction()));
            if (resultCode != RESULT_OK || data == null) {
                clearPendingProjectionAction();
                mediaProjectionGranted = false;
                mediaProjectionRejected = true;
                refreshPermissionStatus();
                setStatus("录屏权限被拒绝，可在权限卡片重新授权");
                return;
            }
            mediaProjectionGranted = true;
            mediaProjectionRejected = false;
            refreshPermissionStatus();
            startProjectionService(resultCode, data);
        }
    }

    private void startProjectionService(int resultCode, Intent data) {
        try {
            setStatus("正在启动投屏服务...");
            AppLog.i("MainActivity", "start projection service: resultCode=" + resultCode
                    + " data=" + (data != null) + " target=" + targetDisplay);
            ScreenCastService.setProjectionListener(new ScreenCastService.ProjectionListener() {
                @Override public void onReady(MediaProjection projection) {
                    runOnUiThread(() -> beginCasting(projection));
                }

                @Override public void onError(String message) {
                    runOnUiThread(() -> {
                        stopProjection("投屏服务失败: " + message);
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                    });
                }

                @Override public void onStopped() {
                    runOnUiThread(() -> stopProjection("系统停止了投屏会话"));
                }
            });

            AppLog.i("MainActivity", "dispatch ACTION_START to ScreenCastService");
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
            AppLog.i("MainActivity", "beginCasting: projection=" + (projection != null)
                    + " previousController=" + (controller != null)
                    + " pendingPkg=" + pendingPackage + " pendingTest=" + startTestAfterProjection);
            if (targetDisplay == null) {
                targetDisplay = displayEnumerator.findRecommendedDisplay();
            }
            targetDisplayObj = displayEnumerator.resolveDisplay(targetDisplay);
            if (targetDisplay == null || targetDisplayObj == null || targetDisplay.isDefault()) {
                setStatus("未找到 FX11 仪表 Display");
                return;
            }

            if (controller != null) controller.stop();
            AppLog.i("MainActivity", "create CastController: target=" + targetDisplay
                    + " display=" + targetDisplayObj.getDisplayId());
            controller = new CastController(this, targetDisplay, targetDisplayObj);
            controller.setListener(new CastController.Listener() {
                @Override public void onVirtualDisplayReady(int mode, int virtualDisplayId) {
                    AppLog.i("MainActivity", "controller ready: mode=" + mode + " vdId="
                            + virtualDisplayId);
                    runOnUiThread(() -> {
                        String host = controller == null ? "UNKNOWN" : controller.getHostName();
                        setStatus(mode == CastController.MODE_MIRROR
                                ? "兼容镜像捕获已启动（" + host + "），正在等待仪表第一帧…"
                                : "直出虚拟屏已就绪（" + host + "）: vd=" + virtualDisplayId);
                    });
                }

                @Override public void onModeChanged(int mode, String reason) {
                    AppLog.i("MainActivity", "controller mode changed: mode=" + mode
                            + " reason=" + reason);
                    runOnUiThread(() -> {
                        String host = controller == null ? "UNKNOWN" : controller.getHostName();
                        applyDefaultSliderLayout(mode);
                        setStatus(mode == CastController.MODE_MIRROR
                                ? "已自动切到兼容镜像：中控会打开应用，仪表等待第一帧（" + host + "）"
                                : "直出模式：" + reason);
                    });
                }

                @Override public void onAppLaunched(String packageName, boolean success, String error) {
                    AppLog.i("MainActivity", "app launch callback: pkg=" + packageName
                            + " success=" + success + " error=" + error);
                    runOnUiThread(() -> {
                        String host = controller == null ? "UNKNOWN" : controller.getHostName();
                        if (!success) {
                            setStatus("启动失败: " + packageName + " / " + error);
                        } else if (TestCastActivity.class.getName().equals(packageName)
                                && controller != null
                                && controller.getMode() == CastController.MODE_DIRECT) {
                            setStatus("测试画面已进入投屏链路（" + host
                                    + "），请看仪表盘；无画面请立即分享日志");
                        } else if (controller != null
                                && controller.getMode() == CastController.MODE_MIRROR) {
                            setStatus("兼容镜像：中控会打开应用，仪表盘等待第一帧（" + host + "）…");
                        } else {
                            setStatus("直出启动请求已被系统接受（" + host + "）：" + packageName
                                    + "；若仪表仍无画面，请分享日志判断是否被重定向");
                        }
                    });
                }

                @Override public void onMirrorFirstFrame() {
                    AppLog.i("MainActivity", "mirror first frame shown on dashboard");
                    runOnUiThread(() -> {
                        String host = controller == null ? "UNKNOWN" : controller.getHostName();
                        setStatus("真实捕获第一帧已输出到仪表盘（" + host + "）");
                    });
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
            AppLog.i("MainActivity", "output host selected: " + controller.getHostName()
                    + " detail=" + controller.getHostDetail());
            AppLog.i("MainActivity", "controller.start returned, projecting=true state="
                    + controller.getDiagnosticState());
            if (controlsContent != null) controlsContent.setVisibility(View.VISIBLE);
            displaySpinner.setEnabled(false);
            applyDefaultSliderLayout(CastController.MODE_DIRECT);
            if (pendingPackage != null) {
                String pkg = pendingPackage;
                pendingPackage = null;
                AppLog.i("MainActivity", "flush pending app after controller start: " + pkg);
                controller.launchApp(pkg);
            } else if (startTestAfterProjection) {
                AppLog.i("MainActivity", "flush pending test screen after controller start");
                controller.launchTestScreen(TestCastActivity.class);
            }
            startTestAfterProjection = false;
            setStatus("仪表 Surface 已创建，等待虚拟屏...");
        } catch (Throwable t) {
            AppLog.e("MainActivity", "beginCasting 异常", t);
            stopProjection(t.getMessage());
            Toast.makeText(this, "投屏失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void onTestClicked() {
        if (!projecting || controller == null) {
            // 测试画面也可以作为第一次投屏入口，无需先点某个第三方 App。
            pendingPackage = null;
            startTestAfterProjection = true;
            startProjectionFlow();
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
        startTestAfterProjection = false;
        mediaProjectionGranted = false;
        mediaProjectionRejected = false;
        displaySpinner.setEnabled(true);
        try {
            startService(new Intent(this, ScreenCastService.class)
                    .setAction(ScreenCastService.ACTION_STOP));
        } catch (Throwable ignored) {
        }
        setStatus(reason == null ? "已停止" : reason);
        refreshPermissionStatus();
    }

    private void applyDefaultSliderLayout(int mode) {
        if (mode == CastController.MODE_MIRROR) {
            // 完整 contain 中控竖屏：高度填满仪表屏，宽度按中控宽高比计算，水平居中。
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
            int sourceWidth = metrics.widthPixels > 0 ? metrics.widthPixels : 1440;
            int sourceHeight = metrics.heightPixels > 0 ? metrics.heightPixels : 1920;
            int mirrorWidth = Math.min(targetDisplay.getWidth(),
                    Math.round(targetDisplay.getHeight() * sourceWidth / (float) sourceHeight));
            seekX.setProgress(50);
            seekY.setProgress(0);
            seekW.setProgress(Math.round(mirrorWidth * 100f / targetDisplay.getWidth()));
            seekH.setProgress(100);
        } else {
            // 诊断版先全屏输出，确认 ecarx 仪表物理合成层接受 Presentation 后再缩小。
            seekX.setProgress(0);
            seekY.setProgress(0);
            seekW.setProgress(100);
            seekH.setProgress(100);
        }
        applySliders();
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
        // 不在这里重建 Spinner：从悬浮窗/录屏授权页返回时，应保留用户手动选择的 Display。
        if (!displayListInitialized && displayEnumerator != null) {
            pickTargetDisplay();
            displayListInitialized = true;
        }
        refreshPermissionStatus();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        dismissPresentationProbe("activity onDestroy");
        if (controller != null) {
            controller.stop();
            controller = null;
        }
        ScreenCastService.setProjectionListener(null);
        // 当前 CastController 仍绑定 Activity 生命周期；退出界面时一并释放投影会话，
        // 避免 Service/MediaProjection 残留。后续若把控制器移入 Service，可移除此处停止逻辑。
        try {
            startService(new Intent(this, ScreenCastService.class)
                    .setAction(ScreenCastService.ACTION_STOP));
        } catch (Throwable ignored) {
        }
    }
}
