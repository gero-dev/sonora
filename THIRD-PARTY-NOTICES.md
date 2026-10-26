# Сторонние компоненты и лицензии

Sonora распространяется под **GPL-3.0** (см. [LICENSE](LICENSE)).

## InnerTubeX (MetrolistGroup)

- Артефакт: `com.github.MetrolistGroup.innertubex:innertubex:v0.11.0` (JitPack)
- Назначение: извлечение аудиопотока YouTube Music — выбор клиента, деобфускация
  `signatureCipher`/`n`-параметра, SABR и контракты PoToken.
- Лицензия: **GNU GPL-3.0**, https://github.com/MetrolistGroup/innertubex

## PoToken WebView (порт из Metrolist)

- Файлы: `app/src/main/java/app/sonora/data/remote/youtube/innertube/potoken/`
  (`PoTokenWebView.kt`, `PoTokenGenerator.kt`, `JavaScriptUtil.kt`,
  `PoTokenException.kt`, `PoTokenResult.kt`) и `app/src/main/assets/po_token.html`.
- Источник: Metrolist, https://github.com/mostafaalagamy/Metrolist (GPL-3.0).
- Изменения при порте: пакет `app.sonora…`, Timber заменён на `android.util.Log`,
  убран глобальный proxy из OkHttp, `BuildConfig.DEBUG` заменён на `Log.isLoggable`.

Использование InnerTubeX и портированного PoToken-кода — причина, по которой Sonora
целиком распространяется под GPL-3.0, а не под MIT. При публикации APK или исходников
нужно соблюдать GPL: предоставлять соответствующий исходный код и сохранять уведомления
об авторских правах.

## Остальные библиотеки

| Компонент | Лицензия |
|---|---|
| AndroidX / Jetpack Compose / Media3 | Apache-2.0 |
| OkHttp / Coil / Ktor | Apache-2.0 |
| kotlinx.serialization / kotlinx.coroutines | Apache-2.0 |
| Material Components (Google) | Apache-2.0 |
| desugar_jdk_libs | GPL-2.0 with Classpath Exception |

## Данные и сервисы

- InnerTube — недокументированный протокол YouTube. Проект не связан с Google/YouTube.
- MusicBrainz / Cover Art Archive — данные под CC0/CC-BY-NC-SA; LRCLIB — тексты по лицензиям
  правообладателей; Internet Archive и Jamendo — по лицензиям конкретных записей.
