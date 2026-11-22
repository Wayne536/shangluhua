package com.slh.app;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.util.CharsetUtil;
import org.littleshoot.proxy.HttpFilters;
import org.littleshoot.proxy.HttpFiltersAdapter;
import org.littleshoot.proxy.HttpFiltersSourceAdapter;
import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 用 LittleProxy 做抓包（成熟的转发实现） */
public class ProxyCatcher {

    public interface Found { void onFound(String sid); }
    public interface Log { void log(String s); }

    private static final Pattern SID = Pattern.compile("sessionid=([^&\\s\"']+)");
    private HttpProxyServer server;
    private volatile String sid;
    private Found cb;
    private Log logger;

    public void start(int port, Found found, Log log) throws Exception {
        this.cb = found;
        this.logger = log;
        log("启动 LittleProxy，端口 " + port);
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        System.setProperty("org.slf4j.simpleLogger.logFile", "System.out");
        System.setProperty("org.slf4j.simpleLogger.showThreadName", "false");
        server = DefaultHttpProxyServer.bootstrap()
                .withPort(port)
                .withFiltersSource(new HttpFiltersSourceAdapter() {
                    @Override
                    public HttpFilters filterRequest(HttpRequest request) {
                        String uri = request.getUri();
                        scan(uri);
                        log("→ " + request.getMethod() + " " + shortUrl(uri));
                        return new HttpFiltersAdapter(request) {
                            @Override
                            public HttpResponse clientToProxyRequest(HttpObject obj) {
                                if (obj instanceof HttpContent) {
                                    ByteBuf b = ((HttpContent) obj).content();
                                    if (b != null && b.readableBytes() > 0) {
                                        scan(b.toString(CharsetUtil.UTF_8));
                                    }
                                }
                                return null;
                            }

                            @Override
                            public void proxyToServerConnectionSucceeded(io.netty.channel.ChannelHandlerContext ctx) {
                                log("↑ 上游连上 " + shortUrl(uri));
                            }

                            @Override
                            public void proxyToServerConnectionFailed() {
                                log("✗ 上游失败 " + shortUrl(uri));
                            }

                            @Override
                            public HttpObject serverToProxyResponse(HttpObject o) {
                                if (o instanceof HttpResponse) {
                                    log("← " + ((HttpResponse) o).getStatus().code() + " " + shortUrl(uri));
                                }
                                return o;
                            }
                        };
                    }
                })
                .start();
        log("代理已就绪");
        new Thread(() -> {
            String host = "cc.hzecool.com";
            int testPort = 7580;
            long t0 = System.currentTimeMillis();
            try (java.net.Socket s1 = new java.net.Socket()) {
                s1.connect(new java.net.InetSocketAddress(host, testPort), 6000);
                log("自检：连通 " + host + ":" + testPort + " 用时 " + (System.currentTimeMillis() - t0) + "ms");
            } catch (Throwable t) {
                log("自检：连不上 " + host + ":" + testPort + " -> " + t);
                try {
                    java.net.InetAddress[] addrs = java.net.InetAddress.getAllByName(host);
                    log("自检：DNS 解析到 " + (addrs.length > 0 ? addrs[0].getHostAddress() : "空"));
                } catch (Throwable t2) {
                    log("自检：DNS 解析失败 -> " + t2);
                }
            }
        }).start();

        // 测非标准端口的 CONNECT 隧道
        new Thread(() -> {
            String[][] targets = {{"cc.hzecool.com", "7580"}, {"codepush.hzecool.com", "3443"},
                                  {"dsa13a.slh.hzdlsoft.com", "8413"}, {"www.baidu.com", "443"}};
            for (String[] t : targets) {
                try (java.net.Socket sk = new java.net.Socket()) {
                    sk.connect(new java.net.InetSocketAddress("127.0.0.1", port), 5000);
                    sk.setSoTimeout(8000);
                    String req = "CONNECT " + t[0] + ":" + t[1] + " HTTP/1.1\r\nHost: " + t[0] + ":" + t[1] + "\r\n\r\n";
                    sk.getOutputStream().write(req.getBytes("ISO-8859-1"));
                    sk.getOutputStream().flush();
                    java.io.InputStream in = sk.getInputStream();
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 60; i++) {
                        int b = in.read();
                        if (b < 0) break;
                        sb.append((char) b);
                        if (sb.toString().endsWith("\r\n\r\n")) break;
                    }
                    String first = sb.toString().split("\r\n")[0];
                    log("隧道自测 " + t[0] + ":" + t[1] + " -> " + first);
                } catch (Throwable e) {
                    log("隧道自测 " + t[0] + ":" + t[1] + " 异常 -> " + e);
                }
            }
        }).start();

        // 决定性测试：让请求真正走一遍本代理
        new Thread(() -> {
            java.net.Proxy proxy = new java.net.Proxy(java.net.Proxy.Type.HTTP,
                    new java.net.InetSocketAddress("127.0.0.1", port));
            for (String url : new String[]{"http://example.com/", "https://www.baidu.com/"}) {
                long t0 = System.currentTimeMillis();
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                            new java.net.URL(url).openConnection(proxy);
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(10000);
                    c.setRequestMethod("GET");
                    int code = c.getResponseCode();
                    log("代理自测 " + url + " -> " + code + " 用时 "
                            + (System.currentTimeMillis() - t0) + "ms");
                    c.disconnect();
                } catch (Throwable e) {
                    log("代理自测 " + url + " 失败 -> " + e
                            + " 用时 " + (System.currentTimeMillis() - t0) + "ms");
                }
            }
        }).start();
    }

    private static String shortUrl(String u) {
        if (u == null) return "";
        return u.length() > 60 ? u.substring(0, 60) : u;
    }

    private void log(String s) {
        if (logger != null) logger.log(s);
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

    public String sid() { return sid; }

    public void stop() {
        try { if (server != null) server.stop(); } catch (Throwable ignored) {}
    }
}
