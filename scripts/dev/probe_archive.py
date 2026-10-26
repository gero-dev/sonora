import json, urllib.request, urllib.parse
def get(url):
    req = urllib.request.Request(url, headers={"User-Agent":"Sonora/0.2 test"})
    with urllib.request.urlopen(req, timeout=25) as r:
        return r.read().decode("utf-8","replace")
q = urllib.parse.quote("jazz AND mediatype:(audio) AND (collection:(netlabels) OR collection:(audio_music) OR collection:(opensource_audio))")
url = f"https://archive.org/advancedsearch.php?q={q}&fl%5B%5D=identifier&fl%5B%5D=title&rows=5&page=1&output=json"
docs = json.loads(get(url))["response"]["docs"]
print("docs:", len(docs))
for d in docs:
    ident = d["identifier"]
    md = json.loads(get(f"https://archive.org/metadata/{ident}"))
    files = md.get("files", [])
    fmts = {}
    for f in files:
        fmts[f.get("format")] = fmts.get(f.get("format"),0)+1
    print(ident, "|", d.get("title","")[:40], "| restricted:", md.get("metadata",{}).get("access-restricted-item"), "| formats:", list(fmts.items())[:6])
