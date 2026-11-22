package com.slh.app;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;

import rikka.shizuku.Shizuku;

/** 悬浮窗实时预览 + 点击/滑动注入 */
public class CapService extends Service {

    public static ImageView target = null;
    private boolean useOverlay = true;

    private Intent intent0;
    private MediaProjection mp;
    private ImageReader reader;
    private VirtualDisplay vd;
    private ImageView iv;
    private TextView info;
    private LinearLayout box;
    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private final Handler h = new Handler(Looper.getMainLooper());
    private int sw, sh, dispW, dispH;
    private int frames = 0;
    private long lastFps = 0;
    private boolean loop = false, expanded = false;
    private float downX, downY, startX, startY;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        intent0 = intent;
        int code = intent.getIntExtra("code", 0);
        Intent data = intent.getParcelableExtra("data");
        if (data == null) return START_NOT_STICKY;
        try { start(code, data); } catch (Throwable t) {
            log("start 失败: " + android.util.Log.getStackTraceString(t));
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void log(String msg) {
        try {
            java.io.File f = new java.io.File(getExternalFilesDir(null), "cap.log");
            java.io.FileWriter w = new java.io.FileWriter(f, true);
            w.write(new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date())
                    + " " + msg + "\n");
            w.close();
        } catch (Throwable ignored) {}
    }

    private void start(int code, Intent data) {
        log("start code=" + code + " data=" + (data != null));
        DisplayMetrics dm = new DisplayMetrics();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(dm);
        dispW = dm.widthPixels; dispH = dm.heightPixels;
        sw = dispW; sh = dispH;

        // 必须先成为 mediaProjection 类型的前台服务，否则系统拒绝
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new android.app.NotificationChannel(
                    "cap", "屏幕预览", android.app.NotificationManager.IMPORTANCE_LOW));
            android.app.Notification n = new android.app.Notification.Builder(this, "cap")
                    .setContentTitle("入库助手")
                    .setContentText("正在预览屏幕")
                    .setSmallIcon(android.R.drawable.ic_menu_view)
                    .build();
            startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            log("已进入前台服务");
        } catch (Throwable t) {
            log("startForeground 失败: " + t);
        }

        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mp = mgr.getMediaProjection(code, data);
        mp.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopSelf(); }
        }, h);

        reader = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 2);
        vd = mp.createVirtualDisplay("cap", sw, sh, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, h);

        log("虚拟显示已创建 " + sw + "x" + sh);
        useOverlay = intent0.getBooleanExtra("overlay", true);
        if (useOverlay) { buildOverlay(); log("悬浮窗已添加"); }
        else log("使用页面内预览");
        loop = true;
        lastFps = System.currentTimeMillis();
        h.postDelayed(this::pump, 80);
    }

    private void buildOverlay() {
        LinearLayout box2 = new LinearLayout(this);
        box2.setOrientation(LinearLayout.VERTICAL);
        box2.setBackgroundColor(0xFF1A1A1A);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(0xFF222222);
        info = new TextView(this);
        info.setTextColor(Color.WHITE);
        info.setTextSize(11);
        info.setPadding(14, 6, 14, 6);
        info.setText("启动中…");
        head.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView exp = chip("⛶");
        exp.setOnClickListener(v -> toggleExpand());
        TextView close = chip("×");
        close.setOnClickListener(v -> stopSelf());
        head.addView(exp);
        head.addView(close);
        box2.addView(head);

        iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_XY);
        iv.setOnTouchListener(this::onTouch);
        box2.addView(iv, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView q = new TextView(this);
        q.setText("队列：6887 19色｜8806 3色（待接入库）");
        q.setTextSize(11);
        q.setTextColor(0xFFBBBBBB);
        q.setPadding(12, 6, 12, 6);
        q.setBackgroundColor(0xFF222222);
        box2.addView(q);

        box = box2;

        int w = (int) (dispW * 0.86);
        int hgt = (int) (dispH * 0.34);
        lp = new WindowManager.LayoutParams(w, hgt, 2038,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = (dispW - w) / 2;
        lp.y = (int) (dispH * 0.30);
        wm.addView(box, lp);
    }

    private TextView chip(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(18);
        v.setTextColor(Color.WHITE);
        v.setGravity(Gravity.CENTER);
        v.setBackgroundColor(0xCC333333);
        v.setPadding(24, 4, 24, 8);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.leftMargin = 8;
        v.setLayoutParams(p);
        return v;
    }

    private void toggleExpand() {
        expanded = !expanded;
        if (expanded) {
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.MATCH_PARENT;
            lp.x = 0; lp.y = 0;
            lp.gravity = Gravity.TOP | Gravity.START;
            // 保持可点：进入全屏时允许接收触摸
            lp.flags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        } else {
            int w = (int) (dispW * 0.62), hgt = (int) (dispH * 0.30);
            lp.width = w; lp.height = hgt;
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = (dispW - w) / 2;
            lp.y = (int) (dispH * 0.35);
            lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        }
        try { wm.updateViewLayout(box, lp); } catch (Throwable ignored) {}
    }

    private boolean onTouch(View v, MotionEvent e) {
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX(); downY = e.getRawY();
                startX = e.getX(); startY = e.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!expanded) {
                    lp.x += (int) (e.getRawX() - downX);
                    lp.y += (int) (e.getRawY() - downY);
                    downX = e.getRawX(); downY = e.getRawY();
                    try { wm.updateViewLayout(box, lp); } catch (Throwable ignored) {}
                }
                return true;
            case MotionEvent.ACTION_UP:
                float dx = e.getX() - startX, dy = e.getY() - startY;
                int x1 = (int) (startX / v.getWidth() * sw);
                int y1 = (int) (startY / v.getHeight() * sh);
                int x2 = (int) (e.getX() / v.getWidth() * sw);
                int y2 = (int) (e.getY() / v.getHeight() * sh);
                if (Math.hypot(dx, dy) < 24) inject("input tap " + x1 + " " + y1);
                else inject("input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " 220");
                return true;
        }
        return false;
    }

    private void inject(String cmd) {
        new Thread(() -> {
            try {
                Method m = Shizuku.class.getDeclaredMethod("newProcess",
                        String[].class, String[].class, String.class);
                m.setAccessible(true);
                Process p = (Process) m.invoke(null, new String[]{"sh", "-c", cmd}, null, null);
                p.waitFor();
            } catch (Throwable ignored) {}
        }).start();
    }

    private int tries = 0;
    private int acq = 0, nonNull = 0, bmpOk = 0, errCnt = 0;
    private String lastErr = "";
    private long statAt = 0;

    private void pump() {
        if (!loop) return;
        try {
            Image img = reader.acquireLatestImage();
            tries++;
            acq++;
            if (img != null) nonNull++;
            if (tries <= 3) log("取帧 #" + tries + " -> " + (img == null ? "null" : (img.getWidth() + "x" + img.getHeight())));
            if (img != null) {
                Bitmap bmp = toBitmap(img);
                img.close();
                if (bmp != null) {
                    if (bmpOk == 0) log("首帧 " + bmp.getWidth() + "x" + bmp.getHeight());
                    ImageView dst = useOverlay ? iv : target;
                    if (dst != null) dst.setImageBitmap(bmp);
                    bmpOk++;
                    frames++;
                }
            }
        } catch (Throwable t) {
            errCnt++;
            lastErr = String.valueOf(t);
        }
        long now = System.currentTimeMillis();
        if (now - statAt >= 2000) {
            statAt = now;
            log("统计 acq=" + acq + " 非空=" + nonNull + " 位图=" + bmpOk
                    + " 错误=" + errCnt + (lastErr.isEmpty() ? "" : " 最后错误=" + lastErr));
        }
        if (now - lastFps >= 1000) {
            info.setText("FPS " + frames + " ｜ 可点可滑");
            frames = 0; lastFps = now;
        }
        h.postDelayed(this::pump, 80);
    }

    private Bitmap toBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buf = plane.getBuffer();
        int rowStride = plane.getRowStride(), pixelStride = plane.getPixelStride();
        int w = image.getWidth(), hgt = image.getHeight();
        int pad = rowStride - pixelStride * w;
        int bw = w + pad / pixelStride;
        Bitmap bmp = Bitmap.createBitmap(bw, hgt, Bitmap.Config.ARGB_8888);
        bmp.copyPixelsFromBuffer(buf);
        if (bw != w) {
            Bitmap c = Bitmap.createBitmap(bmp, 0, 0, w, hgt);
            bmp.recycle();
            return c;
        }
        return bmp;
    }

    @Override
    public void onDestroy() {
        loop = false;
        try { if (box != null) wm.removeView(box); } catch (Throwable ignored) {}
        try { if (vd != null) vd.release(); } catch (Throwable ignored) {}
        try { if (mp != null) mp.stop(); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
