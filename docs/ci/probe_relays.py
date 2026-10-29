#!/usr/bin/env python3
"""Sonora RF research: probe public YouTube relay candidates.

Stage 1: resolve + fetch metadata / stream URL from relay API.
Stage 2: real HTTP Range request for audio bytes (expect 200/206 + audio/*).

NOTE: run from a machine whose egress may be WARP/VPN -> proves relay function,
NOT reachability from a Russian ISP. RF-side reachability is tested separately
via check-host.net RU nodes.

Writes docs/ci/relay-probe-results.json
"""
import json
import socket
import ssl
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

VID = "dQw4w9WgXcQ"          # stable, long-lived, audio available
ALT_VID = "jNQXAC9IVRw"      # "Me at the zoo" (19s)
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0 Safari/537.36")
TIMEOUT = 15

INVIDIOUS = [
    "https://inv.nadeko.net",
    "https://yewtu.be",
    "https://invidious.nerdvpn.de",
    "https://invidious.f5.si",
    "https://iv.melmac.space",
    "https://invidious.privacyredirect.com",
    "https://iv.datura.network",
    "https://invidious.jing.rocks",
    "https://invidious.reallyaweso.me",
    "https://invidious.materialio.us",
    "https://yt.artemislena.eu",
    "https://invidious.einfachzocken.eu",
    "https://inv.tux.pizza",
    "https://invidious.protokolla.fi",
    "https://inv.zzls.xyz",
    "https://invidious.dhusch.de",
    "https://iv.ggtyler.dev",
    "https://invidious.lunivers.trade",
    "https://invidious.nikkosphere.com",
    "https://invidious.adminforge.de",
]

PIPED = [
    "https://pipedapi.kavin.rocks",
    "https://pipedapi.adminforge.de",
    "https://api.piped.private.coffee",
    "https://pipedapi.reallyaweso.me",
    "https://pipedapi.drgns.space",
    "https://pipedapi.ducks.party",
    "https://pipedapi.leptons.xyz",
    "https://pipedapi.nosebs.ru",
    "https://pipedapi.orangenet.cc",
    "https://pipedapi.r4fo.com",
    "https://pipedapi.astartes.nl",
    "https://piped-api.piped.tokhmi.xyz",
]

COBALT = [
    "https://api.cobalt.tools",
    "https://cobalt-api.kwiatekmiki.com",
    "https://cobalt-api.meowing.de",
    "https://co.eepy.today",
]

MISC = [
    "https://tunelio.dev",
]

lock = threading.Lock()


def log(msg):
    with lock:
        print(msg, flush=True)


def http(url, method="GET", headers=None, data=None, timeout=TIMEOUT, max_bytes=None,
         want_headers=False):
    req = urllib.request.Request(url, method=method, data=data)
    req.add_header("User-Agent", UA)
    req.add_header("Accept", "*/*")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    ctx = ssl.create_default_context()
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as r:
            if max_bytes is not None:
                body = r.read(max_bytes)
            else:
                body = r.read()
            dt = (time.time() - t0) * 1000
            hdrs = dict(r.headers)
            return {
                "ok": True, "status": r.status, "ms": round(dt), "len": len(body),
                "ctype": hdrs.get("Content-Type", ""), "headers": hdrs if want_headers else {},
                "body": body,
            }
    except urllib.error.HTTPError as e:
        dt = (time.time() - t0) * 1000
        try:
            b = e.read(400)
        except Exception:
            b = b""
        return {"ok": False, "status": e.code, "ms": round(dt), "err": f"HTTP {e.code}",
                "body": b}
    except Exception as e:
        dt = (time.time() - t0) * 1000
        return {"ok": False, "status": None, "ms": round(dt), "err": type(e).__name__ + ": " + str(e)[:160],
                "body": b""}


def dns(host):
    try:
        t0 = time.time()
        infos = socket.getaddrinfo(host, 443, proto=socket.IPPROTO_TCP)
        ips = sorted({i[4][0] for i in infos})
        return {"ok": True, "ms": round((time.time() - t0) * 1000), "ips": ips[:3]}
    except Exception as e:
        return {"ok": False, "err": type(e).__name__ + ": " + str(e)[:100]}


def audio_range(url, headers=None):
    h = dict(headers or {})
    h["Range"] = "bytes=0-65535"
    r = http(url, headers=h, max_bytes=70000)
    out = {
        "status": r.get("status"), "ms": r.get("ms"), "bytes": r.get("len"),
        "ctype": r.get("ctype", ""), "err": r.get("err"),
    }
    out["ok"] = bool(r.get("ok")) and r.get("status") in (200, 206) and r.get("len", 0) > 1000
    if r.get("ok") and r.get("status") == 206:
        out["content_range"] = r.get("headers", {}).get("Content-Range", "")
    return out


def probe_invidious(base):
    host = urllib.parse.urlparse(base).hostname
    res = {"kind": "invidious", "base": base, "host": host, "dns": dns(host)}
    if not res["dns"]["ok"]:
        res["verdict"] = "dns_fail"
        return res
    fields = "title,author,lengthSeconds,adaptiveFormats,formatStreams"
    url = f"{base}/api/v1/videos/{VID}?fields={urllib.parse.quote(fields)}"
    r = http(url, max_bytes=400000)
    res["api"] = {"status": r.get("status"), "ms": r.get("ms"), "len": r.get("len"), "err": r.get("err")}
    if not r.get("ok"):
        res["verdict"] = "api_fail"
        if r.get("body"):
            res["api"]["snippet"] = r["body"][:200].decode("utf-8", "replace")
        return res
    try:
        j = json.loads(r["body"])
    except Exception as e:
        res["verdict"] = "api_not_json"
        res["api"]["snippet"] = r["body"][:200].decode("utf-8", "replace")
        return res
    if not isinstance(j, dict):
        res["verdict"] = "api_not_object"
        res["api"]["snippet"] = str(j)[:200]
        return res
    res["title"] = (j.get("title") or "")[:60]
    fmt = j.get("adaptiveFormats") or []
    aud = [f for f in fmt if str(f.get("type", "")).startswith("audio")]
    res["n_adaptive"] = len(fmt)
    res["n_audio"] = len(aud)
    if aud:
        a = aud[0]
        res["audio_itag"] = a.get("itag")
        res["audio_mime"] = a.get("type")
    # Test the LOCAL proxy endpoint (relay re-streams from its own IP)
    lv = f"{base}/latest_version?id={VID}&itag=140&local=true"
    res["local_proxy"] = audio_range(lv)
    # Test direct googlevideo URL from the relay (will 403 for us if IP-bound)
    if aud and aud[0].get("url"):
        u = aud[0]["url"]
        if u.startswith("/"):
            u = base + u
        res["direct_url"] = audio_range(u)
    res["verdict"] = "ok_audio" if res["local_proxy"]["ok"] else "no_audio_bytes"
    return res


def probe_piped(base):
    host = urllib.parse.urlparse(base).hostname
    res = {"kind": "piped", "base": base, "host": host, "dns": dns(host)}
    if not res["dns"]["ok"]:
        res["verdict"] = "dns_fail"
        return res
    r = http(f"{base}/streams/{VID}", max_bytes=600000)
    res["api"] = {"status": r.get("status"), "ms": r.get("ms"), "len": r.get("len"), "err": r.get("err")}
    if not r.get("ok"):
        res["verdict"] = "api_fail"
        if r.get("body"):
            res["api"]["snippet"] = r["body"][:200].decode("utf-8", "replace")
        return res
    try:
        j = json.loads(r["body"])
    except Exception:
        res["verdict"] = "api_not_json"
        res["api"]["snippet"] = r["body"][:200].decode("utf-8", "replace")
        return res
    res["title"] = (j.get("title") or "")[:60]
    astreams = j.get("audioStreams") or []
    res["n_audio"] = len(astreams)
    if astreams:
        a = [s for s in astreams if "mp4" in str(s.get("mimeType", ""))] or astreams
        s = a[0]
        res["audio_itag"] = s.get("itag")
        res["audio_mime"] = s.get("mimeType")
        res["proxied"] = s.get("proxyUrl") is not None
        u = s.get("url") or s.get("proxyUrl")
        if u:
            res["direct_url"] = audio_range(u)
        res["verdict"] = "ok_audio" if res["direct_url"]["ok"] else "no_audio_bytes"
    else:
        res["verdict"] = "no_audio_streams"
        res["api"]["keys"] = list(j.keys())[:10]
    return res


def probe_cobalt(base):
    host = urllib.parse.urlparse(base).hostname
    res = {"kind": "cobalt", "base": base, "host": host, "dns": dns(host)}
    if not res["dns"]["ok"]:
        res["verdict"] = "dns_fail"
        return res
    payload = json.dumps({"url": f"https://www.youtube.com/watch?v={VID}",
                          "downloadMode": "audio", "audioFormat": "wav"}).encode()
    r = http(base + "/", method="POST", data=payload,
             headers={"Content-Type": "application/json", "Accept": "application/json"},
             max_bytes=20000)
    res["api"] = {"status": r.get("status"), "ms": r.get("ms"), "err": r.get("err"),
                  "snippet": r["body"][:300].decode("utf-8", "replace") if r.get("body") else ""}
    try:
        j = json.loads(r["body"])
        res["api"]["status_field"] = j.get("status")
        res["api"]["error"] = (j.get("error") or {})
        u = j.get("url")
        if u:
            res["direct_url"] = audio_range(u)
            res["verdict"] = "ok_audio" if res["direct_url"]["ok"] else "no_audio_bytes"
        else:
            res["verdict"] = "api_no_url"
    except Exception:
        res["verdict"] = "api_fail"
    return res


def main():
    jobs = []
    for b in INVIDIOUS:
        jobs.append((probe_invidious, b))
    for b in PIPED:
        jobs.append((probe_piped, b))
    for b in COBALT:
        jobs.append((probe_cobalt, b))
    results = []
    t_start = time.time()
    with ThreadPoolExecutor(max_workers=10) as ex:
        futs = [ex.submit(fn, b) for fn, b in jobs]
        for f in futs:
            try:
                results.append(f.result())
            except Exception as e:
                results.append({"kind": "?", "verdict": "probe_crash", "err": str(e)[:200]})
    elapsed = round(time.time() - t_start, 1)
    out = {
        "tested_at_msk": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime()),
        "video": VID,
        "elapsed_s": elapsed,
        "egress_note": "probe machine egress may be WARP/VPN; function only, not RF reachability",
        "results": results,
    }
    path = "docs/ci/relay-probe-results.json"
    with open(path, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print(f"\nWROTE {path} in {elapsed}s")
    print(f"{'verdict':18} {'kind':10} {'api':5} {'local':6} {'ms':6} host")
    for r in sorted(results, key=lambda x: (x.get("verdict") != "ok_audio", x.get("kind", ""))):
        api = (r.get("api") or {}).get("status")
        lp = ""
        if "local_proxy" in r:
            lp = str(r["local_proxy"]["status"])
        elif "direct_url" in r:
            lp = str(r["direct_url"]["status"])
        print(f"{r.get('verdict',''):18} {r.get('kind',''):10} {str(api):5} {lp:6} "
              f"{str((r.get('api') or {}).get('ms')):6} {r.get('base')}")


if __name__ == "__main__":
    main()
