package com.slh.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 本地小代理：捞商陆花请求里的 sessionid，然后原样转发 */
public class SessionCatcher {

    private java.io.File logFile;
    private void log(String m) {
        try {
            if (logFile == null) return;
            java.io.FileWriter w = new java.io.FileWriter(logFile, true);
            w.write(new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date()) + " " + m + "\n");
            w.close();
        } catch (Throwable ignored) {}
    }
    public void setLogFile(java.io.File f) { logFile = f; }

    public interface Found { void onFound(String sid); }

    private ServerSocket server;
    private volatile boolean running = false;
    private volatile String sid = null;
    public static final int PORT = 8899;
    private static final Pattern SID = Pattern.compile("sessionid=([^&\\s\"']+)");

    public void start(Found cb) throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress("127.0.0.1", PORT));
        running = true;
        log("代理已监听 127.0.0.1:" + PORT);
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    Socket c = server.accept();
                    log("收到连接 " + c.getRemoteSocketAddress());
                    new Thread(() -> handle(c, cb)).start();
                } catch (IOException e) {
                    if (!running) break;
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    public String sid() { return sid; }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) {}
    }

    private void handle(Socket c, Found cb) {
        try {
            c.setSoTimeout(20000);
            InputStream in = c.getInputStream();

            // 1) 读到完整请求头
            java.io.ByteArrayOutputStream headBuf = new java.io.ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            int hdrEnd = -1;
            while (hdrEnd < 0) {
                int r = in.read(tmp);
                if (r <= 0) { c.close(); return; }
                headBuf.write(tmp, 0, r);
                String cur = new String(headBuf.toByteArray(), "ISO-8859-1");
                hdrEnd = cur.indexOf("\r\n\r\n");
                if (headBuf.size() > 65536) { c.close(); return; }
            }
            byte[] all = headBuf.toByteArray();
            String headStr = new String(all, 0, hdrEnd, "ISO-8859-1");
            String[] lines = headStr.split("\r\n");
            String[] parts = lines[0].split(" ");
            if (parts.length < 3) { c.close(); return; }
            String method = parts[0], url = parts[1];
            log("请求: " + method + " " + (url.length() > 70 ? url.substring(0, 70) : url));

            // 2) 按 Content-Length 读完整请求体
            int clen = 0;
            for (String l : lines) if (l.toLowerCase().startsWith("content-length:"))
                clen = Integer.parseInt(l.substring(15).trim());
            java.io.ByteArrayOutputStream bodyBuf = new java.io.ByteArrayOutputStream();
            int already = all.length - (hdrEnd + 4);
            if (already > 0) bodyBuf.write(all, hdrEnd + 4, already);
            while (bodyBuf.size() < clen) {
                int r = in.read(tmp);
                if (r <= 0) break;
                bodyBuf.write(tmp, 0, r);
            }
            byte[] body = bodyBuf.toByteArray();

            // 3) 顺带捞 sessionid
            String probe = headStr + new String(body, "ISO-8859-1");
            Matcher m = SID.matcher(probe);
            if (m.find() && sid == null) {
                sid = m.group(1);
                log("抓到 sessionid: " + sid);
                cb.onFound(sid);
            }

            if ("CONNECT".equalsIgnoreCase(method)) {
                String hp = url; String h2 = hp; int p2 = 443;
                if (hp.contains(":")) {
                    h2 = hp.substring(0, hp.indexOf(':'));
                    p2 = Integer.parseInt(hp.substring(hp.indexOf(':') + 1));
                }
                final Socket t = new Socket();
                t.connect(new InetSocketAddress(h2, p2), 8000);
                OutputStream to = c.getOutputStream();
                to.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes("ISO-8859-1"));
                to.flush();
                if (body.length > 0) { t.getOutputStream().write(body); t.getOutputStream().flush(); }
                final InputStream cin = in;
                final InputStream tin = t.getInputStream();
                Thread t1 = new Thread(() -> pump(cin, t));
                Thread t2 = new Thread(() -> pump(tin, c));
                t1.setDaemon(true); t2.setDaemon(true);
                t1.start(); t2.start();
                t1.join(120000); t2.join(120000);
                try { t.close(); } catch (Throwable ignored) {}
                try { c.close(); } catch (Throwable ignored) {}
                return;
            }

            // 4) 普通 HTTP 转发
            String host = null; int port = 80; String path = url;
            for (String l : lines) if (l.toLowerCase().startsWith("host:")) host = l.substring(5).trim();
            if (url.startsWith("http://")) {
                String rest = url.substring(7);
                int slash = rest.indexOf('/');
                String hp = slash >= 0 ? rest.substring(0, slash) : rest;
                path = slash >= 0 ? rest.substring(slash) : "/";
                if (hp.contains(":")) {
                    host = hp.substring(0, hp.indexOf(':'));
                    port = Integer.parseInt(hp.substring(hp.indexOf(':') + 1));
                } else host = hp;
            }
            if (host == null) { c.close(); return; }
            log("转发到 " + host + ":" + port);

            Socket up = new Socket();
            up.connect(new InetSocketAddress(host, port), 8000);
            OutputStream uo = up.getOutputStream();
            StringBuilder req = new StringBuilder();
            req.append(method).append(" ").append(path).append(" ").append(parts[2]).append("\r\n");
            for (int i = 1; i < lines.length; i++) {
                String l = lines[i];
                if (l.isEmpty()) continue;
                String low = l.toLowerCase();
                if (low.startsWith("proxy-connection") || low.startsWith("connection:")) continue;
                req.append(l).append("\r\n");
            }
            req.append("Connection: close\r\n\r\n");
            uo.write(req.toString().getBytes("ISO-8859-1"));
            if (body.length > 0) uo.write(body);
            uo.flush();

            InputStream ui = up.getInputStream();
            OutputStream co = c.getOutputStream();
            byte[] b2 = new byte[8192];
            int r;
            while ((r = ui.read(b2)) > 0) { co.write(b2, 0, r); }
            co.flush();
            up.close();
            c.close();
        } catch (Throwable t) {
            log("异常: " + t);
            try { c.close(); } catch (Throwable ignored) {}
        }
    }

    private static void pump(InputStream in, Socket out) {
        try {
            OutputStream o = out.getOutputStream();
            byte[] b = new byte[8192];
            int r;
            while ((r = in.read(b)) > 0) { o.write(b, 0, r); o.flush(); }
        } catch (Throwable ignored) {}
        try { out.shutdownOutput(); } catch (Throwable ignored) {}
    }
}
