package com.slh.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.ProxyInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 只把商陆花放进 VPN，并把它的 HTTP 请求指向本机代理。
 * 不需要 Shizuku，也不改系统全局代理。
 */
public class CaptureVpnService extends VpnService {

    public static final String ACTION_START = "com.slh.app.VPN_START";
    public static final String ACTION_STOP = "com.slh.app.VPN_STOP";
    public static final String ACTION_SESSION = "com.slh.app.SESSION_UPDATED";

    private static final String CHANNEL = "capture";
    private static final int NOTIFY_ID = 41;

    public static volatile String capturedSid;
    public static volatile boolean running;

    private ParcelFileDescriptor tun;
    private int proxyPort = SessionCatcher.PORT;
    private ProxyCatcher proxy;
    private Thread timeoutThread;
    private volatile boolean stopped;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopAll("收到停止命令");
            return START_NOT_STICKY;
        }

        startForeground(NOTIFY_ID, buildNotification("正在等待商陆花请求…", false));
        if (running) return START_NOT_STICKY;

        running = true;
        stopped = false;
        capturedSid = null;

        try {
            ProxyCatcher.blockDataTunnel = true;
            proxy = new ProxyCatcher();
            proxyPort = proxy.startAuto(sid -> {
                capturedSid = sid;
                saveSid(sid);
                log("抓到 sessionid: " + mask(sid));
                notify("会话已更新", "代理已关闭，可以继续导入");
                sendBroadcast(new Intent(ACTION_SESSION).setPackage(getPackageName()));
                new Thread(() -> {
                    try {
                        Thread.sleep(800);
                    } catch (InterruptedException ignored) {
                    }
                    stopAll("已抓到会话");
                }).start();
            }, msg -> log("[代理] " + msg));
            log("本地代理已启动 127.0.0.1:" + proxyPort);
        } catch (Throwable t) {
            log("代理启动失败：" + t);
            stopAll("代理启动失败");
            return START_NOT_STICKY;
        }

        if (!establishVpn()) {
            stopAll("VPN 建立失败");
            return START_NOT_STICKY;
        }

        timeoutThread = new Thread(() -> {
            try {
                Thread.sleep(120000);
            } catch (InterruptedException ignored) {
            }
            if (running && capturedSid == null) {
                log("等待超时：没有抓到 sessionid");
                notify("没抓到会话", "请打开商陆花并刷新货品页后重试");
                stopAll("等待超时");
            }
        }, "vpn-timeout");
        timeoutThread.setDaemon(true);
        timeoutThread.start();

        return START_NOT_STICKY;
    }

    private boolean establishVpn() {
        try {
            Builder b = new Builder();
            b.setSession("入库助手抓会话");
            b.addAddress("10.8.0.2", 32);
            b.addRoute("10.8.0.0", 24);
            b.addAllowedApplication("com.slh_mobile_pro");
            b.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", proxyPort));
            b.setBlocking(false);
            tun = b.establish();
            if (tun == null) {
                log("VPN establish 返回 null");
                return false;
            }
            log("VPN 已建立，仅代理商陆花；HTTP 代理 127.0.0.1:" + proxyPort);
            return true;
        } catch (Throwable t) {
            log("VPN establish 异常：" + t);
            return false;
        }
    }

    private void stopAll(String reason) {
        synchronized (this) {
            if (stopped) return;
            stopped = true;
        }
        running = false;
        log("收尾：" + reason);
        ProxyCatcher.blockDataTunnel = false;
        if (proxy != null) {
            try {
                proxy.stop();
            } catch (Throwable ignored) {
            }
            proxy = null;
        }
        if (tun != null) {
            try {
                tun.close();
            } catch (Throwable ignored) {
            }
            tun = null;
        }
        if (timeoutThread != null) {
            timeoutThread.interrupt();
            timeoutThread = null;
        }
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
        stopSelf();
    }

    @Override
    public void onRevoke() {
        log("VPN 权限被撤销");
        stopAll("VPN 被撤销");
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        stopAll("服务销毁");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void saveSid(String sid) {
        try {
            getSharedPreferences("slh", MODE_PRIVATE).edit().putString("sid", sid).apply();
        } catch (Throwable ignored) {
        }
    }

    private Notification buildNotification(String text, boolean done) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "抓会话", NotificationManager.IMPORTANCE_LOW);
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
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(!done);
        return b.build();
    }

    private void notify(String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFY_ID + 1, buildNotification(title + " · " + text, true));
        } catch (Throwable ignored) {
        }
    }

    private void log(String s) {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) return;
            FileWriter w = new FileWriter(new File(dir, "cap.log"), true);
            w.write(new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date())
                    + " [VPN] " + s + "\n");
            w.close();
        } catch (Throwable ignored) {
        }
    }

    private static String mask(String sid) {
        if (sid == null) return "null";
        return sid.length() <= 16 ? sid : sid.substring(0, 16) + "…";
    }
}
