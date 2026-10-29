#!/usr/bin/env python3
"""Discover Invidious instances from the official list and test the media proxy.

For every https instance from https://api.invidious.io/instances.json we request
/latest_version?id=<id>&itag=140&local=true with a Range header and check whether
real audio bytes come back (206/200 + audio/* mime).
"""
import json
import ssl
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor

UA = ("Mozilla/5.0 (Linux; Android 14; Sonora) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36")
VID = "dQw4w9WgXcQ"


def fetch(url, timeout=15, limit=None):
    r = urllib.request.Request(url)
    r.add_header("User-Agent", UA)
    t0 = time.time()
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            return {"ok": True, "code": resp.status, "ms": int((time.time()-t0)*1000),
                    "body": resp.read(limit) if limit else resp.read(),
                    "headers": dict(resp.headers)}
    except Exception as e:
        return {"ok": False, "err": f"{type(e).__name__}: {str(e)[:90]}",
                "ms": int((time.time()-t0)*1000)}


def discover():
    r = fetch("https://api.invidious.io/instances.json?sort_by=health", timeout=20)
    if not r.get("ok"):
        print("discovery failed:", r.get("err"))
        return []
    try:
        data = json.loads(r["body"])
    except Exception:
        print("discovery not json")
        return []
    hosts = []
    for item in data:
        if not isinstance(item, list) or len(item) < 2:
            continue
        host, meta = item[0], item[1]
        if not isinstance(meta, dict):
            continue
        if meta.get("type") != "https":
            continue
        uri = meta.get("uri") or f"https://{host}"
        api_ok = (meta.get("api") is not False)
        hosts.append((uri.rstrip("/"), api_ok))
    return hosts


def probe_audio(base):
    url = f"{base}/latest_version?id={VID}&itag=140&local=true"
    r = urllib.request.Request(url)
    r.add_header("User-Agent", UA)
    r.add_header("Range", "bytes=0-65535")
    t0 = time.time()
    try:
        with urllib.request.urlopen(r, timeout=15,
                                    context=ssl.create_default_context()) as resp:
            body = resp.read(70000)
            ct = resp.headers.get("Content-Type") or ""
            return {"code": resp.status, "ms": int((time.time()-t0)*1000),
                    "bytes": len(body), "ct": ct,
                    "server": resp.headers.get("Server") or "",
                    "cf": bool(resp.headers.get("cf-ray")),
                    "ok": resp.status in (200, 206) and ct.startswith("audio") and len(body) > 10000}
    except Exception as e:
        return {"code": None, "ms": int((time.time()-t0)*1000),
                "err": f"{type(e).__name__}: {str(e)[:70]}"}


if __name__ == "__main__":
    inst = discover()
    print(f"discovered {len(inst)} https instances")
    results = []
    with ThreadPoolExecutor(max_workers=12) as ex:
        futs = {ex.submit(probe_audio, b): (b, api) for b, api in inst}
        for f in futs:
            r = f.result()
            b, api = futs[f]
            results.append({"base": b, "api_listed": api, **{k: v for k, v in r.items()}})
    json.dump(results, open("docs/ci/invidious-audio-scan.json", "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    print(f"\n{'ok':3} {'code':4} {'ms':6} {'bytes':7} {'ct':20} {'cf':3} host")
    for r in sorted(results, key=lambda x: (not x.get("ok"), x["ms"])):
        print(f"{'YES' if r.get('ok') else '':3} {str(r.get('code')):4} {r.get('ms'):6} "
              f"{str(r.get('bytes','')):7} {str(r.get('ct'))[:20]:20} "
              f"{'cf' if r.get('cf') else '':3} {r['base'][8:]} {r.get('err','')}")
