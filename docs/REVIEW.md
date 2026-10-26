# Sonora — статический ревью на compile-blocking ошибки

> **Исторический документ.** Это снимок раннего ревью (0.6.x), оставленный для
> истории. Текущая версия **0.9.6** проверена сборкой `assembleDebug`, unit-тестами
> (`PlaylistFileParserTest`, `ArtistNamesTest`, `InnerTubeSmokeTest`) и `lintDebug`;
> актуальный статус — в [README.md](../README.md) и [CHANGELOG.md](../CHANGELOG.md).

> **Итог после ревью (26.10.2026):** все найденные проблемы исправлены, проект собран
> реальным компилятором: `gradlew :app:assembleDebug` — **BUILD SUCCESSFUL**,
> `gradlew :app:lintDebug` — без ошибок, приложение запущено на эмуляторе Android 14 (API 34)
> и проверено вручную (сканирование, воспроизведение, темы, все экраны).
> Единственный BLOCKER (`android.Uri` → `Uri`) исправлен; «LIKELY» с `Modifier.blur` обойдён
> заменой на `graphicsLayer { alpha }`. Документ оставлен как история ревью.

Снимок: 26.10.2026, ~13:27 MSK. Компилятора и сети нет, проверка ручная (чтение исходников + сверка с API
pinned-версий). Проверены **все** `.kt`, существовавшие на момент чтения, плюс build-файлы и ресурсы:

- `app/src/main/java/app/sonora/**` — 44 файла: `AppGraph`, `SonoraApp`, `MainActivity`,
  `core/{model,settings,security,util,net}`, `data/{library,download,remote/{spotify,youtube,soundcloud}}`,
  `playback/{PlaybackController,PlaybackService,AudioEffectBus}`, `ui/{MainScreen,MainViewModel}`, `ui/nav`,
  `ui/theme`, `ui/components/{TrackRow,MiniPlayer}`,
  `ui/screens/{Home,Search,Library,Settings,Connections,Player,Queue,Details}`;
- `app/build.gradle.kts`, `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`,
  `gradle/wrapper/gradle-wrapper.properties`, `app/src/main/AndroidManifest.xml`,
  `app/src/main/res/values/{themes,colors,strings}.xml`, `res/drawable/ic_launcher_foreground.xml`,
  `res/mipmap-anydpi-v26/*`.

Важно: `ui/screens/*` и `ui/components/*` дописывались другим агентом прямо во время ревью
(последние правки файлов — 13:23–13:27). Проверено актуальное на этот момент содержимое; если файлы
поменяются после — выводы по ним надо перепроверить. На момент снимка все 6 экранов, импортируемых
`MainScreen.kt`, уже существуют (`AlbumDetailScreen`, `ArtistDetailScreen`, `PlaylistDetailScreen` — в
`DetailsScreen.kt`), сигнатуры вызовов совпадают. После первичной вычитки (13:28) перепроверены
`AppGraph`, `LibraryRepository`, `MediaStoreScanner`, `TrackRow`, `MiniPlayer`, `PlayerScreen`, `DetailsScreen`,
`LibraryScreen`, `HomeScreen` — блокер ниже на месте, новых неразрешённых символов не появилось.

Ниже — только то, что ломает компиляцию или является вероятной ошибкой API. Стиль/архитектура не рассматривались.

## BLOCKERS

### 1. `app/src/main/java/app/sonora/data/library/MediaStoreScanner.kt:91` — нет класса `android.Uri`

```kotlin
// строки 88-94
private fun albumArtUri(albumId: Long): String? {
    if (albumId <= 0L) return null
    return android.content.ContentUris.withAppendedId(
        android.Uri.parse("content://media/external/audio/albumart"), // <-- Unresolved reference: Uri
        albumId,
    ).toString()
}
```

В пакете `android` класса `Uri` нет (он в `android.net`), а `android.net.Uri` уже импортирован в этом файле
(строка 5), поэтому простое `Uri.parse(...)` резолвится. Остальные полные имена в файле
(`android.content.ContentUris`) корректны.

Фикс:

```kotlin
return android.content.ContentUris.withAppendedId(
    Uri.parse("content://media/external/audio/albumart"),
    albumId,
).toString()
```

## LIKELY

### 1. `app/src/main/java/app/sonora/ui/screens/PlayerScreen.kt:111` — `Modifier.blur(80.dp)` может требовать opt-in

```kotlin
AsyncImage(
    ...
    modifier = Modifier
        .fillMaxSize()
        .blur(80.dp),   // androidx.compose.ui.draw.blur
)
```

`androidx.compose.ui.draw.blur` в части версий compose-ui помечен `@ExperimentalBlurApi`; если в
**compose-ui 1.6.8 (BOM 2024.06.00)** аннотация ещё существует — будет ошибка «This declaration is
experimental…». **Проверить** на первом sync. Обратите внимание: уже добавленный на файл
`@OptIn(ExperimentalMaterial3Api::class)` (строка 82) это **не** покрывает — нужен именно
`androidx.compose.ui.draw.ExperimentalBlurApi`.

Вариант A (если аннотация есть):

```kotlin
import androidx.compose.ui.draw.ExperimentalBlurApi
...
@OptIn(ExperimentalBlurApi::class)
@Composable
fun PlayerScreen(...)
```

Вариант B (не зависит от версии, безопаснее — фон всё равно закрыт затемняющей шторкой):

```kotlin
import androidx.compose.ui.graphics.graphicsLayer
...
modifier = Modifier
    .fillMaxSize()
    .graphicsLayer { alpha = 0.30f },
```

(Отдельно: на API < 31 `blur` — no-op; это runtime-поведение, не ошибка компиляции.)

## NITS

1. `ui/screens/PlayerScreen.kt:82` — `@OptIn(ExperimentalMaterial3Api::class)` выглядит лишним (в файле нет
   experimental-M3 вызовов) → максимум warning «unnecessary opt-in», не ошибка.
2. `playback/PlaybackService.kt:58` и `:71` — `state.collect { }` / `combine(...).collect { }` **без**
   `import kotlinx.coroutines.flow.collect`. В coroutines 1.7.x это компилируется (member
   `Flow.collect(FlowCollector)` + SAM, `FlowCollector` — `fun interface`). Если компилятор всё же
   потребует — добавить импорт, это безопасно.
3. `core/model/Models.kt:28` — `entries.firstOrNull { ... }` внутри `companion object` enum’а. По правилам
   разрешения имён это должно компилироваться (enum-`entries` — статическое свойство), но если словите
   «Unresolved reference: entries» — заменить на `Source.entries.firstOrNull { ... }`.
4. Неиспользуемые импорты (только warnings): `ui/MainScreen.kt:28` `NavHostController`;
   `data/remote/ConnectableSource.kt:4` `app.sonora.core.model.Source`.
5. `app/build.gradle.kts:84` — `androidx.palette:palette-ktx:1.0.0` объявлена, но нигде не используется
   (Palette API не импортируется ни в одном `.kt`); можно убрать, на компиляцию не влияет.
6. В репозитории нет `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` (есть только
   `gradle-wrapper.properties` с `gradle-8.9-bin.zip`) — sync из Android Studio это переживёт, а CLI-сборка
   (`./gradlew assembleDebug`) без wrapper-jar не запустится.
7. `playback/PlaybackService.kt:5,26` — `@OptIn(UnstableApi::class)` через `androidx.annotation.OptIn`:
   корректно при `androidx.annotation` ≥ 1.6 (придёт транзитивно через Compose/Activity). Проверить, только
   если sync пожалуется на сам `OptIn`.

## Что проверено и претензий нет

- **Импорты:** у всех `Icons.Rounded.*` есть отдельные импорты (проверено скриптом по файлам);
  `collectAsState`, `PaddingValues`, `dp`/`sp`, `Brush`/`Color`, `Dp`, `TextOverflow`, `remember*`,
  `getValue`/`setValue` (все `var … by` файлы импортируют оба), `backgroundColor`/`clip`/`combinedClickable`
  и т.п. — везде на месте. FQ-обращений с неверным пакетом больше нет (кроме найденного `android.Uri`).
- **Material3 1.2.1 / BOM 2024.06:** не используются API 1.3+/1.4 (`SegmentedButton`, `PullToRefresh*`,
  `LoadingIndicator`, `*FloatingToolbar`, `Primary/SecondaryScrollableTabRow`, `TopAppBar`, `ListItem`,
  `SearchBar` — отсутствуют). Используемые `surfaceContainer*`, `HorizontalDivider` доступны с 1.2.0;
  `ModalBottomSheet`/`rememberModalBottomSheetState`/`FilterChip`/`ScrollableTabRow`/`Tab` — с opt-in,
  `@OptIn(ExperimentalMaterial3Api::class)` стоит в `TrackRow.kt`, `LibraryScreen`, `SearchScreen`,
  `SettingsScreen`, `PlayerScreen`; `combinedClickable` покрыт `@OptIn(ExperimentalFoundationApi::class)`.
  `Typography`/`Shapes`/`darkColorScheme`/`lightColorScheme` — параметры существуют.
- **Media3 1.3.1:** `MediaSessionService.onGetSession(MediaSession.ControllerInfo)`, `MediaSession.Builder`,
  `MediaController.Builder(...).buildAsync()`, `Player`-методы (`setMediaItems(list,index,pos)`,
  `addMediaItem(i,item)`, `moveMediaItem`, `removeMediaItem`, `seekToNext/PreviousMediaItem`,
  `setPlaybackSpeed`, `playbackParameters.speed`, `shuffleModeEnabled`, `repeatMode`, `release()`),
  `ExoPlayer.setSkipSilenceEnabled`/`audioSessionId`, `C.AUDIO_SESSION_ID_UNSET`, `C.USAGE_MEDIA`,
  `MediaItem.Builder().setUri(String?)`, `MediaMetadata.setArtworkUri` — по версии 1.3.1 корректны;
  `@OptIn(UnstableApi::class)` на `PlaybackService` есть (весь остальной player-код использует только стабильные
  API). Манифест: `<service … foregroundServiceType="mediaPlayback">` с action
  `androidx.media3.session.MediaSessionService`, разрешения `FOREGROUND_SERVICE[_MEDIA_PLAYBACK]`,
  `POST_NOTIFICATIONS`, `READ_MEDIA_AUDIO`, `READ_EXTERNAL_STORAGE(maxSdk 32)`, `INTERNET`,
  `ACCESS_NETWORK_STATE`, `MODIFY_AUDIO_SETTINGS` — всё используется и объявлено.
- **Сериализация:** `@Serializable` только на `Source`, `Track`, `Playlist`, `ThemeMode`, `Accent`,
  `AppSettings`; типы полей сериализуемы (`Map<String,String>`, `List<Float>`, enum’ы, nullable String,
  примитивы). `ListSerializer(...)`/`serializer()`/reified `encodeToString` — импорты и перегрузки верные
  (`decodeFromString(serializer, …)`/`encodeToString(serializer, …)` — члены `StringFormat`, импорт не нужен).
  Плагин serialization 1.9.24 + kotlinx-serialization-json 1.6.3 совместимы.
- **Корутины:** suspend-вызовы внутри `runCatching`/`flatMap`/`forEach` корректны (это inline);
  `viewModelScope`, `lifecycleScope`, `CoroutineScope(SupervisorJob()+…)`, `isActive`, `combine` из 3 flow,
  `MutableStateFlow.update` (импорт есть там, где используется), `distinctBy`/`awaitAll` — типы сходятся.
- **Кросс-файловые контракты:** `AppGraph` (`SpotifySource/YouTubeSource/SoundCloudSource(appContext,
  settings, secure)`, `DownloadManager(appContext, settings)`, `LibraryRepository(appContext, settings,
  scanner, extraDirs)`), `MainViewModel` → `graph.downloads.importFromUri/delete/items`,
  `graph.sources.searchAll/bySource`, `graph.library.*`, `graph.playback.*` — всё существует и совпадает по
  сигнатурам. `MainActivity` → `SonoraTheme(settings=…)`, `MainScreen(graph=…)`; `MainScreen` → все
  `*Screen(...)` совпадают; `TrackRow`/`MiniPlayer`/`TrackActionSheet`/`CreatePlaylistDialog`/`EmptyState`/
  `CoverArt*` — вызовы соответствуют объявлениям; `PlayerUiState`/`DownloadItem` — поля есть.
- **Android API level (minSdk 26):** `java.util.Base64` (26), `java.time.LocalTime` (26),
  `dynamicDarkColorScheme` под `Build.VERSION.SDK_INT >= S`, `enableEdgeToEdge`,
  `registerForActivityResult`/`rememberLauncherForActivityResult`, `MediaStore`-колонки,
  `getExternalFilesDir`, `MediaMetadataRetriever` — по уровням проходят.
- **Ресурсы/тема:** `@style/Theme.Sonora` (parent `Theme.Material3.DayNight.NoActionBar` при material
  1.12.0), `@color/window_background`, `@color/ic_launcher_background`, `@drawable/ic_launcher_foreground`,
  `@mipmap/ic_launcher[_round]` (adaptive, minSdk 26 — допустимо только `mipmap-anydpi-v26`), `@string/app_name`
  — все существуют. `namespace "app.sonora"` совпадает с пакетами и `applicationId`.
- **Сборка:** AGP 8.5.2 / Gradle 8.9 / Kotlin 1.9.24 / compose-compiler 1.5.14 (совместим с 1.9.24) /
  Java 17 — согласованы; `app/proguard-rules.pro` на месте (нужен только для release).

**Итого: блокеров — 1, вероятных — 1, придирок — 7.**
