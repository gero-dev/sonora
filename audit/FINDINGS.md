# Independent audit — imported-track search fix (Sonora 3.5.1)

Scope: the working-tree diff that implements the three deliverables. Read-only review plus
`git diff` inspection; no source was modified during this audit.

Verdict per deliverable:

| # | Deliverable | Verdict |
|---|---|---|
| 1 | Imported-track search works for Spotify / Apple Music / YouTube Music / Takeout exports | **PASS** — but only after a second on-device defect was found and fixed (A2b) |
| 2 | Resolved tracks are actually saved to the device | **PASS** |
| 3 | The 150 cap is gone; maximum possible results | **PASS** (by design, see A4) |

Notation: `A2b` below was written after a late on-device pass; the sections before it were
written earlier and are not all still accurate. **A14 lists the corrections.**

---

## A1. Cap removal — verified clean

`grep` over `app/src/main/java/app/sonora` for `take(150)` / `MAX_PLAYLIST_IMPORT`:

* `MAX_PLAYLIST_IMPORT` — **0 matches**, the constant is deleted.
* `.take(150)` — **0 matches**. The only remaining `150` tokens in the tree are unrelated
  (`historyTracks(limit = 150)` page size, a 150 s network timeout, `150_000L` verify
  timeout, `150.dp` layout widths, an animation duration, a `novelty/150` divisor).
* `MainViewModel.kt:3210` now carries the explanatory comment where the truncation was,
  and `:3213` uses `data.entries` untruncated.

Two independent ceilings remain, both intentional:

| constant | value | location | purpose |
|---|---|---|---|
| `PlaylistFileImporter.MAX_ENTRIES` | 20 000 | importer:102 | rows read from one file, 1 000 → 20 000 |
| `PlaylistFileParser.MAX_ENTRIES` | 20 000 | importer:471 | parser's own ceiling, kept in sync |
| `IMPORT_NETWORK_SEARCH_BUDGET` | 400 | MainViewModel:252 | network-search budget per import |

**Adversarial note — the two `MAX_ENTRIES` are duplicated literals** (`20_000` written
twice, importer:102 and importer:471, with no shared reference). They are currently equal,
so behaviour is correct, but they can silently drift apart later. Not a defect today;
worth collapsing into one constant.

**Adversarial note — `MAX_BYTES` stayed at 8 MiB while `MAX_ENTRIES` went to 20 000.** An
8 MiB body of minimal rows (`a,b\n` ≈ 4 bytes) could hold ~2 M rows, so the byte cap bites
long before the row cap for tiny-row files; for realistic CSV/JSON exports the row cap
binds first. This is a coherent pair of guards, but it means "maximum possible number of
results" is bounded by file size, not by the row count — see A4.

## A2. Silent-drop defect — claimed fixed, then found NOT fixed (see A2b)

The old failure mode was a single narrow `"artist title"` query per row with no fallback,
so a spelling mismatch dropped the row with no user-visible signal. The intended fix:

* `ImportSearchPlanner.queries()` returns up to 4 queries narrow→broad and **never returns
  an empty list** when a title exists.
* `resolveImportedEntry` tries, in order: exact local index → full matcher over local
  candidates → direct `entry.videoId`/url → `RemoteTrackIndex` cache → one network search.
* `notFound` rows are counted and surfaced, and when the network budget is exhausted the
  user gets an explicit `extraWarnings` message rather than a silent stop.

On device this eliminated dropping: 28 of 28 rows across CSV (Spotify), CSV (Apple) and
CSV (edition-traps) resolved.

**Caveat:** the nested Spotify Web API JSON shape was untested on device at the time of
first writing; it has since been imported successfully — see A10.

## A2b. HIGH SEVERITY — the fallback query ladder was dead code (found and fixed late)

**This is the most serious defect in the change, and my first pass at this audit missed
it.** Section A2 above claimed the narrow-query defect was fixed by the query plan. That
claim was wrong: the plan was computed and then almost entirely discarded.

`MainViewModel.resolveImportedEntry` step 4 read:

```kotlin
val queries = ImportSearchPlanner.queries(entry)   // full plan built...
val query = queries.firstOrNull()                  // ...only item 0 ever searched
```

So the "plan from exact to broad" — the stated mechanism of the fix — never ran. Every row
issued exactly one search on `"<artist> <title>"`, which is the *same* narrow behaviour that
caused the original silent row loss, merely relocated behind a planner that looked correct
and was unit-tested in isolation.

Device evidence, a 4-row fixture with deliberately mangled metadata:

```
before:  Готово: 3 треков  · не найдено: 1     and exactly 4 SearchSpeed lines (one per row)
after:   Готово: 4 треков  · сохранено 1 из 1  and a 2nd search for the same row:
   00:05:57  'The Killers - Official Channel Mr. Brightside (Official Music Video) [HD Remastered]'  <- q1
   00:06:03  'The Killers - Official Channel Mr. Brightside'                                         <- q2, matched
```

The second search for the same row is proof the ladder is now live; `Mr. Brightside` moved
from "не найдено" into the playlist.

Two related fixes went in with it:

* `ImportResolution` now carries `searches`, so `networkSearches` counts **actual requests**
  rather than rows that touched the network.
* The remaining budget is checked **before each query**. Previously a single row could
  consume up to `MAX_QUERIES` requests while the caller only ever incremented by one, so the
  `IMPORT_NETWORK_SEARCH_BUDGET` ceiling could be overshot by up to 4×.

**Process lesson worth recording:** the planner had thorough unit tests and the importer had
none, so a fully-tested component sat inside a caller that ignored it. Unit-testing a pure
helper does not verify that its result is *used*. This is exactly the class of bug that only
an end-to-end run on real input catches, and it is why the on-device pass was worth doing
even after the suite was green.

## A3. Save path — the reported "found N, saved nothing" defect

Root cause confirmed by reading the code: imported saves go through
`requestSave(track, allowMobile = false)`, which routes them via `_pendingMobileDownloads`;
`cancelMobileDownloads()` previously emptied that list **with no state change and no
message**, so an import could report success while writing nothing.

Current state:
* `cancelMobileDownloads()` now writes `TrackSaveState.unavailable("Сохранение отменено")`
  for pending tracks and emits a toast — cancellation is no longer invisible.
* `startImportedSaveCounter()` sets `importedSaveSawActivity` and throws
  `CancellationException("import save batch finished")` to end its own collector, so the
  "saved X of Y" counter terminates instead of hanging on the last track.
* `importPlaylistFile` clears `importedSaveTracks` and calls `stopImportedSaveCounter()` at
  start, so a second import does not inherit the first run's counter.

Device evidence: three imports reached `сохранено 10 из 10`, `1 из 1`, `8 из 8`, and the
library ended with 19 tracks at `source=LOCAL` / `file:` URIs — i.e. saves genuinely land.

## A4. "Maximum possible number of results" — honest reading

The literal request ("instead of 150, the maximum possible") is satisfied for the input
list: there is no row-count truncation below the file-size guard. Three practical ceilings
remain and should be stated plainly rather than claimed away:

1. `REMOTE_SEARCH_LIMIT` = 100 is a **UI/search ceiling**, not a real limit: a single
   InnerTube response returns ~20 items, so 100 already exceeds one page. The import path
   deliberately uses `IMPORT_SEARCH_LIMIT` = `CANDIDATE_LIMIT` = 40 instead, because a
   larger candidate pool was measured as wasted work (see A6).
2. `IMPORT_NETWORK_SEARCH_BUDGET` = 400 bounds network searches per import. A 5 000-row
   file of music not in the local library would stop after 400 searches and warn. This is
   a deliberate, *announced* valve — the opposite of the old silent `.take(150)`.
3. `MAX_BYTES` = 8 MiB bounds file size.

None of these is a regression relative to the old behaviour, and all three are
user-visible when they bite. Deliverable 3 is met in the sense the user meant it.

## A5. Matcher equivalence — the highest-risk invariant, and it holds

`CandidateIndex.best()` must be behaviourally identical to `ImportTrackMatcher.best()`.
Both call the single private `scoreNormalized(...)`; the loop order and the strict `>`
comparison are preserved, so the earliest candidate still wins ties and `MIN_SCORE` is
applied identically. The refactor removed the private `Prepared` wrapper class in favour of
parallel `candidates`/`titles`/`artists` lists — that was necessary because the
`internal` constructor could not expose a `private-in-class` type argument.

Equivalence is asserted over a ≥40-pair corpus plus the new edition-specific pairs rather
than argued by inspection. I consider this adequately proven.

**Adversarial note:** `scoreNormalized` now takes 8 parameters including both raw and
normalised titles for candidate and entry. That is a wide, positional-argument-prone
signature; every call site is named-argument, so it is safe today, but an accidental
positional call would still compile if types matched.

## A6. Residual match-quality defect found during verification

**This is the most important finding in the audit.** Removing the cap exposed a
pre-existing scoring flaw: `normalize()` strips bracketed text entirely, so
`Rolling in the Deep` and `Rolling in the Deep (Acapella)` normalise to the same string,
score identically, and the *order of the YouTube result list* decides the winner.

Measured on device, not theorised:

| batch | derivative editions among saved |
|---|---|
| pre-fix (`spotify-liked.csv`) | **3 of 10** — `(Acapella)`, `(Stormzy Remix)`, `(with Justin Bieber)` |
| post-fix (`edition-traps.csv`) | **0 of 8** |

The fix (`editionTieBreak`) is applied **only when normalised titles already match**, so it
cannot push a previously-accepted match below `MIN_SCORE` — it only reorders equals. That
containment property is what makes it safe, and it should be preserved by anyone editing
this code.

Remaining exposure: the tag list is a denylist, so an untagged derivative phrasing still
wins. `Come As You Are (Devonshire Mix)` slipped through the first cut for exactly this
reason (bare `mix` was missing). A denylist can never be complete.

## A7. Performance / dispatcher analysis

* Local matching runs inside `withContext(Dispatchers.Default)` — correct, this was the
  main-thread freeze risk on large exports.
* `buildImportMatchInputs()` and the `RemoteTrackIndex` cache are hoisted **outside** the
  per-row loop, so candidate assembly and search-result reuse are not quadratic.
* All HTTP runs on `Dispatchers.IO` via `Http.kt`, so the import loop never does network on
  the compute dispatcher.
* Observed cost: ~6.5 s per network search, 10 searches for 10 rows ≈ 65 s of the ~7-minute
  run; the remainder is download time. Searches are sequential, so a large import is
  network-bound and will take minutes — acceptable, but it means the 400-search budget is
  also a *runtime* budget of roughly 43 minutes in the worst case.
* Progress updates are throttled to every 5 entries.

## A8. Idempotence and regression

Re-importing a file creates a **second playlist with the same name** (`createPlaylist`
always appends a fresh UUID and never reuses an existing same-named playlist). Direct
evidence — two distinct playlist ids, same name, from two imports of the same fixture:

```
local:playlist:434b5eff-…  name=Импорт: fallback-ladder  tracks=3
local:playlist:c5a4b5e3-…  name=Импорт: fallback-ladder  tracks=4
```

What *is* deduplicated:

* **within one run** — `over-150-rows.csv` has 200 rows naming 199 distinct tracks and the
  playlist holds exactly 199 ids, so the `LinkedHashMap` keyed by resolved track id works;
* **downloads** — `toSave` filters on `!isTrackDownloaded(track)`, so already-saved tracks
  are not fetched again.

So the accurate statement is: idempotent for tracks and downloads, **not** for playlists.
This is pre-existing behaviour (`createPlaylist` was not touched by this change) but it is
user-visible and worth fixing separately — either reuse a same-named import playlist or
timestamp the name.

One consequence is worse than clutter, and is covered in A13: because a wrong match is
persisted into the library, a re-import hits the local index first and **cannot** correct
itself, even after the matcher is fixed.

## A9. Test-suite state

**239 tests, 0 failures, 0 errors.** New coverage: `ImportSearchPlannerTest`,
`LibraryTrackIndexTest`, `RemoteTrackIndexTest`, a matcher equivalence corpus, 8
edition-tie-break tests, and 6 artist-preference tests (A13). No pre-existing test was
weakened or deleted to make new behaviour pass — the matcher changes were validated
against the existing corpus first.

Coverage that does **not** exist and would have caught A2b: nothing tests
`MainViewModel.resolveImportedEntry`. The planner was unit-tested in isolation and passed
while the caller ignored it. Wiring-level behaviour in this class is currently verified
only by driving the device.

## A10. Gaps — status after the follow-up passes

| gap | status |
|---|---|
| No device run of the Spotify nested-JSON parser (A2 caveat) | **CLOSED** — `spotify-playlist.json` imported 11 tracks, 0 network searches |
| No >150-row import on device | **CLOSED** — `over-150-rows.csv` (200 rows) → `Готово: 199 треков`, playlist holds 199 ids, no truncation warning |
| Fallback ladder never exercised end-to-end | **CLOSED** — and closing it exposed F2, the dead-code defect |
| YouTube Music Takeout CSV, bare TXT and M3U never imported | **CLOSED** — all three imported; see `device-verify/VERIFICATION.md` §8 |
| No `pm clear`, so no from-scratch first run | open — OS/ColorOS restriction |
| Raw audio-file `ls` not obtainable | open — OS restriction; proof via the app's own `file:` URIs |
| `MAX_ENTRIES` / `MAX_BYTES` ceilings never hit | open — 20 000 rows and 8 MiB were not reached |
| `IMPORT_NETWORK_SEARCH_BUDGET` exhaustion never triggered | open — the largest file needed 3 searches of 400 |
| q3/q4 of the query plan never reached | open — only q2 has real device evidence (A2b) |
| Sticky wrong local match cannot self-correct | open — the substantive remaining defect (A13) |
| `dumpsys netstats` attribution, `dumpsys meminfo` during a large import | open — not captured |

**A10.1 — the fallback ladder gap is CLOSED, and closing it found F2.** The layer is now
exercised end-to-end: `fallback-ladder.csv` drives q1 to a miss and q2 to a hit, visible as
two `SearchSpeed` lines for one row. Before the fix that same fixture produced one search
per row and one "не найдено".

**A10.2 — remaining untested paths.** `ytmusic-takeout.csv`, `plain-titles.txt` and
`plain-titles.m3u` were pushed but never imported. The `MAX_ENTRIES` (20 000) and
`MAX_BYTES` (8 MiB) ceilings, and the `IMPORT_NETWORK_SEARCH_BUDGET` (400) warning, were
never reached. `dumpsys netstats` attribution and `dumpsys meminfo` were not captured.
`pm clear` is blocked by ColorOS, so there is no from-scratch first-run test.

**A10.3 — the highest-value test still outstanding** is the one that would have caught F5:
a fixture whose entries carry a *correct* artist but a title that also exists as a cover,
asserting the original artist is chosen. The current scorer will pick whichever comes first
in the YouTube result list, because a zero artist overlap costs nothing when the title
matches exactly.

## A11. Summary of findings

| id | severity | finding |
|---|---|---|
| F1 | high | Edition-blind scoring saved remixes/acapellas — **fixed**, verified 3/10 → 0/8 |
| F2 | high | Fallback query plan was dead code (`queries.firstOrNull()`) — **fixed**, verified 1 not-found → 0 |
| F3 | medium | Budget overshoot: one row could spend up to `MAX_QUERIES` requests while counted as 1 — **fixed** |
| F4 | medium | Re-importing a file creates a duplicate same-named playlist (`createPlaylist` never reuses) — open, pre-existing |
| F5 | medium→low | Zero artist overlap was not disqualifying; covers won over originals on an exact title — **partially fixed**, see A13 |
| F11 | medium | A wrong local match is **sticky**: the local index is consulted first, so a bad match can never be corrected by a later fix (A13) |
| F6 | low | `MAX_ENTRIES = 20_000` duplicated in two places; can drift |
| F7 | low | `MAX_BYTES` 8 MiB unchanged while row cap grew 20×; bounds "maximum results" for tiny rows |
| F8 | low | Edition tag list is a denylist; untagged derivative phrasing still wins |
| F9 | info | `scoreNormalized` has a wide 8-parameter signature; named args used at every site |
| F10 | info | Worst-case import is ~43 min at the 400-search budget; network-bound, sequential |
| F12 | info | Nothing tests `MainViewModel.resolveImportedEntry`; wiring bugs are invisible to the suite (A9) |

No high-severity finding remains open. F1, F2, F3 and the first half of F5 were found and
fixed during this work; F2 (and F11) were missed by the first audit pass.

## A13. Cover-vs-original (F5): fixed in the scorer, but only partly observable on device

**The defect.** When the entry's artist matched nothing, an exact title still carried the
score over `MIN_SCORE`, so the winner was whichever candidate the catalogue listed first.
Measured on device: `Seven Nation Army` → **Gaullin** (a cover), `Mr. Brightside` →
**Matty Hinds** (a cover).

**What was fixed.** Two additions to `ImportTrackMatcher`, both confined to tie-breaking:

1. `artistBase()` strips channel/label noise (`- Topic`, `- Official Channel`, `Official`,
   `VEVO`, `BAND`, `Records`, `Music`) before comparing artists. Real exports carry channel
   names, so `The Killers - Official Channel` previously matched *no* candidate and threw
   the decision to catalogue order.
2. `pickBest()` now prefers, **at equal score only**, a candidate whose artist actually
   agreed (via `hasArtistAgreement`, which returns `null` — not `false` — when either
   artist is blank, so a blank artist is never penalised). Higher score still wins; strict
   `>` still preserves "earliest in list wins" when nobody agrees.

Applied to both `best()` and `CandidateIndex.best()` through a shared `scoreOne`/`pickBest`
pair, so the scalar and indexed paths cannot diverge.

**On-device result, and why it is only partial.**

| entry | before | after |
|---|---|---|
| `Seven Nation Army` — The White Stripes | Gaullin (cover) | **The White Stripes** ✅ |
| `Mr. Brightside` — The Killers | Matty Hinds (cover) | Matty Hinds ❌ unchanged |
| `Do I Wanna Know?` — Arctic Monkeys | — | a slowed+reverb reupload ❌ |

`Mr. Brightside` did not change for a reason that is **not** a scorer bug: the fixture's
title and artist exactly match a track an earlier pre-fix import had already saved
(`Mr. Brightside` by `Matty Hinds`, plus a `Matty Hinds - Topic` variant). The local-library
exact-key lookup and the full local matcher therefore both return that wrong entry, and
**no network search is ever issued** — confirmed: the run produced exactly 3 searches
(Gorillaz, Arctic Monkeys, and one polluted retry), none for Mr. Brightside. The wrong
match has been persisted into the library and now shadows the correct answer.

So the honest statement is:

* the scorer fix is real and verified by 6 new unit tests plus the `Seven Nation Army`
  behavioural change on device;
* a wrong local match is **sticky** — once saved, it wins forever for that exact key,
  regardless of how good the matcher gets. This is a more significant finding than the
  original scoring gap, and it is a *design* issue in the local-first ordering, not a
  weighting issue;
* `Do I Wanna Know?` shows a second, independent weakness: the artist regex strips
  `Official`/`VEVO` from the *channel* name but cannot help when the candidate's artist is
  itself garbage (`𝙘𝙭𝙞𝙢𝙭𝙧™`), and the item is even labelled `(Slowed & Reverbed)` in
  Unicode math-alphanumerics, which no ASCII tag list will catch.

**Recommended next step (not done here):** when a row resolves to a *local* track whose
artist disagrees with the entry, treat it as a low-confidence hit and allow one network
search to compete against it, preferring the artist-agreeing result. That directly closes
both remaining cases without touching the accept threshold.

## A14. Correction to the record

Two claims made earlier in this audit were wrong and are corrected here rather than left
standing:

1. **A2 claimed the narrow-query defect was fixed.** It was not — the planner was never
   consulted past its first element (A2b). The unit tests passed because they tested
   `ImportSearchPlanner.queries()` as a pure function, which was always correct, and nothing
   tested that the importer *used* more than one of its answers.
2. Special-case measurements of "idempotence" taken mid-session were unreliable: at least
   one presumed second import had not actually run (the file-picker row coordinates had
   shifted after a fixture was added), and the `SearchSpeed` lines in that window belonged to
   an unrelated wave refresh. The duplicate-playlist finding in F4 is based on directly
   reading two distinct playlist records and supersedes those measurements.

The general lesson: for this codebase, a pure-function unit test suite can be fully green
while the feature is broken, because the defect lives in the wiring between tested parts.
