package com.slh.app;

import java.util.ArrayList;
import java.util.List;

/** 把 Excel / 表格复制出来的制表符文本转成标准笔记 */
public class TableNotes {

    public static boolean isTable(String text) {
        if (text == null || text.isEmpty()) return false;
        int tabs = 0;
        for (String l : text.split("\n")) {
            if (l.contains("\t")) tabs++;
            if (tabs >= 1 && text.contains("\n")) return true;
        }
        return false;
    }

    public static List<String> toNotes(String text) {
        List<String> out = new ArrayList<>();
        String[] lines = text.replace("\r", "").split("\n");
        int[] map = null;
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            String[] cells = line.split("\t", -1);
            if (map == null) {
                int[] h = headerMap(cells);
                if (h != null) {
                    map = h;
                    continue; // 表头行不算数据
                }
                map = new int[]{0, 1, 2, 3, 4};
            }
            String no = cell(cells, map[0]);
            String name = cell(cells, map[1]);
            String colors = normList(cell(cells, map[2]));
            String sizes = normList(cell(cells, map[3]));
            String price = cell(cells, map[4]).replace("元", "").replace("¥", "").trim();
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

    /** 有表头就按表头找列，没有就按固定列序 */
    static int[] headerMap(String[] cells) {
        int[] map = new int[]{-1, -1, -1, -1, -1};
        for (int c = 0; c < cells.length; c++) {
            String h = cells[c].trim();
            if (h.isEmpty()) continue;
            if (h.contains("款号") || h.contains("货号")) map[0] = c;
            else if (h.contains("名称") || h.contains("品名")) map[1] = c;
            else if (h.contains("颜色")) map[2] = c;
            else if (h.contains("尺码") || h.contains("尺寸") || h.contains("码数")) map[3] = c;
            else if (h.contains("价")) map[4] = c;
        }
        return map[0] >= 0 ? map : null;
    }

    static String cell(String[] c, int i) {
        return (i >= 0 && i < c.length) ? c[i].trim() : "";
    }

    static String normList(String s) {
        return s.replace("、", "/").replace("，", "/").replace(",", "/")
                .replace("；", "/").replace(" ", "/").replace("＋", "/");
    }
}
