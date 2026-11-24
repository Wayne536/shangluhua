package com.slh.app;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数字尺码区间（105-130）按商陆花里真实存在的尺码展开，
 * 不靠猜步长：存在的 105/110/115/120/125/130 都会列出来。
 */
public class SizeExpand {

    private static final Pattern RANGE = Pattern.compile("^(\\d{2,3})\\s*[-~—]\\s*(\\d{2,3})$");

    public static List<String> expand(String token, Collection<String> known) {
        List<String> out = new ArrayList<>();
        if (token == null) return out;
        Matcher m = RANGE.matcher(token.trim());
        if (!m.find()) {
            out.add(token.trim());
            return out;
        }
        int a = Integer.parseInt(m.group(1));
        int b = Integer.parseInt(m.group(2));
        if (a > b) {
            int t = a;
            a = b;
            b = t;
        }
        List<Integer> nums = new ArrayList<>();
        if (known != null) {
            for (String k : known) {
                if (k == null) continue;
                String v = k.trim();
                if (!v.matches("\\d{2,3}")) continue;
                int n = Integer.parseInt(v);
                if (n >= a && n <= b) nums.add(n);
            }
        }
        if (nums.isEmpty()) {
            out.add(token.trim());
            return out;
        }
        Collections.sort(nums);
        java.util.LinkedHashSet<Integer> uniq = new java.util.LinkedHashSet<>(nums);
        for (int n : uniq) out.add(String.valueOf(n));
        return out;
    }
}
