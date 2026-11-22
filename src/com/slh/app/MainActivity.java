package com.slh.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {

    private TextView status;
    private EditText input;
    private LinearLayout logBox;
    private ScrollView logScroll;
    private ProxyCatcher catcher;
    private SharedPreferences sp;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sp = getSharedPreferences("slh", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        int pad = dp(10);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("入库助手");
        title.setTextSize(15);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(11);
        status.setTextColor(0xFF666666);
        status.setText("检查 Shizuku…");
        status.setPadding(0, dp(2), 0, dp(6));
        root.addView(status);

        input = new EditText(this);
        input.setHint("把微信笔记粘到这里");
        input.setTextSize(14);
        input.setMinLines(4);
        input.setMaxLines(8);
        input.setBackgroundColor(0xFFF5F5F6);
        input.setPadding(dp(8), dp(6), dp(8), dp(6));
        root.addView(input, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, dp(6));
        Button imp = new Button(this);
        imp.setText("导入");
        imp.setAllCaps(false);
        imp.setTextSize(15);
        imp.setOnClickListener(v -> new Thread(this::doImport).start());
        Button cap = new Button(this);
        cap.setText("抓会话");
        cap.setAllCaps(false);
        cap.setTextSize(15);
        cap.setOnClickListener(v -> new Thread(this::captureSession).start());
        LinearLayout.LayoutParams h = new LinearLayout.LayoutParams(0, -2, 1f);
        h.rightMargin = dp(6);
        imp.setLayoutParams(h);
        cap.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(imp);
        row.addView(cap);
        root.addView(row);

        logScroll = new ScrollView(this);
        logBox = new LinearLayout(this);
        logBox.setOrientation(LinearLayout.VERTICAL);
        logScroll.addView(logBox);
        logScroll.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(logScroll);

        setContentView(root);
        refreshStatus();
        ui("就绪");
    }

    // ---------- 日志 ----------
    private void log(String s) {
        try {
            java.io.File f = new java.io.File(getExternalFilesDir(null), "cap.log");
            java.io.FileWriter w = new java.io.FileWriter(f, true);
            w.write(new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date())
                    + " " + s + "\n");
            w.close();
        } catch (Throwable ignored) {}
        runOnUiThread(() -> {
            TextView t = new TextView(this);
            t.setText(s);
            t.setTextSize(12);
            t.setTextColor(0xFF333333);
            t.setPadding(0, dp(2), 0, dp(2));
            logBox.addView(t);
            logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private void ui(String s) { runOnUiThread(() -> status.setText(s)); }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void refreshStatus() {
        try {
            if (!Shizuku.pingBinder()) { ui("Shizuku 未运行"); return; }
            ui(Shizuku.checkSelfPermission() == 0 ? "就绪（Shizuku 已授权）" : "Shizuku 未授权");
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

    // ---------- 会话 ----------
    private String captureSession() {
        try {
            log("抓会话：设置代理…");
            if (catcher != null) catcher.stop();
            catcher = new ProxyCatcher();
            catcher.start(SessionCatcher.PORT, sid -> {}, msg -> log(msg));
            sh("settings put global http_proxy 127.0.0.1:" + SessionCatcher.PORT);
            sh("am force-stop com.slh_mobile_pro");
            Thread.sleep(700);
            sh("am start -n com.slh_mobile_pro/com.slh_mobile_pro.MainActivity");
            long deadline = System.currentTimeMillis() + 150000;
            while (System.currentTimeMillis() < deadline && catcher.sid() == null) {
                Thread.sleep(400);
            }
            String sid = catcher.sid();
            sh("settings put global http_proxy :0");
            catcher.stop();
            if (sid != null) {
                sp.edit().putString("sid", sid).apply();
                log("会话已更新：" + sid.substring(0, Math.min(20, sid.length())) + "…");
            } else {
                log("没抓到会话（商陆花没联网？）");
            }
            return sid;
        } catch (Throwable t) {
            sh("settings put global http_proxy :0");
            log("抓会话失败：" + t);
            return null;
        }
    }

    private String session() {
        String sid = sp.getString("sid", null);
        if (sid == null || sid.isEmpty()) sid = captureSession();
        return sid;
    }

    // ---------- 导入 ----------
    private void doImport() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) { log("先把笔记粘进来"); return; }
        List<String> notes = splitNotes(text);
        log("共 " + notes.size() + " 条");
        try {
            runImport(notes);
        } catch (SlhApi.SessionExpired e) {
            log("会话失效，自动重连…");
            if (captureSession() != null) {
                try {
                    runImport(notes);
                } catch (Exception e2) {
                    log("重连后仍失败：" + e2);
                }
            }
        } catch (Exception e) {
            log("出错：" + e);
        }
    }

    private List<String> splitNotes(String text) {
        List<String> out = new ArrayList<>();
        String[] parts = text.split("(?m)(?=(?:【\\s*款号\\s*】|^\\s*款号\\s*:?))");
        for (String p : parts) {
            if (!p.trim().isEmpty()) out.add(p.trim());
        }
        return out;
    }

    private void runImport(List<String> notes) throws Exception {
        String sid = session();
        if (sid == null) { log("没有会话，先点「抓会话」"); return; }
        SlhApi api = new SlhApi(sid);
        ui("读取基础数据…");
        Map<String, JSONObject> colors = api.colors();
        Map<String, JSONObject> sizes = api.sizes();
        List<String> codes = api.codes();
        log("颜色 " + colors.size() + " 个，尺码 " + sizes.size() + " 个，货品 " + codes.size() + " 条");

        int ok = 0, skip = 0, fail = 0;
        for (String note : notes) {
            NoteParser n = NoteParser.parse(note);
            StringBuilder lack = new StringBuilder();
            if (n.no.isEmpty()) lack.append("款号 ");
            if (n.name.isEmpty()) lack.append("名称 ");
            if (n.colors.isEmpty()) lack.append("颜色 ");
            if (lack.length() > 0) {
                log("⚠ " + (n.no.isEmpty() ? "?" : n.no) + " 缺少必填项：" + lack);
                fail++;
                continue;
            }
            if (codes.contains(n.no)) { log("⏭ " + n.no + " 已存在"); skip++; continue; }

            List<String> miss = new ArrayList<>();
            for (String c : n.colors) if (!colors.containsKey(c)) miss.add(c);
            if (!miss.isEmpty()) {
                log("新建颜色：" + Text.join(miss));
                for (String c : miss) {
                    try { api.createColor(c); } catch (Exception ignored) {}
                }
                colors = api.colors();
                List<String> still = new ArrayList<>();
                for (String c : n.colors) if (!colors.containsKey(c)) still.add(c);
                if (!still.isEmpty()) {
                    log("✘ " + n.no + " 颜色建失败：" + Text.join(still));
                    fail++;
                    continue;
                }
            }

            List<String> cids = new ArrayList<>();
            for (String c : n.colors) cids.add(colors.get(c).optString("sid"));
            List<String> sids = new ArrayList<>();
            for (String s : n.sizes) {
                if (sizes.containsKey(s)) sids.add(sizes.get(s).optString("sid"));
                else if (sizes.containsKey("均码")) sids.add(sizes.get("均码").optString("sid"));
            }

            JSONObject r = api.createStyle(n.no, n.name, cids, sids, "");
            if (r.has("val")) {
                log("✔ " + n.no + " → id " + r.optString("val") + "（" + cids.size() + " 色）");
                codes.add(n.no);
                ok++;
            } else if (r.toString().contains("already_exist")) {
                log("⏭ " + n.no + " 已存在");
                skip++;
            } else {
                log("✘ " + n.no + " 失败：" + r.toString());
                fail++;
            }
        }
        ui("完成：成功 " + ok + " / 跳过 " + skip + " / 失败 " + fail);
    }
}
