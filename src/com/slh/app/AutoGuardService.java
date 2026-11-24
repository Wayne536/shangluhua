package com.slh.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 自动抓会话期间的前台服务：商陆花一到前台，本 App 会被系统冻结，
 * 挂个前台通知把进程保活，后台线程才能继续跑 am / input。
 */
public class AutoGuardService extends Service {

    private static final String CHANNEL = "auto_capture";
    private static final int ID = 42;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(ID, build());
        } catch (Throwable ignored) {
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification build() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "自动抓会话",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("入库助手")
                .setContentText("正在自动抓取会话…")
                .setContentIntent(pi)
                .setOngoing(true);
        return b.build();
    }
}
