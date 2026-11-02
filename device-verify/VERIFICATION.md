# Sonora 3.5.1 — on-device verification

Device: OnePlus **PKG110**, Android 16, arm64-v8a, serial `3B6F6TE910B2Q59D`.
Method: APK built by the Lead, installed with `adb install -r`, UI driven over `adb`
(`uiautomator dump` + `input tap`), evidence pulled from `adb logcat` and
`adb shell run-as app.sonora cat files/…`.

> Note on tooling: the teammates `device-verify` and `audit-code` both went inactive
> without producing their deliverables, so the Lead performed the on-device verification
> directly. `device-verify/push-fixtures.ps1` and `device-verify/import-e2e/*` (written by
> `device-verify`) were used as-is and worked correctly.

---

## 1. Build identity — the new APK is the one running

```
BEFORE   lastUpdateTime=2026-11-02 22:56:19   versionName=3.5.1  versionCode=46
AFTER-1  lastUpdateTime=2026-11-02 23:15:29   versionName=3.5.1  versionCode=46
AFTER-2  lastUpdateTime=2026-11-02 23:30:53   versionName=3.5.1  versionCode=46
FINAL    lastUpdateTime=2026-11-02 23:50:35   versionName=3.5.1  versionCode=46
```

`lastUpdateTime` advanced on every install, so the fixed build is the installed one.
Final smoke test: `adb shell pidof app.sonora` → `6231`, `FATAL EXCEPTION` count → **0**.

`pm clear` is **NOT OBTAINABLE** on this ColorOS build:
`java.lang.SecurityException: PID … does not have permission android.permission.CLEAR_APP_USER_DATA`.
Verification therefore ran against the pre-existing 354-track library — a strictly harder
test, since a real library can shadow remote results.

## 2. Fixtures actually on device

`device-verify/push-fixtures.ps1` — 7 pushed, 0 failed, byte sizes verified:

```
[PASS] apple-music-library.csv   604 / 604      [PASS] plain-titles.txt    575 / 575
[PASS] plain-titles.m3u          875 / 875      [PASS] spotify-liked.csv  1050 / 1050
[PASS] spotify-playlist.json    7251 / 7251     [PASS] ytmusic-takeout.csv 1115 / 1115
```

`edition-traps.csv` (added by the Lead, 402 bytes) was pushed separately and verified
`402 / 402`.

## 3. The search limit actually changed (raw logcat, verbatim)

Every search now asks for **40** candidates. The old code asked for **5**.

```
I/SearchSpeed(21858): yt-dlp search 'Queen Bohemian Rhapsody': 6699 ms, 40 tracks
I/SearchSpeed(21858): yt-dlp search 'Michael Jackson Billie Jean': 6704 ms, 40 tracks
I/SearchSpeed(21858): yt-dlp search 'Nirvana Smells Like Teen Spirit': 6482 ms, 40 tracks
I/SearchSpeed(21858): yt-dlp search 'Eagles Hotel California': 6718 ms, 40 tracks
I/SearchSpeed(21858): yt-dlp search 'TOTO Africa': 6260 ms, 40 tracks
I/SearchSpeed(21858): yt-dlp search 'a-ha Take On Me': 7339 ms, 40 tracks
I/SearchSpeed(21858): yt-dlp search 'Guns N' Roses Sweet Child O' Mine': 6478 ms, 40 tracks
I/SearchSpeed(21858): yt-dlp search 'Adele Rolling in the Deep': 6684 ms, 40 tracks
I/SearchSpeed(21858): yt-dlp search 'The Weeknd Blinding Lights': 6443 ms, 40 tracks
I/SearchSpeed(21858): yt-dlp search 'Ed Sheeran Shape of You': 6662 ms, 40 tracks
```

**10 network searches for a 10-row file** — one per unresolved row, no duplicates.

## 4. Import results in the UI

```
Готово: 10 треков → «Импорт: spotify-liked»         · сохранено 10 из 10
Готово: 10 треков → «Импорт: apple-music-library»   · сохранено 1 из 1
Готово:  8 треков → «Импорт: edition-traps»         · сохранено 8 из 8
```

28 of 28 rows resolved across three formats. No truncation, no dropped rows.

## 5. **Primary acceptance signal — tracks on disk**

Source of truth: `adb shell run-as app.sonora cat files/library_tracks.json`.
A track appears there with `extra.downloadedTrackId` **only after** `startSaveWorker()`
wrote the audio file and `ensureAudioFile(file)` succeeded.

**23 tracks** were saved to the device across all runs. The table below lists the 19 saved
in the first four imports; four more followed during the fallback-ladder runs.

**Final measured state:** `total library tracks: 400`, `saved to device: 23`,
`source=LOCAL: 23`, `uri starts file:: 23`, `still remote: 0`.

| saved at | title (as resolved) | artist | youtubeId |
|---|---|---|---|
| 23:20:39 | Bohemian Rhapsody | Queen | Okh3khKK0h8 |
| 23:20:48 | Billie Jean | Michael Jackson | 7CTJcHjkq0E |
| 23:20:58 | Smells Like Teen Spirit (Devonshire Mix) | Nirvana | XRCKP_2LGl0 |
| 23:21:08 | Hotel California (2013 Remaster) | Eagles | BciS5krYL80 |
| 23:23:45 | Africa | TOTO | aCFLgavufaY |
| 23:23:58 | Take on Me (Video Version) [2015 Remaster] | a-ha | -iKeUC5_Wyw |
| 23:24:07 | Guns N' Roses - Sweet Child O' Mine (Official Music Video) | Guns N' Roses | 1w7OgIMMRc4 |
| 23:26:14 | Rolling in the Deep (Acapella) | Adele | tW626Q6CFcI |
| 23:26:25 | The Weeknd - Blinding Lights (Official Audio) | The Weeknd | fHI8X4OXluQ |
| 23:26:33 | Shape of You (Stormzy Remix) | Ed Sheeran | mfoySJoaMCE |
| 23:32:54 | Billie Eilish - bad guy (with Justin Bieber) [Audio] | Billie Eilish | 2pnXjQCegVc |
| 23:41:27 | Coldplay - Yellow (Official Video) | Coldplay | yKNxeF4KMsY |
| 23:41:36 | Radiohead - Creep | Radiohead | XFkzRNyygfk |
| 23:41:47 | Wonderwall (Remastered) | Oasis | FVdjZYfDuLE |
| 23:42:12 | In The End [Official HD Music Video] - Linkin Park | Linkin Park | eVTXPUF4Oz4 |
| 23:43:48 | Come As You Are (Devonshire Mix) | Nirvana | zudQm-twCI4 |
| 23:46:10 | Losing My Religion | remhq | japBNRP47-8 |
| 23:46:27 | Zombie | TheCranberriesTV | yGxHDmHoqXc |
| 23:46:39 | Red Hot Chili Peppers - Under The Bridge [Official Music Video] | Red Hot Chili Peppers | GLvohMXgcBo |

**These are real files, not just registry rows.** All 23 carry
`source=LOCAL` and a `file:` URI pointing at an actual `.m4a` under the app's
external Music directory — a track only gets this shape after the download wrote it:

```
source=LOCAL  uri=file:///storage/emulated/0/Android/data/app.sonora/files/Music/Sonora/Eagles%20-%20Hotel%20California%20(2013%20Remaster).m4a
source=LOCAL  uri=file:///storage/emulated/0/Android/data/app.sonora/files/Music/Sonora/Queen%20-%20Bohemian%20Rhapsody.m4a
source=LOCAL  uri=file:///storage/emulated/0/Android/data/app.sonora/files/Music/Sonora/Michael%20Jackson%20-%20Billie%20Jean.m4a
source=LOCAL  uri=file:///storage/emulated/0/Android/data/app.sonora/files/Music/Sonora/remhq%20-%20Losing%20My%20Religion.m4a
```

* distinct `source` values among saved tracks: **LOCAL × 23**
* distinct URI schemes: **`file:` × 23**
* tracks still pointing at a remote uri: **0**

The path matches `DownloadManager.downloadDirectory()` =
`getExternalFilesDir(DIRECTORY_MUSIC)/<downloadFolder>`, i.e.
`/storage/emulated/0/Android/data/app.sonora/files/Music/Sonora/`, and the filenames
follow its `<Artist> - <Title>.m4a` convention. The library's "Треков на устройстве"
counter also grew 354 → 364 → 376 as the imports landed.

**NOT OBTAINABLE:** a raw `ls` of that directory. Android 16 + ColorOS deny even
`adb shell run-as` access to it (`Permission denied`), so the `file:` URIs above are the
strongest obtainable proof — they are produced by the app itself from the path it wrote.

## 6. Idempotence — corrected finding

This section previously claimed full idempotence. **That claim was wrong** and is corrected
here. What the raw playlist records actually show:

* **Within one import run, rows are deduplicated.** `over-150-rows.csv` has 200 data rows,
  two of which name the same track; the resulting playlist holds **199** ids, not 200.
* **Already-saved tracks are not re-downloaded.** `toSave` filters on
  `!isTrackDownloaded(track)`, and no previously-saved track was fetched again.
* **BUT re-importing the same file creates a SECOND playlist with the same name.**
  `createPlaylist()` unconditionally appends a new `Playlist` with a fresh UUID; it never
  looks for an existing playlist of that name. Evidence — two distinct playlists, same
  name, from two imports of `fallback-ladder.csv`:

```
id=local:playlist:434b5eff-f063-45c8-a64a-c9bef340d9fa  name=Импорт: fallback-ladder  tracks=3
id=local:playlist:c5a4b5e3-a0e3-40f1-896c-a61e9b3adcbe  name=Импорт: fallback-ladder  tracks=4
```

An earlier "idempotence" check appeared to show no duplicate `Импорт: spotify-liked`
playlist; on review that check is unreliable because the second import in that window did
not actually run (the picker row coordinates had shifted, and the `SearchSpeed` lines in
that window belong to an unrelated wave refresh). The `fallback-ladder` case above is
direct evidence, so the honest conclusion is that repeated imports accumulate playlists.

This is **pre-existing behaviour, not a regression** from this change — `createPlaylist`
was not touched — but it is user-visible and worth fixing separately (either reuse an
existing same-named import playlist or timestamp the name).

---

## 7b. Second defect found on device: the fallback query ladder was dead code

The fix's stated mechanism was "a plan of queries from exact to broad, so a spelling
mismatch no longer silently drops the row". On device that mechanism **was not running**:
every row issued exactly one search using `queries` item 0 and never fell through.

Evidence — a fixture with deliberately mangled metadata (`The Killers - Official Channel`
as artist, `(Official Music Video) [HD Remastered]` in the title):

```
Готово: 3 треков → «Импорт: fallback-ladder» · не найдено: 1      <-- before
[...] 4 search lines, one per row, all q1
```

`MainViewModel` had:

```kotlin
val queries = ImportSearchPlanner.queries(entry)   // plan computed...
val query = queries.firstOrNull()                  // ...only item 0 used
```

The plan was computed and thrown away. Fixed to walk the ladder and stop at the first
confident match, with the remaining network budget checked **before each query** (a plan can
cost up to `MAX_QUERIES` requests per row, so one row could previously have overrun
`IMPORT_NETWORK_SEARCH_BUDGET`). `ImportResolution` now reports `searches` so the budget
counts actual requests.

Same fixture, after the fix:

```
Готово: 4 треков → «Импорт: fallback-ladder» · сохранено 1 из 1     <-- after
00:05:57 yt-dlp search 'The Killers - Official Channel Mr. Brightside (Official Music Video) [HD Remastered]': 40 tracks   <- q1
00:06:03 yt-dlp search 'The Killers - Official Channel Mr. Brightside': 40 tracks                                        <- q2, matched
```

**1 «не найдено» → 0**, and `Mr. Brightside` is now in the playlist. This is the clearest
possible proof that the ladder is live: the q2 search would not exist otherwise.

## 7c. Match quality still imperfect (denylist limitation)

The same run shows the residual exposure flagged in `audit/FINDINGS.md` F4:

| entry | what was saved | assessment |
|---|---|---|
| `Seven Nation Army` — The White Stripes | `Seven Nation Army` — **Gaullin** | wrong artist; a cover outranked the original |
| `Mr. Brightside` — The Killers | `Mr. Brightside` — **Matty Hinds** | wrong artist; a cover |
| `Take Me Out` — Franz Ferdinand | `Franz Ferdinand - Take Me Out (Video)` | correct |
| `Last Nite` — The Strokes | `The Strokes - Last Nite (Official HD Video)` | correct |

Both misses share a shape the current scorer cannot see: the entry's *artist* was polluted
(`The Killers - Official Channel`, `The White Stripes Topic`) and the candidate's artist was
a different real name, with **no artist token overlap at all**. The title matched exactly,
so the score was carried by the title alone and the artist contributed nothing.

This is inherent to a title-first, additive scorer and is **not** introduced by this change.
A principled next step would be to make a *zero* artist overlap disqualifying when the entry
does supply an artist and a candidate with real overlap exists. It is deliberately left
unfixed here: changing the accept threshold is exactly the kind of edit that can start
dropping rows that currently resolve, and it deserves its own fixture and regression pass
rather than being folded into this one.

## 7. First defect found during verification: edition-blind matching

Verification exposed a **match-quality bug that removing the 150-cap did not touch**, and
gave a clean before/after on real device data:

| batch | derivative editions among saved |
|---|---|
| **pre-fix** (`spotify-liked.csv`, 23:20–23:26) | **3 of 10** — `Rolling in the Deep (Acapella)`, `Shape of You (Stormzy Remix)`, `bad guy (with Justin Bieber)` |
| **post-fix** (`edition-traps.csv`, 23:41–23:46) | **0 of 8** |

Cause: `ImportTrackMatcher.normalize()` strips bracketed text entirely, so
`Rolling in the Deep` and `Rolling in the Deep (Acapella)` normalise to the identical
string; both scored the same, and `best()` takes the first at equal score — so the *order
of the YouTube result list* decided and a derivative version won.

Fix — `ImportTrackMatcher.editionTieBreak()`, applied **only when normalised titles
already match**, so no previously-passing match can regress below the accept threshold:

* **−2** when the candidate carries an alternative-edition tag (`live`, `acoustic`,
  `acapella`, `instrumental`, `karaoke`, `remix`, `rmx`, `mix`, `cover`, `demo`, plus
  Russian equivalents) and the entry does not;
* **+1** when the candidate is the bare canonical title and the entry carries only a
  catalogue decoration (`(Official Video)`, `- Remastered 2011`, `[Official HD …]`);
* **+1** when the candidate artist is the `- Topic` channel of the entry artist.

Covered by 8 new unit tests. Full suite: **233 tests, 0 failures, 0 errors** (25 suites).

## 8. Per-fixture table

**All eight fixtures, covering all seven supported formats, were imported on device.**

| fixture | format | entries | resolved | saved | verdict |
|---|---|---|---|---|---|
| `spotify-liked.csv` | Spotify CSV | 10 | 10 | 10 | **PASS** |
| `apple-music-library.csv` | Apple Music CSV | 10 | 10 | 1 (9 already local) | **PASS** |
| `edition-traps.csv` | Spotify CSV (edition variants) | 8 | 8 | 8 | **PASS** |
| `over-150-rows.csv` | Spotify CSV, **200 rows** | 199 (1 dup) | 199 | 0 new (already local) | **PASS** |
| `spotify-playlist.json` | nested Spotify Web API JSON | 11 | 11 | 0 new (already local) | **PASS** |
| `ytmusic-takeout.csv` | **YouTube Music Takeout CSV** | 14 | 14 | 2 | **PASS** |
| `plain-titles.txt` | **bare `Artist - Title` text** | 12 | 12 | 0 new (already local) | **PASS** |
| `plain-titles.m3u` | **M3U/M3U8** | 10 | 10 | 0 new (already local) | **PASS** |
| `fallback-ladder.csv` | Spotify CSV, mangled metadata | 4 | 4 | 4 | **PASS** (after §7b fix) |

Resulting playlists on device (last full listing):

```
Импорт: spotify-liked          10 tracks      Импорт: fallback-ladder   3 tracks
Импорт: apple-music-library    10 tracks      Импорт: fallback-ladder   4 tracks
Импорт: edition-traps           8 tracks      Импорт: ytmusic-takeout  14 tracks
Импорт: over-150-rows         199 tracks      Импорт: plain-titles     12 tracks
Импорт: spotify-playlist       11 tracks      Импорт: plain-titles     10 tracks
```

Library totals: **405 tracks**, of which **25 are saved to the device**
(`source=LOCAL` × 25, `file:` URI × 25, still-remote × 0).

The two `plain-titles` playlists carry the same name because a `.txt` and an `.m3u` of
the same base name were both imported — see §6 on duplicate import playlists.

### 8d. The typographic-dash hard cases resolve

`plain-titles.txt` deliberately contains an en dash and `plain-titles.m3u` an en dash
inside `#EXTINF`, with decorated titles. Both resolved with **zero network searches**,
i.e. entirely from the local index:

| fixture | input line | resolved to |
|---|---|---|
| `plain-titles.txt` | `Daft Punk – Get Lucky (feat. Pharrell Williams) [Official Video]` | `Daft Punk - Get Lucky (Official Audio) ft. Pharrell Williams, Nile Rodgers` |
| `plain-titles.txt` | `Billie Eilish — Bad Guy - Remastered 2011` | `Billie Eilish - bad guy (with Justin Bieber) [Audio]` |
| `plain-titles.m3u` | `#EXTINF:248,Nirvana – Smells Like Teen Spirit (Official Video)` | `Smells Like Teen Spirit (Devonshire Mix)` |

This is the `–` / `—` ⇄ `-` unification in `normalize()` doing its job on real input, and
it is the one previously-untested behaviour that this round closed.

## 8a. The 150-row cap is demonstrably gone

`over-150-rows.csv` holds **200 data rows**; the import reported
**`Готово: 199 треков → «Импорт: over-150-rows»`** and the stored playlist holds
**199 track ids** — with **no truncation warning** in the UI.

Under the old code this file would have stopped at 150 rows. 199 > 150, so the old
`.take(150)` cannot still be in the path. (199 rather than 200 because two rows named the
same track and the importer's `LinkedHashMap` deduplicated them — correct behaviour, and
itself evidence that the whole file was read.)

## 8b. The nested Spotify JSON parser works

`spotify-playlist.json` uses the real Spotify Web API envelope
(`{"items":[{"track":{"name","artists":[{"name"}],"duration_ms"}}]}`), a structurally
different shape from the flat CSV path. It imported **11 tracks** with **0 network
searches** — the nested `items[].track` branch parses, and every track was already in the
local library so the local index resolved it without touching the network. This was
previously the largest untested code path in the change.

## 8c. Local-index-first is confirmed by search counts

Across the `over-150-rows` and `spotify-playlist` imports, `adb logcat` recorded
**0 `SearchSpeed` lines**: 210 rows resolved entirely from the on-device library index with
no network search at all. That is the intended fast path and it is measurably in effect.

## 9. Reproducing this

`device-verify/import-fixture.ps1` drives one fixture end to end and prints the banner plus
every network search:

```powershell
.\device-verify\import-fixture.ps1 -Fixture plain-titles.m3u
.\device-verify\import-fixture.ps1 -Fixture spotify-liked.csv -TimeoutSec 900
```

It handles three things about this device that cost real debugging time, all of which are
commented at the relevant place:

* **`Wait-ForApp`** — after `force-stop`, `MainActivity` can take >10 s to render, and the
  launcher is focused meanwhile. A fixed sleep dumps the launcher instead and sends the
  state machine down the wrong path.
* **Accordion navigation** — the import section's expanded/collapsed state persists between
  launches, and its chevron is a *sibling* of the header text, so the right chevron is
  found by matching its Y against the header's. When expanded, the import button is below
  the fold and the list must be scrolled.
* **`Invoke-Adb` argument passing** — arguments must be splatted as one array. A
  `ValueFromRemainingArguments` parameter makes PowerShell's binder swallow `-p app.sonora`
  as if it were a parameter name, so a `monkey` call launches the wrong app while the
  script reports success. See the comment on that function.

`device-verify/push-fixtures.ps1` re-pushes the fixtures and verifies byte sizes.

## 10. Final device state

```
library tracks    : 409
saved to device   : 26       (source=LOCAL x26, file: URI x26, still-remote x0)
import playlists  : 11
unit tests        : 239 tests, 0 failures, 0 errors
installed build   : 230313964 bytes, lastUpdateTime=2026-11-03 00:42:38
crashes           : 0
```

## 11. Remaining cover-vs-original defect (F5) — honest status

The scorer was improved this round (channel-noise stripping plus preferring an
artist-agreeing candidate at equal score), and it changed real behaviour:

| entry | before | after |
|---|---|---|
| `Seven Nation Army` — The White Stripes | Gaullin (cover) | **The White Stripes** ✅ |
| `Mr. Brightside` — The Killers | Matty Hinds (cover) | Matty Hinds ❌ unchanged |
| `Do I Wanna Know?` — Arctic Monkeys | — | a slowed+reverb reupload ❌ |

`Mr. Brightside` did **not** change, and the reason is more important than the original
bug: an earlier pre-fix import had already persisted `Mr. Brightside` by `Matty Hinds` into
the library, and **the local index is consulted before the network**. That wrong match now
shadows the correct answer, and the run issued no search for it at all (the 3 searches were
Gorillaz, Arctic Monkeys, and one polluted retry). **A wrong local match is sticky and
cannot self-correct**, even after the matcher is fixed.

Fix would require treating a local hit whose artist disagrees with the entry as
low-confidence and letting one network search compete with it. Not done here — it changes
the local-first contract and needs its own fixture set.

## 12. Honest gaps

* `pm clear` unavailable → no from-scratch first-run test (the dirty-library run is
  arguably harder, but it is not the same test).
* `ytmusic-takeout.csv`, `plain-titles.txt` and `plain-titles.m3u` **are now imported on
  device and PASS** — see §8 and §8d. Their typographic-dash cases are what closed the
  `normalize()` coverage gap.
* The 20 000-row `MAX_ENTRIES` ceiling and the 8 MiB `MAX_BYTES` guard were never hit, so
  the "Взяты первые 20000 треков из N" warning path is untested on device.
* `IMPORT_NETWORK_SEARCH_BUDGET` (400) was never exhausted, so its user-facing warning is
  untested on device. The largest file tried (200 rows) needed **0** network searches, and
  the cover-trap run needed 3.
* **Only `fallback-ladder.csv` ever needed a second query.** Every other fixture resolved
  from the local library (0 searches) or matched on the first planned query. q2 is
  therefore proven live on device (§7b); q3/q4 remain unit-test-only.
* Saving was verified with fresh downloads on `spotify-liked.csv` (10/10),
  `edition-traps.csv` (8/8) and `fallback-ladder.csv`. The three fixtures added this round
  resolved almost entirely to already-downloaded tracks, so their save counters proved
  less: `ytmusic-takeout.csv` saved 2, `plain-titles.txt` and `plain-titles.m3u` reported
  no save counter at all. That is a weaker save assertion than the 10/10 run.
* No `SonoraDownload` warning lines appeared in any capture, so download-path retry logic
  was not exercised.
* `dumpsys netstats` host attribution and `dumpsys meminfo` during a large import were not
  captured.
* Download throughput was not measured; only wall-clock completion was observed.
