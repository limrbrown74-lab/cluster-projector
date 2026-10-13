package org.clusterprojector.app;

import android.app.Application;

/** 应用级初始化：尽早配置诊断日志目录。 */
public class ClusterApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        AppLog.init(this);
        org.clusterprojector.display.ClusterLog.init(this);
    }
}
