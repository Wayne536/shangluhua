package com.slh.app;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 笔记解析：款号 / 名称 / 颜色 / 尺码 */
public class NoteParser {

    public String no = "", name = "";
    public List<String> colors = new ArrayList<>();
    public List<String> sizes = new ArrayList<>();

    private static final String L = "款号|货号|款式|品名|名称|颜色|色号|尺码|尺寸|码数";

    public static NoteParser parse(String raw) {
        NoteParser n = new NoteParser();
        String text = raw.replace("\r\n", "\n").replace('：', ':');
        String code = "", name = "", colors = "", sizes = "";

        if (text.contains("【")) {
            code = tag(text, "款号");
            if (code.isEmpty()) code = tag(text, "货号");
            name = tag(text, "名称");
            if (name.isEmpty()) name = tag(text, "款式");
            if (name.isEmpty()) name = tag(text, "品名");
            colors = tag(text, "颜色");
            if (colors.isEmpty()) colors = tag(text, "色号");
            sizes = tag(text, "尺码");
            if (sizes.isEmpty()) sizes = tag(text, "尺寸");
            if (sizes.isEmpty()) sizes = tag(text, "码数");
        }

        if (code.isEmpty() || colors.isEmpty()) {
            for (String line : text.split("\n")) {
                String s = line.replace("【", "").replace("】", "").trim();
                Matcher m = Pattern.compile("^(" + L + ")\\s*:?\\s*(.*)$").matcher(s);
                if (!m.find()) continue;
                String k = m.group(1), v = m.group(2).trim();
                switch (k) {
                    case "款号": case "货号": if (code.isEmpty()) code = v; break;
                    case "名称": case "款式": case "品名": if (name.isEmpty()) name = v; break;
                    case "颜色": case "色号": if (colors.isEmpty()) colors = v; break;
                    case "尺码": case "尺寸": case "码数": if (sizes.isEmpty()) sizes = v; break;
                }
            }
        }

        // 「8806全羊毛加厚三色均码」→ 款号 8806，名称后半段
        Matcher m = Pattern.compile("^([A-Za-z0-9][A-Za-z0-9\\-]*?)([\\u4e00-\\u9fa5].*)$").matcher(code.trim());
        if (m.find() && name.isEmpty()) {
            code = m.group(1);
            name = m.group(2);
        }

        n.no = code.trim();
        n.name = name.trim();
        n.colors = splitList(colors);
        n.sizes = normSizes(splitList(sizes));
        if (n.sizes.isEmpty()) n.sizes.add("均码");
        return n;
    }

    private static String tag(String text, String label) {
        Matcher m = Pattern.compile("【\\s*" + label + "\\s*】\\s*:?\\s*([^\\n]*)").matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }

    private static List<String> splitList(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String t : s.split("[/／、,，;；|\\s]+")) {
            String v = t.trim();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    private static List<String> normSizes(List<String> in) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : in) {
            String low = s.toLowerCase();
            if (low.matches("[smlx]{2,6}")) {
                for (char ch : s.toCharArray()) out.add(String.valueOf(ch).toUpperCase());
            } else if (low.equals("均") || low.equals("均码") || low.equals("f")) {
                out.add("均码");
            } else if (low.equals("xxl")) {
                out.add("2XL");
            } else {
                out.add(s.length() <= 3 ? s.toUpperCase() : s);
            }
        }
        return new ArrayList<>(out);
    }
}
