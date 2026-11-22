#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""极简入口：手机浏览器打开 http://127.0.0.1:5055
粘贴多条笔记 → 后台队列逐条入库 → 页内实时显示进度
"""
import http.server
import json
import re
import socketserver
import threading
import time
import traceback
import urllib.parse
import uuid

import api_import as A
import ocr

OCR_ENABLED = False   # 视觉识色：先关掉（每图 2~4 秒，批量太慢）

PORT = 5055

STATE = {"sid": None, "colors": None, "sizes": None, "codes": None, "ts": 0}
LOCK = threading.Lock()
JOBS = {}
JOB_LOCK = threading.Lock()


def log(*a):
    print(" ".join(str(x) for x in a), flush=True)


def ensure_dict(force=False):
    with LOCK:
        fresh = STATE["colors"] and (time.time() - STATE["ts"] < 600)
        if fresh and not force:
            return STATE["colors"], STATE["sizes"], STATE["codes"]
        api = A.make_api(force=force)
        try:
            c, s, st = api.colors(), api.sizes(), api.styles()
        except A.SessionExpired:
            api = A.make_api(force=True)
            c, s, st = api.colors(), api.sizes(), api.styles()
        STATE.update({"sid": api.sid, "colors": c, "sizes": s,
                      "codes": {p.get("code") for p in st}, "ts": time.time()})
        return c, s, STATE["codes"]


def split_notes(text):
    text = (text or "").replace("\r\n", "\n").strip()
    if not text:
        return []
    parts = re.split(r"(?=(?:【\s*款号\s*】|^\s*款号\s*[:：]?[ \t]*\S))", text, flags=re.M)
    return [p.strip() for p in parts if p.strip()]


def process_one(colors, sizes, codes, api, chunk, images):
    """处理一条，返回 (code, 说明)"""
    try:
        item = A.parse_note(chunk)
    except Exception as e:
        return ("?", "解析失败：" + str(e))

    code, name, want = item["no"], item["name"], item["colors"]

    # 文字里没写颜色 → 从图片里认
    ocr_note = ""
    if OCR_ENABLED and not want and images:
        try:
            known = set(colors.keys())
            got, per = ocr.extract_colors_multi(images, known)
            if got:
                want = got
                item["colors"] = got
                detail = "；".join("%s:%s" % (fn[:8], "/".join(g) or "无") for fn, g in per)
                ocr_note = "（颜色来自 %d 张图的识别：%s）" % (len(images), detail[:80])
        except Exception as ex:
            ocr_note = "（图片识色失败：%s）" % str(ex)[:40]

    lack = []
    if not code:
        lack.append("款号")
    if not name:
        lack.append("名称")
    if not want:
        lack.append("颜色")
    if lack:
        msg = "缺少必填项：" + "、".join(lack) + ocr_note + "，已跳过"
        return (code or "?", msg)

    if code in codes:
        return (code, "已存在，跳过")

    miss = [c for c in want if c not in colors]
    if miss:
        for c in miss:
            try:
                api.create_color(c)
            except Exception:
                pass
        try:
            colors, sizes, codes = ensure_dict(force=True)
        except Exception:
            pass
        miss = [c for c in want if c not in colors]
        if miss:
            return (code, "颜色建失败：" + "、".join(miss))

    cids = [colors[c]["sid"] for c in want]
    sids = [sizes[x]["sid"] if x in sizes else sizes["均码"]["sid"] for x in item["sizes"]]

    fileid = ""
    if images:
        ids = []
        for fn, data in images[:9]:
            try:
                fid = api.upload_image(data, fn)
                if fid and fid.isdigit():
                    ids.append(fid)
            except Exception:
                pass
        fileid = ",".join(ids)

    try:
        r = api.create_style(code, name, cids, sids, fileid=fileid)
    except A.SessionExpired:
        ensure_dict(force=True)
        api2 = A.SlhApi(STATE["sid"])
        r = api2.create_style(code, name, cids, sids, fileid=fileid)

    if "val" in r:
        codes.add(code)
        tag = "，%d 张图" % len(fileid.split(",")) if fileid else ""
        return (code, "✔ 建好 id=%s（%d 色：%s%s）" % (
            r["val"], len(cids), "、".join(want)[:60], tag))
    if "already_exist" in str(r.get("msgId", "")):
        codes.add(code)
        return (code, "已存在（服务端拒绝重复）")
    return (code, "失败：" + json.dumps(r, ensure_ascii=False)[:120])


def run_job(jid, text, images):
    job = JOBS[jid]
    t0 = time.time()
    try:
        notes = split_notes(text)
        job["total"] = len(notes)
        if not notes:
            job["lines"].append("没有识别到笔记")
            return
        colors, sizes, codes = ensure_dict()
        api = A.SlhApi(STATE["sid"])
        for i, chunk in enumerate(notes, 1):
            job["current"] = i
            code, msg = process_one(colors, sizes, codes, api, chunk, images)
            job["lines"].append({"i": i, "code": code, "msg": msg})
            job["ok"] = sum(1 for x in job["lines"] if isinstance(x, dict) and x["msg"].startswith("✔"))
            job["skip"] = sum(1 for x in job["lines"] if isinstance(x, dict) and "跳过" in x["msg"])
            job["fail"] = len(job["lines"]) - job["ok"] - job["skip"]
    except Exception as e:
        job["lines"].append({"i": 0, "code": "!", "msg": "出错：" + str(e)})
        job["error"] = traceback.format_exc()[-400:]
    finally:
        job["elapsed"] = time.time() - t0
        job["done"] = True


def parse_multipart(body, ctype):
    m = re.search(r'boundary="?([^";]+)"?', ctype or "")
    if not m:
        return {}, []
    boundary = ("--" + m.group(1)).encode()
    fields, files = {}, []
    for part in body.split(boundary):
        if not part.strip(b"\r\n-") or b"\r\n\r\n" not in part:
            continue
        head, data = part.split(b"\r\n\r\n", 1)
        data = data.rstrip(b"\r\n-")
        hs = head.decode("utf-8", "ignore")
        nm = re.search(r'name="([^"]*)"', hs)
        fn = re.search(r'filename="([^"]*)"', hs)
        if not nm:
            continue
        if fn and fn.group(1):
            files.append((fn.group(1), data))
        else:
            fields[nm.group(1)] = data.decode("utf-8", "ignore")
    return fields, files


PAGE = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>笔记入库</title>
<style>
body{font-family:-apple-system,sans-serif;margin:0;padding:14px;background:#f5f5f6;color:#222}
h2{margin:0 0 10px;font-size:19px}
textarea{width:100%;height:32vh;padding:12px;font-size:15px;border:1px solid #ddd;border-radius:12px;box-sizing:border-box;background:#fff}
.row{display:flex;gap:10px;margin-top:10px}
button{flex:1;padding:16px;font-size:17px;border:0;border-radius:12px;font-weight:600}
#go{background:#ff5b5b;color:#fff}
#clear{background:#e9e9ec;color:#333;flex:0 0 32%}
button:disabled{opacity:.55}
.filebox{background:#fff;border:1px dashed #ccc;border-radius:12px;padding:12px;margin-top:10px;font-size:14px}
#status{font-size:13px;color:#666;margin-top:10px;min-height:20px}
#list{margin-top:10px}
.item{background:#fff;border-radius:12px;padding:12px 14px;margin-bottom:8px;font-size:14px;line-height:1.6}
.item .c{font-weight:600}
.ok{color:#0a7a3d}.skip{color:#888}.bad{color:#c0392b}
#sum{background:#fff;border-radius:12px;padding:12px 14px;margin-top:10px;font-size:14px;display:none}
.tip{color:#999;font-size:12px;margin-top:8px}
</style></head><body>
<h2>笔记 → 商陆花</h2>
<textarea id="notes" placeholder="把微信笔记粘进来，可以一次粘多条（每条以【款号】或 款号 开头）"></textarea>
<div class="filebox">
  <input type="file" id="imgs" accept="image/*" multiple>
  <div class="tip">可选，最多 9 张图（每一条都会带上同一组图）</div>
</div>
<div class="row">
  <button id="clear">清空</button>
  <button id="go">开始入库</button>
</div>
<div id="status"></div>
<div id="list"></div>
<div id="sum"></div>
<script>
const go=document.getElementById('go'),cl=document.getElementById('clear'),
      nt=document.getElementById('notes'),im=document.getElementById('imgs'),
      st=document.getElementById('status'),list=document.getElementById('list'),sum=document.getElementById('sum');
let timer=null;
cl.onclick=()=>{nt.value='';im.value='';list.innerHTML='';sum.style.display='none';st.textContent='';};
function line(x){
  const cls=x.msg.startsWith('✔')?'ok':(x.msg.includes('跳过')?'skip':'bad');
  return `<div class="item"><span class="c">${x.code}</span> <span class="${cls}">${x.msg}</span></div>`;
}
go.onclick=async()=>{
  if(!nt.value.trim()){st.textContent='先把笔记粘进来';return;}
  const fd=new FormData();fd.append('notes',nt.value);
  for(const f of im.files) fd.append('imgs',f,f.name);
  go.disabled=true;cl.disabled=true;list.innerHTML='';sum.style.display='none';
  st.textContent='正在提交…';
  try{
    const r=await fetch('/import',{method:'POST',body:fd});
    const j=await r.json();
    if(!j.job){st.textContent='提交失败';return;}
    poll(j.job);
  }catch(e){st.textContent='请求失败：'+e;go.disabled=false;cl.disabled=false;}
};
async function poll(id){
  const r=await fetch('/job/'+id);
  const j=await r.json();
  list.innerHTML=j.lines.map(line).join('');
  const cur=j.current||0,tot=j.total||0;
  st.textContent=j.done?('完成，用时 '+(j.elapsed||0).toFixed(1)+' 秒')
                       :('处理中… '+(tot?cur+'/'+tot:'准备中'));
  if(j.done){
    sum.style.display='block';
    sum.textContent='成功 '+(j.ok||0)+' ｜ 跳过 '+(j.skip||0)+' ｜ 失败 '+(j.fail||0);
    go.disabled=false;cl.disabled=false;
  }else{
    timer=setTimeout(()=>poll(id),800);
  }
}
</script>
</body></html>"""


class Handler(http.server.BaseHTTPRequestHandler):
    def _send(self, body, code=200, ctype="text/html; charset=utf-8"):
        data = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path.startswith("/favicon"):
            return self._send("", 404)
        if self.path.startswith("/job/"):
            jid = self.path.split("/job/", 1)[1].strip()
            job = JOBS.get(jid)
            if not job:
                return self._send(json.dumps({"lines": [], "done": True}),
                                  ctype="application/json; charset=utf-8")
            return self._send(json.dumps({
                "lines": job.get("lines", []), "done": job.get("done", False),
                "current": job.get("current", 0), "total": job.get("total", 0),
                "ok": job.get("ok", 0), "skip": job.get("skip", 0),
                "fail": job.get("fail", 0), "elapsed": job.get("elapsed", 0),
            }, ensure_ascii=False), ctype="application/json; charset=utf-8")
        self._send(PAGE)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length)
        ctype = self.headers.get("Content-Type", "")
        if "multipart/form-data" in ctype:
            fields, files = parse_multipart(raw, ctype)
            text = fields.get("notes", "")
        else:
            text = (urllib.parse.parse_qs(raw.decode("utf-8", "ignore")).get("notes") or [""])[0]
            files = []

        jid = uuid.uuid4().hex[:12]
        JOBS[jid] = {"lines": [], "done": False, "current": 0, "total": 0,
                     "ok": 0, "skip": 0, "fail": 0, "elapsed": 0}
        threading.Thread(target=run_job, args=(jid, text, files), daemon=True).start()
        self._send(json.dumps({"job": jid}), ctype="application/json; charset=utf-8")

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    socketserver.TCPServer.allow_reuse_address = True
    with socketserver.TCPServer(("127.0.0.1", PORT), Handler) as httpd:
        print("已启动: http://127.0.0.1:%d" % PORT, flush=True)
        httpd.serve_forever()
