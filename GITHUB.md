# Sonora — данные для публикации на GitHub

Актуальная версия: **1.7.0** (`versionCode 29`), APK **223,3 МБ**.
Предыдущие релизы, заметки и контрольные суммы — в разделе Releases репозитория.

---

## 1. Настройки репозитория

| Поле | Значение |
|---|---|
| **Название** | `sonora` |
| **Короткое описание (RU)** | Открытый музыкальный плеер для Android: фонотека, «Моя волна», плейлисты и автопилот стриминга для РФ |
| **Короткое описание (EN)** | Open-source Android music player with offline library, smart playlists and a self-healing streaming autopilot |
| **Лицензия** | GNU General Public License v3.0 (`GPL-3.0`) |
| **Основная ветка** | `main` |
| **Минимальная версия Android** | 8.0 (API 26), targetSdk 34 |
| **Размер репозитория** | ~19 МБ исходников (без `build/`, `.gradle/`, `local.properties`) |

### Topics

```
android music-player kotlin jetpack-compose media3 exoplayer
offline-music local-files lyrics lrc widget material3
warp wireguard youtube-music innertube yt-dlp
streaming russia vpn-free invidious piped autopilot
open-source gpl-3-0 android-app audio-player
```

---

## 2. Релиз **v1.7.0**

**Тег:** `v1.7.0` · **Заголовок:** `Sonora 1.7.0 — автопилот стриминга: сам проверяет все способы, поднимает свой WARP и играет в РФ`

### Release notes

```markdown
## Sonora 1.7.0

### Автопилот стриминга: сам находит рабочий способ

- **Проверка всех способов**: прямой InnerTube, yt-dlp, медиашлюз и собственный
  WARP. Автопилот сам определяет, что работает на текущей сети, и выбирает
  порядок транспортов.
- **Быстрый признак DPI-блокировки**: если TLS до music.youtube.com рвётся (на
  реальной сети пользователя — RST по SNI), прямой путь и зеркала заведомо
  мертвы (замеры: yt-dlp 0/13, зеркала 0/32) — автопилот сразу идёт в WARP.
- **Автопереключение при ошибке**: при 403/сбое источника плеер сбрасывает
  кэши, поднимает другой транспорт и продолжает трек с той же позиции.
- **Периодическая перепроверка**: при старте, при смене сети, при ошибке и раз
  в 10 минут (кэш результата 30 минут).

### Свой быстрый и стабильный WARP

- **Автоматическая регистрация свежего WARP-профиля** (Curve25519 генерируется
  на устройстве, ключ наружу не уходит). API Cloudflare — когда доступен.
- **Запоминание рабочего конфига** и выбор зеркального endpoint вместо
  engage.cloudflareclient.com:2408 (в РФ не отвечает): подключение сократилось
  с 24–28 с до **1–2 с**.
- **Проверка именно звука через WARP**: Range-запрос к медиа; если InnerTube
  упирается в бот-гейт, идёт yt-dlp с рабочими клиентами.
- **Подтверждено на реальной сети РФ без VPN/WARP на ПК**: приложение само
  поднимает WARP-туннель только для своего трафика и воспроизводит трек
  (`PlaybackState.PLAYING`).

### Автоматизация проверки на ПК

- `scripts/dev/streaming-audit.py` — матрица проверок DNS/TCP/TLS/HTTP,
  SNI-DPI, InnerTube, Range-медиа, yt-dlp (10 вариантов, IPv4/IPv6),
  Invidious/Piped, переключение WARP с восстановлением в `finally`.
- `scripts/dev/run-streaming-audit.ps1` — фазовая обёртка.
- Отчёт: `docs/PC-STREAMING-AUDIT.md` + JSON в `docs/ci/`.
- Итог: без WARP YouTube полностью блокируется DPI; с WARP yt-dlp отдаёт
  10/13 вариантов на 643–2983 КиБ/с.

### Технически

- versionCode **29**, versionName **1.7.0**, targetSdk 34, minSdk 26.
- APK **~223 МБ** (Python-рантайм yt-dlp + ffmpeg), debug-подпись.
- `StreamingAutoPilot`, `TransportModels`, `WarpAutoRegistrar`,
  `WarpTunnel.importConfigText`, `PlaybackController.onStreamError`.
- Настройки: автопилот, WARP-автопилот, авто-регистрация WARP,
  автопереключение потока.

### Проверка

- `assembleDebug` — BUILD SUCCESSFUL;
- `testDebugUnitTest` — 59 тестов, 0 ошибок;
- `lintDebug` — без ошибок;
- на эмуляторе Android 14: собственный WARP подключён за 1–2 с, трек играет
  (`PLAYING`) при выключенном WARP на ПК; «Проверить все способы» показывает
  выбранный транспорт и тайминги.
```

### Файлы релиза (assets)

| Файл | Размер | SHA-256 |
|---|---|---|
| `Sonora-1.7.0-debug.apk` | 234 105 648 Б (223,3 МБ) | см. `Sonora-1.7.0-SHA256SUMS.txt` |
| `Sonora-1.7.0-source.zip` | ≈ 19,1 МБ | см. `Sonora-1.7.0-SHA256SUMS.txt` |
| `Sonora-1.7.0-SHA256SUMS.txt` | — | контрольные суммы |

APK подписан debug-ключом — для ручной установки, не для Google Play.

### Установка

```bash
adb install -r Sonora-1.7.0-debug.apk
```

После установки: «Настройки → Поток и сеть → Автопилот» — включён по умолчанию;
там же «Проверить все способы» и «Разрешить VPN для WARP» (Android спросит один
раз). При первом воспроизведении в РФ автопилот сам поднимет свой WARP.

---

## 3. Документация

- [README.md](README.md) — возможности, скриншоты, сборка;
- [docs/STREAMING-RF.md](docs/STREAMING-RF.md) — автопилот, свой WARP, ограничения;
- [docs/PC-STREAMING-AUDIT.md](docs/PC-STREAMING-AUDIT.md) — реальные замеры на сети;
- [docs/README.md](docs/README.md) — полный индекс;
- [CHANGELOG.md](CHANGELOG.md) — история версий.

## 4. Важные оговорки

- **InnerTube и yt-dlp** — неофициальные клиенты YouTube Music; YouTube может
  изменить протокол. Приложение не связано с Google/YouTube.
- **Автопилот** — не «магия»: если провайдер блокирует и WireGuard-эндпоинты
  WARP, приложение честно покажет это и предложит своё зеркало/прокси.
  На реальной сети пользователя WARP — единственный рабочий egress, и Sonora
  теперь поднимает его сама.
- **WARP** — обычный WireGuard-туннель только для трафика Sonora; авторегистрация
  создаёт анонимный профиль Cloudflare. Перед публичной сборкой удалите
  `app/src/main/assets/warp/` (встроенные ключи — для личного использования).
- **Лицензия GPL-3.0** — см. `THIRD-PARTY-NOTICES.md`.
- Скачивание с YouTube нарушает условия сервиса; ответственность — на
  пользователе. DRM не обходится.
