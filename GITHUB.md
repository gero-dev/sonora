# Sonora — данные для публикации на GitHub

Всё, что нужно для создания репозитория и релиза. Проверено на версии **0.9.8**
(`versionCode 19`, `versionName 0.9.8`, APK 39 749 010 байт).

---

## 1. Настройки репозитория

| Поле | Значение |
|---|---|
| **Название** | `sonora` |
| **Короткое описание (About, RU)** | Открытый музыкальный плеер для Android: локальная фонотека, WARP, виджеты и «Моя волна» |
| **Короткое описание (About, EN)** | Open-source Android music player: offline library, WARP tunnel, widgets, smart “My Wave” |
| **Сайт (Website)** | *(оставить пустым)* |
| **Лицензия** | GNU General Public License v3.0 (`GPL-3.0`) |
| **Основная ветка** | `main` |
| **Языки репозитория** | Kotlin, XML (Compose/ресурсы), Python (dev-скрипты) |
| **Минимальная версия Android** | 8.0 (API 26), targetSdk 34 |
| **Размер репозитория** | ~13 МБ исходников (без `build/`, `.gradle/`, `local.properties`) |

### Topics (теги для GitHub)

```
android music-player kotlin jetpack-compose media3 exoplayer
offline-music local-files lyrics lrc widget material3
warp wireguard vpn youtube-music innertube
open-source gpl-3-0 android-app audio-player
```

---

## 2. Описание для README (первые строки)

> **Sonora** — открытый музыкальный плеер для Android. Локальная фонотека играет
> полностью и офлайн; YouTube Music работает через InnerTube — тот же движок, что
> в Metrolist: поиск, плейлисты, очередь «дальше», тексты и воспроизведение прямо
> в приложении. Можно войти в Google, слушать личные плейлисты, скачивать треки с
> обложками и собирать «Мою волну» из локальной и онлайн-музыки. Свободную музыку
> можно искать и скачивать из Internet Archive и Jamendo.

### Бейджи

```markdown
![CI](https://img.shields.io/badge/CI-assembleDebug%20%2B%20lint-blue)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4.20-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202024.06-4285F4?logo=jetpackcompose&logoColor=white)
![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC84?logo=android&logoColor=white)
![License](https://img.shields.io/badge/license-GPL--3.0-blue)
```

---

## 3. Релиз **v0.9.8**

**Тег:** `v0.9.8` · **Заголовок:** `Sonora 0.9.8 — WARP из коробки, метаданные из файлов, обложки`

### Что нового (черновик release notes)

```markdown
## Sonora 0.9.8

### WARP из коробки
- Три WARP-конфига идут в комплекте: при первом запуске уже в списке, первый выбран.
- «Восстановить встроенные конфиги» в настройках, если удалили.
- Импорт своих `.conf` через файл сохранён; лимит 8 конфигов, автоперебор как раньше.
- ВАЖНО: приватные ключи встроенных конфигов лежат в APK — сборка для личного
  использования; перед публикацией удалите `app/src/main/assets/warp/`.

### Метаданные из файлов
- Чтение sidecar-файлов рядом с музыкой: `<имя>.json`, `<имя>.nfo`, `<имя>.lrc`,
  `album.json`, `cover.jpg` / `folder.jpg` — альбом, год, обложка и текст.
- «Импортировать метаданные из файла»: JSON/CSV/NFO, сопоставление по исполнителю
  и названию, результат «Совпало: N, обновлено: M».
- Подсказки альбома/года теперь уходят в онлайн-поиск — базы находят точнее.

### Обложки
- Сначала встроенная обложка из аудиофайла (ID3/FLAC/MP4) и обложка из папки —
  без сети и без «битых» MediaStore-ссылок.
- Онлайн-подбор стал терпимее: несколько вариантов запроса к iTunes/Deezer,
  ослабленные фильтры, оценка кандидатов, обложка альбома как fallback.
- Кнопка «Найти пропущенные обложки» с прогрессом (до 40 за запуск).

### Прочее
- Версия 0.9.8 (versionCode 19), документация обновлена.
```

### Файлы релиза (assets)

| Файл | Размер | SHA-256 |
|---|---|---|
| `Sonora-0.9.8-debug.apk` | 39 749 010 Б (37,9 МБ) | `e4a089d1ecb394a7fa4a98f3b634925efa9f9f7dab0f0dc7f6db7987341cb6b1` |
| `Sonora-0.9.8-source.zip` | ≈ 15 800 000 Б (≈ 15,0 МБ) | см. `Sonora-0.9.8-SHA256SUMS.txt` рядом с архивом |

APK подписан **debug-ключом** — для установки вручную. Для публикации в магазинах
нужен release-keystore.

### Установка

```bash
adb install -r Sonora-0.9.8-debug.apk
```
Или скопировать APK на телефон и открыть (разрешить установку из неизвестных источников).

---

## 4. Что лежит в репозитории

```
sonora/
├── .github/                     шаблоны Issue/PR
│   └── workflows/               (пусто; CI лежит в docs/ci/android.yml)
├── app/                         Android-приложение (Kotlin, Compose, Media3)
│   ├── src/main/java/app/sonora/
│   │   ├── core/                модели, настройки, HTTP, безопасное хранилище
│   │   ├── data/                библиотека, метаданные, источники, загрузки
│   │   ├── net/warp/            WireGuard-туннель (WARP), конфиги и перебор
│   │   ├── playback/            ExoPlayer, MediaSessionService, очередь
│   │   ├── ui/                  экраны, компоненты, тема, онбординг
│   │   └── widget/              виджеты 2×2, 4×1, 4×2, 4×4
│   └── src/main/res/            ресурсы, раскладки виджетов, иконки
├── docs/                        документация (20 файлов: гайды, архитектура, лицензии)
├── gradle/wrapper/              Gradle 9.7.1 wrapper
├── scripts/dev/                 вспомогательные скрипты для тестовых файлов
├── CHANGELOG.md                 история версий (0.1.0 → 0.9.8)
├── README.md                    описание, возможности, скриншоты, сборка
├── LICENSE                      GPL-3.0
└── THIRD-PARTY-NOTICES.md       сторонние библиотеки и лицензии
```

### Документация

`docs/README.md` — индекс. `docs/ARCHITECTURE.md` · `docs/SETUP.md` · `docs/IMPORT.md` ·
`docs/DOWNLOADS.md` · `docs/METADATA.md` · `docs/WARP.md` · `docs/WAVE.md` · `docs/PLAYBACK.md` ·
`docs/LYRICS.md` · `docs/WIDGET.md` · `docs/TAKEOUT.md` · `docs/GOOGLE-ACCOUNT.md` ·
`docs/DISCOVERY.md` · `docs/INTEGRATIONS.md` · `docs/UI.md` · `docs/LEGAL.md` ·
`docs/PLATFORM-FACTS.md` · `docs/ROADMAP.md` · `docs/REVIEW.md` · `CHANGELOG.md`

### CI (`docs/ci/android.yml`)

- JDK 21, Android SDK 37, Gradle wrapper;
- `./gradlew :app:assembleDebug :app:lintDebug`;
- APK загружается артефактом `sonora-debug-apk`.

Файл специально лежит вне `.github/workflows`: так его можно закоммитить без
OAuth-scope `workflow`. Чтобы включить CI, скопируйте его в
`.github/workflows/android.yml` и сделайте коммит (нужен токен со scope
`workflow` или push через аккаунт с правом на Actions).

---

## 5. Публикация с нуля

```bash
cd sonora
git init
git add -A
git commit -m "feat: Sonora 0.9.8 — WARP из коробки, метаданные из файлов, обложки"
git branch -M main
git remote add origin https://github.com/<username>/sonora.git
git push -u origin main
```

Релиз:

```bash
git tag -a v0.9.8 -m "Sonora 0.9.8"
git push origin v0.9.8
```

Затем на GitHub: **Releases → Draft a new release** → тег `v0.9.8` → вставить
release notes выше → прикрепить `Sonora-0.9.8-debug.apk` и `Sonora-0.9.8-source.zip`.

В **Settings → General**: Description («Открытый музыкальный плеер для Android…»),
Topics (список выше), License → GPL-3.0. В **Settings → Code and automation → Actions**
включить Workflows.

---

## 6. Важные оговорки (для README/About)

- **InnerTube** — неофициальный клиентский API YouTube Music. YouTube может изменить
  протокол; приложение не связано с Google/YouTube. Подробности — `docs/LEGAL.md`,
  `docs/PLATFORM-FACTS.md`.
- **WARP** — обычный WireGuard-туннель, работает **только для трафика Sonora**
  (per-app VPN), не меняет маршрутизацию остальных приложений. В 0.9.8 три конфига
  идут **внутри APK** (`app/src/main/assets/warp/`) — это осознанное решение для
  личного использования: приватные ключи видны тому, у кого есть APK, поэтому
  **перед публикацией сборки удалите `app/src/main/assets/warp/`**. Свои конфиги
  пользователь по-прежнему импортирует файлом. Подробности — `docs/WARP.md`.
- **Лицензия**: GPL-3.0 (совместимо с зависимостью InnerTubeX, GPL-3.0) — см.
  `THIRD-PARTY-NOTICES.md`.
- Проект не обходит DRM. Кнопка сохранения работает для InnerTube/YouTube,
  прямых HTTPS-потоков и открытых каталогов (Internet Archive, Jamendo);
  скачивание с YouTube может нарушать условия сервиса — ответственность пользователя.
- APK в релизе — debug-подпись, не для Google Play.
