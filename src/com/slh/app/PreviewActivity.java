package com.slh.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

import rikka.shizuku.Shizuku;

/** 实时预览 + 点击注入（验证版） */
public class PreviewActivity extends Activity {

    private MediaProjection mp;
    private ImageReader reader;
    private VirtualDisplay vd;
    private ImageView iv;
    private TextView fpsView;
    private final Handler h = new Handler(Looper.getMainLooper());
    private int sw, sh;
    private int frames = 0;
    private long lastFpsTime = 0;
    private boolean loop = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        sw = dm.widthPixels;
        sh = dm.heightPixels;

        FrameLayout root = new FrameLayout(this);
        iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_XY);
        iv.setBackgroundColor(0xFF000000);
        root.addView(iv, new FrameLayout.LayoutParams(-1, -1));

        fpsView = new TextView(this);
        fpsView.setTextColor(0xFFFFFFFF);
        fpsView.setTextSize(12);
        fpsView.setBackgroundColor(0x88000000);
        fpsView.setPadding(12, 6, 12, 6);
        fpsView.setText("准备捕获…");
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(-2, -2);
        flp.leftMargin = 20; flp.topMargin = 20;
        root.addView(fpsView, flp);
        setContentView(root);

        iv.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_UP) {
                float sx = e.getX() / v.getWidth() * sw;
                float sy = e.getY() / v.getHeight() * sh;
                tap((int) sx, (int) sy);
            }
            return true;
        });

        requestProjection();
    }

    private void requestProjection() {
        MediaProjectionManager mgr =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        Intent i = null;
        try {
            // Android 14+：只共享单个 App
            Class<?> cfgCls = Class.forName("android.media.projection.MediaProjectionConfig");
            Object cfg = cfgCls.getMethod("createConfigForUserChoice").invoke(null);
            Method m = mgr.getClass().getMethod("createScreenCaptureIntent", cfgCls);
            i = (Intent) m.invoke(mgr, cfg);
        } catch (Throwable t) {
            i = mgr.createScreenCaptureIntent();
        }
        startActivityForResult(i, 1001);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != 1001 || res != RESULT_OK || data == null) {
            fpsView.setText("已取消授权");
            return;
        }
        MediaProjectionManager mgr =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mp = mgr.getMediaProjection(res, data);
        mp.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { loop = false; }
        }, h);

        reader = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 2);
        vd = mp.createVirtualDisplay("cap", sw, sh,
                getResources().getDisplayMetrics().densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, h);
        loop = true;
        lastFpsTime = System.currentTimeMillis();
        h.postDelayed(this::pump, 100);
        // 选完目标 App 后系统会把它切到前台，这里把我们的页面拉回来
        h.postDelayed(() -> {
            Intent back = new Intent(this, PreviewActivity.class);
            back.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(back);
        }, 1800);
    }

    private void pump() {
        if (!loop) return;
        try {
            Image img = reader.acquireLatestImage();
            if (img != null) {
                Bitmap bmp = toBitmap(img);
                img.close();
                if (bmp != null) {
                    iv.setImageBitmap(bmp);
                    frames++;
                }
            }
        } catch (Throwable ignored) {}
        long now = System.currentTimeMillis();
        if (now - lastFpsTime >= 1000) {
            fpsView.setText("FPS " + frames + "  点按可操作");
            frames = 0;
            lastFpsTime = now;
        }
        h.postDelayed(this::pump, 80);
    }

    private Bitmap toBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buf = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int w = image.getWidth(), hgt = image.getHeight();
        int rowPadding = rowStride - pixelStride * w;
        int bw = w + rowPadding / pixelStride;
        Bitmap bmp = Bitmap.createBitmap(bw, hgt, Bitmap.Config.ARGB_8888);
        bmp.copyPixelsFromBuffer(buf);
        if (bw != w) {
            Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0, w, hgt);
            bmp.recycle();
            return cropped;
        }
        return bmp;
    }

    private void tap(int x, int y) {
        new Thread(() -> {
            try {
                Method m = Shizuku.class.getDeclaredMethod("newProcess",
                        String[].class, String[].class, String.class);
                m.setAccessible(true);
                Process p = (Process) m.invoke(null,
                        new String[]{"sh", "-c", "input tap " + x + " " + y}, null, null);
                p.waitFor();
            } catch (Throwable ignored) {}
        }).start();
    }

    @Override
    protected void onDestroy() {
        loop = false;
        try { if (vd != null) vd.release(); } catch (Throwable ignored) {}
        try { if (mp != null) mp.stop(); } catch (Throwable ignored) {}
        super.onDestroy();
    }
}
