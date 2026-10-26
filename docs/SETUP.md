# Установка и сборка Sonora

## 1. Что нужно

| Компонент | Версия | Зачем |
|---|---|---|
| Android Studio | Ladybug (2024.2) или новее | IDE + встроенный Gradle |
| JDK | **17** (Android Studio Bundled JDK подходит) | AGP 8.5 требует Java 17 |
| Android SDK Platform | **API 34** | `compileSdk = 34` |
| Android SDK Build-Tools | 34.0.0 | сборка |
| Устройство | Android **8.0 (API 26)** и новее | `minSdk = 26` |
| Gradle | 8.9 (скачается wrapper'ом) | AGP 8.5.2 |

Проверить Java:

```powershell
java -version   # ожидается 17.x
```

## 2. Открыть проект

1. Распакуйте папку `Sonora` (там, где лежит `settings.gradle.kts`).
2. Android Studio → **File → Open** → выберите папку `Sonora` (не `app`!).
3. Дождитесь **Gradle sync**. Первый sync скачает AGP, Compose, Media3 — нужен интернет.
   Wrapper (Gradle 8.9) уже включён в проект, отдельная установка Gradle не требуется.
4. Если Studio ругается на JDK — **Settings → Build, Execution, Deployment → Build Tools → Gradle**
   → Gradle JDK = 17 (Bundled).

## 3. Сборка

Debug-сборка:

```powershell
.\gradlew.bat :app:assembleDebug
```

APK появится в `app\build\outputs\apk\debug\app-debug.apk`.

Установка на подключённый по USB телефон с включённой отладкой:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Release (по желанию) — нужен keystore:

```powershell
keytool -genkey -v -keystore sonora.jks -keyalg RSA -keysize 2048 -validity 10000 -alias sonora
.\gradlew.bat :app:assembleRelease
```
и прописать `signingConfigs` в `app/build.gradle.kts`.

## 4. Первый запуск

1. Приложение попросит доступ к аудиофайлам и уведомлениям — разрешите (без уведомлений
   не будет кнопок управления в шторке).
2. **Медиатека → «Пересканировать»** — подтянутся локальные треки.
3. **Настройки → Аккаунты и сервисы** — там подключаются Spotify / YouTube / SoundCloud.

---

## 5. Ключи и аккаунты

### 5.1. Spotify

1. Откройте <https://developer.spotify.com/dashboard> и войдите **со Spotify Premium**
   (с февраля 2026 Development Mode требует Premium у владельца приложения).
2. **Create app**: имя любое, Redirect URI — строго:
   ```
   sonora://oauth/spotify
   ```
3. Отметьте Web API / Web Playback SDK (для нашего сценария достаточно Web API).
4. Скопируйте **Client ID** (Client Secret не нужен — используется PKCE).
5. В Sonora: **Настройки → Аккаунты и сервисы → Spotify Client ID → Сохранить**.
6. **Подключения → Spotify → «Войти»** — откроется браузер, после согласия вернёт в приложение.
7. В Dashboard добавьте свой аккаунт в **User Management** (Development Mode: до 5 пользователей).

Ограничения, о которых нужно знать заранее:

- полные треки сторонним приложениям Spotify не отдаёт — только превью (и с 27.11.2024
  новые приложения могут их не получать вовсе);
- квота Development Mode небольшая; при 429 приходит `QUOTA_EXCEEDED`;
- для «настоящего» воспроизведения нужен Spotify Premium + официальный клиент Spotify
  (App Remote), которого в этой версии нет — есть переход по deep link.

### 5.2. YouTube Music (InnerTube)

Настройка не нужна: Sonora работает анонимно через InnerTube, как Metrolist.
Поиск, плейлисты, очередь, тексты и аудиопоток доступны сразу после установки.

- ключи Google Cloud, OAuth-клиент и квоты Data API **не используются**;
- плейлист можно импортировать по ссылке: **Медиатека → YouTube → «По ссылке»**
  (`music.youtube.com/playlist?list=…`, `PL…`, `OLAK5uy_…`);
- личная библиотека: **Подключения → Google-аккаунт → «Войти в Google»** (WebView,
  cookie/SAPISIDHASH, как в Metrolist);
- скачивание трека: **«Скачать»** в меню трека или кнопка в плеере. Файл и
  обложка сохраняются в приватной папке приложения, трек появляется в «Треках»,
  «Альбомах» и «Исполнителях». Так же сохраняются прямые ссылки и открытые
  каталоги; подробности — [DOWNLOADS.md](DOWNLOADS.md).

Если YouTube меняет протокол и поток перестаёт открываться — обновите зависимость
`com.github.MetrolistGroup.innertubex` в `app/build.gradle.kts`.

Для PoToken используется скрытый WebView с BotGuard (`assets/po_token.html`), как в Metrolist.
WebView нужен только для генерации токена; если его нет или он сломан, приложение
автоматически переходит на клиентов без токена. На устройствах без WebView (некоторые TV)
это ожидаемый fallback, а не ошибка.

### 5.3. SoundCloud

Официальный API SoundCloud сейчас требует подписку **Artist Pro** для регистрации приложения,
а все клиенты считаются confidential — `client_secret` нельзя держать в APK, нужен свой бэкенд.
Кроме того, API Terms of Use запрещают сохранение файлов и объединение SoundCloud с другими
сервисами в одном плеере.

Поэтому в Sonora SoundCloud — **только поиск и переход в приложение**:

1. Если у вас уже есть `client_id` — вставьте его в **Настройки → SoundCloud client_id**.
2. Без ключа кнопка «Открыть SoundCloud» просто запускает приложение/сайт.

### 5.4. Jamendo (опционально)

Бесплатный каталог Creative Commons; ключ выдаётся бесплатно.

1. <https://devportal.jamendo.com/> → зарегистрируйте приложение → скопируйте **Client ID**.
2. Sonora: **Настройки → Открытые каталоги → Jamendo client_id → Сохранить**.
3. Вкладка **Обзор** → чип **Jamendo** начнёт искать.

Без ключа работает Internet Archive — он не требует регистрации вообще.

### 5.5. Онлайн-метаданные и тексты

Ключи не нужны. Используются:

- **MusicBrainz** — альбом, год, MBID и канонический author-credit (лимит 1 запрос/сек);
- **Cover Art Archive** — обложка по release id;
- **iTunes** — обложка 1000×1000, альбом, год;
- **Deezer** — обложка 1000×1000, альбом, фото автора;
- **TheAudioDB** — обложка, фото, биография и жанр автора;
- **Wikipedia** (EN/RU) — фото и биография автора;
- **InnerTube** — fallback-поиск обложки/автора в YouTube Music;
- **LRCLIB** — текст песни.

Включить: **Настройки → Библиотека → «Искать метаданные и тексты онлайн»** (тогда обогащение
запускается после каждого сканирования) либо нажать **«Обогатить метаданные сейчас»**.
Сервис заполняет только пустые поля, улучшает маленькие обложки YouTube и добавляет
фото/биографию автора; существующие теги не затираются. Подробности — [METADATA.md](METADATA.md).

### 5.6. Проверка OAuth-редиректа

Android-манифест уже содержит фильтр:

```xml
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="sonora" android:host="oauth" />
</intent-filter>
```

Обработчик — `MainActivity.handleDeepLink()` → `AppGraph.handleOAuth()`. Механизм остался
для Spotify и SoundCloud; YouTube больше не использует OAuth.

### 5.7. Встроенный YouTube Music

Отдельный экран (`Настройки → Аккаунты и сервисы → YouTube Music` или
`Подключения → «Открыть music.youtube.com»`). Загружается официальный `music.youtube.com`
в WebView:

- войдите в Google-аккаунт **на самой странице** — это обычный браузерный вход;
- Google может ответить «браузер может быть небезопасным» и заблокировать вход — это
  ограничение Google, обойти его нельзя;
- воспроизведение — только пока экран открыт (фонового режима нет), с рекламой,
  без блокировщика и без скачивания; кнопка «В браузере» открывает тот же сайт в Chrome.

### 5.8. Импорт библиотеки из Google Takeout

1. <https://takeout.google.com/> → выберите **YouTube and YouTube Music** → экспорт.
2. Скачайте полученный ZIP (или распакуйте нужные CSV).
3. Sonora: **Настройки → Библиотека → «Импорт библиотеки из Google Takeout»** → выберите
   файл (ZIP или CSV).
4. Успешный импорт показывает «Треков: N»; импортированные треки появляются
   в **Медиатека → YouTube** и играются через InnerTube.

Ключи API не нужны. Поддерживаются `music library-songs.csv` и CSV с колонками
`Song Title / Artist / Album / Video ID / Playlist Title`. Дубликаты отбрасываются.

### 5.9. Импорт плейлиста из другого сервиса

1. Экспортируйте список треков в CSV/TSV, JSON, M3U/M3U8 или TXT:
   - Spotify — CSV через Exportify;
   - Apple Music — CSV/JSON;
   - YouTube Music — CSV или Google Takeout.
2. **Настройки → Импорт из других платформ → «Импортировать плейлист из файла»**.
3. Sonora найдёт совпадения в локальной библиотеке и InnerTube и создаст плейлист
   «Импорт: имя файла». Прогресс и число ненайденных треков видны в настройках.
4. Встроенная инструкция — кнопка **«Как это работает»**; подробные форматы,
   примеры и лимиты — [IMPORT.md](IMPORT.md).

Это импорт **списка**, а не аудио: полные треки Spotify/SoundCloud не
скачиваются. Сохранение доступных потоков описано в [DOWNLOADS.md](DOWNLOADS.md).

---

## 6. Типовые проблемы

| Симптом | Причина | Решение |
|---|---|---|
| `Your project path contains non-ASCII characters` | в пути к проекту кириллица («Егор Груздев») | в `gradle.properties` уже стоит `android.overridePathCheck=true`; если убрали — верните или перенесите проект в путь без кириллицы |
| `Unsupported class file major version` | запущен JDK 17 или 11 вместо 21 | Settings → Build Tools → Gradle → Gradle JDK = 21 |
| `Failed to resolve: androidx.compose...` | нет интернета/корпоративный прокси | проверьте сеть, настройте proxy в `gradle.properties` |
| `SDK location not found` | не задан Android SDK | создайте `local.properties` со строкой `sdk.dir=C\:\\Android\\Sdk` |
| `Compose compiler version mismatch` | Kotlin ≠ Compose Compiler | версия Compose-плагина задаётся Kotlin 2.4.20 (`org.jetbrains.kotlin.plugin.compose`), не меняйте её отдельно |
| `Duplicate class com.metrolist.innertubex...` | подключён aggregator-POM вместо KMP-модуля | используйте `com.github.MetrolistGroup.innertubex:innertubex`, а не `com.github.MetrolistGroup:innertubex` |
| Пустая медиатека | нет разрешения или файлы в нестандартной папке | разрешите доступ к аудио, нажмите «Пересканировать» |
| OAuth-возврат не срабатывает | другой redirect URI / браузер открыл в WebView | сверьте URI, попробуйте Chrome/Custom Tabs |
| Spotify 403 на плейлистах | Development Mode ограничил endpoint | попробуйте «Мне нравится», обновите приложение Spotify |

## 7. Статус сборки и известные ограничения

Проверено в среде разработки (Windows, JDK 21, Android SDK 37):

- `gradlew :app:assembleDebug` — **BUILD SUCCESSFUL**;
- смоук-тесты InnerTube: поиск, плейлист, поток (itag 251), очередь «дальше», текст;
- локальный плеер, автоочередь, виджет, встроенный YouTube Music, импорт Takeout,
  импорт плейлиста по ссылке и из CSV/JSON/M3U/TXT, сохранение InnerTube-треков,
  синхронизированные тексты. Скриншоты — в [screenshots](screenshots).

### Как добавить виджет

Долгое нажатие на свободном месте рабочего стола → **Widgets** → **Sonora** → развернуть
список и выбрать один из вариантов:

| Виджет | Размер | Что показывает |
|---|---|---|
| Компактный плеер 2×2 | 2×2 | обложка, название/исполнитель, прогресс, play/pause |
| Плеер 4×1 | 4×1 | обложка 56dp, две строки текста, прогресс, prev/play/next |
| Плеер 4×2 с обложкой | 4×2 | обложка 92dp, крупный текст, прогресс, все кнопки |
| Большой плеер 4×4 | 4×4 | большая обложка, 18sp заголовок, кнопки 48dp |
| «Текущий трек и управление…» | авто | прежний виджет, сам выбирает раскладку по размеру |

Кнопки управляют воспроизведением напрямую (broadcast в `SonoraWidgetProvider`), состояние
обновляется из процесса приложения без фонового опроса. Фиксированные виджеты не растягиваются
(`resizeMode=none`), авто-виджет — растягивается.

Ограничения версии 0.7.0 (это не баги, а осознанный объём):

- в списке виджетов пять записей: четыре фиксированных + авто-виджет (его нельзя убрать,
  не сломав уже размещённые экземпляры);
- 4×4 показывает обложку «широким кропом»: RemoteViews не умеет строгие пропорции;
- кэш метаданных хранит и «ничего не найдено» (30 дней) — это специально, чтобы не дёргать
  MusicBrainz/LRCLIB при каждом запуске; «Очистить кэш метаданных» сбрасывает и отметки,
  после чего поиск можно повторить;
- бесконечная очередь повторяет треки, когда библиотека маленькая (например, 5 треков):
  иначе очередь просто оборвалась бы. При большой фонотеке повторы редки;
- виджет: три раскладки по размеру (2×2, 4×1, 4×4) — на очень узкой ячейке < 2×2 элементы
  могут подрезаться; порог «большой» раскладки — 110 dp по высоте (константа
  `LARGE_MIN_HEIGHT_DP` в `SonoraWidget`);

- Google-вход требует собственного OAuth-клиента «Desktop app» (Client ID + Secret);
  без него кнопка показывает подсказку. Полный вход проверить без реального клиента нельзя;
- «вход как в Metrolist» (InnerTube/YouTube Music) сознательно не реализован — это обход
  технической защиты сервиса;
- встроенный YouTube Music — обычный браузер: реклама не блокируется, фон и скачивание
  недоступны, Google может блокировать вход в WebView;
- поиск по API (Spotify/YouTube/SoundCloud) убран из интерфейса; поля API-ключей тоже;
- импорт Takeout: реальные плейлисты Takeout (`playlists/*.csv` с одними Video ID)
  пока не сопоставляются с библиотекой — импортируются треки из `music-library-songs.csv`;
- скачивание — только свободный контент из **Обзора** (Archive.org/Jamendo) и свои файлы;
- **Jamendo** без client_id не работает (Internet Archive работает всегда);
- в Archive.org часть айтемов не содержит пригодных аудиофайлов — такие пропускаются;
- тексты LRCLIB — общественная база: изредка под верным названием лежит чужой текст.
  Приложение отсекает явные несовпадения по исполнителю и длительности (±20 %),
  но 100 % гарантии дать нельзя;
- виджет — классический RemoteViews: без обложки и анимаций, текст в узкой ячейке
  обрезается многоточием (это ограничение RemoteViews, не приложения);
- нет unit/UI-тестов и CI.

Дальше: [ARCHITECTURE.md](ARCHITECTURE.md#8-точки-расширения).
