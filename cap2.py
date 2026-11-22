import json, os
from mitmproxy import http
F = "/root/workspace/up.jsonl"
BI = "/root/workspace/bodies"
os.makedirs(BI, exist_ok=True)

def request(flow: http.HTTPFlow):
    try:
        ctype = flow.request.headers.get("content-type", "")
        raw = flow.request.get_content() or b""
        rec = {"t": "req", "method": flow.request.method, "host": flow.request.host,
               "port": flow.request.port, "path": flow.request.path,
               "ctype": ctype, "len": len(raw)}
        if "multipart" in ctype or "octet-stream" in ctype or len(raw) > 20000:
            fn = os.path.join(BI, "b%03d.bin" % len(os.listdir(BI)))
            open(fn, "wb").write(raw[:200000])
            rec["body_file"] = fn
            rec["head"] = raw[:300].decode("latin-1", "ignore")
        else:
            rec["body"] = raw.decode("utf-8", "ignore")[:400000]
        with open(F, "a", encoding="utf-8") as f:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
    except Exception:
        pass

def response(flow: http.HTTPFlow):
    try:
        raw = flow.response.get_content() or b""
        rec = {"t": "resp", "path": flow.request.path, "status": flow.response.status_code,
               "len": len(raw), "body": raw[:800].decode("utf-8", "ignore")}
        with open(F, "a", encoding="utf-8") as f:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
    except Exception:
        pass
