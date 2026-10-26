# Открытые каталоги и онлайн-метаданные (Discover)

Состояние на **26.10.2026**. Документ описывает data-слой Sonora: поиск и
скачивание музыки из открытых каталогов (**Internet Archive**, **Jamendo**) и
онлайн-обогащение метаданными (**MusicBrainz**, **Cover Art Archive**, **LRCLIB**).
Только публичные API, без DRM, скрейпинга и обходов. Правовая рамка — в
[LEGAL.md](LEGAL.md), платформенные факты — в [PLATFORM-FACTS.md](PLATFORM-FACTS.md).

## Зачем это нужно

Плееру своей музыки нужен легальный способ найти и скачать свободный контент:
аудио под public domain / Creative Commons, у которого есть прямой `https`-файл —
его можно слушать (ExoPlayer) и скачивать (`DownloadManager`). Контракт —
`DiscoverSource`, объединение — `DiscoverRepository`.

## Internet Archive (archive.org)

**Что даёт:** поиск по публичным аудио-айтемам (концерты, netlabel-релизы,
public domain-записи) и прямую ссылку на файл. API-ключ не нужен.

**Как работает `ArchiveDiscoverSource`:**

1. `GET https://archive.org/advancedsearch.php` с `q=<query> AND mediatype:(audio)`
   и полями `identifier`, `title`, `creator`, `year`, `licenseurl`; `fl[]`
   кодируется как `fl%5B%5D=`.
2. Для каждого айтема (не больше 12 на поиск) — `GET /metadata/<identifier>`,
   из `files` выбирается лучший аудиофайл: форматы `VBR MP3`, `MP3`,
   `Ogg Vorbis`, `32Kbps MP3`, `Flac`, `64Kbps MP3` (в этом порядке); отметаются
   `_files.xml`, `_meta.xml`, `.torrent`; иначе — первый файл с аудио-расширением.
   Айтемы с `access-restricted-item` и файлы с `private: true` пропускаются:
   их нельзя скачивать свободно.
3. Ссылки: `archive.org/download/<identifier>/<file>` (сегменты пути кодируются
   отдельно) и `archive.org/services/img/<identifier>` как обложка.
4. Метаданные айтема кэшируются в памяти процесса, параллельных запросов — не
   больше 4 (семафор), чтобы не нагружать публичный сервис.

**Правовая база:** лицензию задаёт сам айтем — `licenseurl`/`rights` в metadata.
Sonora показывает короткую подпись («CC BY-SA 4.0», «Public Domain», «Проверьте
лицензию на archive.org»), кладёт `extra["licenseUrl"]` и deep link на айтем.
Перед публикацией скачанного файла пользователь обязан соблюдать лицензию и
указывать автора. Non-CC контент автоматически не фильтруется: решение за
пользователем. **Настройка не требуется** (`isAvailable() = true`).

## Jamendo (api.jamendo.com v3.0)

**Что даёт:** каталог независимых артистов под Creative Commons; официальный API
отдаёт и поток (`audio`), и файл для скачивания (`audiodownload`).

**Как работает `JamendoDiscoverSource`:** `GET https://api.jamendo.com/v3.0/tracks/`
с `client_id`, `format=json`, `limit`, `search`, `audioformat=mp32`,
`include=musicinfo`. `uri` = `audiodownload`, иначе `audio` (только https);
треки без аудио или с `audiodownload_allowed = false` пропускаются. В `extra`:
`fileName`, `license`, `licenseUrl` из `license_ccurl`, `streamUrl`, жанры.

**Настройка:**

1. Зарегистрироваться на <https://devportal.jamendo.com> (бесплатно).
2. Создать приложение и скопировать **client_id** (client_secret не нужен и в
   APK не попадает).
3. Вставить ключ в Sonora: Настройки → Jamendo client_id.
4. Без ключа источник недоступен: `isAvailable() = false`, а `setupHint()`
   объясняет, где взять ключ; чип в UI остаётся с подсказкой.

**Квоты:** Jamendo лимитирует запросы на client_id и может ответить `429`;
приложение ограничивает `limit` сотней результатов и не делает дополнительных
запросов на каждый трек. Неверный/отозванный ключ (API может ответить `200` с
`headers.status=failed`, `401` или `403`) и сетевые ошибки превращаются в пустой
список — UI показывает «Ничего не найдено»/подсказку.

## Онлайн-метаданные: MusicBrainz + Cover Art Archive + LRCLIB

`MetadataService` дополняет локальные треки тем, чего нет в тегах. Трек не
меняется напрямую: сервис возвращает `TrackMetadata`, а
`LibraryRepository.updateTrackMetadata` заполняет только пустые поля.

**MusicBrainz** — альбом, год, MBID записи: `GET /ws/2/recording/?query=recording:"<title>" AND artist:"<artist>"&fmt=json&limit=5`.
Обязателен `User-Agent: Sonora/0.2.0 (Android; contact: sonora.app@example.com)`
и **не больше 1 запроса в секунду глобально** (`Mutex` + `lastRequestAt`); на
`503` — одна повторная попытка через 1.5 с. Год — из `first-release-date` или
`releases[0].date` (первые 4 символа), альбом — `releases[0].title`, MBID —
`recordings[0].id`. Если исполнитель пуст или «Неизвестный исполнитель»,
ограничение по artist не добавляется.

**Cover Art Archive** — обложка по release id:
`https://coverartarchive.org/release/<releaseId>/front-500`. URL не проверяется
отдельным запросом: если обложки нет, Coil покажет заглушку (404 — норма).

**LRCLIB** — текст: `GET https://lrclib.net/api/search?track_name=<...>&artist_name=<...>&limit=5`;
при пустом результате повторный запрос только по названию. Сначала выбирается
`plainLyrics`; если её нет — `syncedLyrics` очищается от таймкодов `[00:12.34]`
и пустых строк. Ключ не нужен, User-Agent рекомендуется (тот же, что у MB).

Название перед поиском чистится от расширения файла, `[...]`, `(feat. ...)`,
`(Remastered)`, `(Live)`; исполнитель режется до первого из `;`, `,`, `&`,
` feat`. Результаты (включая отрицательные) кэшируются в памяти по ключу
`artist|title`; ошибка одного провайдера не мешает другому. `isComplete(track)` —
есть альбом, обложка и текст.

## Маппинг данных

| Поле `Track` | Internet Archive | Jamendo |
|---|---|---|
| `id` | `archive:<identifier>:<fileName>` | `jamendo:<id>` |
| `title` | `file.title` → `doc.title` → имя файла | `name` |
| `artist` | `creator` → «Internet Archive» | `artist_name` → «Неизвестный исполнитель» |
| `album` | `doc.title` / item title | `album_name` |
| `durationMs` | `length` (секунды или `3:45`) | `duration` × 1000 |
| `uri` | `archive.org/download/<id>/<file>` | `audiodownload` → `audio` (только https) |
| `artworkUrl` | `archive.org/services/img/<id>` | `image` |
| `playable` / `downloadAllowed` | `true` / `true` | `true` / `true` |
| `deepLink` | `archive.org/details/<id>` | `jamendo.com/track/<id>` |

`extra` в обоих случаях содержит `fileName`, `license`, `licenseUrl`; у Archive
дополнительно `identifier` и `year`, у Jamendo — `streamUrl` и `tags`.

## Типовые сбои

| Симптом | Причина | Поведение Sonora |
|---|---|---|
| Archive: «Ничего не найдено» | нет результатов / нет сети | пустой список + текст об ошибке в UI |
| Archive: часть результатов пропала | `/metadata/<id>` вернул 404/битый JSON | айтем пропускается, остальные видны |
| Archive: трек не играет | внутри айтема выбран не тот файл | deep link на айтем, можно открыть вручную |
| Jamendo: источник недоступен | нет `jamendoClientId` | чип с подсказкой, где взять ключ |
| Jamendo: пусто | неверный client_id (`headers.status=failed`) | пустой список + `setupHint()` |
| Jamendo: пусто | `429` — рейт-лимит | пустой список, повторить позже |
| MusicBrainz: нет альбома/года | запись не найдена | `null` по этой части, текст может прийти |
| MusicBrainz: `503` | перегрузка сервиса | одна повторная попытка, затем пусто |
| LRCLIB: нет текста | инструментал / нет в базе | `lyrics = null`, остальные поля сохраняются |
| Cover Art Archive: 404 | у релиза нет обложки | Coil показывает заглушку |
| Любой источник: битый JSON | смена формата API | исключение ловится, пустой список |

## Ограничения

- Скачивание разрешено только там, где источник сам отдаёт файл
  (`downloadAllowed = true`); Spotify/YouTube/SoundCloud в Discover не участвуют.
- Массовое скачивание не поддерживается: один трек — одно явное действие.
- Публичные API не дают SLA: ответы разбираются защитно, сетевые ошибки не
  пробрасываются в UI.
