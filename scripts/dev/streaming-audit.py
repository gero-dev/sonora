#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Sonora / PC streaming audit.

Measures what actually works for YouTube audio on the *user's real* network
(Russian ISP), with Cloudflare WARP on/off, and writes a machine-readable JSON
plus a Russian human-readable report.

Only stdlib is required (urllib/http.client/socket/ssl/subprocess). The yt-dlp
zipapp is optional and is used only when --with-ytdlp is passed.

Examples:
    python scripts/dev/streaming-audit.py --phase A --warp on --quick
    python scripts/dev/streaming-audit.py --phase B --warp off --with-ytdlp
    python scripts/dev/streaming-audit.py --report-only

Safety: --warp on|off always restores the original WARP state in a finally
block and verifies general internet afterwards.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import socket
import ssl
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import urllib.error
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta, timezone
from http.client import HTTPSConnection
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

MSK = timezone(timedelta(hours=3))
ROOT = Path(__file__).resolve().parents[2]
DEFAULT_JSON = ROOT / "docs" / "ci" / "pc-streaming-audit.json"
DEFAULT_MD = ROOT / "docs" / "PC-STREAMING-AUDIT.md"
WARP_CLI = Path(r"C:\Program Files\Cloudflare\Cloudflare WARP\warp-cli.exe")

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")

DEFAULT_VIDEOS = [("dQw4w9WgXcQ", "control"), ("kJQP7kiw5Fk", "music")]
SAMPLE_BYTES = 65536
AF4 = socket.AF_INET
AF6 = socket.AF_INET6


# --------------------------------------------------------------------------
# small utilities
# --------------------------------------------------------------------------
def now_msk() -> datetime:
    return datetime.now(MSK)


def iso(dt: datetime) -> str:
    return dt.isoformat(timespec="seconds")


def run_cmd(cmd, timeout: float = 60.0, env: dict | None = None) -> dict:
    t0 = time.perf_counter()
    try:
        p = subprocess.run(cmd, capture_output=True, text=True,
                           encoding="utf-8", errors="replace", timeout=timeout,
                           env=env)
        return {"rc": p.returncode, "out": (p.stdout or "").strip(),
                "err": (p.stderr or "").strip(),
                "ms": round((time.perf_counter() - t0) * 1000)}
    except FileNotFoundError as e:
        return {"rc": -1, "out": "", "err": f"not found: {e}",
                "ms": round((time.perf_counter() - t0) * 1000)}
    except subprocess.TimeoutExpired:
        return {"rc": -2, "out": "", "err": f"timeout after {timeout}s",
                "ms": round((time.perf_counter() - t0) * 1000)}
    except Exception as e:  # pragma: no cover
        return {"rc": -3, "out": "", "err": f"{type(e).__name__}: {e}",
                "ms": round((time.perf_counter() - t0) * 1000)}


def fmt_kb(n) -> str:
    try:
        return f"{n / 1024.0:,.0f}".replace(",", " ")
    except Exception:
        return "?"


# --------------------------------------------------------------------------
# WARP
# --------------------------------------------------------------------------
def warp_status() -> str:
    if not WARP_CLI.exists():
        return "Unavailable"
    r = run_cmd([str(WARP_CLI), "status"], timeout=20)
    txt = f"{r['out']} {r['err']}"
    m = re.search(r"Status update:\s*(\S+)", txt)
    if m:
        v = m.group(1)
        if v in ("Connected", "Disconnected", "Connecting", "Disconnecting"):
            return v
    if "not running" in txt.lower() or "unable" in txt.lower():
        return "Unavailable"
    return "Unknown"


def warp_wait(target: str, timeout: float = 45.0) -> str:
    deadline = time.perf_counter() + timeout
    st = warp_status()
    while time.perf_counter() < deadline:
        if st == target:
            return st
        time.sleep(0.6)
        st = warp_status()
    return st


def warp_set(target: str, timeout: float = 45.0) -> dict:
    """target: 'Connected' or 'Disconnected'. Returns {ok, before, after, ms}."""
    before = warp_status()
    t0 = time.perf_counter()
    if before == target:
        return {"ok": True, "before": before, "after": before,
                "ms": 0, "noop": True}
    if before == "Unavailable":
        return {"ok": False, "before": before, "after": before,
                "ms": 0, "error": "warp-cli unavailable"}
    action = "connect" if target == "Connected" else "disconnect"
    r = run_cmd([str(WARP_CLI), action], timeout=40)
    after = warp_wait(target, timeout)
    return {"ok": after == target, "before": before, "after": after,
            "ms": round((time.perf_counter() - t0) * 1000),
            "cmd_rc": r["rc"], "cmd_err": r["err"] or r["out"]}


def public_ip(family: int, timeout: float = 8.0) -> dict:
    """Public egress IP using a family-forced HTTPS request."""
    if family == AF4:
        host = "api.ipify.org"
    else:
        host = "api64.ipify.org"
    r = http_over_ip(host, family, "/", read_bytes=64, timeout=timeout,
                     collect_body=True)
    ip = None
    if r.get("ok") and r.get("body"):
        ip = r["body"].strip()
    elif not r.get("ok"):
        r2 = http_over_ip("ifconfig.me", family, "/ip", read_bytes=64,
                          timeout=timeout, collect_body=True)
        if r2.get("ok") and r2.get("body"):
            ip = r2["body"].strip()
            r = r2
    return {"ok": bool(ip), "ip": ip, "family": "v4" if family == AF4 else "v6",
            "ms": r.get("total_ms"), "error": r.get("error")}


def dns_servers() -> list:
    """Best-effort list of configured DNS servers (works on RU Windows)."""
    try:
        r = run_cmd(["ipconfig", "/all"], timeout=20)
    except Exception:
        return []
    txt = r["out"]
    out, grab = [], False
    for line in txt.splitlines():
        if "DNS" in line:
            grab = True
            rest = line.split(":", 1)[1] if ":" in line else line
            for m in re.findall(r"([0-9a-fA-F:.]+)", rest):
                if m.count(".") == 3 or ":" in m:
                    out.append(m.strip())
            continue
        if grab:
            m = re.match(r"\s+([0-9a-fA-F:.]+)\s*$", line)
            if m and (m.group(1).count(".") == 3 or ":" in m.group(1)):
                out.append(m.group(1))
                continue
        grab = False
    # de-dupe, keep order
    seen, res = set(), []
    for ip in out:
        if ip not in seen and ip not in ("::1",):
            seen.add(ip)
            res.append(ip)
    return res[:12]


# --------------------------------------------------------------------------
# raw socket HTTP (family-forced, SNI controlled)
# --------------------------------------------------------------------------
def resolve(host: str, family: int) -> tuple:
    t0 = time.perf_counter()
    try:
        infos = socket.getaddrinfo(host, 443, family, socket.SOCK_STREAM)
        ips, seen = [], set()
        for info in infos:
            ip = info[4][0]
            if ip not in seen:
                seen.add(ip)
                ips.append(ip)
        return ips, round((time.perf_counter() - t0) * 1000), None
    except Exception as e:
        return [], round((time.perf_counter() - t0) * 1000), \
            f"{type(e).__name__}: {e}"


def http_over_ip(host, family, path="/", method="GET", headers=None,
                 read_bytes=0, timeout=10.0, ip=None, sni=None, tls=True,
                 body=None, collect_body=False) -> dict:
    """HTTP/1.1 request to `host` forced through one address family.

    Returns measurements; never raises.
    """
    fam = AF4 if family == AF4 else AF6
    label = "v4" if family == AF4 else "v6"
    res = {"host": host, "family": label, "ip": ip, "ok": False,
           "status": None, "tcp_ms": None, "tls_ms": None,
           "total_ms": None, "bytes": 0, "kbps": None,
           "first_byte_ms": None, "error": None, "tls_version": None,
           "headers": {}}
    t0 = time.perf_counter()
    sock = None
    try:
        if ip is None:
            ips, dns_ms, err = resolve(host, fam)
            res["dns_ms"] = dns_ms
            if not ips:
                res["error"] = err or "no address"
                res["total_ms"] = round((time.perf_counter() - t0) * 1000)
                return res
            ip = ips[0]
            res["ip"] = ip
        soc = socket.socket(fam, socket.SOCK_STREAM)
        soc.settimeout(timeout)
        t_conn = time.perf_counter()
        if fam == AF4:
            soc.connect((ip, 443))
        else:
            soc.connect((ip, 443, 0, 0))
        res["tcp_ms"] = round((time.perf_counter() - t_conn) * 1000)
        sock = soc
        if tls:
            def _handshake(verify: bool):
                s2 = socket.socket(fam, socket.SOCK_STREAM)
                s2.settimeout(timeout)
                if fam == AF4:
                    s2.connect((ip, 443))
                else:
                    s2.connect((ip, 443, 0, 0))
                ctx = (ssl.create_default_context() if verify
                       else ssl._create_unverified_context())
                try:
                    ctx.set_alpn_protocols(["http/1.1"])
                except Exception:
                    pass
                return ctx.wrap_socket(s2, server_hostname=sni or host)

            t_tls = time.perf_counter()
            try:
                sock = _handshake(True)
            except ssl.SSLCertVerificationError as ce:
                # Bare apex hosts (e.g. googlevideo.com) carry a cert for other
                # names; the handshake itself still proves TCP/TLS reachability.
                res["tls_cert_error"] = str(ce)[:160]
                try:
                    soc.close()
                except Exception:
                    pass
                sock = _handshake(False)
                res["tls_unverified"] = True
            res["tls_ms"] = round((time.perf_counter() - t_tls) * 1000)
            res["tls_version"] = sock.version()

        hdrs = {"Host": host, "User-Agent": UA,
                "Accept": "*/*",
                "Accept-Language": "ru-RU,ru;q=0.9,en;q=0.8",
                "Connection": "close"}
        if headers:
            hdrs.update(headers)
        req = f"{method} {path} HTTP/1.1\r\n"
        for k, v in hdrs.items():
            req += f"{k}: {v}\r\n"
        payload = b""
        if body is not None:
            payload = body.encode("utf-8") if isinstance(body, str) else body
            req += f"Content-Length: {len(payload)}\r\n"
        req += "\r\n"
        sock.sendall(req.encode("latin-1", "replace") + payload)

        buf = b""
        while b"\r\n\r\n" not in buf:
            chunk = sock.recv(8192)
            if not chunk:
                break
            buf += chunk
        if b"\r\n\r\n" not in buf:
            res["error"] = "no-http-response"
            res["total_ms"] = round((time.perf_counter() - t0) * 1000)
            return res
        head, rest = buf.split(b"\r\n\r\n", 1)
        lines = head.split(b"\r\n")
        m = re.match(r"HTTP/\d\.\d\s+(\d{3})", lines[0].decode("latin-1"))
        res["status"] = int(m.group(1)) if m else None
        for ln in lines[1:]:
            if b":" in ln:
                k, v = ln.split(b":", 1)
                res["headers"][k.decode("latin-1").lower()] = \
                    v.decode("latin-1").strip()

        got = len(rest)
        t_first = time.perf_counter() if got else None
        want = read_bytes if read_bytes else 0
        if want and got < want:
            while got < want:
                chunk = sock.recv(min(65536, want - got))
                if not chunk:
                    break
                if t_first is None:
                    t_first = time.perf_counter()
                got += len(chunk)
        res["bytes"] = got
        end = time.perf_counter()
        res["total_ms"] = round((end - t0) * 1000)
        if t_first:
            res["first_byte_ms"] = round((t_first - t0) * 1000)
            dt = max(end - t_first, 0.001)
            res["kbps"] = round((got / 1024.0) / dt, 1)
        res["ok"] = res["status"] is not None
        if collect_body:
            res["body"] = rest[:2048].decode("utf-8", "replace")
    except ssl.SSLError as e:
        res["error"] = f"tls: {e}"
    except socket.timeout:
        res["error"] = f"timeout after {timeout}s"
    except OSError as e:
        res["error"] = f"{type(e).__name__}: {e}"
    except Exception as e:  # pragma: no cover
        res["error"] = f"{type(e).__name__}: {e}"
    finally:
        try:
            if sock is not None:
                sock.close()
        except Exception:
            pass
        if res["total_ms"] is None:
            res["total_ms"] = round((time.perf_counter() - t0) * 1000)
    res.setdefault("dns_ms", None)
    return res


def probe_endpoint(host: str, timeout: float = 4.0, path: str = "/") -> dict:
    """DNS + TCP + TLS + optional HTTP status for both families."""
    out = {"host": host, "path": path, "v4": {}, "v6": {}}
    for fam, key in ((AF4, "v4"), (AF6, "v6")):
        ips, dns_ms, err = resolve(host, fam)
        r = {"dns_ms": dns_ms, "ips": ips[:4], "dns_error": err,
             "tcp_ms": None, "tls_ms": None, "tls_version": None,
             "status": None, "ok": False, "error": None}
        if not ips:
            r["error"] = err or "no AAAA/A"
            out[key] = r
            continue
        pr = http_over_ip(host, fam, path, read_bytes=0, timeout=timeout,
                          ip=ips[0])
        for k in ("tcp_ms", "tls_ms", "tls_version", "status", "error"):
            r[k] = pr.get(k)
        r["ok"] = pr.get("ok", False)
        r["total_ms"] = pr.get("total_ms")
        out[key] = r
    return out


# --------------------------------------------------------------------------
# InnerTube player API
# --------------------------------------------------------------------------
WEB_REMIX_VERSION = "1.20241009.01.00"
CLIENTS = [
    {
        "name": "WEB_REMIX",
        "host": "music.youtube.com",
        "client_id": "67",
        "version": WEB_REMIX_VERSION,
        "origin": "https://music.youtube.com",
        "client": {
            "clientName": "WEB_REMIX",
            "clientVersion": WEB_REMIX_VERSION,
            "hl": "ru", "gl": "RU",
            "userAgent": UA,
            "originalUrl": "https://music.youtube.com/",
            "platform": "DESKTOP",
            "clientFormFactor": "UNKNOWN_FORM_FACTOR",
        },
        "ua": UA,
    },
    {
        "name": "ANDROID",
        "host": "www.youtube.com",
        "client_id": "3",
        "version": "19.44.38",
        "origin": "https://www.youtube.com",
        "client": {
            "clientName": "ANDROID",
            "clientVersion": "19.44.38",
            "androidSdkVersion": 30,
            "hl": "ru", "gl": "RU",
            "userAgent": ("com.google.android.youtube/19.44.38 "
                          "(Linux; U; Android 11) gzip"),
        },
        "ua": "com.google.android.youtube/19.44.38 (Linux; U; Android 11) gzip",
    },
    {
        # Embedded TV client: historically gives direct URLs without PO token.
        "name": "TVHTML5_SIMPLY",
        "host": "www.youtube.com",
        "client_id": "85",
        "version": "2.0",
        "origin": "https://www.youtube.com",
        "embed": {"embedUrl": "https://www.youtube.com/"},
        "client": {
            "clientName": "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            "clientVersion": "2.0",
            "hl": "ru", "gl": "RU",
            "userAgent": UA,
        },
        "ua": UA,
    },
    {
        "name": "TVHTML5",
        "host": "www.youtube.com",
        "client_id": "7",
        "version": "7.20250101.00.00",
        "origin": "https://www.youtube.com",
        "client": {
            "clientName": "TVHTML5",
            "clientVersion": "7.20250101.00.00",
            "hl": "ru", "gl": "RU",
            "userAgent": UA,
        },
        "ua": UA,
    },
]


def pick_audio(formats: list) -> dict | None:
    audio = []
    for f in formats or []:
        mime = (f.get("mimeType") or "")
        if mime.startswith("audio/"):
            audio.append(f)
    if not audio:
        return None
    def score(f):
        try:
            return int(f.get("bitrate") or 0)
        except Exception:
            return 0
    audio.sort(key=score, reverse=True)
    return audio[0]


def format_url(f: dict) -> str | None:
    url = f.get("url")
    if url:
        return url
    cipher = f.get("signatureCipher") or f.get("cipher")
    if cipher:
        try:
            q = urllib.parse.parse_qs(cipher)
            if q.get("url"):
                return urllib.parse.unquote(q["url"][0])
        except Exception:
            return None
    return None


def innertube_player(video_id: str, client_cfg: dict, timeout: float = 20.0) -> dict:
    host = client_cfg["host"]
    body = {
        "context": {"client": client_cfg["client"]},
        "videoId": video_id,
        "contentCheckOk": True,
        "racyCheckOk": True,
    }
    if client_cfg.get("embed"):
        body["context"]["thirdParty"] = client_cfg["embed"]
    payload = json.dumps(body)
    headers = {
        "Content-Type": "application/json",
        "X-YouTube-Client-Name": client_cfg["client_id"],
        "X-YouTube-Client-Version": client_cfg["version"],
        "X-Goog-Visitor-Id": "",
        "Origin": client_cfg["origin"],
        "Referer": client_cfg["origin"] + "/",
        "User-Agent": client_cfg["ua"],
        "Accept-Language": "ru-RU,ru;q=0.9",
    }
    res = {"client": client_cfg["name"], "host": host, "video": video_id,
           "ok": False, "http": None, "ms": None, "playability": None,
           "reason": None, "audio_formats": 0, "url": None, "itag": None,
           "mime": None, "bitrate": None, "content_length": None,
           "url_host": None, "has_n_param": None, "error": None}
    t0 = time.perf_counter()
    conn = None
    try:
        conn = HTTPSConnection(host, 443, timeout=timeout)
        conn.request("POST", "/youtubei/v1/player?prettyPrint=false",
                     body=payload, headers=headers)
        resp = conn.getresponse()
        raw = resp.read()
        res["http"] = resp.status
        res["ms"] = round((time.perf_counter() - t0) * 1000)
        if resp.status != 200:
            res["error"] = f"HTTP {resp.status}: " + raw[:200].decode("utf-8", "replace")
            return res
        data = json.loads(raw.decode("utf-8", "replace"))
        ps = data.get("playabilityStatus") or {}
        res["playability"] = ps.get("status")
        res["reason"] = ps.get("reason")
        sd = data.get("streamingData") or {}
        formats = (sd.get("adaptiveFormats") or []) + (sd.get("formats") or [])
        audio = [f for f in formats
                 if (f.get("mimeType") or "").startswith("audio/")]
        res["audio_formats"] = len(audio)
        best = pick_audio(formats)
        if best:
            u = format_url(best)
            res["itag"] = best.get("itag")
            res["mime"] = best.get("mimeType")
            res["bitrate"] = best.get("bitrate")
            res["content_length"] = best.get("contentLength")
            if u:
                res["url"] = u
                res["url_host"] = urllib.parse.urlsplit(u).hostname
                res["has_n_param"] = "n=" in urllib.parse.urlsplit(u).query
                res["ok"] = True
        if not res["ok"] and not res["error"]:
            res["error"] = "no audio format with resolvable url"
    except Exception as e:
        res["error"] = f"{type(e).__name__}: {e}"
        res["ms"] = round((time.perf_counter() - t0) * 1000)
    finally:
        try:
            if conn is not None:
                conn.close()
        except Exception:
            pass
    return res


# --------------------------------------------------------------------------
# media probe (Range 0-65535)
# --------------------------------------------------------------------------
def media_probe(url: str, family: int, timeout: float = 20.0,
                nbytes: int = SAMPLE_BYTES, referer: str | None = None) -> dict:
    if not url:
        return {"ok": False, "error": "no url", "bytes": 0}
    sp = urllib.parse.urlsplit(url)
    host = sp.hostname
    path = sp.path or "/"
    if sp.query:
        path += "?" + sp.query
    headers = {"Range": f"bytes=0-{max(nbytes - 1, 0)}"}
    if referer:
        headers["Referer"] = referer
    r = http_over_ip(host, family, path, read_bytes=nbytes, timeout=timeout,
                     headers=headers)
    out = {"url_host": host, "family": "v4" if family == AF4 else "v6",
           "ok": r.get("ok", False), "status": r.get("status"),
           "bytes": r.get("bytes"), "kbps": r.get("kbps"),
           "tcp_ms": r.get("tcp_ms"), "tls_ms": r.get("tls_ms"),
           "first_byte_ms": r.get("first_byte_ms"),
           "total_ms": r.get("total_ms"), "error": r.get("error"),
           "content_range": r.get("headers", {}).get("content-range"),
           "content_type": r.get("headers", {}).get("content-type")}
    if not out["ok"] and r.get("status") and r.get("body"):
        out["error"] = (r.get("body") or "")[:180]
    return out


def googlevideo_layers(host: str, url: str, timeout: float = 8.0) -> dict:
    """Distinguish DNS / TCP / TLS / HTTP / signed-media blocking on a
    googlevideo host. Each layer is measured independently."""
    out = {"host": host, "layers": {}}
    ips4, dns4_ms, err4 = resolve(host, AF4)
    out["layers"]["dns_v4"] = {"ok": bool(ips4), "ms": dns4_ms,
                               "ips": ips4[:4], "error": err4}
    ips6, dns6_ms, err6 = resolve(host, AF6)
    out["layers"]["dns_v6"] = {"ok": bool(ips6), "ms": dns6_ms,
                               "ips": ips6[:4], "error": err6}
    if ips4:
        # 1) TCP only
        t0 = time.perf_counter()
        tcp_ok, tcp_err = False, None
        s = None
        try:
            s = socket.socket(AF4, socket.SOCK_STREAM)
            s.settimeout(timeout)
            s.connect((ips4[0], 443))
            tcp_ok = True
        except Exception as e:
            tcp_err = f"{type(e).__name__}: {e}"
        finally:
            try:
                if s:
                    s.close()
            except Exception:
                pass
        out["layers"]["tcp_v4"] = {"ok": tcp_ok,
                                   "ms": round((time.perf_counter() - t0) * 1000),
                                   "error": tcp_err}
        # 2) TLS handshake with SNI = host
        t0 = time.perf_counter()
        tls_ok, tls_err, tls_ver = False, None, None
        s = None
        try:
            raw = socket.socket(AF4, socket.SOCK_STREAM)
            raw.settimeout(timeout)
            raw.connect((ips4[0], 443))
            ctx = ssl.create_default_context()
            try:
                ctx.set_alpn_protocols(["http/1.1"])
            except Exception:
                pass
            s = ctx.wrap_socket(raw, server_hostname=host)
            tls_ok = True
            tls_ver = s.version()
        except Exception as e:
            tls_err = f"{type(e).__name__}: {e}"
        finally:
            try:
                if s:
                    s.close()
            except Exception:
                pass
        out["layers"]["tls_v4"] = {"ok": tls_ok, "sni": host, "version": tls_ver,
                                   "ms": round((time.perf_counter() - t0) * 1000),
                                   "error": tls_err}
        # 3) plain HTTP GET / on that host, no signed params
        plain = http_over_ip(host, AF4, "/", read_bytes=0, timeout=timeout,
                             ip=ips4[0])
        out["layers"]["http_plain_v4"] = {
            "ok": plain.get("ok"), "status": plain.get("status"),
            "tcp_ms": plain.get("tcp_ms"), "tls_ms": plain.get("tls_ms"),
            "error": plain.get("error")}
        # 4) signed media, IPv4
        if url:
            out["layers"]["media_v4"] = media_probe(url, AF4,
                                                    timeout=timeout + 6)
            # 5) signed media, IPv6
            out["layers"]["media_v6"] = media_probe(url, AF6,
                                                    timeout=timeout + 2)
        else:
            out["layers"]["media_v4"] = {
                "ok": False, "error": "нет подписанного URL — байты не проверить"}
            out["layers"]["media_v6"] = {
                "ok": False, "error": "нет подписанного URL — байты не проверить"}
    # If IPv4 DNS failed entirely, still report IPv6 media attempt
    if not ips4 and url:
        out["layers"]["media_v6"] = media_probe(url, AF6, timeout=timeout + 2)
    return out


# --------------------------------------------------------------------------
# yt-dlp (optional, zipapp)
# --------------------------------------------------------------------------
def find_ytdlp(explicit: str | None = None) -> Path | None:
    cands = []
    if explicit:
        cands.append(Path(explicit))
    cands += [
        Path(os.environ.get("TEMP") or ".") / "yt-dlp-latest.pyz",
        ROOT / "scripts" / "dev" / "yt-dlp-latest.pyz",
        Path.home() / "yt-dlp" / "yt-dlp.pyz",
    ]
    for c in cands:
        try:
            if c.exists() and c.is_file():
                return c
        except Exception:
            pass
    return None


def ytdlp_version(pyz: Path) -> str | None:
    r = run_cmd([sys.executable, str(pyz), "--version"], timeout=60)
    if r["rc"] == 0 and r["out"]:
        return r["out"].splitlines()[-1].strip()
    return None


def js_runtimes() -> dict:
    found = {}
    for name in ("deno", "node", "qjs", "bun"):
        r = run_cmd(["where", name], timeout=8)
        if r["rc"] == 0 and r["out"]:
            found[name] = r["out"].splitlines()[0].strip()
    return found


def ytdlp_resolve(pyz: Path, video_id: str, extra_args: list,
                  timeout: float = 100.0) -> dict:
    args = [sys.executable, str(pyz),
            "--no-warnings", "--no-progress", "--no-playlist",
            "--simulate", "--get-url", "-f", "bestaudio/best",
            "--socket-timeout", "15", "--retries", "1",
            "--extractor-retries", "1"] + list(extra_args) + [
            f"https://www.youtube.com/watch?v={video_id}"]
    t0 = time.perf_counter()
    env = dict(os.environ)
    env["PYTHONIOENCODING"] = "utf-8"
    env["PYTHONUTF8"] = "1"
    r = run_cmd(args, timeout=timeout, env=env)
    ms = round((time.perf_counter() - t0) * 1000)
    url = None
    for line in (r["out"] or "").splitlines():
        line = line.strip()
        if line.startswith("http://") or line.startswith("https://"):
            url = line
    err = None
    if not url:
        for line in (r["err"] or "").splitlines():
            if "ERROR" in line or "error" in line.lower():
                err = line.strip()[:300]
                break
        if not err:
            err = (r["err"] or "").strip().splitlines()[-1][:300] \
                if (r["err"] or "").strip() else f"rc={r['rc']}"
    host = urllib.parse.urlsplit(url).hostname if url else None
    return {"variant": None, "ok": bool(url), "ms": ms, "url": url,
            "url_host": host, "rc": r["rc"], "error": err,
            "stderr_tail": (r["err"] or "")[-400:]}


YTDLP_VARIANTS = [
    ("default", []),
    ("tv_simply", ["--extractor-args", "youtube:player_client=tv_simply"]),
    ("tv", ["--extractor-args", "youtube:player_client=tv"]),
    ("android_vr", ["--extractor-args", "youtube:player_client=android_vr"]),
    ("web_safari", ["--extractor-args", "youtube:player_client=web_safari"]),
    ("ios", ["--extractor-args", "youtube:player_client=ios"]),
    ("mweb", ["--extractor-args", "youtube:player_client=mweb"]),
    ("default@ipv4", ["--force-ipv4"]),
    ("tv_simply@ipv4", ["--force-ipv4",
                        "--extractor-args", "youtube:player_client=tv_simply"]),
    ("default@ipv6", ["--force-ipv6"]),
]


def run_ytdlp_matrix(args, videos, notes) -> dict:
    pyz = find_ytdlp(args.ytdlp)
    if not pyz:
        notes.append("yt-dlp: zipapp not found (pass --ytdlp PATH)")
        return {"available": False, "version": None, "runs": []}
    ver = ytdlp_version(pyz)
    out = {"available": True, "path": str(pyz), "version": ver,
           "js_runtimes": js_runtimes(), "runs": []}
    tasks = []
    primary = videos[0][0]
    for name, extra in YTDLP_VARIANTS:
        tasks.append((primary, name, extra))
    if len(videos) > 1:
        for name, extra in YTDLP_VARIANTS[:3]:
            tasks.append((videos[1][0], name, extra))

    def one(vid, name, extra):
        r = ytdlp_resolve(pyz, vid, extra, timeout=args.timeout + 75)
        r["variant"] = name
        r["video"] = vid
        fam = AF4
        if name.endswith("@ipv6"):
            fam = AF6
        if r["ok"]:
            r["probe"] = media_probe(r["url"], fam, timeout=args.timeout + 6,
                                     nbytes=32768,
                                     referer="https://www.youtube.com/")
        return r

    with ThreadPoolExecutor(max_workers=3) as ex:
        futs = [ex.submit(one, v, n, e) for (v, n, e) in tasks]
        for f in as_completed(futs):
            try:
                out["runs"].append(f.result())
            except Exception as e:  # pragma: no cover
                notes.append(f"yt-dlp variant crashed: {type(e).__name__}: {e}")
    order = {n: i for i, (n, _) in enumerate(YTDLP_VARIANTS)}
    out["runs"].sort(key=lambda r: (r.get("video") != primary,
                                    order.get(r.get("variant"), 99)))
    ok = sum(1 for r in out["runs"] if r.get("ok"))
    notes.append(f"yt-dlp {ver}: {ok}/{len(out['runs'])} variants resolved")
    return out


# --------------------------------------------------------------------------
# public relays: Invidious + Piped
# --------------------------------------------------------------------------
INVIDIOUS_HOSTS = [
    "inv.nadeko.net", "inv.tux.pizza", "inv.zzls.xyz",
    "invidious.adminforge.de", "invidious.dhusch.de",
    "invidious.einfachzocken.eu", "invidious.f5.si",
    "invidious.jing.rocks", "invidious.lunivers.trade",
    "invidious.materialio.us", "invidious.nerdvpn.de",
    "invidious.nikkosphere.com", "invidious.privacyredirect.com",
    "invidious.protokolla.fi", "invidious.reallyaweso.me",
    "invidious.tiekoetter.com", "iv.datura.network", "iv.ggtyler.dev",
    "iv.melmac.space",
]
PIPED_HOSTS = [
    "pipedapi.adminforge.de", "pipedapi.kavin.rocks", "pipedapi.r4fo.com",
    "pipedapi.drgns.space", "pipedapi.astartes.nl", "pipedapi.ducks.party",
    "pipedapi.leptons.xyz", "pipedapi.nosebs.ru", "pipedapi.orangenet.cc",
    "pipedapi.reallyaweso.me",
]


def http_json(url: str, timeout: float = 8.0) -> dict:
    req = urllib.request.Request(url, headers={
        "User-Agent": UA, "Accept": "application/json"})
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            code = resp.status
            raw = resp.read(4 * 1024 * 1024)
        return {"ok": True, "http": code,
                "json": json.loads(raw.decode("utf-8", "replace")),
                "ms": round((time.perf_counter() - t0) * 1000)}
    except urllib.error.HTTPError as e:
        try:
            body = e.read()[:300].decode("utf-8", "replace")
        except Exception:
            body = ""
        return {"ok": False, "http": e.code,
                "error": f"HTTP {e.code}: {body}",
                "ms": round((time.perf_counter() - t0) * 1000)}
    except Exception as e:
        return {"ok": False, "http": None,
                "error": f"{type(e).__name__}: {e}",
                "ms": round((time.perf_counter() - t0) * 1000)}


def best_stream(streams: list, keys=("bitrate",)) -> dict | None:
    if not streams:
        return None
    def score(s):
        for k in keys:
            try:
                return int(s.get(k) or 0)
            except Exception:
                pass
        return 0
    return sorted(streams, key=score, reverse=True)[0]


def relay_invidious(host: str, video_id: str, timeout: float) -> dict:
    base = f"https://{host}"
    r = {"kind": "invidious", "host": host, "video": video_id}
    j = http_json(f"{base}/api/v1/videos/{video_id}?local=true", timeout=timeout)
    r["api_http"] = j["http"]
    r["api_ms"] = j["ms"]
    if not j["ok"]:
        r["error"] = j.get("error")
        return r
    d = j["json"]
    r["title"] = d.get("title")
    fmts = d.get("adaptiveFormats") or []
    audio = [f for f in fmts
             if str(f.get("type") or f.get("mimeType") or "").startswith("audio")]
    best = best_stream(audio)
    if not best:
        r["error"] = "no audio adaptiveFormats"
        return r
    url = best.get("url")
    r["itag"] = best.get("itag")
    r["url_host"] = urllib.parse.urlsplit(url).hostname if url else None
    if not url:
        r["error"] = "no url"
        return r
    probe = media_probe(url, AF4, timeout=timeout, nbytes=32768,
                        referer=base + "/")
    r["probe"] = probe
    r["ok"] = bool(probe.get("ok") and (probe.get("status") in (200, 206))
                   and (probe.get("bytes") or 0) > 1000)
    if not r["ok"] and not r.get("error"):
        r["error"] = probe.get("error") or f"HTTP {probe.get('status')}"
    return r


def relay_piped(host: str, video_id: str, timeout: float) -> dict:
    base = f"https://{host}"
    r = {"kind": "piped", "host": host, "video": video_id}
    j = http_json(f"{base}/streams/{video_id}", timeout=timeout)
    r["api_http"] = j["http"]
    r["api_ms"] = j["ms"]
    if not j["ok"]:
        r["error"] = j.get("error")
        return r
    d = j["json"]
    r["title"] = d.get("title")
    streams = d.get("audioStreams") or []
    best = best_stream(streams)
    if not best:
        r["error"] = "no audioStreams"
        return r
    url = best.get("url") or best.get("proxyUrl")
    r["itag"] = best.get("itag")
    r["url_host"] = urllib.parse.urlsplit(url).hostname if url else None
    if not url:
        r["error"] = "no url"
        return r
    probe = media_probe(url, AF4, timeout=timeout, nbytes=32768,
                        referer=base + "/")
    r["probe"] = probe
    r["ok"] = bool(probe.get("ok") and (probe.get("status") in (200, 206))
                   and (probe.get("bytes") or 0) > 1000)
    if not r["ok"] and not r.get("error"):
        r["error"] = probe.get("error") or f"HTTP {probe.get('status')}"
    return r


def discover_invidious(timeout: float = 8.0) -> list:
    j = http_json("https://api.invidious.io/instances.json"
                  "?pretty=1&sort_by=type,users", timeout=timeout)
    if not j["ok"]:
        return []
    hosts = []
    try:
        for item in j["json"]:
            if not (isinstance(item, list) and len(item) == 2):
                continue
            info = item[1]
            if not isinstance(info, dict):
                continue
            if info.get("type") not in (None, "https"):
                continue
            if info.get("api") is False:
                continue
            uri = info.get("uri") or ""
            h = urllib.parse.urlsplit(uri).hostname
            if h:
                hosts.append(h)
    except Exception:
        pass
    return hosts[:10]


def run_relays(videos, timeout, notes) -> dict:
    vid = videos[0][0]
    disc = discover_invidious(timeout=min(timeout + 3, 8))
    hosts = []
    for h in INVIDIOUS_HOSTS + disc:
        if h not in hosts:
            hosts.append(h)
    tasks = [("invidious", h) for h in hosts] + \
            [("piped", h) for h in PIPED_HOSTS]

    def one(kind, host):
        try:
            if kind == "invidious":
                return relay_invidious(host, vid, timeout)
            return relay_piped(host, vid, timeout)
        except Exception as e:
            return {"kind": kind, "host": host, "ok": False,
                    "error": f"{type(e).__name__}: {e}"}

    results = []
    with ThreadPoolExecutor(max_workers=20) as ex:
        futs = [ex.submit(one, k, h) for k, h in tasks]
        for f in as_completed(futs):
            try:
                results.append(f.result())
            except Exception as e:  # pragma: no cover
                notes.append(f"relay crashed: {type(e).__name__}: {e}")
    working = sum(1 for r in results if r.get("ok"))
    notes.append(f"relays: {working}/{len(results)} работают с 32 КиБ")
    return {"discovery_ok": bool(disc), "discovered": disc,
            "invidious_hosts": hosts, "piped_hosts": PIPED_HOSTS,
            "results": results}


# --------------------------------------------------------------------------
# orchestration
# --------------------------------------------------------------------------
ENDPOINTS = [
    ("youtube.com", "/"),
    ("www.youtube.com", "/"),
    ("music.youtube.com", "/"),
    ("music.youtube.com", "/youtubei/v1/player?prettyPrint=false"),
    ("youtubei.googleapis.com", "/"),
    ("googlevideo.com", "/"),
    ("i.ytimg.com", "/vi/dQw4w9WgXcQ/default.jpg"),
    ("yt3.ggpht.com", "/"),
]


def collect_env() -> dict:
    return {
        "platform": platform_label(),
        "python": sys.version.split()[0],
        "hostname": socket.gethostname(),
        "user": os.environ.get("USERNAME"),
        "cwd": str(Path.cwd()),
    }


def platform_label() -> str:
    try:
        return f"{os.name} {sys.platform} {sys.version.split()[0]}"
    except Exception:
        return sys.platform


def restore_warp(before: str, notes: list) -> dict:
    """Restore the exact pre-run WARP state and verify internet."""
    info = {"before": before, "after": warp_status(), "ok": True,
            "attempts": [], "internet": None}
    if before not in ("Connected", "Disconnected"):
        info["skipped"] = True
        return info
    cur = warp_status()
    if cur != before:
        r = warp_set(before)
        info["attempts"].append(r)
        if not r.get("ok"):
            info["ok"] = False
            notes.append(f"WARP RESTORE FAILED: {r}")
    ip = None
    for attempt in range(4):
        p = public_ip(AF4, timeout=8)
        if p.get("ok"):
            ip = p.get("ip")
            break
        if warp_status() != before:
            warp_set(before)
        time.sleep(1.5)
    info["internet"] = ip
    info["after"] = warp_status()
    if not ip:
        info["ok"] = False
        notes.append("КРИТИЧНО: после восстановления WARP нет интернета (IPv4)")
    elif info["after"] != before:
        info["ok"] = False
        notes.append(f"WARP восстановлен не в исходное состояние: "
                     f"{before} -> {info['after']}")
    return info


def tls_probe(host: str, ip: str, sni: str, timeout: float = 5.0) -> dict:
    """TCP + TLS handshake to `ip` but with a chosen SNI. Separates
    'host is blocked by name (DPI)' from 'host/IP is reachable'."""
    t0 = time.perf_counter()
    res = {"host": host, "ip": ip, "sni": sni, "ok": False, "ms": None,
           "tls_version": None, "error": None}
    s = None
    try:
        s = socket.socket(AF4, socket.SOCK_STREAM)
        s.settimeout(timeout)
        s.connect((ip, 443))
        ctx = ssl.create_default_context()
        try:
            ctx.set_alpn_protocols(["http/1.1"])
        except Exception:
            pass
        s = ctx.wrap_socket(s, server_hostname=sni)
        res["ok"] = True
        res["tls_version"] = s.version()
    except Exception as e:
        res["error"] = f"{type(e).__name__}: {e}"
    finally:
        try:
            if s:
                s.close()
        except Exception:
            pass
        res["ms"] = round((time.perf_counter() - t0) * 1000)
    return res


def sni_experiment(gv_hosts, timeout: float = 5.0) -> dict:
    """Is the block SNI-based? Test the same IP with the real SNI and with a
    neutral SNI. Applies to youtube.com and to googlevideo media edges."""
    out = {"youtube_com": [], "googlevideo_edges": []}
    ips, _, _ = resolve("youtube.com", AF4)
    host = "youtube.com"
    if ips:
        ip = ips[0]
        out["youtube_com"] = [
            tls_probe(host, ip, host, timeout),
            tls_probe(host, ip, "example.com", timeout),
            tls_probe(host, ip, "google.com", timeout),
        ]
    edges = [h for h in (gv_hosts or []) if h]
    if not edges:
        edges = ["rr3---sn-u125g5-5p.googlevideo.com",
                 "rr1---sn-4g5e6nez.googlevideo.com"]
    for h in edges[:2]:
        eips, _, _ = resolve(h, AF4)
        if not eips:
            out["googlevideo_edges"].append(
                {"host": h, "dns_ok": False, "probes": []})
            continue
        out["googlevideo_edges"].append({
            "host": h, "dns_ok": True, "ip": eips[0],
            "probes": [tls_probe(h, eips[0], h, timeout),
                       tls_probe(h, eips[0], "example.com", timeout)]})
    return out


def run_audit(args) -> dict:
    started = now_msk()
    before = warp_status()
    notes = []
    toggle = None
    target = {"on": "Connected", "off": "Disconnected"}.get(args.warp)
    if target and before not in ("Unavailable",) and before != target:
        toggle = warp_set(target)
        notes.append(f"WARP: {before} -> {toggle.get('after')} "
                     f"({toggle.get('ms')} ms)")
        if not toggle.get("ok"):
            notes.append(f"WARP toggle failed: {toggle}")
            notes.append("Продолжаем с фактическим состоянием WARP")
    actual = warp_status()
    data = {}
    try:
        data["env"] = collect_env()
        data["warp_state"] = actual
        data["egress"] = {
            "v4": public_ip(AF4, timeout=10),
            "v6": public_ip(AF6, timeout=10),
            "dns_servers": dns_servers(),
        }
        # 1. endpoints (DNS/TCP/TLS/HTTP) - v4 and v6 separately
        eps = []
        for host, path in ENDPOINTS:
            try:
                eps.append(probe_endpoint(host, timeout=args.timeout, path=path))
            except Exception as e:
                eps.append({"host": host, "path": path, "error":
                            f"{type(e).__name__}: {e}"})
        data["endpoints"] = eps
        # 1b. SNI experiment (only meaningful on the real, unproxied network)
        if actual == "Disconnected":
            try:
                data["sni_test"] = sni_experiment(
                    getattr(args, "fallback_gv_hosts", None),
                    timeout=args.timeout)
            except Exception as e:
                notes.append(f"SNI test failed: {type(e).__name__}: {e}")
        # 2. InnerTube player resolve
        inn = []
        for vid, kind in args.videos:
            for cfg in CLIENTS:
                try:
                    inn.append(innertube_player(vid, cfg,
                                                timeout=args.timeout + 12))
                except Exception as e:
                    inn.append({"client": cfg["name"], "video": vid,
                                "ok": False,
                                "error": f"{type(e).__name__}: {e}"})
        data["innertube"] = inn
        # 3. media Range probe + googlevideo layer analysis
        media, gvl = [], []
        seen_hosts = set()
        for e in inn:
            if not e.get("url"):
                continue
            mp = media_probe(e["url"], AF4, timeout=args.timeout + 8)
            mp.update({"client": e.get("client"), "video": e.get("video"),
                       "itag": e.get("itag")})
            media.append(mp)
            h = e.get("url_host")
            if h and h not in seen_hosts:
                seen_hosts.add(h)
                try:
                    gvl.append(googlevideo_layers(h, e["url"],
                                                  timeout=args.timeout))
                except Exception as ex:
                    gvl.append({"host": h,
                                "error": f"{type(ex).__name__}: {ex}"})
        data["media"] = media
        data["googlevideo"] = gvl
        # 4. yt-dlp
        if args.with_ytdlp:
            data["ytdlp"] = run_ytdlp_matrix(args, args.videos, notes)
            # If InnerTube gave us no media host, layer-test a googlevideo edge
            # host that yt-dlp resolved (works even without signed params).
            if not gvl:
                hosts = [r.get("url_host") for r in
                         (data["ytdlp"].get("runs") or []) if r.get("url_host")]
                hosts = [h for h in hosts if "googlevideo" in h]
                if hosts:
                    try:
                        gvl.append(googlevideo_layers(hosts[0], "",
                                                      timeout=args.timeout))
                    except Exception as ex:
                        gvl.append({"host": hosts[0],
                                    "error": f"{type(ex).__name__}: {ex}"})
                    data["googlevideo"] = gvl
        # 4b. last resort: edge host remembered from a previous run
        if not data.get("googlevideo"):
            for h in (getattr(args, "fallback_gv_hosts", None) or []):
                if "googlevideo" in h:
                    try:
                        data["googlevideo"] = [googlevideo_layers(
                            h, "", timeout=args.timeout)]
                    except Exception as ex:
                        data["googlevideo"] = [{"host": h,
                                                "error":
                                                f"{type(ex).__name__}: {ex}"}]
                    break
        # 5. relays (skip in --quick)
        if not args.quick:
            data["relays"] = run_relays(args.videos, args.timeout, notes)
    except Exception as e:
        notes.append(f"EXCEPTION: {type(e).__name__}: {e}")
    finally:
        restore = restore_warp(before, notes)
    run = {
        "id": (f"phase:{args.phase}" if args.phase else f"auto:{actual}:{started.strftime('%Y%m%d-%H%M%S')}"),
        "label": args.phase or f"WARP {actual}",
        "phase": args.phase,
        "started_at": iso(started),
        "finished_at": iso(now_msk()),
        "warp_requested": args.warp,
        "warp_before": before,
        "warp_state": actual,
        "warp_toggle": toggle,
        "restore": restore,
        "quick": args.quick,
        "with_ytdlp": args.with_ytdlp,
        "videos": [{"id": v, "kind": k} for v, k in args.videos],
        "notes": notes,
    }
    run.update(data)
    return run


def load_doc(path: Path) -> dict:
    if path.exists():
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except Exception:
            return {}
    return {}


def save_run(doc: dict, run: dict, path: Path) -> dict:
    runs = [r for r in doc.get("runs", []) if r.get("id") != run["id"]]
    runs.append(run)
    runs.sort(key=lambda r: r.get("started_at") or "")
    doc["schema"] = 1
    doc["generated_at"] = iso(now_msk())
    doc["runs"] = runs
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n",
                    encoding="utf-8")
    return doc


# --------------------------------------------------------------------------
# summary helpers
# --------------------------------------------------------------------------
def ep_ok(ep: dict, family: str) -> bool:
    d = ep.get(family) or {}
    return bool(d.get("ok"))


def run_media_ok(run: dict) -> bool:
    for m in run.get("media") or []:
        if m.get("status") in (200, 206) and (m.get("bytes") or 0) >= 1024:
            return True
    return False


def run_api_ok(run: dict) -> bool:
    return any(e.get("ok") for e in (run.get("innertube") or []))


def run_ep_stats(run: dict) -> tuple:
    eps = run.get("endpoints") or []
    v4 = sum(1 for e in eps if ep_ok(e, "v4"))
    v6 = sum(1 for e in eps if ep_ok(e, "v6"))
    return v4, v6, len(eps)


def run_ytdlp_ok_count(run: dict) -> tuple:
    y = run.get("ytdlp") or {}
    rs = y.get("runs") or []
    return sum(1 for r in rs if r.get("ok")), len(rs)


def run_relay_ok_count(run: dict) -> tuple:
    rel = run.get("relays") or {}
    rs = rel.get("results") or []
    return sum(1 for r in rs if r.get("ok")), len(rs)


def pick_run(runs: list, state: str) -> dict | None:
    cands = [r for r in runs if r.get("warp_state") == state]
    return cands[-1] if cands else None


# --------------------------------------------------------------------------
# markdown report (Russian)
# --------------------------------------------------------------------------
def md_table(headers, rows) -> str:
    out = ["| " + " | ".join(str(h) for h in headers) + " |",
           "|" + "|".join(["---"] * len(headers)) + "|"]
    for r in rows:
        out.append("| " + " | ".join(str(c) for c in r) + " |")
    return "\n".join(out)


def yn(b) -> str:
    return "✅" if b else "❌"


def short(v, n=70) -> str:
    s = "" if v is None else str(v)
    s = s.replace("|", "\\|").replace("\n", " ")
    return s if len(s) <= n else s[:n - 1] + "…"


def egress_str(run: dict) -> str:
    eg = run.get("egress") or {}
    v4 = (eg.get("v4") or {}).get("ip") or "n/a"
    v6 = (eg.get("v6") or {}).get("ip") or "нет"
    return f"IPv4 {v4} / IPv6 {v6}"


def section_endpoints(run: dict) -> str:
    rows = []
    for e in run.get("endpoints") or []:
        for fam in ("v4", "v6"):
            d = e.get(fam) or {}
            ips = ", ".join(d.get("ips") or []) or "—"
            tcp = d.get("tcp_ms")
            tls = d.get("tls_ms")
            st = d.get("status")
            err = d.get("error")
            note = ""
            if err:
                note = short(err, 46)
            elif st is not None:
                note = f"HTTP {st}"
            rows.append([e.get("host"), ("IPv4" if fam == "v4" else "IPv6"),
                         d.get("dns_ms"), short(ips, 44),
                         "—" if tcp is None else tcp,
                         "—" if tls is None else tls,
                         "—" if st is None else st, note])
    return md_table(["хост", "сем.", "DNS, мс", "адреса", "TCP, мс",
                     "TLS, мс", "HTTP", "примечание"], rows)


def section_innertube(run: dict) -> str:
    rows = []
    for e in run.get("innertube") or []:
        rows.append([e.get("video"), e.get("client"),
                     "—" if e.get("ms") is None else e.get("ms"),
                     e.get("http") or "—",
                     e.get("playability") or "—",
                     e.get("audio_formats", "—"),
                     short(e.get("reason") or e.get("error") or "", 50),
                     yn(e.get("ok"))])
    return md_table(["video", "client", "мс", "HTTP", "playability",
                     "аудио-форматов", "причина/ошибка", "url"], rows)


def section_media(run: dict) -> str:
    rows = []
    for m in run.get("media") or []:
        rows.append([m.get("client") or "—", m.get("video") or "—",
                     m.get("itag") or "—", m.get("url_host") or "—",
                     m.get("status") or "—", fmt_kb(m.get("bytes") or 0),
                     "—" if m.get("kbps") is None else m.get("kbps"),
                     "—" if m.get("first_byte_ms") is None
                     else m.get("first_byte_ms"),
                     short(m.get("error") or "", 40),
                     yn(m.get("status") in (200, 206)
                        and (m.get("bytes") or 0) > 1024)])
    return md_table(["client", "video", "itag", "host", "HTTP", "байт",
                     "КиБ/с", "1-й байт, мс", "ошибка", "ok"], rows)


def section_gvl(run: dict) -> str:
    out = []
    for g in run.get("googlevideo") or []:
        out.append(f"**{g.get('host')}**")
        layers = g.get("layers") or {}
        if not layers:
            out.append(f"- ошибка: {g.get('error')}")
            continue
        order = [("dns_v4", "DNS IPv4"), ("dns_v6", "DNS IPv6"),
                 ("tcp_v4", "TCP:443"), ("tls_v4", "TLS/SNI"),
                 ("http_plain_v4", "HTTP GET / (без подписи)"),
                 ("media_v4", "медиа Range IPv4"),
                 ("media_v6", "медиа Range IPv6")]
        rows = []
        for key, name in order:
            d = layers.get(key)
            if not d:
                continue
            ok = d.get("ok")
            if key == "http_plain_v4":
                ok = d.get("status") is not None
            extra = []
            for k in ("ms", "tcp_ms", "tls_ms", "status", "bytes", "kbps",
                      "version"):
                if d.get(k) is not None:
                    extra.append(f"{k}={d[k]}")
            if d.get("error"):
                extra.append(short(d["error"], 60))
            rows.append([name, yn(bool(ok)), " ".join(extra) or "—"])
        out.append(md_table(["слой", "ok", "детали"], rows))
        out.append("")
    return "\n".join(out)


def section_ytdlp(run: dict) -> str:
    y = run.get("ytdlp")
    if not y:
        return "_yt-dlp не запускался в этой фазе._"
    if not y.get("available"):
        return "_yt-dlp zipapp не найден._"
    rows = []
    for r in y.get("runs") or []:
        p = r.get("probe") or {}
        rows.append([r.get("video"), r.get("variant"), r.get("ms"),
                     yn(r.get("ok")),
                     short(r.get("url_host") or "", 34),
                     p.get("status") or "—",
                     "—" if p.get("bytes") is None else fmt_kb(p.get("bytes")),
                     "—" if p.get("kbps") is None else p.get("kbps"),
                     short(r.get("error") or p.get("error") or "", 60)])
    head = (f"Версия yt-dlp: **{y.get('version')}**; JS-рантаймы: "
            f"{', '.join((y.get('js_runtimes') or {}).keys()) or 'не найдены'}")
    return head + "\n\n" + md_table(
        ["video", "вариант", "мс", "resolve", "host", "HTTP", "байт",
         "КиБ/с", "ошибка"], rows)


def section_relays(run: dict) -> str:
    rel = run.get("relays")
    if not rel:
        return "_Релеи не проверялись в этой фазе._"
    rows = []
    for r in rel.get("results") or []:
        p = r.get("probe") or {}
        rows.append([r.get("kind"), r.get("host"), r.get("api_http") or "—",
                     "—" if r.get("api_ms") is None else r.get("api_ms"),
                     short(r.get("url_host") or "", 30),
                     p.get("status") or "—",
                     "—" if p.get("bytes") is None else fmt_kb(p.get("bytes")),
                     "—" if p.get("kbps") is None else p.get("kbps"),
                     short(r.get("error") or p.get("error") or "", 46),
                     yn(r.get("ok"))])
    disc = rel.get("discovered") or []
    head = (f"Автообнаружение api.invidious.io: "
            f"{yn(rel.get('discovery_ok'))}, найдено {len(disc)}; "
            f"всего проверено {len(rel.get('results') or [])} хостов.")
    return head + "\n\n" + md_table(
        ["тип", "хост", "API HTTP", "API, мс", "медиа-host", "HTTP", "байт",
         "КиБ/с", "ошибка", "ok"], rows)


def phase_block(run: dict, idx: int) -> str:
    lines = [f"## {idx}. {run.get('label')}"]
    lines.append("")
    lines.append(f"- Начало (МСК): **{run.get('started_at')}**, "
                 f"конец: **{run.get('finished_at')}**")
    lines.append(f"- WARP фактически: **{run.get('warp_state')}** "
                 f"(до запуска: {run.get('warp_before')})")
    lines.append(f"- Egress: {egress_str(run)}")
    dns = ((run.get("egress") or {}).get("dns_servers") or [])
    lines.append(f"- DNS-серверы: {', '.join(dns) if dns else '—'}")
    v4, v6, total = run_ep_stats(run)
    lines.append(f"- Инфраструктура YouTube: IPv4 {v4}/{total} хостов, "
                 f"IPv6 {v6}/{total} хостов")
    lines.append(f"- InnerTube: {'доступен' if run_api_ok(run) else 'недоступен'}; "
                 f"медиа Range: {'работает' if run_media_ok(run) else 'не работает'}")
    if run.get("ytdlp"):
        a, b = run_ytdlp_ok_count(run)
        lines.append(f"- yt-dlp: {a}/{b} вариантов получили URL")
    if run.get("relays"):
        a, b = run_relay_ok_count(run)
        lines.append(f"- Релеи: {a}/{b} отдали рабочий аудио-поток")
    if run.get("notes"):
        lines.append("")
        lines.append("Заметки: " + "; ".join(short(n, 160) for n in run["notes"]))
    lines.append("")
    lines.append("**DNS / TCP / TLS / HTTP по хостам**")
    lines.append("")
    lines.append(section_endpoints(run))
    lines.append("")
    lines.append("**InnerTube `player` API**")
    lines.append("")
    lines.append(section_innertube(run))
    lines.append("")
    lines.append("**Медиа: Range 0–65535 (64 КиБ)**")
    lines.append("")
    lines.append(section_media(run))
    if run.get("googlevideo"):
        lines.append("")
        lines.append("**Послойный разбор googlevideo (DNS → TCP → TLS/SNI → "
                     "HTTP → подписанное медиа)**")
        lines.append("")
        lines.append(section_gvl(run))
    if run.get("sni_test"):
        lines.append("")
        lines.append("**Блокировка по SNI: один и тот же IP, подставной SNI**")
        lines.append("")
        lines.append(section_sni(run))
    if run.get("ytdlp"):
        lines.append("")
        lines.append("**yt-dlp (актуальный zipapp)**")
        lines.append("")
        lines.append(section_ytdlp(run))
    if run.get("relays"):
        lines.append("")
        lines.append("**Публичные релеи (Invidious / Piped)**")
        lines.append("")
        lines.append(section_relays(run))
    lines.append("")
    return "\n".join(lines)


def section_sni(run: dict) -> str:
    st = run.get("sni_test") or {}
    rows = []
    for p in st.get("youtube_com") or []:
        rows.append(["youtube.com", p.get("ip"), p.get("sni"), p.get("ms"),
                     yn(p.get("ok")), short(p.get("error") or "", 70)])
    for e in st.get("googlevideo_edges") or []:
        if not e.get("dns_ok"):
            rows.append([e.get("host"), "—", "DNS не резолвится", "—", "❌", ""])
            continue
        for p in e.get("probes") or []:
            rows.append([e.get("host"), e.get("ip"), p.get("sni"),
                         p.get("ms"), yn(p.get("ok")),
                         short(p.get("error") or "", 70)])
    out = md_table(["цель", "IP", "SNI", "мс", "TLS ok", "ошибка"], rows)
    yt = st.get("youtube_com") or []
    if len(yt) >= 2:
        real = yt[0]
        fake = yt[1]
        if (not real.get("ok")) and fake.get("ok"):
            out += ("\n\n**Вывод:** TCP:443 устанавливается, но TLS с SNI "
                    "`youtube.com` рвётся, а с нейтральным SNI — проходит. "
                    "Это блокировка по SNI (DPI), а не по IP и не по DNS.")
        elif real.get("ok"):
            out += "\n\n**Вывод:** SNI `youtube.com` проходит — блокировки по SNI нет."
    return out


def build_recommendations(doc: dict) -> list:
    runs = doc.get("runs") or []
    off = pick_run(runs, "Disconnected")
    on = pick_run(runs, "Connected")
    off_media = run_media_ok(off) if off else None
    off_api = run_api_ok(off) if off else None
    on_media = run_media_ok(on) if on else None
    y_off = run_ytdlp_ok_count(off) if (off and off.get("ytdlp")) else (0, 0)
    rec = []
    if off is not None:
        sni = off.get("sni_test") or {}
        yt = sni.get("youtube_com") or []
        if len(yt) >= 2 and (not yt[0].get("ok")) and yt[1].get("ok"):
            rec.append("Блокировка **по SNI (DPI)**: TCP:443 проходит, TLS с SNI "
                       "`youtube.com` обрывается RST, с нейтральным SNI — "
                       "рукопожатие проходит. Значит обход — это смена SNI "
                       "(WARP/фронтинг), а не смена IP/DNS.")
        if off_media:
            rec.append("**Прямой googlevideo из РФ работает** — основной путь "
                       "можно оставить без WARP; WARP держать как резерв.")
        else:
            rec.append("**Прямой googlevideo из РФ не работает** — ни InnerTube, "
                       "ни медиа-байты не проходят. Значит встроенный «прямой» "
                       "путь в приложении не может быть основным для РФ.")
        if off_api and not off_media:
            rec.append("InnerTube `player` отвечает из РФ, но байты с "
                       "`*.googlevideo.com` не идут → блокировка не на API, а на "
                       "медиа-хостах (TLS/SNI или сам поток).")
    if y_off[1]:
        if y_off[0]:
            rec.append(f"yt-dlp из РФ: {y_off[0]}/{y_off[1]} вариантов "
                       "разрешают URL. Использовать только рабочие "
                       "`player_client`, остальные не пробовать.")
        else:
            rec.append("yt-dlp из РФ: 0 вариантов смогли получить рабочий URL — "
                       "держать его как офлайн/через-WARP инструмент, не как "
                       "основной онлайн-путь.")
    if on is not None:
        if on_media:
            rec.append("**WARP восстанавливает доступ** (InnerTube + медиа "
                       "работают через egress Cloudflare) — это рабочий "
                       "«свой» транспорт для РФ.")
        else:
            rec.append("**WARP не даёт медиа** — egress Cloudflare YouTube "
                       "отклоняет (403) или режет поток; тогда WARP не решение.")
    rec += [
        "Порядок для автопилота (РФ): 1) кэш/локальные файлы; 2) рабочий "
        "релей из замеренного списка (если есть); 3) WARP-туннель "
        "(если он даёт 206 и ≥ ~100 КиБ/с); 4) прямой googlevideo только "
        "если замер показал, что он живой. Кэшировать вердикт на 10–15 минут.",
        "Таймауты: DNS/TCP/TLS — 2500–3000 мс на попытку, Range-проба — "
        "6000–8000 мс, полный отказ транспорта — после 2 попыток. Не держать "
        "пользователя на «прямом» пути дольше ~8 с в РФ.",
        "«Быстрый и стабильный свой WARP-метод» реалистично = WARP уже "
        "поднят + проба 64 КиБ перед стартом + ротация endpoint'ов/профиля "
        "при 403/таймауте. WARP не отменяет блокировку YouTube для части "
        "egress-IP; поэтому обязательна проверка именно байтов, а не только "
        "API-ответа.",
    ]
    return rec


def build_report(doc: dict) -> str:
    runs = doc.get("runs") or []
    gen = doc.get("generated_at") or iso(now_msk())
    try:
        dt = datetime.fromisoformat(gen)
        gen_h = dt.strftime("%d.%m.%Y %H:%M:%S МСК")
    except Exception:
        gen_h = gen
    on = pick_run(runs, "Connected")
    off = pick_run(runs, "Disconnected")
    lines = []
    lines.append("# Аудит стриминга YouTube на реальной сети пользователя "
                 "(ПК, Windows)")
    lines.append("")
    lines.append(f"**Сформировано:** {gen_h}  ")
    lines.append(f"**Скрипт:** `scripts/dev/streaming-audit.py`  ")
    if runs:
        r0 = runs[0]
        env = r0.get("env") or {}
        lines.append(f"**Машина:** {env.get('hostname')} · "
                     f"Python {env.get('python')} · {env.get('platform')}")
    lines.append("")
    lines.append("> Отчёт сгенерирован автоматически по результатам реальных "
                 "сетевых замеров. Все цифры — фактические, без «должно "
                 "работать». Один и тот же скрипт можно перезапустить.")
    lines.append("")

    # ---- verdict -----------------------------------------------------
    lines.append("## 1. Короткий вердикт")
    lines.append("")
    v = []
    if off is not None:
        v4, v6, total = run_ep_stats(off)
        v.append(f"Без WARP (реальная сеть РФ): инфраструктура YouTube "
                 f"IPv4 {v4}/{total}, IPv6 {v6}/{total}; "
                 f"InnerTube {'отвечает' if run_api_ok(off) else 'не отвечает'}; "
                 f"медиа 64 КиБ {'качается' if run_media_ok(off) else 'НЕ качается'}.")
        a, b = run_ytdlp_ok_count(off)
        if b:
            v.append(f"yt-dlp без WARP: {a}/{b} вариантов дали URL.")
        if off.get("relays"):
            a, b = run_relay_ok_count(off)
            v.append(f"Публичные релеи без WARP: {a}/{b} рабочих.")
    if on is not None:
        v4, v6, total = run_ep_stats(on)
        v.append(f"С WARP: инфраструктура IPv4 {v4}/{total}, IPv6 {v6}/{total}; "
                 f"InnerTube {'отвечает' if run_api_ok(on) else 'не отвечает'}; "
                 f"медиа 64 КиБ {'качается' if run_media_ok(on) else 'НЕ качается'}.")
    for x in v:
        lines.append(f"- {x}")
    lines.append("")

    # ---- environment -------------------------------------------------
    lines.append("## 2. Условия замеров")
    lines.append("")
    if on is not None:
        eg = on.get("egress") or {}
        lines.append(f"- WARP ON: IPv4 `{(eg.get('v4') or {}).get('ip')}`, "
                     f"IPv6 `{(eg.get('v6') or {}).get('ip')}`.")
    if off is not None:
        eg = off.get("egress") or {}
        lines.append(f"- WARP OFF: IPv4 `{(eg.get('v4') or {}).get('ip')}`, "
                     f"IPv6 `{(eg.get('v6') or {}).get('ip') or 'нет'}`.")
    if off is not None:
        dns = (off.get("egress") or {}).get("dns_servers") or []
        lines.append(f"- DNS при WARP OFF: {', '.join(dns) if dns else '—'}.")
    y = next((r.get("ytdlp") for r in runs if r.get("ytdlp")), None)
    if y:
        lines.append(f"- yt-dlp: {y.get('version')} (zipapp), JS-рантаймы: "
                     f"{', '.join((y.get('js_runtimes') or {}).keys()) or 'нет'}.")
    lines.append("- Каждый слой измерялся отдельно: DNS → TCP:443 → TLS с "
                 "правильным SNI → HTTP-код → реальные байты через `Range: "
                 "bytes=0-65535`. Это позволяет отличить блокировку DNS/SNI от "
                 "блокировки самого медиа-потока.")
    lines.append("")

    # ---- per phase ---------------------------------------------------
    lines.append("## 3. Результаты по фазам")
    lines.append("")
    for i, run in enumerate(runs, 1):
        lines.append(phase_block(run, i))
        lines.append("")

    # ---- layer diff --------------------------------------------------
    if off is not None:
        lines.append("## 4. Где именно ломается доступ (послойная дифф-диагностика)")
        lines.append("")
        gvl = off.get("googlevideo") or []
        if gvl:
            for g in gvl[:2]:
                layers = g.get("layers") or {}
                dns = layers.get("dns_v4") or {}
                tcp = layers.get("tcp_v4") or {}
                tls = layers.get("tls_v4") or {}
                plain = layers.get("http_plain_v4") or {}
                m4 = layers.get("media_v4") or {}
                m6 = layers.get("media_v6") or {}
                lines.append(f"Хост `{g.get('host')}`:")
                lines.append("")
                lines.append(f"- DNS: {'ок' if dns.get('ok') else 'нет'} "
                             f"({dns.get('ms')} мс, "
                             f"{', '.join(dns.get('ips') or []) or '—'})")
                lines.append(f"- TCP:443: {'ок' if tcp.get('ok') else 'НЕТ'} "
                             f"({tcp.get('ms')} мс)")
                lines.append(f"- TLS/SNI: {'ок' if tls.get('ok') else 'НЕТ'} "
                             f"({tls.get('ms')} мс)")
                lines.append(f"- HTTP GET / без подписи: "
                             f"{'ок, HTTP ' + str(plain.get('status')) if plain.get('status') else 'НЕТ'}")
                lines.append(f"- Медиа Range IPv4: HTTP {m4.get('status')}, "
                             f"{fmt_kb(m4.get('bytes') or 0)} байт, "
                             f"{m4.get('kbps')} КиБ/с")
                lines.append(f"- Медиа Range IPv6: "
                             f"{'HTTP ' + str(m6.get('status')) if m6.get('status') else 'НЕТ'} "
                             f"({short(m6.get('error') or 'нет IPv6-маршрута', 60)})")
                lines.append("")
        else:
            lines.append("_В фазе без WARP не удалось получить URL googlevideo "
                         "для послойного анализа._")
            lines.append("")

    # ---- conclusions -------------------------------------------------
    lines.append("## 5. Что это значит для автопилота Sonora")
    lines.append("")
    for i, r in enumerate(build_recommendations(doc), 1):
        lines.append(f"{i}. {r}")
    lines.append("")
    lines.append("### Ожидаемые тайминги (по замерам)")
    lines.append("")
    if off is not None:
        ep = off.get("endpoints") or []
        tcp_t = [d.get("tcp_ms") for e in ep for d in [(e.get("v4") or {})]
                 if d.get("tcp_ms") is not None]
        tls_t = [d.get("tls_ms") for e in ep for d in [(e.get("v4") or {})]
                 if d.get("tls_ms") is not None]
        if tcp_t:
            lines.append(f"- TCP:443 до хостов YouTube: "
                         f"{min(tcp_t)}–{max(tcp_t)} мс "
                         f"(медиана ~{sorted(tcp_t)[len(tcp_t) // 2]} мс).")
        if tls_t:
            lines.append(f"- TLS-хендшейк: {min(tls_t)}–{max(tls_t)} мс.")
        apis = [e.get("ms") for e in (off.get("innertube") or [])
                if e.get("ms") is not None]
        if apis:
            lines.append(f"- InnerTube player: {min(apis)}–{max(apis)} мс.")
        med = [m.get("first_byte_ms") for m in (off.get("media") or [])
               if m.get("first_byte_ms") is not None]
        if med:
            lines.append(f"- Первый байт медиа: {min(med)}–{max(med)} мс.")
    lines.append("")

    # ---- honesty -----------------------------------------------------
    lines.append("## 6. Ограничения и честные оговорки")
    lines.append("")
    lines.append("- Замеры сделаны с одного ПК, одного провайдера и в один "
                 "момент времени. У российских провайдеров блокировки "
                 "различаются; результат нельзя считать универсальным.")
    lines.append("- WARP переключался через `warp-cli` и всегда возвращался в "
                 "исходное состояние; при выключенном WARP окно без туннеля "
                 "было коротким.")
    lines.append("- InnerTube `player` и googlevideo требуют корректных "
                 "подписей/`n`-параметра. Ответ API без ошибки ещё не значит, "
                 "что медиа скачается — поэтому везде проверялись именно байты.")
    lines.append("- Публичные релеи (Invidious/Piped) нестабильны и часто "
                 "меняются; список хостов — снимок на момент аудита.")
    lines.append("- Ничего, кроме WARP on/off, в системе не менялось.")
    lines.append("")

    # ---- reproduce ---------------------------------------------------
    lines.append("## 7. Как повторить")
    lines.append("")
    lines.append("```powershell")
    lines.append("# Полный аудит по фазам (WARP переключается и возвращается сам)")
    lines.append("powershell -ExecutionPolicy Bypass -File "
                 "scripts/dev/run-streaming-audit.ps1")
    lines.append("")
    lines.append("# Точечно")
    lines.append("python scripts/dev/streaming-audit.py --phase A --warp on --quick")
    lines.append("python scripts/dev/streaming-audit.py --phase B --warp off --with-ytdlp")
    lines.append("python scripts/dev/streaming-audit.py --report-only")
    lines.append("```")
    lines.append("")
    lines.append(f"JSON: `docs/ci/pc-streaming-audit.json` · "
                 f"MD: `docs/PC-STREAMING-AUDIT.md`")
    lines.append("")
    return "\n".join(lines)


# --------------------------------------------------------------------------
# console summary
# --------------------------------------------------------------------------
def print_summary(run: dict) -> None:
    print("=" * 78)
    print(f"Фаза: {run.get('label')} | WARP: {run.get('warp_state')} "
          f"| egress: {egress_str(run)}")
    v4, v6, total = run_ep_stats(run)
    print(f"  инфраструктура: IPv4 {v4}/{total}, IPv6 {v6}/{total}")
    print(f"  InnerTube: {'OK' if run_api_ok(run) else 'FAIL'} | "
          f"медиа Range: {'OK' if run_media_ok(run) else 'FAIL'}")
    for m in (run.get("media") or [])[:4]:
        print(f"    media {m.get('client')}/{m.get('video')}: "
              f"HTTP {m.get('status')} {m.get('bytes')} b "
              f"{m.get('kbps')} KiB/s err={short(m.get('error') or '', 60)}")
    if run.get("ytdlp"):
        for r in (run.get("ytdlp") or {}).get("runs") or []:
            p = r.get("probe") or {}
            print(f"    yt-dlp {r.get('variant')}/{r.get('video')}: "
                  f"{'OK' if r.get('ok') else 'FAIL'} HTTP {p.get('status')} "
                  f"{p.get('kbps')} KiB/s {short(r.get('error') or '', 50)}")
    if run.get("relays"):
        a, b = run_relay_ok_count(run)
        print(f"  релеи: {a}/{b}")
    if run.get("notes"):
        for n in run["notes"]:
            print(f"  note: {n}")
    rs = run.get("restore") or {}
    print(f"  restore: before={rs.get('before')} after={rs.get('after')} "
          f"internet={rs.get('internet')} ok={rs.get('ok')}")
    print("=" * 78)


# --------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------
def parse_args(argv=None):
    ap = argparse.ArgumentParser(
        description="Аудит доступности YouTube-аудио на реальной сети "
                    "(WARP on/off, IPv4/IPv6, InnerTube, yt-dlp, релеи).")
    ap.add_argument("--phase", default=None,
                    help="метка фазы, например A / B / C / D")
    ap.add_argument("--warp", choices=["on", "off", "leave"], default="leave",
                    help="желаемое состояние WARP на время замера "
                         "(исходное восстанавливается автоматически)")
    ap.add_argument("--with-ytdlp", action="store_true",
                    help="проверить yt-dlp zipapp и варианты player_client")
    ap.add_argument("--quick", action="store_true",
                    help="без релеев (быстрее); yt-dlp по-прежнему по флагу")
    ap.add_argument("--json-out", default=str(DEFAULT_JSON))
    ap.add_argument("--md-out", default=str(DEFAULT_MD))
    ap.add_argument("--ytdlp", default=None, help="путь к yt-dlp .pyz")
    ap.add_argument("--videos", default=None,
                    help="id через запятую, по умолчанию dQw4w9WgXcQ,kJQP7kiw5Fk")
    ap.add_argument("--timeout", type=float, default=5.0,
                    help="базовый таймаут сетевых проб, с")
    ap.add_argument("--report-only", action="store_true",
                    help="только пересобрать MD из JSON, без сети")
    args = ap.parse_args(argv)
    if args.videos:
        ids = [v.strip() for v in args.videos.split(",") if v.strip()]
        args.videos = [(v, "custom") for v in ids]
    else:
        args.videos = DEFAULT_VIDEOS
    return args


def main(argv=None) -> int:
    args = parse_args(argv)
    json_path = Path(args.json_out)
    md_path = Path(args.md_out)

    if args.report_only:
        doc = load_doc(json_path)
        if not doc.get("runs"):
            print(f"нет данных в {json_path}")
            return 2
        md_path.parent.mkdir(parents=True, exist_ok=True)
        md_path.write_text(build_report(doc), encoding="utf-8")
        print(f"отчёт пересобран: {md_path}")
        return 0

    print(f"[audit] старт {iso(now_msk())}, warp={args.warp}, "
          f"quick={args.quick}, ytdlp={args.with_ytdlp}")
    # Remember googlevideo edge hosts seen in earlier runs: they let us test
    # DNS/TCP/TLS/SNI layers even when no signed URL can be obtained now.
    prev = load_doc(json_path)
    fallback, seen = [], set()
    for r in prev.get("runs", []):
        for y in ((r.get("ytdlp") or {}).get("runs") or []):
            h = y.get("url_host")
            if h and "googlevideo" in h and h not in seen:
                seen.add(h)
                fallback.append(h)
        for m in (r.get("media") or []):
            h = m.get("url_host")
            if h and "googlevideo" in h and h not in seen:
                seen.add(h)
                fallback.append(h)
    args.fallback_gv_hosts = fallback[:3]
    run = run_audit(args)
    doc = save_run(load_doc(json_path), run, json_path)
    md_path.parent.mkdir(parents=True, exist_ok=True)
    md_path.write_text(build_report(doc), encoding="utf-8")
    print_summary(run)
    print(f"[audit] JSON: {json_path}")
    print(f"[audit] MD:   {md_path}")
    restore = run.get("restore") or {}
    if not restore.get("ok", True):
        print("!!! WARP/INTERNET RESTORE FAILED — вмешательство нужно вручную")
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main())

