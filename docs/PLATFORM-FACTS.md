# Факты о платформах: Spotify, YouTube Music, SoundCloud, Metrolist

> **Актуально на 26.10.2026, но с оговоркой:** разделы про YouTube описывают состояние
> до перехода Sonora на InnerTube. Сейчас приложение использует InnerTubeX из Metrolist
> (GPL-3.0) для поиска и воспроизведения. Исторический разбор ниже оставлен как справка
> о публичных API и ограничениях платформ.

Состояние на **26.10.2026**. Документ фиксирует, что технически и юридически доступно стороннему Android-приложению, и почему «войти в три сервиса и бесплатно стримить/качать всё» нереализуемо легально.

Обозначения: ✅ — разрешено и поддержано; ⚠️ — разрешено частично/с ограничениями; ⛔ — нарушает ToS или технически невозможно.
Пометка **не подтверждено** — факт не удалось подтвердить первоисточником (часть страниц `developer.spotify.com` рендерится клиентским JS, дословные цитаты недоступны).

---

## 1. Spotify

### 1.1. Что даёт Web API

Web API — это **каталог и библиотека, а не транспорт аудио**. Доступны метаданные треков, альбомов, артистов, плейлистов, сохранённых треков (`user-library-read`), поиск, управление плейлистами; OAuth-скоупы перечислены в [документации по scopes](https://developer.spotify.com/documentation/web-api/concepts/scopes).

`preview_url` — 30-секундный MP3-превью. С **27.11.2024** для новых приложений он удалён из мульти-ответов (`SimpleTrack`), вместе с ещё шестью категориями данных: Related Artists, Recommendations, Audio Features, Audio Analysis, Featured Playlists, Category Playlists, алгоритмические/редакционные плейлисты ([Introducing some changes to our Web API, 27.11.2024](https://developer.spotify.com/blog/2024-11-27-changes-to-the-web-api)). Приложения с ранее выданным extended-quota доступом не затронуты.

### 1.2. Изменения 2026 года (важно для планирования)

| Дата | Изменение |
|---|---|
| **11.02.2026** | Новые Development Mode Client ID создаются по новым правилам ([Update on Developer Access and Platform Security, 06.02.2026](https://developer.spotify.com/blog/2026-02-06-update-on-developer-access-and-platform-security)) |
| **09.03.2026** | Те же правила применяются к существующим Development Mode интеграциям; сужение набора endpoint'ов для существующих приложений **отложено** (апдейт в том же посте) |
| **23.07.2026** | Лимит Client ID поднят с 1 до 25; квота считается на аккаунт разработчика, а не на приложение; 429 содержит `reason: QUOTA_EXCEEDED` ([Web API quota updates, 23.07.2026](https://developer.spotify.com/blog/2026-07-23-web-api-quota-updates)) |

Правила Development Mode после февраля 2026: **обязателен Spotify Premium у разработчика**, до 5 авторизованных пользователей на Client ID, доступ только к сокращённому набору endpoint'ов ([блог](https://developer.spotify.com/blog/2026-02-06-update-on-developer-access-and-platform-security), [migration guide](https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide), [changelog февраля 2026](https://developer.spotify.com/documentation/web-api/references/changes/february-2026)). Development Mode прямо объявлен песочницей «для обучения и личных некоммерческих проектов», а не фундаментом продукта.

Критерии extended quota (production) обновлялись отдельно 15.04.2025 ([блог](https://developer.spotify.com/blog/2025-04-15-updating-the-criteria-for-web-api-extended-access)) — коммерческое использование требует соответствия этим критериям и ревью Spotify.

### 1.3. Правила воспроизведения

- **Web Playback SDK** — браузерная JS-библиотека: создаёт устройство `browser` и проигрывает треки внутри страницы. Требует **Spotify Premium**; аудиоданные приложению не отдаются ([Web Playback SDK](https://developer.spotify.com/documentation/web-playback-sdk)). В Android WebView работоспособность не гарантирована (не подтверждено).
- **Spotify Android SDK / App Remote** — только **управление установленным приложением Spotify**: play/pause/seek, метаданные, запуск воспроизведения; скоуп `app-remote-control`, аудио остаётся в клиенте Spotify ([репозиторий SDK](https://github.com/spotify/android-sdk), [Android SDK 0.8.0+, 06.07.2023](https://developer.spotify.com/blog/2023-07-06-new-android-sdk-version)). Воспроизведение «по требованию» требует Premium; SDK не является плеером и не даёт доступа к потоку.
- **Mobile streaming SDK** (2014–2019), позволявшие приложению стримить самостоятельно, **отключены 01.09.2022** ([An update on the deprecated mobile streaming SDKs](https://developer.spotify.com/blog/2022-07-15-mobile-streaming-sdks-update)).
- **Embeds / iframe-плеер** — официальный способ показать плеер внутри приложения/WebView ([Embeds](https://developer.spotify.com/documentation/embeds)); это по-прежнему плеер Spotify, а не поток в вашем коде.

### 1.4. Почему стриминг и скачивание внутри своего приложения невозможны

- В Web API **нет endpoint'а**, отдающего аудиофайл или аудиопоток: только метаданные и (ограниченно) 30-секундные превью ([список изменений](https://developer.spotify.com/blog/2024-11-27-changes-to-the-web-api)).
- Поток защищён DRM: в вебе воспроизведение идёт через Encrypted Media Extensions и CDM (Widevine) ([W3C EME](https://www.w3.org/TR/encrypted-media/)); Spotify официально не документирует свою DRM-схему — **не подтверждено** официально, но независимые работы используют Spotify как эталон DRM-защиты ([arXiv:2103.16360](https://arxiv.org/abs/2103.16360)).
- Обход технических средств защиты запрещён законом: DMCA §1201 (США), ст. 1299 ГК РФ, Directive 2001/29/EC; плюс [Developer Terms](https://developer.spotify.com/terms) и [Developer Policy](https://developer.spotify.com/policy) (ред. 15.05.2025) запрещают обход DRM, скачивание контента вне функций сервиса и ре-дистрибуцию.
- Официальное офлайн-скачивание существует **только в приложении Spotify** для Premium-подписчиков и недоступно сторонним приложениям.
- Вывод: единственный легальный сценарий — **делегировать воспроизведение официальному клиенту** (App Remote / Web Playback SDK / embed), а своё приложение использовать как каталог и пульт.

---

## 2. YouTube и YouTube Music

### 2.1. Публичный API: только метаданные

**YouTube Data API v3** покрывает видео, каналы, плейлисты, подписки, комментарии, поиск. Ресурсы: `activities`, `captions`, `channels`, `comments`, `playlistItems`, `playlists`, `search`, `subscriptions`, `videos` и др. ([API Reference](https://developers.google.com/youtube/data/reference/rest)). Аудиопотоков и «музыкальной библиотеки» там нет.

Квоты (по состоянию на 15.10.2026): по умолчанию **10 000 units/день** на все endpoint'ы, плюс отдельные бакеты: **100 вызовов `search.list`/день** и **100 `videos.insert`/день**, каждый вызов стоит 1 unit ([Quota Calculator](https://developers.google.com/youtube/data/determine_quota_cost)). Дневная квота сбрасывается в полночь по PT.

**Отдельного публичного API у YouTube Music нет.** Data API v3 — API YouTube (видео), а не YouTube Music; недокументированные внутренние API (`music.youtube.com`/InnerTube) публичными не являются (не подтверждено как «официально отсутствует» — просто нет публичной документации; см. также запрет на недокументированные API ниже).

**YouTube ToS** (ред. 05.01.2022): смотреть/слушать контент разрешено только в личных некоммерческих целях; запрещено скачивать, копировать, распространять, обходить ограничения и системы защиты; воспроизведение через встраиваемый плеер разрешено ([Условия использования](https://www.youtube.com/t/terms)).

### 2.2. Встраиваемый плеер — единственный легальный способ воспроизведения

**IFrame Player API / embedded player** ([справка](https://developers.google.com/youtube/iframe_api_reference)) — официальный путь. Ограничения из [Required Minimum Functionality](https://developers.google.com/youtube/terms/required-minimum-functionality):

- плеер виден, минимум **200×200 px**; нельзя перекрывать/скрывать плеер и его контролы оверлеями;
- один автоплей на страницу, автоплей только когда плеер виден;
- для WebView нужен `HTTP Referer`: Android — `WebView` с `loadDataWithBaseURL`/заголовком `Referer` или `CustomTabs` c `Intent.EXTRA_REFERRER`; использовать системный WebView;
- менять параметры/внешний вид плеера сверх документации нельзя.

**Developer Policies** ([ссылка](https://developers.google.com/youtube/terms/developer-policies)) прямо запрещают: скачивать, импортировать, кэшировать, бэкапить или хранить копии аудиовизуального контента без письменного разрешения; делать контент доступным офлайн; использовать недокументированные API и реверс-инжинирить их; ограничивать или ухудшать функции YouTube (включая рекламу); смешивать результаты поиска YouTube с другими источниками. **API Services ToS** ([ссылка](https://developers.google.com/youtube/terms/api-services-terms-of-service), ред. 14.09.2026) добавляет запрет на обход квот и на использование контента вне API-сервисов.

Отсюда: **фоновое аудио, извлечение audio-only, блокировка рекламы, скачивание — нарушение ToS**, даже если технически осуществимы через WebView/парсинг.

### 2.3. Неофициальные клиенты: постоянная гонка

- С ноября 2023 YouTube ведёт глобальную кампанию против блокировщиков рекламы ([The Verge / FoneArena, 01.11.2023](https://www.fonearena.com/blog/409630/youtube-ad-blocker-crackdown-expands-globally.html)).
- 2024–2026: YouTube ужесточил целостность клиента (SABR-стриминг, PoToken, шифрование `n`-параметра). Пример: трекер yt-dlp фиксирует переход YouTube Music на `sabr=1` ([yt-dlp issue #13037](https://github.com/yt-dlp/yt-dlp/issues/13037)).
- Патчи ReVanced вынуждены постоянно добавлять «spoof client / spoof player parameter» ([issue #2832](https://github.com/inotia00/ReVanced_Extended/issues/2832)), и они периодически перестают работать ([issue #3167](https://github.com/inotia00/ReVanced_Extended/issues/3167)).
- Скачивание контента YouTube нарушает ToS и авторские права; в РФ — ст. 1270 ГК РФ (воспроизведение/переработка) и ст. 1299 ГК РФ (обход защиты).

---

## 3. SoundCloud

### 3.1. Статус API для новых разработчиков

Регистрация приложений открыта (раздел «Get an API key» помечен как новый), но с 2025–2026 появился барьер: **для регистрации API-приложения требуется подписка SoundCloud Artist Pro**, затем CLI (remote pairing) или веб-форма выдаёт `client_id`/`client_secret` ([Get an API key](https://developers.soundcloud.com/docs/api/register-app)). Более высокие лимиты — только по отдельному одобрению SoundCloud ([Rate Limits](https://developers.soundcloud.com/docs/api/rate-limits)). API Terms of Use действуют с **30.03.2024** ([ссылка](https://developers.soundcloud.com/docs/api/terms-of-use)).

Аутентификация — OAuth 2.1 + PKCE; два потока: Authorization Code (действия от имени пользователя) и Client Credentials (публичные ресурсы). Важно для Android: сейчас **все клиенты считаются confidential** и обязаны хранить `client_secret`, т.е. секрет нельзя безопасно держать в APK — нужен собственный бэкенд ([API Guide](https://developers.soundcloud.com/docs/api/guide)).

### 3.2. Что разрешено

- **Стриминг**: `stream_url` трека / `GET /tracks/{id}/stream`; плеер воспроизводит транскодированный поток (AAC). Уровни доступа: `playable` — полностью, `preview` — фрагмент, `blocked` — только метаданные ([Restricted Access](https://developers.soundcloud.com/docs/api/guide)).
- **Поиск, плейлисты, лайки, подписки** — есть.
- **Атрибуция обязательна**: указать автора, SoundCloud как источник и ссылку на трек ([Terms of Use, раздел Attribution](https://developers.soundcloud.com/docs/api/terms-of-use)).
- **Рейт-лимиты**: ~100 запросов/мин общий бюджет; playback `GET /tracks/:id/stream(s)` — 1500/24 ч; поиск — 3000/ч; `GET /tracks/:id/download` — 10/мин ([Rate Limits](https://developers.soundcloud.com/docs/api/rate-limits)).

### 3.3. Что запрещено

- **Скачивание и офлайн**: API ToU требует, чтобы приложение **не имело функции сохранения файлов** и не кэшировало контент дольше сессии; офлайн-доступ запрещён **независимо от того, разрешил ли артист скачивание** ([Caching and Downloading](https://developers.soundcloud.com/docs/api/terms-of-use)). Endpoint `/download` существует, но приложение всё равно не вправе делать файлы постоянными.
- **SoundCloud Go+**: доступ через API запрещён; приложение для Go+-контента требует отдельного письменного соглашения ([SoundCloud Go Content](https://developers.soundcloud.com/docs/api/terms-of-use)).
- **Агрегация с другими сервисами**: запрещено создавать «alternative digital content service», в частности сервис, который агрегирует и стримит контент нескольких пользователей в on-demand-сервис, **или опыт воспроизведения, объединяющий SoundCloud с контентом других сервисов (пример в тексте: SoundCloud с YouTube)** ([Content and Privacy](https://developers.soundcloud.com/docs/api/terms-of-use)). Это прямой запрет на «одну библиотеку» в одном плеере.
- Прочее: stream ripping, обход ограничений, реверс-инжиниринг, эмуляция функций SoundCloud, продажа доступа.

---

## 4. Metrolist

**Что это:** неофициальный open-source клиент YouTube Music для Android — [github.com/metrolistgroup/metrolist](https://github.com/MetrolistGroup/Metrolist). Лицензия **GPL-3.0** ([LICENSE](https://github.com/MetrolistGroup/Metrolist/blob/main/LICENSE)); текущая версия **13.7.0** (versionCode 153, `compileSdk 37`, `minSdk 26`, см. [app/build.gradle.kts](https://github.com/MetrolistGroup/Metrolist/blob/main/app/build.gradle.kts)). Проект переведён в **maintenance mode**: только багфиксы, новые фичи не принимаются ([README](https://github.com/MetrolistGroup/Metrolist/blob/main/README.md)).

**Что внутри:** собственный модуль `:innertube` — клиент **недокументированного внутреннего API YouTube (InnerTube)**, плюс Media3/ExoPlayer. README прямо указывает зависимость `zemer-cipher` — «YouTube cipher deobfuscation and PoToken generation», т.е. обход защиты потока и генерация proof-of-origin токенов. Заявленные функции: стриминг любой песни, фоновое воспроизведение, **скачивание и кэш для офлайна**, вход в аккаунт YouTube Music, синхронизация библиотеки, обход региональных ограничений через VPN/прокси.

**Риски:**
- Нарушает [Developer Policies III.D.7](https://developers.google.com/youtube/terms/developer-policies) (недокументированные API, реверс) и YouTube ToS (скачивание, audio-only, обход защиты) — это не «серая зона», а явный запрет.
- Операционно нестабилен по определению: любые изменения InnerTube/PoToken/SABR ломают воспроизведение; проект уже в maintenance mode.
- Распространение: не Google Play, а Obtainium/IzzyOnDroid/GitHub; риск блокировок и претензий правообладателей (DMCA-жалобы на форки типичны — **не подтверждено** для конкретно Metrolist).
- GPL-3.0: переиспользование кода в закрытом продукте невозможно; форк обязывает раскрывать исходники.

**Вывод:** Metrolist — показатель спроса и пример архитектуры (InnerTube + ExoPlayer + PoToken), но **не фундамент** для легального продукта.

---

## 5. Сравнительная таблица возможностей стороннего Android-приложения

| Возможность | Spotify | YouTube / YouTube Music | SoundCloud |
|---|---|---|---|
| Просмотр своей библиотеки | ✅ Web API (`user-library-read`, плейлисты) | ⚠️ Data API: подписки/плейлисты/liked видео; библиотеку YouTube Music публичный API не покрывает | ✅ OAuth `/me`, лайки, плейлисты |
| Поиск | ✅ Web API | ✅ Data API (бакет 100 `search.list`/день) | ✅ `/tracks?q=` |
| Полный стриминг треков | ⚠️ только через плеер Spotify (Web Playback SDK / App Remote), нужен Premium | ⚠️ только встраиваемый IFrame-плеер, с рекламой и видимым видео | ✅ `stream_url` + своя атрибуция; Go+ — ⛔ |
| Фоновое воспроизведение | ⚠️ только официальным клиентом Spotify | ⛔ RMF требует видимый плеер; извлечение аудио запрещено | ✅ разрешено (свой плеер) |
| Скачивание | ⛔ нет endpoint'а, DRM, запрет ToS | ⛔ запрещено ToS/политиками | ⛔ ToU запрещает file-save/офлайн |
| Официальная поддержка | ✅ Web API + SDK (с лимитами) | ✅ Data API + IFrame API | ✅ API (нужен Artist Pro) |
| **Вердикт** | **легально, но ограничено**: каталог/библиотека — да; воспроизведение — только через клиент Spotify | **легально, но ограничено**: метаданные — да; воспроизведение — только официальный embedded-плеер; фон/скачивание/блокировка рекламы — нарушение ToS | **легально, но ограничено**: стриминг и поиск — да (Artist Pro + бэкенд); офлайн и агрегация с другими сервисами — нарушение ToU |

Легенда вердиктов: **«легально и поддержано»** — есть публичный API/SDK под этот сценарий; **«легально, но ограничено»** — сценарий возможен только через официальный компонент платформы или с существенными ограничениями; **«нарушает ToS»** — прямой запрет в договоре, независимо от технической осуществимости.

---

## 6. Легальные способы собрать «одну библиотеку»

1. **Локальные файлы** — основа: `MediaStore`/SAF, FLAC/MP3/Opus, теги + обложки, при желании MusicBrainz/AcoustID для дедупликации. Это единственный контент, который приложение вправе хранить и проигрывать в фоне без ограничений платформ.
2. **Покупки с правом скачивания** — Bandcamp (покупка даёт файлы; страницы артистов/альбомов открываются, официального публичного API для покупок нет — **не подтверждено**), Jamendo API (Creative Commons), Free Music Archive, каталоги CC0/CC-BY.
3. **Собственные копии** — CD-рипы, ранее купленные MP3/AAC, архивы лейблов; импорт в локальную библиотеку.
4. **Handoff вместо проксирования**: Spotify — App Remote/Web Playback SDK (Premium); YouTube — IFrame-плеер в видимом окне; SoundCloud — свой плеер по `stream_url` с атрибуцией. Едиными остаются **индекс, поиск, плейлисты, очередь и метаданные**, а аудио всегда воспроизводит компонент платформы.
5. **Перенос плейлистов** (TuneMyMusic, Soundiiz и т.п.) — легально переносит только списки треков, а не аудио; полезен для миграции между сервисами.
6. **Self-hosted** — Navidrome/Music Assistant/Subsonic-совместимый сервер для своей фонотеки; даёт «одно приложение» для всего, что вы реально имеете право хранить.
7. **Чего делать нельзя**: объединять стримы разных сервисов в один плеер с единым аудиопотоком — это прямо запрещено ToU SoundCloud (агрегация с другими сервисами) и политиками YouTube; обход DRM Spotify — нарушение закона.

---

## Вывод для проектирования

1. «Одна библиотека» = локальный индекс метаданных и плейлистов + делегирование воспроизведения официальным компонентам платформ; собственный аудиопоток из Spotify/YouTube невозможен легально.
2. Spotify: Web API — каталог/библиотека (Development Mode: Premium, ≤5 пользователей, урезанные endpoint'ы); воспроизведение — App Remote или Web Playback SDK при Premium; скачивание — вне закона и вне API.
3. YouTube: Data API — только метаданные при 10 000 units/день; единственный легальный плеер — IFrame с видимым окном и рекламой; фон, audio-only, скачивание, блокировка рекламы — нарушение ToS; публичного API YouTube Music нет.
4. SoundCloud: API даёт поиск и стриминг с атрибуцией, но требует Artist Pro и бэкенд для хранения секрета; офлайн-кэш и агрегация с другими сервисами запрещены ToU.
5. Metrolist (GPLv3, InnerTube + PoToken) подтверждает техническую возможность неофициального клиента, но это нарушение ToS, нестабильно и непригодно как основа продукта; безопасная альтернатива — локальные файлы, покупки с правом скачивания и handoff в официальные плееры.
