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
    private android.widget.EditText priceInput;
    private final java.util.List<android.net.Uri> images = new java.util.ArrayList<>();
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

        priceInput = new android.widget.EditText(this);
        priceInput.setHint("进货价（可选，填了所有新建货品都带上）");
        priceInput.setTextSize(13);
        priceInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        priceInput.setBackgroundColor(0xFFF5F5F6);
        priceInput.setPadding(dp(8), dp(4), dp(8), dp(4));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.topMargin = dp(4);
        root.addView(priceInput, plp);

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

        Button pick = new Button(this);
        pick.setText("选图");
        pick.setAllCaps(false);
        pick.setOnClickListener(v -> {
            android.content.Intent i = new android.content.Intent(
                    android.content.Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(android.content.Intent.createChooser(i, "选择图片"), 2001);
        });
        LinearLayout.LayoutParams h = new LinearLayout.LayoutParams(0, -2, 1f);
        h.rightMargin = dp(6);
        imp.setLayoutParams(h);
        cap.setLayoutParams(h);
        pick.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(imp);
        row.addView(cap);
        row.addView(pick);
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
            log("启动代理…");
            if (catcher != null) catcher.stop();
            catcher = new ProxyCatcher();
            catcher.start(SessionCatcher.PORT, sid -> {}, msg -> log(msg));

            sh("settings put global http_proxy 127.0.0.1:" + SessionCatcher.PORT);
            log("请切到商陆花 → 打开「货品」页面，再点一下刷新");
            long deadline = System.currentTimeMillis() + 120000;
            while (System.currentTimeMillis() < deadline && catcher.sid() == null) {
                Thread.sleep(400);
            }
            String sid = catcher.sid();
            sh("settings put global http_proxy :0");
            catcher.stop();
            if (sid != null) {
                sp.edit().putString("sid", sid).apply();
                log("✅ 会话已更新：" + sid.substring(0, Math.min(22, sid.length())) + "…");
            } else {
                log("❌ 没抓到：确认商陆花已打开货品页并刷新，再点一次抓会话");
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
        Map<String, JSONObject> colors;
        try {
            colors = api.colors();
        } catch (SlhApi.SessionExpired e) {
            log("会话失效，自动重新抓取…");
            sid = captureSession();
            if (sid == null) { log("重抓失败，请手动点「抓会话」"); return; }
            api = new SlhApi(sid);
            colors = api.colors();
        }
        Map<String, JSONObject> sizes = api.sizes();
        List<String> codes = api.codes();
        log("颜色 " + colors.size() + " 个，尺码 " + sizes.size() + " 个，货品 " + codes.size() + " 条");

        String fileid = "";
        if (!images.isEmpty()) {
            ui("上传图片…");
            java.util.List<String> ids = new java.util.ArrayList<>();
            int max = Math.min(9, images.size());
            for (int i = 0; i < max; i++) {
                try {
                    java.io.InputStream in = getContentResolver().openInputStream(images.get(i));
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    in.close();
                    String fid = api.uploadImage(bo.toByteArray(), "IMG_" + (i + 1) + ".jpg");
                    if (fid.matches("\\d+")) { ids.add(fid); }
                    else { log("图片 " + (i + 1) + " 返回异常：" + fid.substring(0, Math.min(40, fid.length()))); }
                } catch (Exception e) {
                    log("图片 " + (i + 1) + " 上传失败：" + e);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (String id : ids) { if (sb.length() > 0) sb.append(','); sb.append(id); }
            fileid = sb.toString();
            log("图片 " + ids.size() + "/" + max + " 张已上传");
        }

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

            String pp = priceInput.getText().toString().trim();
            JSONObject r = api.createStyle(n.no, n.name, cids, sids, fileid, pp);
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

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 2001 && res == RESULT_OK && data != null) {
            images.clear();
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                for (int i = 0; i < n && i < 9; i++) {
                    images.add(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                images.add(data.getData());
            }
            log("已选 " + images.size() + " 张图");
        }
    }
}
