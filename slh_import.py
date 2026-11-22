#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""笔记 → 商陆花 货品入库（adb 直连版）

不依赖无障碍，不需要 AutoJs6：
  uiautomator dump 读界面 → 按文字找控件 → input tap / input swipe 点击
中文通过 ADBKeyboard 广播输入。
"""
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

import os
SERIAL = os.environ.get("ADB_SERIAL", "127.0.0.1:3035")
PKG = "com.slh_mobile_pro"
W, H = 1080, 2376



import asyncio as _asyncio
import os as _os

_PORT_FILE = "/root/workspace/adb_port.txt"


def _scan_ports():
    async def probe(port, sem):
        async with sem:
            try:
                r, w = await _asyncio.wait_for(_asyncio.open_connection("127.0.0.1", port), 0.22)
                w.close()
                return port
            except Exception:
                return None

    async def main():
        sem = _asyncio.Semaphore(900)
        res = await _asyncio.gather(*[probe(p, sem) for p in range(1024, 65536)])
        return [p for p in res if p]

    try:
        return _asyncio.run(main())
    except Exception:
        return []


def _dev_ok():
    out = subprocess.run(["adb", "devices"], capture_output=True, text=True, timeout=20).stdout
    for line in out.splitlines():
        m = re.match(r"(127\.0\.0\.1:\d+)\s+device", line)
        if m:
            return m.group(1)
    return None


def ensure_adb(verbose=True):
    """确保有一条在线的 adb 连接，自动重连（无线调试端口会变）"""
    global SERIAL
    ok = _dev_ok()
    if ok:
        SERIAL = ok
        return SERIAL

    cands = []
    if _os.path.exists(_PORT_FILE):
        try:
            cands.append(int(open(_PORT_FILE).read().strip()))
        except Exception:
            pass
    ports = cands + [p for p in _scan_ports() if p not in cands]
    for p in ports:
        if p == 5037:
            continue
        r = subprocess.run(["adb", "connect", "127.0.0.1:%d" % p],
                           capture_output=True, text=True, timeout=20).stdout
        if "connected" in r and "failed" not in r:
            time.sleep(0.6)
            if _dev_ok():
                SERIAL = "127.0.0.1:%d" % p
                open(_PORT_FILE, "w").write(str(p))
                if verbose:
                    log("  · adb 已重连：" + SERIAL)
                return SERIAL
    raise RuntimeError("adb 连不上手机，请在开发者选项里重新打开无线调试")


def adb(*args, timeout=40):
    p = subprocess.run(["adb", "-s", SERIAL, "shell"] + list(args),
                       capture_output=True, text=True, timeout=timeout)
    return (p.stdout or "") + (p.stderr or "")


def bcast_text(text):
    """通过 ADBKeyboard 输入任意文字（支持中文）"""
    p = subprocess.run(["adb", "-s", SERIAL, "shell", "am", "broadcast",
                        "-a", "ADB_INPUT_TEXT", "--es", "msg", text],
                       capture_output=True, text=True, timeout=40)
    return p.stdout


def dump_raw():
    ensure_adb(verbose=False)
    for _ in range(3):
        adb("uiautomator", "dump", "/sdcard/window.xml")
        out = adb("cat", "/sdcard/window.xml")
        if out.strip().startswith("<?xml"):
            return out
        time.sleep(0.6)
    raise RuntimeError("dump 失败")


def snap(wait=0.7):
    """等一小段后单次抓取（比 dump_stable 快一倍）"""
    time.sleep(wait)
    return Scr(dump_raw())


def dump_stable(tries=6, gap=0.4):
    prev = None
    for _ in range(tries):
        cur = dump_raw()
        if prev is not None and cur == prev:
            return cur
        prev = cur
        time.sleep(gap)
    return prev


import re
_B = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


class Scr:
    def __init__(self, xml):
        self.root = ET.fromstring(xml)
        self.nodes, self.parent = [], {}
        self._walk(self.root, None)

    def _walk(self, el, p):
        i = len(self.nodes)
        self.nodes.append(el)
        self.parent[i] = p
        for c in el:
            self._walk(c, i)

    def t(self, i):
        return (self.nodes[i].get("text") or "").strip()

    def b(self, i):
        m = _B.match(self.nodes[i].get("bounds") or "")
        return tuple(map(int, m.groups())) if m else None

    def find(self, text, exact=True):
        out = []
        for i in range(len(self.nodes)):
            v = self.t(i)
            if not v:
                continue
            if (v == text) if exact else (text in v):
                out.append(i)
        return out

    def find1(self, text, exact=True):
        r = self.find(text, exact)
        return r[0] if r else None

    def clickable_anc(self, i, up=8):
        cur = i
        for _ in range(up):
            if cur is None:
                return None
            if self.nodes[cur].get("clickable") == "true":
                return cur
            cur = self.parent[cur]
        return None


def center(b):
    return ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)


def tap(x, y):
    adb("input", "tap", str(x), str(y))


def swipe(x1, y1, x2, y2, ms=400):
    adb("input", "swipe", str(x1), str(y1), str(x2), str(y2), str(ms))


def tap_idx(s, i):
    j = s.clickable_anc(i) or i
    b = s.b(i if j is None else j)
    if not b:
        raise RuntimeError("无 bounds")
    x, y = center(b)
    tap(x, y)
    return x, y


def keyboard_shown():
    try:
        out = adb("dumpsys", "input_method")
        for line in out.splitlines():
            if "mInputShown=" in line:
                return "mInputShown=true" in line
    except Exception:
        pass
    return False


def dismiss_keyboard():
    if keyboard_shown():
        adb("input", "keyevent", "4")
        time.sleep(0.6)
        return True
    return False


ADB_IME = "com.android.adbkeyboard/.AdbIME"
_ORIG_IME = None


def ensure_ime():
    """切到 ADBKeyboard，并记住原来的输入法"""
    global _ORIG_IME
    ensure_adb(verbose=False)
    cur = adb("settings", "get", "secure", "default_input_method").strip()
    if "not found" in cur or "daemon" in cur:
        ensure_adb(verbose=False)
        cur = adb("settings", "get", "secure", "default_input_method").strip()
    if ADB_IME in cur:
        return
    _ORIG_IME = cur or "com.sohu.inputmethod.sogouoem/.SogouIME"
    adb("ime", "enable", ADB_IME)
    adb("ime", "set", ADB_IME)
    time.sleep(1.2)
    log("  · 输入法已切换（原：%s）" % _ORIG_IME)


def restore_ime():
    """退出时把输入法换回用户原来的"""
    global _ORIG_IME
    if not _ORIG_IME:
        return
    adb("ime", "set", _ORIG_IME)
    time.sleep(0.8)
    log("  · 输入法已还原：%s" % _ORIG_IME)
    _ORIG_IME = None


def log(*a):
    print(*a, flush=True)


# ---------------- 页面判断 ----------------
def is_home(s):
    i = s.find1("待调入")
    return i is not None


def is_list(s):
    return s.find1("智能排序") is not None


def is_new(s):
    return s.find1("货品新增") is not None and s.find1("请输入款号") is not None


def has_dialog(s):
    return s.find1("确定") is not None and s.find1("取消") is not None


def fullscreen_ok(s):
    i = s.find1("首页")
    b = s.b(i) if i is not None else None
    if not b:
        return True
    return (b[2] - b[0]) > 40 and b[3] > 2200   # 底部 tab 在正常位置


# ---------------- 动作 ----------------
def _launch_once():
    adb("am", "force-stop", PKG)
    time.sleep(0.8)
    adb("monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")


def start_clean(timeout=8, retries=3):
    """启动商陆花并等到首页；超过 timeout 秒没起来就强制重启，最多 retries 次"""
    for attempt in range(1, retries + 1):
        _launch_once()
        t0 = time.time()
        while time.time() - t0 < timeout:
            try:
                s = snap(0.6)
            except Exception:
                time.sleep(0.5)
                continue
            if is_home(s):
                log("  · 启动成功（%.1fs）" % (time.time() - t0))
                return s
            if time.time() - t0 > 3 and (is_list(s) or is_new(s)):
                # 停在了别的页面，也说明活着
                log("  · 已启动，停在列表/新增页")
                return s
        log("  ⚠ %d 秒没打开，第 %d 次重启" % (timeout, attempt))
    raise RuntimeError("商陆花启动失败（已重试 %d 次）" % retries)


def go_new_page(s):
    """从任意状态回到「货品新增」页"""
    if has_dialog(s):
        for _ in range(3):
            c = s.find("取消")
            if not c:
                break
            tap_idx(s, c[-1])
            time.sleep(1)
            s = Scr(dump_stable())
    if is_new(s):
        return s
    if is_home(s):
        tap_idx(s, s.find1("货品"))
        time.sleep(2)
        s = Scr(dump_stable())
    if not is_list(s):
        for _ in range(4):
            adb("input", "keyevent", "4")
            time.sleep(1)
            s = Scr(dump_stable())
            if is_list(s) or is_home(s):
                break
        if is_home(s):
            tap_idx(s, s.find1("货品"))
            time.sleep(2)
            s = Scr(dump_stable())

    # 找圆形 + （约 165×165，右下）
    fab = None
    for i in range(len(s.nodes)):
        if s.nodes[i].get("clickable") != "true":
            continue
        b = s.b(i)
        if not b:
            continue
        w, h = b[2] - b[0], b[3] - b[1]
        cx, cy = center(b)
        if cx > 820 and cy > 1700 and 120 < w < 220 and 120 < h < 220 and abs(w - h) < 30:
            fab = i
            break
    if fab is None:
        raise RuntimeError("找不到 + 按钮")
    tap_idx(s, fab)
    time.sleep(2)
    s = Scr(dump_stable())
    if not is_new(s):
        raise RuntimeError("没进入货品新增页")
    return s


def fill_row(s, label, value):
    row = s.find1("请输入" + label) or s.find1(label)
    if row is None:
        raise RuntimeError("找不到「%s」行" % label)
    tap_idx(s, row)
    time.sleep(1.2)
    s = Scr(dump_stable())
    if not has_dialog(s):
        raise RuntimeError("点「%s」没弹出输入框" % label)
    bcast_text(value)
    time.sleep(0.8)
    ok = s.find("确定")
    if not ok:
        raise RuntimeError("没有确定按钮")
    tap_idx(s, ok[-1])
    time.sleep(1.2)
    s = Scr(dump_stable())
    if has_dialog(s):
        raise RuntimeError("「%s」确定后弹窗没关" % label)
    log("  ✓ %s = %s" % (label, value))
    return s


def pick_multi(s, entry_text, values):
    """打开颜色/尺码选择页，逐个点选，最后点「完成」"""
    e = s.find1(entry_text)
    if e is None:
        raise RuntimeError("找不到入口 " + entry_text)
    tap_idx(s, e)
    time.sleep(2)
    s = Scr(dump_stable())

    missing = []
    for v in values:
        found = False
        for attempt in range(6):
            s = Scr(dump_stable())
            idx = s.find1(v)
            if idx is not None:
                tap_idx(s, idx)
                time.sleep(0.5)
                found = True
                break
            # 没找到就向下滚一屏
            swipe(W // 2, int(H * 0.75), W // 2, int(H * 0.35), 500)
            time.sleep(0.8)
        if not found:
            missing.append(v)
            log("  ⚠ 没找到 " + v)
        else:
            log("  · 选中 " + v)

    # 回到顶部再点完成
    for _ in range(3):
        swipe(W // 2, int(H * 0.35), W // 2, int(H * 0.8), 400)
        time.sleep(0.4)
    s = snap(0.6)
    done = s.find1("完成")
    if done is None:
        raise RuntimeError("找不到完成按钮")
    tap_idx(s, done)
    return snap(1.2), missing



def find_search_box(s, hint="输入颜色查询"):
    """按提示文字定位搜索框（页面后面还藏着货品列表的搜索框，必须区分）"""
    cands = []
    for i in range(len(s.nodes)):
        if not (s.nodes[i].get("class") or "").endswith("EditText"):
            continue
        b = s.b(i)
        if not b:
            continue
        if s.t(i).startswith(hint):
            cands.append(i)
    if cands:
        return cands[0]
    # 兜底：取最靠下的那个（选择页的搜索框在 y≈270，货品列表那个在 y≈130）
    best = None
    for i in range(len(s.nodes)):
        if not (s.nodes[i].get("class") or "").endswith("EditText"):
            continue
        b = s.b(i)
        if not b or b[1] > 500:
            continue
        if best is None or b[1] > s.b(best)[1]:
            best = i
    return best


def clear_text(n=40):
    """用按键清空输入框（比广播稳）"""
    adb("input", "keyevent", "123")          # MOVE_END
    for _ in range(n):
        adb("input", "keyevent", "67")        # DEL
    time.sleep(0.2)


def type_search(s, text, hint="输入颜色查询"):
    box = find_search_box(s, hint)
    if box is None:
        s = snap(0.5)
        box = find_search_box(s, hint)
    if box is None:
        raise RuntimeError("找不到搜索框")
    tap_idx(s, box)
    time.sleep(0.4)
    clear_text()
    time.sleep(0.3)
    bcast_text(text)
    time.sleep(1.0)


def close_color_dialog(s):
    """新增颜色弹窗还开着时先取消，避免把字打进「排序号」"""
    if s.find1("编辑颜色") is None:
        return s
    c = s.find("取消")
    if c:
        tap_idx(s, c[-1])
        time.sleep(1.0)
        log("  · 关闭遗留的编辑颜色弹窗")
    return snap(0.8)



def choose_group(s, group="杂色"):
    """强制重新选一次分组：灰色的「杂色」只是占位，不算已选"""
    field = None
    for i in range(len(s.nodes)):
        b = s.b(i); t = s.t(i)
        if not b or not t:
            continue
        if 1200 <= b[1] <= 1450 and t in ("选择分组", "杂色", "大牛", "棕", "紫", "蓝"):
            field = i
            break
    if field is None:
        return s
    tap_idx(s, field)
    s = snap(0.8)
    gi = s.find1(group)
    if gi is not None:
        tap_idx(s, gi)
        s = snap(0.6)
    return s


def tap_confirm_until_closed(s, tries=3):
    for _ in range(tries):
        if s.find1("编辑颜色") is None:
            return s, True
        ok = s.find("确定")
        if not ok:
            break
        tap_idx(s, ok[-1])
        s = snap(1.0)
    return s, s.find1("编辑颜色") is None


def find_pill(s, text):
    """找颜色/尺码格子：必须是 TextView 且在列表区域（y>400），排除搜索框"""
    for i in range(len(s.nodes)):
        cls = s.nodes[i].get("class") or ""
        if not cls.endswith("TextView"):
            continue
        if s.t(i) != text:
            continue
        b = s.b(i)
        if b and b[1] > 400:
            return i
    return None


def pick_by_search(s, values, group="杂色", hint="输入颜色查询"):
    """每个值都走搜索：搜到就点，搜不到就新增"""
    missing = []
    for v in values:
        s = close_color_dialog(s)
        type_search(s, v, hint)
        s = snap(0.55)

        if s.find1("没有找到结果") is not None:
            add = None
            for i in range(len(s.nodes)):
                t = s.t(i)
                if t.startswith("新增“") or t.startswith("新增\""):
                    add = i
                    break
            if add is None:
                missing.append(v)
                log("  ⚠ 搜不到也点不了新增：" + v)
                continue
            tap_idx(s, add)
            s = snap(1.1)
            s = choose_group(s, group)
            s, closed = tap_confirm_until_closed(s, 3)
            if not closed:
                log("  ⚠ 确定没关掉弹窗：" + v)
            s = close_color_dialog(s)

        idx = find_pill(s, v)
        if idx is None:
            s = snap(0.8)
            idx = find_pill(s, v)
        if idx is None:
            missing.append(v)
            log("  ⚠ 没点到 " + v)
            continue
        tap_idx(s, idx)
        time.sleep(0.35)
        log("  · " + v)
    return s, missing


def pick_multi_search(s, entry_text, values, group="杂色"):
    e = s.find1(entry_text)
    if e is None:
        raise RuntimeError("找不到入口 " + entry_text)
    tap_idx(s, e)
    s = snap(1.4)
    hint = "输入颜色查询" if entry_text == "请选择颜色" else "输入尺码查询"
    s, missing = pick_by_search(s, values, group, hint)
    # 清掉搜索词，再点完成（有筛选时完成不生效）
    s = snap(0.5)
    box = find_search_box(s, hint)
    if box is not None:
        tap_idx(s, box)
        time.sleep(0.3)
        clear_text()
        time.sleep(0.8)
    dismiss_keyboard()
    # 「完成」在重绘时会短暂消失，点不到就重试
    closed = False
    for _ in range(5):
        s = snap(0.6)
        done = s.find1("完成")
        if done is None:
            closed = True      # 已经关掉了
            break
        tap_idx(s, done)
        time.sleep(1.1)
        s2 = snap(0.3)
        if s2.find1("完成") is None:
            closed = True
            break
    if not closed:
        raise RuntimeError("点完成没关掉颜色页")
    return snap(1.0), missing


def read_field(s, label):
    """读表单里某行右侧的值"""
    cands = s.find(label)
    if not cands:
        return ""
    for idx in cands:
        b = s.b(idx)
        if not b or b[1] < 1400:
            continue
        for j in range(len(s.nodes)):
            bj = s.b(j)
            if not bj:
                continue
            v = s.t(j)
            if not v or v == label or v.startswith("¥"):
                continue
            if abs(bj[1] - b[1]) < 25 and bj[0] >= b[2] and bj[2] <= 1080:
                return v
    return ""


def run(item_no, item_name, colors, size="均码", do_save=True, resume=False, save_only=False):
    log("=== %s / %s / %d 色 / %s ===" % (item_no, item_name, len(colors), size))
    ensure_adb()
    ensure_ime()
    try:
        return _run(item_no, item_name, colors, size, do_save, resume, save_only)
    finally:
        restore_ime()


def _run(item_no, item_name, colors, size="均码", do_save=True, resume=False, save_only=False):
    if resume or save_only:
        s = snap(0.5)
    else:
        s = start_clean()
        s = go_new_page(s)
        s = fill_row(s, "款号", item_no)
        if item_name:
            s = fill_row(s, "名称", item_name)

    if save_only:
        save = s.find1("保存")
        if save is None:
            raise RuntimeError("找不到保存按钮")
        tap_idx(s, save)
        s = snap(2.5)
        ok = s.find1("保存成功") is not None
        log("  保存结果：%s" % ("成功" if ok else "未见到「保存成功」"))
        if ok:
            c = s.find("取消")
            if c:
                tap_idx(s, c[-1])
        return ok

    s, miss_c = pick_multi_search(s, "请选择颜色", colors)
    c_ok = read_field(s, "颜色")
    log("  颜色回读：%s" % (c_ok or "(空)"))
    log("  颜色缺失：%s" % (miss_c or "无"))

    s, miss_s = pick_multi_search(s, "请选择尺码", [size])
    s_ok = read_field(s, "尺码")
    log("  尺码回读：%s" % (s_ok or "(空)"))

    if not do_save:
        log("  (未保存，保留在页面上)")
        return

    save = s.find1("保存")
    if save is None:
        raise RuntimeError("找不到保存按钮")
    tap_idx(s, save)
    time.sleep(3)
    s = Scr(dump_stable())
    ok = s.find1("保存成功") is not None
    log("  保存结果：%s" % ("成功" if ok else "未见到「保存成功」"))
    if ok:
        c = s.find("取消")
        if c:
            tap_idx(s, c[-1])
            time.sleep(1)
    return ok


if __name__ == "__main__":
    import json
    cfg = json.load(open(sys.argv[1], encoding="utf-8")) if len(sys.argv) > 1 else {}
    run(cfg.get("no", "TEST-003"), cfg.get("name", "测试勿用"),
        cfg.get("colors", ["羽毛白", "花语粉"]), cfg.get("size", "均码"),
        cfg.get("save", False), cfg.get("resume", False), cfg.get("save_only", False))
