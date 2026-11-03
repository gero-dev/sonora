# Sonora 3.5.1 — import fixture corpus and expected results

All paths in this file are relative to the workspace root
(`C:\Users\Егор Груздев\Downloads\Sonora-3.5.1-source`).

## Why there are TWO import buttons, not one

The fixtures are consumed by **two different code paths with different parsers**.
This is the single most important thing to know before reading the tables.

| Button in Settings | Strings | ViewModel entry | Parser |
|---|---|---|---|
| **«Импортировать плейлист из файла»** | `PlaylistFileImporter.kt` | `MainViewModel.importPlaylistFile()` | `PlaylistFileParser` |
| **«Импорт Takeout…»** | `TakeoutImporter.kt` | `MainViewModel.importTakeout()` | `TakeoutImporter` |

Both buttons sit in the section **«Импорт из других платформ»** on the Настройки tab.

* 5 of the 6 fixtures are for the **playlist-file** button.
* `ytmusic-takeout.csv` is for the **Takeout** button, because Google Takeout
  exports are handled by `TakeoutImporter` and that parser uses a *different set
  of column aliases* (see the Takeout section below).

The library-matching rule is shared by both paths and is
`ImportTrackMatcher.score()` (`app/src/main/java/app/sonora/data/import/ImportTrackMatcher.kt`):

```
title   exact = 3   partial = 1
artist  exact = 2   partial = 1
duration within 10_000 ms = +1
accept when total >= MIN_SCORE (3)
```

Consequence used throughout the tables: **an exact title alone (3) is enough**,
so a track still matches when the artist is missing or spelled differently.

## Fixture inventory

| File | Bytes | Entries | Parser path | Deliberate hard cases |
|---|---:|---:|---|---|
| `spotify-liked.csv` | see `push-fixtures` output | 10 | playlist-file, `parseTable` | none (clean control file) |
| `spotify-playlist.json` | " | 12 | playlist-file, `parseJson` | `(feat. …) [Official Video]`, `- Topic` artist, 369 s track (4+ min) |
| `apple-music-library.csv` | " | 10 | playlist-file, `parseTable` | `Time` column = `5:54` clock format, lowercase `3:14` |
| `ytmusic-takeout.csv` | " | 14 | **Takeout**, `TakeoutImporter` | `Artist Name(s)` header, Cyrillic, accented Latin, 318 s track |
| `plain-titles.txt` | " | 12 | playlist-file, `parseText` | en dash `–`, em dash `—`, `(feat. …) [Official Video]`, `- Remastered 2011` |
| `plain-titles.m3u` | " | 11 | playlist-file, `parseM3u` | `#EXTINF` durations, en dash in EXTINF title, `(Official Video)` |

String evidence is quoted as `file:line` in the tables below and was read from
the 3.5.1 source tree at the time this corpus was written.

---

## 1. `spotify-liked.csv` — 10 entries, clean control

Shape: a Spotify "Liked Songs" table export. Header
`Track Name,Artist Name,Album Name,Duration (ms),Spotify URL`.
`looksLikeTable()` passes on the comma + `track name`/`artist name` headers
(`PlaylistFileImporter.kt:276-283`); the delimiter resolves to `,` and the
header resolves to a real header row (`:294-296`).
`Duration (ms)` → `duration ms` is in `DURATION_HEADERS` (`:454`) and
`parseClockMs` treats a plain number in `1..20_000` as **seconds, else ms**
(`:418-422`). `354320` is outside that range, so it stays ms. Correct.

| # | Entry (title / artist) | Expected result |
|---|---|---|
| 1 | Bohemian Rhapsody / Queen | MATCH (title 3 + artist 2 + duration 1 = 6) |
| 2 | Billie Jean / Michael Jackson | MATCH (6) |
| 3 | Smells Like Teen Spirit / Nirvana | MATCH (6) |
| 4 | Hotel California / Eagles | MATCH (6) |
| 5 | Africa / TOTO | MATCH (6) |
| 6 | Take On Me / a-ha | MATCH (6) |
| 7 | Sweet Child O' Mine / Guns N' Roses | MATCH (6) |
| 8 | Rolling in the Deep / Adele | MATCH (6) |
| 9 | Blinding Lights / The Weeknd | MATCH (6) |
| 10 | Shape of You / Ed Sheeran | MATCH (6) |

Expected: **10 / 10 matched, 0 not found.**
Expected playlist name: **`Импорт: spotify-liked`**
(`MainViewModel.kt:3280-3284`, `"Импорт: $baseName".take(80)`).

## 2. `spotify-playlist.json` — 12 entries

Shape: a real Spotify Web API playlist/tracks object,
`{"items":[{"track":{…}}]}`. The wrapper is unwrapped explicitly at
`PlaylistFileImporter.kt:167-171` (`element.obj("track")` is visited when the
outer object has no `name`). `type:"track"` is required to survive the
container filter at `:192-195`. The `album` sub-object supplies `album`, and
`external_urls.spotify` supplies `url` (`:213-218`).

| # | Entry as written in the file | Normalised by `ImportTrackMatcher.normalize` | Expected result |
|---|---|---|---|
| 1 | Bohemian Rhapsody / Queen | `bohemian rhapsody` / `queen` | MATCH (6) |
| 2 | Billie Jean / Michael Jackson | `billie jean` | MATCH (6) |
| 3 | Smells Like Teen Spirit / Nirvana | `smells like teen spirit` | MATCH (6) |
| 4 | Hotel California / Eagles | `hotel california` | MATCH (6) |
| 5 | Africa / TOTO | `africa` | MATCH (6) |
| 6 | Take On Me / a-ha | `take on me` | MATCH (6) |
| 7 | Sweet Child O' Mine / Guns N' Roses | `sweet child o mine` | MATCH (6) |
| 8 | Rolling in the Deep / Adele | `rolling in the deep` | MATCH (6) |
| 9 | Blinding Lights / The Weeknd | `blinding lights` | MATCH (6) |
| 10 | Shape of You / Ed Sheeran | `shape of you` | MATCH (6) |
| 11 | **Get Lucky (feat. Pharrell Williams) [Official Video]** / Daft Punk | `get lucky` | MATCH — `DECORATION_PARENS` strips `[Official Video]` (`:72-77`), `FEAT_TAIL` strips `(feat. …` (`:88`), `ANY_PARENS` clears the rest (`:98`) |
| 12 | Heart-Shaped Box / **Nirvana - Topic** | `heart shaped box` / `nirvana` | MATCH — `TOPIC_SUFFIX` (`:85`) + `topicBase()` gives artist `ARTIST_EXACT` (`:137`) |

Expected: **12 / 12 matched.** Entry 11 carries a **369 000 ms** duration, so it
is the 4+ minute track that qualifies for the duration bonus; entry 4
(`391376`) and entry 7 (`356066`) also exceed 4 minutes.
Expected playlist name: **`Импорт: spotify-playlist`**.

## 3. `apple-music-library.csv` — 10 entries

Apple Music CSV shape: `Song Name,Artist Name,Album Name,Time,Duration In Milliseconds`.
`Song Name` → `song name` ∈ `TITLE_HEADERS` (`:445-448`), so the header row is
detected. Both `Time` and `Duration In Milliseconds` normalise into
`DURATION_HEADERS` (`:454`); `column()` takes the **first** matching header
(`:300`), i.e. `Time`, and `parseClockMs` parses `5:54` → 354 000 ms (`:423-427`).
Note the file mixes a `3:14` clock value with a `194088` ms value in the
`Duration In Milliseconds` column; only the `Time` column is read.

| # | Entry | `Time` → parsed | Expected result |
|---|---|---|---|
| 1 | Bohemian Rhapsody / Queen | 5:54 → 354000 | MATCH (6) |
| 2 | Billie Jean / Michael Jackson | 4:53 → 293000 | MATCH (6) |
| 3 | Smells Like Teen Spirit / Nirvana | 5:01 → 301000 | MATCH (6) |
| 4 | Hotel California / Eagles | 6:31 → 391000 | MATCH (6) |
| 5 | Take On Me / a-ha | 3:45 → 225000 | MATCH (6) |
| 6 | Sweet Child O' Mine / Guns N' Roses | 5:56 → 356000 | MATCH (6) |
| 7 | Rolling in the Deep / Adele | 3:48 → 228000 | MATCH (6) |
| 8 | Blinding Lights / The Weeknd | 3:20 → 200000 | MATCH (6) |
| 9 | Shape of You / Ed Sheeran | 3:53 → 233000 | MATCH (6) |
| 10 | Bad Guy / Billie Eilish | 3:14 → 194000 | MATCH (title+artist = 5, no duration bonus only if the catalogue time differs >10 s) |

Expected: **10 / 10 matched.**
Expected playlist name: **`Импорт: apple-music-library`**.

## 4. `ytmusic-takeout.csv` — 14 entries, **Takeout button**

This file is for **«Импорт Takeout…»**, not the playlist-file button.
`TakeoutImporter.normalizeHeader` is `lowercase().filter { it.isLetterOrDigit() }`
(`TakeoutImporter.kt:307-311`), so the header `Artist Name(s)` becomes
`artistnames`. That string is **not** in `ARTIST_ALIASES`
(`:502-505`, which contains `artistname` and `artistname1/2` but not
`artistnames`).

> **PREDICTED BUG (`ARTIST_NAMES_HEADER_UNMATCHED`) — to be confirmed in task-6.**
> `columnsFromHeader` (`:160-189`) still returns a `ColumnMap` because
> `Video ID`, `Song Title`, `Album Name`, `Duration (seconds)` and
> `Playlist Title` all resolve, and `known` only needs one hit (`:179-181`).
> But `artist = -1`, so `record.cell(-1)` is null and every row falls back to
> `DEFAULT_ARTIST = "YouTube"` (`:200-202`, `:475`).
> Prediction: the Takeout import **succeeds and creates the playlist**, but every
> track is attributed to the artist `YouTube` and **no row is reported as
> "not found"**, because `videoId` is present for every row and
> `processRecord` builds a playable track from it unconditionally (`:213-223`).
> The artist is therefore **not** taken from the `Artist Name(s)` column at all.
> Confirm or refute this in task-6 from the on-device result; this file is
> designed to expose it.
>
> Checked and **not** a defect: all 14 `Video ID` values in this file are exactly
> 11 characters, so `VIDEO_ID_REGEX.matches(it)` (`:205`) accepts every one of
> them. Verified with a script, not by eye.

| # | Song Title | Video ID (all 11 chars) | Expected result (predicted) |
|---|---|---|---|
| 1 | Bohemian Rhapsody | `4u7EnebtmKW` | track created, artist `YouTube`, playable |
| 2 | Billie Jean | `5ChkMS8Otdz` | as #1 |
| 3 | Smells Like Teen Spirit | `4CeeEOM32jQ` | as #1 |
| 4 | Hotel California | `40riOy7x9W7` | as #1 |
| 5 | Africa | `2374M0fQpWi` | as #1 |
| 6 | Take On Me | `2WfaOiMkCvy` | as #1 |
| 7 | Sweet Child O' Mine | `7o2CTH4ctst` | as #1 |
| 8 | Rolling in the Deep | `1c8gk2PeTE0` | as #1 |
| 9 | Blinding Lights | `0VjIjW4GlUZ` | as #1 |
| 10 | Shape of You | `7qiZfU4dY1l` | as #1 |
| 11 | Get Lucky (feat. Pharrell Williams) | `2Foc5Q5nqNi` | as #1 |
| 12 | Heart-Shaped Box | `5GJWxDKyk3A` | as #1 |
| 13 | **Миллион алых роз** / Алла Пугачёва | `9bZkp7q19f0` | as #1 — Cyrillic must survive UTF-8 round-trip |
| 14 | **SARÀ PERCHÉ TI AMO** / Ricchi e Poveri | `1w7OgIMMRc4` | as #1 — accented Latin; 318 s is the 4+ min case |

Expected: **14 track records created** in the Takeout result, every one carrying
the wrong artist `YouTube` because of the header mismatch described above.

## 5. `plain-titles.txt` — 12 entries

`parseText` (`PlaylistFileImporter.kt:384-399`) skips lines starting with `#`
or `//`, then splits on `" – "`, `" — "` or `" - "`
(`splitArtistTitle`, `:401-413`).

| # | Line as written | Parsed artist / title | Expected result |
|---|---|---|---|
| 1-10 | `Queen - Bohemian Rhapsody` … `Ed Sheeran - Shape of You` | correct split | MATCH (6) |
| 11 | `Daft Punk – Get Lucky (feat. Pharrell Williams) [Official Video]` | `Daft Punk` / `Get Lucky (feat. Pharrell Williams) [Official Video]` | MATCH — hard case: **en dash** separator + feat. tail + bracket decoration all normalise away |
| 12 | `Billie Eilish — Bad Guy - Remastered 2011` | `Billie Eilish` / `Bad Guy - Remastered 2011` | MATCH — hard case: **em dash** separator, then `DASH_VERSION` strips `- Remastered 2011` (`:91-95`) |

Comment lines (`#` at 1-2, `//` at line 11 of the file) must produce **no**
entries, so the file yields exactly **12 entries**.

Expected: **12 / 12 matched.**
Expected playlist name: **`Импорт: plain-titles`**.

## 6. `plain-titles.m3u` — 11 entries

`parseM3u` (`:354-380`) reads the title from the `#EXTINF:` line, not from the
path line; a path line with no pending `#EXTINF` is skipped (`:368`). Path lines
here are plain file names, not URLs, so `videoId`/`url` stay null and matching
goes through the library/InnerTube search.

| # | `#EXTINF` duration | Parsed title | Expected result |
|---|---:|---|---|
| 1 | 354 | Queen - Bohemian Rhapsody | MATCH (title 3 + artist 2 + duration 1) |
| 2 | 293 | Michael Jackson - Billie Jean | MATCH (6) |
| 3 | 301 | Nirvana - Smells Like Teen Spirit | MATCH (6) |
| 4 | 391 | Eagles - Hotel California | MATCH (6) |
| 5 | 295 | TOTO - Africa | MATCH (6) |
| 6 | 225 | a-ha - Take On Me | MATCH (6) |
| 7 | 356 | Guns N' Roses - Sweet Child O' Mine | MATCH (6) |
| 8 | 228 | Adele - Rolling in the Deep | MATCH (6) |
| 9 | 200 | The Weeknd - Blinding Lights | MATCH (6) |
| 10 | 233 | Ed Sheeran - Shape of You | MATCH (6) |
| 11 | 248 | `Nirvana – Smells Like Teen Spirit (Official Video)` | MATCH — en dash in `#EXTINF` + `(Official Video)` decoration |

Expected: **11 / 11 matched.**
Expected playlist name: **`Импорт: plain-titles`** — note this **collides with
the `.txt` fixture above**, because the name is derived from the file name
without its extension (`MainViewModel.kt:3280`). Running both will create two
playlists both named `Импорт: plain-titles`; that is expected behaviour, not a
defect, but it must not be mistaken for duplicate downloads in the task-6
idempotence pass.

---

## Totals

| Fixture | Entries | Expected matched | Expected not found |
|---|---:|---:|---:|
| `spotify-liked.csv` | 10 | 10 | 0 |
| `spotify-playlist.json` | 12 | 12 | 0 |
| `apple-music-library.csv` | 10 | 10 | 0 |
| `ytmusic-takeout.csv` | 14 | (Takeout path, see prediction) | — |
| `plain-titles.txt` | 12 | 12 | 0 |
| `plain-titles.m3u` | 11 | 11 | 0 |
| **playlist-file total** | **55** | **55** | **0** |

## Non-ASCII coverage

| Fixture | Non-ASCII present | Purpose |
|---|---|---|
| `ytmusic-takeout.csv` | `Миллион алых роз`, `Алла Пугачёва`, `Как тревожен этот путь`, `Русские хиты`, `SARÀ PERCHÉ TI AMO` | Cyrillic + accented Latin through the Takeout CSV reader |
| `plain-titles.txt` | `–` (U+2013), `—` (U+2014) | typographic dashes through `splitArtistTitle` |
| `spotify-playlist.json` | `—` is absent; `–` absent | control for JSON path |

## 4+ minute tracks (duration bonus, tolerance ±10 s)

| Fixture | Track | Duration |
|---|---|---:|
| `spotify-liked.csv` | Hotel California | 391376 ms |
| `spotify-liked.csv` | Sweet Child O' Mine | 356066 ms |
| `spotify-liked.csv` | Bohemian Rhapsody | 354320 ms |
| `spotify-playlist.json` | Get Lucky (feat. …) | 369000 ms |
| `apple-music-library.csv` | Hotel California | 391000 ms |
| `ytmusic-takeout.csv` | Миллион алых роз | 318 s |
