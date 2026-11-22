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

    private static void pump(InputStream in, OutputStream out) {
        try {
            byte[] b = new byte[8192];
            int r;
            while ((r = in.read(b)) > 0) { out.write(b, 0, r); out.flush(); }
        } catch (Throwable ignored) {}
    }

    private void handle(Socket c, Found cb) {
        try {
            c.setSoTimeout(8000);
            InputStream in = c.getInputStream();
            byte[] buf = new byte[16384];
            int n = in.read(buf);          // 阻塞读第一段
            if (n <= 0) { log("连上但没数据"); c.close(); return; }
            log("首包 " + n + " 字节: " + new String(buf, 0, Math.min(60, n), "UTF-8").replace("\n", " "));
            String head = new String(buf, 0, n, "UTF-8");
            int hdrEnd = head.indexOf("\r\n\r\n");
            int bodyStart = hdrEnd >= 0 ? hdrEnd + 4 : n;
            String headerPart = head.substring(0, Math.max(0, Math.min(bodyStart, head.length())));

            Matcher m = SID.matcher(head);
            if (m.find() && sid == null) {
                sid = m.group(1);
                log("抓到 sessionid");
                cb.onFound(sid);
            }

            String[] lines = headerPart.split("\r?\n");
            if (lines.length == 0) { c.close(); return; }
            String[] parts = lines[0].split(" ");
            if (parts.length < 3) { c.close(); return; }
            String method = parts[0], url = parts[1];

            String host = null; int port = 80; String path = url;
            for (String line : lines) {
                if (line.toLowerCase().startsWith("host:")) host = line.substring(5).trim();
            }
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
            if ("CONNECT".equalsIgnoreCase(method)) {
                // HTTPS：只建隧道，不解密，保证其它 App 正常上网
                String hp = parts[1];
                String h2 = hp; int p2 = 443;
                if (hp.contains(":")) {
                    h2 = hp.substring(0, hp.indexOf(':'));
                    p2 = Integer.parseInt(hp.substring(hp.indexOf(':') + 1));
                }
                Socket t = new Socket();
                t.connect(new InetSocketAddress(h2, p2), 8000);
                OutputStream to = c.getOutputStream();
                to.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes("UTF-8"));
                to.flush();
                final InputStream cin = c.getInputStream();
                final InputStream tin = t.getInputStream();
                final OutputStream tout = t.getOutputStream();
                final OutputStream cout = c.getOutputStream();
                Thread t1 = new Thread(() -> pump(cin, tout));
                Thread t2 = new Thread(() -> pump(tin, cout));
                t1.setDaemon(true); t2.setDaemon(true);
                t1.start(); t2.start();
                t1.join(60000); t2.join(60000);
                try { t.close(); } catch (Throwable ignored) {}
                try { c.close(); } catch (Throwable ignored) {}
                return;
            }
            if (host == null) { c.close(); return; }

            Socket up = new Socket();
            up.connect(new InetSocketAddress(host, port), 8000);
            OutputStream uo = up.getOutputStream();
            StringBuilder req = new StringBuilder();
            req.append(method).append(" ").append(path).append(" ").append(parts[2]).append("\r\n");
            for (int i = 1; i < lines.length; i++) {
                if (!lines[i].isEmpty()) req.append(lines[i]).append("\r\n");
            }
            req.append("\r\n");
            uo.write(req.toString().getBytes("UTF-8"));
            if (bodyStart < n) uo.write(buf, bodyStart, n - bodyStart);
            uo.flush();

            InputStream ui = up.getInputStream();
            OutputStream co = c.getOutputStream();
            byte[] b2 = new byte[8192];
            int r;
            while ((r = ui.read(b2)) > 0) co.write(b2, 0, r);
            co.flush();
            up.close();
            c.close();
        } catch (Throwable t) {
            log("处理异常: " + t);
            try { c.close(); } catch (Throwable ignored) {}
        }
    }
}
