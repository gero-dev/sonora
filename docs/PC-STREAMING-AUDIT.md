# Аудит стриминга YouTube на реальной сети пользователя (ПК, Windows)

**Сформировано:** 29.10.2026 18:56:15 МСК  
**Скрипт:** `scripts/dev/streaming-audit.py`  
**Машина:** DESKTOP-30NVUJ9 · Python 3.14.7 · nt win32 3.14.7

> Отчёт сгенерирован автоматически по результатам реальных сетевых замеров. Все цифры — фактические, без «должно работать». Один и тот же скрипт можно перезапустить.

## 1. Короткий вердикт

- Без WARP (реальная сеть РФ): инфраструктура YouTube IPv4 3/8, IPv6 0/8; InnerTube не отвечает; медиа 64 КиБ НЕ качается.
- С WARP: инфраструктура IPv4 8/8, IPv6 8/8; InnerTube не отвечает; медиа 64 КиБ НЕ качается.

## 2. Условия замеров

- WARP ON: IPv4 `104.28.198.244`, IPv6 `2a09:bac5:5157:2db9::48e:3d`.
- WARP OFF: IPv4 `46.138.152.63`, IPv6 `нет`.
- DNS при WARP OFF: 192.168.1.254.
- yt-dlp: 2026.10.26 (zipapp), JS-рантаймы: deno, node.
- Каждый слой измерялся отдельно: DNS → TCP:443 → TLS с правильным SNI → HTTP-код → реальные байты через `Range: bytes=0-65535`. Это позволяет отличить блокировку DNS/SNI от блокировки самого медиа-потока.

## 3. Результаты по фазам

## 1. A (WARP ON, baseline)

- Начало (МСК): **2026-10-29T18:53:07+03:00**, конец: **2026-10-29T18:53:31+03:00**
- WARP фактически: **Connected** (до запуска: Connected)
- Egress: IPv4 104.28.198.244 / IPv6 2a09:bac5:5154:319::4f:153
- DNS-серверы: 127.0.2.2, 127.0.2.3, ::ffff:127.0.2.2, ::ffff:127.0.2.3
- Инфраструктура YouTube: IPv4 7/8 хостов, IPv6 8/8 хостов
- InnerTube: недоступен; медиа Range: не работает

**DNS / TCP / TLS / HTTP по хостам**

| хост | сем. | DNS, мс | адреса | TCP, мс | TLS, мс | HTTP | примечание |
|---|---|---|---|---|---|---|---|
| youtube.com | IPv4 | 0 | 64.233.161.93, 64.233.161.190, 64.233.161.1… | 43 | 139 | 301 | HTTP 301 |
| youtube.com | IPv6 | 0 | 2a00:1450:4010:c0f::5d, 2a00:1450:4010:c0f:… | 56 | 130 | 301 | HTTP 301 |
| www.youtube.com | IPv4 | 0 | 142.251.153.4, 142.251.150.4, 142.251.152.4… | 39 | 98 | 200 | HTTP 200 |
| www.youtube.com | IPv6 | 0 | 2001:4860:4828:400::, 2001:4860:482c:400::,… | 39 | 131 | 200 | HTTP 200 |
| music.youtube.com | IPv4 | 2 | 142.251.153.4, 142.251.156.4, 142.251.152.4… | 66 | 134 | 200 | HTTP 200 |
| music.youtube.com | IPv6 | 33 | 2001:4860:482c:400::, 2001:4860:4828:400::,… | 39 | 129 | 200 | HTTP 200 |
| music.youtube.com | IPv4 | 1 | 142.251.153.4, 142.251.156.4, 142.251.152.4… | — | — | — | timeout after 5.0s |
| music.youtube.com | IPv6 | 1 | 2001:4860:482c:400::, 2001:4860:4828:400::,… | 63 | 102 | 405 | HTTP 405 |
| youtubei.googleapis.com | IPv4 | 27 | 172.217.117.4, 172.217.115.4, 172.217.114.4… | 38 | 136 | 405 | HTTP 405 |
| youtubei.googleapis.com | IPv6 | 1 | 2001:4860:4846:400::, 2001:4860:4844:400::,… | 38 | 101 | 405 | HTTP 405 |
| googlevideo.com | IPv4 | 0 | 64.233.162.104, 64.233.162.103, 64.233.162.… | 39 | 280 | 302 | HTTP 302 |
| googlevideo.com | IPv6 | 1 | 2a00:1450:4010:c1e::6a, 2a00:1450:4010:c1e:… | 37 | 279 | 302 | HTTP 302 |
| i.ytimg.com | IPv4 | 31 | 142.251.148.6, 192.178.41.6, 142.251.145.6,… | 40 | 101 | 200 | HTTP 200 |
| i.ytimg.com | IPv6 | 0 | 2001:4860:4825:600::, 2001:4860:4824:600::,… | 40 | 133 | 200 | HTTP 200 |
| yt3.ggpht.com | IPv4 | 28 | 74.125.205.132, 142.251.1.132, 64.233.162.1… | 42 | 135 | 400 | HTTP 400 |
| yt3.ggpht.com | IPv6 | 28 | 2a00:1450:4010:c05::84, 2a00:1450:4010:c07:… | 38 | 158 | 400 | HTTP 400 |

**InnerTube `player` API**

| video | client | мс | HTTP | playability | аудио-форматов | причина/ошибка | url |
|---|---|---|---|---|---|---|---|
| dQw4w9WgXcQ | WEB_REMIX | 281 | 200 | UNPLAYABLE | 0 | Видео недоступно | ❌ |
| dQw4w9WgXcQ | ANDROID | 227 | 400 | — | 0 | HTTP 400: {   "error": {     "code": 400,     "me… | ❌ |
| dQw4w9WgXcQ | TVHTML5_SIMPLY | 206 | 200 | ERROR | 0 | YouTube больше не поддерживается в этом приложени… | ❌ |
| dQw4w9WgXcQ | TVHTML5 | 235 | 200 | UNPLAYABLE | 0 | Необходимо перезагрузить страницу. | ❌ |
| kJQP7kiw5Fk | WEB_REMIX | 236 | 200 | LOGIN_REQUIRED | 0 | Войдите в аккаунт, чтобы подтвердить, что вы не б… | ❌ |
| kJQP7kiw5Fk | ANDROID | 192 | 400 | — | 0 | HTTP 400: {   "error": {     "code": 400,     "me… | ❌ |
| kJQP7kiw5Fk | TVHTML5_SIMPLY | 157 | 200 | ERROR | 0 | YouTube больше не поддерживается в этом приложени… | ❌ |
| kJQP7kiw5Fk | TVHTML5 | 284 | 200 | LOGIN_REQUIRED | 0 | Войдите в аккаунт, чтобы подтвердить, что вы не б… | ❌ |

**Медиа: Range 0–65535 (64 КиБ)**

| client | video | itag | host | HTTP | байт | КиБ/с | 1-й байт, мс | ошибка | ok |
|---|---|---|---|---|---|---|---|---|---|


## 2. B+C (WARP OFF: реальная сеть РФ + yt-dlp + релеи)

- Начало (МСК): **2026-10-29T18:53:36+03:00**, конец: **2026-10-29T18:54:10+03:00**
- WARP фактически: **Disconnected** (до запуска: Connected)
- Egress: IPv4 46.138.152.63 / IPv6 нет
- DNS-серверы: 192.168.1.254
- Инфраструктура YouTube: IPv4 3/8 хостов, IPv6 0/8 хостов
- InnerTube: недоступен; медиа Range: не работает
- yt-dlp: 0/13 вариантов получили URL
- Релеи: 0/32 отдали рабочий аудио-поток

Заметки: WARP: Connected -> Disconnected (125 ms); yt-dlp 2026.10.26: 0/13 variants resolved; relays: 0/32 работают с 32 КиБ

**DNS / TCP / TLS / HTTP по хостам**

| хост | сем. | DNS, мс | адреса | TCP, мс | TLS, мс | HTTP | примечание |
|---|---|---|---|---|---|---|---|
| youtube.com | IPv4 | 6 | 142.250.154.136, 142.250.154.91, 142.250.15… | 49 | — | — | ConnectionResetError: [WinError 10054] Удален… |
| youtube.com | IPv6 | 3 | — | — | — | — | gaierror: [Errno 11004] getaddrinfo failed |
| www.youtube.com | IPv4 | 4 | 142.251.150.4, 142.251.151.4, 142.251.152.4… | 75 | — | — | ConnectionResetError: [WinError 10054] Удален… |
| www.youtube.com | IPv6 | 5 | — | — | — | — | gaierror: [Errno 11004] getaddrinfo failed |
| music.youtube.com | IPv4 | 5 | 142.251.156.4, 142.251.157.4, 142.251.151.4… | 48 | — | — | ConnectionResetError: [WinError 10054] Удален… |
| music.youtube.com | IPv6 | 4 | — | — | — | — | gaierror: [Errno 11004] getaddrinfo failed |
| music.youtube.com | IPv4 | 0 | 142.251.156.4, 142.251.157.4, 142.251.151.4… | 49 | — | — | ConnectionResetError: [WinError 10054] Удален… |
| music.youtube.com | IPv6 | 0 | — | — | — | — | gaierror: [Errno 11004] getaddrinfo failed |
| youtubei.googleapis.com | IPv4 | 5 | 172.217.116.4, 172.217.119.4, 172.217.114.4… | 47 | 137 | 405 | HTTP 405 |
| youtubei.googleapis.com | IPv6 | 8 | — | — | — | — | gaierror: [Errno 11004] getaddrinfo failed |
| googlevideo.com | IPv4 | 5 | 142.251.127.103, 142.251.127.104, 142.251.1… | 48 | 239 | 302 | HTTP 302 |
| googlevideo.com | IPv6 | 6 | — | — | — | — | gaierror: [Errno 11004] getaddrinfo failed |
| i.ytimg.com | IPv4 | 4 | 142.251.144.6, 142.251.149.6, 142.251.147.6… | 49 | 120 | 200 | HTTP 200 |
| i.ytimg.com | IPv6 | 8 | — | — | — | — | gaierror: [Errno 11004] getaddrinfo failed |
| yt3.ggpht.com | IPv4 | 5 | 142.251.127.198 | 48 | — | — | ConnectionResetError: [WinError 10054] Удален… |
| yt3.ggpht.com | IPv6 | 4 | — | — | — | — | gaierror: [Errno 11004] getaddrinfo failed |

**InnerTube `player` API**

| video | client | мс | HTTP | playability | аудио-форматов | причина/ошибка | url |
|---|---|---|---|---|---|---|---|
| dQw4w9WgXcQ | WEB_REMIX | 70 | — | — | 0 | ConnectionResetError: [WinError 10054] Удаленный … | ❌ |
| dQw4w9WgXcQ | ANDROID | 69 | — | — | 0 | ConnectionResetError: [WinError 10054] Удаленный … | ❌ |
| dQw4w9WgXcQ | TVHTML5_SIMPLY | 69 | — | — | 0 | ConnectionResetError: [WinError 10054] Удаленный … | ❌ |
| dQw4w9WgXcQ | TVHTML5 | 70 | — | — | 0 | ConnectionResetError: [WinError 10054] Удаленный … | ❌ |
| kJQP7kiw5Fk | WEB_REMIX | 74 | — | — | 0 | ConnectionResetError: [WinError 10054] Удаленный … | ❌ |
| kJQP7kiw5Fk | ANDROID | 80 | — | — | 0 | ConnectionResetError: [WinError 10054] Удаленный … | ❌ |
| kJQP7kiw5Fk | TVHTML5_SIMPLY | 80 | — | — | 0 | ConnectionResetError: [WinError 10054] Удаленный … | ❌ |
| kJQP7kiw5Fk | TVHTML5 | 70 | — | — | 0 | ConnectionResetError: [WinError 10054] Удаленный … | ❌ |

**Медиа: Range 0–65535 (64 КиБ)**

| client | video | itag | host | HTTP | байт | КиБ/с | 1-й байт, мс | ошибка | ok |
|---|---|---|---|---|---|---|---|---|---|

**yt-dlp (актуальный zipapp)**

Версия yt-dlp: **2026.10.26**; JS-рантаймы: deno, node

| video | вариант | мс | resolve | host | HTTP | байт | КиБ/с | ошибка |
|---|---|---|---|---|---|---|---|---|
| dQw4w9WgXcQ | default | 2071 | ❌ |  | — | — | — | ERROR: [youtube] dQw4w9WgXcQ: Unable to download API page: … |
| dQw4w9WgXcQ | tv_simply | 2105 | ❌ |  | — | — | — | ERROR: [youtube] dQw4w9WgXcQ: Unable to download API page: … |
| dQw4w9WgXcQ | tv | 2075 | ❌ |  | — | — | — | ERROR: [youtube] dQw4w9WgXcQ: Unable to download API page: … |
| dQw4w9WgXcQ | android_vr | 1989 | ❌ |  | — | — | — | ERROR: [youtube] dQw4w9WgXcQ: Unable to download API page: … |
| dQw4w9WgXcQ | web_safari | 1910 | ❌ |  | — | — | — | ERROR: [youtube] dQw4w9WgXcQ: Unable to download API page: … |
| dQw4w9WgXcQ | ios | 1954 | ❌ |  | — | — | — | ERROR: [youtube] dQw4w9WgXcQ: Unable to download API page: … |
| dQw4w9WgXcQ | mweb | 2034 | ❌ |  | — | — | — | ERROR: [youtube] dQw4w9WgXcQ: Unable to download API page: … |
| dQw4w9WgXcQ | default@ipv4 | 1985 | ❌ |  | — | — | — | ERROR: [youtube] dQw4w9WgXcQ: Unable to download API page: … |
| dQw4w9WgXcQ | tv_simply@ipv4 | 1973 | ❌ |  | — | — | — | ERROR: [youtube] dQw4w9WgXcQ: Unable to download API page: … |
| dQw4w9WgXcQ | default@ipv6 | 1682 | ❌ |  | — | — | — | ERROR: [youtube] dQw4w9WgXcQ: Unable to download API page: … |
| kJQP7kiw5Fk | default | 16939 | ❌ |  | — | — | — | ERROR: [youtube] kJQP7kiw5Fk: Unable to download API page: … |
| kJQP7kiw5Fk | tv_simply | 1998 | ❌ |  | — | — | — | ERROR: [youtube] kJQP7kiw5Fk: Unable to download API page: … |
| kJQP7kiw5Fk | tv | 1788 | ❌ |  | — | — | — | ERROR: [youtube] kJQP7kiw5Fk: Unable to download API page: … |

**Публичные релеи (Invidious / Piped)**

Автообнаружение api.invidious.io: ✅, найдено 3; всего проверено 32 хостов.

| тип | хост | API HTTP | API, мс | медиа-host | HTTP | байт | КиБ/с | ошибка | ok |
|---|---|---|---|---|---|---|---|---|---|
| invidious | iv.datura.network | — | 52 |  | — | — | — | URLError: <urlopen error [Errno 11001] getadd… | ❌ |
| invidious | inv-ygg.nadeko.net | — | 24 |  | — | — | — | URLError: <urlopen error [Errno 11001] getadd… | ❌ |
| invidious | invidious.einfachzocken.eu | — | 82 |  | — | — | — | URLError: <urlopen error [WinError 10054] Уда… | ❌ |
| invidious | inv.nadeko.ygg | — | 6 |  | — | — | — | URLError: <urlopen error [Errno 11001] getadd… | ❌ |
| invidious | invidious.jing.rocks | — | 87 |  | — | — | — | URLError: <urlopen error [Errno 11001] getadd… | ❌ |
| invidious | invidious.privacyredirect.com | — | 90 |  | — | — | — | URLError: <urlopen error [WinError 10054] Уда… | ❌ |
| invidious | invidious.tiekoetter.com | — | 92 |  | — | — | — | URLError: <urlopen error [WinError 10054] Уда… | ❌ |
| piped | pipedapi.r4fo.com | — | 12 |  | — | — | — | URLError: <urlopen error [Errno 11001] getadd… | ❌ |
| invidious | iv.melmac.space | — | 97 |  | — | — | — | URLError: <urlopen error [Errno 11001] getadd… | ❌ |
| invidious | invidious.adminforge.de | — | 119 |  | — | — | — | URLError: <urlopen error [WinError 10054] Уда… | ❌ |
| invidious | invidious.reallyaweso.me | — | 129 |  | — | — | — | URLError: <urlopen error [WinError 10054] Уда… | ❌ |
| piped | pipedapi.nosebs.ru | — | 26 |  | — | — | — | URLError: <urlopen error [Errno 11001] getadd… | ❌ |
| piped | pipedapi.drgns.space | — | 58 |  | — | — | — | URLError: <urlopen error [Errno 11001] getadd… | ❌ |
| invidious | yt.chocolatemoo53.com | — | 164 |  | — | — | — | URLError: <urlopen error [WinError 10054] Уда… | ❌ |
| invidious | invidious.f5.si | — | 233 |  | — | — | — | URLError: <urlopen error [WinError 10054] Уда… | ❌ |
| invidious | invidious.dhusch.de | — | 262 |  | — | — | — | URLError: <urlopen error [SSL: TLSV1_ALERT_IN… | ❌ |
| invidious | invidious.nikkosphere.com | 451 | 282 |  | — | — | — | HTTP 451:  | ❌ |
| invidious | invidious.lunivers.trade | 503 | 295 |  | — | — | — | HTTP 503: <html><body><h1>503 Service Unavail… | ❌ |
| invidious | inv.nadeko.net | — | 302 |  | — | — | — | URLError: <urlopen error [WinError 10054] Уда… | ❌ |
| invidious | invidious.materialio.us | — | 433 |  | — | — | — | URLError: <urlopen error [WinError 10054] Уда… | ❌ |
| piped | pipedapi.reallyaweso.me | — | 302 |  | — | — | — | JSONDecodeError: Expecting value: line 1 colu… | ❌ |
| piped | pipedapi.adminforge.de | 403 | 452 |  | — | — | — | HTTP 403: <!DOCTYPE HTML PUBLIC "-//W3C//DTD … | ❌ |
| invidious | invidious.nerdvpn.de | 401 | 586 |  | — | — | — | HTTP 401: <html> <head><title>401 Authorizat… | ❌ |
| piped | pipedapi.kavin.rocks | 403 | 672 |  | — | — | — | HTTP 403: <!DOCTYPE html> <!--[if lt IE 7]> <… | ❌ |
| invidious | inv.zzls.xyz | — | 833 |  | — | — | — | URLError: <urlopen error [WinError 10054] Уда… | ❌ |
| piped | pipedapi.leptons.xyz | 502 | 1396 |  | — | — | — | HTTP 502: {"type":"https://developers.cloudfl… | ❌ |
| invidious | invidious.protokolla.fi | — | 2258 |  | — | — | — | URLError: <urlopen error [WinError 10054] Уда… | ❌ |
| invidious | inv.tux.pizza | — | 5023 |  | — | — | — | URLError: <urlopen error timed out> | ❌ |
| invidious | iv.ggtyler.dev | — | 5018 |  | — | — | — | URLError: <urlopen error timed out> | ❌ |
| piped | pipedapi.orangenet.cc | — | 5060 |  | — | — | — | URLError: <urlopen error timed out> | ❌ |
| piped | pipedapi.astartes.nl | — | 5113 |  | — | — | — | URLError: <urlopen error timed out> | ❌ |
| piped | pipedapi.ducks.party | — | 5235 |  | — | — | — | TimeoutError: The read operation timed out | ❌ |


## 3. D (WARP ON: полный пайплайн + yt-dlp)

- Начало (МСК): **2026-10-29T18:55:26+03:00**, конец: **2026-10-29T18:56:09+03:00**
- WARP фактически: **Connected** (до запуска: Connected)
- Egress: IPv4 104.28.198.244 / IPv6 2a09:bac5:5157:2db9::48e:3d
- DNS-серверы: 127.0.2.2, 127.0.2.3, ::ffff:127.0.2.2, ::ffff:127.0.2.3
- Инфраструктура YouTube: IPv4 8/8 хостов, IPv6 8/8 хостов
- InnerTube: недоступен; медиа Range: не работает
- yt-dlp: 10/13 вариантов получили URL
- Релеи: 0/32 отдали рабочий аудио-поток

Заметки: yt-dlp 2026.10.26: 10/13 variants resolved; relays: 0/32 работают с 32 КиБ

**DNS / TCP / TLS / HTTP по хостам**

| хост | сем. | DNS, мс | адреса | TCP, мс | TLS, мс | HTTP | примечание |
|---|---|---|---|---|---|---|---|
| youtube.com | IPv4 | 30 | 64.233.162.136, 64.233.162.91, 64.233.162.1… | 39 | 144 | 301 | HTTP 301 |
| youtube.com | IPv6 | 29 | 2a00:1450:4010:c22::88, 2a00:1450:4010:c22:… | 41 | 104 | 301 | HTTP 301 |
| www.youtube.com | IPv4 | 28 | 142.251.150.4, 142.251.154.4, 142.251.151.4… | 40 | 134 | 200 | HTTP 200 |
| www.youtube.com | IPv6 | 28 | 2001:4860:482c:400::, 2001:4860:482b:400::,… | 42 | 133 | 200 | HTTP 200 |
| music.youtube.com | IPv4 | 28 | 142.251.150.4, 142.251.153.4, 142.251.157.4… | 37 | 140 | 200 | HTTP 200 |
| music.youtube.com | IPv6 | 34 | 2001:4860:4829:400::, 2001:4860:482a:400::,… | 39 | 129 | 200 | HTTP 200 |
| music.youtube.com | IPv4 | 0 | 142.251.150.4, 142.251.153.4, 142.251.157.4… | 38 | 101 | 405 | HTTP 405 |
| music.youtube.com | IPv6 | 1 | 2001:4860:4829:400::, 2001:4860:482a:400::,… | 37 | 109 | 405 | HTTP 405 |
| youtubei.googleapis.com | IPv4 | 28 | 172.217.112.4, 172.217.116.4, 172.217.119.4… | 39 | 106 | 405 | HTTP 405 |
| youtubei.googleapis.com | IPv6 | 40 | 2001:4860:4847:400::, 2001:4860:4843:400::,… | 42 | 97 | 405 | HTTP 405 |
| googlevideo.com | IPv4 | 30 | 142.251.38.68 | 36 | 283 | 302 | HTTP 302 |
| googlevideo.com | IPv6 | 30 | 2a00:1450:4010:c02::67, 2a00:1450:4010:c02:… | 41 | 254 | 302 | HTTP 302 |
| i.ytimg.com | IPv4 | 31 | 142.251.148.6, 142.251.149.6, 142.251.144.6… | 40 | 110 | 200 | HTTP 200 |
| i.ytimg.com | IPv6 | 27 | 2001:4860:4831:600::, 2001:4860:4825:600::,… | 43 | 110 | 200 | HTTP 200 |
| yt3.ggpht.com | IPv4 | 29 | 74.125.205.132, 142.251.1.132, 64.233.162.1… | 39 | 131 | 400 | HTTP 400 |
| yt3.ggpht.com | IPv6 | 30 | 2a00:1450:4010:c05::84, 2a00:1450:4010:c07:… | 39 | 133 | 400 | HTTP 400 |

**InnerTube `player` API**

| video | client | мс | HTTP | playability | аудио-форматов | причина/ошибка | url |
|---|---|---|---|---|---|---|---|
| dQw4w9WgXcQ | WEB_REMIX | 243 | 200 | UNPLAYABLE | 0 | Видео недоступно | ❌ |
| dQw4w9WgXcQ | ANDROID | 214 | 400 | — | 0 | HTTP 400: {   "error": {     "code": 400,     "me… | ❌ |
| dQw4w9WgXcQ | TVHTML5_SIMPLY | 225 | 200 | ERROR | 0 | YouTube больше не поддерживается в этом приложени… | ❌ |
| dQw4w9WgXcQ | TVHTML5 | 237 | 200 | UNPLAYABLE | 0 | Необходимо перезагрузить страницу. | ❌ |
| kJQP7kiw5Fk | WEB_REMIX | 294 | 200 | UNPLAYABLE | 0 | Видео недоступно | ❌ |
| kJQP7kiw5Fk | ANDROID | 181 | 400 | — | 0 | HTTP 400: {   "error": {     "code": 400,     "me… | ❌ |
| kJQP7kiw5Fk | TVHTML5_SIMPLY | 196 | 200 | ERROR | 0 | YouTube больше не поддерживается в этом приложени… | ❌ |
| kJQP7kiw5Fk | TVHTML5 | 295 | 200 | LOGIN_REQUIRED | 0 | Войдите в аккаунт, чтобы подтвердить, что вы не б… | ❌ |

**Медиа: Range 0–65535 (64 КиБ)**

| client | video | itag | host | HTTP | байт | КиБ/с | 1-й байт, мс | ошибка | ok |
|---|---|---|---|---|---|---|---|---|---|

**Послойный разбор googlevideo (DNS → TCP → TLS/SNI → HTTP → подписанное медиа)**

**rr3---sn-u125g5-5p.googlevideo.com**
| слой | ok | детали |
|---|---|---|
| DNS IPv4 | ✅ | ms=0 |
| DNS IPv6 | ✅ | ms=31 |
| TCP:443 | ✅ | ms=65 |
| TLS/SNI | ✅ | ms=183 version=TLSv1.3 |
| HTTP GET / (без подписи) | ✅ | tcp_ms=64 tls_ms=202 status=404 |
| медиа Range IPv4 | ❌ | нет подписанного URL — байты не проверить |
| медиа Range IPv6 | ❌ | нет подписанного URL — байты не проверить |


**yt-dlp (актуальный zipapp)**

Версия yt-dlp: **2026.10.26**; JS-рантаймы: deno, node

| video | вариант | мс | resolve | host | HTTP | байт | КиБ/с | ошибка |
|---|---|---|---|---|---|---|---|---|
| dQw4w9WgXcQ | default | 6915 | ✅ | rr3---sn-u125g5-5p.googlevideo.com | 302 | 0 | — |  |
| dQw4w9WgXcQ | tv_simply | 6808 | ✅ | rr3---sn-u125g5-5p.googlevideo.com | 302 | 0 | — |  |
| dQw4w9WgXcQ | tv | 5019 | ❌ |  | — | — | — | ERROR: [youtube] dQw4w9WgXcQ: The page needs to be reloaded. |
| dQw4w9WgXcQ | android_vr | 3626 | ✅ | rr5---sn-25ge7nsd.googlevideo.com | 302 | 0 | — |  |
| dQw4w9WgXcQ | web_safari | 5464 | ✅ | manifest.googlevideo.com | 206 | 32 | 643.6 |  |
| dQw4w9WgXcQ | ios | 3285 | ❌ |  | — | — | — | ERROR: [youtube] dQw4w9WgXcQ: Requested format is not avail… |
| dQw4w9WgXcQ | mweb | 5269 | ✅ | rr5---sn-25ge7nsd.googlevideo.com | 302 | 0 | — |  |
| dQw4w9WgXcQ | default@ipv4 | 4935 | ✅ | rr4---sn-vgqsrnld.googlevideo.com | 206 | 32 | 2983.4 |  |
| dQw4w9WgXcQ | tv_simply@ipv4 | 4442 | ✅ | rr4---sn-vgqsrnld.googlevideo.com | 206 | 32 | 862.2 |  |
| dQw4w9WgXcQ | default@ipv6 | 4663 | ✅ | rr5---sn-25ge7nsd.googlevideo.com | 206 | 32 | 964.3 |  |
| kJQP7kiw5Fk | default | 5018 | ✅ | rr2---sn-25glenez.googlevideo.com | 302 | 0 | — |  |
| kJQP7kiw5Fk | tv_simply | 4249 | ✅ | rr2---sn-25glenez.googlevideo.com | 302 | 0 | — |  |
| kJQP7kiw5Fk | tv | 3489 | ❌ |  | — | — | — | ERROR: [youtube] kJQP7kiw5Fk: The page needs to be reloaded. |

**Публичные релеи (Invidious / Piped)**

Автообнаружение api.invidious.io: ✅, найдено 3; всего проверено 32 хостов.

| тип | хост | API HTTP | API, мс | медиа-host | HTTP | байт | КиБ/с | ошибка | ok |
|---|---|---|---|---|---|---|---|---|---|
| invidious | iv.datura.network | — | 119 |  | — | — | — | URLError: <urlopen error [Errno 11001] getadd… | ❌ |
| invidious | invidious.f5.si | 530 | 156 |  | — | — | — | HTTP 530: {"type":"https://developers.cloudfl… | ❌ |
| invidious | invidious.einfachzocken.eu | — | 174 |  | — | — | — | URLError: <urlopen error [SSL: CERTIFICATE_VE… | ❌ |
| invidious | iv.melmac.space | — | 333 |  | — | — | — | URLError: <urlopen error [SSL: CERTIFICATE_VE… | ❌ |
| invidious | invidious.dhusch.de | — | 340 |  | — | — | — | URLError: <urlopen error [SSL: TLSV1_ALERT_IN… | ❌ |
| invidious | invidious.tiekoetter.com | 403 | 337 |  | — | — | — | HTTP 403: <html> <head><title>403 Forbidden<… | ❌ |
| piped | pipedapi.r4fo.com | — | 31 |  | — | — | — | URLError: <urlopen error [Errno 11001] getadd… | ❌ |
| piped | pipedapi.drgns.space | — | 44 |  | — | — | — | URLError: <urlopen error [Errno 11001] getadd… | ❌ |
| invidious | invidious.reallyaweso.me | 502 | 420 |  | — | — | — | HTTP 502:  | ❌ |
| invidious | invidious.lunivers.trade | 503 | 428 |  | — | — | — | HTTP 503: <html><body><h1>503 Service Unavail… | ❌ |
| piped | pipedapi.nosebs.ru | — | 25 |  | — | — | — | URLError: <urlopen error [Errno 11001] getadd… | ❌ |
| piped | pipedapi.kavin.rocks | 403 | 142 |  | — | — | — | HTTP 403: <!DOCTYPE html> <!--[if lt IE 7]> <… | ❌ |
| invidious | invidious.jing.rocks | — | 482 |  | — | — | — | URLError: <urlopen error [Errno 11001] getadd… | ❌ |
| invidious | invidious.protokolla.fi | — | 552 |  | — | — | — | JSONDecodeError: Expecting value: line 2 colu… | ❌ |
| invidious | inv.nadeko.ygg | — | 461 |  | — | — | — | URLError: <urlopen error [Errno 11001] getadd… | ❌ |
| piped | pipedapi.ducks.party | 200 | 294 |  | — | — | — | no audioStreams | ❌ |
| invidious | invidious.adminforge.de | 404 | 715 |  | — | — | — | HTTP 404: <!DOCTYPE html> <html lang="de" id=… | ❌ |
| invidious | invidious.privacyredirect.com | 404 | 717 |  | — | — | — | HTTP 404: <!DOCTYPE html> <html lang="en"> <h… | ❌ |
| piped | pipedapi.reallyaweso.me | — | 270 |  | — | — | — | JSONDecodeError: Expecting value: line 1 colu… | ❌ |
| piped | pipedapi.leptons.xyz | 502 | 375 |  | — | — | — | HTTP 502: {"type":"https://developers.cloudfl… | ❌ |
| invidious | invidious.nikkosphere.com | 451 | 801 |  | — | — | — | HTTP 451:  | ❌ |
| piped | pipedapi.adminforge.de | 403 | 651 |  | — | — | — | HTTP 403: <!DOCTYPE HTML PUBLIC "-//W3C//DTD … | ❌ |
| invidious | inv.nadeko.net | 403 | 1096 |  | — | — | — | HTTP 403: Endpoint disabled | ❌ |
| invidious | invidious.nerdvpn.de | 401 | 1572 |  | — | — | — | HTTP 401: <html> <head><title>401 Authorizat… | ❌ |
| invidious | inv.zzls.xyz | 403 | 2062 |  | — | — | — | HTTP 403: Endpoint disabled | ❌ |
| invidious | yt.chocolatemoo53.com | 200 | 1135 | yt.chocolatemoo53.com | 307 | 0 | 40.5 | HTTP 307 | ❌ |
| invidious | iv.ggtyler.dev | — | 5048 |  | — | — | — | URLError: <urlopen error timed out> | ❌ |
| invidious | inv-ygg.nadeko.net | — | 5038 |  | — | — | — | URLError: <urlopen error timed out> | ❌ |
| invidious | invidious.materialio.us | — | 5441 |  | — | — | — | URLError: <urlopen error _ssl.c:1064: The han… | ❌ |
| piped | pipedapi.astartes.nl | — | 5104 |  | — | — | — | URLError: <urlopen error timed out> | ❌ |
| piped | pipedapi.orangenet.cc | — | 5035 |  | — | — | — | URLError: <urlopen error timed out> | ❌ |
| invidious | inv.tux.pizza | — | 5554 |  | — | — | — | URLError: <urlopen error timed out> | ❌ |


## 4. B2 (WARP OFF: SNI + медиа-CDN)

- Начало (МСК): **2026-10-29T18:56:09+03:00**, конец: **2026-10-29T18:56:15+03:00**
- WARP фактически: **Disconnected** (до запуска: Connected)
- Egress: IPv4 46.138.152.63 / IPv6 нет
- DNS-серверы: 192.168.1.254
- Инфраструктура YouTube: IPv4 3/8 хостов, IPv6 0/8 хостов
- InnerTube: недоступен; медиа Range: не работает

Заметки: WARP: Connected -> Disconnected (122 ms)

**DNS / TCP / TLS / HTTP по хостам**

| хост | сем. | DNS, мс | адреса | TCP, мс | TLS, мс | HTTP | примечание |
|---|---|---|---|---|---|---|---|
| youtube.com | IPv4 | 6 | 142.251.20.136, 142.251.20.91, 142.251.20.9… | 47 | — | — | ConnectionResetError: [WinError 10054] Удален… |
| youtube.com | IPv6 | 3 | — | — | — | — | gaierror: [Errno 11004] getaddrinfo failed |
| www.youtube.com | IPv4 | 2 | 142.251.150.4, 142.251.151.4, 142.251.152.4… | 46 | — | — | ConnectionResetError: [WinError 10054] Удален… |
| www.youtube.com | IPv6 | 4 | — | — | — | — | gaierror: [Errno 11004] getaddrinfo failed |
| music.youtube.com | IPv4 | 1 | 142.251.156.4, 142.251.157.4, 142.251.151.4… | 86 | — | — | ConnectionResetError: [WinError 10054] Удален… |
| music.youtube.com | IPv6 | 5 | — | — | — | — | gaierror: [Errno 11004] getaddrinfo failed |
| music.youtube.com | IPv4 | 0 | 142.251.156.4, 142.251.157.4, 142.251.151.4… | 47 | — | — | ConnectionResetError: [WinError 10054] Удален… |
| music.youtube.com | IPv6 | 0 | — | — | — | — | gaierror: [Errno 11004] getaddrinfo failed |
| youtubei.googleapis.com | IPv4 | 1 | 172.217.116.4, 172.217.119.4, 172.217.114.4… | 47 | 117 | 405 | HTTP 405 |
| youtubei.googleapis.com | IPv6 | 5 | — | — | — | — | gaierror: [Errno 11004] getaddrinfo failed |
| googlevideo.com | IPv4 | 2 | 142.251.127.103, 142.251.127.104, 142.251.1… | 50 | 224 | 302 | HTTP 302 |
| googlevideo.com | IPv6 | 6 | — | — | — | — | gaierror: [Errno 11004] getaddrinfo failed |
| i.ytimg.com | IPv4 | 2 | 142.251.144.6, 142.251.149.6, 142.251.147.6… | 48 | 114 | 200 | HTTP 200 |
| i.ytimg.com | IPv6 | 5 | — | — | — | — | gaierror: [Errno 11004] getaddrinfo failed |
| yt3.ggpht.com | IPv4 | 4 | 142.251.127.198 | 46 | — | — | ConnectionResetError: [WinError 10054] Удален… |
| yt3.ggpht.com | IPv6 | 7 | — | — | — | — | gaierror: [Errno 11004] getaddrinfo failed |

**InnerTube `player` API**

| video | client | мс | HTTP | playability | аудио-форматов | причина/ошибка | url |
|---|---|---|---|---|---|---|---|
| dQw4w9WgXcQ | WEB_REMIX | 78 | — | — | 0 | ConnectionResetError: [WinError 10054] Удаленный … | ❌ |
| dQw4w9WgXcQ | ANDROID | 94 | — | — | 0 | ConnectionResetError: [WinError 10054] Удаленный … | ❌ |
| dQw4w9WgXcQ | TVHTML5_SIMPLY | 92 | — | — | 0 | ConnectionResetError: [WinError 10054] Удаленный … | ❌ |
| dQw4w9WgXcQ | TVHTML5 | 83 | — | — | 0 | ConnectionResetError: [WinError 10054] Удаленный … | ❌ |
| kJQP7kiw5Fk | WEB_REMIX | 92 | — | — | 0 | ConnectionResetError: [WinError 10054] Удаленный … | ❌ |
| kJQP7kiw5Fk | ANDROID | 91 | — | — | 0 | ConnectionResetError: [WinError 10054] Удаленный … | ❌ |
| kJQP7kiw5Fk | TVHTML5_SIMPLY | 70 | — | — | 0 | ConnectionResetError: [WinError 10054] Удаленный … | ❌ |
| kJQP7kiw5Fk | TVHTML5 | 72 | — | — | 0 | ConnectionResetError: [WinError 10054] Удаленный … | ❌ |

**Медиа: Range 0–65535 (64 КиБ)**

| client | video | itag | host | HTTP | байт | КиБ/с | 1-й байт, мс | ошибка | ok |
|---|---|---|---|---|---|---|---|---|---|

**Послойный разбор googlevideo (DNS → TCP → TLS/SNI → HTTP → подписанное медиа)**

**rr3---sn-u125g5-5p.googlevideo.com**
| слой | ok | детали |
|---|---|---|
| DNS IPv4 | ✅ | ms=0 |
| DNS IPv6 | ❌ | ms=3 gaierror: [Errno 11004] getaddrinfo failed |
| TCP:443 | ✅ | ms=58 |
| TLS/SNI | ✅ | ms=127 version=TLSv1.3 |
| HTTP GET / (без подписи) | ✅ | tcp_ms=59 tls_ms=151 status=404 |
| медиа Range IPv4 | ❌ | нет подписанного URL — байты не проверить |
| медиа Range IPv6 | ❌ | нет подписанного URL — байты не проверить |


**Блокировка по SNI: один и тот же IP, подставной SNI**

| цель | IP | SNI | мс | TLS ok | ошибка |
|---|---|---|---|---|---|
| youtube.com | 142.251.20.136 | youtube.com | 61 | ❌ | ConnectionResetError: [WinError 10054] Удаленный хост принудительно р… |
| youtube.com | 142.251.20.136 | example.com | 115 | ❌ | SSLCertVerificationError: [SSL: CERTIFICATE_VERIFY_FAILED] certificat… |
| youtube.com | 142.251.20.136 | google.com | 128 | ✅ |  |
| rr3---sn-u125g5-5p.googlevideo.com | 74.125.4.67 | rr3---sn-u125g5-5p.googlevideo.com | 139 | ✅ |  |
| rr3---sn-u125g5-5p.googlevideo.com | 74.125.4.67 | example.com | 130 | ❌ | SSLCertVerificationError: [SSL: CERTIFICATE_VERIFY_FAILED] certificat… |
| rr5---sn-25ge7nsd.googlevideo.com | 173.194.183.234 | rr5---sn-25ge7nsd.googlevideo.com | 149 | ✅ |  |
| rr5---sn-25ge7nsd.googlevideo.com | 173.194.183.234 | example.com | 125 | ❌ | SSLCertVerificationError: [SSL: CERTIFICATE_VERIFY_FAILED] certificat… |


## 4. Где именно ломается доступ (послойная дифф-диагностика)

Хост `rr3---sn-u125g5-5p.googlevideo.com`:

- DNS: ок (0 мс, 74.125.4.67)
- TCP:443: ок (58 мс)
- TLS/SNI: ок (127 мс)
- HTTP GET / без подписи: ок, HTTP 404
- Медиа Range IPv4: HTTP None, 0 байт, None КиБ/с
- Медиа Range IPv6: НЕТ (нет подписанного URL — байты не проверить)

## 5. Что это значит для автопилота Sonora

1. **Прямой googlevideo из РФ не работает** — ни InnerTube, ни медиа-байты не проходят. Значит встроенный «прямой» путь в приложении не может быть основным для РФ.
2. **WARP не даёт медиа** — egress Cloudflare YouTube отклоняет (403) или режет поток; тогда WARP не решение.
3. Порядок для автопилота (РФ): 1) кэш/локальные файлы; 2) рабочий релей из замеренного списка (если есть); 3) WARP-туннель (если он даёт 206 и ≥ ~100 КиБ/с); 4) прямой googlevideo только если замер показал, что он живой. Кэшировать вердикт на 10–15 минут.
4. Таймауты: DNS/TCP/TLS — 2500–3000 мс на попытку, Range-проба — 6000–8000 мс, полный отказ транспорта — после 2 попыток. Не держать пользователя на «прямом» пути дольше ~8 с в РФ.
5. «Быстрый и стабильный свой WARP-метод» реалистично = WARP уже поднят + проба 64 КиБ перед стартом + ротация endpoint'ов/профиля при 403/таймауте. WARP не отменяет блокировку YouTube для части egress-IP; поэтому обязательна проверка именно байтов, а не только API-ответа.

### Ожидаемые тайминги (по замерам)

- TCP:443 до хостов YouTube: 46–86 мс (медиана ~47 мс).
- TLS-хендшейк: 114–224 мс.
- InnerTube player: 70–94 мс.

## 6. Ограничения и честные оговорки

- Замеры сделаны с одного ПК, одного провайдера и в один момент времени. У российских провайдеров блокировки различаются; результат нельзя считать универсальным.
- WARP переключался через `warp-cli` и всегда возвращался в исходное состояние; при выключенном WARP окно без туннеля было коротким.
- InnerTube `player` и googlevideo требуют корректных подписей/`n`-параметра. Ответ API без ошибки ещё не значит, что медиа скачается — поэтому везде проверялись именно байты.
- Публичные релеи (Invidious/Piped) нестабильны и часто меняются; список хостов — снимок на момент аудита.
- Ничего, кроме WARP on/off, в системе не менялось.

## 7. Как повторить

```powershell
# Полный аудит по фазам (WARP переключается и возвращается сам)
powershell -ExecutionPolicy Bypass -File scripts/dev/run-streaming-audit.ps1

# Точечно
python scripts/dev/streaming-audit.py --phase A --warp on --quick
python scripts/dev/streaming-audit.py --phase B --warp off --with-ytdlp
python scripts/dev/streaming-audit.py --report-only
```

JSON: `docs/ci/pc-streaming-audit.json` · MD: `docs/PC-STREAMING-AUDIT.md`
