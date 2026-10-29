package org.clusterprojector.app;

import android.app.Application;

import org.clusterprojector.display.ClusterLog;
import org.clusterprojector.display.ClusterProjectionSwitch;

/** 应用级初始化：尽早配置诊断日志目录。 */
public class ClusterApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        AppLog.init(this);
        org.clusterprojector.display.ClusterLog.init(this);

        // 投屏中 App 进程被系统杀死时，物理切屏状态可能残留（仪表停留在 Android 显示源）。
        // 每次进程启动都检查一次：没有活跃投屏会话却处于已切屏状态，就恢复原厂仪表。
        // 异步执行，避免阻塞应用启动。
        new Thread(() -> {
            try {
                if (ClusterProjectionSwitch.isActive()) {
                    ClusterLog.w("ClusterApp",
                            "residual cluster switch detected, restoring stock QNX dashboard");
                    ClusterProjectionSwitch.disable();
                    ClusterLog.i("ClusterApp", "stock dashboard restored");
                }
            } catch (Throwable t) {
                ClusterLog.w("ClusterApp", "restore stock dashboard failed", t);
            }
        }, "cluster-restore-stock").start();
    }
}
