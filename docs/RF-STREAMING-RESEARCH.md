# Стриминг YouTube-аудио в РФ без VPN/WARP — исследование и рекомендации

**Дата:** 29.10.2026, 18:04–18:45 МСК (Europe/Moscow)
**Автор:** rf-researcher
**Статус:** исследование + замеры; код приложения не менялся.
**Связанные документы:** [PLAYBACK.md](PLAYBACK.md), [WARP.md](WARP.md), [YTDLP_INTEGRATION.md](YTDLP_INTEGRATION.md), [LEGAL.md](LEGAL.md).

---

## 0. Краткое резюме (TL;DR)

1. **Блокировка многослойная, и «одного флага» не существует.** 10.02.2026 `youtube.com` убрали из НСДН — DNS-серверы провайдеров перестали возвращать адреса. До этого, с июля 2024, ТСПУ душили сам видеопоток (`googlevideo`). Отдельно с 2026 года Cloudflare официально сообщает о троттлинге «до ~16 КБ на соединение» для российских абонентов — это убивает почти все публичные зеркала, которые стоят за Cloudflare.
2. **403 на `googlevideo` — это в первую очередь привязка подписи к IP и блокировка IP, а не «плохой клиент».** Ни `player_client`, ни `--force-ipv4`, ни cookies не превращают заблокированный IP в рабочий. Флаги лечат только «версионные» 403 (устаревший yt-dlp, сломанный nsig, отсутствие PO-токена).
3. **Встроенный yt-dlp действительно устарел:** AAR `library:0.18.1` везёт сборку **2025.11.12** (проверено распаковкой `res/raw/ytdlp`), тогда как актуальная на 29.10.2026 — **2026.10.26**. Обновление рантайма из GitHub реализуемо и в РФ (`api.github.com` отвечает 200 с российских сетей), QuickJS (`libqjs.so`) поддержан и библиотека сама передаёт `--js-runtimes quickjs:…`. **Обновление необходимо, но недостаточно.**
4. **Публичные релеи в 2026 практически непригодны.** Из 20 Invidious-инстансов ни один стабильно не отдал аудио; официальный список `api.invidious.io/instances.json` содержит сейчас всего 6 https-инстансов, половина из них мертва. Piped API отвечает 200, но `audioStreams` пустой (`0`). Cobalt требует JWT-ключ (`error.api.auth.jwt.missing`). Единственный инстанс, который реально отдал `audio/mp4` байты — `yt.chocolatemoo53.com`, и он с российских проб недоступен (таймаут).
5. **Вывод:** без смены egress (VPN/WARP/свой шлюз) надёжного стриминга в РФ не будет. Для приложения это означает: (а) держать публичные зеркала только как «best-effort» fallback с жёсткой валидацией и коротким TTL; (б) добавить **свой шлюз** (VPS вне РФ, не за Cloudflare) и/или поле «свой инстанс» в настройках; (в) прямые пути (InnerTube/yt-dlp) оставить основными и не ставить релей на критический путь.

---

## 1. Что именно заблокировано в РФ (состояние на конец октября 2026)

### 1.1. Хронология по источникам

| Дата | Событие | Источник |
|---|---|---|
| конец июля 2024 | Начало «замедления» YouTube: главная открывается, видео не играет | [Moscow Times, 10.02.2026](https://ru.themoscowtimes.com/2026/02/10/roskomnadzor-pereshel-kpolnoi-blokirovke-youtube-posle-polutora-let-zamedleniya-a186865) |
| 19.12.2024 | РКН признал, что сам ограничил YouTube | там же |
| ~09–10.02.2026 | `youtube.com` удалён из Национальной системы доменных имён (НСДН); DNS-серверы провайдеров перестали возвращать IP | [Moscow Times](https://ru.themoscowtimes.com/2026/02/10/roskomnadzor-pereshel-kpolnoi-blokirovke-youtube-posle-polutora-let-zamedleniya-a186865), [URA.RU](https://ura.news/news/1053067812), [3DNews](https://3dnews.ru/1136656/rkn-pereshyol-k-polnoy-blokirovke-youtube-spustya-15-goda-zamedleniya-eksperti-svyazali-eto-s-merami-protiv-telegram/) |
| 23.04.2026 | Cloudflare: российские провайдеры системно душат трафик к Cloudflare-сайтам, ~16 КБ на соединение | [Cloudflare Docs](https://developers.cloudflare.com/support/troubleshooting/general-troubleshooting/service-disruption/) |
| 2026 | 281-ФЗ: административная ответственность за рекламу VPN и за использование VPN для поиска экстремистских материалов | [ГАРАНТ](https://base.garant.ru/412423444/) |

Ключевая цитата из экспертов (Moscow Times): блокировка сделана «грубо, но надёжно» через НСДН, при этом **вся инфраструктура YouTube, включая CDN, продолжает работать**, и сервис доступен через альтернативный DNS или VPN. То есть на уровне IP глобального «выключения» Google нет — но и одного DNS-обхода недостаточно: ТСПУ-фильтрация и троттлинг Cloudflare остаются.

### 1.2. Уровни блокировки: что именно ломает наше приложение

| Слой | Что происходит | Как проявляется в Sonora | Лечится ли клиентским флагом |
|---|---|---|---|
| **DNS (НСДН)** | `youtube.com` не резолвится через DNS провайдера | ошибка резолва, `UnknownHostException`; InnerTube/yt-dlp не стартуют | да, если приложение само ходит в DoH/свой резолвер — но это лишь первый слой |
| **ТСПУ / DPI** | фильтрация/замедление TLS к `*.googlevideo.com`, часть Google-префиксов режется | медиа-запрос висит, обрывается, иногда 403 | **нет** |
| **IP-репутация / блок** | `googlevideo` подписывает URL под IP+клиент+PO-токен; заблокированный/«плохой» IP получает 403 на медиа | `HTTP Error 403: Forbidden` уже после получения форматов | **нет** (только смена egress) |
| **PO token** | часть клиентов отдаёт форматы только с Proof-of-Origin токеном | 403 на 1080p+, пустой список форматов, «requires a PO token» в `-v` | частично: выбор клиента без PO-требований |
| **Cloudflare-троттлинг** | ~16 КБ на соединение → TLS-handshake проходит, тело не едет | зеркало «отвечает 200», но поток встаёт через пару секунд | нет (только не-Cloudflare хост) |
| **IPv6** | у части провайдеров IPv6-путь живой, у части — нет; YouTube оценивает весь /64 как один адрес | на одном протоколе 403, на другом играет | частично: `--force-ipv4` / `--force-ipv6` перебор |

Практический вывод: **если медиа IP-заблокировано, никакой клиентский флаг не поможет — нужен другой egress** (VPN/WARP или релей/шлюз). Это честная граница, ниже в §6 она сформулирована отдельно.

---

## 2. yt-dlp в Android: что реально лежит в `library:0.18.1`

### 2.1. Разбор AAR (Gradle cache)

Путь: `…\.gradle\caches\modules-2\files-2.1\io.github.junkfood02.youtubedl-android\library\0.18.1\…\library-0.18.1.aar` (59,2 МБ).

| Компонент | Факт |
|---|---|
| `jni/arm64-v8a/libpython.zip.so` | 14,3 МБ — **CPython 3.12** + `site-packages`: только `Cryptodome`, `mutagen`. **Пакета `yt_dlp` здесь нет.** |
| `jni/*/libqjs.so` | QuickJS: 916 КБ (arm64), 635 КБ (v7a), 950 КБ (x86), 982 КБ (x86_64) |
| `res/raw/ytdlp` | 3 170 726 Б — zipapp yt-dlp; внутри `yt_dlp/version.py`: **`__version__ = '2025.11.12'`**, канал stable, head `335653be…` |
| `classes.jar` | 24 класса: `YoutubeDL`, `YoutubeDLUpdater`, `YoutubeDLRequest`, `UpdateChannel{STABLE,NIGHTLY,MASTER}`, `VideoInfo`, … |
| EJS (js-челленджи) | В zipapp 2025.11.12 уже есть `yt_dlp/extractor/youtube/jsc/_builtin/vendor/yt.solver.core.js` и `yt_dlp_ejs 0.3.1`. В актуальном 2026.10.26 — `yt_dlp_ejs 0.8.0` и полный `yt_dlp_ejs/yt/solver/core.min.js`. |

**Вывод:** версия yt-dlp не «зашита» в код библиотеки — она лежит ресурсом и **обновляется в рантайме**.

### 2.2. API обновления: `updateYoutubeDL(context, UpdateChannel)`

Проверено по классам в AAR и по исходникам форка (yausername/youtubedl-android, junkfood02):

- Каналы: `STABLE` → `https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest`, `NIGHTLY` → `yt-dlp-nightly-builds`, `MASTER` → `yt-dlp-master-builds`.
- `YoutubeDLUpdater.update()`: GET релиза по API → берёт asset с именем ровно `yt-dlp` (`browser_download_url`) → качает во временный файл (timeout 5/10 с) → **удаляет старую папку `noBackupFilesDir/youtubedl-android/yt-dlp/`** и кладёт новый zipapp → пишет `dlpVersion`/`dlpVersionName` в SharedPreferences. При ошибке — откат на встроенную сборку.
- `YoutubeDL.version(context)` возвращает текущий тег, `versionName()` — имя релиза.
- Таким образом: **да, `YoutubeDL.update(context, UpdateChannel.NIGHTLY)` работает и в РФ**, если доступен `api.github.com` (см. замеры: 200 с трёх российских сетей, 118–166 мс).
- **Лучше не использовать API GitHub вообще:** анонимный лимит `api.github.com` — 60 запросов/час на IP, а `checkForUpdate()` дергает API при каждой попытке. Надёжнее скачивать ассет напрямую (проверено: так же отдаёт zipapp 2026.10.26):
  - stable: `https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp`
  - nightly: `https://github.com/yt-dlp/yt-dlp-nightly-builds/releases/latest/download/yt-dlp`
  Имя ассета всегда `yt-dlp`, JSON-релиз не нужен; версию после распаковки читать через `YoutubeDL.version(context)` (или из `yt_dlp/version.py` внутри zipapp). Такой URL не лимитирован API и переживает 60/h.
- RF-зеркала/прокси для GitHub в рамках этого исследования **не тестировались** — не обещать их в UI; если понадобятся, добавлять как пользовательскую настройку «зеркало обновлений».

### 2.3. JS-рантайм: QuickJS — это то, что нужно в 2026

`execute()` в библиотеке **сама** добавляет `--js-runtimes quickjs:<abs path>/libqjs.so` (строка есть в `YoutubeDL.class`), плюс `--ffmpeg-location`, `--no-cache-dir`. Значит:
- nsig/JS-челленджи решаются QuickJS-рантаймом — внешний Deno/Node не нужен (yt-dlp 2025.11.12 и 2026.10.26 поддерживают `quickjs`, минимальная версия 2023-12-09);
- EJS-скрипты у 2026.10.26 **входят в zipapp**, отдельная установка `yt-dlp-ejs` не требуется;
- PO-token провайдер (`bgutil-ytdlp-pot-provider`) в Android-приложении недоступен (нужен внешний сервис/JS-окружение) — это ограничение, а не баг сборки.

### 2.4. Чего библиотека НЕ делает

| Не делает | Следствие | Что делать в приложении |
|---|---|---|
| не задаёт `--force-ipv4` | на «кривом» IPv6 — 403/таймаут | перебор `--force-ipv4`/`--force-ipv6` (уже есть в `YtDlpResolver.buildAttempts`) |
| не задаёт `--extractor-args youtube:player_client=…` | дефолтный набор клиентов не всегда даёт играбельный URL | перебор наборов клиентов (уже есть `clientSets`) |
| не задаёт `--no-check-certificates` | на DPI с MITM/подменой сертификата — падение TLS | опция в настройках (осознанно; риск) |
| не обновляет `yt_dlp_ejs` отдельно | при смене схемы челленджей старый zipapp ломается | канал **NIGHTLY**, обновление раз в сутки/по кнопке |
| не проверяет, что URL реально играет | 403 приходит уже в плеере | `StreamProbe` Range-запрос до отдачи в Media3 (уже есть) |

### 2.5. Рекомендуемые флаги/аргументы yt-dlp для 2026 (без Deno, на QuickJS)

Порядок попыток (от дешёвой к дорогой; каждая проверяется Range-запросом):

1. `-f bestaudio/best --dump-json --no-playlist --no-warnings --no-progress --socket-timeout 10`
2. `--extractor-args "youtube:player_client=default,tv_simply"` → затем `tv,android_vr` → `web_safari,web_embedded` → `ios,mweb` → `android,web`.
   - по [PO Token Guide](https://github.com/yt-dlp/yt-dlp/wiki/PO-Token-Guide) PO-токен **не требуется** для `tv`, `tv_simply`*, `web_embedded`, `android_vr` (*`tv_simply` требует GVS-токен по таблице, `android_vr` не отдаёт «Made for kids»);
   - `web`/`mweb`/`web_music`/`android`/`ios` — требуют PO-токен для GVS (в Android-приложении его взять негде, поэтому они в конце списка).
3. `--force-ipv4` (потом `--force-ipv6`, если IPv4 не даёт — см. §1.2).
4. `--no-cache-dir` (библиотека и так добавляет) — устаревший кэш подписей даёт 403 на каждом видео.
5. `--remote-components ejs:github` — **не обязателен** для zipapp 2026.10.26 (EJS внутри), но полезен, если в будущем EJS вынесут наружу; требует доступ к GitHub.
6. `--no-check-certificates` — только как аварийная опция в настройках (DPI/корпоративный MITM).
7. `--proxy http://host:port` — если пользователь ввёл свой прокси; это фактически «свой egress» и самый надёжный путь.
8. Cookies — **в последнюю очередь**: решают «Sign in to confirm you're not a bot», но могут создавать медиа-403 и тянут приватность/ToS.

---

## 3. Замеры

### 3.1. Методика и важное предупреждение

- **Тестовая машина** — Windows, Москва, но **весь egress идёт через Cloudflare WARP**: `ip-api.com` → `country=RU`, `isp=Cloudflare WARP`, `AS13335`, `104.28.230.244`; интерфейс `CloudflareWARP` в статусе Up; DNS `127.0.2.2`. Отключить WARP в рамках исследования было нельзя (системная настройка), поэтому **локальные тесты показывают только «работает ли релей вообще», а не то, что видит российский абонент без VPN.**
- Дополнительный перекос: WARP-адрес — датацентровый IP Cloudflare, поэтому Cloudflare-фронтованные зеркала отвечают нам `403`/CAPTCHA (бот-защита). Это занижает локальные результаты для части хостов.
- **Российские точки замера** — Globalping API (`api.globalping.io`), пробы в РФ: **AS9123 Timeweb, AS49505 Selectel, AS50340 Selectel, AS200350 Yandex.Cloud, AS208677 Cloud.ru, AS29182 IOT, AS48347 Mediasoft**. Это **датацентровые сети, а не домашние провайдеры**: у них свои резолверы (поэтому НСДН-блок в DNS не виден) и другой профиль ТСПУ. Результаты — индикативны для «российского сегмента», но не заменяют тест на телефоне с SIM-картой.
- Время: локальный скан 18:05:39 МСК; серии Globalping 18:10–18:40 МСК.
- Сырые результаты: `docs/ci/relay-probe-results.json`, `docs/ci/globalping-results.json`, `docs/ci/invidious-audio-scan.json`; скрипты — `docs/ci/*.py`.

### 3.2. Таблица RF-проб: инфраструктура YouTube

| Проверка | Timeweb | Selectel | Yandex.Cloud | Cloud.ru | IOT / Mediasoft |
|---|---|---|---|---|---|
| DNS `youtube.com` | OK (142.251.1.190) | OK (142.250.150.190) | OK (173.194.221.136) | — | OK (142.250.178.110) |
| DNS `googlevideo.com` | OK (64.233.162.147) | OK (142.251.142.228) | OK (64.233.161.104) | — | OK (172.253.152.105) |
| DNS `youtubei.googleapis.com` | OK (172.217.119.4) | OK | OK | — | OK |
| DNS `api.github.com` | OK (140.82.121.6) | OK | OK | — | — |
| HTTP `https://www.youtube.com/` | **200**, 315 мс | 200 (AS50340, 296 мс) / таймаут 10,5 с (AS49505) | — | — | fail 2 мс (IOT) |
| HTTP `youtubei/v1/player` (GET) | **405** (доступен), 289 мс | таймаут 10,1 с | — | таймаут 15 с | — |
| HTTP `https://googlevideo.com/` | **302**, 176 мс | **302**, 163 мс | — | — | таймаут 15 с |
| HTTP `https://api.github.com/.../releases/latest` | **200** (5937 Б), 118 мс | **200** (5555 Б), 131 мс | — | — | 200 (AS50340, 166 мс) |
| IP+SNI `74.125.131.93` (`www.youtube.com`) | таймаут 10,2 с | таймаут 10,3 с | — | — | — |
| IP+SNI `74.125.173.198` (`rr1---sn-…googlevideo.com`) | **404**, 158 мс | таймаут 15 с | — | — | — |
| IP+SNI `172.217.119.4` (`youtubei`) | **405**, 258 мс | таймаут 15 с | — | — | — |

**Как читать:** DNS-блок НСДН в датацентрах не проявляется (свои резолверы) — то есть «в лоб» из РФ youtube.com иногда резолвится и даже отдаёт HTML. Но доставка нестабильна и зависит от ASN: часть проб висит по 10–15 с, часть Google-префиксов недоступна. Это ровно тот профиль, который в приложении выглядит как «иногда играет, чаще нет».

### 3.3. Таблица RF-проб: доступность релеев (root/API)

| Хост | Timeweb | Selectel | Комментарий |
|---|---|---|---|
| `piped-api.piped.tokhmi.xyz` | **200** 363 мс | **200** 270 мс | но TLS-сертификат не совпадает с хостом → **непригоден** (см. 3.5) |
| `pipedapi.ducks.party` | **200/302** 212 мс | **200/302** 142 мс | API живой, аудио нет (3.5) |
| `api.piped.private.coffee` | **200/302** 2,03 с | таймаут 6 с | медленно |
| `api.cobalt.tools` | 200 (Cloudflare) | 200 (Cloudflare) | требует JWT |
| `cobalt-api.meowing.de` | 200 (Cloudflare) | 200 (Cloudflare) | требует JWT |
| `tunelio.dev` | 200 (Cloudflare) | 200 (Cloudflare) | платный API |
| `api.invidious.io/instances.json` | **200** 484 мс | **200** 381 мс | автообновление списка зеркал из РФ возможно |
| `inv.nadeko.net` | таймаут 15 с | fail 293 мс | «Endpoint disabled» |
| `yewtu.be` | таймаут 15 с | fail 140 мс | Cloudflare |
| `invidious.nerdvpn.de` | fail 873 мс | fail 796 мс | Cloudflare/TLS |
| `invidious.f5.si` | 530 | fail | Cloudflare 1033 |
| `iv.melmac.space` | fail | fail | TLS hostname mismatch |
| `invidious.jing.rocks` | fail 1,6 с | fail | DNS не резолвится |
| `yt.artemislena.eu` | fail 362 мс | таймаут | TLS internal error |
| `invidious.reallyaweso.me` | таймаут | таймаут | Cloudflare |
| `invidious.materialio.us` | таймаут | fail | TLS handshake timeout |
| `inv.tux.pizza` | таймаут | таймаут | Cloudflare |
| `iv.ggtyler.dev` | fail 2,3 с | fail 2,3 с | — |
| `invidious.lunivers.trade` | 503 | 503 | — |
| `invidious.nikkosphere.com` | 451 | 451 | юридическая блокировка владельцем |
| `invidious.protokolla.fi` | 200 | fail | CAPTCHA-страница на API |
| `yt.chocolatemoo53.com` | таймаут 15 с | fail 211 мс | **единственный, кто отдал аудио локально** (3.5) |
| `inv.us.projectsegfau.lt` | таймаут 15 с | таймаут 15 с | — |

### 3.4. Таблица RF-проб: аудио-эндпоинты (Range 0–65535)

| Хост / эндпоинт | Результат из РФ | Вердикт |
|---|---|---|
| `invidious.protokolla.fi/latest_version?id=…&itag=140&local=true` | 206, 1,9 с (Timeweb); на повторе — таймаут 15 с | **не подтверждён**: локально тот же запрос вернул `200 text/html` (CAPTCHA) — «206» был частичным HTML, а не аудио |
| `inv.nadeko.net/...`, `yewtu.be/...`, `invidious.materialio.us/...`, `inv.tux.pizza/...`, `invidious.reallyaweso.me/...` | таймауты 15 с / обрывы | мертво |
| `invidious.f5.si/...` | 530 | мертво |
| `invidious.einfachzocken.eu/...` | 404 | мертво |
| `yt.chocolatemoo53.com/...` | таймаут (Timeweb), fail 211 мс (Selectel) | **недоступен из РФ**, хотя локально отдаёт аудио |
| Piped `/streams/<id>` (ducks.party, tokhmi.xyz, private.coffee) | 200 JSON | API отвечает, но **аудиопотоков нет** (см. 3.5) |

**Итог по РФ:** ни одного воспроизводимого аудиопотока с российских датацентровых проб получить не удалось.

### 3.5. Локальная проверка «функциональности» релеев (egress WARP)

**Invidious, `/api/v1/videos` (20 инстансов):** 403 (`inv.nadeko.net` — «Endpoint disabled»), 403 (`yewtu.be`), 404/502/503/530/451, таймауты 15–30 с, CAPTCHA (`protokolla.fi`), DNS-fail (`iv.datura.network`, `invidious.jing.rocks`). Ни одного рабочего API.

**Invidious, медиа-прокси `/latest_version` (20 инстансов, Range):** аудио (`206` + `audio/mp4`, 65 536 Б) отдал **только `yt.chocolatemoo53.com`** (3,0 с). На повторном запросе — таймаут. Остальные: 403/404/451/500/502/503/530, TLS-ошибки, таймауты, CAPTCHA-HTML.

Официальный автопоиск `api.invidious.io/instances.json` сейчас содержит **всего 6 https-инстансов**; при проверке их медиа-прокси ни один не отдал аудио (500, 403, 400, таймауты).

**Piped, `/streams/<id>` (5 инстансов, с парсингом JSON):**

| Инстанс | API | `audioStreams` | Аудио Range |
|---|---|---|---|
| `pipedapi.ducks.party` | 200, 354 мс | **0** (video 3) | — |
| `api.piped.private.coffee` | 200, 300 мс | **0** (video 3) | — |
| `piped-api.piped.tokhmi.xyz` | сертификат не совпадает с хостом | — | — |
| `pipedapi.kavin.rocks` | 403 | — | — |
| `pipedapi.adminforge.de` | 403 | — | — |

**Cobalt:** `api.cobalt.tools`, `cobalt-api.meowing.de` → `400 {"status":"error","error":{"code":"error.api.auth.jwt.missing"}}`; `co.eepy.today` → 502; `cobalt-api.kwiatekmiki.com` → DNS fail. Публичный cobalt в 2026 требует JWT-ключ → **для приложения не годится** (нужен свой инстанс + auth).

**Tunelio (`tunelio.dev`):** HTTP-API с `Authorization: Bearer tnl_…`, 100 бесплатных кредитов, затем платно. Это не публичный релей, а вендорский сервис — можно рассматривать как платный резерв, но не как дефолт (зависимость, деньги, ToS, приватность).

### 3.6. Выводы из замеров

1. **Публичные Invidious/Piped в 2026 — не инфраструктура, а лотерея.** Официальный список Invidious сократился до 6 https-инстансов; Piped-инстансы отдают 200, но без аудиопотоков; Cloudflare-фронтованные хосты в РФ душатся (16 КБ) и таймаутят.
2. **`api.invidious.io` как источник автопоиска исчерпан** — список слишком короткий. Нужен второй источник (список Piped-инстансов) и собственная health-проверка «реально играет».
3. **Единственный измеренный рабочий медиа-прокси (`yt.chocolatemoo53.com`) из РФ недоступен** → как дефолт для РФ он бесполезен.
4. **Встроенный список `RelayGateway.DEFAULT_INSTANCES` содержит мёртвые/Cloudflare-хосты** (`yewtu.be`, `inv.tux.pizza`, `invidious.materialio.us`, `inv.us.projectsegfau.lt`, `yt.chocolatemoo53.com`) — его нужно пересобрать по результатам health-проверки, а не по «популярности».
5. `api.github.com` доступен из РФ → **автообновление yt-dlp имеет смысл включать** (это дешёвая и полезная страховка), но рассчитывать на него как на решение 403 нельзя.

---

## 4. Рекомендуемая архитектура (с учётом уже написанного кода)

В проекте уже есть каркас: `app/sonora/net/relay/RelayGateway.kt` (Invidious/Piped, встроенный список, автопоиск `api.invidious.io`, TTL 5 мин, health-проверки, `probeBeforePlay`), `InnerTubeStreams.resolveViaRelay()`, `InnerTubeDataSource` (`ResolvingDataSource.Resolver`, ветки `ytdlp:` и `innertube:`), `YtDlpResolver` (перебор клиентов и IPv4/IPv6, `StreamProbe`). Ниже — что изменить, а не «написать с нуля».

### 4.1. Приоритеты путей (ничего не выносим на критический путь)

```
Media3 → InnerTubeDataSource (ResolvingDataSource)
  1) innertube:  → InnerTubeStreams (прямой InnerTube)         — быстро, дефолт
  2) ytdlp:     → YtDlpResolver + StreamProbe                   — если InnerTube не дал играбельный URL
  3) relay:     → RelayGateway (только если 1–2 не дали 200/206) — best-effort fallback
  4) own relay  → пользовательский шлюз (если задан в настройках) — приоритетнее публичных
```
Правила: суммарный бюджет на трек — 10–15 с; релей не прогревать на старте; кэш URL 5 мин (уже есть), отрицательный кэш инстанса — 15–30 мин.

### 4.2. Что поправить в `RelayGateway`

1. **Список инстансов:** убрать Cloudflare-фронтованные и мёртвые; оставить проверенные health-проверкой. На 29.10.2026 из публичных осмысленны только Piped-API (`pipedapi.ducks.party`, `api.piped.private.coffee`), но у них **нет аудио** → держать их «на всякий случай» с пометкой «API-only», а основным считать **свой шлюз**.
2. **Discovery:** оставить `api.invidious.io` (доступен из РФ), но добавить второй источник — список Piped-инстансов (`https://piped-instances.kavin.rocks/`), с TTL 6 ч и лимитом 40. Не доверять списку: каждый кандидат проходит health-проверку.
3. **Health-check должен проверять воспроизводимость, а не 200:**
   - Invidious: `GET /latest_version?id=<id>&itag=140&local=true`, `Range: bytes=0-65535` → ожидать `200/206` **и** `Content-Type: audio/*` (иначе это CAPTCHA/HTML!);
   - Piped: `GET /streams/<id>` → `audioStreams` непустой **и** Range по `proxyUrl` → `206` + `audio/*`;
   - таймаут на инстанс 3–5 с, всего 9 с на раунд; результаты — в `lastHealth` (уже есть).
4. **Error-taxonomy (для логов и UI):**

| Признак | Класс | Действие |
|---|---|---|
| `403` от `googlevideo` при прямом пути | `IP_BLOCKED` | сразу релей (флаги не помогут) |
| `403` от релея (nginx/CF/JSON «blocked») | `RELAY_DENIED` | карантин инстанса 30 мин |
| `410`/`404` у релея | `EXPIRED`/`GONE` | сбросить кэш URL, следующий инстанс |
| `429`/`503`/`502` | `RELAY_DOWN` | backoff 60 с, следующий инстанс |
| таймаут > 5 с | `RELAY_SLOW` | пометить, не блокировать навсегда |
| `200` + `text/html` | `CAPTCHA`/`CHALLENGE` | карантин 30 мин, не считать успехом |
| `206` + `audio/*` | `OK` | закэшировать на 5 мин |

5. **Failover внутри Media3:** `ResolvingDataSource.Resolver` вызывается один раз на `open()`; если затем `read()` падает на 403/410, ExoPlayer сам инстанс не сменит. Нужен тонкий `RelayDataSource` (обёртка над `DataSource`), которая при `IOException`/`HttpDataSourceException` с кодом 403/410/5xx переспрашивает `RelayGateway.resolve(..., exclude = текущий)` и переоткрывает поток; в остальном — обычный `DefaultDataSource`.
6. **Кэш и TTL:** URL релея — 5 мин (медиа-URL протухают), health — 10 мин, отрицательный результат — 30 мин. Всё в `ConcurrentHashMap`, как сейчас.
7. **Свой шлюз — основной надёжный вариант.** Настройка «Свой шлюз (URL)»: пользователь ставит на VPS (вне РФ, желательно не Cloudflare) `yt-dlp` + минимальный HTTP-сервис, который отдаёт `GET /audio/<videoId>` c redirect/стримом и подписью. Плюсы: работает без VPN на телефоне, предсказуемо, приватность контролируема. Минусы: нужен сервер/оплата, ToS-риски для оператора, задержки из РФ к зарубежному VPS (обычно ок для аудио 128 kbps).

### 4.3. Media3-интеграция (эскиз, не код)

```
class RelayDataSourceFactory(gw: RelayGateway, upstream: DataSource.Factory) : DataSource.Factory
class RelayDataSource(...) : DataSource  // open() -> resolve(videoId) -> delegate.open()
                                         // read() IOException(403/410) -> resolve(next) -> retry once
```
URI-схема: `relay:<videoId>` (или `relay:<videoId>?itag=140`). Для «своего шлюза» — тот же резолвер, приоритет 0. Кэш и дедупликацию резолвов — на уровне `RelayGateway.resolve(videoId, exclude)`.

### 4.4. Правовые/ToS-оговорки (честно)

- **YouTube ToS** запрещает автоматический доступ/скачивание; yt-dlp и основанные на нём релеи нарушают ToS. Публичные зеркала рискуют блокировкой, а приложение — удалением из Google Play (политики Play в отношении YouTube-«скачивателей» жёсткие).
- **РФ, 281-ФЗ от 31.07.2025** (вступил в 2025): введена административная ответственность за **рекламу** VPN-сервисов и за использование VPN для поиска экстремистских материалов. Поэтому: не рекламировать функцию как «обход блокировок», не публиковать промо-материалы «как обойти РКН», в UI — нейтральные формулировки («альтернативный источник потока», «шлюз»).
- **Релей видит метаданные** (какой трек слушают) и может логировать. Свой шлюз — доверенный; публичные — нет; в UI нужен дисклеймер.
- Не хранить cookies/аккаунты YouTube на сервере; не проксировать приватные данные.

---

## 5. Настройки UI и диагностика

### 5.1. Переключатели

| Настройка | Тип | Дефолт для РФ | Комментарий |
|---|---|---|---|
| «Шлюз (без VPN)» `relayEnabled` | toggle | **ON** | основной переключатель «не использовать VPN»; в описании — «если прямой поток не работает» |
| «Свой шлюз» `relayOwnUrl` | тестовое поле (advanced) | пусто | приоритетнее публичных; валидировать health-проверкой |
| «Публичные зеркала» `relayInstances` | многострочное поле (advanced) | пусто = встроенный список | пользователь может вставить свои |
| «Проверять поток перед игрой» `probeBeforePlay` | toggle | **ON** | Range-проверка, +0,3–2 с на трек, зато нет «403 в плеере» |
| «Обновлять yt-dlp автоматически» `ytDlpAutoUpdate` | toggle | **ON** | раз в сутки, канал NIGHTLY |
| «Канал yt-dlp» | select STABLE/NIGHTLY | **NIGHTLY** | в 2026 stable быстро «портится» |
| «Протокол» `youtubeIpMode` | Авто/IPv4/IPv6 | **Авто** (IPv4 раньше) | перебор уже реализован |
| «Разрешить работу с самоподписанным сертификатом» | advanced, off | OFF | `--no-check-certificates`; только для DPI-MITM |

Публичные зеркала не выносить в первый экран: это «best-effort», они ломаются и их не надо обещать пользователю.

### 5.2. «Проверить поток» (диагностика)

Экран/диалог запускает по шагам и показывает результат и время каждого шага:

1. **DNS** — резолв `www.youtube.com`, `youtubei.googleapis.com`, `googlevideo.com` (системный резолвер и DoH, если включён).
2. **InnerTube** — `player`-запрос на тестовом видео → есть ли `adaptiveFormats` (классификация ошибки: TLS/403/404).
3. **yt-dlp** — версия (`YoutubeDL.version`), попытка обновления (если включено), `--dump-json` → число форматов и набор клиента, который сработал.
4. **Медиа напрямую** — Range 0–65535 по полученному URL → `206`+`audio/*` (это и есть проверка «IP не заблокирован»).
5. **Шлюз** — health всех инстансов (параллельно, 6 потоков, 9 с), для лучшего — реальный Range аудио.
6. **Итог** — вердикт одной строкой: «Прямой путь: IP-блок (403) → Шлюз: 2/7 живых, аудио ок → рекомендуем свой шлюз/WARP».

Кнопка «Скопировать отчёт» (без приватных данных) — сильно снижает поддержку.

---

## 6. Что технически невозможно (и почему не стоит обещать)

1. **Если `googlevideo`-медиа блокируется по IP — ни один флаг yt-dlp/InnerTube не поможет.** Смена `player_client`, `-4`, `--no-check-certificates`, cookies, PO-токен — всё это меняет только то, как вы получили подписанный URL. Сам URL подписан под IP клиента; заблокированный IP получает 403 на любые форматы.
2. **No-VPN путь без релея/чужого egress не существует**, если провайдер режет медиа. Приложение может обойти DNS (DoH) — это лечит только первый слой; ТСПУ-фильтрация и троттлинг остаются.
3. **Публичные зеркала нельзя гарантировать**: они ротируются, умирают, стоят за Cloudflare (16 КБ/соединение в РФ), требуют CAPTCHA/JWT. Максимум — best-effort fallback с валидацией.
4. **PO-токен в приложении без внешнего провайдера не генерируется надёжно**; `bgutil-ytdlp-pot-provider` требует сервис/браузерное окружение. QuickJS+EJS решают только JS-челленджи (nsig), но не PO-токен.
5. **IPv6 — не панацея**, а лотерея по ASN; YouTube оценивает весь /64.

Поэтому в UI честно: «если прямой поток недоступен — включите шлюз (или свой шлюз) **или** WARP/VPN». Обещать «работает без VPN всегда» нельзя.

---

## 7. Источники

**Состояние РФ**
- Moscow Times, 10.02.2026 — https://ru.themoscowtimes.com/2026/02/10/roskomnadzor-pereshel-kpolnoi-blokirovke-youtube-posle-polutora-let-zamedleniya-a186865
- 3DNews, 11.02.2026 — https://3dnews.ru/1136656/rkn-pereshyol-k-polnoy-blokirovke-youtube-spustya-15-goda-zamedleniya-eksperti-svyazali-eto-s-merami-protiv-telegram/
- URA.RU, 10.02.2026 — https://ura.news/news/1053067812
- Cloudflare Docs «Potential disruption of services for Russian users», 23.04.2026 — https://developers.cloudflare.com/support/troubleshooting/general-troubleshooting/service-disruption/
- 281-ФЗ от 31.07.2025 (КоАП: реклама VPN / использование VPN) — https://base.garant.ru/412423444/

**yt-dlp**
- PO Token Guide — https://github.com/yt-dlp/yt-dlp/wiki/PO-Token-Guide
- EJS wiki — https://github.com/yt-dlp/yt-dlp/wiki/EJS
- Релизы (актуальный 2026.10.26) — https://github.com/yt-dlp/yt-dlp/releases
- Issue #15750 «version 2026.01.29: unable to download video data: HTTP Error 403» — https://github.com/yt-dlp/yt-dlp/issues/15750
- Памятка по 403 (вендорский блог, читать с поправкой на рекламу) — https://tunelio.dev/ru/blog/yt-dlp-403-forbidden/

**Релеи/зеркала**
- Invidious: список инстансов — https://api.invidious.io/instances.json ; статус публичных зеркал в 2026 — https://sumguy.com/invidious-piped-redlib-nitter-2026/
- Интеграция youtubedl-android (форк junkfood02) — https://github.com/junkfood02/youtubedl-android ; upstream — https://github.com/yausername/youtubedl-android
- Media3 `ResolvingDataSource` — https://developer.android.com/reference/androidx/media3/datasource/ResolvingDataSource

---

## 8. Приложение: как повторить замеры

| Файл | Что делает |
|---|---|
| `docs/ci/probe_relays.py` | локальный скан 20 Invidious + 12 Piped + 4 cobalt (API + Range), пишет `relay-probe-results.json` |
| `docs/ci/probe_invidious_audio.py` | прямой Range по `/latest_version` (медиа-прокси) для списка инстансов |
| `docs/ci/scan_invidious_audio.py` | автопоиск официального списка Invidious + Range-проверка аудио |
| `docs/ci/verify_piped_audio.py` | `/streams/<id>` → `audioStreams` → Range по `proxyUrl` |
| `docs/ci/globalping_probe.py` | замеры **из российских сетей** через Globalping API, фазы `A` (YouTube), `B` (релеи), `C` (аудио), `D` (IP+SNI), `E` (discovery) |
| `docs/ci/summarize.py` | сводка всех JSON в консоль |

Запуск: `python docs/ci/globalping_probe.py A|B|C|D|E` (Globalping — без ключа, лимиты анонимного доступа; не чаще ~1 измерения в 2 с).
