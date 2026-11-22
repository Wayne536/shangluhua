package com.slh.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {

    private TextView status;
    private LinearLayout list;
    private Button startBtn, stopBtn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("入库助手");
        title.setTextSize(16);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(11);
        status.setTextColor(Color.parseColor("#666666"));
        status.setText("正在检查 Shizuku…");
        status.setPadding(0, dp(3), 0, dp(8));
        root.addView(status);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        startBtn = new Button(this);
        startBtn.setText("启动");
        startBtn.setAllCaps(false);
        startBtn.setTextSize(15);
        startBtn.setOnClickListener(v -> new Thread(this::startLayout).start());

        stopBtn = new Button(this);
        stopBtn.setText("停止");
        stopBtn.setAllCaps(false);
        stopBtn.setTextSize(15);
        stopBtn.setOnClickListener(v -> new Thread(this::stopLayout).start());

        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -2, 1f);
        half.rightMargin = dp(6);
        startBtn.setLayoutParams(half);
        stopBtn.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(startBtn);
        row.addView(stopBtn);
        root.addView(row);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(4), 0, 0);
        Button wxBtn = new Button(this);
        wxBtn.setText("微信全屏");
        wxBtn.setAllCaps(false);
        wxBtn.setTextSize(14);
        wxBtn.setOnClickListener(v -> new Thread(() ->
                fullscreen("com.tencent.mm", "com.tencent.mm/.ui.LauncherUI")).start());
        Button slhBtn = new Button(this);
        slhBtn.setText("商陆花全屏");
        slhBtn.setAllCaps(false);
        slhBtn.setTextSize(14);
        slhBtn.setOnClickListener(v -> new Thread(() ->
                fullscreen("com.slh_mobile_pro", "com.slh_mobile_pro/com.slh_mobile_pro.MainActivity")).start());
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, -2, 1f);
        half2.rightMargin = dp(6);
        wxBtn.setLayoutParams(half2);
        slhBtn.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        row2.addView(wxBtn);
        row2.addView(slhBtn);
        root.addView(row2);

        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(8), 0, 0);
        sv.addView(list);
        sv.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(sv);

        setContentView(root);
        addItem("队列（待接）", "#999999", 11);
        addItem("6887  19 色", "#333333", 13);
        addItem("8806  3 色", "#333333", 13);
        refreshStatus();
    }

    private void addItem(String t, String color, int size) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(size);
        v.setTextColor(Color.parseColor(color));
        v.setPadding(dp(2), dp(3), dp(2), dp(3));
        list.addView(v);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void ui(String s) { runOnUiThread(() -> status.setText(s)); }

    private void refreshStatus() {
        try {
            if (!Shizuku.pingBinder()) { ui("Shizuku 未运行：请先打开 Shizuku"); return; }
            ui(Shizuku.checkSelfPermission() == 0 ? "就绪" : "Shizuku 未授权");
        } catch (Throwable t) { ui("Shizuku 状态异常"); }
    }

    // ---------- 命令 ----------
    private String sh(String cmd) {
        try {
            java.lang.reflect.Method m = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            Process p = (Process) m.invoke(null, new String[]{"sh", "-c", cmd}, null, null);
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            p.waitFor();
            return sb.toString();
        } catch (Throwable t) { return "ERR:" + t; }
    }

    private int taskIdOf(String pkg) {
        String out = sh("dumpsys activity activities | grep -E 'Task\\{.*" + pkg + "' | head -1");
        Matcher m = Pattern.compile("Task\\{[0-9a-f]+ #(\\d+)").matcher(out);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    // ---------- 布局 ----------
    private void startLayout() {
        try {
            ui("准备中…");
            sh("settings put global enable_freeform_support 1");
            sh("settings put global force_resizable_activities 1");
            int w = getResources().getDisplayMetrics().widthPixels;
            int h = getResources().getDisplayMetrics().heightPixels;

            // 起两个 App（自由窗口）
            if (taskIdOf("com.slh_mobile_pro") <= 0) {
                ui("启动商陆花…");
                sh("am start --windowingMode 5 -n com.slh_mobile_pro/com.slh_mobile_pro.MainActivity");
                Thread.sleep(4000);
            }
            if (taskIdOf("com.tencent.mm") <= 0) {
                ui("启动微信…");
                sh("am start --windowingMode 5 -n com.tencent.mm/.ui.LauncherUI");
                Thread.sleep(3000);
            }

            // 自己也进自由窗口
            sh("am start --windowingMode 5 -n com.slh.app/.MainActivity");
            Thread.sleep(1200);

            int a = (int) (h * 0.40);
            int c = (int) (h * 0.76);
            int wx = taskIdOf("com.tencent.mm");
            int slh = taskIdOf("com.slh_mobile_pro");
            int me = taskIdOf("com.slh.app");
            if (wx > 0) sh("am task resize " + wx + " 0 0 " + w + " " + a);
            if (slh > 0) sh("am task resize " + slh + " 0 " + a + " " + w + " " + c);
            if (me > 0) sh("am task resize " + me + " 0 " + c + " " + w + " " + h);
            Thread.sleep(600);

            // 最后把两个 App 提到前面（它们各自在自己的区域里）
            sh("am start --windowingMode 5 -n com.tencent.mm/.ui.LauncherUI");
            Thread.sleep(900);
            sh("am start --windowingMode 5 -n com.slh_mobile_pro/com.slh_mobile_pro.MainActivity");
            Thread.sleep(900);
            ui("已启动：微信上 / 商陆花中 / 队列下");
        } catch (Throwable t) { ui("启动失败: " + t); }
    }

    private android.view.View exitBtn = null;

    private void fullscreen(String pkg, String comp) {
        try {
            int w = getResources().getDisplayMetrics().widthPixels;
            int h = getResources().getDisplayMetrics().heightPixels;
            sh("am start --windowingMode 5 -n " + comp);
            Thread.sleep(900);
            int t = taskIdOf(pkg);
            if (t > 0) sh("am task resize " + t + " 0 0 " + w + " " + h);
            Thread.sleep(500);
            ui("已全屏，点右上角 × 返回");
            showExit();
        } catch (Throwable e) { ui("全屏失败: " + e); }
    }

    private void restoreThree() {
        hideExit();
        try {
            int w = getResources().getDisplayMetrics().widthPixels;
            int h = getResources().getDisplayMetrics().heightPixels;
            int a = (int) (h * 0.40), c = (int) (h * 0.76);
            int wx = taskIdOf("com.tencent.mm");
            int slh = taskIdOf("com.slh_mobile_pro");
            int me = taskIdOf("com.slh.app");
            if (wx > 0) sh("am task resize " + wx + " 0 0 " + w + " " + a);
            if (slh > 0) sh("am task resize " + slh + " 0 " + a + " " + w + " " + c);
            if (me > 0) sh("am task resize " + me + " 0 " + c + " " + w + " " + h);
            Thread.sleep(500);
            ui("已回到三段");
        } catch (Throwable e) { ui("恢复失败: " + e); }
    }

    private void showExit() {
        runOnUiThread(() -> {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName())));
                return;
            }
            if (exitBtn != null) return;
            android.view.WindowManager wm =
                    (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
            TextView v = new TextView(this);
            v.setText("×");
            v.setTextSize(22);
            v.setTextColor(Color.WHITE);
            v.setGravity(android.view.Gravity.CENTER);
            v.setBackgroundColor(0xCC333333);
            android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                    dp(44), dp(44), 2038,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    android.graphics.PixelFormat.TRANSLUCENT);
            lp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
            lp.x = dp(10); lp.y = dp(60);
            v.setOnClickListener(x -> new Thread(this::restoreThree).start());
            try { wm.addView(v, lp); exitBtn = v; } catch (Throwable ignored) {}
        });
    }

    private void hideExit() {
        runOnUiThread(() -> {
            if (exitBtn == null) return;
            try {
                ((android.view.WindowManager) getSystemService(WINDOW_SERVICE)).removeView(exitBtn);
            } catch (Throwable ignored) {}
            exitBtn = null;
        });
    }

    private void stopLayout() {
        try {
            ui("正在停止…");
            sh("am force-stop com.tencent.mm");
            sh("am force-stop com.slh_mobile_pro");
            int me = taskIdOf("com.slh.app");
            int w = getResources().getDisplayMetrics().widthPixels;
            int h = getResources().getDisplayMetrics().heightPixels;
            if (me > 0) sh("am task resize " + me + " 0 0 " + w + " " + h);
            ui("已停止");
        } catch (Throwable t) { ui("停止失败: " + t); }
    }
}
