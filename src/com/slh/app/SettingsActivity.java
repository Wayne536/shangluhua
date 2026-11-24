package com.slh.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

import rikka.shizuku.Shizuku;

/** 设置：权限、更新、关于 */
public class SettingsActivity extends Activity {

    private static final String REPO = "Wayne536/shangluhua";
    private static final String API_TAGS = "https://api.github.com/repos/" + REPO + "/tags?per_page=100";
    private static final int REQ_SHIZUKU = 2001;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout content;
    private TextView shizukuValue, overlayValue, notifyValue, batteryValue;
    private TextView sourceValue, channelValue, updateStatus;

    private String source = "github";   // github | mirror
    private String channel = "stable";  // stable | beta
    private long downloadId = -1;
    private BroadcastReceiver downloadReceiver;

    private final Shizuku.OnRequestPermissionResultListener permListener = (code, result) -> {
        if (code == REQ_SHIZUKU) refreshPermissions();
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(col(R.color.bg));
        getWindow().setNavigationBarColor(col(R.color.bg));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(col(R.color.bg));

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        root.addView(main, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(10), dp(14), dp(6));

        ImageView back = new ImageView(this);
        back.setImageDrawable(icon(R.drawable.ic_back, col(R.color.text), dp(21)));
        back.setBackgroundResource(R.drawable.bg_text_button);
        back.setPadding(dp(8), dp(8), dp(8), dp(8));
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(38), dp(38)));

        TextView title = text("设置", 19, col(R.color.text));
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.leftMargin = dp(10);
        bar.addView(title, tlp);
        main.addView(bar);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(6), dp(16), dp(28));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        main.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        buildOverview();
        buildUpdate();
        buildPermissions();
        buildAbout();

        setContentView(root);

        try {
            Shizuku.addRequestPermissionResultListener(permListener);
        } catch (Throwable ignored) {
        }

        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                long id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == downloadId) onDownloadDone(id);
            }
        };
        try {
            registerReceiver(downloadReceiver,
                    new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissions();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            Shizuku.removeRequestPermissionResultListener(permListener);
        } catch (Throwable ignored) {
        }
        if (downloadReceiver != null) {
            try {
                unregisterReceiver(downloadReceiver);
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------- 概览 ----------------

    private void buildOverview() {
        LinearLayout card = card();
        card.addView(section("概览"));

        String ver = currentVersion();
        int code = currentCode();
        boolean hasSession = false;
        try {
            SharedPreferences sp = getSharedPreferences("slh", MODE_PRIVATE);
            String sid = sp.getString("sid", "");
            hasSession = sid != null && !sid.isEmpty();
        } catch (Throwable ignored) {
        }

        addRow(card, "版本", "v" + ver, col(R.color.text), null);
        addRow(card, "版本号", String.valueOf(code), col(R.color.muted), null);
        addRow(card, "登录会话", hasSession ? "已保存" : "未抓取",
                hasSession ? col(R.color.success) : col(R.color.warn), null);
        addRow(card, "商陆花接口", "14.103.84.43:8013", col(R.color.muted), null);
        addRow(card, "抓包端口", "127.0.0.1:8899", col(R.color.muted), null);
        content.addView(card);
    }

    // ---------------- 更新 ----------------

    private void buildUpdate() {
        LinearLayout card = card();
        card.addView(section("更新"));

        sourceValue = addRow(card, "更新源", sourceLabel(), col(R.color.primary), this::toggleSource);
        channelValue = addRow(card, "更新渠道", channelLabel(), col(R.color.primary), this::toggleChannel);

        TextView btn = text("检查更新", 15, Color.WHITE);
        btn.setGravity(Gravity.CENTER);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btn.setBackgroundResource(R.drawable.bg_primary_button);
        btn.setClickable(true);
        btn.setOnClickListener(v -> checkUpdate());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(46));
        blp.topMargin = dp(12);
        card.addView(btn, blp);

        updateStatus = text("当前 v" + currentVersion() + "，点上面按钮检查 GitHub 上的新版本", 11,
                col(R.color.hint));
        updateStatus.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(-1, -2);
        ulp.topMargin = dp(8);
        card.addView(updateStatus, ulp);
        content.addView(card);
    }

    private void toggleSource() {
        source = "github".equals(source) ? "mirror" : "github";
        sourceValue.setText(sourceLabel());
        updateStatus.setText("已切换到" + sourceLabel() + "，点「检查更新」重试");
    }

    private void toggleChannel() {
        channel = "stable".equals(channel) ? "beta" : "stable";
        channelValue.setText(channelLabel());
        updateStatus.setText("已切换到" + channelLabel() + "，点「检查更新」重试");
    }

    private String sourceLabel() {
        return "github".equals(source) ? "GitHub" : "镜像（jsDelivr）";
    }

    private String channelLabel() {
        return "stable".equals(channel) ? "正式版" : "公测版";
    }

    private void checkUpdate() {
        updateStatus.setText("正在检查更新…");
        final String cur = currentVersion();
        new Thread(() -> {
            TagInfo best = null;
            String err = null;
            try {
                String body = httpGet(API_TAGS);
                JSONArray arr = new JSONArray(body);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String name = o.optString("name", "").trim();
                    if (!name.matches("(?i)^v?\\d+(\\.\\d+){1,3}(-beta(\\.\\d+)?)?$")) continue;
                    boolean beta = name.toLowerCase(Locale.ROOT).contains("-beta");
                    if ("stable".equals(channel) && beta) continue;
                    if (compareVersion(name, cur) <= 0) continue;
                    if (best == null || compareVersion(name, best.tag) > 0) {
                        best = new TagInfo(name, beta);
                    }
                }
            } catch (Throwable t) {
                err = friendly(t);
            }
            final TagInfo fBest = best;
            final String fErr = err;
            ui.post(() -> showUpdateResult(cur, fBest, fErr));
        }).start();
    }

    private void showUpdateResult(String cur, TagInfo best, String err) {
        if (err != null) {
            updateStatus.setText("检查失败：" + err + "\n可切换更新源，或到 GitHub 仓库手动下载");
            return;
        }
        if (best == null) {
            updateStatus.setText("已是最新版本 v" + cur + "（" + sourceLabel() + "）");
            return;
        }
        updateStatus.setText("发现新版本 " + best.tag);
        new AlertDialog.Builder(this)
                .setTitle("发现新版本 " + best.tag)
                .setMessage("当前版本：v" + cur + "\n更新源：" + sourceLabel() + "\n\n"
                        + "是否下载更新？下载完成后会打开安装界面。")
                .setPositiveButton("下载更新", (d, w) -> download(best.tag))
                .setNegativeButton("稍后", null)
                .show();
    }

    private void download(String tag) {
        String file = "入库助手-" + tag + ".apk";
        String base;
        if ("mirror".equals(source)) {
            base = "https://cdn.jsdelivr.net/gh/" + REPO + "@" + tag + "/releases/";
        } else {
            base = "https://github.com/" + REPO + "/raw/" + tag + "/releases/";
        }
        String url = base + encode(file);
        try {
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle("入库助手 " + tag);
            req.setDescription("正在下载更新…");
            req.setMimeType("application/vnd.android.package-archive");
            req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, file);
            downloadId = dm.enqueue(req);
            updateStatus.setText("正在下载 " + tag + "…下载完成后会自动打开安装界面");
        } catch (Throwable t) {
            updateStatus.setText("下载失败：" + t.getMessage());
        }
    }

    private void onDownloadDone(long id) {
        try {
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            Cursor c = dm.query(new DownloadManager.Query().setFilterById(id));
            int status = -1;
            if (c != null) {
                if (c.moveToFirst()) {
                    status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
                }
                c.close();
            }
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                updateStatus.setText("下载失败，可切换更新源后重试");
                return;
            }
            Uri uri = dm.getUriForDownloadedFile(id);
            if (uri == null) {
                updateStatus.setText("下载完成，但没找到安装包");
                return;
            }
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            updateStatus.setText("下载完成，正在打开安装界面…");
            startActivity(i);
        } catch (Throwable t) {
            updateStatus.setText("安装包已下载，请到通知栏点击安装（" + t.getMessage() + "）");
        }
    }

    // ---------------- 权限 ----------------

    private void buildPermissions() {
        LinearLayout card = card();
        card.addView(section("权限管理"));

        shizukuValue = addRow(card, "Shizuku（自动抓取）", "", col(R.color.muted), this::onShizuku);
        overlayValue = addRow(card, "悬浮窗", "", col(R.color.muted), this::onOverlay);
        notifyValue = addRow(card, "通知权限", "", col(R.color.muted), this::onNotify);
        batteryValue = addRow(card, "电池优化白名单", "", col(R.color.muted), this::onBattery);
        addRow(card, "存储", "应用私有目录", col(R.color.success), null);

        TextView note = text("授权 Shizuku 后抓会话全自动：自动打开商陆花 → 货品 → 下拉刷新；没授权就走手动两步流程。电池白名单可减少后台被杀。", 11, col(R.color.hint));
        note.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.topMargin = dp(8);
        card.addView(note, nlp);
        content.addView(card);
    }

    private void refreshPermissions() {
        if (shizukuValue == null) return;
        try {
            if (!Shizuku.pingBinder()) {
                setVal(shizukuValue, "Shizuku 未运行", col(R.color.error));
            } else if (Shizuku.checkSelfPermission() != 0) {
                setVal(shizukuValue, "未授权", col(R.color.warn));
            } else {
                setVal(shizukuValue, "已授权", col(R.color.success));
            }
        } catch (Throwable t) {
            setVal(shizukuValue, "状态未知", col(R.color.warn));
        }

        setVal(overlayValue, Settings.canDrawOverlays(this) ? "已授权" : "未授权",
                Settings.canDrawOverlays(this) ? col(R.color.success) : col(R.color.warn));

        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            boolean on = nm != null && nm.areNotificationsEnabled();
            setVal(notifyValue, on ? "已开启" : "已关闭",
                    on ? col(R.color.success) : col(R.color.warn));
        } catch (Throwable t) {
            setVal(notifyValue, "状态未知", col(R.color.muted));
        }

        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            boolean ok = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
            setVal(batteryValue, ok ? "已加入白名单" : "未加入",
                    ok ? col(R.color.success) : col(R.color.warn));
        } catch (Throwable t) {
            setVal(batteryValue, "状态未知", col(R.color.muted));
        }
    }

    private void onShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                Intent i = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                if (i != null) startActivity(i);
                else toast("请先安装并启动 Shizuku");
                return;
            }
            if (Shizuku.checkSelfPermission() != 0) {
                toast("请在弹窗里允许「入库助手」");
                Shizuku.requestPermission(REQ_SHIZUKU);
                return;
            }
            toast("Shizuku 已授权");
        } catch (Throwable t) {
            toast("打开 Shizuku 失败：" + t.getMessage());
        }
    }

    private void onOverlay() {
        try {
            if (Settings.canDrawOverlays(this)) {
                toast("悬浮窗已授权");
                return;
            }
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            toast("打开悬浮窗设置失败：" + t.getMessage());
        }
    }

    private void onNotify() {
        try {
            Intent i;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            } else {
                i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
            }
            startActivity(i);
        } catch (Throwable t) {
            toast("打开通知设置失败：" + t.getMessage());
        }
    }

    private void onBattery() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                toast("已在电池优化白名单");
                return;
            }
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable t2) {
                toast("打开电池优化设置失败：" + t2.getMessage());
            }
        }
    }

    // ---------------- 关于 ----------------

    private void buildAbout() {
        LinearLayout card = card();
        card.addView(section("关于"));
        addRow(card, "GitHub 仓库", REPO, col(R.color.primary),
                () -> openUrl("https://github.com/" + REPO));
        addRow(card, "所有版本", "Tags", col(R.color.primary),
                () -> openUrl("https://github.com/" + REPO + "/tags"));
        addRow(card, "问题反馈", "Issues", col(R.color.primary),
                () -> openUrl("https://github.com/" + REPO + "/issues"));
        content.addView(card);
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            toast("打开链接失败");
        }
    }

    // ---------------- 工具 ----------------

    private int col(int id) {
        return getColor(id);
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        t.setTextColor(color);
        return t;
    }

    private TextView section(String s) {
        TextView t = text(s, 12, col(R.color.muted));
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(0, dp(2), 0, dp(4));
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.bg_card);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        c.setElevation(dp(1));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(12);
        c.setLayoutParams(p);
        return c;
    }

    private TextView addRow(LinearLayout card, String label, String value, int color, Runnable action) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(12), 0, dp(12));

        TextView l = text(label, 14, col(R.color.text));
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView v = text(value, 12, color);
        v.setGravity(Gravity.END);
        r.addView(v);

        if (action != null) {
            r.setBackgroundResource(R.drawable.bg_row);
            r.setClickable(true);
            r.setOnClickListener(x -> action.run());
        }
        card.addView(r);

        View d = new View(this);
        d.setBackgroundColor(col(R.color.border));
        card.addView(d, new LinearLayout.LayoutParams(-1, dp(1)));
        return v;
    }

    private Drawable icon(int res, int color, int size) {
        Drawable d = getResources().getDrawable(res, getTheme()).mutate();
        d.setTint(color);
        d.setBounds(0, 0, size, size);
        return d;
    }

    private void setVal(TextView v, String s, int color) {
        if (v == null) return;
        v.setText(s);
        v.setTextColor(color);
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private String currentVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "1.0.0";
        }
    }

    private int currentCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    private String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "shangluhua-app");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
        }
        c.disconnect();
        if (code != 200) throw new java.io.IOException("HTTP " + code);
        return new String(bo.toByteArray(), "UTF-8");
    }

    private String friendly(Throwable t) {
        if (t instanceof java.net.UnknownHostException) return "网络不可用或 GitHub 被屏蔽";
        String m = t.getMessage();
        if (m != null && m.startsWith("HTTP ")) {
            return "GitHub 返回 " + m.substring(5) + "，可尝试切换更新源或开梯子";
        }
        return m == null ? t.getClass().getSimpleName() : m;
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Throwable t) {
            return s;
        }
    }

    private static int compareVersion(String a, String b) {
        int[] va = nums(a), vb = nums(b);
        for (int i = 0; i < 4; i++) {
            if (va[i] != vb[i]) return va[i] - vb[i];
        }
        boolean ba = a.toLowerCase(Locale.ROOT).contains("-beta");
        boolean bb = b.toLowerCase(Locale.ROOT).contains("-beta");
        if (ba != bb) return ba ? -1 : 1;
        return 0;
    }

    private static int[] nums(String s) {
        String x = s.replaceFirst("(?i)^v", "");
        int dash = x.indexOf('-');
        if (dash >= 0) x = x.substring(0, dash);
        String[] p = x.split("\\.");
        int[] r = new int[4];
        for (int i = 0; i < Math.min(4, p.length); i++) {
            try {
                r[i] = Integer.parseInt(p[i]);
            } catch (Throwable ignored) {
            }
        }
        return r;
    }

    private static class TagInfo {
        final String tag;
        final boolean beta;

        TagInfo(String tag, boolean beta) {
            this.tag = tag;
            this.beta = beta;
        }
    }
}
