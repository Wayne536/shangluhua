package com.slh.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.InputStream;

/** 点缩略图看大图：黑底、整图居中、点一下退出 */
public class ImagePreviewActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        final ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(iv, new FrameLayout.LayoutParams(-1, -1));

        TextView tip = new TextView(this);
        tip.setText("点一下返回");
        tip.setTextSize(12);
        tip.setTextColor(0xB3FFFFFF);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x66000000);
        bg.setCornerRadius(dp(14));
        tip.setBackground(bg);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        tlp.bottomMargin = dp(36);
        root.addView(tip, tlp);

        root.setOnClickListener(v -> finish());
        setContentView(root);

        String s = getIntent() == null ? null : getIntent().getStringExtra("uri");
        if (s == null) {
            finish();
            return;
        }
        final Uri uri = Uri.parse(s);
        final int w = getResources().getDisplayMetrics().widthPixels;
        final int h = getResources().getDisplayMetrics().heightPixels;
        new Thread(() -> {
            Bitmap bm = decode(uri, w, h);
            runOnUiThread(() -> {
                if (bm == null) {
                    tip.setText("这张图读不出来");
                    return;
                }
                iv.setImageBitmap(bm);
            });
        }).start();
    }

    /** 按屏幕大小采样解码，避免大图直接 OOM */
    private Bitmap decode(Uri uri, int w, int h) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream in = getContentResolver().openInputStream(uri);
            BitmapFactory.decodeStream(in, null, o);
            if (in != null) in.close();
            int sample = 1;
            while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) {
                sample *= 2;
            }
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            in = getContentResolver().openInputStream(uri);
            Bitmap bm = BitmapFactory.decodeStream(in, null, o2);
            if (in != null) in.close();
            return bm;
        } catch (Throwable t) {
            return null;
        }
    }

    private int dp(int v) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return (int) (v * dm.density + 0.5f);
    }
}
