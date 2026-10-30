# Документация Sonora

Полный набор руководств по приложению. Если вы только начинаете — прочитайте
корневой [README.md](../README.md), затем нужный раздел ниже.

## Для пользователя

| Документ | О чём |
|---|---|
| [SETUP.md](SETUP.md) | установка, первый запуск, сборка, ключи сервисов, типовые ошибки |
| [IMPORT.md](IMPORT.md) | импорт своих аудиофайлов и плейлистов из Spotify/Apple Music/YouTube Music |
| [DOWNLOADS.md](DOWNLOADS.md) | сохранение треков: InnerTube, прямые ссылки, открытые каталоги, статусы и ошибки |
| [METADATA.md](METADATA.md) | источники метаданных, авторы, фото и биографии, обложки, кэш |
| [GOOGLE-ACCOUNT.md](GOOGLE-ACCOUNT.md) | вход в Google, личные плейлисты и «Понравившиеся», приватность |
| [TAKEOUT.md](TAKEOUT.md) | импорт библиотеки YouTube Music из Google Takeout |
| [LYRICS.md](LYRICS.md) | синхронизированные тексты, LRC и караоке-режим |
| [WIDGET.md](WIDGET.md) | виджеты рабочего стола: размеры, обложка, обновление |
| [WAVE.md](WAVE.md) | «Моя волна»: только музыка, история, новизна, разнообразие, доля устройства |
| [PLAYBACK.md](PLAYBACK.md) | режимы проигрывания: онлайн + устройство или только загруженное, стриминг в очереди |
| [HISTORY.md](HISTORY.md) | вкладка «История»: треки, исполнители, статистика и приватность |
| [LEGAL.md](LEGAL.md) | что можно и чего нельзя: лицензии, ToS, DRM |
| [PLATFORM-FACTS.md](PLATFORM-FACTS.md) | детальный разбор API и условий сервисов с источниками |

## Для разработчика

| Документ | О чём |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | слои, потоки данных, InnerTube, воспроизведение, загрузка, импорт |
| [INTEGRATIONS.md](INTEGRATIONS.md) | устройство интеграций: Spotify, YouTube Music, SoundCloud, DownloadManager |
| [STREAMING-RF.md](STREAMING-RF.md) | история стриминга без VPN и ограничения (WARP удалён в 3.0.0) |
| [PC-STREAMING-AUDIT.md](PC-STREAMING-AUDIT.md) | замеры: DPI-блокировка TLS и что работало с WARP (история) |
| [UPDATE.md](UPDATE.md) | обновления из GitHub: автопроверка, кэш, добровольное скачивание, SHA-256, установка |
| [NOHIT.md](NOHIT.md) | DPI-обход NoHiT вместо WARP: компоненты, локальный прокси, проверка на сети, ограничения |
| [RECOGNITION.md](RECOGNITION.md) | поиск песни по аудиофрагменту: AudD, локальный отпечаток, ограничения |
| [PERFORMANCE.md](PERFORMANCE.md) | скорость интерфейса: причины подтормаживаний, изменения, замеры gfxinfo |
| [YTDLP_INTEGRATION.md](YTDLP_INTEGRATION.md) | yt-dlp + ReccoBeats: поиск, поток, аудио-фичи, кэш, оптимизации |
| [RF-STREAMING-RESEARCH.md](RF-STREAMING-RESEARCH.md) | замеры зеркал и блокировок из РФ (29.10.2026), ограничения и рекомендации |
| [DISCOVERY.md](DISCOVERY.md) | открытые каталоги Internet Archive и Jamendo |
| [UI.md](UI.md) | дизайн-система, экраны, состояния, ограничения |
| [ROADMAP.md](ROADMAP.md) | планы и направления развития |
| [REVIEW.md](REVIEW.md) | история статического ревью на compile-blocking ошибки |

## Проект

| Документ | О чём |
|---|---|
| [CHANGELOG.md](../CHANGELOG.md) | история версий |
| [CONTRIBUTING.md](../CONTRIBUTING.md) | как помочь проекту |
| [SECURITY.md](../SECURITY.md) | сообщение об уязвимостях |
| [CODE_OF_CONDUCT.md](../CODE_OF_CONDUCT.md) | правила общения |
| [THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md) | сторонние библиотеки и лицензии |
| [GITHUB.md](../GITHUB.md) | публикация репозитория и релиза |

## Быстрые ссылки

- Сборка: `./gradlew :app:assembleDebug` (JDK 21, Android SDK 37).
- APK: `app/build/outputs/apk/debug/app-debug.apk`.
- Проверка: `./gradlew :app:testDebugUnitTest :app:lintDebug`.
- Версия: `3.3.0`, `versionCode 42`.
