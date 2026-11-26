package com.slh.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** 本地离线 OCR：PP-OCRv4（检测 + 识别），ONNX Runtime 跑在手机里 */
public class OnnxOcr {

    public static class Line {
        public String text;
        public float score;
        public int top;
        Line(String t, float s, int y) {
            text = t;
            score = s;
            top = y;
        }
    }

    private OrtEnvironment env;
    private OrtSession det;
    private OrtSession rec;
    private List<String> keys;
    private String detIn, detOut, recIn, recOut;
    private boolean ready;

    public synchronized void init(Context ctx) throws Exception {
        if (ready) return;
        byte[] detBytes = readAsset(ctx, "models/det.onnx");
        byte[] recBytes = readAsset(ctx, "models/rec.onnx");
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opt = new OrtSession.SessionOptions();
        opt.setIntraOpNumThreads(2);
        det = env.createSession(detBytes, opt);
        rec = env.createSession(recBytes, opt);
        detIn = det.getInputNames().iterator().next();
        detOut = det.getOutputNames().iterator().next();
        recIn = rec.getInputNames().iterator().next();
        recOut = rec.getOutputNames().iterator().next();
        String meta = null;
        try {
            Map<String, String> m = rec.getMetadata().getCustomMetadata();
            meta = m.get("character");
        } catch (Throwable ignored) {
        }
        if (meta == null) meta = readAssetText(ctx, "models/keys.txt");
        keys = new ArrayList<>();
        keys.add("blank");
        for (String line : meta.split("\n")) keys.add(line);
        keys.add(" ");
        ready = true;
    }

    public boolean isReady() {
        return ready;
    }

    /** 识别整张图，返回按行结果（从上到下） */
    public List<Line> run(Context ctx, Bitmap src) throws Exception {
        init(ctx);
        Bitmap bmp = src;
        int maxSide = Math.max(src.getWidth(), src.getHeight());
        if (maxSide > 1600) {
            float k = 1600f / maxSide;
            bmp = Bitmap.createScaledBitmap(src, Math.round(src.getWidth() * k),
                    Math.round(src.getHeight() * k), true);
        }
        float scale = (float) src.getWidth() / bmp.getWidth();

        // --- 检测 ---
        int dw = Math.max(32, (bmp.getWidth() + 31) / 32 * 32);
        int dh = Math.max(32, (bmp.getHeight() + 31) / 32 * 32);
        float[] mean = {0.485f, 0.456f, 0.406f};
        float[] std = {0.229f, 0.224f, 0.225f};
        FloatBuffer in = inputsFromBitmap(bmp, dw, dh, mean, std);
        float[] prob;
        int ph, pw;
        try (OnnxTensor t = OnnxTensor.createTensor(env, in, new long[]{1, 3, dh, dw});
             OrtSession.Result r = det.run(Collections.singletonMap(detIn, t))) {
            OnnxTensor o = (OnnxTensor) r.get(0);
            long[] sh = o.getInfo().getShape();
            ph = (int) sh[sh.length - 2];
            pw = (int) sh[sh.length - 1];
            prob = new float[ph * pw];
            o.getFloatBuffer().get(prob);
        }

        // --- 连通域找文字框 ---
        List<int[]> boxes = new ArrayList<>();
        List<Float> scores = new ArrayList<>();
        boolean[] vis = new boolean[ph * pw];
        int[] stack = new int[ph * pw];
        for (int y = 0; y < ph; y++) {
            for (int x = 0; x < pw; x++) {
                int idx = y * pw + x;
                if (vis[idx] || prob[idx] < 0.3f) continue;
                int minX = x, maxX = x, minY = y, maxY = y, cnt = 0;
                float sum = 0;
                int sp = 0;
                stack[sp++] = idx;
                vis[idx] = true;
                while (sp > 0) {
                    int cur = stack[--sp];
                    int cy = cur / pw, cx = cur % pw;
                    cnt++;
                    sum += prob[cur];
                    if (cx < minX) minX = cx;
                    if (cx > maxX) maxX = cx;
                    if (cy < minY) minY = cy;
                    if (cy > maxY) maxY = cy;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = cx + dx, ny = cy + dy;
                            if (nx < 0 || ny < 0 || nx >= pw || ny >= ph) continue;
                            int ni = ny * pw + nx;
                            if (!vis[ni] && prob[ni] >= 0.3f) {
                                vis[ni] = true;
                                stack[sp++] = ni;
                            }
                        }
                    }
                }
                int w = maxX - minX + 1, h = maxY - minY + 1;
                if (cnt < 8 || h < 4) continue;
                float sc = sum / cnt;
                if (sc < 0.5f) continue;
                // unclip 1.5
                float area = (float) w * h;
                float per = 2f * (w + h);
                float d = area * 1.5f / per;
                int x1 = Math.max(0, Math.round(minX - d));
                int y1 = Math.max(0, Math.round(minY - d));
                int x2 = Math.min(pw - 1, Math.round(maxX + d));
                int y2 = Math.min(ph - 1, Math.round(maxY + d));
                boxes.add(new int[]{x1, y1, x2, y2});
                scores.add(sc);
            }
        }
        if (boxes.isEmpty()) return new ArrayList<>();

        // 按行排序：y 相近的算一行
        Integer[] order = new Integer[boxes.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        final List<int[]> bx = boxes;
        java.util.Arrays.sort(order, (a, b) -> {
            int ya = (bx.get(a)[1] + bx.get(a)[3]) / 2;
            int yb = (bx.get(b)[1] + bx.get(b)[3]) / 2;
            if (Math.abs(ya - yb) < 12) return bx.get(a)[0] - bx.get(b)[0];
            return ya - yb;
        });

        // --- 逐框识别 ---
        List<Line> out = new ArrayList<>();
        float sx = (float) bmp.getWidth() / pw;
        float sy = (float) bmp.getHeight() / ph;
        for (int oi : order) {
            int[] b = boxes.get(oi);
            int x1 = Math.max(0, Math.round(b[0] * sx));
            int y1 = Math.max(0, Math.round(b[1] * sy));
            int x2 = Math.min(bmp.getWidth(), Math.round(b[2] * sx));
            int y2 = Math.min(bmp.getHeight(), Math.round(b[3] * sy));
            if (x2 - x1 < 6 || y2 - y1 < 6) continue;
            Bitmap crop = Bitmap.createBitmap(bmp, x1, y1, x2 - x1, y2 - y1);
            int rh = 48;
            int rw = Math.max(16, Math.min(420, Math.round((float) (x2 - x1) * rh / (y2 - y1))));
            Bitmap scaled = Bitmap.createScaledBitmap(crop, rw, rh, true);
            float[] pix = new float[3 * rh * rw];
            for (int yy = 0; yy < rh; yy++) {
                for (int xx = 0; xx < rw; xx++) {
                    int c = scaled.getPixel(xx, yy);
                    pix[yy * rw + xx] = ((Color.red(c) / 255f) - 0.5f) / 0.5f;
                    pix[rh * rw + yy * rw + xx] = ((Color.green(c) / 255f) - 0.5f) / 0.5f;
                    pix[2 * rh * rw + yy * rw + xx] = ((Color.blue(c) / 255f) - 0.5f) / 0.5f;
                }
            }
            String text;
            float conf;
            try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(pix),
                    new long[]{1, 3, rh, rw});
                 OrtSession.Result r = rec.run(Collections.singletonMap(recIn, t))) {
                OnnxTensor o = (OnnxTensor) r.get(0);
                long[] sh = o.getInfo().getShape();
                int steps = (int) sh[1];
                int cls = (int) sh[2];
                float[] logits = new float[steps * cls];
                o.getFloatBuffer().get(logits);
                StringBuilder sb = new StringBuilder();
                float sum = 0;
                int cnt = 0;
                int prev = -1;
                for (int t2 = 0; t2 < steps; t2++) {
                    int best = 0;
                    float bv = logits[t2 * cls];
                    for (int c = 1; c < cls; c++) {
                        float v = logits[t2 * cls + c];
                        if (v > bv) {
                            bv = v;
                            best = c;
                        }
                    }
                    if (best != 0 && best != prev) {
                        sb.append(best < keys.size() ? keys.get(best) : "");
                        sum += bv;
                        cnt++;
                    }
                    prev = best;
                }
                text = sb.toString();
                conf = cnt == 0 ? 0 : sum / cnt;
            }
            if (text.trim().isEmpty()) continue;
            out.add(new Line(text.trim(), conf, y1));
        }
        return out;
    }

    private static FloatBuffer inputsFromBitmap(Bitmap bmp, int w, int h, float[] mean, float[] std) {
        Bitmap sc = Bitmap.createScaledBitmap(bmp, w, h, true);
        float[] data = new float[3 * w * h];
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            sc.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                int c = row[x];
                data[y * w + x] = ((Color.red(c) / 255f) - mean[0]) / std[0];
                data[w * h + y * w + x] = ((Color.green(c) / 255f) - mean[1]) / std[1];
                data[2 * w * h + y * w + x] = ((Color.blue(c) / 255f) - mean[2]) / std[2];
            }
        }
        return FloatBuffer.wrap(data);
    }

    private static byte[] readAsset(Context ctx, String name) throws Exception {
        InputStream is = ctx.getAssets().open(name);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        is.close();
        return bo.toByteArray();
    }

    private static String readAssetText(Context ctx, String name) throws Exception {
        return new String(readAsset(ctx, name), "UTF-8");
    }
}
