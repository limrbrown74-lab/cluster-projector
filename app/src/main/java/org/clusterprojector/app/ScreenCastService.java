package org.clusterprojector.app;

import android.app.Activity;
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
    private MediaProjection.Callback activeCallback;

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
        AppLog.i("ScreenCastService", "onCreate sdk=" + Build.VERSION.SDK_INT);
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "null" : intent.getAction();
        AppLog.i("ScreenCastService", "onStartCommand action=" + action
                + " startId=" + startId + " hasExistingProjection=" + (mediaProjection != null));
        try {
            if (intent == null) {
                AppLog.w("ScreenCastService", "service restarted with null intent; do not sticky restart");
                return START_NOT_STICKY;
            }
            if (ACTION_STOP.equals(action)) {
                stopProjection("ACTION_STOP", false);
                stopSelf();
                return START_NOT_STICKY;
            }

            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            AppLog.i("ScreenCastService", "projection result: resultCode=" + resultCode
                    + " expectedOk=" + Activity.RESULT_OK + " data=" + (resultData != null)
                    + " action=" + (resultData == null ? null : resultData.getAction())
                    + " component=" + (resultData == null ? null : resultData.getComponent()));
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

            stopProjection("replace old projection before new start", false);

            Notification notification = buildNotification();
            MediaProjection newProjection = null;
            try {
                activeCallback = null;
                MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
                    @Override public void onStop() {
                        AppLog.w("ScreenCastService", "MediaProjection onStop from system/user");
                        stopProjection("system projection callback", true);
                        stopSelf();
                    }
                };

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    AppLog.i("ScreenCastService", "Android 14+ branch: projection first, foreground second");
                    newProjection = manager.getMediaProjection(resultCode, resultData);
                    if (newProjection == null) {
                        throw new IllegalStateException("getMediaProjection 返回 null");
                    }
                    newProjection.registerCallback(projectionCallback, mainHandler);
                    AppLog.i("ScreenCastService", "startForeground begin type=mediaProjection");
                    startForeground(NOTIFICATION_ID, notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
                    AppLog.i("ScreenCastService", "startForeground OK");
                } else {
                    // FX11 / Android 11 保持“先前台服务、再获取投影”的兼容顺序。
                    AppLog.i("ScreenCastService", "legacy branch: foreground first, projection second");
                    AppLog.i("ScreenCastService", "startForeground begin");
                    startForeground(NOTIFICATION_ID, notification);
                    AppLog.i("ScreenCastService", "startForeground OK");
                    newProjection = manager.getMediaProjection(resultCode, resultData);
                    if (newProjection == null) {
                        throw new IllegalStateException("getMediaProjection 返回 null");
                    }
                    newProjection.registerCallback(projectionCallback, mainHandler);
                    activeCallback = projectionCallback;
                    AppLog.i("ScreenCastService", "projection callback registered");
                }

                mediaProjection = newProjection;
                AppLog.i("ScreenCastService", "getMediaProjection OK projection="
                        + newProjection);
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
            AppLog.e("ScreenCastService", "onStartCommand 异常 action=" + action, t);
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

    private void stopProjection(String reason, boolean alreadyStoppedBySystem) {
        AppLog.i("ScreenCastService", "stopProjection reason=" + reason
                + " alreadyStopped=" + alreadyStoppedBySystem
                + " hasProjection=" + (mediaProjection != null));
        MediaProjection projection = mediaProjection;
        mediaProjection = null;
        if (projection != null && !alreadyStoppedBySystem) {
            if (activeCallback != null) {
                try {
                    projection.unregisterCallback(activeCallback);
                    AppLog.i("ScreenCastService", "projection callback unregistered");
                } catch (Throwable t) {
                    AppLog.w("ScreenCastService", "unregister projection callback failed: " + t);
                }
            }
            activeCallback = null;
            try {
                projection.stop();
                AppLog.i("ScreenCastService", "projection.stop() returned");
            } catch (Throwable t) {
                AppLog.w("ScreenCastService", "projection.stop() failed: " + t);
            }
        }
        if (alreadyStoppedBySystem) {
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
        AppLog.i("ScreenCastService", "onDestroy");
        if (mediaProjection != null) stopProjection("service onDestroy", false);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) {
            AppLog.w("ScreenCastService", "NotificationManager unavailable");
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Cluster Projection", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(channel);
        AppLog.i("ScreenCastService", "notification channel ready");
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
