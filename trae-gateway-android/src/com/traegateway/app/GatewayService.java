package com.traegateway.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

public class GatewayService extends Service {

    public static final String ACTION_START = "com.traegateway.app.START";
    public static final String ACTION_STOP = "com.traegateway.app.STOP";
    public static final String ACTION_RESTART = "com.traegateway.app.RESTART";

    private static final String CHANNEL_ID = "gateway";
    private static final int NOTIFICATION_ID = 1;

    private Handler handler;
    private boolean foregroundStarted;

    public static void start(Context context) {
        Intent intent = new Intent(context, GatewayService.class).setAction(ACTION_START);
        startCompat(context, intent, true);
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, GatewayService.class).setAction(ACTION_STOP);
        startCompat(context, intent, false);
    }

    public static void restart(Context context) {
        Intent intent = new Intent(context, GatewayService.class).setAction(ACTION_RESTART);
        startCompat(context, intent, true);
    }

    private static void startCompat(Context context, Intent intent, boolean foreground) {
        try {
            if (foreground && Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable t) {
            Gateway.addLog("[app] 启动服务失败：" + t.getMessage());
            if (foreground) {
                try {
                    context.startService(intent);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Gateway.init(getApplicationContext());
        handler = new Handler(Looper.getMainLooper());
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            Gateway.stop();
            stopForeground(true);
            foregroundStarted = false;
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_RESTART.equals(action)) {
            Gateway.stop();
            Gateway.setUserStopped(false);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(1600);
                    } catch (InterruptedException ignored) {
                    }
                    Gateway.start();
                }
            }, "gateway-restart").start();
        } else {
            if (intent != null) {
                Gateway.resetFailures();
            }
            Gateway.setUserStopped(false);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    Gateway.start();
                }
            }, "gateway-launch").start();
        }

        if (!foregroundStarted) {
            startForeground(NOTIFICATION_ID, buildNotification("网关启动中", "正在启动本地网关…"));
            foregroundStarted = true;
        }
        updateNotification();
        scheduleWatchdog();
        return START_STICKY;
    }

    private void scheduleWatchdog() {
        if (handler == null) {
            return;
        }
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, 4000);
    }

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (Gateway.isUserStopped()) {
                handler.postDelayed(this, 5000);
                return;
            }
            if (Gateway.isRunning()) {
                if (System.currentTimeMillis() - Gateway.getStartedAt() > 30000) {
                    Gateway.resetFailures();
                }
            } else if (Gateway.getFailures() >= 5) {
                updateNotification("网关启动失败", "请打开 App 查看运行日志");
            } else {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        Gateway.start();
                    }
                }, "gateway-watchdog").start();
            }
            updateNotification();
            handler.postDelayed(this, 5000);
        }
    };

    private void updateNotification() {
        updateNotification(Gateway.isRunning() ? "网关运行中" : "网关启动中",
                Gateway.isRunning() ? "端口 " + Gateway.port() + " · 本机可访问" : "正在启动本地网关…");
    }

    private void updateNotification(String title, String text) {
        if (!foregroundStarted) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(title, text));
        }
    }

    private Notification buildNotification(String title, String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(this, 10, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent panelIntent = new Intent(this, PanelActivity.class);
        panelIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent panelPending = PendingIntent.getActivity(this, 11, panelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, GatewayService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 12, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openPending)
                .setPriority(Notification.PRIORITY_LOW)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(0, getString(R.string.notif_action_panel), panelPending)
                .addAction(0, getString(R.string.notif_action_stop), stopPending);
        return builder.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.channel_desc));
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    @Override
    public void onDestroy() {
        if (handler != null) {
            handler.removeCallbacks(watchdog);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
