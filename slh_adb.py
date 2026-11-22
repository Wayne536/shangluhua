#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""通过 adb(无线调试) 操作手机的底层工具库

不依赖无障碍服务：uiautomator dump 读界面 → 解析 XML → input tap 点坐标
"""
import re
import subprocess
import time
import xml.etree.ElementTree as ET

import os
SERIAL = os.environ.get("ADB_SERIAL", "127.0.0.1:3035")
REMOTE_XML = "/sdcard/window.xml"


def adb(*args, timeout=30):
    cmd = ["adb", "-s", SERIAL, "shell"] + list(args)
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    return p.stdout + p.stderr


def dump_raw():
    """抓一次界面 XML 字符串"""
    for _ in range(3):
        adb("uiautomator", "dump", REMOTE_XML)
        out = adb("cat", REMOTE_XML)
        if out.strip().startswith("<?xml"):
            return out
        time.sleep(0.6)
    raise RuntimeError("dump 失败：拿不到 XML")


_BOUNDS_RE = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def parse_bounds(s):
    m = _BOUNDS_RE.match(s or "")
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1, y1, x2, y2)


def center(b):
    return ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)


class Screen:
    """一次界面快照，带父节点索引"""

    def __init__(self, xml_text):
        self.root = ET.fromstring(xml_text)
        self.nodes = []
        self.parent = {}
        self._walk(self.root, None)

    def _walk(self, el, p):
        idx = len(self.nodes)
        self.nodes.append(el)
        self.parent[idx] = p
        for ch in el:
            self._walk(ch, idx)

    def text_of(self, idx):
        el = self.nodes[idx]
        return (el.get("text") or "").strip()

    def bounds_of(self, idx):
        return parse_bounds(self.nodes[idx].get("bounds"))

    def find(self, text, exact=True, contains=False):
        """返回匹配的节点索引列表"""
        out = []
        for i in range(len(self.nodes)):
            t = self.text_of(i)
            if not t:
                continue
            if exact and t == text:
                out.append(i)
            elif contains and text in t:
                out.append(i)
        return out

    def find_one(self, text, exact=True, contains=False):
        r = self.find(text, exact, contains)
        return r[0] if r else None

    def clickable_ancestor(self, idx, max_up=8):
        """向上找第一个 clickable 的祖先（含自己）"""
        cur = idx
        for _ in range(max_up):
            if cur is None:
                return None
            el = self.nodes[cur]
            if el.get("clickable") == "true":
                return cur
            cur = self.parent[cur]
        return None

    def describe(self, idx):
        el = self.nodes[idx]
        return "%s class=%s clickable=%s bounds=%s" % (
            (el.get("text") or "")[:20], el.get("class", "").split(".")[-1],
            el.get("clickable"), el.get("bounds"))


def tap(x, y):
    adb("input", "tap", str(x), str(y))


def tap_node(scr, idx):
    """优先点可点的祖先，否则点自己的中心"""
    target = scr.clickable_ancestor(idx) or idx
    b = scr.bounds_of(target)
    if not b:
        raise RuntimeError("节点没有 bounds")
    x, y = center(b)
    tap(x, y)
    return x, y


def key(code):
    adb("input", "keyevent", str(code))


def input_text(s):
    # adb input text 不支持中文，中文只能走剪贴板/输入法；这里先做 ASCII
    adb("input", "text", s.replace(" ", "%s"))


def start_app(pkg, activity=None):
    if activity:
        adb("am", "start", "-n", "%s/%s" % (pkg, activity))
    else:
        adb("monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1")


def shot(path="/sdcard/shot.png", local="shot.png"):
    adb("screencap", "-p", path)
    subprocess.run(["adb", "-s", SERIAL, "pull", path, local],
                   capture_output=True, text=True, timeout=60)
    return local


def dump_stable(tries=8, gap=0.45):
    """等界面动画停稳：连续两次 dump 内容一致才算稳定"""
    prev = None
    for _ in range(tries):
        cur = dump_raw()
        if prev is not None and cur == prev:
            return cur
        prev = cur
        time.sleep(gap)
    return prev
