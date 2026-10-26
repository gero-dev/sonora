# Архитектура Sonora

## 1. Принцип

Приложение — это **индекс и плеер**, а не прокси для чужих аудиопотоков.
Всё, что можно хранить и играть законно (локальные файлы), играет само приложение;
всё, что принадлежит платформам, остаётся у платформ: Sonora показывает метаданные,
ведёт очередь и делает handoff в официальный клиент.

```
┌──────────────────────────────────────────────────────────────┐
│ Compose UI (screens, components)                             │
│   Home / Search / Library / Player / Queue / Settings / …    │
└───────────────▲───────────────────────────┬──────────────────┘
                │ StateFlow<...>            │ действия
┌───────────────┴───────────────────────────▼──────────────────┐
│ MainViewModel                                                │
│   tracks, playlists, favorites, player, search, connections,  │
│   settings, syncing                                          │
└───────┬───────────────────┬───────────────────┬──────────────┘
        │                   │                   │
┌───────▼────────┐  ┌───────▼────────┐  ┌───────▼──────────────┐
│ LibraryRepo    │  │ SourceRegistry │  │ PlaybackController   │
│ MediaStore     │  │ Spotify / YT / │  │ MediaController      │
│ JSON в filesDir│  │ SoundCloud     │  │ (MediaSession)       │
└────────────────┘  └───────┬────────┘  └───────┬──────────────┘
                            │                   │ binder
                    ┌───────▼────────┐  ┌───────▼──────────────┐
                    │ OkHttp + API   │  │ PlaybackService      │
                    │ OAuth PKCE     │  │ ExoPlayer + MediaSession
                    └────────────────┘  └──────────────────────┘
```

Один процесс, один `AppGraph` (ручной DI). Сервис воспроизведения живёт в том же процессе,
поэтому эквалайзер управляется через `AudioEffectBus` (прямые ссылки на `Equalizer`
невозможны между процессами).

## 2. Модель данных

Единый `Track` для всех источников, `id` кодирует источник:

| Источник | id | uri | playable |
|---|---|---|---|
| Локальный файл | `local:<mediaId>` | `content://media/external/audio/media/<id>` | всегда `true` |
| Spotify | `spotify:<trackId>` | `preview_url` (может быть `null`) | `preview_url != null` |
| YouTube | `youtube:<videoId>` | `innertube:<videoId>` (резолвится в прямой URL) | `true` |
| SoundCloud | `soundcloud:<trackId>` | `null` | `false` |
| Internet Archive | `archive:<identifier>:<file>` | `https://archive.org/download/...` | `true` |
| Jamendo | `jamendo:<id>` | `audiodownload` / `audio` | `true` |

`ARCHIVE`/`JAMENDO` — единственные внешние источники с `playable = true`: это свободные
записи, которые авторы разрешили скачивать, поэтому их можно и стримить, и сохранять.

Если `playable == false`, `MainViewModel.playOrOpen(track)` открывает `track.deepLink`
в официальном приложении. Это единственная точка «перехода», UI о ней не думает.

`Playlist.remoteId` — идентификатор в сервисе (`"liked"` для Spotify «Мне нравится»),
`Playlist.trackIds` — локальные id в кэше.

## 3. Хранение

| Что | Где | Формат |
|---|---|---|
| Настройки | SharedPreferences `sonora_settings` | JSON-строка `AppSettings` |
| OAuth-токены | EncryptedSharedPreferences `sonora_secure` | ключи `spotify.access`, `spotify.refresh`, … |
| Библиотека | `filesDir/library_tracks.json`, `library_playlists.json`, `library_favorites.json` | JSON через `kotlinx.serialization` |
| Скачанные/импортированные файлы | `getExternalFilesDir(MUSIC)/Sonora` | файлы |

Запись атомарная: `JsonFile.write()` пишет `*.tmp` и переименовывает.

## 4. Воспроизведение

- `PlaybackService : MediaSessionService` создаёт `ExoPlayer` и `MediaSession`.
  Благодаря этому работают уведомление, экран блокировки, наушники и Android Auto.
- Источник данных — `InnerTubeDataSourceFactory`: URI `innertube:<videoId>` на лету
  заменяется прямым аудио-URL InnerTubeX (кэш `InnerTubeStreams`, TTL по `expire`).
- UI работает с `PlaybackController`, который держит `MediaController` (binder к сервису)
  и публикует `StateFlow<PlayerUiState>`.
- `PlayerUiState` — снимок: `isPlaying`, `positionMs`, `durationMs`, `index`, `queue`,
  `shuffle`, `repeatMode`, `speed`, `sleepEndsAt`. Позиция обновляется таймером 500 мс,
  только когда играет.
- Non-playable элементы в очередь не попадают (`playQueue` фильтрует `playable && uri != null`).
- Эквалайзер: `Equalizer(0, player.audioSessionId)` создаётся в сервисе при первом
  `onIsPlayingChanged(true)`, параметры приходят из `AudioEffectBus`.
- Таймер сна реализован корутиной в `PlaybackController`: по истечении — `pause()`.

## 5. OAuth (PKCE, без клиентского секрета)

```
UI: «Войти» → vm.connect(source) → source.authUrl()
   → AppLinks.open(браузер/Custom Tabs)
   → пользователь подтверждает
   → редирект sonora://oauth/<service>?code=...&state=...
   → MainActivity.handleDeepLink → AppGraph.handleOAuth → source.handleCallback(uri)
   → обмен code → access/refresh в SecureStore
   → vm.refreshConnections()
```

- `code_verifier` и `state` кладутся в `SecureStore` перед открытием браузера и проверяются
  при возврате; `state` защищает от CSRF.
- Токен обновляется за 60 секунд до истечения; при 401 — один принудительный refresh и повтор.
- Client Secret не используется нигде. Spotify — публичный клиент, SoundCloud — только
  `client_id` для чтения публичных данных (полноценный OAuth у них требует бэкенда).

## 6. Поиск и синхронизация

- `SourceRegistry.searchAll()` — параллельный `async` по подключённым источникам
  с общим таймаутом 15 с; ошибка одного источника не роняет выдачу.
- Локальный поиск — подстрочный по title/artist/album. Параллельно `YouTubeSource.search()`
  ищет в InnerTube; результаты идут отдельной секцией «YouTube Music · InnerTube».
- `MainViewModel.syncSource()`: `libraryPlaylists()` → кэш плейлистов → до 25 плейлистов
  `playlistTracks()` → `cacheRemoteTracks()`. Результат — офлайн-просмотр метаданных.
- Дебаунс поиска 350 мс, предыдущая задача отменяется.

### 6.1. Открытые каталоги (Обзор)

```
DiscoverScreen ──► MainViewModel.discover ──► DiscoverRepository
                                                 ├── ArchiveDiscoverSource (archive.org, без ключа)
                                                 └── JamendoDiscoverSource  (нужен client_id)
```

- `DiscoverRepository.search(source, query)` — один каталог; `searchAll` — по всем доступным.
- `ArchiveDiscoverSource`: `advancedsearch.php` (сначала только музыкальные коллекции
  `netlabels/audio_music/opensource_audio`, при пустом результате — весь `mediatype:(audio)`),
  затем `/metadata/<id>` для каждого айтема (Semaphore 4, кэш на процесс, ≤24 айтема за поиск).
  Из айтема берётся до 6 аудиофайлов — часто это целый альбом.
- `JamendoDiscoverSource`: `/v3.0/tracks` с `audioformat=mp32`; треки, у которых
  `audiodownload_allowed = false`, пропускаются (скачивание действительно разрешено).
- Результаты **не кэшируются в библиотеку**: они живут только на экране «Обзор».
  Скачивание → `DownloadManager.download()` → файл в `Music/Sonora` → `scanLocal()`
  → трек появляется в медиатеке как обычный локальный (`source = LOCAL`), без дублей.

### 6.2. Онлайн-метаданные и текст песни

```
MainViewModel.enrichLibrary() ──► MetadataService.lookup(track)
                                     ├── MusicBrainz /ws/2/recording  (1 rps, UA обязателен)
                                     ├── Cover Art Archive /release/<id>/front-500
                                     ├── iTunes Search /search?entity=song      (обложка 1000)
                                     ├── Deezer /search + /artist/<id>           (обложка/фото)
                                     ├── TheAudioDB searchtrack/searchartist     (фото/био/жанр)
                                     ├── LRCLIB /api/search           (plainLyrics / syncedLyrics)
                                     ├── Wikipedia EN/RU (если нет фото/био автора)
                                     └── InnerTube search (если нет обложки/автора)
        └── LibraryRepository.updateTrackMetadata()
              → Track(album, artist, artworkUrl, lyrics,
                      extra[artistImageUrl, artistImageUri, artistBio, genre, year])
```

- Основные провайдеры идут параллельно и изолированы; у каждого таймаут 12 с
  (Wikipedia — 15 с). TheAudioDB после 403/401 отключается до перезапуска.
- **Обложка**: Deezer (1000) → TheAudioDB → iTunes (1000) → Cover Art Archive (500)
  → InnerTube (1000). Маленькое YouTube-превью (`=w120`, `=s120`) заменяется;
  встроенная обложка локального файла не затирается.
- **Автор**: MusicBrainz artist-credit → TheAudioDB → Deezer → iTunes → InnerTube →
  очищенное текущее имя. `ArtistNames` убирает `- Topic`, `VEVO`, `Official` и
  группирует имена без регистра, диакритики и ведущего «The».
- **Фото/биография автора**: TheAudioDB → Deezer `picture_xl` → Wikipedia;
  биография — TheAudioDB → Wikipedia. Для скачанных треков фото кэшируется в
  `filesDir/covers` (`artistImageUri`).
- **Альбом/год**: iTunes → Deezer → TheAudioDB → MusicBrainz → InnerTube.
  Внутри MusicBrainz выбирается официальный студийный альбом; сборники и
  концертники штрафуются.
- Заполняются **только пустые** поля, но имя канала и маленькая обложка считаются
  «пустыми» и улучшаются. Ручные теги не затираются.
- Тексты фильтруются по названию/исполнителю и длительности (±20 % или 15 с);
  LRC сохраняется в `Track.syncedLyrics`, обычный текст — в `Track.lyrics`.
- Подробный пользовательский и технический гайд — [METADATA.md](METADATA.md).

#### Постоянный кэш метаданных

```
MetadataService.lookup(track)
   ├─ MetadataCache.get("исполнитель|название")
   │    └─ свежая запись (≤30 дней, schemaVersion ≥ 2) → вернуть сразу, без сети
   ├─ провайдеры (см. выше)
   └─ MetadataCache.put(...) — сразу, атомарно, в filesDir/metadata_cache.json
```

- `CachedMetadata` хранит album, year, coverUrl, lyrics, syncedLyrics, musicBrainzId,
  artist, title, artistImageUrl, artistBio, genre, provider, `fetchedAt`,
  `attempts`, `failed`, `schemaVersion`.
- **Сетевые сбои не кэшируются**: результат пишется, только если хотя бы один
  провайдер реально ответил; иначе при следующем обогащении будет ещё одна попытка.
- Отрицательный результат кэшируется на **6 часов**, успешный — на 30 дней.
- `CachedMetadata.schemaVersion = 2`; старые записи один раз перезапрашиваются при
  обновлении. Дополнительно `METADATA_SCHEMA_VERSION = 4` в `MainViewModel`
  сбрасывает отметки `metadataCheckedAt`, чтобы библиотека прошла обогащение заново.
- `metadataCandidates()` пропускает треки, проверенные менее 24 часов назад, и
  включает треки без альбома, обложки, автора, фото/биографии или текста.
- «Очистить кэш метаданных» удаляет `metadata_cache.json` и сбрасывает отметки.

### 6.3. YouTube Music на InnerTube

```
SearchScreen → vm.onSearchQueryChanged()
   → YouTubeSource.search()
       → InnerTubeApi.search(): POST youtubei/v1/search (WEB_REMIX, фильтр «песни»)
       → Track(source=YOUTUBE, playable=true, uri=innertube:<videoId>)
   → playOrOpen() → ExoPlayer
       → InnerTubeDataSourceFactory: innertube: → InnerTubeStreams.resolve()
           → InnerTubeXEngine (InnerTubeX): перебор клиентов, cipher/SABR
           → прямой аудио-URL googlevideo
       → appendYouTubeRelated(): youtubei/v1/next + automix → очередь
```

- Ключи, OAuth и квоты Data API не используются; аккаунт не нужен.
- Плейлист импортируется по ссылке (`?list=…`) или из Takeout: `youtubei/v1/browse`
  с `browseId = VL…` и продолжениями.
- Тексты: вкладка Lyrics из `next` → `browse` (`MPLYt…`) → `musicDescriptionShelfRenderer`.
- PoToken: `TokenProvider` в `InnerTubeXEngine` берёт WebView-токены из
  `potoken/PoTokenGenerator` (BotGuard, порт из Metrolist); при недоступном WebView
  движок откатывается на клиентов без токена.
- **Вход в Google:** `GoogleLoginScreen` (WebView) → cookie `music.youtube.com` →
  `InnerTubeSession` (SecureStore, SAPISIDHASH, visitorData/dataSyncId/authUser) →
  `FEmusic_liked_playlists` и `LM` (понравившиеся).
- **Скачивание:** `vm.downloadYouTube()` → `InnerTubeStreams.resolveDownload()`
  (`AudioQuality.MP4`) → `DownloadManager` (приватная папка) + обложка рядом →
  `LibraryRepository.upsertLocalTracks()` → `scanLocal()`; в `extra` остаются
  `youtubeId` и `downloadedTrackId`.
- **Моя волна:** `MainViewModel.buildWave()` — только музыка: отсекаются клипы, концерты,
  интервью и стримы (маркеры в названии), ролики короче 45 с и записи длиннее 12 минут.
  Порядок — взвешенный случайный (любимые треки/исполнители и частые артисты получают
  больший вес), затем дедупликация по `id` и `artist|title` без скобочных пометок,
  лимит двух треков на исполнителя в начале подборки, смешивание локального и онлайна ~60/40.
  Сортировки (`Для вас`/название/исполнитель/длительность/перемешать) применяются после отбора.
- **Тексты:** `LyricsSheet` интерполирует `positionMs` по `positionSampledAtMs`
  (60 fps), крупная типографика, затухающие края, автоскролл с ручной паузой.
- InnerTubeX (GPL-3.0) — единственная тяжёлая зависимость; см. `THIRD-PARTY-NOTICES.md`.

### 6.4. Виджет рабочего стола

- `SonoraWidgetProvider` — `onUpdate`/`onAppWidgetOptionsChanged` → `SonoraWidget.updateAll`;
  кастомные action'ы `PLAY_PAUSE/NEXT/PREVIOUS` вызывают методы `AppGraph.get(context).playback`.
- Набор виджетов: отдельные провайдеры с фиксированной раскладкой (`SonoraWidget2x2Provider`,
  `SonoraWidget4x1Provider`, `SonoraWidget4x2Provider`, `SonoraWidget4x4Provider`) плюс
  legacy-провайдер, который сам выбирает раскладку по размеру. Все наследуют
  `BaseSonoraWidgetProvider`, действия кнопок обрабатываются в одном месте.
- Обложка: Coil в `Dispatchers.IO` → `Bitmap` → `RemoteViews.setImageViewBitmap`; кэш
  `ConcurrentHashMap<trackId, Bitmap>`, множества `loading`/`failed`, чтобы обновление
  прогресса раз в секунду не перезагружало картинку.
- `PlaybackController.refresh()` — единственный источник обновлений: виджет не опрашивает
  сервис, обновление не чаще раза в секунду.
- Последний трек (название, исполнитель, прогресс, URL обложки) сохраняется в
  `SharedPreferences("sonora_widget")`, поэтому после смерти процесса виджет показывает его.

### 6.5. Встроенный YouTube Music (WebView)

- Экран `YouTubeWebScreen` (`Routes.YOUTUBE_WEB`) — обычный `WebView` с `music.youtube.com`:
  JS, DOM storage, сторонние cookies (для входа Google), воспроизведение после жеста.
- Мы **не перехватываем** медиапоток, не блокируем рекламу и не извлекаем аудио: страница
  работает как в браузере. Аппаратная «назад» сначала листает историю WebView.
- Google может отказать во входе внутри WebView — ограничение на стороне Google.

### 6.6. Импорт Google Takeout

```
Settings → «Импорт библиотеки из Google Takeout»
   → SAF (OpenDocument, */*) → TakeoutImporter.import(uri)
       ├─ ZIP → ZipInputStream → все *.csv
       └─ CSV → потоковый RFC4180-парсер
   → колонки распознаются по заголовкам (Song Title / Artist / Album / Video ID / Playlist)
   → Track(source=YOUTUBE, playable=false, deepLink=watch|search) + Playlist(source=YOUTUBE)
   → LibraryRepository.cacheRemoteTracks/cacheRemotePlaylists
   → Медиатека → вкладка «YouTube»
```

- Лимиты: 200 000 строк, 10 000 треков, 200 плейлистов; дубликаты отбрасываются по id.
- Строки с Video ID получают `innertube:<videoId>` и играются; без id остаётся только deep link.

### 6.7. Синхронизированные тексты

- `LyricsSheet` парсит LRC (`[mm:ss.xx]`), игнорирует метатеги, сортирует строки.
- `positionMs` из `PlayerUiState` → активная строка = последняя с `timeMs <= positionMs`;
  `LazyColumn` автоматически скроллит к ней (пауза автоскролла 3 с после ручного скролла).
- Тап по строке вызывает `vm.seekTo(timeMs)` — как в Spotify.
- Если таймкодов нет, показывается обычный текст без подсветки.

### 6.8. Жесты, авто- и бесконечная очередь

- `PlayerScreen`: `detectDragGestures` на корневом Box. Порог 88 dp; приоритет —
  ось с большим смещением: ←/→ `next()/previous()`, ↓ `onBack()`, ↑ очередь.
  Обложка следует за пальцем через `graphicsLayer` (translation + небольшой поворот),
  двойной тап — play/pause.
- `MainViewModel.playOrOpen()` → `buildQueueFor(seed)`: seed → 2 трека того же альбома →
  перемешанные «тот же исполнитель + остальная фонотека», до `autoQueueSize` (25).
  Хвост перемешивается при каждом запуске.
- Бесконечная очередь: `observeInfiniteQueue()` слушает `PlayerUiState`; при
  `index >= queue.size - 2` вызывается `buildRandomBatch()` (2 трека от исполнителя +
  перемешанный пул без последних 8 сыгранных; при маленькой библиотеке — перемешанная
  фонотека с повторами) → `PlaybackController.appendToQueue()`.
- Страховка для кнопки «вперёд»: `PlaybackController.queueExtender` (выставляет
  `MainViewModel`) вызывается, если `hasNextMediaItem()` == false; батч добавляется и
  переключение выполняется. Так «вперёд» не залипает, даже если фоновое расширение
  по StateFlow не успело.
- После обогащения метаданных `applyMetadata()` обновляет медиа-элементы очереди
  (кроме текущего, чтобы не сбить позицию) и `PlayerUiState.queue`, поэтому плеер,
  мини-плеер и очередь показывают новые обложки/теги сразу.

### 6.9. Сохранение любого трека

```
PlayerScreen / TrackActionSheet
   → MainViewModel.downloadTrack(track)
      ├─ Source.YOUTUBE или uri = innertube:<id> → downloadYouTube()
      │    → InnerTubeStreams.resolveDownload(AudioQuality.MP4)
      │    → DownloadManager.download(url, headers, mime)
      │    → DownloadManager.downloadArtwork()
      │    → MetadataService.lookup() + InnerTube lyrics
      │
      └─ прямой HTTPS-поток → DownloadManager.download(url, fileName, mime)
   → Track(source=LOCAL, uri=file://…, extra[downloadedTrackId, downloadedFrom])
   → LibraryRepository.upsertLocalTracks() + scanLocal()
```

- `downloadTrack()` — единая точка входа; UI не знает, откуда трек. InnerTube
  маршрутизируется в `downloadYouTube()`, локальные треки сразу получают статус
  «Уже на устройстве», остальные требуют `playable = true` и HTTPS-URI.
- `DownloadManager` поддерживает заголовки (для `googlevideo`), MIME, Wi-Fi-only,
  `.part`, уникальные имена и прогресс через `StateFlow`.
- `trackDownloads` — общий прогресс для плеера и списков; `youtubeDownloads` —
  дополнительный поток для вкладки «YouTube». Статусы: «Скачивание», «Готово»,
  «Ошибка», «Уже скачано», «Недоступно».
- `isTrackDownloaded()` ищет локальную копию по `downloadedTrackId` или
  `youtubeId`, поэтому повторное скачивание блокируется и после перезапуска.
- Локальная копия получает `source = LOCAL`, `playable = true`, обложку и
  метаданные; `mergeScanned()` при пересканировании не затирает их.
- DRM, HLS-сегменты и полные треки Spotify/SoundCloud не поддерживаются.
  Подробный пользовательский гайд — [DOWNLOADS.md](DOWNLOADS.md).

### 6.10. Импорт списка треков из файла

```
Settings → «Импорт из других платформ»
   → SAF OpenDocument (*/*) → PlaylistFileImporter.read(uri)
       ├─ CSV/TSV → RFC4180-подобный парсер, заголовки RU/EN
       ├─ JSON    → Spotify items[].track, Apple data[].attributes, массивы
       ├─ M3U     → #EXTINF + ссылки
       └─ TXT     → «Артист — Название» / просто название
   → MainViewModel.importPlaylistFile()
       → для каждой строки: локальная библиотека (score ≥ 3) → Video ID/URL →
         InnerTube search(query, 5) → matchScore()
       → LibraryRepository.cacheRemoteTracks()
       → LibraryRepository.createPlaylist("Импорт: <файл>", trackIds)
```

- `PlaylistFileParser` — чистый Kotlin-парсер без Android-зависимостей;
  unit-тесты: `PlaylistFileParserTest` (Spotify CSV/JSON, Apple JSON, M3U, TXT,
  дедупликация, лимиты).
- `matchScore()`: точное название 3, частичное 1; точный исполнитель 2,
  частичный 1; длительность ±10 с ещё 1. Порог — 3.
- Лимиты: 150 треков за импорт, 1 000 распознанных записей, 8 МБ на файл.
- Плейлист создаётся только из найденных треков; ненайденные пропускаются,
  итог показывается в настройках.

## 7. Потоки (UI)

- `MainViewModel` — единственная ViewModel; экраны получают её и `PaddingValues` + `navigate`.
- Навигация: 5 табов (`home/search/discover/library/settings`) + полноэкранные `player`,
  `queue`, `youtube_web` + детальные `playlist/{id}`, `album/{name}/{artist}`, `artist/{name}`.
- Мини-плеер над нижней навигацией, скрыт на полноэкранных маршрутах.

## 8. Точки расширения

1. **Spotify App Remote** — управление официальным клиентом (play/pause/seek) без своего аудио.
2. **YouTube IFrame** — WebView с видимым плеером ≥200×200 и рекламой (единственный легальный
   способ играть YouTube внутри приложения; фон и audio-only запрещены).
3. **SAF-импорт папок** и полноценный менеджер скачиваний для своей музыки (FLAC/Opus).
4. **Last.fm scrobbling** (ключ уже есть в настройках, осталось реализовать).
5. **Android Auto** — `MediaSessionService` уже совместим, нужен `automotive`-манифест.
6. **Тесты**: unit на мапперы API, Compose UI tests на экраны.

## 9. InnerTube

YouTube Music работает через недокументированный InnerTube — тот же протокол, что
используют Metrolist и другие открытые клиенты. Поиск, плейлисты, очередь и тексты
разбираются в `data/remote/youtube/innertube/`; поток отдаёт InnerTubeX (GPL-3.0):
перебор клиентов, деобфускация подписи и `n`-параметра, SABR и контракты PoToken.

Риски осознанны: протокол неофициальный и может измениться в любой момент. Поэтому
движок вынесен за интерфейс `InnerTubeStreams`, а при его ошибке остаётся fallback
на прямые аудио-URL и встроенный WebView `music.youtube.com` для личной библиотеки.
