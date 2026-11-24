package com.slh.app;

import java.util.Collection;

/** 颜色模糊匹配：笔记写「暗靛蓝」，商陆花里是「暗靛蓝色」，别再新建一个 */
public class ColorMatch {

    /** 返回已存在的颜色名，没匹配到返回 null */
    public static String resolve(String want, Collection<String> existing) {
        if (want == null || existing == null) return null;
        String w = want.trim();
        if (w.isEmpty()) return null;
        if (existing.contains(w)) return w;

        String nw = norm(w);
        if (nw.length() < 2) return null;

        // 1) 去掉「色」和空格后完全一样
        for (String c : existing) {
            if (norm(c).equals(nw)) return c;
        }
        // 2) 互相包含（取最短的那个，通常是标准名）
        String best = null;
        for (String c : existing) {
            String nc = norm(c);
            if (nc.length() < 2) continue;
            if (nc.contains(nw) || nw.contains(nc)) {
                if (best == null || c.length() < best.length()) best = c;
            }
        }
        return best;
    }

    static String norm(String s) {
        return s.replace("色", "").replace(" ", "").replace("（", "").replace("）", "").trim();
    }
}
