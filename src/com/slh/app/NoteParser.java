package com.slh.app;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 笔记解析：款号 / 名称 / 颜色 / 尺码 */
public class NoteParser {

    public String no = "", name = "", price = "";
    public List<String> colors = new ArrayList<>();
    public List<String> sizes = new ArrayList<>();

    private static final String L = "款号|货号|款式|品名|名称|颜色|色号|尺码|尺寸|码数";
    /** 没有「名称」时，用这几行的值顶上 */
    private static final String MAT = "成份|成分|材质|面料";
    /** 每行可以带自己的进货价 */
    private static final String PRICE = "进货价|进价|价格|成本";

    public static NoteParser parse(String raw) {
        NoteParser n = new NoteParser();
        String text = raw.replace("\r\n", "\n").replace('：', ':');
        String code = "", name = "", colors = "", sizes = "", material = "", price = "";

        if (text.contains("【")) {
            code = tag(text, "款号");
            if (code.isEmpty()) code = tag(text, "货号");
            name = tag(text, "名称");
            if (name.isEmpty()) name = tag(text, "款式");
            if (name.isEmpty()) name = tag(text, "品名");
            colors = tag(text, "颜色");
            if (colors.isEmpty()) colors = tag(text, "色号");
            price = tag(text, "进货价");
            if (price.isEmpty()) price = tag(text, "进价");
            if (price.isEmpty()) price = tag(text, "价格");
            sizes = tag(text, "尺码");
            if (sizes.isEmpty()) sizes = tag(text, "尺寸");
            if (sizes.isEmpty()) sizes = tag(text, "码数");
        }

        if (code.isEmpty() || colors.isEmpty()) {
            for (String line : text.split("\n")) {
                String s = line.replace("【", "").replace("】", "").trim();
                Matcher m = Pattern.compile("^(" + L + "|" + MAT + "|" + PRICE + ")\\s*:?\\s*(.*)$")
                        .matcher(s);
                if (!m.find()) continue;
                String k = m.group(1), v = m.group(2).trim();
                switch (k) {
                    case "款号": case "货号": if (code.isEmpty()) code = v; break;
                    case "名称": case "款式": case "品名": if (name.isEmpty()) name = v; break;
                    case "颜色": case "色号": if (colors.isEmpty()) colors = v; break;
                    case "尺码": case "尺寸": case "码数": if (sizes.isEmpty()) sizes = v; break;
                    default:
                        if (k.matches(PRICE)) {
                            if (price.isEmpty()) price = v;
                        } else if (material.isEmpty()) {
                            material = v;
                        }
                        break;
                }
            }
        }

        // 「8806全羊毛加厚三色均码」→ 款号 8806，名称后半段
        Matcher m = Pattern.compile("^([A-Za-z0-9][A-Za-z0-9\\-]*?)([\\u4e00-\\u9fa5].*)$").matcher(code.trim());
        if (m.find() && name.isEmpty()) {
            code = m.group(1);
            name = m.group(2);
        }

        // 群里很多笔记只写成份不写名称，用成份顶上，免得因为缺名称被跳过
        if (name.isEmpty() && !material.isEmpty()) name = material;

        if (code.isEmpty() || colors.isEmpty()) {
            parseLoose(text, n, code, name, colors, sizes);
            code = n.no.isEmpty() ? code : n.no;
            name = n.name.isEmpty() ? name : n.name;
            if (colors.isEmpty() && !n.colors.isEmpty()) {
                colors = joinSlash(n.colors);
            }
            if (sizes.isEmpty() && !n.sizes.isEmpty()) {
                sizes = joinSlash(n.sizes);
            }
        }

        n.price = price.replace("元", "").replace("¥", "").trim();
        String rawCode = code.trim();
        if (rawCode.matches(".*[ \t].*")) {
            // 「款号：2205  30羊绒卷边半高领」这种，空格后面是名称
            String[] seg = rawCode.split("[ \t]+", 2);
            n.no = seg[0];
            if (name.trim().isEmpty() && seg.length > 1) name = seg[1].trim();
        } else {
            n.no = rawCode;
        }
        n.name = reorderName(name.trim());
        n.colors = splitList(colors);
        n.sizes = normSizes(splitList(sizes));
        if (n.sizes.isEmpty()) n.sizes.add("均码");
        return n;
    }

    /**
     * 没有【】也没有「款号：」时的兜底：
     * 「8806，卷边半高领，超细美丽诺100%羊毛。M，L，XL三码」+ 下面一片颜色
     */
    private static void parseLoose(String text, NoteParser n, String code0,
                                   String name0, String colors0, String sizes0) {
        String[] lines = text.split("\n");
        String first = "";
        int firstIdx = -1;
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].trim().isEmpty()) {
                first = lines[i].trim();
                firstIdx = i;
                break;
            }
        }
        if (first.isEmpty()) return;

        if (n.no.isEmpty() && code0.isEmpty()) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^([A-Za-z0-9][A-Za-z0-9\\-_]{1,})").matcher(first);
            if (m.find()) n.no = m.group(1);
        }
        if (n.name.isEmpty() && name0.isEmpty() && !n.no.isEmpty()) {
            String rest = first.substring(first.indexOf(n.no) + n.no.length());
            for (String p : rest.split("[，,。、；;]")) {
                String t = p.trim();
                if (t.isEmpty()) continue;
                if (isSizeToken(t)) continue;
                String full = t.replaceFirst("^款\\s*", "").trim();
                if (full.matches(".*[0-9]%.*") && full.matches("[\\u4e00-\\u9fa5A-Za-z0-9%.]+")) {
                    n.name = full;
                    break;
                }
                // 「拉毛花边100%山羊绒」→ 取数字前面的部分当名称；纯成分词才跳过
                String cand = t.replaceAll("[0-9%０-９].*$", "").trim();
                cand = cand.replaceFirst("^款\\s*", "").trim();
                if (cand.length() >= 2 && cand.matches("[\\u4e00-\\u9fa5A-Za-z]+")
                        && !cand.matches("^(羊毛|羊绒|山羊绒|棉|腈纶|锦纶|氨纶|聚酯|莱赛尔|亚麻|真丝|莫代尔|粘纤|天丝|涤纶|里料)+$")) {
                    n.name = cand;
                    break;
                }
            }
        }
        if (n.sizes.isEmpty() && sizes0.isEmpty()) {
            n.sizes = findSizesLoose(lines);
        }
        if (n.colors.isEmpty() && colors0.isEmpty()) {
            Set<String> cs = new LinkedHashSet<>();
            for (int i = firstIdx + 1; i < lines.length; i++) {
                String line = lines[i];
                if (line.contains("：") || line.contains(":")) continue;
                for (String t : line.split("[\\t，,、/\\s]+")) {
                    String v = t.trim();
                    if (v.length() < 2 || v.length() > 6) continue;
                    if (!v.matches("[\\u4e00-\\u9fa5]+")) continue;
                    if (isSizeToken(v)) continue;
                    cs.add(v);
                }
            }
            if (!cs.isEmpty()) n.colors = new ArrayList<>(cs);
        }
    }

    /** 无标签笔记里找尺码：优先在含「分/码/尺码」的行里找，支持 M一L 这种写法 */
    private static List<String> findSizesLoose(String[] lines) {
        List<String> out = new ArrayList<>();
        String[] order = {"XS", "S", "M", "L", "XL", "2XL", "3XL", "4XL"};
        for (String raw : lines) {
            String line = raw.replace('一', '-').replace('－', '-').replace('–', '-')
                    .replace('~', '-').replace('～', '-').replace('至', '-')
                    .replace('—', '-').replace('─', '-');
            boolean hint = line.contains("分") || line.contains("码") || line.contains("尺码")
                    || line.contains("均码");
            java.util.regex.Matcher rm = java.util.regex.Pattern
                    .compile("(?i)([A-Za-z]{1,3})\\s*-\\s*([A-Za-z]{1,3})").matcher(line);
            if (rm.find()) {
                String a = rm.group(1).toUpperCase(), b = rm.group(2).toUpperCase();
                int ia = indexOfSize(order, a), ib = indexOfSize(order, b);
                if (ia >= 0 && ib >= 0) {
                    for (int i = Math.min(ia, ib); i <= Math.max(ia, ib); i++) out.add(order[i]);
                    return out;
                }
                out.add(a);
                out.add(b);
                return out;
            }
            if (!hint) continue;
            java.util.regex.Matcher sm = java.util.regex.Pattern
                    .compile("(?<![A-Za-z])(XXS|XS|S|M|L|XL|XXL|2XL|3XL|4XL)(?![A-Za-z])")
                    .matcher(line.toUpperCase());
            while (sm.find()) out.add(sm.group(1));
            if (line.contains("均码")) {
                out.clear();
                out.add("均码");
            }
            if (!out.isEmpty()) return out;
        }
        return out;
    }

    private static int indexOfSize(String[] order, String v) {
        for (int i = 0; i < order.length; i++) if (order[i].equals(v)) return i;
        return -1;
    }

    private static boolean isSizeToken(String t) {
        String v = t.toUpperCase();
        return v.matches("^(XXS|XS|S|M|L|XL|XXL|XX L|2XL|3XL|4XL|[一二三四五]码|均码|F)$")
                || v.matches("^(XXS|XS|S|M|L|XL|2XL|3XL)[一二三四五六]码$");
    }

    /** 「拉毛花边100%山羊绒」→「100%山羊绒拉毛花边」：带成分百分比的，把成分挪到前面 */
    private static String reorderName(String name) {
        if (name == null || name.isEmpty()) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(.*?)([0-9]+(?:\\.[0-9]+)?%\\s*[\\u4e00-\\u9fa5]{1,8})$")
                .matcher(name.trim());
        if (m.find()) {
            String head = m.group(1).trim();
            String mat = m.group(2).trim();
            if (!head.isEmpty()) return mat + head;
        }
        return name;
    }

    private static String joinSlash(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) sb.append('/');
            sb.append(s);
        }
        return sb.toString();
    }

    private static String tag(String text, String label) {
        // 注意：不能用 \s，它会把换行吃掉，把下一行当成这个字段的值
        Matcher m = Pattern.compile("【\\s*" + label + "\\s*】[ \\t]*:?[ \\t]*([^\\n]*)")
                .matcher(text);
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
        final String[] ORDER = {"XS", "S", "M", "L", "XL", "2XL", "3XL", "4XL"};
        for (String s : in) {
            String dash = s.replace('一', '-').replace('—', '-').replace('–', '-')
                    .replace('~', '-').replace('～', '-').replace('至', '-').replace(' ', '-');
            java.util.regex.Matcher rm = java.util.regex.Pattern
                    .compile("^([A-Za-z]{1,3})-([A-Za-z]{1,3})$").matcher(dash);
            if (rm.find()) {
                String a = rm.group(1).toUpperCase(), b = rm.group(2).toUpperCase();
                int ia = -1, ib = -1;
                for (int i = 0; i < ORDER.length; i++) {
                    if (ORDER[i].equals(a)) ia = i;
                    if (ORDER[i].equals(b)) ib = i;
                }
                if (ia >= 0 && ib >= 0) {
                    for (int i = Math.min(ia, ib); i <= Math.max(ia, ib); i++) out.add(ORDER[i]);
                    continue;
                }
            }
            String low = s.toLowerCase();
            if (low.equals("均") || low.equals("均码") || low.equals("f") || low.equals("free")) {
                out.add("均码");
                continue;
            }
            // 全是 s/m/l/x 的连写（SML、SMLXL）拆成单个尺码；单个 XL/XXL 不能拆
            if (low.matches("[smlx]{2,8}") && !low.matches("x{1,4}[sl]")) {
                for (String t : tokenizeSizes(low)) out.add(canonSize(t));
                continue;
            }
            out.add(canonSize(low));
        }
        return new ArrayList<>(out);
    }

    /** 把 smlxl 这种连写切成 s/m/l/xl（优先长匹配） */
    private static List<String> tokenizeSizes(String low) {
        List<String> out = new ArrayList<>();
        int i = 0;
        String[] tok = {"xxxl", "xxl", "xxs", "xl", "xs", "s", "m", "l", "x"};
        while (i < low.length()) {
            boolean hit = false;
            for (String t : tok) {
                if (low.startsWith(t, i)) {
                    out.add(t);
                    i += t.length();
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                out.add(String.valueOf(low.charAt(i)));
                i++;
            }
        }
        return out;
    }

    private static String canonSize(String low) {
        switch (low) {
            case "xxl": return "2XL";
            case "xxxl": return "3XL";
            case "xxxxl": return "4XL";
            case "xxs": return "2XS";
            default: return low.length() <= 3 ? low.toUpperCase() : low;
        }
    }
}
