#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""笔记 → 商陆花（接口版）

一条笔记 = 解析 → 查重 → 取颜色ID → 建货品 → 回读校验
全程 HTTP，不点界面。会话用 A2 方式从 App 抓取。

用法：
    python3 api_import.py note.txt          # 单条
    python3 api_import.py notes/            # 目录，批量
    python3 api_import.py --code 6887       # 只查重
"""
import json
import os
import sys
import time
import urllib.parse
import urllib.request

HOST, PORT = "14.103.84.43", 8013
BASE = "http://%s:%d/slh/callInterface.do" % (HOST, PORT)
SESSION_FILE = "/root/workspace/session.txt"
DICT_FILE = "/root/workspace/dict_api.json"


def log(*a):
    print(*a, flush=True)


class SessionExpired(Exception):
    pass


class SlhApi:
    def __init__(self, sid):
        self.sid = sid

    def call(self, iface, extra, timeout=40):
        base = {
            "productCode": "pocketSLhPro", "dlProductCode": "pocketSLhPro",
            "dlUseFieldNameAlias": "1", "deviceType": "9",
            "productVersion": "2.6.9", "slh_version": "2.6.9",
            "sessionid": self.sid, "sessionId": self.sid, "interfaceid": iface,
        }
        base.update(extra)
        req = urllib.request.Request(
            BASE, data=urllib.parse.urlencode(base).encode(),
            headers={"Content-Type": "application/x-www-form-urlencoded"})
        raw = urllib.request.urlopen(req, timeout=timeout).read().decode("utf-8", "ignore")
        d = json.loads(raw)
        if isinstance(d, dict):
            msg = json.dumps(d, ensure_ascii=False)
            if ("expired" in str(d.get("msgId", "")) or d.get("errorCode") == "-8"
                    or "会话已失效" in msg or "会话已过期" in msg):
                raise SessionExpired(d.get("error") or "会话过期")
        return d

    # ---------- 基础数据 ----------
    def colors(self):
        r = self.call("cs-syncdata", {"type": "sc_dict_color", "offset": "0",
                                      "limit": "2000", "lastSyncTime": "2000-01-01 00:00:00"})
        return {d["name"]: d for d in (r.get("dataList") or [])}

    def sizes(self):
        r = self.call("cs-syncdata", {"type": "sc_dict_size", "offset": "0",
                                      "limit": "2000", "lastSyncTime": "2000-01-01 00:00:00"})
        return {d["name"]: d for d in (r.get("dataList") or [])}

    def styles(self):
        """全部货品（只取未停用的），返回 [(code, item)]"""
        out = []
        off = 0
        while True:
            r = self.call("cs-syncdata", {"type": "dres_style", "offset": str(off),
                                          "limit": "1000", "lastSyncTime": "2000-01-01 00:00:00"})
            dl = r.get("dataList") or []
            for p in dl:
                if str(p.get("delflag", "0")) == "0":
                    out.append(p)
            off += len(dl)
            if len(dl) < 1000:
                break
        return out

    # ---------- 颜色 ----------
    def create_color(self, name, group_sid="8", epid="430817"):
        """新建一个颜色（group_sid 默认 8 = 杂色）"""
        payload = {"name": name, "parenttypeid": "", "typeid": "601",
                   "action": "add", "parentid": str(group_sid), "delflag": 0}
        return self.call("cs-save-dict-full",
                         {"epid": str(epid), "jsonparam": json.dumps(payload, ensure_ascii=False)})

    # ---------- 图片 ----------
    def upload_image(self, data, filename=None):
        """上传一张图片，返回 fileid（商陆花用它关联商品图）"""
        import uuid
        filename = filename or ("IMG_%s.png" % uuid.uuid4().hex[:10])
        boundary = "----slh" + uuid.uuid4().hex[:16]
        params = {
            "productVersion": "2.6.9", "dlUseFieldNameAlias": "1",
            "productCode": "pocketSLhPro", "dlProductCode": "pocketSLhPro",
            "deviceType": "9", "slh_version": "2.6.9",
            "sessionid": self.sid, "sessionId": self.sid,
        }
        url = "http://%s:%s/slh/uploadMultiFile.do?%s" % (
            HOST, PORT, urllib.parse.urlencode(params))
        body = b"".join([
            ("--%s\r\n" % boundary).encode(),
            ('Content-Disposition: form-data; name="%s"; filename="%s"\r\n' % (filename, filename)).encode(),
            b"Content-Type: application/octet-stream\r\n\r\n",
            data,
            ("\r\n--%s--\r\n" % boundary).encode(),
        ])
        req = urllib.request.Request(url, data=body, headers={
            "Content-Type": "multipart/form-data; boundary=%s" % boundary,
            "Content-Length": str(len(body)),
        })
        txt = urllib.request.urlopen(req, timeout=120).read().decode("utf-8", "ignore").strip()
        return txt

    # ---------- 写 ----------
    def create_style(self, code, name, color_sids, size_sids, fileid=""):
        payload = {
            "fileid": fileid or "", "videoId": "", "videoUrl": "", "coverUrl": "",
            "code": code, "name": name, "classid": "", "brandid": "",
            "colorids": ",".join(color_sids), "sizeids": ",".join(size_sids),
            "marketdate": time.strftime("%Y-%m-%d"),
            "purprice": "", "stdprice1": "", "uselastprice": "1", "special": "0",
            "purinjson": None, "dwid": "", "season": "",
            "year1": int(time.strftime("%Y")), "locationid": "", "designer": "",
            "lining": "", "execstdid": "", "safetycatid": "", "rem": "",
            "processunitprice": "", "isprocess": "",
        }
        return self.call("sf-1511", {"jsonparam": json.dumps(payload, ensure_ascii=False)})


# ---------- 会话 ----------
def get_session(force=False):
    if not force and os.path.exists(SESSION_FILE):
        sid = open(SESSION_FILE).read().strip()
        if sid:
            return sid
    from session import capture_session, write_sid
    sid, _ = capture_session(timeout=60)
    write_sid(sid)
    return sid


def make_api(force=False):
    return SlhApi(get_session(force))


# ---------- 解析（复用脚本里的逻辑） ----------
def parse_note(text):
    """复用 note_parser.py，返回统一结构"""
    sys.path.insert(0, "/root/workspace")
    from note_parser import parse as _p
    d = _p(text)
    return {"no": d["款号"], "name": d["名称"], "colors": d["颜色"], "sizes": d["尺码"]}


def run_one(api, note_text, colors, sizes, existing_codes, do_create=True):
    item = parse_note(note_text)
    code, name = item["no"], item["name"]
    want = item["colors"]
    log("—— %s / %s / %d 色" % (code, name, len(want)))

    lack = []
    if not code:
        lack.append("款号")
    if not name:
        lack.append("名称")
    if not want:
        lack.append("颜色")
    if lack:
        log("   ⚠ 缺少必填项：%s，跳过" % "、".join(lack))
        return "missing_field"

    if code in existing_codes:
        log("   ⏭ 已存在，跳过")
        return "skip"

    miss = [c for c in want if c not in colors]
    if miss:
        log("   颜色库缺 %d 个，自动新建：%s" % (len(miss), "、".join(miss)))
        for c in miss:
            try:
                r0 = api.create_color(c)
                log("     + %s -> sid %s" % (c, r0.get("sid", "?")))
            except Exception as e:
                log("     ! %s 建失败：%s" % (c, e))
        colors = api.colors()          # 重新拉一次拿新 ID
        miss = [c for c in want if c not in colors]
        if miss:
            log("   ✘ 仍缺：%s" % "、".join(miss))
            return "missing_color"

    cids = [colors[c]["sid"] for c in want]
    sids = []
    for s in item["sizes"]:
        if s in sizes:
            sids.append(sizes[s]["sid"])
        else:
            log("   ⚠ 尺码库里没有：%s，改用均码" % s)
            sids.append(sizes["均码"]["sid"])

    if not do_create:
        log("   ✔ 预检通过（未写入）")
        return "dry"

    r = api.create_style(code, name, cids, sids)
    if "val" in r:
        log("   ✔ 建好 id=%s（%d 色 × %d 码）" % (r["val"], len(cids), len(sids)))
        existing_codes.add(code)
        return "ok"
    log("   ✘ 失败：%s" % json.dumps(r, ensure_ascii=False)[:200])
    return "fail"


def collect_notes(path):
    if path and os.path.isdir(path):
        files = sorted(os.path.join(path, f) for f in os.listdir(path) if f.endswith(".txt"))
        return [(f, open(f, encoding="utf-8").read()) for f in files]
    if path:
        return [(path, open(path, encoding="utf-8").read())]
    return [("stdin", sys.stdin.read())]


def main():
    args = [a for a in sys.argv[1:]]
    dry = "--dry" in args
    only_code = None
    if "--code" in args:
        only_code = args[args.index("--code") + 1]
    paths = [a for a in args if not a.startswith("--") and a != only_code]

    api = make_api()
    log("会话 %s..." % api.sid[:20])

    try:
        t0 = time.time()
        colors = api.colors()
        sizes = api.sizes()
        styles = api.styles()
    except SessionExpired:
        log("会话过期，重新抓取…")
        api = make_api(force=True)
        t0 = time.time()
        colors = api.colors()
        sizes = api.sizes()
        styles = api.styles()
    codes = {p.get("code") for p in styles}
    log("字典：%d 色 / %d 码 / %d 条货品（%.1fs）" % (len(colors), len(sizes), len(styles), time.time() - t0))

    if only_code:
        log("款号 %s ：%s" % (only_code, "已存在" if only_code in codes else "不存在"))
        return

    notes = collect_notes(paths[0] if paths else "/root/workspace/note.txt")
    results = []
    for name, text in notes:
        try:
            r = run_one(api, text, colors, sizes, codes, do_create=not dry)
        except Exception as e:
            log("   ✘ 异常：%s" % e)
            r = "error"
        results.append((name, r))

    log("\n====== 结果 ======")
    for name, r in results:
        log("  %-28s %s" % (os.path.basename(name), r))


if __name__ == "__main__":
    main()
