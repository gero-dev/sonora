# Интеграции Sonora: Spotify, YouTube Music, SoundCloud

Состояние на **26.10.2026**. Документ описывает, что каждая интеграция реально
делает, как её настроить и почему часть функций принципиально недоступна.
Технические первоисточники — в [PLATFORM-FACTS.md](PLATFORM-FACTS.md).

## Главный принцип

Sonora — плеер **своей** музыки. Внешние сервисы подключены только через
официальные API и InnerTube; метаданные, очередь и воспроизведение. Приложение
не обходит DRM и не скрапит страницы. Если платформа не разрешает
воспроизведение, трек честно помечается `playable = false`, получает
`deepLink` и открывается в приложении сервиса. `downloadAllowed` по умолчанию
`false` у сетевых треков; у YouTube он включается для личного офлайн-скачивания.

## Что поддерживается

| Функция | Сервис | Статус | Почему |
|---|---|---|---|
| Вход в аккаунт | Spotify | да, OAuth 2.0 + PKCE | Web API поддерживает публичные клиенты без client_secret |
| Свои плейлисты и «Мне нравится» | Spotify | да | скоупы `user-library-read`, `playlist-read-*` |
| Поиск по каталогу | Spotify | да | `GET /v1/search` |
| Воспроизведение трека | Spotify | только 30-сек. превью | полных треков Web API не отдаёт, стриминг-транспорт закрыт |
| Скачивание музыки Spotify | Spotify | только 30-сек. превью | полные треки защищены DRM; превью можно сохранить в приватную папку |
| Поиск по каталогу | YouTube | да, без ключа | InnerTube `search` (WEB_REMIX, фильтр «песни») |
| Импорт публичного плейлиста по ссылке | YouTube | да | InnerTube `browse` (`VL…`) + продолжения |
| Импорт списка треков из файла | Spotify / Apple / YouTube | да | CSV/TSV, JSON, M3U/M3U8, TXT → поиск в библиотеке и InnerTube |
| Своя библиотека YouTube Music | YouTube | да, после входа | WebView-логин → cookie/SAPISIDHASH; `FEmusic_liked_playlists`, `LM` |
| Воспроизведение трека | YouTube | да | InnerTubeX: cipher/SABR → прямой аудио-URL → ExoPlayer |
| Скачивание | YouTube | да, в приватную папку | Поток InnerTubeX; обложка и метаданные сохраняются рядом, файлы не публикуются |
| Поиск по каталогу | SoundCloud | да, при наличии client_id | `GET /search/tracks` |
| resolve ссылки / публичные плейлисты | SoundCloud | да | `GET /resolve`, `GET /playlists/{id}` |
| Своя библиотека, лайки | SoundCloud | нет | нужен confidential OAuth + backend (client_secret) |
| Воспроизведение и скачивание | SoundCloud | нет | ToU запрещает агрегацию с другими сервисами и file-save; стриминг требует Artist Pro и серверного confidential-клиента |
| Импорт своих файлов и скачивание свободного контента | локально | да | файлы пользователя, CC, явно разрешённые ссылки |
| Метаданные, обложки, фото и биография автора | каталоги | да | MusicBrainz, CAA, iTunes, Deezer, TheAudioDB, Wikipedia, InnerTube |

## Spotify

**Что делает:** вход через браузер (Custom Tabs), чтение профиля, плейлистов,
сохранённых треков, поиск, воспроизведение `preview_url`, если Spotify его
вернул. Превью — 30-секундный MP3; у приложений, созданных после 27.11.2024,
`preview_url` обычно отсутствует в мульти-ответах, поэтому `playable` часто
`false`, и трек открывается в Spotify.

**Настройка:**
1. <https://developer.spotify.com/dashboard> → **Create app**.
2. Redirect URI — ровно `sonora://oauth/spotify` (схема `sonora`, host `oauth`,
   путь `spotify`; иначе Spotify вернёт `INVALID_CLIENT: Invalid redirect URI`).
3. Скопировать **Client ID** (client secret не нужен и не должен попадать в APK).
4. Вставить Client ID в Sonora: Настройки → Spotify Client ID.
5. Development Mode ограничен: авторам нужен Spotify Premium, до 5
   авторизованных пользователей на Client ID, сокращённый набор endpoint'ов.
   Для продакшена требуется extended quota и ревью Spotify.

**Токены:** access/refresh token и `expires_at` хранятся в `SecureStore`
(EncryptedSharedPreferences). Access-токен обновляется автоматически, если до
истечения меньше 60 секунд; при 401 — один принудительный refresh и один
повтор запроса. `state` и `code_verifier` (PKCE S256) тоже в SecureStore;
`client_secret` нет вообще.

**Ограничения и ошибки:** 401 → «Сессия истекла, подключите заново»;
403 на `/tracks` редакционного плейлиста → fallback на `/playlists/{id}`;
429 (`reason: QUOTA_EXCEEDED` с 23.07.2026) → «слишком много запросов»;
сетевые/JSON-ошибки не роняют UI: пустой результат + `lastError`.

## YouTube Music

**Что делает:** поиск, плейлисты, очередь «дальше», тексты и воспроизведение через
InnerTube (`music.youtube.com/youtubei/v1/*`). Это тот же неофициальный протокол,
что в Metrolist. Треки создаются как `playable = true`, `uri = innertube:<videoId>`;
ExoPlayer резолвит URI в прямой аудио-URL через InnerTubeX (GPL-3.0).
Скачивание (`downloadAllowed = true`) сохраняет MP4-поток в приватную папку
приложения, рядом — обложку; локальный трек получает `youtubeId` в `extra`.

**Настройки не нужны для поиска:** ключи API, OAuth и квоты Data API удалены из приложения.
Для личной библиотеки есть **вход в Google через WebView** (`GoogleLoginScreen`):
cookie и SAPISIDHASH передаются в InnerTube, как в Metrolist.

**Плейлисты:** импорт по ссылке (`?list=…`, `PL…`, `OLAK5uy_…`) или из Google Takeout;
после входа `libraryPlaylists()` читает `FEmusic_liked_playlists`, а `LM` — «Понравившиеся».

**Ошибки:** 403/429 → «YouTube ограничил доступ, попробуйте позже»; ошибки парсинга
не роняют UI — пустая выдача + `lastError`. Если поток не извлекается, остаётся
fallback на прямые аудио-URL и веб-плеер.

**Лицензия:** InnerTubeX распространяется под GPL-3.0, поэтому Sonora тоже GPL-3.0;
см. [THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md).

## SoundCloud

**Что делает:** поиск по каталогу и чтение публичных плейлистов/ссылок через
`api.soundcloud.com` с пользовательским `client_id`. Всё, что получено, —
**только метаданные**: `uri = null`, `playable = false`,
`downloadAllowed = false`, `deepLink = permalink_url`. Поля `stream_url` и
`download_url` не читаются вообще, `client_id` к медиа-ссылкам не добавляется.

**Почему так:**
- API Terms of Use (30.03.2024) запрещают сохранение файлов и офлайн-кэш дольше
  сессии — независимо от того, разрешил ли артист скачивание;
- Go+ контент через API запрещён без отдельного письменного соглашения;
- ToU запрещают «агрегацию» — опыт воспроизведения, объединяющий SoundCloud с
  контентом других сервисов;
- новые API-ключи требуют SoundCloud Artist Pro, а все клиенты сейчас
  confidential: нужен `client_secret` на сервере, в APK его держать нельзя.

**Настройка:** получить существующий `client_id` (Artist Pro + регистрация
приложения) и вставить в Настройки → SoundCloud Client ID. Вход по OAuth и
библиотека пользователя не реализуются — для них нужен собственный backend.
Без `client_id` источник доступен только как переход в приложение SoundCloud.

**Ошибки:** 401/403 → пустой результат и явная подсказка «SoundCloud отклонил
client_id, проверьте ключ»; 429 — рейт-лимит; сеть/JSON — пустой результат +
`lastError`. Кэш результатов живёт только в памяти процесса.

## Метаданные и обложки

`MetadataService` опрашивает MusicBrainz, Cover Art Archive, iTunes, Deezer,
TheAudioDB, Wikipedia и (при необходимости) InnerTube. Каждый провайдер
изолирован и ограничен таймаутом; TheAudioDB отключается после 403/401.
Обложка выбирается по размеру и надёжности, автор — по каноническому
artist-credit, фото/биография берутся из TheAudioDB, Deezer или Wikipedia.
`ArtistNames` нормализует `- Topic`, `VEVO`, `Official`, регистр и диакритику.
Подробности — [METADATA.md](METADATA.md).

## DownloadManager

Единый конвейер сохранения для треков, которые приложение умеет играть:

- InnerTube/YouTube Music — MP4-поток через InnerTubeX, с заголовками
  `googlevideo`;
- Internet Archive и Jamendo — оригинальный файл с открытой лицензией;
- прямые HTTPS-ссылки — ответ сервера как файл;
- Spotify — только 30-секундное превью, если оно есть.

Полные треки Spotify, SoundCloud и DRM-контент не сохраняются; HLS/DASH-сегменты
не собираются. Для InnerTube файл и обложка кладутся в приватную папку, локальный
трек получает `youtubeId` и `downloadedTrackId`; для прямых ссылок —
`downloadedFrom` и `downloadedTrackId`.

Папка: `getExternalFilesDir(Music)/<downloadFolder>` (по умолчанию `Sonora`),
fallback — `filesDir`. Поддерживается `settings.downloadWifiOnly` (ошибка
«Только Wi-Fi»), уникальные имена файлов, `.part`, прогресс в `StateFlow`,
удаление и подсчёт размера.

## Импорт списков

`PlaylistFileImporter` + `PlaylistFileParser` читают CSV/TSV, JSON (Spotify,
Apple Music), M3U/M3U8 и TXT. `MainViewModel.importPlaylistFile()` ищет каждый
трек сначала в локальной библиотеке, затем в InnerTube, кэширует найденные
онлайн-треки и создаёт плейлист «Импорт: имя файла». Это импорт **метаданных**,
а не аудио: полные треки Spotify/SoundCloud не скачиваются. Лимиты: 150 треков
за раз, 1 000 записей, 8 МБ. Подробности — [IMPORT.md](IMPORT.md).

## Хранение секретов

Все токены — только в `SecureStore` (`EncryptedSharedPreferences`, при сбое
keystore — обычные prefs, чтобы приложение не падало). Ключи: `spotify.access`,
`spotify.refresh`, `spotify.expires_at`, `spotify.user`, `spotify.verifier`,
`spotify.state`. В `SettingsStore` лежат только не-секреты: Spotify Client ID,
SoundCloud/Jamendo client_id. Секретов в репозитории и в APK нет; бэкенда у приложения нет.
Ключей YouTube в настройках больше нет — InnerTube работает анонимно.

## Типовые сбои

| Симптом | Причина | Поведение Sonora |
|---|---|---|
| Spotify: пустой экран после входа | не совпал Redirect URI | `lastError` + подсказка про `sonora://oauth/spotify` |
| Spotify: 401 после успешного входа | отозван refresh token | один refresh, затем «подключите заново» |
| Spotify: трек не играет | нет `preview_url` | `playable=false`, кнопка «Открыть в Spotify» |
| YouTube: 403/429 | YouTube ограничил доступ | понятный текст, пустой список |
| YouTube: пустой поток | протокол InnerTube изменился | fallback на прямой URL/веб-плеер, ошибка в `lastError` |
| SoundCloud: 401/403 | неверный/отозванный client_id | пустой список + setupHint |
| Любой источник: malformed JSON | битый ответ API | исключение ловится, пустой список, `lastError` |
| Нет сети | офлайн | пустой список + ошибка, приложение не падает |
