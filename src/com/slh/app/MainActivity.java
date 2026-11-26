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
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.net.VpnService;
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
    private static final int REQ_PREVIEW = 2003;
    private static final int REQ_SHIZUKU = 1001;
    private static final int REQ_VPN = 2002;
    private static final int LV_INFO = 0, LV_OK = 1, LV_WARN = 2, LV_ERR = 3;
    private static final int NOTIFY_RETURN = 51;
    private static final String CH_RETURN = "quick_return";

    private SharedPreferences sp;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText input, priceInput;
    private TextView statusPill, countText, imageCount, pasteBtn, clearTextBtn;
    private View importBtn, captureBtn;
    private TextView captureLabel, captureHint;
    private TextView pickBtn, clearImgBtn;
    private TextView resultOk, resultSkip, resultFail;
    private View progress;
    private ScrollView scroll;
    private LinearLayout content, logBox, thumbRow, resultCard;
    private HorizontalScrollView thumbScroll;
    private TextView snackbar, logEmpty;

    private final List<Uri> images = new ArrayList<>();
    private boolean pendingAuto;
    /** 上次自动填进输入框的剪贴板内容，避免反复覆盖 */
    private String lastAutoFill = "";
    /** 刚导入过的内容，别再被剪贴板自动填回来 */
    private String lastImported = "";
    private boolean pickingImages;
    /** 长按后进入编辑：缩略图上出现 ⛔，可删可拖 */
    private boolean editMode;
    private TextView imgHint, editDoneBtn;
    private volatile boolean busy;
    private volatile boolean capturing, cancelCapture;

    private final Shizuku.OnRequestPermissionResultListener permListener =
            (requestCode, grantResult) -> {
                if (requestCode != REQ_SHIZUKU) return;
                refreshStatus();
                if (grantResult != 0) {
                    pendingAuto = false;
                    snack("未授权：自动抓取需要 Shizuku 权限");
                    return;
                }
                snack("Shizuku 已授权");
                if (pendingAuto) {
                    pendingAuto = false;
                    startVpnCapture(true);
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
        new Thread(this::healStaleProxy).start();
        if (getIntent() != null && getIntent().getBooleanExtra("paste", false)) {
            ui.postDelayed(this::pasteFromNotify, 600);
        }

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
            lastAutoFill = "";
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
        input.setMinLines(8);
        input.setMaxLines(16);
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

        editDoneBtn = textButton("完成", 0);
        editDoneBtn.setVisibility(View.GONE);
        editDoneBtn.setOnClickListener(v -> {
            editMode = false;
            rebuildThumbs();
            snack("已退出编辑");
        });
        imgRow.addView(editDoneBtn);

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

        imgHint = text("最多 9 张 · 点一下看大图 · 长按进入编辑（删除/排序）", 11, col(R.color.hint));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
        hlp.topMargin = dp(8);
        card.addView(imgHint, hlp);

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
        // 长按：App 内抓包 120 秒（调试用，抓商陆花保存货品的请求）
        captureBtn.setOnLongClickListener(v -> {
            startSniff(120);
            return true;
        });
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-1, dp(48));
        lp2.topMargin = dp(10);
        content.addView(captureBtn, lp2);

        captureHint = text("抓会话前请先关闭梯子 / VPN", 11, col(R.color.hint));
        captureHint.setGravity(Gravity.CENTER);
        captureHint.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(-1, -2);
        lp3.topMargin = dp(6);
        content.addView(captureHint, lp3);
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
        TextView share = textButton("分享日志", 0);
        share.setOnClickListener(v -> shareLog());
        head.addView(share);
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
                String sid = sp.getString("sid", "");
                if (sid != null && !sid.isEmpty()) {
                    setPill("● 已就绪", col(R.color.success), col(R.color.success_soft));
                } else {
                    setPill("● 待抓会话", col(R.color.primary), col(R.color.primary_soft));
                }
            } catch (Throwable t) {
                setPill("● 状态未知", col(R.color.warn), col(R.color.warn_soft));
            }
            refreshCaptureMode();
        });
    }

    /** 上次抓会话的进程崩了会留下系统代理，整机断网；这里检测到就自己清掉 */
    private void healStaleProxy() {
        try {
            String p = android.provider.Settings.Global.getString(getContentResolver(),
                    android.provider.Settings.Global.HTTP_PROXY);
            if (p == null) return;
            String v = p.trim();
            if (v.isEmpty() || v.equals(":0") || v.equals("null")) return;
            if (!v.contains("127.0.0.1") && !v.contains("localhost")) return;
            int port = 8899;
            int idx = v.lastIndexOf(':');
            if (idx > 0) {
                try {
                    port = Integer.parseInt(v.substring(idx + 1).trim());
                } catch (Throwable ignored) {
                }
            }
            if (isListening(port)) return; // 真有代理在跑，不动它
            if (!AutoCapture.available()) {
                log("⚠ 系统代理残留（" + v + "），但 Shizuku 不可用，清不掉");
                return;
            }
            AutoCapture ac = new AutoCapture(this::log);
            ac.sh("settings put global http_proxy :0");
            log("⚠ 发现残留的系统代理 " + v + "，已自动清掉");
            snack("上次抓取没清干净，代理已恢复");
        } catch (Throwable ignored) {
        }
    }

    private static boolean isListening(int port) {
        java.net.Socket s = new java.net.Socket();
        try {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 300);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 调试：App 内跑代理抓包，导出到 cap.log，用来找商陆花的保存接口 */
    private void startSniff(final int secs) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("抓包 " + secs + " 秒")
                .setMessage("会把系统代理指到本机，然后你到商陆花里给货品加颜色并保存。"
                        + "\n\n期间商陆花可能报网络错误，正常。结束后自动恢复代理。")
                .setPositiveButton("开始", (d, w) -> doSniff(secs))
                .setNegativeButton("取消", null)
                .show();
    }

    private void doSniff(final int secs) {
        log("=== 抓包开始 " + secs + "秒 ===");
        ProxyCatcher.dumpBody = true;
        ProxyCatcher.dumpTunnel = true;   // 记录加密隧道首包
        ProxyCatcher.blockDataTunnel = false;
        final ProxyCatcher p = new ProxyCatcher();
        try {
            p.start(8899, sid -> {}, s2 -> log(s2));
        } catch (Throwable t) {
            log("代理启动失败：" + t);
            ProxyCatcher.dumpBody = false;
            ProxyCatcher.blockDataTunnel = false;
            return;
        }
        final AutoCapture ac = new AutoCapture(this::log);
        new Thread(() -> {
            try {
                ac.sh("settings put global http_proxy 127.0.0.1:8899");
            } catch (Throwable t) {
                log("代理设置失败：" + t);
            }
            try {
                Thread.sleep(secs * 1000L);
            } catch (InterruptedException ignored) {
            }
            try {
                ac.sh("settings put global http_proxy :0");
            } catch (Throwable t) {
                log("代理恢复失败：" + t);
            }
            p.stop();
            ProxyCatcher.blockDataTunnel = false;
            ProxyCatcher.dumpBody = false;
            ProxyCatcher.dumpTunnel = false;
            ui.post(() -> {
                log("=== 抓包结束，代理已恢复 ===");
                snack("抓包结束");
            });
        }).start();
        snack("抓包中 " + secs + " 秒，去商陆花里保存一次");
    }

    private void setCaptureHint(final String s) {
        ui.post(() -> {
            if (captureHint != null && s != null) captureHint.setText(s);
        });
    }

    /** 按 Shizuku 是否可用，把抓会话按钮切成「自动 / 手动」 */
    private void refreshCaptureMode() {
        boolean auto = AutoCapture.available();
        if (captureLabel != null && !capturing) {
            captureLabel.setText(auto ? "自动抓取会话" : "抓取登录会话");
        }
        if (captureHint != null) {
            captureHint.setText(auto
                    ? "Shizuku 已授权 · 一键自动抓取，约 2 秒"
                    : "未授权 Shizuku · 手动抓取（可开小窗，先关梯子 / VPN）");
        }
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
    protected void onStart() {
        super.onStart();
        cancelReturnNotification();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!busy && !capturing && !pickingImages) postReturnNotification();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        new Thread(this::healStaleProxy).start();
    }

    private static boolean looksLikeNote(String s) {
        if (s == null || s.length() < 8) return false;
        if (TableNotes.isTable(s)) return true;
        if (s.contains("款号") || s.contains("货号") || s.contains("【") || s.contains("颜色")) {
            return true;
        }
        // 「8806，卷边半高领，成分。M，L，XL三码」这种没标签的开头
        String first = s.split("\n")[0].trim();
        return first.matches("^[A-Za-z0-9][A-Za-z0-9\\-_]{2,}[，,].*");
    }

    private void postReturnNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(CH_RETURN, "回到入库助手",
                        NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(this, MainActivity.class);
            open.putExtra("paste", true);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);
            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, CH_RETURN)
                    : new Notification.Builder(this);
            b.setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("入库助手")
                    .setContentText("复制完点这里回来，笔记会自动填好")
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            nm.notify(NOTIFY_RETURN, b.build());
        } catch (Throwable ignored) {
        }
    }

    private void cancelReturnNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIFY_RETURN);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra("paste", false)) {
            ui.postDelayed(this::pasteFromNotify, 600);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            Shizuku.removeRequestPermissionResultListener(permListener);
        } catch (Throwable ignored) {}
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

    /** 从通知进来时粘贴：等窗口拿到焦点再读剪贴板，否则读不到 */
    private void pasteFromNotify() {
        if (!hasWindowFocus()) {
            ui.postDelayed(this::pasteFromNotify, 300);
            return;
        }
        pasteClipboard();
    }

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
            int wasEmpty = old.isEmpty() ? 1 : 0;
            input.setText(old.isEmpty() ? s : old + "\n" + s);
            input.setSelection(0);
            input.scrollTo(0, 0);
            snack(wasEmpty == 1 ? "已粘贴，从上往下看" : "已追加粘贴");
        } catch (Throwable t) {
            snack("读取剪贴板失败：" + t.getMessage());
        }
    }

    private void pickImages() {
        pickingImages = true;
        Intent i = new Intent(this, ImagePickerActivity.class);
        ArrayList<String> cur = new ArrayList<>();
        for (Uri u : images) cur.add(u.toString());
        i.putStringArrayListExtra("selected", cur);
        startActivityForResult(i, REQ_IMAGES);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PREVIEW) {
            if (res == RESULT_OK && data != null) {
                ArrayList<String> edited = data.getStringArrayListExtra("notes");
                if (edited != null && !edited.isEmpty()) startImport(edited);
            }
            return;
        }
        if (req == REQ_VPN) {
            if (res == RESULT_OK) {
                final boolean auto = pendingAuto;
                new Thread(() -> captureAndReport(auto)).start();
            } else {
                snack("没有授权 VPN，抓不了会话");
            }
            return;
        }
        if (req != REQ_IMAGES) return;
        pickingImages = false;
        if (res != RESULT_OK || data == null) return;
        ArrayList<String> picked = data.getStringArrayListExtra("uris");
        if (picked != null) {
            images.clear();
            for (String s : picked) {
                if (images.size() >= 9) break;
                images.add(Uri.parse(s));
            }
            rebuildThumbs();
            log("已选 " + images.size() + " 张图");
            return;
        }
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
                editMode = false;
                thumbScroll.setVisibility(View.GONE);
                clearImgBtn.setVisibility(View.GONE);
                imageCount.setText("未选");
                return;
            }
            thumbRow.setPadding(0, 0, 0, dp(1));
            thumbScroll.setClipChildren(false);
            thumbScroll.setClipToPadding(false);
            thumbRow.setClipChildren(false);
            thumbScroll.setVisibility(View.VISIBLE);
            clearImgBtn.setVisibility(View.VISIBLE);
            imageCount.setText("已选 " + images.size() + " 张");

            for (int i = 0; i < images.size(); i++) {
                final Uri u = images.get(i);
                ThumbBox box = new ThumbBox(this);
                box.setClickable(true);
                box.setContentDescription("第 " + (i + 1) + " 张，点一下看大图，长按拖动排序");
                final Uri target = u;
                box.onDelete = () -> {
                    images.remove(target);
                    rebuildThumbs();
                    snack("已移除一张");
                };

                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackgroundResource(R.drawable.bg_thumb);
                iv.setClipToOutline(true);
                FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(56), dp(56));
                ilp.leftMargin = dp(10);
                ilp.topMargin = dp(10);
                box.addView(iv, ilp);

                box.setOnClickListener(v -> {
                    if (editMode) return;
                    openImagePreview(u);
                });
                box.setOnLongClickListener(v -> {
                    startThumbDrag(v);
                    return true;
                });
                box.setOnTouchListener(thumbTouch);

                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(66), dp(66));
                p.rightMargin = dp(4);
                thumbRow.addView(box, p);
                loadThumb(u, iv);
            }

            refreshEditUi();
            if (!editMode && images.size() < 9) {
                TextView add = text("＋", 22, col(R.color.hint));
                add.setGravity(Gravity.CENTER);
                add.setBackgroundResource(R.drawable.bg_thumb);
                add.setOnClickListener(v -> pickImages());
                FrameLayout addBox = new FrameLayout(this);
                FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(dp(56), dp(56));
                alp.leftMargin = dp(10);
                alp.topMargin = dp(10);
                addBox.addView(add, alp);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(66), dp(66));
                p.rightMargin = dp(4);
                thumbRow.addView(addBox, p);
            }
        });
    }

    /** 缩略图容器：自己把 iOS 样式的删除标画在最上层，左上角压一半出去 */
    private class ThumbBox extends FrameLayout {
        Runnable onDelete;
        private boolean badgeDown;

        ThumbBox(android.content.Context c) {
            super(c);
            setClipChildren(false);
        }

        @Override
        protected void dispatchDraw(android.graphics.Canvas canvas) {
            super.dispatchDraw(canvas);
            if (onDelete == null || !editMode) return;
            android.graphics.Paint p = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG);
            float r = dp(9);
            float cx = dp(10), cy = dp(10);
            p.setColor(0xFFFF3B30);
            canvas.drawCircle(cx, cy, r, p);
            p.setColor(Color.WHITE);
            canvas.drawRoundRect(cx - r * 0.5f, cy - dp(1.4f),
                    cx + r * 0.5f, cy + dp(1.4f), dp(1.6f), dp(1.6f), p);
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent e) {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    badgeDown = editMode && onDelete != null && inBadge(e.getX(), e.getY());
                    if (badgeDown) return true;
                    return super.onTouchEvent(e);
                case android.view.MotionEvent.ACTION_UP:
                    if (badgeDown) {
                        badgeDown = false;
                        boolean hit = inBadge(e.getX(), e.getY());
                        if (hit && onDelete != null) {
                            onDelete.run();
                            return true;
                        }
                        return true;
                    }
                    break;
                default:
                    break;
            }
            return super.onTouchEvent(e);
        }

        private boolean inBadge(float x, float y) {
            float dx = x - dp(10), dy = y - dp(10);
            float r = dp(16);
            return dx * dx + dy * dy <= r * r;
        }
    }

    // ===== 缩略图：点开看大图 / 长按拖动排序 =====

    private View dragView;
    private int dragFrom = -1, dragTo = -1;
    private float dragDownRawX;
    private boolean draggingThumb;

    private final View.OnTouchListener thumbTouch = (v, e) -> {
        switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                dragDownRawX = e.getRawX();
                return false; // 先交给 View 处理点击 / 长按
            case android.view.MotionEvent.ACTION_MOVE:
                if (!draggingThumb || dragView != v) return false;
                moveThumbDrag(v, e.getRawX() - dragDownRawX);
                return true;
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL:
                if (draggingThumb && dragView == v) {
                    endThumbDrag();
                    return true;
                }
                return false;
            default:
                return false;
        }
    };

    private void startThumbDrag(View v) {
        if (draggingThumb) return;
        int idx = thumbRow.indexOfChild(v);
        if (idx < 0 || v.getWidth() == 0) return;
        dragFrom = dragTo = idx;
        dragView = v;
        draggingThumb = true;
        if (!editMode) {
            editMode = true;
            refreshEditUi();
            for (int i = 0; i < thumbRow.getChildCount(); i++) {
                thumbRow.getChildAt(i).invalidate();
            }
        }
        v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(90).start();
        v.setTranslationZ(dp(6));
        v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        thumbScroll.requestDisallowInterceptTouchEvent(true);
        snack("左右拖动调整顺序");
    }

    private void moveThumbDrag(View v, float dx) {
        v.setTranslationX(dx);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
        int slot = v.getWidth() + lp.rightMargin;
        if (slot <= 0) return;
        int want = Math.round((v.getLeft() + dx) / (float) slot);
        want = Math.max(0, Math.min(images.size() - 1, want));
        if (want != dragTo) {
            dragTo = want;
            for (int i = 0; i < thumbRow.getChildCount(); i++) {
                View c = thumbRow.getChildAt(i);
                if (c == v) continue;
                float shift = 0;
                if (dragFrom < dragTo) {
                    if (i > dragFrom && i <= dragTo) shift = -slot;
                } else if (dragFrom > dragTo) {
                    if (i >= dragTo && i < dragFrom) shift = slot;
                }
                c.animate().translationX(shift).setDuration(110).start();
            }
        }
        int curX = v.getLeft() + Math.round(dx);
        int left = thumbScroll.getScrollX();
        int right = left + thumbScroll.getWidth();
        if (curX < left + dp(28)) thumbScroll.scrollBy(-dp(14), 0);
        else if (curX + v.getWidth() > right - dp(28)) thumbScroll.scrollBy(dp(14), 0);
    }

    private void endThumbDrag() {
        View v = dragView;
        int from = dragFrom, to = dragTo;
        draggingThumb = false;
        dragView = null;
        dragFrom = dragTo = -1;
        thumbScroll.requestDisallowInterceptTouchEvent(false);
        if (v != null) {
            v.animate().scaleX(1f).scaleY(1f).translationX(0).setDuration(120).start();
            v.setTranslationZ(0);
        }
        if (from >= 0 && to >= 0 && from != to
                && from < images.size() && to < images.size()) {
            Uri moved = images.remove(from);
            images.add(to, moved);
            snack("顺序已调整");
        }
        rebuildThumbs();
    }

    private void shareLog() {
        try {
            StringBuilder sb = new StringBuilder();
            String ver = "?";
            try {
                ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Throwable ignored) {
            }
            sb.append("入库助手 v").append(ver).append(" 运行日志\n\n");
            File f = new File(getExternalFilesDir(null), "cap.log");
            if (f.exists()) {
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
                java.util.ArrayDeque<String> q = new java.util.ArrayDeque<>();
                String line;
                while ((line = r.readLine()) != null) {
                    q.addLast(line);
                    if (q.size() > 300) q.removeFirst();
                }
                r.close();
                for (String l : q) sb.append(l).append('\n');
            } else {
                sb.append("（还没有日志）");
            }
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT, "入库助手运行日志");
            i.putExtra(Intent.EXTRA_TEXT, sb.toString());
            startActivity(Intent.createChooser(i, "分享日志"));
        } catch (Throwable t) {
            snack("导出日志失败：" + t.getMessage());
        }
    }

    private void refreshEditUi() {
        if (editDoneBtn != null) {
            editDoneBtn.setVisibility(editMode ? View.VISIBLE : View.GONE);
        }
        if (pickBtn != null) {
            pickBtn.setVisibility(editMode ? View.GONE : View.VISIBLE);
        }
        if (clearImgBtn != null) {
            clearImgBtn.setVisibility(!editMode && !images.isEmpty()
                    ? View.VISIBLE : View.GONE);
        }
        if (imgHint != null) {
            imgHint.setText(editMode
                    ? "点左上角 ⛔ 删除 · 拖动排序 · 点「完成」退出"
                    : "最多 9 张 · 点一下看大图 · 长按进入编辑（删除/排序）");
        }
    }

    private void openImagePreview(Uri u) {
        Intent i = new Intent(this, ImagePreviewActivity.class);
        i.putExtra("uri", u.toString());
        startActivity(i);
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
        if (vpnActive()) {
            new AlertDialog.Builder(this)
                    .setTitle("先关掉梯子再抓")
                    .setMessage("检测到其他 VPN / 梯子正开着。\n\n"
                            + "本功能会开一个只代理商陆花的本地 VPN，同一时间只能有一个 VPN。\n\n"
                            + "请先关掉梯子再试。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        if (AutoCapture.available()) {
            new AlertDialog.Builder(this)
                    .setTitle("自动抓取会话")
                    .setMessage("会依次自动完成：\n\n"
                            + "1. 临时把系统代理指向本机\n"
                            + "2. 冷启动商陆花，并在它启动前就掐断加密隧道\n"
                            + "3. 启动时的接口请求走明文，直接带出 sessionid\n"
                            + "4. 校验这个会话能用，立刻恢复代理、切回来\n\n"
                            + "全程约 2 秒，商陆花只闪一下。")
                    .setPositiveButton("开始", (d, w) -> startVpnCapture(true))
                    .setNeutralButton("手动", (d, w) -> startVpnCapture(false))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("手动抓会话")
                .setMessage("会开一个只代理商陆花的本地 VPN，不影响其他应用。\n\n"
                        + "1. 把商陆花开成小窗：最近任务里长按「商陆花」→ 小窗\n"
                        + "   （没有小窗就直接切过去，也行）\n"
                        + "2. 点一下「货品」卡片，或下拉刷新一次\n"
                        + "3. 抓到会话自动关 VPN，最多等 2 分钟\n\n"
                        + "用小窗的话可以一直留在入库助手，抓到立刻显示在这里。\n"
                        + "授权 Shizuku 后这些都能全自动。")
                .setPositiveButton("开始抓取", (d, w) -> startVpnCapture(false))
                .setNegativeButton("取消", null);
        try {
            if (Shizuku.pingBinder()) {
                b.setNeutralButton("自动（先授权）", (d, w) -> {
                    pendingAuto = true;
                    try {
                        Shizuku.requestPermission(REQ_SHIZUKU);
                    } catch (Throwable t) {
                        pendingAuto = false;
                        snack("请求授权失败：" + t.getMessage());
                    }
                });
            }
        } catch (Throwable ignored) {
        }
        b.show();
    }

    private void startVpnCapture(boolean auto) {
        try {
            Intent prep = VpnService.prepare(this);
            if (prep != null) {
                pendingAuto = auto;
                startActivityForResult(prep, REQ_VPN);
                return;
            }
        } catch (Throwable t) {
            snack("检查 VPN 权限失败：" + t.getMessage());
            return;
        }
        new Thread(() -> captureAndReport(auto)).start();
    }

    private void refreshCaptureUi() {
        if (captureLabel == null) return;
        captureLabel.setText(capturing ? "取消抓取"
                : (AutoCapture.available() ? "自动抓取会话" : "抓取登录会话"));
        boolean can = capturing || !busy;
        captureBtn.setEnabled(can);
        captureBtn.setAlpha(can ? 1f : 0.45f);
    }

    /** 只把商陆花放进 VPN，并把它的 HTTP 请求指向本机代理 */
    private String captureSession(Runnable onVpnReady, int waitSeconds) {
        if (capturing) return null;
        capturing = true;
        cancelCapture = false;
        ui.post(this::refreshCaptureUi);
        String sid = null;
        try {
            CaptureVpnService.capturedSid = null;
            log("开启只代理商陆花的 VPN…");
            Intent svc = new Intent(this, CaptureVpnService.class)
                    .setAction(CaptureVpnService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc);
            else startService(svc);
            if (onVpnReady != null) {
                log("自动刷新「货品」页，等会话…");
                onVpnReady.run();
            } else {
                log("把商陆花开成小窗，点一下「货品」；抓到会自动关闭 VPN");
                setCaptureHint("抓取中：在商陆花小窗里点一下「货品」");
            }
            long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
            while (System.currentTimeMillis() < deadline
                    && CaptureVpnService.capturedSid == null && !cancelCapture) {
                Thread.sleep(300);
            }
            sid = CaptureVpnService.capturedSid;
            if (sid != null) {
                sp.edit().putString("sid", sid).apply();
                log("✅ 会话已更新，VPN 已关闭："
                        + sid.substring(0, Math.min(22, sid.length())) + "…");
            } else if (cancelCapture) {
                log("已取消抓取，VPN 已关闭");
            } else {
                log("❌ 没抓到：确认商陆花已重新打开并刷新「货品」页；VPN 已关闭");
            }
        } catch (Throwable t) {
            log("❌ 抓会话失败：" + t);
            sid = null;
        } finally {
            try {
                stopService(new Intent(this, CaptureVpnService.class));
            } catch (Throwable ignored) {
            }
            capturing = false;
            ui.post(this::refreshCaptureUi);
            ui.post(this::refreshCaptureMode);
        }
        return sid;
    }

    /** 开着梯子时本机 DNS 会返回 fake-ip，和只代理商陆花的 VPN 也冲突 */
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

    private void captureAndReport(boolean auto) {
        if (busy) {
            snack("正在执行，请稍候");
            return;
        }
        setBusy(true, auto ? "自动抓会话…" : "等待商陆花…");
        try {
            String sid = auto ? captureAuto() : captureSession(null, 120);
            if (cancelCapture) snack("已取消抓取");
            else snack(sid != null ? "会话已更新，VPN 已关闭" : "没抓到会话，请重试");
        } finally {
            setBusy(false, null);
        }
    }

    /** 有 Shizuku：自动打开商陆花 → 货品页 → 开 VPN → 下拉刷新 → 回到本 App */
    private String captureAuto() {
        startGuard(true);
        try {
            return captureAutoInner();
        } finally {
            startGuard(false);
        }
    }

    /** 自动化期间挂前台通知，避免商陆花到前台后本 App 被系统冻结 */
    private void startGuard(boolean on) {
        try {
            Intent i = new Intent(this, AutoGuardService.class);
            if (on) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
                else startService(i);
            } else {
                stopService(i);
            }
        } catch (Throwable ignored) {
        }
    }

    private String captureAutoInner() {
        String apk;
        try {
            apk = getPackageManager().getApplicationInfo(getPackageName(), 0).sourceDir;
        } catch (Throwable t) {
            log("⚠ 取不到安装路径，改用手动抓取");
            return captureSession(null, 120);
        }
        AutoCapture ac = new AutoCapture(this::log);
        String sidPath = "/data/local/tmp/slh_sid.txt";
        String smLog = "/data/local/tmp/slh_auto.log";
        log("自动抓取：打开商陆花，从启动请求里取会话");
        statusBusy("自动抓会话…");

        String cmd = "rm -f " + sidPath + " " + smLog + "; "
                + "(setsid sh -c 'CLASSPATH=" + apk
                + " app_process /system/bin com.slh.app.ShellAuto' >/dev/null 2>&1 &)";
        String r = ac.sh(cmd);
        if (r.startsWith("ERR:")) {
            log("⚠ 启动自动流程失败：" + r + "，改用手动抓取");
            return captureSession(null, 120);
        }

        long deadline = System.currentTimeMillis() + 75000;
        String sid = null;
        boolean gaveUp = false;
        while (System.currentTimeMillis() < deadline && sid == null
                && !gaveUp && !cancelCapture) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
            }
            String out = ac.sh("cat " + sidPath + " 2>/dev/null").trim();
            if (out.startsWith("ERR:")) continue;
            if (out.equals("-")) {
                gaveUp = true;
            } else if (out.length() > 8) {
                sid = out.split("\n")[0].trim();
            }
        }
        if (sid != null && !sid.isEmpty()) {
            sp.edit().putString("sid", sid).apply();
            log("✅ 会话已更新：" + sid.substring(0, Math.min(22, sid.length())) + "…");
        } else {
            log("❌ 没抓到会话，可以改用手动方式");
            String tail = ac.sh("tail -12 " + smLog + " 2>/dev/null");
            for (String line : tail.split("\n")) {
                if (!line.trim().isEmpty()) log("· " + line.trim());
            }
        }
        return sid;
    }

    private String session() {
        String sid = sp.getString("sid", null);
        return (sid == null || sid.isEmpty()) ? null : sid;
    }

    // ================= 导入 =================

    private int noteCount(String text) {
        if (text == null || text.trim().isEmpty()) return 0;
        return splitNotes(text).size();
    }

    private List<String> splitNotes(String text) {
        List<String> out = new ArrayList<>();
        if (TableNotes.isTable(text)) {
            List<String> t = TableNotes.toNotes(text);
            if (!t.isEmpty()) return t;
        }
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
        Intent preview = new Intent(this, PreviewImportActivity.class);
        preview.putStringArrayListExtra("notes", new ArrayList<>(notes));
        preview.putExtra("sid", session());
        startActivityForResult(preview, REQ_PREVIEW);
    }

    private void startImport(final List<String> notes) {
        lastImported = input.getText().toString().trim();
        final String price = priceInput.getText().toString().trim();
        log("共 " + notes.size() + " 条笔记");
        setBusy(true, "准备导入…");
        new Thread(() -> {
            int[] r = new int[]{0, 0, 0};
            try {
                r = runImport(notes, price);
            } catch (SlhApi.SessionExpired e) {
                log("⚠ 会话失效，导入中止；请重新抓取会话");
                r = new int[]{0, 0, notes.size()};
            } catch (Throwable t) {
                log("✘ 出错：" + t);
                r = new int[]{0, 0, Math.max(1, notes.size())};
            } finally {
                final int ok = r[0], skip = r[1], fail = r[2];
                ui.post(() -> {
                    showResult(ok, skip, fail);
                    snack("完成：成功 " + ok + " · 跳过 " + skip + " · 失败 " + fail);
                    setBusy(false, null);
                    if (fail == 0 && ok > 0) {
                        input.setText("");
                        lastAutoFill = "";
                        images.clear();
                        rebuildThumbs();
                        log("已自动清空笔记和图片，可以贴下一条");
                    }
                });
            }
        }).start();
    }

    /** 精确找不到就按后缀匹配：笔记写 8806，商陆花里是 20-8806 */
    private static String existingCode(List<String> codes, String no) {
        if (codes == null || no == null || no.isEmpty()) return null;
        if (codes.contains(no)) return no;
        for (String c : codes) {
            if (c.endsWith("-" + no)) return c;
        }
        return null;
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
        if (sid == null && AutoCapture.available()) {
            log("还没有会话，先自动抓一次…");
            sid = captureAuto();
        }
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
            if (!AutoCapture.available()) {
                log("⚠ 会话失效，请点「抓取登录会话」重新抓取，然后重新导入");
                return new int[]{0, 0, notes.size()};
            }
            log("⚠ 会话失效，自动重新抓取…");
            String s2 = captureAuto();
            if (s2 == null) {
                log("✘ 没抓到新会话，导入中止");
                return new int[]{0, 0, notes.size()};
            }
            log("✔ 已抓到新会话，继续导入");
            api = new SlhApi(s2);
            colors = api.colors();
        }
        Map<String, JSONObject> sizes = api.sizes();
        List<String> codes = api.codes();
        log("颜色 " + colors.size() + " 个，尺码 " + sizes.size() + " 个，货品 " + codes.size() + " 条");

        // 已存在的款号：补色要用它的完整记录（拿 id 和现有颜色）
        java.util.Set<String> existWant = new java.util.HashSet<>();
        for (String note : notes) {
            NoteParser np = NoteParser.parse(note);
            String hit = existingCode(codes, np.no);
            if (hit != null) existWant.add(hit);
        }
        Map<String, JSONObject> existRecs = existWant.isEmpty()
                ? new java.util.HashMap<>() : api.styleRecords(existWant);

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
            String existHit = existingCode(codes, n.no);
            if (existHit != null) {
                JSONObject rec = existRecs.get(existHit);
                String styleId = rec == null ? "" : rec.optString("id");
                if (styleId.isEmpty()) {
                    log("⏭ " + n.no + " 已存在，但读不到 id，跳过");
                    skip++;
                    continue;
                }
                log("↻ " + n.no + " 已存在（商陆花 " + existHit + "），补色…");
                int added = ColorPatch.addColors(api, styleId, n.colors, colors, sizes, this::log);
                if (added > 0) ok++;
                else if (added == 0) skip++;
                else fail++;
                continue;
            }

            // 颜色先做模糊匹配，避免「暗靛蓝」和「暗靛蓝色」各建一个
            List<String> useColors = new ArrayList<>();
            List<String> miss = new ArrayList<>();
            for (String c : n.colors) {
                if (colors.containsKey(c)) {
                    useColors.add(c);
                    continue;
                }
                String hit = ColorMatch.resolve(c, colors.keySet());
                if (hit != null) {
                    log("颜色「" + c + "」用现有的「" + hit + "」");
                    useColors.add(hit);
                } else {
                    useColors.add(c);
                    miss.add(c);
                }
            }
            n.colors = useColors;
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

            // 数字尺码区间（105-130）按商陆花里真实存在的尺码展开
        List<String> expandSizes = new ArrayList<>();
        for (String s : n.sizes) expandSizes.addAll(SizeExpand.expand(s, sizes.keySet()));
        n.sizes = expandSizes;

        List<String> cids = new ArrayList<>();
            for (String c : n.colors) cids.add(colors.get(c).optString("sid"));
            List<String> sids = new ArrayList<>();
            for (String s : n.sizes) {
                if (sizes.containsKey(s)) sids.add(sizes.get(s).optString("sid"));
                else if (sizes.containsKey("均码")) sids.add(sizes.get("均码").optString("sid"));
            }

            String rowPrice = n.price.isEmpty() ? price : n.price;
            JSONObject r = api.createStyle(n.no, n.name, cids, sids, fileid, rowPrice);
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
