import json
from mitmproxy import http
F="/root/workspace/req.jsonl"
def request(flow: http.HTTPFlow):
    try:
        body = flow.request.get_text(strict=False) or ""
        if len(body) > 4000: body = body[:4000] + "...(truncated)"
        with open(F,"a",encoding="utf-8") as f:
            f.write(json.dumps({
                "host": flow.request.host, "port": flow.request.port,
                "path": flow.request.path, "method": flow.request.method,
                "ctype": flow.request.headers.get("content-type",""),
                "body": body,
            }, ensure_ascii=False) + "\n")
    except Exception:
        pass
def response(flow: http.HTTPFlow):
    try:
        if "/slh/" not in flow.request.path: return
        t = flow.response.get_text(strict=False) or ""
        if len(t) > 2000: t = t[:2000] + "...(truncated)"
        with open(F,"a",encoding="utf-8") as f:
            f.write(json.dumps({"resp_path": flow.request.path, "resp": t}, ensure_ascii=False) + "\n")
    except Exception:
        pass
