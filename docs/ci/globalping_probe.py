#!/usr/bin/env python3
"""Sonora RF research: measure YouTube/relay reachability from REAL Russian probes.

Uses Globalping (https://globalping.io) API. Gives status code, TLS, timings.

Usage:
  python globalping_probe.py A   # RF reality: DNS/HTTP for YouTube infra
  python globalping_probe.py B   # relay host reachability from RU probes
  python globalping_probe.py C   # audio Range test from RU probes

Writes/appends docs/ci/globalping-results.json
"""
import json
import sys
import time
import urllib.error
import urllib.request

API = "https://api.globalping.io/v1/measurements"
UA = "sonora-rf-research/1.0"


def req(url, method="GET", body=None, timeout=25):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(url, method=method, data=data)
    r.add_header("User-Agent", UA)
    r.add_header("Accept", "application/json")
    if data:
        r.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(r, timeout=timeout) as resp:
        return resp.status, json.loads(resp.read())


def create(kind, target, limit=3, mopts=None, locations=None):
    body = {"type": kind, "target": target,
            "locations": locations or [{"country": "RU"}], "limit": limit}
    if mopts:
        body["measurementOptions"] = mopts
    try:
        _, j = req(API, "POST", body)
    except urllib.error.HTTPError as e:
        detail = e.read()[:400].decode("utf-8", "replace")
        raise RuntimeError(f"create {e.code}: {detail}") from None
    mid = j["id"]
    for _ in range(45):
        time.sleep(1.5)
        _, m = req(f"{API}/{mid}")
        if m.get("status") == "finished":
            return m
    return m


def summarize(m):
    out = []
    for r in m.get("results", []):
        pr = r.get("probe", {}) or {}
        loc = pr.get("location", {}) or {}
        res = r.get("result", {}) or {}
        tim = res.get("timings") or {}
        item = {
            "city": loc.get("city"), "country": loc.get("country"),
            "asn": pr.get("asn"), "net": pr.get("network"),
            "status": res.get("status"), "code": res.get("statusCode"),
            "total_ms": tim.get("total") or tim.get("dns") or tim.get("connect"),
            "headers": res.get("headers") or {},
            "resolved": [a.get("value") for a in (res.get("answers") or [])] or None,
            "err": res.get("error"),
        }
        out.append(item)
    return out


def run(label, kind, target, limit=3, mopts=None):
    t0 = time.time()
    try:
        m = create(kind, target, limit=limit, mopts=mopts)
        rows = summarize(m)
        print(f"\n### {label}  [{kind}] {target}  ({round(time.time()-t0,1)}s)")
        for r in rows:
            extra = ""
            if r["resolved"]:
                extra = " resolved=" + ",".join(str(a) for a in r["resolved"][:3])
            print(f"  {str(r['city']):<14} AS{r['asn']!s:<7} {str(r['net'])[:24]:<24} "
                  f"status={r['status']} code={r['code']} {r['total_ms']}ms{extra} "
                  f"{('ERR:'+str(r['err'])) if r['err'] else ''}")
        return {"label": label, "kind": kind, "target": target, "rows": rows}
    except Exception as e:
        print(f"\n### {label} FAILED: {type(e).__name__}: {e}")
        return {"label": label, "kind": kind, "target": target, "error": str(e)}


MEASUREMENTS = []


def add(label, kind, target, limit=3, mopts=None):
    MEASUREMENTS.append(run(label, kind, target, limit=limit, mopts=mopts))
    time.sleep(1.2)


def http_measure(label, host, path="/", limit=3, headers=None):
    reqd = {"method": "GET", "path": path or "/"}
    if headers:
        reqd["headers"] = headers
    add(label, "http", host, limit=limit,
        mopts={"protocol": "HTTPS", "request": reqd})


if __name__ == "__main__":
    phase = sys.argv[1] if len(sys.argv) > 1 else "A"
    out_path = "docs/ci/globalping-results.json"
    try:
        existing = json.load(open(out_path, encoding="utf-8"))
    except Exception:
        existing = {"runs": []}

    if phase == "A":
        add("dns youtube.com", "dns", "youtube.com", limit=4)
        add("dns www.youtube.com", "dns", "www.youtube.com", limit=3)
        add("dns googlevideo.com", "dns", "googlevideo.com", limit=4)
        add("dns youtubei.googleapis.com", "dns", "youtubei.googleapis.com", limit=3)
        add("dns api.github.com", "dns", "api.github.com", limit=3)
        add("dns rr1---sn-4g5e6nzz.googlevideo.com", "dns",
            "rr1---sn-4g5e6nzz.googlevideo.com", limit=3)
        http_measure("http www.youtube.com", "www.youtube.com", "/", limit=4)
        http_measure("http youtubei player", "youtubei.googleapis.com",
                     "/youtubei/v1/player", limit=3)
        http_measure("http api.github.com", "api.github.com",
                     "/repos/yt-dlp/yt-dlp/releases/latest", limit=3)
        http_measure("http googlevideo.com", "googlevideo.com", "/", limit=3)
        http_measure("http github.com", "github.com", "/yt-dlp/yt-dlp", limit=2)
    elif phase == "B":
        hosts = json.load(open("docs/ci/hosts_b.json", encoding="utf-8"))
        for h in hosts:
            http_measure(h["label"], h["host"], h.get("path", "/"), limit=h.get("limit", 2))
    elif phase == "C":
        hosts = json.load(open("docs/ci/hosts_c.json", encoding="utf-8"))
        for h in hosts:
            http_measure(h["label"], h["host"], h["path"], limit=h.get("limit", 2),
                         headers={"Range": "bytes=0-65535",
                                  "User-Agent": "Mozilla/5.0 (Linux; Android 14; Sonora) "
                                                "AppleWebKit/537.36 Chrome/131.0"})
    elif phase == "E":
        # discovery endpoints + defaults that were not covered in phase B/C
        hosts = json.load(open("docs/ci/hosts_e.json", encoding="utf-8"))
        for h in hosts:
            http_measure(h["label"], h["host"], h["path"], limit=h.get("limit", 2),
                         headers={"Range": "bytes=0-65535"} if "latest_version" in h["path"] else None)
    elif phase == "D":
        # target = raw IP, request.host = SNI/Host header -> separates DNS block
        # from IP/SNI-level filtering by TSPU
        hosts = json.load(open("docs/ci/hosts_d.json", encoding="utf-8"))
        for h in hosts:
            add(h["label"], "http", h["target"], limit=h.get("limit", 2),
                mopts={"protocol": "HTTPS",
                       "request": {"host": h["hostHeader"], "method": "GET",
                                   "path": h.get("path", "/")}})

    existing["runs"].extend(MEASUREMENTS)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(existing, f, ensure_ascii=False, indent=1)
    print(f"\nappended {len(MEASUREMENTS)} measurements -> {out_path}")
