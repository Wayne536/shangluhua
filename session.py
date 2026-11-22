#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""A2 方案：从商陆花 App 的请求里取一个有效的 sessionid

流程：起本地代理 → 手机全局代理指过来 → 启动商陆花 → 等它发出请求 →
      从请求体里抽出 sessionid → 撤代理、关代理进程
"""
import json
import os
import re
import socket
import subprocess
import time

from slh_import import adb, snap, tap_idx, log

MITM = "/opt/python/bin/mitmdump"
CAP = "/root/workspace/cap.py"
FLOW = "/root/workspace/req.jsonl"
BASE = "http://14.103.84.43:8013"


def free_port(start=8110, end=8200):
    for p in range(start, end):
        s = socket.socket()
        s.settimeout(0.15)
        try:
            s.bind(("127.0.0.1", p))
            s.close()
            return p
        except Exception:
            s.close()
    raise RuntimeError("没有空闲端口")


def _set_proxy(v):
    adb("settings", "put", "global", "http_proxy", v)


def capture_session(timeout=60):
    """返回 (sessionid, port)，失败抛异常。结束时一定撤代理、关进程。"""
    if os.path.exists(FLOW):
        os.remove(FLOW)

    port = free_port()
    proc = subprocess.Popen(
        [MITM, "-p", str(port), "-s", CAP, "--set", "confdir=/root/workspace/.mitm"],
        stdout=open("/root/workspace/sess.log", "w"), stderr=subprocess.STDOUT)

    try:
        # 等代理真正起来
        for _ in range(30):
            s = socket.socket()
            s.settimeout(0.2)
            try:
                s.connect(("127.0.0.1", port))
                s.close()
                break
            except Exception:
                s.close()
                time.sleep(0.3)
        else:
            raise RuntimeError("代理没起来")

        log("  · 代理就绪 :%d" % port)
        _set_proxy("127.0.0.1:%d" % port)

        adb("am", "force-stop", "com.slh_mobile_pro")
        time.sleep(0.8)
        adb("monkey", "-p", "com.slh_mobile_pro", "-c", "android.intent.category.LAUNCHER", "1")

        t0 = time.time()
        sid = None
        while time.time() - t0 < timeout:
            time.sleep(1.2)
            # 卡在「无法连接」页就点重试
            try:
                s = snap(0.2)
                r = s.find1("重试")
                if r is not None:
                    tap_idx(s, r)
            except Exception:
                pass
            sid = read_sid()
            if sid:
                break
        if not sid:
            raise RuntimeError("没抓到 sessionid（超时）")
        return sid, port
    finally:
        try:
            _set_proxy(":0")
        except Exception:
            pass
        try:
            proc.terminate()
            proc.wait(timeout=5)
        except Exception:
            try:
                proc.kill()
            except Exception:
                pass


_SID_RE = re.compile(r"sessionid=([\w-]+)")


def read_sid():
    if not os.path.exists(FLOW):
        return None
    sid = None
    try:
        with open(FLOW, encoding="utf-8") as f:
            for line in f:
                try:
                    d = json.loads(line)
                except Exception:
                    continue
                body = d.get("body") or ""
                m = _SID_RE.search(body)
                if m and m.group(1).startswith("ctr-"):
                    sid = m.group(1)
    except Exception:
        pass
    return sid


def write_sid(sid):
    with open("/root/workspace/session.txt", "w") as f:
        f.write(sid)


def load_sid():
    try:
        v = open("/root/workspace/session.txt").read().strip()
        return v or None
    except Exception:
        return None


if __name__ == "__main__":
    sid, port = capture_session()
    write_sid(sid)
    log("sessionid = " + sid)
    log("已保存到 session.txt")
