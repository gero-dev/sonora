"""Генерирует тестовые MP3 с обложками для проверки плеера на эмуляторе.

Запуск: python scripts/dev/make_test_media.py <out_dir>
Требует ffmpeg в PATH и Pillow.
"""
import os
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFont

TRACKS = [
    ("night-drive", "Ночной драйв", "Sonora Test", "Neon Nights", 220, (88, 40, 160), (255, 95, 162)),
    ("lofi-beats", "Лоу-фай утро", "Sonora Test", "Coffee & Beats", 174, (20, 90, 120), (0, 212, 200)),
    ("sunrise", "Рассвет", "Sonora Test", "Neon Nights", 330, (200, 90, 20), (255, 201, 61)),
]


def make_cover(path: str, title: str, c1, c2) -> None:
    size = 600
    img = Image.new("RGB", (size, size))
    draw = ImageDraw.Draw(img)
    for y in range(size):
        t = y / size
        color = tuple(int(c1[i] + (c2[i] - c1[i]) * t) for i in range(3))
        draw.line([(0, y), (size, y)], fill=color)

    font_path = "C:/Windows/Fonts/arialbd.ttf"
    font = ImageFont.truetype(font_path, 56) if os.path.exists(font_path) else ImageFont.load_default()
    text = title
    bbox = draw.textbbox((0, 0), text, font=font)
    x = (size - (bbox[2] - bbox[0])) // 2
    draw.text((x, size // 2 - 40), text, font=font, fill=(255, 255, 255))
    img.save(path, "PNG")


def make_mp3(out_dir: str, slug: str, title: str, artist: str, album: str, freq: int, c1, c2) -> str:
    cover = os.path.join(out_dir, f"{slug}.png")
    mp3 = os.path.join(out_dir, f"{slug}.mp3")
    make_cover(cover, title, c1, c2)
    cmd = [
        "ffmpeg", "-y", "-loglevel", "error",
        "-f", "lavfi", "-i", f"sine=frequency={freq}:duration=45",
        "-i", cover,
        "-map", "0:a", "-map", "1:v",
        "-c:a", "libmp3lame", "-b:a", "192k",
        "-c:v", "mjpeg", "-id3v2_version", "3",
        "-metadata", f"title={title}",
        "-metadata", f"artist={artist}",
        "-metadata", f"album={album}",
        "-metadata:s:v", "title=Album cover",
        "-metadata:s:v", "comment=Cover (front)",
        mp3,
    ]
    subprocess.run(cmd, check=True)
    os.remove(cover)
    return mp3


def main() -> None:
    out_dir = sys.argv[1] if len(sys.argv) > 1 else "testmedia"
    os.makedirs(out_dir, exist_ok=True)
    for slug, title, artist, album, freq, c1, c2 in TRACKS:
        path = make_mp3(out_dir, slug, title, artist, album, freq, c1, c2)
        print("created", path, os.path.getsize(path), "bytes")


if __name__ == "__main__":
    main()
