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
