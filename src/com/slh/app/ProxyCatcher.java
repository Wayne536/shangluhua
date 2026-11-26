package com.slh.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量透明代理：纯 Socket 做 TCP 转发，不解密、不改内容，
 * 顺便从明文请求里捞 sessionid。CONNECT 和普通 HTTP 都支持。
 */
public class ProxyCatcher {

    public interface Found { void onFound(String sid); }
    public interface Log { void log(String s); }

    public static final int PORT = 8899;
    /** 8899 被占用时依次往后试，避免抓包直接失败 */
    public static final int[] PORTS = {8899, 8900, 8901};

    /** 抓会话时拦截商陆花的数据隧道，逼它回退到明文 HTTP 接口 */
    public static volatile boolean blockDataTunnel = false;

    private static final Pattern SID = Pattern.compile("sessionid=([^&\\s\"';]+)");
    private static final byte[] HEAD_END = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
    private static final int MAX_HEAD = 64 * 1024;
    /** 请求体先读这么多再转发：上游连不上时也能从请求里捞到 sessionid */
    private static final int MAX_BODY_SCAN = 256 * 1024;
    /** 自测用：模拟所有上游都连不上 */
    public static volatile boolean testFailUpstream = false;
    /** 抓包调试：把 POST 的请求体也写进日志（找接口用） */
    public static volatile boolean dumpBody = false;

    private ServerSocket server;
    private volatile boolean running;
    private volatile String sid;
    private Found cb;
    private Log logger;
    private final List<Socket> sockets = Collections.synchronizedList(new ArrayList<>());
    /** 商陆花的数据隧道（加密），开抓时要掐断，逼它回落到明文接口 */
    private final List<Socket> tunnels = Collections.synchronizedList(new ArrayList<>());

    public static boolean isDataTunnel(String host) {
        return host.contains("dsa13a") || host.contains("gdoc01c");
    }

    /** 调试：把隧道首包按十六进制打出来，看看到底是不是 TLS */
    public static volatile boolean dumpTunnel = false;

    /** 主动掐断已经连着的数据隧道 */
    public void killDataTunnels() {
        synchronized (tunnels) {
            for (Socket s : tunnels) closeQuietly(s);
            tunnels.clear();
        }
        log("已断开商陆花数据隧道");
    }

    /** 从 8899 开始找空闲端口，返回实际用上的端口 */
    public int startAuto(Found found, Log log) throws Exception {
        Exception last = null;
        for (int p : PORTS) {
            try {
                start(p, found, log);
                return p;
            } catch (Exception e) {
                last = e;
                closeQuietly(server);
                server = null;
                log.log("端口 " + p + " 被占用，换下一个");
            }
        }
        throw last == null ? new Exception("没有可用端口") : last;
    }

    public void start(int port, Found found, Log log) throws Exception {
        this.cb = found;
        this.logger = log;
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress("127.0.0.1", port));
        running = true;
        Thread t = new Thread(this::acceptLoop, "proxy-accept");
        t.setDaemon(true);
        t.start();
        log("简易代理已启动，端口 " + port);
    }

    public String sid() {
        return sid;
    }

    public void stop() {
        running = false;
        closeQuietly(server);
        synchronized (sockets) {
            for (Socket s : sockets) closeQuietly(s);
            sockets.clear();
        }
    }

    // ---------------- 连接处理 ----------------

    private void acceptLoop() {
        while (running) {
            try {
                Socket c = server.accept();
                c.setTcpNoDelay(true);
                track(c);
                Thread t = new Thread(() -> handle(c), "proxy-conn");
                t.setDaemon(true);
                t.start();
            } catch (Throwable e) {
                if (running) log("代理 accept 异常：" + e);
                break;
            }
        }
    }

    private void handle(Socket client) {
        Socket upstream = null;
        try {
            client.setSoTimeout(15000);
            byte[] head = readHead(client);
            if (head == null) return;

            int end = indexOf(head, HEAD_END);
            if (end < 0) return;
            String headStr = new String(head, 0, end, StandardCharsets.ISO_8859_1);
            String[] lines = headStr.split("\r\n");
            if (lines.length == 0) return;
            String[] parts = lines[0].split(" ");
            if (parts.length < 3) return;
            String method = parts[0], target = parts[1], version = parts[2];

            Map<String, String> headers = new LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int idx = lines[i].indexOf(':');
                if (idx > 0) {
                    headers.put(lines[i].substring(0, idx).trim().toLowerCase(Locale.ROOT),
                            lines[i].substring(idx + 1).trim());
                }
            }

            int extraLen = head.length - end - HEAD_END.length;
            byte[] extra = new byte[Math.max(0, extraLen)];
            if (extraLen > 0) System.arraycopy(head, end + HEAD_END.length, extra, 0, extraLen);

            if ("CONNECT".equalsIgnoreCase(method)) {
                String host = target;
                int port = 443;
                int lp = target.lastIndexOf(':');
                if (lp > 0 && (target.indexOf(']') < lp)) {
                    host = target.substring(0, lp);
                    port = parseInt(target.substring(lp + 1), 443);
                }
                host = stripBrackets(host);
                if (blockDataTunnel && isDataTunnel(host)) {
                    log("拦截数据隧道 " + host + ":" + port + "，逼它走明文接口");
                    sendError(client, "502 Bad Gateway");
                    return;
                }
                log("→ CONNECT " + host + ":" + port);
                upstream = connect(host, port);
                if (upstream == null) {
                    sendError(client, "502 Bad Gateway");
                    return;
                }
                log("↑ 上游连上 " + host + ":" + port);
                if (isDataTunnel(host)) tunnels.add(upstream);
                OutputStream co = client.getOutputStream();
                co.write("HTTP/1.1 200 Connection Established\r\n\r\n"
                        .getBytes(StandardCharsets.ISO_8859_1));
                co.flush();
                if (extra.length > 0) {
                    OutputStream uo = upstream.getOutputStream();
                    uo.write(extra);
                    uo.flush();
                }
                if (dumpTunnel && isDataTunnel(host)) {
                    log("[tunnel] " + host + ":" + port + " 开始记录首包");
                    pumpBoth(client, upstream, false, true);
                } else {
                    pumpBoth(client, upstream, false, false);
                }
                return;
            }

            String host;
            int port = 80;
            String path = target;
            if (target.regionMatches(true, 0, "http://", 0, 7)) {
                try {
                    URI uri = URI.create(target);
                    host = uri.getHost();
                    if (uri.getPort() > 0) port = uri.getPort();
                    path = uri.getRawPath();
                    if (path == null || path.isEmpty()) path = "/";
                    if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
                } catch (Throwable t) {
                    host = headers.get("host");
                }
            } else {
                host = headers.get("host");
            }
            if (host == null) {
                log("✗ 请求没有 Host：" + target);
                sendError(client, "400 Bad Request");
                return;
            }
            if (host.startsWith("[")) {
                int rb = host.indexOf(']');
                if (rb > 0) {
                    String rest = host.substring(rb + 1);
                    if (rest.startsWith(":")) port = parseInt(rest.substring(1), 80);
                    host = host.substring(0, rb + 1);
                }
            } else if (host.indexOf(':') > 0) {
                int c = host.lastIndexOf(':');
                port = parseInt(host.substring(c + 1), 80);
                host = host.substring(0, c);
            }
            host = stripBrackets(host);

            log("→ " + method + " http://" + host + path);
            // 先把请求体读下来扫一遍：这样即使上游连不上（页面报网络错误），
            // 只要请求里带了 sessionid 也照样抓得到
            int clen = parseInt(headers.get("content-length"), 0);
            byte[] body = readBody(client, extra, clen);
            scan(headStr);
            scan(new String(body, StandardCharsets.ISO_8859_1));
            if (dumpBody && body.length > 0 && "POST".equalsIgnoreCase(method)) {
                String b = new String(body, StandardCharsets.UTF_8);
                log("[req " + host + "] " + (b.length() > 3000 ? b.substring(0, 3000) + "…" : b));
            }

            if (testFailUpstream && path.contains("api.do")) {
                log("自测：故意让 " + path + " 连接失败");
                sendError(client, "502 Bad Gateway");
                return;
            }
            upstream = connect(host, port);
            if (upstream == null) {
                sendError(client, "502 Bad Gateway");
                return;
            }
            log("↑ 上游连上 " + host + ":" + port);

            StringBuilder req = new StringBuilder();
            req.append(method).append(' ').append(path).append(' ').append(version).append("\r\n");
            for (int i = 1; i < lines.length; i++) {
                String low = lines[i].toLowerCase(Locale.ROOT);
                if (low.startsWith("proxy-connection:") || low.startsWith("proxy-authorization:")) continue;
                req.append(lines[i]).append("\r\n");
            }
            req.append("\r\n");

            OutputStream uo = upstream.getOutputStream();
            uo.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (body.length > 0) uo.write(body);
            uo.flush();

            pumpBoth(client, upstream, true);
        } catch (Throwable t) {
            log("代理连接异常：" + t);
        } finally {
            closeQuietly(client);
            closeQuietly(upstream);
            untrack(client);
            untrack(upstream);
            if (upstream != null) tunnels.remove(upstream);
        }
    }

    private void pumpBoth(Socket client, Socket upstream, boolean plainHttp) {
        pumpBoth(client, upstream, plainHttp, false);
    }

    private void pumpBoth(Socket client, Socket upstream, boolean plainHttp, boolean dump) {
        Thread up = new Thread(() -> pump(client, upstream, plainHttp, false, dump, "c2s"), "proxy-c2u");
        Thread down = new Thread(() -> pump(upstream, client, false, plainHttp, dump, "s2c"), "proxy-u2c");
        up.setDaemon(true);
        down.setDaemon(true);
        up.start();
        down.start();
        try {
            up.join();
            down.join();
        } catch (InterruptedException ignored) {
        }
    }

    private void pump(Socket from, Socket to, boolean scan, boolean logStatus) {
        pump(from, to, scan, logStatus, false, "");
    }

    private void pump(Socket from, Socket to, boolean scan, boolean logStatus,
                      boolean dump, String dir) {
        byte[] buf = new byte[8192];
        byte[] carry = new byte[0];
        boolean first = true;
        try {
            from.setSoTimeout(120000);
            to.setSoTimeout(120000);
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                out.flush();
                if (scan && sid == null) {
                    byte[] joined = new byte[carry.length + n];
                    System.arraycopy(carry, 0, joined, 0, carry.length);
                    System.arraycopy(buf, 0, joined, carry.length, n);
                    scan(new String(joined, StandardCharsets.ISO_8859_1));
                    carry = Arrays.copyOfRange(buf, Math.max(0, n - 64), n);
                }
                if (dump && first) {
                    first = false;
                    StringBuilder hex = new StringBuilder();
                    StringBuilder asc = new StringBuilder();
                    int m = Math.min(n, 48);
                    for (int i = 0; i < m; i++) {
                        int b = buf[i] & 0xff;
                        hex.append(String.format("%02x ", b));
                        asc.append(b >= 32 && b < 127 ? (char) b : '.');
                    }
                    log("[tunnel " + dir + "] " + n + "B " + hex);
                    log("[tunnel " + dir + "] ascii " + asc);
                }
                if (logStatus && first) {
                    first = false;
                    String s = new String(buf, 0, Math.min(n, 80), StandardCharsets.ISO_8859_1);
                    Matcher m = Pattern.compile("^HTTP/1\\.[01] (\\d{3})").matcher(s);
                    if (m.find()) log("← " + m.group(1));
                }
            }
        } catch (SocketTimeoutException ignored) {
        } catch (Throwable ignored) {
        }
        try {
            to.shutdownOutput();
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 工具 ----------------

    private byte[] readBody(Socket client, byte[] extra, int clen) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        if (extra.length > 0) b.write(extra, 0, extra.length);
        if (clen <= 0 || clen > MAX_BODY_SCAN) return b.toByteArray();
        try {
            InputStream in = client.getInputStream();
            byte[] buf = new byte[8192];
            while (b.size() < clen) {
                int n = in.read(buf, 0, Math.min(buf.length, clen - b.size()));
                if (n <= 0) break;
                b.write(buf, 0, n);
            }
        } catch (Throwable ignored) {
        }
        return b.toByteArray();
    }

    private Socket connect(String host, int port) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 6000);
            s.setSoTimeout(120000);
            s.setTcpNoDelay(true);
            track(s);
            return s;
        } catch (Throwable t) {
            log("✗ 上游失败 " + host + ":" + port + " " + t);
            closeQuietly(s);
            return null;
        }
    }

    private void scan(String s) {
        if (s == null || sid != null) return;
        Matcher m = SID.matcher(s);
        if (m.find()) {
            sid = m.group(1);
            log("抓到 sessionid: " + sid);
            if (cb != null) cb.onFound(sid);
        }
    }

    private void sendError(Socket c, String status) {
        try {
            OutputStream o = c.getOutputStream();
            o.write(("HTTP/1.1 " + status + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            o.flush();
        } catch (Throwable ignored) {
        }
    }

    private static byte[] readHead(Socket s) {
        try {
            InputStream in = s.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            while (b.size() < MAX_HEAD) {
                int n = in.read(buf);
                if (n < 0) return null;
                b.write(buf, 0, n);
                byte[] cur = b.toByteArray();
                if (indexOf(cur, HEAD_END) >= 0) return cur;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static int indexOf(byte[] data, byte[] pat) {
        outer:
        for (int i = 0; i <= data.length - pat.length; i++) {
            for (int j = 0; j < pat.length; j++) {
                if (data[i + j] != pat[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static String stripBrackets(String h) {
        if (h != null && h.startsWith("[") && h.endsWith("]")) {
            return h.substring(1, h.length() - 1);
        }
        return h;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private void track(Socket s) {
        if (s != null) sockets.add(s);
    }

    private void untrack(Socket s) {
        if (s != null) sockets.remove(s);
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void closeQuietly(ServerSocket s) {
        if (s != null) {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private void log(String s) {
        if (logger != null) logger.log(s);
    }
}
