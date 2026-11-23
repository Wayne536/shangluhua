package com.slh.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {

    private static final int REQ_IMAGES = 2001;
    private static final int REQ_SHIZUKU = 1001;
    private static final int LV_INFO = 0, LV_OK = 1, LV_WARN = 2, LV_ERR = 3;

    private SharedPreferences sp;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText input, priceInput;
    private TextView statusPill, countText, imageCount, pasteBtn, clearTextBtn;
    private View importBtn, captureBtn;
    private TextView captureLabel;
    private TextView pickBtn, clearImgBtn;
    private TextView resultOk, resultSkip, resultFail;
    private View progress;
    private ScrollView scroll;
    private LinearLayout content, logBox, thumbRow, resultCard;
    private HorizontalScrollView thumbScroll;
    private TextView snackbar, logEmpty;

    private final List<Uri> images = new ArrayList<>();
    private ProxyCatcher catcher;
    private volatile boolean busy;
    private volatile boolean capturing, cancelCapture;

    private final Shizuku.OnRequestPermissionResultListener permListener =
            (requestCode, grantResult) -> {
                if (requestCode == REQ_SHIZUKU) {
                    refreshStatus();
                    snack(grantResult == 0 ? "Shizuku 已授权" : "未授权：抓会话需要 Shizuku 权限");
                }
            };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(col(R.color.bg));
        getWindow().setNavigationBarColor(col(R.color.bg));
        int uiFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            uiFlags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(uiFlags);
        sp = getSharedPreferences("slh", MODE_PRIVATE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(col(R.color.bg));

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        root.addView(main, new FrameLayout.LayoutParams(-1, -1));

        main.addView(buildTopBar());

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        ((ProgressBar) progress).setIndeterminate(true);
        ((ProgressBar) progress).setIndeterminateTintList(ColorStateList.valueOf(col(R.color.primary)));
        progress.setVisibility(View.GONE);
        main.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(6), dp(16), dp(28));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        main.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        buildNoteCard();
        buildOptionsCard();
        buildActions();
        buildResultCard();
        buildLog();

        snackbar = new TextView(this);
        snackbar.setTextSize(13);
        snackbar.setTextColor(Color.WHITE);
        snackbar.setBackgroundResource(R.drawable.bg_snack);
        snackbar.setPadding(dp(16), dp(11), dp(16), dp(11));
        snackbar.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.88));
        snackbar.setAlpha(0f);
        snackbar.setVisibility(View.GONE);
        snackbar.setElevation(dp(6));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-2, -2,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        slp.bottomMargin = dp(26);
        root.addView(snackbar, slp);

        setContentView(root);
        root.setFocusableInTouchMode(true);
        root.requestFocus();

        try {
            Shizuku.addRequestPermissionResultListener(permListener);
        } catch (Throwable ignored) {
        }

        restoreDraft();
        refreshStatus();

        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                int n = noteCount(s.toString());
                countText.setText(n == 0 ? "已识别 0 条" : "已识别 " + n + " 条");
                refreshActionState();
            }
        });
        int n0 = noteCount(input.getText().toString());
        countText.setText("已识别 " + n0 + " 条");
        refreshActionState();
    }

    // ================= 界面搭建 =================

    private LinearLayout buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(12), dp(14), dp(8));

        FrameLayout mark = new FrameLayout(this);
        GradientDrawable ib = new GradientDrawable();
        ib.setCornerRadius(dp(10));
        ib.setColor(col(R.color.primary));
        mark.setBackground(ib);
        ImageView markIcon = new ImageView(this);
        markIcon.setImageDrawable(icon(R.drawable.ic_upload, Color.WHITE, dp(19)));
        mark.addView(markIcon, new FrameLayout.LayoutParams(dp(19), dp(19), Gravity.CENTER));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(36), dp(36));
        ilp.rightMargin = dp(10);
        bar.addView(mark, ilp);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView t = text("入库助手", 19, col(R.color.text));
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titles.addView(t);
        TextView sub = text("把微信笔记录进商陆花", 11, col(R.color.muted));
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-2, -2);
        subLp.topMargin = dp(1);
        titles.addView(sub, subLp);
        bar.addView(titles);

        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        statusPill = text("检查中…", 11, col(R.color.primary));
        statusPill.setGravity(Gravity.CENTER);
        statusPill.setPadding(dp(11), dp(6), dp(11), dp(6));
        statusPill.setBackground(pillBg(col(R.color.primary_soft)));
        statusPill.setOnClickListener(v -> onStatusClick());

        ImageView gear = new ImageView(this);
        gear.setImageDrawable(icon(R.drawable.ic_settings, col(R.color.muted), dp(20)));
        gear.setBackgroundResource(R.drawable.bg_text_button);
        gear.setPadding(dp(9), dp(9), dp(9), dp(9));
        gear.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(38), dp(38));
        glp.rightMargin = dp(4);
        bar.addView(gear, glp);
        bar.addView(statusPill);
        return bar;
    }

    private void buildNoteCard() {
        LinearLayout card = card();

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(sectionLabel("微信笔记"));
        head.addView(spacer(), new LinearLayout.LayoutParams(0, 1, 1f));
        pasteBtn = textButton("粘贴", R.drawable.ic_paste);
        pasteBtn.setOnClickListener(v -> pasteClipboard());
        head.addView(pasteBtn);
        clearTextBtn = textButton("清空", 0);
        clearTextBtn.setOnClickListener(v -> {
            input.setText("");
            snack("已清空笔记");
        });
        head.addView(clearTextBtn);
        card.addView(head);

        input = new EditText(this);
        input.setHint("粘贴群里的商品笔记，支持一次多条…");
        input.setHintTextColor(col(R.color.hint));
        input.setTextColor(col(R.color.text));
        input.setTextSize(15);
        input.setBackgroundResource(R.drawable.bg_input);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setMinLines(5);
        input.setMaxLines(10);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, -2);
        ilp.topMargin = dp(10);
        card.addView(input, ilp);

        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        countText = text("已识别 0 条", 11, col(R.color.muted));
        foot.addView(countText);
        foot.addView(spacer(), new LinearLayout.LayoutParams(0, 1, 1f));
        foot.addView(text("必填：款号 · 名称 · 颜色", 11, col(R.color.hint)));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
        flp.topMargin = dp(8);
        card.addView(foot, flp);

        content.addView(card);
    }

    private void buildOptionsCard() {
        LinearLayout card = card();

        LinearLayout priceRow = new LinearLayout(this);
        priceRow.setOrientation(LinearLayout.HORIZONTAL);
        priceRow.setGravity(Gravity.CENTER_VERTICAL);
        priceRow.addView(sectionLabel("进货价"));
        priceRow.addView(spacer(), new LinearLayout.LayoutParams(0, 1, 1f));

        priceInput = new EditText(this);
        priceInput.setHint("0.00");
        priceInput.setHintTextColor(col(R.color.hint));
        priceInput.setTextColor(col(R.color.text));
        priceInput.setTextSize(14);
        priceInput.setGravity(Gravity.CENTER);
        priceInput.setSingleLine(true);
        priceInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        priceInput.setBackgroundResource(R.drawable.bg_input);
        priceInput.setPadding(dp(10), 0, dp(10), 0);
        priceRow.addView(priceInput, new LinearLayout.LayoutParams(dp(104), dp(38)));
        TextView yuan = text(" 元/件", 12, col(R.color.muted));
        priceRow.addView(yuan);
        card.addView(priceRow);

        View div = new View(this);
        div.setBackgroundColor(col(R.color.border));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, dp(1));
        dlp.topMargin = dp(12);
        dlp.bottomMargin = dp(12);
        card.addView(div, dlp);

        LinearLayout imgRow = new LinearLayout(this);
        imgRow.setOrientation(LinearLayout.HORIZONTAL);
        imgRow.setGravity(Gravity.CENTER_VERTICAL);
        imgRow.addView(sectionLabel("图片"));
        imageCount = text("未选", 12, col(R.color.muted));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.leftMargin = dp(8);
        imgRow.addView(imageCount, clp);
        imgRow.addView(spacer(), new LinearLayout.LayoutParams(0, 1, 1f));

        clearImgBtn = textButton("清空", 0);
        clearImgBtn.setVisibility(View.GONE);
        clearImgBtn.setOnClickListener(v -> {
            images.clear();
            rebuildThumbs();
            snack("已清空图片");
        });
        imgRow.addView(clearImgBtn);

        pickBtn = smallButton("选择图片", R.drawable.ic_image);
        pickBtn.setOnClickListener(v -> pickImages());
        imgRow.addView(pickBtn);
        card.addView(imgRow);

        thumbScroll = new HorizontalScrollView(this);
        thumbScroll.setHorizontalScrollBarEnabled(false);
        thumbScroll.setVisibility(View.GONE);
        thumbRow = new LinearLayout(this);
        thumbRow.setOrientation(LinearLayout.HORIZONTAL);
        thumbScroll.addView(thumbRow, new HorizontalScrollView.LayoutParams(-2, -2));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(10);
        card.addView(thumbScroll, tlp);

        TextView hint = text("最多 9 张 · 长按缩略图可移除", 11, col(R.color.hint));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
        hlp.topMargin = dp(8);
        card.addView(hint, hlp);

        content.addView(card);
    }

    private void buildActions() {
        importBtn = bigButton("导入到商陆花", R.drawable.ic_upload, true);
        importBtn.setOnClickListener(v -> doImport());
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(-1, dp(52));
        content.addView(importBtn, lp1);

        captureBtn = bigButton("抓取登录会话", R.drawable.ic_sync, false);
        captureLabel = (TextView) ((LinearLayout) captureBtn).getChildAt(1);
        captureBtn.setOnClickListener(v -> onCaptureClick());
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-1, dp(48));
        lp2.topMargin = dp(10);
        content.addView(captureBtn, lp2);

        TextView vpnHint = text("抓会话前请先关闭梯子 / VPN", 11, col(R.color.hint));
        vpnHint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(-1, -2);
        lp3.topMargin = dp(6);
        content.addView(vpnHint, lp3);
    }

    private void buildResultCard() {
        resultCard = card();
        resultCard.setVisibility(View.GONE);
        TextView title = sectionLabel("本次结果");
        title.setTextSize(12);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.bottomMargin = dp(8);
        resultCard.addView(title, tlp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        resultOk = metricCell(row, "成功", R.color.success);
        resultSkip = metricCell(row, "跳过", R.color.warn);
        resultFail = metricCell(row, "失败", R.color.error);
        resultCard.addView(row);
        content.addView(resultCard);
    }

    private void buildLog() {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(sectionLabel("运行日志"));
        head.addView(spacer(), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView clear = textButton("清空日志", 0);
        clear.setOnClickListener(v -> clearLog());
        head.addView(clear);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = dp(18);
        hlp.bottomMargin = dp(8);
        content.addView(head, hlp);

        LinearLayout logCard = new LinearLayout(this);
        logCard.setOrientation(LinearLayout.VERTICAL);
        logCard.setBackgroundResource(R.drawable.bg_card);
        logCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        logCard.setMinimumHeight(dp(110));
        logBox = new LinearLayout(this);
        logBox.setOrientation(LinearLayout.VERTICAL);
        logCard.addView(logBox, new LinearLayout.LayoutParams(-1, -2));
        content.addView(logCard);
        addEmptyLog();
    }

    private void addEmptyLog() {
        logEmpty = new TextView(this);
        logEmpty.setText("暂无记录\n粘贴笔记后点「导入」，每条的结果会显示在这里");
        logEmpty.setTextSize(12);
        logEmpty.setTextColor(col(R.color.hint));
        logEmpty.setLineSpacing(dp(3), 1f);
        logBox.addView(logEmpty);
    }

    private void clearLog() {
        logBox.removeAllViews();
        addEmptyLog();
        try {
            File f = new File(getExternalFilesDir(null), "cap.log");
            if (f.exists()) new FileWriter(f, false).close();
        } catch (Throwable ignored) {}
        snack("日志已清空");
    }

    // ================= 组件工厂 =================

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

    private TextView sectionLabel(String s) {
        TextView t = text(s, 13, col(R.color.text));
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private View spacer() {
        return new View(this);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.bg_card);
        c.setPadding(dp(14), dp(13), dp(14), dp(13));
        c.setElevation(dp(1));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(12);
        c.setLayoutParams(p);
        return c;
    }

    private Drawable icon(int res, int color, int size) {
        Drawable d = getResources().getDrawable(res, getTheme()).mutate();
        d.setTint(color);
        d.setBounds(0, 0, size, size);
        return d;
    }

    private LinearLayout bigButton(String label, int iconRes, boolean primary) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundResource(primary ? R.drawable.bg_primary_button : R.drawable.bg_secondary_button);
        box.setClickable(true);
        box.setFocusable(true);
        int fg = primary ? Color.WHITE : col(R.color.primary);
        ImageView ic = new ImageView(this);
        ic.setImageDrawable(icon(iconRes, fg, dp(19)));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(19), dp(19));
        ilp.rightMargin = dp(9);
        box.addView(ic, ilp);
        TextView t = text(label, primary ? 16 : 15, fg);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        box.addView(t);
        return box;
    }

    private TextView smallButton(String label, int iconRes) {
        TextView b = text(label, 12, col(R.color.primary));
        b.setGravity(Gravity.CENTER);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackgroundResource(R.drawable.bg_secondary_button);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setCompoundDrawablesRelative(
                icon(iconRes, col(R.color.primary), dp(14)), null, null, null);
        b.setCompoundDrawablePadding(dp(5));
        b.setClickable(true);
        b.setFocusable(true);
        b.setLayoutParams(new LinearLayout.LayoutParams(-2, dp(34)));
        return b;
    }

    private TextView textButton(String label, int iconRes) {
        TextView b = text(label, 12, col(R.color.primary));
        b.setGravity(Gravity.CENTER);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackgroundResource(R.drawable.bg_text_button);
        b.setPadding(dp(9), dp(7), dp(9), dp(7));
        if (iconRes != 0) {
            b.setCompoundDrawablesRelative(
                    icon(iconRes, col(R.color.primary), dp(14)), null, null, null);
            b.setCompoundDrawablePadding(dp(4));
        }
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private TextView metricCell(LinearLayout row, String label, int colorRes) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        TextView value = text("0", 24, col(colorRes));
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        cell.addView(value);
        TextView l = text(label, 11, col(R.color.muted));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.topMargin = dp(2);
        cell.addView(l, llp);
        row.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
        return value;
    }

    private GradientDrawable pillBg(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(20));
        return g;
    }

    // ================= 状态 =================

    private void setPill(String s, int fg, int bg) {
        statusPill.setText(s);
        statusPill.setTextColor(fg);
        statusPill.setBackground(pillBg(bg));
    }

    private void refreshStatus() {
        ui.post(() -> {
            if (busy) return;
            try {
                if (!Shizuku.pingBinder()) {
                    setPill("● Shizuku 未运行", col(R.color.error), col(R.color.error_soft));
                    return;
                }
                if (Shizuku.checkSelfPermission() != 0) {
                    setPill("● 待授权", col(R.color.warn), col(R.color.warn_soft));
                    return;
                }
                String sid = sp.getString("sid", "");
                if (sid == null || sid.isEmpty()) {
                    setPill("● 待抓会话", col(R.color.primary), col(R.color.primary_soft));
                } else {
                    setPill("● 已就绪", col(R.color.success), col(R.color.success_soft));
                }
            } catch (Throwable t) {
                setPill("● 状态未知", col(R.color.warn), col(R.color.warn_soft));
            }
        });
    }

    private void onStatusClick() {
        try {
            if (!Shizuku.pingBinder()) {
                snack("Shizuku 没在运行，请先到系统里启动 Shizuku");
                return;
            }
            if (Shizuku.checkSelfPermission() != 0) {
                snack("请在弹窗里允许「入库助手」");
                Shizuku.requestPermission(REQ_SHIZUKU);
                return;
            }
            refreshStatus();
            snack(sp.getString("sid", "").isEmpty()
                    ? "还没有会话，点「抓取登录会话」"
                    : "会话已保存，状态正常");
        } catch (Throwable t) {
            snack("Shizuku 状态异常：" + t.getMessage());
        }
    }

    private void setBusy(boolean b, String label) {
        busy = b;
        ui.post(() -> {
            progress.setVisibility(b ? View.VISIBLE : View.GONE);
            pickBtn.setEnabled(!b);
            pasteBtn.setEnabled(!b);
            clearImgBtn.setEnabled(!b);
            refreshCaptureUi();
            pickBtn.setAlpha(b ? 0.45f : 1f);
            pasteBtn.setAlpha(b ? 0.45f : 1f);
            clearImgBtn.setAlpha(b ? 0.45f : 1f);
            if (b) {
                setPill("● " + label, col(R.color.primary), col(R.color.primary_soft));
            } else {
                refreshStatus();
            }
            refreshActionState();
        });
    }

    private void statusBusy(String label) {
        if (!busy) return;
        ui.post(() -> {
            if (busy) setPill("● " + label, col(R.color.primary), col(R.color.primary_soft));
        });
    }

    private void refreshActionState() {
        boolean has = noteCount(input.getText().toString()) > 0;
        boolean on = has && !busy;
        importBtn.setEnabled(on);
        importBtn.setAlpha(on ? 1f : 0.45f);
    }

    private void restoreDraft() {
        String draft = sp.getString("draft", "");
        if (!draft.isEmpty()) {
            input.setText(draft);
            input.setSelection(draft.length());
        }
        String p = sp.getString("price", "");
        if (!p.isEmpty()) priceInput.setText(p);
    }

    @Override
    protected void onPause() {
        super.onPause();
        sp.edit()
                .putString("draft", input.getText().toString())
                .putString("price", priceInput.getText().toString())
                .apply();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            Shizuku.removeRequestPermissionResultListener(permListener);
        } catch (Throwable ignored) {}
        // 抓会话途中退出时，别把系统代理留在 127.0.0.1，否则整机没网
        final ProxyCatcher c = catcher;
        if (c != null) {
            c.stop();
            new Thread(() -> sh("settings put global http_proxy :0")).start();
        }
    }

    // ================= 提示 / 日志 =================

    private final Runnable hideSnack = () -> {
        if (snackbar == null) return;
        snackbar.animate().alpha(0f).translationY(dp(12)).setDuration(160)
                .withEndAction(() -> snackbar.setVisibility(View.GONE)).start();
    };

    private void snack(String s) {
        ui.post(() -> {
            snackbar.removeCallbacks(hideSnack);
            snackbar.setText(s);
            snackbar.setVisibility(View.VISIBLE);
            snackbar.setTranslationY(dp(12));
            snackbar.animate().alpha(1f).translationY(0f).setDuration(160).start();
            snackbar.postDelayed(hideSnack, 2800);
        });
    }

    private static String stripStatus(String s) {
        for (String p : new String[]{"✔ ", "✅ ", "⏭ ", "✘ ", "❌ ", "⚠ "}) {
            if (s.startsWith(p)) return s.substring(p.length());
        }
        return s;
    }

    private int levelOf(String s) {
        if (s.startsWith("✔") || s.startsWith("✅")) return LV_OK;
        if (s.startsWith("⚠") || s.startsWith("⏭")) return LV_WARN;
        if (s.startsWith("✘") || s.startsWith("❌")) return LV_ERR;
        return LV_INFO;
    }

    private int levelColor(int lv) {
        switch (lv) {
            case LV_OK: return col(R.color.success);
            case LV_WARN: return col(R.color.warn);
            case LV_ERR: return col(R.color.error);
            default: return col(R.color.muted);
        }
    }

    private void log(String s) {
        writeLogFile(s);
        int lv = levelOf(s);
        ui.post(() -> addLogRow(s, lv));
    }

    private void writeLogFile(String s) {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) return;
            FileWriter w = new FileWriter(new File(dir, "cap.log"), true);
            w.write(new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date())
                    + " " + s + "\n");
            w.close();
        } catch (Throwable ignored) {}
    }

    private void addLogRow(String msg, int lv) {
        if (logEmpty != null && logEmpty.getParent() == logBox) {
            logBox.removeView(logEmpty);
        }
        int maxScroll = Math.max(0, content.getHeight() - scroll.getHeight());
        boolean atBottom = scroll.getScrollY() >= maxScroll - dp(180);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(4), 0, dp(4));

        View dot = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(levelColor(lv));
        dot.setBackground(g);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(6), dp(6));
        dlp.topMargin = dp(6);
        dlp.rightMargin = dp(8);
        row.addView(dot, dlp);

        TextView m = text(stripStatus(msg), 12, col(R.color.text_body));
        m.setLineSpacing(dp(2), 1f);
        row.addView(m, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView tm = text(new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date()),
                10, col(R.color.hint));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.topMargin = dp(2);
        tlp.leftMargin = dp(8);
        row.addView(tm, tlp);

        logBox.addView(row);
        while (logBox.getChildCount() > 200) logBox.removeViewAt(0);

        if (atBottom) {
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    // ================= 剪贴板 / 图片 =================

    private void pasteClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null
                    || cm.getPrimaryClip().getItemCount() == 0) {
                snack("剪贴板是空的");
                return;
            }
            ClipData clip = cm.getPrimaryClip();
            CharSequence cs = clip.getItemAt(0).coerceToText(this);
            String s = cs == null ? "" : cs.toString().trim();
            if (s.isEmpty()) {
                snack("剪贴板是空的");
                return;
            }
            String old = input.getText().toString().trim();
            input.setText(old.isEmpty() ? s : old + "\n" + s);
            input.setSelection(input.getText().length());
            snack("已粘贴");
        } catch (Throwable t) {
            snack("读取剪贴板失败：" + t.getMessage());
        }
    }

    private void pickImages() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(Intent.createChooser(i, "选择图片"), REQ_IMAGES);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_IMAGES || res != RESULT_OK || data == null) return;
        images.clear();
        if (data.getClipData() != null) {
            int n = data.getClipData().getItemCount();
            for (int i = 0; i < n && i < 9; i++) images.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            images.add(data.getData());
        }
        rebuildThumbs();
        log("已选 " + images.size() + " 张图");
    }

    private void rebuildThumbs() {
        ui.post(() -> {
            thumbRow.removeAllViews();
            if (images.isEmpty()) {
                thumbScroll.setVisibility(View.GONE);
                clearImgBtn.setVisibility(View.GONE);
                imageCount.setText("未选");
                return;
            }
            thumbScroll.setVisibility(View.VISIBLE);
            clearImgBtn.setVisibility(View.VISIBLE);
            imageCount.setText("已选 " + images.size() + " 张");

            for (final Uri u : images) {
                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackgroundResource(R.drawable.bg_thumb);
                iv.setClipToOutline(true);
                iv.setContentDescription("已选图片，长按移除");
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(52), dp(52));
                p.rightMargin = dp(8);
                iv.setOnLongClickListener(v -> {
                    images.remove(u);
                    rebuildThumbs();
                    snack("已移除一张");
                    return true;
                });
                thumbRow.addView(iv, p);
                loadThumb(u, iv);
            }

            if (images.size() < 9) {
                TextView add = text("＋", 22, col(R.color.hint));
                add.setGravity(Gravity.CENTER);
                add.setBackgroundResource(R.drawable.bg_thumb);
                add.setOnClickListener(v -> pickImages());
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(52), dp(52));
                p.rightMargin = dp(8);
                thumbRow.addView(add, p);
            }
        });
    }

    private void loadThumb(final Uri u, final ImageView iv) {
        new Thread(() -> {
            Bitmap bm = decodeThumb(u, dp(52));
            if (bm != null) ui.post(() -> iv.setImageBitmap(bm));
        }).start();
    }

    private Bitmap decodeThumb(Uri u, int sizePx) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            java.io.InputStream in = getContentResolver().openInputStream(u);
            BitmapFactory.decodeStream(in, null, o);
            if (in != null) in.close();
            int sample = 1;
            while (o.outWidth / (sample * 2) >= sizePx && o.outHeight / (sample * 2) >= sizePx) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            in = getContentResolver().openInputStream(u);
            Bitmap bm = BitmapFactory.decodeStream(in, null, o2);
            if (in != null) in.close();
            return bm;
        } catch (Throwable t) {
            return null;
        }
    }

    // ================= 会话 =================

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
        } catch (Throwable t) {
            return "ERR:" + t;
        }
    }

    private void onCaptureClick() {
        if (capturing) {
            cancelCapture = true;
            snack("正在取消…");
            return;
        }
        if (busy) {
            snack("正在执行，请稍候");
            return;
        }
        boolean vpn = vpnActive();
        String title = vpn ? "先关掉梯子再抓" : "第 1 步：先打开商陆花";
        String msg = vpn
                ? "检测到梯子 / VPN 正开着。\n\n"
                        + "抓会话时商陆花的请求要经过代理，开着梯子会卡在启动页。\n\n"
                        + "请先关掉梯子，把商陆花打开到「货品」页面，"
                        + "再回来点「我已关掉，开始抓取」。"
                : "把商陆花切到「货品」页面，等页面正常显示。\n\n"
                        + "这一步不挂代理，不会卡在启动页。\n\n"
                        + "准备好后点「开始抓取」，再切回商陆花下拉刷新。";
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(vpn ? "我已关掉，开始抓取" : "开始抓取", (d, w) -> {
                    if (vpnActive()) snack("梯子还开着，抓取可能会卡住");
                    new Thread(this::captureAndReport).start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void refreshCaptureUi() {
        if (captureLabel == null) return;
        captureLabel.setText(capturing ? "取消抓取" : "抓取登录会话");
        boolean can = capturing || !busy;
        captureBtn.setEnabled(can);
        captureBtn.setAlpha(can ? 1f : 0.45f);
    }

    private boolean shopAppAlive() {
        try {
            String out = sh("pidof com.slh_mobile_pro");
            out = out == null ? "" : out.trim();
            return out.matches("[0-9\\s]+");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 抓会话：只在用户确认商陆花已停在货品页后开启代理，抓到立刻关闭 */
    private String captureSession() {
        if (capturing) return null;
        capturing = true;
        cancelCapture = false;
        ui.post(this::refreshCaptureUi);
        String sid = null;
        try {
            log("启动代理…");
            if (catcher != null) catcher.stop();
            catcher = new ProxyCatcher();
            catcher.start(SessionCatcher.PORT, s -> {}, msg -> log(msg));

            sh("settings put global http_proxy 127.0.0.1:" + SessionCatcher.PORT);
            log("第 2 步：代理已开启。请切回商陆花，下拉刷新「货品」页");
            log("最多等 2 分钟；抓到会话或点「取消抓取」会立刻关闭代理");
            long deadline = System.currentTimeMillis() + 120000;
            while (System.currentTimeMillis() < deadline
                    && catcher.sid() == null && !cancelCapture) {
                Thread.sleep(300);
            }
            sid = catcher.sid();
            sh("settings put global http_proxy :0");
            try { catcher.stop(); } catch (Throwable ignored) {}
            if (sid != null) {
                sp.edit().putString("sid", sid).apply();
                log("✅ 会话已更新，代理已关闭："
                        + sid.substring(0, Math.min(22, sid.length())) + "…");
            } else if (cancelCapture) {
                log("已取消抓取，代理已关闭");
            } else {
                log("❌ 没抓到：确认商陆花停在「货品」页并下拉刷新；代理已关闭");
            }
        } catch (Throwable t) {
            try { sh("settings put global http_proxy :0"); } catch (Throwable ignored) {}
            if (catcher != null) { try { catcher.stop(); } catch (Throwable ignored) {} }
            log("❌ 抓会话失败：" + t);
            sid = null;
        } finally {
            capturing = false;
            ui.post(this::refreshCaptureUi);
        }
        return sid;
    }

    /** 开着梯子时本机 DNS 会返回 fake-ip，抓包代理连不上，商陆花会卡启动页 */
    private boolean vpnActive() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void captureAndReport() {
        if (busy) {
            snack("正在执行，请稍候");
            return;
        }
        setBusy(true, "等待刷新…");
        try {
            String sid = captureSession();
            if (cancelCapture) snack("已取消抓取，代理已关闭");
            else snack(sid != null ? "会话已更新，代理已关闭" : "没抓到会话，请按步骤再试");
        } finally {
            setBusy(false, null);
        }
    }

    private String session() {
        String sid = sp.getString("sid", null);
        if (sid == null || sid.isEmpty()) sid = captureSession();
        return sid;
    }

    // ================= 导入 =================

    private int noteCount(String text) {
        if (text == null || text.trim().isEmpty()) return 0;
        return splitNotes(text).size();
    }

    private List<String> splitNotes(String text) {
        List<String> out = new ArrayList<>();
        String[] parts = text.split("(?m)(?=(?:【\\s*款号\\s*】|^\\s*款号\\s*:?))");
        for (String p : parts) {
            if (!p.trim().isEmpty()) out.add(p.trim());
        }
        return out;
    }

    private void doImport() {
        final String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            snack("先把微信笔记粘进来");
            return;
        }
        if (busy) {
            snack("正在执行，请稍候");
            return;
        }
        final List<String> notes = splitNotes(text);
        final String price = priceInput.getText().toString().trim();
        log("共 " + notes.size() + " 条笔记");
        setBusy(true, "准备导入…");
        new Thread(() -> {
            int[] r = new int[]{0, 0, 0};
            try {
                r = runImport(notes, price);
            } catch (SlhApi.SessionExpired e) {
                log("⚠ 会话失效");
                if (!shopAppAlive()) {
                    log("请先打开商陆花到「货品」页，再点「抓取登录会话」，然后重新导入");
                    r = new int[]{0, 0, notes.size()};
                } else {
                    log("商陆花在后台：切过去打开「货品」页刷新，抓到会话会自动继续");
                    String sid = captureSession();
                    if (sid != null) {
                        try {
                            r = runImport(notes, price);
                        } catch (Throwable t) {
                            log("✘ 重连后仍失败：" + t);
                            r = new int[]{0, 0, notes.size()};
                        }
                    } else {
                        r = new int[]{0, 0, notes.size()};
                    }
                }
            } catch (Throwable t) {
                log("✘ 出错：" + t);
                r = new int[]{0, 0, Math.max(1, notes.size())};
            } finally {
                final int ok = r[0], skip = r[1], fail = r[2];
                ui.post(() -> {
                    showResult(ok, skip, fail);
                    snack("完成：成功 " + ok + " · 跳过 " + skip + " · 失败 " + fail);
                    setBusy(false, null);
                });
            }
        }).start();
    }

    private void showResult(int ok, int skip, int fail) {
        resultCard.setVisibility(View.VISIBLE);
        resultOk.setText(String.valueOf(ok));
        resultSkip.setText(String.valueOf(skip));
        resultFail.setText(String.valueOf(fail));
    }

    private int[] runImport(List<String> notes, String price) throws Exception {
        statusBusy("连接商陆花…");
        String sid = session();
        if (sid == null) {
            log("✘ 没有会话，先点「抓取登录会话」");
            return new int[]{0, 0, notes.size()};
        }
        SlhApi api = new SlhApi(sid);

        statusBusy("读取基础数据…");
        Map<String, JSONObject> colors;
        try {
            colors = api.colors();
        } catch (SlhApi.SessionExpired e) {
            log("⚠ 会话失效");
            if (!shopAppAlive()) {
                log("请先打开商陆花到「货品」页，再点「抓取登录会话」，然后重新导入");
                return new int[]{0, 0, notes.size()};
            }
            log("商陆花在后台：切过去刷新「货品」页，抓到会话会自动继续");
            sid = captureSession();
            if (sid == null) {
                log("✘ 重抓失败，请手动点「抓取登录会话」");
                return new int[]{0, 0, notes.size()};
            }
            api = new SlhApi(sid);
            colors = api.colors();
        }
        Map<String, JSONObject> sizes = api.sizes();
        List<String> codes = api.codes();
        log("颜色 " + colors.size() + " 个，尺码 " + sizes.size() + " 个，货品 " + codes.size() + " 条");

        String fileid = "";
        if (!images.isEmpty()) {
            statusBusy("上传图片…");
            List<String> ids = new ArrayList<>();
            int max = Math.min(9, images.size());
            for (int i = 0; i < max; i++) {
                try {
                    statusBusy("上传图片 " + (i + 1) + "/" + max);
                    java.io.InputStream in = getContentResolver().openInputStream(images.get(i));
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    in.close();
                    String fid = api.uploadImage(bo.toByteArray(), "IMG_" + (i + 1) + ".jpg");
                    if (fid.matches("\\d+")) {
                        ids.add(fid);
                    } else {
                        log("⚠ 图片 " + (i + 1) + " 返回异常："
                                + fid.substring(0, Math.min(40, fid.length())));
                    }
                } catch (Exception e) {
                    log("⚠ 图片 " + (i + 1) + " 上传失败：" + e);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (String id : ids) {
                if (sb.length() > 0) sb.append(',');
                sb.append(id);
            }
            fileid = sb.toString();
            log("图片 " + ids.size() + "/" + max + " 张已上传");
        }

        int ok = 0, skip = 0, fail = 0;
        for (int idx = 0; idx < notes.size(); idx++) {
            String note = notes.get(idx);
            statusBusy("导入 " + (idx + 1) + "/" + notes.size());
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
            if (codes.contains(n.no)) {
                log("⏭ " + n.no + " 已存在");
                skip++;
                continue;
            }

            List<String> miss = new ArrayList<>();
            for (String c : n.colors) if (!colors.containsKey(c)) miss.add(c);
            if (!miss.isEmpty()) {
                log("新建颜色：" + Text.join(miss));
                for (String c : miss) {
                    try {
                        api.createColor(c);
                    } catch (Exception ignored) {}
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

            JSONObject r = api.createStyle(n.no, n.name, cids, sids, fileid, price);
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
        statusBusy("整理结果…");
        return new int[]{ok, skip, fail};
    }
}
