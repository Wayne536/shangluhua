package com.slh.app;

import java.io.FileWriter;

/**
 * 在 Shizuku 的 shell 进程里执行（由 App 用 app_process 拉起），
 * 不依赖入库助手自身是否还活着 —— 商陆花到前台时 App 会被系统冻结，
 * 这里把整段自动化放到 shell 侧跑：
 *
 * 打开商陆花 → 进「货品」页 → 临时挂系统代理 → 下拉刷新 →
 * 抓到 sessionid 写文件 → 立刻恢复代理 → 切回入库助手。
 */
public class ShellAuto {

    static final String SID_PATH = "/data/local/tmp/slh_sid.txt";
    static final String LOG_PATH = "/data/local/tmp/slh_auto.log";
    static final int PORT = 8899;

    public static void main(String[] args) {
        say("开始");
        AutoCapture ac = new AutoCapture(ShellAuto::say, true);
        ProxyCatcher proxy = null;
        String sid = null;
        try {
            ac.sh("wm dismiss-keyguard");
            ac.sh("settings put global http_proxy :0");
            say("先挂代理 127.0.0.1:" + PORT + "，再启动商陆花（抓完立刻恢复）");
            proxy = new ProxyCatcher();
            ProxyCatcher.blockDataTunnel = false;
            proxy.start(PORT, id -> {}, ShellAuto::say);
            ac.sh("settings put global http_proxy 127.0.0.1:" + PORT);
            if (!ac.launch()) {
                say("商陆花没打开，放弃");
                return;
            }
            if (!ac.openGoods()) {
                say("没确认到货品页，还是试一下刷新");
            }
            proxy.killDataTunnels();
            ProxyCatcher.blockDataTunnel = true;
            say("开始下拉刷新，等 sessionid…");
            ac.pullRefresh();
            long end = System.currentTimeMillis() + 20000;
            while (System.currentTimeMillis() < end && proxy.sid() == null) {
                Thread.sleep(300);
            }
            sid = proxy.sid();
        } catch (Throwable t) {
            say("出错：" + t);
        } finally {
            try {
                ac.sh("settings put global http_proxy :0");
            } catch (Throwable ignored) {
            }
            if (proxy != null) proxy.stop();
            ProxyCatcher.blockDataTunnel = false;
            try {
                FileWriter w = new FileWriter(SID_PATH);
                w.write(sid == null ? "-" : sid);
                w.close();
            } catch (Throwable ignored) {
            }
            say(sid == null ? "没抓到会话" : "抓到会话 " + mask(sid));
            try {
                ac.backToApp();
            } catch (Throwable ignored) {
            }
            say("结束");
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
            }
            System.exit(sid == null ? 1 : 0);
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
}
