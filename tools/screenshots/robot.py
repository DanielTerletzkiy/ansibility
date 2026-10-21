#!/usr/bin/env python3
"""Tiny client for JetBrains' Robot Server plugin: runs Rhino JS inside the sandbox IDE (runIdeForScreenshots)."""
import json, sys, urllib.request, urllib.error

URL = "http://localhost:8082"

def js(script, edt=True):
    body = json.dumps({"script": script, "runInEdt": edt}).encode()
    req = urllib.request.Request(URL + "/js/execute", body, {"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            res = json.loads(r.read())
    except urllib.error.HTTPError as e:
        raise RuntimeError(e.read().decode()[:3000])
    if res.get("status") != "SUCCESS":
        raise RuntimeError(res.get("message") or res)
    return res

if __name__ == "__main__":
    src = open(sys.argv[1]).read() if len(sys.argv) > 1 else sys.stdin.read()
    print(json.dumps(js(src, edt="--bg" not in sys.argv), indent=1)[:4000])
