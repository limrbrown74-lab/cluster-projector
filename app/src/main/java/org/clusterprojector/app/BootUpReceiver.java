package org.clusterprojector.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机后打开控制面板；MediaProjection 授权仍需用户在中控屏确认。 */
public class BootUpReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        try {
            Intent launch = new Intent(context, MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(launch);
            AppLog.i("BootUpReceiver", "开机后打开控制面板");
        } catch (Throwable t) {
            AppLog.e("BootUpReceiver", "开机启动失败", t);
        }
    }
}
