# Sonora — данные для публикации на GitHub

Всё, что нужно для создания репозитория и релиза. Проверено на версии **0.9.6**
(`versionCode 17`, `versionName 0.9.6`, APK 39 394 366 байт).

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

## 3. Релиз **v0.9.6**

**Тег:** `v0.9.6` · **Заголовок:** `Sonora 0.9.6 — скачивание InnerTube и полные метаданные авторов`

### Что нового (черновик release notes)

```markdown
## Sonora 0.9.6

### Исправлено
- **«Не удалось скачать» для треков из InnerTube**: поддержаны bounded-range
  потоки InnerTubeX (только `Range`-куски), добавлены резервный player-поток,
  сброс кэша ссылки при 403/410/416 и корректные заголовки. Теперь скачивание
  идёт как в Metrolist, а не падает на первом полном GET.
- В интерфейсе показывается настоящая причина ошибки (`HTTP 403`, `Только Wi-Fi`,
  `Range не принят сервером`) вместо общей фразы.

### Добавлено
- **Больше источников метаданных**: MusicBrainz, Cover Art Archive, iTunes,
  Deezer, TheAudioDB, Wikipedia (EN/RU) и InnerTube-fallback.
- **Авторы и обложки**: фото автора, биография и жанр; обложки до 1000×1000;
  имена `Nirvana - Topic`/`NirvanaVEVO` сводятся к `Nirvana`, дубли в Медиатеке
  исчезают. Для скачанных треков фото автора кэшируется офлайн.
- Кэш метаданных с версией схемы: старые записи один раз перезапрашиваются.

### Сохранено из 0.9.5
- Импорт плейлистов CSV/JSON/M3U/TXT, сохранение любого играемого трека,
  WARP на главном, бесконечная «Моя волна», импорт Takeout, караоке-тексты.

**Полный APK:** `Sonora-0.9.6-debug.apk` · **Исходники:** `Sonora-0.9.6-source.zip`
```

### Файлы релиза (assets)

| Файл | Размер | SHA-256 |
|---|---|---|
| `Sonora-0.9.6-debug.apk` | 39 394 366 Б (37.6 МБ) | `93cef0349271b8a1f8a4d79d1f620a99976ba30458fec5cdf653cc2584ce532b` |
| `Sonora-0.9.6-source.zip` | ≈ 12,3 МБ | указан в `Sonora-0.9.6-source.zip.sha256` рядом с архивом |

APK подписан **debug-ключом** — для установки вручную. Для публикации в магазинах
нужен release-keystore.

### Установка

```bash
adb install -r Sonora-0.9.6-debug.apk
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
├── docs/                        документация (18 файлов: гайды, архитектура, лицензии)
├── gradle/wrapper/              Gradle 9.7.1 wrapper
├── scripts/dev/                 вспомогательные скрипты для тестовых файлов
├── CHANGELOG.md                 история версий (0.1.0 → 0.9.6)
├── README.md                    описание, возможности, скриншоты, сборка
├── LICENSE                      GPL-3.0
└── THIRD-PARTY-NOTICES.md       сторонние библиотеки и лицензии
```

### Документация

`docs/README.md` — индекс. `docs/ARCHITECTURE.md` · `docs/SETUP.md` · `docs/IMPORT.md` ·
`docs/DOWNLOADS.md` · `docs/METADATA.md` · `docs/WARP.md` · `docs/LYRICS.md` · `docs/WIDGET.md` ·
`docs/TAKEOUT.md` · `docs/GOOGLE-ACCOUNT.md` · `docs/DISCOVERY.md` · `docs/REVIEW.md` ·
`docs/INTEGRATIONS.md` · `docs/UI.md` · `docs/LEGAL.md` · `docs/PLATFORM-FACTS.md` ·
`docs/ROADMAP.md` · `docs/REVIEW.md` · `CHANGELOG.md`

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
git commit -m "feat: Sonora 0.9.6"
git branch -M main
git remote add origin https://github.com/<username>/sonora.git
git push -u origin main
```

Релиз:

```bash
git tag -a v0.9.6 -m "Sonora 0.9.6"
git push origin v0.9.6
```

Затем на GitHub: **Releases → Draft a new release** → тег `v0.9.6` → вставить
release notes выше → прикрепить `Sonora-0.9.6-debug.apk` и `Sonora-0.9.6-source.zip`.

В **Settings → General**: Description («Открытый музыкальный плеер для Android…»),
Topics (список выше), License → GPL-3.0. В **Settings → Code and automation → Actions**
включить Workflows.

---

## 6. Важные оговорки (для README/About)

- **InnerTube** — неофициальный клиентский API YouTube Music. YouTube может изменить
  протокол; приложение не связано с Google/YouTube. Подробности — `docs/LEGAL.md`,
  `docs/PLATFORM-FACTS.md`.
- **WARP** — обычный WireGuard-туннель, работает **только для трафика Sonora**
  (per-app VPN), не меняет маршрутизацию остальных приложений; конфиги импортирует
  пользователь, приватные ключи не попадают в APK/репозиторий.
- **Лицензия**: GPL-3.0 (совместимо с зависимостью InnerTubeX, GPL-3.0) — см.
  `THIRD-PARTY-NOTICES.md`.
- Проект не обходит DRM. Кнопка сохранения работает для InnerTube/YouTube,
  прямых HTTPS-потоков и открытых каталогов (Internet Archive, Jamendo);
  скачивание с YouTube может нарушать условия сервиса — ответственность пользователя.
- APK в релизе — debug-подпись, не для Google Play.
