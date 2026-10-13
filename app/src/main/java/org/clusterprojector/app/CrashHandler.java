package org.clusterprojector.app;

import android.os.Process;

/**
 * 全局未捕获异常处理器：把崩溃堆栈写入日志文件，再交给默认处理器。
 * 本模块由 AI 辅助原创开发。
 */
public final class CrashHandler implements Thread.UncaughtExceptionHandler {

    private final Thread.UncaughtExceptionHandler defaultHandler;

    private CrashHandler() {
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    public static void install() {
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler());
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        AppLog.e("CRASH", "未捕获异常 in thread=" + t.getName(), e);
        AppLog.writeCrash(e);
        if (defaultHandler != null) {
            defaultHandler.uncaughtException(t, e);
        } else {
            Process.killProcess(Process.myPid());
        }
    }
}
