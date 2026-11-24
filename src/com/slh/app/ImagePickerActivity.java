package com.slh.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 自己的选图界面：4 列网格，选中带绿色序号，最多 9 张，顺序就是选的顺序 */
public class ImagePickerActivity extends Activity {

    private static final int REQ_PERM = 3001;
    private static final int MAX = 9;

    private final List<Uri> picked = new ArrayList<>();
    private final List<Uri> all = new ArrayList<>();
    private final List<TextView> badges = new ArrayList<>();
    private LinearLayout grid;
    private TextView title;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private int cell;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xFFFFFFFF);
        getWindow().setNavigationBarColor(0xFFFFFFFF);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                            | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        DisplayMetrics dm = getResources().getDisplayMetrics();
        // 4 列正好铺满：左右各 2dp，列间 2dp
        cell = (dm.widthPixels - dp(2) * 2 - dp(2) * 3) / 4;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFFFFFFF);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(10), dp(12), dp(10));
        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextSize(15);
        cancel.setTextColor(0xFF555555);
        cancel.setPadding(dp(6), dp(6), dp(6), dp(6));
        cancel.setOnClickListener(v -> finish());
        bar.addView(cancel);

        title = new TextView(this);
        title.setText("选择图片");
        title.setTextSize(16);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextColor(0xFF111111);
        title.setGravity(Gravity.CENTER);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        final TextView done = new TextView(this);
        done.setText("完成");
        done.setTextSize(15);
        done.setTextColor(0xFF2563EB);
        done.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        done.setPadding(dp(10), dp(6), dp(6), dp(6));
        done.setOnClickListener(v -> finishPick());
        bar.addView(done);
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        ScrollView sc = new ScrollView(this);
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(2), dp(2), dp(2), dp(24));
        sc.addView(grid);
        root.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        for (String s : getIntent().getStringArrayListExtra("selected")) {
            picked.add(Uri.parse(s));
        }
        refreshTitle();

        if (hasPerm()) load();
        else requestPermissions(perms(), REQ_PERM);
    }

    private String[] perms() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{"android.permission.READ_MEDIA_IMAGES",
                    Manifest.permission.READ_EXTERNAL_STORAGE};
        }
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    private boolean hasPerm() {
        if (Build.VERSION.SDK_INT < 23) return true;
        for (String p : perms()) {
            if (checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) return true;
        }
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (code != REQ_PERM) return;
        if (hasPerm()) {
            load();
        } else {
            tip("没有相册权限，读不到图片");
        }
    }

    private void tip(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xFF999999);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(20), dp(60), dp(20), dp(20));
        grid.removeAllViews();
        grid.addView(t);
    }

    private void load() {
        new Thread(() -> {
            List<Uri> list = new ArrayList<>();
            try (Cursor c = getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Images.Media._ID},
                    null, null, MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                if (c != null) {
                    int id = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                    while (c.moveToNext() && list.size() < 120) {
                        list.add(ContentUris.withAppendedId(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(id)));
                    }
                }
            } catch (Throwable t) {
                runOnUiThread(() -> tip("读相册失败：" + t.getMessage()));
                return;
            }
            final List<Uri> res = list;
            runOnUiThread(() -> build(res));
        }).start();
    }

    private void build(List<Uri> list) {
        all.clear();
        all.addAll(list);
        grid.removeAllViews();
        if (all.isEmpty()) {
            tip("相册里没有图片");
            return;
        }
        LinearLayout row = null;
        for (int i = 0; i < all.size(); i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(cell, cell);
            cp.leftMargin = dp(2);
            cp.bottomMargin = dp(2);
            row.addView(cellView(all.get(i), i), cp);
        }
    }

    private View cellView(final Uri uri, int index) {
        FrameLayout box = new FrameLayout(this);

        final ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundColor(0xFFEFEFEF);
        box.addView(iv, new FrameLayout.LayoutParams(-1, -1));

        final TextView badge = new TextView(this);
        badge.setVisibility(View.GONE);
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(12);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xFF07C160);
        g.setStroke(dp(2), Color.WHITE);
        badge.setBackground(g);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(24), dp(24),
                Gravity.TOP | Gravity.END);
        blp.topMargin = dp(4);
        blp.rightMargin = dp(4);
        box.addView(badge, blp);
        if (badges.size() > index) badges.set(index, badge);
        else badges.add(badge);

        // 先按已有选择回填
        int idx = picked.indexOf(uri);
        if (idx >= 0) {
            badge.setVisibility(View.VISIBLE);
            badge.setText(String.valueOf(idx + 1));
        }

        box.setOnClickListener(v -> {
            int pos = picked.indexOf(uri);
            if (pos >= 0) {
                picked.remove(pos);
            } else {
                if (picked.size() >= MAX) {
                    toast("最多选 " + MAX + " 张");
                    return;
                }
                picked.add(uri);
            }
            renumber();
        });

        final int px = dp(84);
        pool.execute(() -> {
            Bitmap bm = thumb(uri, px);
            if (bm != null) runOnUiThread(() -> iv.setImageBitmap(bm));
        });
        return box;
    }

    private void renumber() {
        for (int i = 0; i < badges.size(); i++) {
            TextView b = badges.get(i);
            if (b == null) continue;
            int pos = picked.indexOf(all.get(i));
            if (pos >= 0) {
                b.setVisibility(View.VISIBLE);
                b.setText(String.valueOf(pos + 1));
            } else {
                b.setVisibility(View.GONE);
            }
        }
        refreshTitle();
    }

    private void refreshTitle() {
        title.setText(picked.isEmpty() ? "选择图片" : "已选 " + picked.size() + "/" + MAX);
    }

    private void finishPick() {
        Intent data = new Intent();
        ArrayList<String> out = new ArrayList<>();
        for (Uri u : picked) out.add(u.toString());
        data.putStringArrayListExtra("uris", out);
        setResult(RESULT_OK, data);
        finish();
    }

    private Bitmap thumb(Uri uri, int size) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream in = getContentResolver().openInputStream(uri);
            BitmapFactory.decodeStream(in, null, o);
            if (in != null) in.close();
            int sample = 1;
            while (o.outWidth / (sample * 2) >= size) sample *= 2;
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

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
    }
}
