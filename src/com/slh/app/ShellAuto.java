package com.slh.app;

import java.io.FileWriter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在 Shizuku 的 shell 进程里执行（App 用 app_process 拉起），不依赖入库助手是否还活着。
 *
 * 打开商陆花 → 进「货品」页 → 临时挂系统代理 → 下拉刷新 →
 * 一抓到 sessionid 就落盘、恢复代理、切回入库助手，不再继续刷。
 */
public class ShellAuto {

    static final String SID_PATH = "/data/local/tmp/slh_sid.txt";
    static final String POS_PATH = "/data/local/tmp/slh_goods_pos";
    static final String LOG_PATH = "/data/local/tmp/slh_auto.log";
    static final int PORT = 8899;

    /** 抓到（或失败）之后，主线程还在下拉就不要再继续了 */
    private static final AtomicBoolean done = new AtomicBoolean(false);

    public static void main(String[] args) {
        final boolean failTest = args != null && args.length > 0 && "failup".equals(args[0]);
        say("开始");
        AutoCapture ac = new AutoCapture(ShellAuto::say, true);
        ProxyCatcher proxy = null;
        try {
            ac.sh("wm dismiss-keyguard");
            ac.sh("settings put global http_proxy :0");
            // 代理要先挂上：商陆花启动时建的数据隧道才会经过这里，后面才能掐断
            proxy = new ProxyCatcher();
            proxy.start(PORT, sid -> onFound(ac, sid), ShellAuto::say);
            ac.sh("settings put global http_proxy 127.0.0.1:" + PORT);

            if (!ac.launch()) {
                fail(ac, proxy, "商陆花没打开");
                return;
            }
            ac.openGoods(false);
            proxy.killDataTunnels();
            ProxyCatcher.blockDataTunnel = true;
            sleep(300);
            if (failTest) {
                ProxyCatcher.testFailUpstream = true;
                say("自测模式：从现在起上游全部连不上");
            }
            say("开始下拉刷新…");
            if (pullUntilFound(ac, 4)) {
                sleep(4000); // 等收尾线程把界面切回去（主线程一返回进程就退出）
                return;
            }
            say("没抓到，重新开一次商陆花、确认货品页再补");
            ac.launch();
            ac.openGoods(true);
            if (pullUntilFound(ac, 2)) {
                sleep(4000);
                return;
            }
            fail(ac, proxy, "没抓到会话");
        } catch (Throwable t) {
            say("出错：" + t);
            fail(ac, proxy, "出错");
        }
    }

    /** 抓到会话：立刻落盘 + 恢复系统代理，再切回 App 退出 */
    private static void onFound(final AutoCapture ac, final String sid) {
        if (!done.compareAndSet(false, true)) return;
        say("抓到 sessionid: " + mask(sid));
        write(SID_PATH, sid);
        int[] pos = ac.goodsPos();
        if (pos != null) write(POS_PATH, pos[0] + "," + pos[1]);
        ac.sh("settings put global http_proxy :0");
        ProxyCatcher.blockDataTunnel = false;
        say("系统代理已恢复，正在切回…");
        Thread t = new Thread(() -> {
            sleep(600); // 让这次刷新的响应先走完
            try {
                ac.backToApp();
            } catch (Throwable ignored) {
            }
            say("已切回入库助手");
            sleep(150);
            System.exit(0);
        }, "slh-back");
        // 不能设成守护线程：主线程一返回，进程就退出了，App 还没被切回来
        t.start();
    }

    /** 拉一次 → 每 250ms 看一眼，抓到就停 */
    private static boolean pullUntilFound(AutoCapture ac, int rounds) {
        for (int i = 0; i < rounds; i++) {
            if (done.get()) return true;
            ac.pullOnce();
            long end = System.currentTimeMillis() + 1200;
            while (System.currentTimeMillis() < end) {
                if (done.get()) return true;
                sleep(250);
            }
        }
        return done.get();
    }

    private static void fail(AutoCapture ac, ProxyCatcher proxy, String msg) {
        try {
            ac.sh("settings put global http_proxy :0");
        } catch (Throwable ignored) {
        }
        if (proxy != null) proxy.stop();
        ProxyCatcher.blockDataTunnel = false;
        write(SID_PATH, "-");
        say(msg);
        try {
            ac.backToApp();
        } catch (Throwable ignored) {
        }
        sleep(150);
        System.exit(1);
    }

    private static int[] readPos() {
        java.io.BufferedReader r = null;
        try {
            r = new java.io.BufferedReader(new java.io.FileReader(POS_PATH));
            String s = r.readLine();
            if (s != null) {
                String[] p = s.trim().split(",");
                if (p.length == 2) {
                    int x = Integer.parseInt(p[0].trim());
                    int y = Integer.parseInt(p[1].trim());
                    if (x > 0 && y > 0) return new int[]{x, y};
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static void write(String path, String text) {
        try {
            FileWriter w = new FileWriter(path);
            w.write(text);
            w.close();
        } catch (Throwable ignored) {
        }
    }

    private static String mask(String sid) {
        if (sid == null) return "null";
        return sid.length() <= 16 ? sid : sid.substring(0, 16) + "…";
    }

    private static void say(String s) {
        System.out.println(s);
        try {
            FileWriter w = new FileWriter(LOG_PATH, true);
            w.write(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA)
                    .format(new java.util.Date()) + " " + s + "\n");
            w.close();
        } catch (Throwable ignored) {
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
