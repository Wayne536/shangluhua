package com.slh.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 商陆花接口客户端 */
public class SlhApi {

    public static final String HOST = "14.103.84.43";
    public static final int PORT = 8013;
    public static final String BASE = "http://" + HOST + ":" + PORT + "/slh/callInterface.do";

    public static class SessionExpired extends Exception {
        public SessionExpired(String m) { super(m); }
    }

    private final String sid;

    public SlhApi(String sid) { this.sid = sid; }

    private Map<String, String> base(String iface) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("productCode", "pocketSLhPro");
        m.put("dlProductCode", "pocketSLhPro");
        m.put("dlUseFieldNameAlias", "1");
        m.put("deviceType", "9");
        m.put("productVersion", "2.6.9");
        m.put("slh_version", "2.6.9");
        m.put("sessionid", sid);
        m.put("sessionId", sid);
        m.put("interfaceid", iface);
        return m;
    }

    private static String enc(Map<String, String> p) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : p.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=')
              .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), "UTF-8"));
        }
        return sb.toString();
    }

    public JSONObject call(String iface, Map<String, String> extra) throws Exception {
        Map<String, String> p = base(iface);
        if (extra != null) p.putAll(extra);
        byte[] body = enc(p).getBytes("UTF-8");
        HttpURLConnection c = (HttpURLConnection) new URL(BASE).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(20000);
        c.setReadTimeout(40000);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        OutputStream o = c.getOutputStream();
        o.write(body);
        o.flush();
        o.close();
        InputStream in = c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        c.disconnect();
        String raw = new String(bo.toByteArray(), "UTF-8");
        JSONObject d = new JSONObject(raw);
        String msg = d.toString();
        if ((d.optString("msgId", "").contains("expired"))
                || "-8".equals(d.optString("errorCode", ""))
                || msg.contains("会话已失效") || msg.contains("会话已过期")) {
            throw new SessionExpired(d.optString("error", "会话过期"));
        }
        return d;
    }

    // ---------- 基础数据 ----------
    public Map<String, JSONObject> colors() throws Exception {
        Map<String, String> e = new HashMap<>();
        e.put("type", "sc_dict_color");
        e.put("offset", "0");
        e.put("limit", "2000");
        e.put("lastSyncTime", "2000-01-01 00:00:00");
        JSONObject r = call("cs-syncdata", e);
        return toMap(r.optJSONArray("dataList"));
    }

    public Map<String, JSONObject> sizes() throws Exception {
        Map<String, String> e = new HashMap<>();
        e.put("type", "sc_dict_size");
        e.put("offset", "0");
        e.put("limit", "2000");
        e.put("lastSyncTime", "2000-01-01 00:00:00");
        JSONObject r = call("cs-syncdata", e);
        return toMap(r.optJSONArray("dataList"));
    }

    private static Map<String, JSONObject> toMap(JSONArray a) {
        Map<String, JSONObject> m = new LinkedHashMap<>();
        if (a == null) return m;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) m.put(o.optString("name"), o);
        }
        return m;
    }

    public List<String> codes() throws Exception {
        List<String> out = new ArrayList<>();
        int off = 0;
        while (true) {
            Map<String, String> e = new HashMap<>();
            e.put("type", "dres_style");
            e.put("offset", String.valueOf(off));
            e.put("limit", "1000");
            e.put("lastSyncTime", "2000-01-01 00:00:00");
            JSONObject r = call("cs-syncdata", e);
            JSONArray dl = r.optJSONArray("dataList");
            if (dl == null) break;
            for (int i = 0; i < dl.length(); i++) {
                JSONObject o = dl.optJSONObject(i);
                if (o != null && !"1".equals(o.optString("delflag", "0"))) {
                    out.add(o.optString("code"));
                }
            }
            off += dl.length();
            if (dl.length() < 1000) break;
        }
        return out;
    }

    public JSONObject createColor(String name) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("name", name);
        payload.put("parenttypeid", "");
        payload.put("typeid", "601");
        payload.put("action", "add");
        payload.put("parentid", "8");
        payload.put("delflag", 0);
        Map<String, String> e = new HashMap<>();
        e.put("epid", "430817");
        e.put("jsonparam", payload.toString());
        return call("cs-save-dict-full", e);
    }

    /** 上传一张图片，返回 fileid（与网页版参数一致） */
    public String uploadImage(byte[] data, String filename) throws Exception {
        String boundary = "----slh" + Long.toHexString(System.nanoTime());
        Map<String, String> p = new LinkedHashMap<>();
        p.put("productVersion", "2.6.9");
        p.put("dlUseFieldNameAlias", "1");
        p.put("productCode", "pocketSLhPro");
        p.put("dlProductCode", "pocketSLhPro");
        p.put("deviceType", "9");
        p.put("slh_version", "2.6.9");
        p.put("sessionid", sid);
        p.put("sessionId", sid);
        String url = "http://" + HOST + ":" + PORT + "/slh/uploadMultiFile.do?" + enc(p);

        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        bo.write(("--" + boundary + "\r\n").getBytes("UTF-8"));
        bo.write(("Content-Disposition: form-data; name=\"" + filename
                + "\"; filename=\"" + filename + "\"\r\n").getBytes("UTF-8"));
        bo.write("Content-Type: application/octet-stream\r\n\r\n".getBytes("UTF-8"));
        bo.write(data);
        bo.write(("\r\n--" + boundary + "--\r\n").getBytes("UTF-8"));
        byte[] body = bo.toByteArray();

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(20000);
        c.setReadTimeout(120000);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        OutputStream o = c.getOutputStream();
        o.write(body);
        o.flush();
        o.close();
        InputStream in = c.getInputStream();
        ByteArrayOutputStream rb = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) rb.write(buf, 0, n);
        in.close();
        c.disconnect();
        return new String(rb.toByteArray(), "UTF-8").trim();
    }

    public JSONObject createStyle(String code, String name, List<String> colorSids,
                                  List<String> sizeSids, String fileid, String purprice) throws Exception {
        JSONObject p = new JSONObject();
        p.put("fileid", fileid == null ? "" : fileid);
        p.put("videoId", "");
        p.put("videoUrl", "");
        p.put("coverUrl", "");
        p.put("code", code);
        p.put("name", name);
        p.put("classid", "");
        p.put("brandid", "");
        p.put("colorids", Text.join(colorSids));
        p.put("sizeids", Text.join(sizeSids));
        p.put("marketdate", Text.today());
        if (purprice != null && purprice.matches("\\d+(\\.\\d+)?"))
            p.put("purprice", new java.math.BigDecimal(purprice));
        else
            p.put("purprice", "");
        p.put("stdprice1", "");
        p.put("uselastprice", "1");
        p.put("special", "0");
        p.put("purinjson", JSONObject.NULL);
        p.put("dwid", "");
        p.put("season", "");
        p.put("year1", Text.year());
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
        return call("sf-1511", e);
    }
}
