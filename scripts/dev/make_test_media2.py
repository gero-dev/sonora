import subprocess, os
out = "testmedia2"
os.makedirs(out, exist_ok=True)
tracks = [
    ("yesterday", "Yesterday", "The Beatles", "Help!", 392, 126),
    ("takefive", "Take Five", "The Dave Brubeck Quartet", "Time Out", 324, 324),
]
for slug, title, artist, album, freq, duration in tracks:
    mp3 = os.path.join(out, slug + ".mp3")
    subprocess.run(["ffmpeg","-y","-loglevel","error","-f","lavfi","-i",f"sine=frequency={freq}:duration={duration}",
                    "-c:a","libmp3lame","-b:a","128k","-id3v2_version","3",
                    "-metadata",f"title={title}","-metadata",f"artist={artist}","-metadata",f"album={album}", mp3], check=True)
    print("created", mp3, os.path.getsize(mp3))
