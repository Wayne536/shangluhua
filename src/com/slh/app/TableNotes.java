package com.slh.app;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 Excel / WPS 复制出来的制表符文本转成标准笔记。
 * 支持：表头自动识别（款号/款式/颜色/尺码/进货价…）、多行单元格（Excel 用引号包起来）、
 * 尺码区间（M-L → M/L）、各种颜色分隔符。
 */
public class TableNotes {

    public static boolean isTable(String text) {
        if (text == null || text.isEmpty()) return false;
        return text.contains("\t");
    }

    public static List<String> toNotes(String text) {
        List<List<String>> records = parseRecords(text);
        List<String> out = new ArrayList<>();
        if (records.isEmpty()) return out;

        // 先找表头行（表格上面常有标题行），找不到就整张按固定列序
        int headerAt = -1;
        int[] map = null;
        for (int i = 0; i < records.size(); i++) {
            int[] h = headerMap(records.get(i));
            if (h != null) {
                headerAt = i;
                map = h;
                break;
            }
        }
        if (map == null) map = new int[]{0, 1, 2, 3, 4}; // 款号 名称 颜色 尺码 进货价

        for (int i = headerAt + 1; i < records.size(); i++) {
            List<String> rec = records.get(i);
            String no = cell(rec, map[0]);
            String name = cell(rec, map[1]);
            String colors = normList(cell(rec, map[2]));
            String sizes = normSizes(cell(rec, map[3]));
            String price = cell(rec, map[4]).replace("元", "").replace("¥", "").trim();
            if (no.isEmpty() && name.isEmpty() && colors.isEmpty()) continue;

            StringBuilder sb = new StringBuilder();
            sb.append("【款号】").append(no).append('\n');
            sb.append("【名称】").append(name).append('\n');
            sb.append("【颜色】").append(colors).append('\n');
            sb.append("【尺码】").append(sizes.isEmpty() ? "均码" : sizes);
            if (!price.isEmpty()) sb.append('\n').append("【进货价】").append(price);
            out.add(sb.toString());
        }
        return out;
    }

    // ---------------- 记录解析（认 Excel 的引号） ----------------

    private static List<List<String>> parseRecords(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuote) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        inQuote = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"' && cell.toString().trim().isEmpty()) {
                inQuote = true;
                cell.setLength(0);
            } else if (c == '\t') {
                cur.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                cur.add(cell.toString());
                cell.setLength(0);
                rows.add(cur);
                cur = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !cur.isEmpty()) {
            cur.add(cell.toString());
            rows.add(cur);
        }
        return rows;
    }

    // ---------------- 表头 ----------------

    private static int[] headerMap(List<String> cells) {
        int[] map = new int[]{-1, -1, -1, -1, -1};
        for (int c = 0; c < cells.size(); c++) {
            String h = cells.get(c).trim();
            if (h.isEmpty()) continue;
            if (h.contains("款号") || h.contains("货号")) map[0] = c;
            else if (h.contains("名称") || h.contains("品名") || h.contains("款式")) map[1] = c;
            else if (h.contains("颜色") || h.contains("色号")) map[2] = c;
            else if (h.contains("尺码") || h.contains("尺寸") || h.contains("码数")) map[3] = c;
            else if (h.contains("进货价") || h.contains("进价") || h.contains("价格")
                    || h.contains("成本价")) map[4] = c;
            else if (h.contains("成分") || h.contains("成份")) {
                if (map[1] < 0) map[1] = c; // 没有名称列时拿成分顶
            }
        }
        return map[0] >= 0 ? map : null;
    }

    private static String cell(List<String> c, int i) {
        return (i >= 0 && i < c.size()) ? c.get(i).trim() : "";
    }

    private static String normList(String s) {
        if (s.isEmpty()) return "";
        String t = s.replace("\r", "").replace("\n", "/")
                .replace("、", "/").replace("，", "/").replace(",", "/")
                .replace("；", "/").replace(";", "/").replace("＋", "/")
                .replace("+", "/")
                .replace(" ", "/");
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        for (String p : t.split("/")) {
            String v = p.trim();
            if (!v.isEmpty()) set.add(v);
        }
        StringBuilder sb = new StringBuilder();
        for (String v : set) {
            if (sb.length() > 0) sb.append('/');
            sb.append(v);
        }
        return sb.toString();
    }

    /** M-L → M/L；S~XL → S/M/L/XL；F/均 → 均码 */
    private static String normSizes(String s) {
        if (s.isEmpty()) return "";
        String[] ORDER = {"XS", "S", "M", "L", "XL", "2XL", "3XL", "4XL"};
        String v = s.trim().toUpperCase().replace(" ", "");
        if (v.equals("F") || v.equals("均") || v.equals("均码") || v.equals("FREE")) return "均码";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^([0-9A-Z]+)[-~—]([0-9A-Z]+)$").matcher(v);
        if (m.find()) {
            String a = m.group(1), b = m.group(2);
            int ia = indexOf(ORDER, a), ib = indexOf(ORDER, b);
            if (ia >= 0 && ib >= 0 && ia <= ib) {
                StringBuilder sb = new StringBuilder();
                for (int i = ia; i <= ib; i++) {
                    if (sb.length() > 0) sb.append('/');
                    sb.append(ORDER[i]);
                }
                return sb.toString();
            }
            return a + "/" + b;
        }
        return normList(s.replace("-", "/"));
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return -1;
    }
}
