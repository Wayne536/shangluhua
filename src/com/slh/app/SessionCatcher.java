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
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    Socket c = server.accept();
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
            c.setSoTimeout(15000);
            InputStream in = c.getInputStream();
            StringBuilder head = new StringBuilder();
            int b, nl = 0;
            while ((b = in.read()) != -1 && nl < 4) {
                head.append((char) b);
                nl = (b == '\n') ? nl + 1 : 0;
            }
            String h = head.toString();
            int clen = 0;
            Matcher cm = Pattern.compile("(?i)content-length:\\s*(\\d+)").matcher(h);
            if (cm.find()) clen = Integer.parseInt(cm.group(1));
            byte[] body = new byte[clen];
            int read = 0;
            while (read < clen) {
                int r = in.read(body, read, clen - read);
                if (r < 0) break;
                read += r;
            }

            String all = h + new String(body, "UTF-8");
            Matcher m = SID.matcher(all);
            if (m.find() && sid == null) {
                sid = m.group(1);
                cb.onFound(sid);
            }

            // 取目标主机
            String[] lines = h.split("\r\n|\n");
            String first = lines[0];
            String[] parts = first.split(" ");
            if (parts.length < 3) { c.close(); return; }
            String url = parts[1];
            String host = null;
            int port = 80;
            String path = url;
            for (String line : lines) {
                if (line.toLowerCase().startsWith("host:")) {
                    host = line.substring(5).trim();
                }
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
            } else if (url.startsWith("https://")) {
                c.close(); return;
            }
            String onlyPath = path;
            Socket up = new Socket();
            up.connect(new InetSocketAddress(host, port), 15000);
            OutputStream uo = up.getOutputStream();
            StringBuilder req = new StringBuilder();
            req.append(parts[0]).append(" ").append(onlyPath).append(" ").append(parts[2]).append("\r\n");
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].isEmpty()) continue;
                req.append(lines[i]).append("\r\n");
            }
            req.append("\r\n");
            uo.write(req.toString().getBytes("UTF-8"));
            if (clen > 0) uo.write(body);
            uo.flush();

            InputStream ui = up.getInputStream();
            byte[] buf = new byte[8192];
            OutputStream co = c.getOutputStream();
            int n;
            while ((n = ui.read(buf)) > 0) co.write(buf, 0, n);
            co.flush();
            up.close();
            c.close();
        } catch (Throwable ignored) {
            try { c.close(); } catch (Throwable ignored2) {}
        }
    }
}
