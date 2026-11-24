package com.slh.app;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import rikka.shizuku.Shizuku;

/**
 * 用 Shizuku 的 shell 权限替用户操作商陆花：
 * 冷启动 → 进入「货品」页 → 下拉刷新。
 *
 * 全部走 am / input / uiautomator dump，不申请无障碍，也不用悬浮窗。
 * 没有 Shizuku 时由调用方退回手动流程。
 */
public class AutoCapture {

    public static final String SLH_PKG = "com.slh_mobile_pro";
    public static final String SELF_ACT = "com.slh.app/com.slh.app.MainActivity";
    private static final String DUMP_PATH = "/data/local/tmp/slh_auto_ui.xml";

    /** 货品页的识别特征：排序栏、类别栏、搜索框、库存数字 */
    private static final String[] GOODS_MARKS = {"智能排序", "全部类别", "输入款号", "库存-"};
    /** 启动时可能挡路的弹窗 */
    private static final String[] POPUPS = {"以后再说", "暂不更新", "跳过", "我知道了"};

    private static final Pattern NODE = Pattern.compile("<node\\b[^>]*>");
    private static final Pattern P_TEXT = Pattern.compile("\\btext=\"([^\"]*)\"");
    private static final Pattern P_DESC = Pattern.compile("\\bcontent-desc=\"([^\"]*)\"");
    private static final Pattern P_BOUNDS =
            Pattern.compile("\\bbounds=\"\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]\"");

    public interface Logger { void log(String s); }

    private final Logger log;
    private final boolean shellMode;
    private final int w, h;
    /** 用户点了取消：后续步骤不再执行 */
    public volatile boolean cancelled;
    /** 上次确认到的「货品」入口坐标，下次可以直接点，省一次读界面 */
    private int[] goodsPos;

    public AutoCapture(Logger log) {
        this(log, false);
    }

    /** @param shellMode true 表示本进程已经是 shell（app_process），直接 exec 不走 Shizuku */
    public AutoCapture(Logger log, boolean shellMode) {
        this.log = log;
        this.shellMode = shellMode;
        int[] sz = screenSize();
        this.w = sz[0];
        this.h = sz[1];
    }

    private int[] screenSize() {
        String out = sh("wm size");
        Matcher m = Pattern.compile("(\\d+)x(\\d+)").matcher(out);
        int[] sz = {1080, 2400};
        while (m.find()) {
            sz[0] = Integer.parseInt(m.group(1));
            sz[1] = Integer.parseInt(m.group(2));
        }
        return sz;
    }

    /** Shizuku 在跑并且已授权给本 App */
    public static boolean available() {
        try {
            return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    // ===================== 对外步骤 =====================

    /** 唤醒并确认已解锁；返回 null 表示可以继续，否则是给用户看的原因 */
    public String prepare() {
        String f = focus();
        if (f.startsWith("ERR:")) {
            return "Shizuku 不可用：" + f.substring(4);
        }
        if (isLocked(f)) {
            sh("input keyevent KEYCODE_WAKEUP");
            sleep(700);
            f = focus();
            if (isLocked(f)) return "手机还锁着，先解锁再点自动抓取";
        }
        return null;
    }

    /** 冷启动商陆花并等它到前台 */
    public boolean launch() {
        if (!healthy()) {
            log.log("Shizuku 连续没响应，先不自动操作了");
            return false;
        }
        String act = launcher();
        log.log("冷启动商陆花");
        sh("timeout 12 am force-stop " + SLH_PKG);
        sleep(350);
        sh("timeout 15 am start -n " + act);
        boolean up = false;
        for (int i = 0; i < 20; i++) {
            if (focus().contains(SLH_PKG)) {
                up = true;
                break;
            }
            if (cancelled) return false;
            sleep(250);
        }
        if (!up) {
            log.log("商陆花没起来，可能被系统拦住了");
            return false;
        }
        sleep(3000); // 等首页渲染（实测 3.5 秒后点才稳）
        return !cancelled;
    }

    /** 进「货品」列表页（读完界面再点） */
    public boolean openGoods() {
        return openGoods(true);
    }

    /**
     * @param verify false 时点完就返回，省掉一次读界面；没抓到时由调用方再来一次
     */
    public boolean openGoods(boolean verify) {
        if (!healthy()) {
            log.log("Shizuku 连续没响应，跳过自动点按");
            return false;
        }
        List<Node> nodes = dump();
        if (nodes.isEmpty()) {
            log.log("读不到商陆花界面（uiautomator 不可用）");
            return false;
        }
        if (dismissPopup(nodes)) {
            nodes = dump();
            if (nodes.isEmpty()) return false;
        }
        if (looksLikeGoods(nodes)) {
            log.log("商陆花已经在「货品」页");
            return true;
        }
        if (cancelled) return false;

        Node goods = find(nodes, "货品");
        if (goods != null) {
            goodsPos = new int[]{goods.cx(), goods.cy()};
            log.log("点首页「货品」→ " + goods.cx() + "," + goods.cy());
            tap(goods);
            sleep(1200);
            if (!verify) {
                log.log("点完「货品」，先当它进去了");
                return true;
            }
            nodes = dump();
            if (looksLikeGoods(nodes)) {
                log.log("已进入「货品」页");
                return true;
            }
            if (nodes.isEmpty()) return false;
        }

        Node stock = find(nodes, "库存");
        if (stock != null && !cancelled) {
            log.log("点底部「库存」");
            tap(stock);
            sleep(1200);
            List<Node> n2 = dump();
            if (looksLikeGoods(n2)) return true;
            Node g2 = find(n2, "货品");
            if (g2 == null) g2 = find(n2, "货品档案");
            if (g2 != null) {
                log.log("点「货品」入口");
                tap(g2);
                sleep(1200);
                if (looksLikeGoods(dump())) return true;
            }
        }

        if (cancelled) return false;
        log.log("按坐标兜底点「货品」卡片");
        tap((int) (w * 0.17), (int) (h * 0.78));
        sleep(1200);
        return looksLikeGoods(dump());
    }

    public int[] goodsPos() {
        return goodsPos;
    }

    /** 用上次记下的坐标直接点「货品」，不再读界面 */
    public void tapGoodsAt(int[] pos) {
        if (pos == null || pos.length < 2) return;
        log.log("点「货品」→ " + pos[0] + "," + pos[1]);
        tap(pos[0], pos[1]);
        sleep(1200);
    }

    /** 下拉刷新一次：从列表顶部慢慢拉到底，拉短了不会触发刷新 */
    public void pullOnce() {
        swipe(w / 2, (int) (h * 0.20), w / 2, (int) (h * 0.98), 1200);
    }

    /** 抓完把界面还给用户 */
    public void backToApp() {
        sh("timeout 12 am start -n " + SELF_ACT);
    }

    // ===================== 界面读取 =====================

    private List<Node> dump() {
        List<Node> out = new ArrayList<>();
        for (int attempt = 0; attempt < 2; attempt++) {
            if (cancelled) break;
            sh("rm -f " + DUMP_PATH);
            sh("timeout 12 uiautomator dump --compressed " + DUMP_PATH);
            out = parse(sh("cat " + DUMP_PATH));
            if (!out.isEmpty()) return out;
            sleep(800);
        }
        return out;
    }

    private static List<Node> parse(String xml) {
        List<Node> out = new ArrayList<>();
        if (xml == null || xml.isEmpty() || xml.startsWith("ERR:")) return out;
        Matcher m = NODE.matcher(xml);
        while (m.find()) {
            String tag = m.group();
            Node n = new Node();
            n.text = attr(tag, P_TEXT);
            n.desc = attr(tag, P_DESC);
            Matcher b = P_BOUNDS.matcher(tag);
            if (!b.find()) continue;
            n.x1 = Integer.parseInt(b.group(1));
            n.y1 = Integer.parseInt(b.group(2));
            n.x2 = Integer.parseInt(b.group(3));
            n.y2 = Integer.parseInt(b.group(4));
            out.add(n);
        }
        return out;
    }

    private static String attr(String tag, Pattern p) {
        Matcher m = p.matcher(tag);
        return m.find() ? m.group(1) : "";
    }

    private static Node find(List<Node> nodes, String label) {
        Node loose = null;
        for (Node n : nodes) {
            if (!n.visible()) continue;
            String t = n.text.trim(), d = n.desc.trim();
            if (t.equals(label) || d.equals(label)) {
                if (loose == null || n.area() < loose.area()) loose = n;
            }
        }
        if (loose != null) return loose;
        for (Node n : nodes) {
            if (!n.visible()) continue;
            if (n.text.trim().contains(label) || n.desc.trim().contains(label)) {
                if (loose == null || n.area() < loose.area()) loose = n;
            }
        }
        return loose;
    }

    private static boolean looksLikeGoods(List<Node> nodes) {
        StringBuilder sb = new StringBuilder();
        for (Node n : nodes) sb.append(n.text).append(' ').append(n.desc).append(' ');
        String s = sb.toString();
        for (String mark : GOODS_MARKS) if (s.contains(mark)) return true;
        return false;
    }

    private boolean dismissPopup(List<Node> nodes) {
        for (String label : POPUPS) {
            Node n = find(nodes, label);
            if (n != null) {
                log.log("关掉弹窗「" + label + "」");
                tap(n);
                sleep(900);
                return true;
            }
        }
        return false;
    }

    private void tap(Node n) {
        tap(n.cx(), n.cy());
    }

    /** 手势必须等它做完：放后台会被一起杀掉，滑动就中断了 */
    private void tap(int x, int y) {
        sh("timeout 10 input tap " + x + " " + y);
    }

    private void swipe(int x1, int y1, int x2, int y2, int ms) {
        sh("timeout 14 input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + ms);
    }

    // ===================== 系统状态 =====================

    /** 当前焦点窗口（mCurrentFocus / mFocusedApp） */
    public String focus() {
        return sh("timeout 8 dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -4");
    }

    private static boolean isLocked(String focus) {
        return focus.contains("NotificationShade")
                || focus.contains("Keyguard")
                || focus.contains("StatusBar");
    }

    /** 商陆花桌面 Activity：优先问系统，取不到就用约定名 */
    private String launcher() {
        String out = sh("timeout 10 cmd package resolve-activity --brief " + SLH_PKG);
        for (String line : out.split("\n")) {
            line = line.trim();
            int slash = line.indexOf('/');
            if (slash > 0 && line.startsWith(SLH_PKG) && !line.contains(" ")) {
                return line;
            }
        }
        return SLH_PKG + "/" + SLH_PKG + ".MainActivity";
    }

    // ===================== shell =====================

    /** 调试开关：打开后每条 shell 命令都记进日志 */
    static final boolean DEBUG = false;

    private volatile int fails;

    /** 每条命令最多等 15 秒：Shizuku 偶发卡住时不至于整个流程停死 */
    public String sh(final String cmd) {
        if (DEBUG) log.log("[sh] " + brief(cmd));
        if (shellMode) return exec(cmd);
        final String[] box = new String[1];
        Thread worker = new Thread(() -> box[0] = run(cmd), "slh-sh");
        worker.setDaemon(true);
        long t0 = System.currentTimeMillis();
        worker.start();
        try {
            worker.join(15000);
        } catch (InterruptedException ignored) {
        }
        if (worker.isAlive()) {
            fails++;
            log.log("[sh 卡住] " + brief(cmd));
            return "ERR:超时";
        }
        String res = box[0] == null ? "" : box[0];
        if (res.startsWith("ERR:")) {
            fails++;
        } else {
            fails = 0;
        }
        long ms = System.currentTimeMillis() - t0;
        if (DEBUG && ms > 2500) {
            log.log("[sh " + ms + "ms] " + brief(cmd)
                    + (res.startsWith("ERR:") ? " → " + brief(res) : ""));
        }
        return res;
    }

    /** 连续多次失败说明 Shizuku 已经不可用，别再硬点下去 */
    public boolean healthy() {
        return fails < 3;
    }

    /** 本进程本身就是 shell（app_process 起在 Shizuku 里）时，直接跑命令 */
    private String exec(String cmd) {
        try {
            Process p = new ProcessBuilder("sh", "-c", cmd)
                    .redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            p.waitFor();
            return sb.toString();
        } catch (Throwable t) {
            return "ERR:" + t;
        }
    }

    private String run(String cmd) {
        Process p = null;
        try {
            java.lang.reflect.Method m = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            p = (Process) m.invoke(null, new String[]{"sh", "-c", cmd}, null, null);
            if (p == null) return "ERR:没有拿到 shell";
            StringBuilder sb = new StringBuilder();
            Thread out = drain(p.getInputStream(), sb);
            Thread err = drain(p.getErrorStream(), sb);
            out.join(12000);
            if (out.isAlive()) p.destroy();
            err.join(1500);
            return sb.toString();
        } catch (Throwable t) {
            return "ERR:" + t;
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static Thread drain(final InputStream in, final StringBuilder sb) {
        Thread t = new Thread(() -> {
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (sb) {
                        sb.append(line).append('\n');
                    }
                }
            } catch (Throwable ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static String brief(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ').trim();
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // ===================== 节点 =====================

    static class Node {
        String text = "";
        String desc = "";
        int x1, y1, x2, y2;

        boolean visible() {
            return x2 > x1 && y2 > y1;
        }

        int cx() {
            return (x1 + x2) / 2;
        }

        int cy() {
            return (y1 + y2) / 2;
        }

        int area() {
            return Math.max(1, (x2 - x1) * (y2 - y1));
        }
    }
}
