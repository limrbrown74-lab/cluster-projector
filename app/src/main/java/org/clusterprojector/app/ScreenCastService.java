package org.clusterprojector.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Activity;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** 持有 MediaProjection 授权，并把授权/停止事件交回界面。 */
public class ScreenCastService extends Service {

    public static final String ACTION_START = "org.clusterprojector.app.START";
    public static final String ACTION_STOP = "org.clusterprojector.app.STOP";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    private static final String CHANNEL_ID = "cluster_projection";
    private static final int NOTIFICATION_ID = 42;

    private static MediaProjection mediaProjection;
    private static ProjectionListener listener;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface ProjectionListener {
        void onReady(MediaProjection projection);
        void onError(String message);
        void onStopped();
    }

    public static void setProjectionListener(ProjectionListener value) {
        listener = value;
    }

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
                stopProjection(false);
                stopSelf();
                return START_NOT_STICKY;
            }

            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            if (resultCode != Activity.RESULT_OK || resultData == null) {
                notifyError("MediaProjection 授权数据无效");
                stopSelf();
                return START_NOT_STICKY;
            }

            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (manager == null) {
                notifyError("系统 MediaProjection 服务不可用");
                stopSelf();
                return START_NOT_STICKY;
            }

            stopProjection(false);

            Notification notification = buildNotification();
            MediaProjection newProjection = null;
            try {
                MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
                    @Override public void onStop() {
                        AppLog.w("ScreenCastService", "MediaProjection onStop");
                        stopProjection(true);
                        stopSelf();
                    }
                };

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // Android 14+ 要求先取得 MediaProjection 并注册回调，再启动对应类型的前台服务。
                    newProjection = manager.getMediaProjection(resultCode, resultData);
                    if (newProjection == null) {
                        throw new IllegalStateException("getMediaProjection 返回 null");
                    }
                    newProjection.registerCallback(projectionCallback, mainHandler);
                    startForeground(NOTIFICATION_ID, notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
                } else {
                    // FX11 / Android 11 保持“先前台服务、再获取投影”的兼容顺序。
                    startForeground(NOTIFICATION_ID, notification);
                    AppLog.i("ScreenCastService", "startForeground OK");
                    newProjection = manager.getMediaProjection(resultCode, resultData);
                    if (newProjection == null) {
                        throw new IllegalStateException("getMediaProjection 返回 null");
                    }
                    newProjection.registerCallback(projectionCallback, mainHandler);
                }

                mediaProjection = newProjection;
                AppLog.i("ScreenCastService", "getMediaProjection OK");
                if (listener != null) listener.onReady(mediaProjection);
                return START_NOT_STICKY;
            } catch (Throwable projectionError) {
                if (newProjection != null) {
                    // stop() 会释放该投影会话；具体 callback 实例由系统随会话一起解除。
                    try {
                        newProjection.stop();
                    } catch (Throwable ignored) {
                    }
                }
                throw projectionError;
            }
        } catch (Throwable t) {
            AppLog.e("ScreenCastService", "onStartCommand 异常", t);
            notifyError(t.getMessage() == null ? t.toString() : t.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }
    }

    private void notifyError(String message) {
        AppLog.e("ScreenCastService", message);
        mainHandler.post(() -> {
            if (listener != null) listener.onError(message);
        });
    }

    private void stopProjection(boolean alreadyStoppedBySystem) {
        MediaProjection projection = mediaProjection;
        mediaProjection = null;
        if (projection != null && !alreadyStoppedBySystem) {
            try {
                projection.stop();
            } catch (Throwable ignored) {
            }
        }
        if (alreadyStoppedBySystem && listener != null) {
            mainHandler.post(() -> {
                if (listener != null) listener.onStopped();
            });
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    @Override public void onDestroy() {
        if (mediaProjection != null) stopProjection(false);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Cluster Projection", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("FX11 仪表投屏")
                .setContentText("正在向仪表盘输出画面")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build();
    }
}
