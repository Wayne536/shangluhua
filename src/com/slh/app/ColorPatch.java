package com.slh.app;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 给已有货品补颜色：读详情 → 追加 colorids 和 SKU → sf-1511 保存 */
public class ColorPatch {

    public interface Log {
        void log(String s);
    }

    /** @return 实际新增的颜色个数；-1 表示失败 */
    public static int addColors(SlhApi api, String styleId, List<String> wantColors,
                                Map<String, JSONObject> colors, Map<String, JSONObject> sizes,
                                Log log) {
        try {
            JSONObject detail = api.styleDetail(styleId);
            if (detail == null || detail.optString("code").isEmpty()) {
                log.log("✘ 读不到货品详情");
                return -1;
            }
            String curIds = detail.optString("colorids");
            String sizeIds = detail.optString("sizeids");
            LinkedHashSet<String> allIds = new LinkedHashSet<>();
            for (String s : curIds.split(",")) {
                String v = s.trim();
                if (!v.isEmpty()) allIds.add(v);
            }
            List<String> added = new ArrayList<>();
            for (String name : wantColors) {
                JSONObject c = colors.get(name);
                if (c == null) {
                    String hit = ColorMatch.resolve(name, colors.keySet());
                    if (hit != null) c = colors.get(hit);
                }
                if (c == null) {
                    // 字典里没有就建一个（和新建货品时的行为一致）
                    try {
                        api.createColor(name);
                        log.log("＋ 新建颜色：" + name);
                        Map<String, JSONObject> fresh = api.colors();
                        colors.putAll(fresh);
                    } catch (Throwable t) {
                        log.log("⚠ 颜色「" + name + "」新建失败，跳过");
                        continue;
                    }
                    c = colors.get(name);
                    if (c == null) {
                        String hit = ColorMatch.resolve(name, colors.keySet());
                        if (hit != null) c = colors.get(hit);
                    }
                    if (c == null) {
                        log.log("⚠ 颜色「" + name + "」建完也找不到，跳过");
                        continue;
                    }
                }
                String sid = c.optString("sid");
                if (sid.isEmpty() || allIds.contains(sid)) continue;
                allIds.add(sid);
                added.add(name);
            }
            if (added.isEmpty()) {
                log.log("✔ 颜色都在，无需补色");
                return 0;
            }

            StringBuilder ids = new StringBuilder();
            for (String id : allIds) {
                if (ids.length() > 0) ids.append(',');
                ids.append(id);
            }
            detail.put("colorids", ids.toString());
            detail.put("modelClass", "DresStyle");
            detail.put("action", "edit");      // 关键：edit 才是修改，否则服务端按新建处理
            detail.put("pk", styleId);
            detail.put("invAdjust", "");

            // 重建 SKU 列表：所有颜色 × 所有尺码
            String oldSku = detail.optString("skuPriceList");
            String tpl = firstSkuRow(oldSku);
            if (tpl == null) tpl = "{'barcode':'','colorid':0,'compid':0,'createopid':0,'delflag':0,"
                    + "'delopid':0,'dwid':0,'epid':0,'flowrecid':0,'id':0,'invid':0,'isexport':0,"
                    + "'keyword':'','loginId':0,'matId':0,'newFlag':false,'opid':0,'origid':0,"
                    + "'purprice':0.0,'respopid':0,'rfid':0,'rowid':-1,'rowidx':0,'sizeid':0,"
                    + "'stdprice1':0.0,'stdprice2':0.0,'stdprice3':0.0,'stdprice4':0.0,'stdprice5':0.0,"
                    + "'styleid':0,'uniCodeIndex':0,'ver':0,'version':0}";
            String code = detail.optString("code");
            StringBuilder sku = new StringBuilder("[");
            boolean first = true;
            for (String cid : allIds) {
                for (String sz : sizeIds.split(",")) {
                    String s = sz.trim();
                    if (s.isEmpty()) continue;
                    String row = tpl
                            .replaceAll("'barcode':'[^']*'", "'barcode':'" + code + pad(cid) + pad(s) + "'")
                            .replaceAll("'colorid':\\d+", "'colorid':" + cid)
                            .replaceAll("'sizeid':\\d+", "'sizeid':" + s)
                            .replaceAll("'id':\\d+", "'id':0");
                    if (!first) sku.append(',');
                    sku.append(row);
                    first = false;
                }
            }
            sku.append(']');
            detail.put("skuPriceList", sku.toString());

            JSONObject r = api.saveStyle(detail);
            if (r.has("val")) {
                log.log("✔ 补色 " + added.size() + " 个：" + join(added) + "（id " + r.optString("val") + "）");
                return added.size();
            }
            log.log("✘ 补色失败：" + r);
            return -1;
        } catch (Throwable t) {
            log.log("✘ 补色出错：" + t);
            return -1;
        }
    }

    private static String firstSkuRow(String sku) {
        if (sku == null) return null;
        Matcher m = Pattern.compile("\\{[^{}]*\\}").matcher(sku);
        return m.find() ? m.group() : null;
    }

    private static String pad(String id) {
        return id.length() == 1 ? "0" + id : id;
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) sb.append('、');
            sb.append(s);
        }
        return sb.toString();
    }
}
