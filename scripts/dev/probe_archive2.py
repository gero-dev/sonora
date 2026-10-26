import json, urllib.request, urllib.parse, collections
def get(url):
    req = urllib.request.Request(url, headers={"User-Agent":"Sonora/0.2 test"})
    with urllib.request.urlopen(req, timeout=25) as r:
        return r.read().decode("utf-8","replace")
for label, coll in [("netlabels","collection:(netlabels)"), ("audio_music","collection:(audio_music)")]:
    q = urllib.parse.quote(f"jazz AND mediatype:(audio) AND {coll}")
    url = f"https://archive.org/advancedsearch.php?q={q}&fl%5B%5D=identifier&rows=8&page=1&output=json"
    docs = json.loads(get(url))["response"]["docs"]
    print("==", label, "docs:", len(docs))
    for d in docs:
        ident = d["identifier"]
        try:
            md = json.loads(get(f"https://archive.org/metadata/{ident}"))
        except Exception as e:
            print(ident, "ERR", e); continue
        fmts = collections.Counter(f.get("format") for f in md.get("files",[]))
        aud = [f for f in md.get("files",[]) if str(f.get("format","")).lower() in ("vbr mp3","mp3","ogg vorbis","flac","mpeg-4 audio","32kbps mp3","64kbps mp3","128kbps mp3")]
        print(ident[:45], "| audio files:", len(aud), "|", dict(list(fmts.items())[:5]))
