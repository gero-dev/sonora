#!/usr/bin/env python3
"""Compact summary of probe results for the research doc."""
import json

l = json.load(open("docs/ci/relay-probe-results.json", encoding="utf-8"))
print("LOCAL (WARP) PROBE at:", l["tested_at_msk"], "elapsed", l["elapsed_s"], "s")
for r in l["results"]:
    api = r.get("api") or {}
    lp = (r.get("local_proxy") or {}) or (r.get("direct_url") or {})
    print(f"  {r.get('verdict'):18} {r.get('kind'):9} api={api.get('status')} "
          f"audio={lp.get('status')} bytes={lp.get('bytes')} ct={str(lp.get('ctype'))[:30]} "
          f"{r.get('base')}")

g = json.load(open("docs/ci/globalping-results.json", encoding="utf-8"))
print("\nGLOBALPING (RU probes):", len(g["runs"]), "runs")
for r in g["runs"]:
    rows = r.get("rows") or []
    parts = []
    for x in rows:
        h = {k.lower(): v for k, v in (x.get("headers") or {}).items()}
        code = x["code"] if x["code"] else "ERR"
        ct = str(h.get("content-type", "-"))[:26]
        parts.append(f"AS{x['asn']} {code} {x['total_ms']}ms ct={ct} "
                     f"cr={h.get('content-range', '-')} cl={h.get('content-length', '-')}")
    print(f"[{r.get('kind')}] {r['label']} :: " + " | ".join(parts))
