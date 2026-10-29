#!/usr/bin/env python3
"""Verify that Piped instances reachable from RU actually yield playable audio.

Fetches /streams/<id> from a Piped API, inspects audioStreams, then does a real
Range request against the proxied audio URL. Run from any egress (function test).
"""
import json
import ssl
import urllib.parse
import urllib.request

UA = ("Mozilla/5.0 (Linux; Android 14; Sonora) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36")
VID = "dQw4w9WgXcQ"


def get(url, headers=None, timeout=20, limit=None):
    r = urllib.request.Request(url)
    r.add_header("User-Agent", UA)
    r.add_header("Accept", "application/json")
    for k, v in (headers or {}).items():
        r.add_header(k, v)
    ctx = ssl.create_default_context()
    import time
    t0 = time.time()
    try:
        with urllib.request.urlopen(r, timeout=timeout, context=ctx) as resp:
            body = resp.read(limit) if limit else resp.read()
            return {"ok": True, "code": resp.status, "ms": int((time.time()-t0)*1000),
                    "body": body, "ctype": resp.headers.get("Content-Type", "")}
    except Exception as e:
        return {"ok": False, "err": f"{type(e).__name__}: {str(e)[:120]}",
                "ms": int((time.time()-t0)*1000)}


HOSTS = [
    "https://pipedapi.ducks.party",
    "https://piped-api.piped.tokhmi.xyz",
    "https://api.piped.private.coffee",
    "https://pipedapi.kavin.rocks",
    "https://pipedapi.adminforge.de",
]

for base in HOSTS:
    print("\n==", base)
    r = get(f"{base}/streams/{VID}", limit=800000)
    print("  api:", r.get("code"), r.get("ms"), "ms", r.get("err", ""))
    if not r.get("ok"):
        continue
    try:
        j = json.loads(r["body"])
    except Exception as e:
        print("  not json:", r["body"][:120])
        continue
    if "error" in j and j.get("error"):
        print("  api error:", str(j.get("error"))[:120], "| message:", str(j.get("message"))[:120])
    au = j.get("audioStreams") or []
    print(f"  title={str(j.get('title'))[:40]!r} audio={len(au)} video={len(j.get('videoStreams') or [])}")
    for s in au[:3]:
        print("   - itag", s.get("itag"), s.get("mimeType"), s.get("bitrate"), "proxy" if s.get("proxyUrl") else "direct")
    if not au:
        continue
    s = au[0]
    u = s.get("proxyUrl") or s.get("url")
    if u and u.startswith("/"):
        u = base + u
    rr = get(u, headers={"Range": "bytes=0-65535"}, limit=70000)
    print("   range:", rr.get("code"), rr.get("ms"), "ms", "bytes=", len(rr.get("body") or b""),
          "ct=", rr.get("ctype"), rr.get("err", ""))
