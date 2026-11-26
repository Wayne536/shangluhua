package com.slh.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** 临时探针：create 一个测试货品 / 找记录 / 试更新接口 */
public class Probe {

    static SlhApi api;

    static void run(String cmd) {
        try {
            Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd}).waitFor();
        } catch (Throwable ignored) {
        }
    }

    static void say(Object o) {
        System.out.println(String.valueOf(o));
        System.out.flush();
    }

    public static void main(String[] a) throws Exception {
        String sid = new String(Files.readAllBytes(Paths.get("/data/local/tmp/slh_sid.txt")), "UTF-8").trim();
        if (sid.startsWith("sid:")) sid = sid.substring(4);
        api = new SlhApi(sid);
        String cmd = a.length > 0 ? a[0] : "find";
        if (cmd.equals("create")) {
            Map<String, JSONObject> colors = api.colors();
            Map<String, JSONObject> sizes = api.sizes();
            String black = colors.get("黑色").optString("sid");
            String one = sizes.get("均码").optString("sid");
            List<String> c = new ArrayList<>();
            c.add(black);
            List<String> s = new ArrayList<>();
            s.add(one);
            say("create=" + api.createStyle("TEST-9003", "测试补色", c, s, "", ""));
        } else if (cmd.equals("find")) {
            JSONObject rec = find(a[1]);
            System.out.println(rec == null ? "not found" : "id=" + rec.optString("id")
                    + " colorids=" + rec.optString("colorids") + " name=" + rec.optString("name"));
        } else if (cmd.equals("try")) {
            String code = a[1], colorName = a[2];
            String[] ifaces = a[3].split(",");
            Map<String, JSONObject> colors = api.colors();
            String add = colors.get(colorName).optString("sid");
            JSONObject rec = find(code);
            if (rec == null) {
                say("not found");
                return;
            }
            String cur = rec.optString("colorids");
            say("start colorids=" + cur + " add=" + colorName + "(" + add + ")");
            for (String iface : ifaces) {
                JSONObject copy = new JSONObject(rec.toString());
                copy.put("colorids", cur + "," + add);
                Map<String, String> e = new HashMap<>();
                e.put("jsonparam", copy.toString());
                String resp;
                try {
                    resp = api.call(iface, e).toString();
                } catch (Throwable t) {
                    resp = "ERR " + t;
                }
                if (resp.length() > 160) resp = resp.substring(0, 160) + "…";
                say(iface + " -> " + resp);
            }
            JSONObject after = find(code);
            say("final colorids=" + (after == null ? "?" : after.optString("colorids")));
        } else if (cmd.equals("apikeys")) {
            String code = a[1], colorName = a[2];
            String[] keys = a[3].split(",");
            JSONObject rec = findLike(code);
            if (rec == null) {
                say("没找到 " + code);
                return;
            }
            Map<String, JSONObject> colors = api.colors();
            String add = colors.get(colorName).optString("sid");
            String cur = rec.optString("colorids");
            say("colorids=" + cur + " add=" + colorName + "(" + add + ")");
            for (String k : keys) {
                JSONObject copy = new JSONObject(rec.toString());
                copy.put("colorids", cur + "," + add);
                String resp;
                try {
                    resp = api.callApi(k, copy.toString()).toString();
                } catch (Throwable t) {
                    resp = "ERR " + t;
                }
                say(k + " -> " + (resp.length() > 150 ? resp.substring(0, 150) : resp));
            }
            JSONObject after = find(code);
            say("after colorids=" + (after == null ? "?" : after.optString("colorids")));
        } else if (cmd.equals("apikey")) {
            String code = a[1], colorName = a[2], apiKey = a[3];
            JSONObject rec = findLike(code);
            if (rec == null) {
                say("没找到 " + code);
                return;
            }
            Map<String, JSONObject> colors = api.colors();
            String add = colors.get(colorName).optString("sid");
            String cur = rec.optString("colorids");
            say("before colorids=" + cur + " add=" + colorName + "(" + add + ")");
            if (("," + cur + ",").contains("," + add + ",")) {
                say("已包含");
                return;
            }
            JSONObject copy = new JSONObject(rec.toString());
            copy.put("colorids", cur + "," + add);
            String resp;
            try {
                resp = api.callApi(apiKey, copy.toString()).toString();
            } catch (Throwable t) {
                resp = "ERR " + t;
            }
            say(apiKey + " -> " + (resp.length() > 200 ? resp.substring(0, 200) : resp));
            JSONObject after = find(code);
            say("after colorids=" + (after == null ? "?" : after.optString("colorids")));
        } else if (cmd.equals("colors")) {
            String code = a[1];
            JSONObject rec = findLike(code);
            if (rec == null) {
                say("没找到 " + code);
                return;
            }
            Map<String, JSONObject> colors = api.colors();
            Map<String, String> sidToName = new HashMap<>();
            for (Map.Entry<String, JSONObject> en : colors.entrySet()) {
                sidToName.put(en.getValue().optString("sid"), en.getKey());
            }
            String[] ids = rec.optString("colorids").split(",");
            List<String> names = new ArrayList<>();
            for (String id : ids) {
                String v = id.trim();
                if (v.isEmpty()) continue;
                String n = sidToName.get(v);
                names.add(n == null ? ("?" + v) : n);
            }
            say("款号=" + rec.optString("code") + " 名称=" + rec.optString("name")
                    + " 颜色(" + names.size() + ")=" + android.text.TextUtils.join("、", names));
            say("sizeids=" + rec.optString("sizeids"));
        } else if (cmd.equals("sleep")) {
            say("sleeping");
            Thread.sleep(Long.parseLong(a[1]) * 1000L);
            say("done");
        } else if (cmd.equals("bind")) {
            ProxyCatcher.dumpBody = true;
            ProxyCatcher p = new ProxyCatcher();
            p.start(8899, sid3 -> {}, s2 -> say("[proxy] " + s2));
            ProxyCatcher.blockDataTunnel = true;
            say("bound");
            Thread.sleep(Long.parseLong(a[1]) * 1000L);
            p.stop();
            say("done");
        } else if (cmd.equals("sniff")) {
            int secs = a.length > 1 ? Integer.parseInt(a[1]) : 120;
            ProxyCatcher.dumpBody = true;
            ProxyCatcher p = new ProxyCatcher();
            p.start(8899, sid2 -> {}, s -> say("[proxy] " + s));
            ProxyCatcher.blockDataTunnel = true;
            run("settings put global http_proxy 127.0.0.1:8899");
            say("sniffing " + secs + "s");
            Thread.sleep(secs * 1000L);
            run("settings put global http_proxy :0");
            p.stop();
            ProxyCatcher.blockDataTunnel = false;
            say("done");
        } else if (cmd.equals("try2")) {
            String code = a[1], colorName = a[2];
            String iface = a.length > 3 ? a[3] : "sf-1511";
            Map<String, JSONObject> colors = api.colors();
            String add = colors.get(colorName).optString("sid");
            JSONObject rec = find(code);
            if (rec == null) {
                say("not found");
                return;
            }
            JSONObject p = new JSONObject();
            p.put("id", rec.optString("id"));
            p.put("fileid", rec.optString("fileid"));
            p.put("videoId", "");
            p.put("videoUrl", "");
            p.put("coverUrl", "");
            p.put("code", rec.optString("code"));
            p.put("name", rec.optString("name"));
            p.put("classid", "");
            p.put("brandid", "");
            p.put("colorids", rec.optString("colorids") + "," + add);
            p.put("sizeids", rec.optString("sizeids"));
            p.put("marketdate", rec.optString("marketdate"));
            p.put("purprice", "");
            p.put("stdprice1", "");
            p.put("uselastprice", "1");
            p.put("special", "0");
            p.put("purinjson", JSONObject.NULL);
            p.put("dwid", rec.optString("dwid"));
            p.put("season", "");
            p.put("year1", "");
            p.put("locationid", "");
            p.put("designer", "");
            p.put("lining", "");
            p.put("execstdid", "");
            p.put("safetycatid", "");
            p.put("rem", "");
            p.put("processunitprice", "");
            p.put("isprocess", "");
            Map<String, String> e = new HashMap<>();
            e.put("jsonparam", p.toString());
            String resp;
            try {
                resp = api.call(iface, e).toString();
            } catch (Throwable t) {
                resp = "ERR " + t;
            }
            say(iface + " -> " + resp);
            JSONObject after = find(code);
            say("colorids=" + (after == null ? "?" : after.optString("colorids")));
        } else if (cmd.equals("update")) {
            JSONObject rec = find(a[1]);
            if (rec == null) {
                say("not found");
                return;
            }
            Map<String, JSONObject> colors = api.colors();
            String add = colors.get(a[2]).optString("sid");
            String cur = rec.optString("colorids");
            if (("," + cur + ",").contains("," + add + ",")) {
                say("已经包含 " + a[2]);
                return;
            }
            String iface = a.length > 3 ? a[3] : "sf-1512";
            rec.put("colorids", cur + "," + add);
            Map<String, String> e = new HashMap<>();
            e.put("jsonparam", rec.toString());
            say(iface + " resp=" + api.call(iface, e));
        }
    }

    static JSONObject findLike(String code) throws Exception {
        int off = 0;
        while (off < 5000) {
            Map<String, String> e = new HashMap<>();
            e.put("type", "dres_style");
            e.put("offset", String.valueOf(off));
            e.put("limit", "500");
            e.put("lastSyncTime", "2000-01-01 00:00:00");
            JSONObject r = api.call("cs-syncdata", e);
            JSONArray dl = r.optJSONArray("dataList");
            if (dl == null || dl.length() == 0) return null;
            for (int i = 0; i < dl.length(); i++) {
                JSONObject o = dl.optJSONObject(i);
                String c = o.optString("code");
                if (c.equals(code) || c.endsWith("-" + code) || c.contains(code)) return o;
            }
            off += dl.length();
            if (dl.length() < 500) return null;
        }
        return null;
    }

    static JSONObject find(String code) throws Exception {
        int off = 0;
        while (off < 5000) {
            Map<String, String> e = new HashMap<>();
            e.put("type", "dres_style");
            e.put("offset", String.valueOf(off));
            e.put("limit", "500");
            e.put("lastSyncTime", "2000-01-01 00:00:00");
            JSONObject r = api.call("cs-syncdata", e);
            JSONArray dl = r.optJSONArray("dataList");
            if (dl == null || dl.length() == 0) return null;
            for (int i = 0; i < dl.length(); i++) {
                JSONObject o = dl.optJSONObject(i);
                if (code.equals(o.optString("code"))) return o;
            }
            off += dl.length();
            if (dl.length() < 500) return null;
        }
        return null;
    }
}
