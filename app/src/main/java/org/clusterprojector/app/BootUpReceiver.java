package org.clusterprojector.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机自启：车机重启后自动拉起投屏服务进程，避免用户手动打开。
 *
 * <p>实际投屏不会自动开始（需要用户授权录屏），只是把进程拉起来，
 * 配合无障碍服务保持后台存活。
 */
public class BootUpReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            // 拉起主 Activity 进程（不显示 UI，仅保活）
            Intent launch = new Intent(context, MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            // 不主动 startActivity，避免开机弹窗；无障碍服务本身已由系统绑定保活。
        }
    }
}
