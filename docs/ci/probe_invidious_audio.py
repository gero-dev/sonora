#!/usr/bin/env python3
"""Direct test of Invidious media proxy: /latest_version?id=..&itag=140&local=true

This is the exact endpoint Sonora's RelayGateway uses. A real Range request
returning 206 + audio/* proves that instance proxies audio bytes.
Run from any egress (function test; RF reachability measured separately).
"""
import ssl
import time
import urllib.parse
import urllib.request

UA = ("Mozilla/5.0 (Linux; Android 14; Sonora) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36")
VID = "dQw4w9WgXcQ"
HOSTS = [
    "https://invidious.protokolla.fi",
    "https://inv.nadeko.net",
    "https://yewtu.be",
    "https://invidious.nerdvpn.de",
    "https://invidious.f5.si",
    "https://iv.melmac.space",
    "https://yt.artemislena.eu",
    "https://invidious.einfachzocken.eu",
    "https://invidious.reallyaweso.me",
    "https://invidious.materialio.us",
    "https://inv.tux.pizza",
    "https://iv.ggtyler.dev",
    "https://invidious.jing.rocks",
    "https://invidious.lunivers.trade",
    "https://yt.chocolatemoo53.com",
    "https://inv.us.projectsegfau.lt",
    "https://invidious.dhusch.de",
]


def probe(base):
    url = f"{base}/latest_version?id={VID}&itag=140&local=true"
    r = urllib.request.Request(url)
    r.add_header("User-Agent", UA)
    r.add_header("Range", "bytes=0-65535")
    t0 = time.time()
    try:
        with urllib.request.urlopen(r, timeout=12,
                                    context=ssl.create_default_context()) as resp:
            body = resp.read(70000)
            return {"code": resp.status, "ms": int((time.time()-t0)*1000),
                    "bytes": len(body), "ct": resp.headers.get("Content-Type"),
                    "cr": resp.headers.get("Content-Range"),
                    "ok": resp.status in (200, 206) and len(body) > 1000}
    except urllib.error.HTTPError as e:
        return {"code": e.code, "ms": int((time.time()-t0)*1000), "err": f"HTTP {e.code}",
                "snippet": e.read(160).decode("utf-8", "replace").replace("\n", " ")}
    except Exception as e:
        return {"code": None, "ms": int((time.time()-t0)*1000),
                "err": f"{type(e).__name__}: {str(e)[:90]}"}


from concurrent.futures import ThreadPoolExecutor
res = []
with ThreadPoolExecutor(max_workers=8) as ex:
    futs = {ex.submit(probe, h): h for h in HOSTS}
    for f, h in futs.items():
        r = f.result()
        res.append((h, r))

print(f"{'host':40} {'code':6} {'ms':6} {'bytes':7} {'ct':26} ok")
for h, r in sorted(res, key=lambda x: (not x[1].get("ok"), x[0])):
    ok = "YES" if r.get("ok") else ""
    print(f"{h[8:]:40} {str(r.get('code')):6} {r.get('ms'):6} {str(r.get('bytes','')):7} "
          f"{str(r.get('ct'))[:26]:26} {ok} {r.get('err','')} {r.get('snippet','')[:80]}")
