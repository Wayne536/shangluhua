package com.slh.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 导入预览：先看解析结果，能改字段，再真正导入 */
public class PreviewImportActivity extends Activity {

    private static final int ST_NEW = 0, ST_EXISTS = 1, ST_MISSING = 2, ST_UNKNOWN = 3;

    private static class Item {
        String no = "", name = "", colors = "", sizes = "", price = "";
        int status = ST_UNKNOWN;
    }

    private final List<Item> items = new ArrayList<>();
    private LinearLayout list;
    private TextView summary;
    private String sid;
    private boolean checked;
    private Map<String, JSONObject> colorMap;
    private List<String> codes;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xFFFFFFFF);
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF6F7F9);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(10), dp(10), dp(10));
        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextSize(15);
        cancel.setTextColor(0xFF555555);
        cancel.setPadding(dp(6), dp(6), dp(6), dp(6));
        cancel.setOnClickListener(v -> finish());
        bar.addView(cancel);

        TextView title = new TextView(this);
        title.setText("导入预览");
        title.setTextSize(16);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextColor(0xFF111111);
        title.setGravity(Gravity.CENTER);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView go = new TextView(this);
        go.setText("开始导入");
        go.setTextSize(15);
        go.setTypeface(Typeface.DEFAULT_BOLD);
        go.setTextColor(0xFF2563EB);
        go.setPadding(dp(10), dp(6), dp(6), dp(6));
        go.setOnClickListener(v -> confirmStart());
        bar.addView(go);
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        summary = new TextView(this);
        summary.setTextSize(12);
        summary.setTextColor(0xFF666666);
        summary.setPadding(dp(16), dp(2), dp(16), dp(8));
        root.addView(summary, new LinearLayout.LayoutParams(-1, -2));

        ScrollView sc = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), 0, dp(12), dp(20));
        sc.addView(list);
        root.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);

        sid = getIntent() == null ? null : getIntent().getStringExtra("sid");
        ArrayList<String> notes = getIntent() == null ? null
                : getIntent().getStringArrayListExtra("notes");
        if (notes != null) {
            for (String n : notes) {
                NoteParser p = NoteParser.parse(n);
                Item it = new Item();
                it.no = p.no;
                it.name = p.name;
                it.colors = join(p.colors);
                it.sizes = join(p.sizes);
                it.price = p.price;
                items.add(it);
            }
        }
        render();
        checkServer();
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) sb.append('/');
            sb.append(s);
        }
        return sb.toString();
    }

    private void checkServer() {
        if (sid == null || sid.isEmpty()) {
            summary.setText("没有会话，只做了格式检查：共 " + items.size() + " 条");
            return;
        }
        new Thread(() -> {
            try {
                SlhApi api = new SlhApi(sid);
                Map<String, JSONObject> cm = api.colors();
                List<String> cs = api.codes();
                runOnUiThread(() -> {
                    colorMap = cm;
                    codes = cs;
                    checked = true;
                    render();
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    summary.setText("没连上商陆花（" + t.getMessage() + "），只做了格式检查");
                });
            }
        }).start();
    }

    private void render() {
        list.removeAllViews();
        int nNew = 0, nExist = 0, nMiss = 0, nColor = 0;
        LinkedHashSet<String> newColors = new LinkedHashSet<>();
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (it.no.isEmpty() || it.name.isEmpty() || it.colors.isEmpty()) {
                it.status = ST_MISSING;
            } else if (checked && codes != null && codes.contains(it.no)) {
                it.status = ST_EXISTS;
            } else {
                it.status = checked ? ST_NEW : ST_UNKNOWN;
            }
            if (it.status == ST_MISSING) nMiss++;
            else if (it.status == ST_EXISTS) nExist++;
            else if (it.status == ST_NEW) nNew++;

            if (checked && colorMap != null && it.status != ST_MISSING) {
                for (String c : it.colors.split("/")) {
                    String c2 = c.trim();
                    if (c2.isEmpty() || colorMap.containsKey(c2)) continue;
                    if (ColorMatch.resolve(c2, colorMap.keySet()) == null) newColors.add(c2);
                }
            }
            list.addView(row(i, it));
        }
        nColor = newColors.size();
        if (!checked) {
            summary.setText("共 " + items.size() + " 条 · 只做了格式检查");
        } else {
            summary.setText("共 " + items.size() + " 条 · 将新建 " + nNew + " · 已存在 " + nExist
                    + " · 缺字段 " + nMiss + (nColor > 0 ? " · 新建颜色 " + nColor : ""));
        }
    }

    private View row(final int idx, final Item it) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        card.setLayoutParams(lp);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView no = new TextView(this);
        no.setText((idx + 1) + ". " + (it.no.isEmpty() ? "（缺款号）" : it.no));
        no.setTextSize(15);
        no.setTypeface(Typeface.DEFAULT_BOLD);
        no.setTextColor(0xFF111111);
        head.addView(no);
        head.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        head.addView(chip(it.status));
        card.addView(head);

        TextView name = new TextView(this);
        name.setText(it.name.isEmpty() ? "（缺名称）" : it.name);
        name.setTextSize(13);
        name.setTextColor(it.name.isEmpty() ? 0xFFD32F2F : 0xFF444444);
        name.setPadding(0, dp(3), 0, 0);
        card.addView(name);

        TextView rest = new TextView(this);
        rest.setText("颜色 " + (it.colors.isEmpty() ? "（缺）" : it.colors)
                + " · 尺码 " + it.sizes
                + (it.price.isEmpty() ? " · 用统一进价" : " · 进价 " + it.price));
        rest.setTextSize(12);
        rest.setTextColor(0xFF777777);
        rest.setPadding(0, dp(2), 0, 0);
        card.addView(rest);

        card.setOnClickListener(v -> edit(idx));
        return card;
    }

    private View chip(int st) {
        String text;
        int bg, fg;
        switch (st) {
            case ST_EXISTS:
                text = "已存在";
                bg = 0xFFE8EAED;
                fg = 0xFF666666;
                break;
            case ST_MISSING:
                text = "缺字段";
                bg = 0xFFFDE7E7;
                fg = 0xFFD32F2F;
                break;
            case ST_NEW:
                text = "将新建";
                bg = 0xFFE3F2FD;
                fg = 0xFF2563EB;
                break;
            default:
                text = "未检查";
                bg = 0xFFE8EAED;
                fg = 0xFF666666;
        }
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(11);
        t.setTextColor(fg);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(9));
        g.setColor(bg);
        t.setBackground(g);
        t.setPadding(dp(8), dp(3), dp(8), dp(3));
        return t;
    }

    private void edit(final int idx) {
        final Item it = items.get(idx);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText e1 = field("款号", it.no);
        final EditText e2 = field("名称", it.name);
        final EditText e3 = field("颜色（用 / 分隔）", it.colors);
        final EditText e4 = field("尺码（用 / 分隔）", it.sizes);
        final EditText e5 = field("进货价（留空=用统一价）", it.price);
        box.addView(e1);
        box.addView(e2);
        box.addView(e3);
        box.addView(e4);
        box.addView(e5);
        new AlertDialog.Builder(this)
                .setTitle("修改第 " + (idx + 1) + " 条")
                .setView(box)
                .setPositiveButton("确定", (d, w) -> {
                    it.no = e1.getText().toString().trim();
                    it.name = e2.getText().toString().trim();
                    it.colors = e3.getText().toString().trim();
                    it.sizes = e4.getText().toString().trim();
                    it.price = e5.getText().toString().trim();
                    render();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private EditText field(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setTextSize(14);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        e.setPadding(dp(4), dp(6), dp(4), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(4);
        e.setLayoutParams(lp);
        return e;
    }

    private void confirmStart() {
        int miss = 0;
        for (Item it : items) {
            if (it.no.isEmpty() || it.name.isEmpty() || it.colors.isEmpty()) miss++;
        }
        if (miss > 0) {
            new AlertDialog.Builder(this)
                    .setTitle("有 " + miss + " 条缺必填")
                    .setMessage("这些会被跳过，其余照常导入。继续吗？")
                    .setPositiveButton("继续", (d, w) -> startImport())
                    .setNegativeButton("返回修改", null)
                    .show();
        } else {
            startImport();
        }
    }

    private void startImport() {
        ArrayList<String> out = new ArrayList<>();
        for (Item it : items) {
            String note = "【款号】" + it.no + "\n【名称】" + it.name
                    + "\n【颜色】" + it.colors + "\n【尺码】" + (it.sizes.isEmpty() ? "均码" : it.sizes);
            if (!it.price.isEmpty()) note += "\n【进货价】" + it.price;
            out.add(note);
        }
        android.content.Intent data = new android.content.Intent();
        data.putStringArrayListExtra("notes", out);
        setResult(RESULT_OK, data);
        finish();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
