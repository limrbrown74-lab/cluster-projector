package org.clusterprojector.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;

/**
 * Foreground service that owns the {@link MediaProjection} grant so the mirror
 * session survives the Activity being backgrounded.
 *
 * <p>Started by {@link MainActivity} after the user grants screen-capture consent.
 *
 * <p>本模块由 AI 辅助原创开发。
 */
public class ScreenCastService extends Service {

    public static final String ACTION_START = "org.clusterprojector.app.START";
    public static final String ACTION_STOP = "org.clusterprojector.app.STOP";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    private static final String CHANNEL_ID = "cluster_projection";
    private static final int NOTIFICATION_ID = 42;

    private static MediaProjection mediaProjection;

    /** @return the projection held by the running service, or null. */
    public static MediaProjection getProjection() {
        return mediaProjection;
    }

    public static boolean isRunning() {
        return mediaProjection != null;
    }

    @Override public void onCreate() {
        super.onCreate();
        AppLog.i("ScreenCastService", "onCreate");
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            AppLog.i("ScreenCastService", "ACTION_STOP");
            stopProjection();
            stopSelf();
            return START_NOT_STICKY;
        }

        // Android 14+ 强制要求：先启动 mediaProjection 类型的前台服务，
        // 再调用 getMediaProjection()，否则抛 SecurityException。
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
        AppLog.i("ScreenCastService", "startForeground OK");

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (resultData != null) {
            MediaProjectionManager mgr =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mgr != null) {
                mediaProjection = mgr.getMediaProjection(resultCode, resultData);
                AppLog.i("ScreenCastService", "getMediaProjection OK: " + (mediaProjection != null));
            }
        }
        return START_NOT_STICKY;
        } catch (Throwable t) {
            AppLog.e("ScreenCastService", "onStartCommand 异常", t);
            stopSelf();
            return START_NOT_STICKY;
        }
    }

    private void stopProjection() {
        if (mediaProjection != null) {
            try { mediaProjection.stop(); } catch (Throwable ignored) {}
            mediaProjection = null;
        }
    }

    @Override public void onDestroy() {
        stopProjection();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Cluster Projection", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b
                .setContentTitle("Cluster Projector")
                .setContentText("Mirroring to cluster display")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build();
    }
}
