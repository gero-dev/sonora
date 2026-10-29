# Sonora — данные для публикации на GitHub

Актуальная версия: **1.9.1** (`versionCode 32`), APK ≈ **223 МБ**.
Предыдущие релизы, заметки и контрольные суммы — в разделе Releases репозитория.

---

## 1. Настройки репозитория

| Поле | Значение |
|---|---|
| **Название** | `sonora` |
| **Короткое описание (RU)** | Открытый музыкальный плеер для Android: фонотека, «Моя волна», автопилот стриминга и обновления из GitHub |
| **Короткое описание (EN)** | Open-source Android music player with offline library, streaming autopilot and in-app GitHub updates |
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
in-app-update github-releases open-source gpl-3-0 android-app
```

---

## 2. Релиз **v1.9.1**

**Тег:** `v1.9.1` · **Заголовок:** `Sonora 1.9.1 — надёжное скачивание обновлений (докачка)`

### Release notes

```markdown
## Sonora 1.9.1

### Обновления из GitHub прямо в приложении

- **Автопроверка раз в сутки**: последний релиз берётся из GitHub Releases
  (API; при лимите — резервный путь `releases/latest` без API). Результат
  кэшируется, чтобы не дёргать GitHub зря.
- **Добровольное скачивание**: приложение ничего не устанавливает само. В
  «Настройки → Обновления» — номер новой версии, размер, «Что нового», кнопка
  «Скачать и установить» с прогрессом, отменой и проверкой SHA-256 из `digest`
  релиза.
- Установка отдаётся системному установщику через FileProvider; если установка
  из неизвестных источников запрещена — кнопка ведёт в нужную настройку Android.
- «Позже» запоминает версию, чтобы не напоминать о ней повторно;
  «Проверить обновления» и «Открыть релизы на GitHub» доступны в любой момент.
- Автопроверку можно выключить переключателем.

### Технически

- versionCode **32**, versionName **1.9.1**, targetSdk 34, minSdk 26.
- `UpdateManager`, `UpdateModels`, FileProvider `app.sonora.updates`,
  `REQUEST_INSTALL_PACKAGES`, `buildConfig` включён.
- 59 unit-тестов, lint без ошибок.
```

### Файлы релиза (assets)

| Файл | Размер | SHA-256 |
|---|---|---|
| `Sonora-1.9.1-debug.apk` | ≈ 223 МБ | см. `Sonora-1.9.1-SHA256SUMS.txt` |
| `Sonora-1.9.1-source.zip` | ≈ 19 МБ | см. `Sonora-1.9.1-SHA256SUMS.txt` |
| `Sonora-1.9.1-SHA256SUMS.txt` | — | контрольные суммы |

APK подписан debug-ключом — для ручной установки, не для Google Play.

### Установка

```bash
adb install -r Sonora-1.9.1-debug.apk
```

После установки: «Настройки → Обновления → Проверить обновления». Дальнейшие
версии приложение найдёт само и предложит скачать.

---

## 3. Документация

- [README.md](README.md) — возможности, скриншоты, сборка;
- [docs/STREAMING-RF.md](docs/STREAMING-RF.md) — автопилот, свой WARP, ограничения;
- [docs/PC-STREAMING-AUDIT.md](docs/PC-STREAMING-AUDIT.md) — реальные замеры на сети;
- [docs/UPDATE.md](docs/UPDATE.md) — обновления из GitHub;
- [docs/README.md](docs/README.md) — полный индекс;
- [CHANGELOG.md](CHANGELOG.md) — история версий.

## 4. Важные оговорки

- **Обновления** не устанавливаются автоматически: только проверка и предложение,
  скачивание и установка — по нажатию пользователя.
- **Debug-подпись**: обновление работает, если установленная копия подписана тем
  же ключом (обычная установка из Releases).
- **InnerTube и yt-dlp** — неофициальные клиенты YouTube Music; YouTube может
  изменить протокол. Приложение не связано с Google/YouTube.
- **Лицензия GPL-3.0** — см. `THIRD-PARTY-NOTICES.md`.
- Скачивание с YouTube нарушает условия сервиса; ответственность — на
  пользователе. DRM не обходится.
