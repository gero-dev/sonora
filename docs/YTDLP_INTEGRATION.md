# Интеграция yt-dlp + ReccoBeats в Sonora

Документ описывает минимальный путь добавления бесплатного поиска/потока и
аудио-телеметрии. UI намеренно не рассматривается.

## 0. Что уже есть в проекте

| Компонент | Файл | Вывод |
|---|---|---|
| Поиск и stream по InnerTube | `data/remote/youtube/innertube/InnerTubeApi.kt`, `InnerTubeStreams.kt`, `InnerTubeXEngine.kt` | Уже работает, быстрее yt-dlp |
| Источник данных ExoPlayer | `playback/InnerTubeDataSource.kt` | Готовый паттерн `ResolvingDataSource` |
| HTTP | `okhttp3` + `ktor-client-okhttp` | Retrofit не нужен |
| Кэш | `core/util/JsonFile.kt` | Room не нужен |
| Очередь/плеер | `playback/PlaybackController.kt` | `Track.uri` — единственная точка входа |
| Настройки | `core/settings/SettingsStore.kt` | Добавлять ничего не обязательно |

**Ключевая оптимизация (нужно согласование):** использовать yt-dlp только как
fallback, а поиск/поток по умолчанию оставить на InnerTube. Это экономит
~90–110 МБ APK, 2–4 секунды на первый вызов Python и батарею. Ниже план
написан так, что yt-dlp — самостоятельный провайдер, который можно включить
флагом.

---

## 1. Зависимости и сборка

`app/build.gradle.kts`:

```kotlin
dependencies {
    // youtubedl-android (Python + yt-dlp). Версию проверить на Maven Central.
    implementation("io.github.junkfood02.youtubedl-android:library:0.17.4")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.17.4")

    // Только если всё-таки нужен Retrofit (см. §4, рекомендуется Ktor)
    // implementation("com.squareup.retrofit2:retrofit:2.11.0")
    // implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
}

android {
    packaging {
        jniLibs {
            // Python-библиотеки должны лежать несжатыми
            useLegacyPackaging = true
        }
    }
    // ABI-фильтры: x86_64 нужен только эмулятору
    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
}
```

`proguard-rules.pro`:

```
-keep class com.yausername.** { *; }
-keep class com.yausername.youtubedl_android.** { *; }
```

Инициализация — один раз, в фоне (`AppGraph.init`):

```kotlin
// AppGraph.kt
val ytDlp by lazy { YtDlpResolver(appContext) }
val audioFeatures by lazy { AudioFeatureRepository(appContext, ytDlp) }

init {
    // ...
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        runCatching { ytDlp.ensureInit() }   // прогреваем Python заранее
    }
}
```

---

## 2. Модель и резолвер yt-dlp

Новый файл `data/remote/ytdlp/YtDlpModels.kt`:

```kotlin
@Serializable
data class YtTrack(
    val id: String,                 // videoId
    val title: String,
    val uploader: String? = null,
    val durationMs: Long = 0L,
    val url: String = "https://www.youtube.com/watch?v=$id",
) {
    fun toSonoraTrack(): Track = Track(
        id = "ytdlp:$id",
        title = title,
        artist = uploader.orEmpty(),
        durationMs = durationMs,
        uri = "ytdlp:$id",          // резолвится в DataSource, см. §5
        source = Source.YOUTUBE,
        playable = true,
        downloadAllowed = false,
        deepLink = url,
    )
}

/** Ответ `--dump-json` (нужные поля; остальные игнорируются). */
@Serializable
internal data class YtDumpJson(
    val id: String,
    val title: String = "",
    val uploader: String? = null,
    val duration: Double? = null,
    val url: String? = null,
    @SerialName("http_headers") val httpHeaders: Map<String, String> = emptyMap(),
)

data class StreamHandle(
    val url: String,
    val headers: Map<String, String>,
)
```

`data/remote/ytdlp/YtDlpResolver.kt`:

```kotlin
class YtDlpResolver(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val initMutex = Mutex()

    @Volatile private var initialized = false

    suspend fun ensureInit() = initMutex.withLock {
        if (initialized) return@withLock
        withContext(Dispatchers.IO) {
            YoutubeDL.getInstance().init(context)
            FFmpeg.getInstance().init(context)
        }
        initialized = true
    }

    /** `ytsearchN:<query> --dump-json --flat-playlist` (N результатов). */
    suspend fun search(query: String, limit: Int = 10): List<YtTrack> =
        withContext(Dispatchers.IO) {
            ensureInit()
            val request = YoutubeDLRequest("ytsearch$limit:$query").apply {
                addOption("--dump-json")
                addOption("--flat-playlist")
                addOption("--no-warnings")
                addOption("--no-playlist")
                addOption("--socket-timeout", "10")
            }
            val response = YoutubeDL.getInstance().execute(request)
            response.out.lineSequence()
                .filter { it.startsWith("{") }
                .mapNotNull { line ->
                    runCatching { json.decodeFromString<YtDumpJson>(line) }.getOrNull()
                }
                .filter { it.id.isNotBlank() }
                .map { dto ->
                    YtTrack(
                        id = dto.id,
                        title = dto.title.ifBlank { dto.id },
                        uploader = dto.uploader,
                        durationMs = ((dto.duration ?: 0.0) * 1000).toLong(),
                    )
                }
                .take(limit)
                .toList()
        }

    /**
     * Прямой аудио-URL + заголовки. Именно `--dump-json`, а не `-g`:
     * в JSON есть `http_headers`, без которых googlevideo отдаёт 403.
     */
    suspend fun streamHandle(idOrUrl: String): StreamHandle =
        withContext(Dispatchers.IO) {
            ensureInit()
            val request = YoutubeDLRequest(watchUrl(idOrUrl)).apply {
                addOption("-f", "bestaudio[ext=m4a]/bestaudio")
                addOption("--dump-json")
                addOption("--no-playlist")
                addOption("--no-warnings")
                addOption("--socket-timeout", "10")
                addOption("--extractor-args", "youtube:player_client=android,web")
            }
            val response = YoutubeDL.getInstance().execute(request)
            val dto = response.out.lineSequence()
                .filter { it.startsWith("{") }
                .mapNotNull { runCatching { json.decodeFromString<YtDumpJson>(it) }.getOrNull() }
                .firstOrNull { !it.url.isNullOrBlank() }
                ?: error("yt-dlp не вернул аудио-URL")
            StreamHandle(dto.url!!, dto.httpHeaders)
        }

    /** 30–60 с фрагмент в MP3 (<5 МБ) для ReccoBeats. */
    suspend fun downloadSnippet(idOrUrl: String, seconds: Int = 30): File =
        withContext(Dispatchers.IO) {
            ensureInit()
            val dir = File(context.cacheDir, "recco").apply { mkdirs() }
            val output = File(dir, "snip-${UUID.randomUUID()}.mp3")
            val request = YoutubeDLRequest(watchUrl(idOrUrl)).apply {
                addOption("-f", "bestaudio/best")
                addOption("--download-sections", "*0-$seconds")
                addOption("--force-keyframes-at-cuts")
                addOption("-x")
                addOption("--audio-format", "mp3")
                addOption("--audio-quality", "96K")   // держим размер < 5 МБ
                addOption("-o", output.absolutePath)
                addOption("--no-playlist")
                addOption("--no-warnings")
            }
            YoutubeDL.getInstance().execute(request)
            output.takeIf { it.exists() && it.length() > 0 }
                ?: dir.listFiles()?.maxByOrNull { it.lastModified() }
                ?: error("Не удалось скачать фрагмент")
        }

    private fun watchUrl(idOrUrl: String): String =
        if (idOrUrl.startsWith("http")) idOrUrl
        else "https://www.youtube.com/watch?v=$idOrUrl"
}
```

Замечания:
* `--flat-playlist` быстрый, но `duration` может быть `null`. Если нужна
  длительность в списке — либо убрать флаг (медленнее), либо добирать
  `--print "%(duration)s"` при старте трека.
* `-g` проще, но не отдаёт заголовки. `--dump-json` надёжнее.
* Прямой URL живёт часы, но привязан к IP/клиенту. Никогда не храните его
  в очереди — только в коротком кэше (см. §5).

---

## 3. Кэш аудио-фич

Вместо Room — существующий `JsonFile` (ноль новых зависимостей, атомарная
запись). Файл `data/telemetry/AudioFeatureCache.kt`:

```kotlin
@Serializable
data class AudioFeature(
    val tempo: Double? = null,
    val energy: Double? = null,
    val danceability: Double? = null,
    val valence: Double? = null,
    val acousticness: Double? = null,
    val instrumentalness: Double? = null,
    val liveness: Double? = null,
    val speechiness: Double? = null,
    val loudness: Double? = null,
)

@Serializable
private data class CachedFeature(
    val key: String,
    val feature: AudioFeature,
    val savedAtMs: Long,
)

class AudioFeatureCache(private val context: Context) {

    private val serializer = ListSerializer(CachedFeature.serializer())
    private val mutex = Mutex()
    private var loaded = false
    private val map = LinkedHashMap<String, AudioFeature>()

    private suspend fun ensureLoaded() = mutex.withLock {
        if (loaded) return@withLock
        JsonFile.read(context, FILE, serializer, emptyList())
            .sortedBy { it.savedAtMs }
            .takeLast(MAX_ENTRIES)
            .forEach { map[it.key] = it.feature }
        loaded = true
    }

    suspend fun get(key: String): AudioFeature? {
        ensureLoaded()
        return mutex.withLock { map[key] }
    }

    suspend fun put(key: String, feature: AudioFeature) {
        ensureLoaded()
        mutex.withLock {
            map[key] = feature
            while (map.size > MAX_ENTRIES) map.remove(map.keys.first())
            JsonFile.write(
                context, FILE, serializer,
                map.map { (k, v) -> CachedFeature(k, v, System.currentTimeMillis()) },
            )
        }
    }

    private companion object {
        const val FILE = "audio_features.json"
        const val MAX_ENTRIES = 1000
    }
}
```

Ключ: `key = "${artist.lowercase()}|${title.normalized()}"` — тот же приём, что
`WaveEngine.waveKey`, либо `ytdlp:<videoId>`.

---

## 4. ReccoBeats

`data/telemetry/ReccoBeatsClient.kt` — вариант на **Ktor** (уже в проекте).
Retrofit-вариант ниже, если хотите именно его.

```kotlin
class ReccoBeatsClient {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 45_000
            socketTimeoutMillis = 45_000
        }
    }

    /** a) По известному Spotify/ReccoBeats id. */
    suspend fun byId(id: String): AudioFeature? = runCatching {
        client.get("$BASE/v1/track/$id/audio-features").body<AudioFeature>()
    }.getOrNull()

    /** b) Универсальный путь: загрузка фрагмента. */
    suspend fun analyze(file: File): AudioFeature? = runCatching {
        client.submitFormWithBinaryData(
            url = "$BASE/v1/analysis/audio-features",
            formData = formData {
                append(
                    "audioFile",
                    file.readBytes(),
                    Headers.build {
                        append(HttpHeaders.ContentType, "audio/mpeg")
                        append(HttpHeaders.ContentDisposition, "filename=\"${file.name}\"")
                    },
                )
            },
        ).body<AudioFeature>()
    }.getOrNull()

    private companion object { const val BASE = "https://api.reccobeats.com" }
}
```

Retrofit-вариант (если нужен именно интерфейс):

```kotlin
interface ReccoBeatsApi {
    @GET("v1/track/{id}/audio-features")
    suspend fun trackFeatures(@Path("id") id: String): AudioFeature

    @Multipart
    @POST("v1/analysis/audio-features")
    suspend fun analyze(@Part audioFile: MultipartBody.Part): AudioFeature

    @GET("v1/track/recommendation")
    suspend fun recommendations(
        @Query("seeds") seeds: String,
        @Query("limit") limit: Int = 20,
    ): List<RecommendationDto>
}
```

Ответ API иногда оборачивает поля в `content`/`data` — один раз залогируйте
raw JSON и при необходимости добавьте `@Serializable`-обёртку.

`data/telemetry/AudioFeatureRepository.kt` — оркестрация + дедупликация
параллельных запросов:

```kotlin
class AudioFeatureRepository(
    context: Context,
    private val ytDlp: YtDlpResolver,
    private val recco: ReccoBeatsClient = ReccoBeatsClient(),
) {
    private val cache = AudioFeatureCache(context)
    private val inFlight = ConcurrentHashMap<String, Deferred<AudioFeature?>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun featuresFor(track: YtTrack): Deferred<AudioFeature?> {
        val key = "ytdlp:${track.id}"
        cache.getSync(key)?.let { return CompletableDeferred(it) }
        return inFlight.getOrPut(key) {
            scope.async {
                try {
                    val snippet = runCatching { ytDlp.downloadSnippet(track.id) }.getOrNull()
                        ?: return@async null
                    val feature = recco.analyze(snippet)
                    snippet.delete()
                    feature?.also { cache.putSync(key, it) }
                } finally {
                    inFlight.remove(key)
                }
            }
        }
    }
}
```

Для простоты синхронные `getSync/putSync` можно заменить на `suspend`-версии и
вызывать внутри `async` — сигнатура `featuresFor` при этом останется `suspend`.

Рекомендации без Spotify ID: косинус по векторам.

```kotlin
fun cosine(a: AudioFeature, b: AudioFeature): Double {
    val va = doubleArrayOf(
        a.energy ?: 0.0, a.danceability ?: 0.0, a.valence ?: 0.0,
        a.acousticness ?: 0.0, a.instrumentalness ?: 0.0,
        ((a.tempo ?: 0.0) / 200.0).coerceIn(0.0, 1.0),
    )
    val vb = doubleArrayOf(
        b.energy ?: 0.0, b.danceability ?: 0.0, b.valence ?: 0.0,
        b.acousticness ?: 0.0, b.instrumentalness ?: 0.0,
        ((b.tempo ?: 0.0) / 200.0).coerceIn(0.0, 1.0),
    )
    val dot = va.indices.sumOf { va[it] * vb[it] }
    val na = sqrt(va.sumOf { it * it })
    val nb = sqrt(vb.sumOf { it * it })
    return if (na == 0.0 || nb == 0.0) 0.0 else dot / (na * nb)
}
```

---

## 5. Интеграция с ExoPlayer (минимум изменений)

### Рекомендуемый вариант: схема `ytdlp:<id>`

Очередь хранит `Track.uri = "ytdlp:<videoId>"`, а URL резолвится в момент
`open()` — как уже сделано для `innertube:`. Это защищает от протухших ссылок
при seek/повторе и не требует менять `PlaybackController`.

`playback/YtDlpBridge.kt`:

```kotlin
object YtDlpBridge {
    @Volatile private lateinit var resolver: YtDlpResolver
    private val cache = ConcurrentHashMap<String, Pair<StreamHandle, Long>>()
    private const val TTL_MS = 5 * 60_000L

    fun init(resolver: YtDlpResolver) { this.resolver = resolver }

    fun resolveBlocking(videoId: String): StreamHandle {
        val now = System.currentTimeMillis()
        cache[videoId]?.let { (h, at) -> if (now - at < TTL_MS) return h }
        val handle = runBlocking(Dispatchers.IO) { resolver.streamHandle(videoId) }
        cache[videoId] = handle to now
        return handle
    }
}
```

В `playback/InnerTubeDataSource.kt` — одна ветка в резолвере:

```kotlin
private val resolverChain = ResolvingDataSource.Resolver { dataSpec ->
    val raw = dataSpec.uri.toString()

    if (raw.startsWith("ytdlp:")) {
        val videoId = raw.removePrefix("ytdlp:").substringBefore('?')
        val handle = YtDlpBridge.resolveBlocking(videoId)
        return@Resolver dataSpec
            .withUri(Uri.parse(handle.url))
            .withRequestHeaders(dataSpec.httpRequestHeaders + handle.headers)
    }

    val videoId = InnerTubeStreams.videoIdOf(raw) ?: return@Resolver dataSpec
    // ... существующая логика InnerTube ...
}
```

`PlaybackService` не меняется. `PlaybackController.toMediaItem` не меняется.
`Track` для yt-dlp — из `YtTrack.toSonoraTrack()`.

### Простой вариант (как в задаче)

Если не хочется трогать DataSource:

```kotlin
val url = ytDlp.streamHandle(track.id).url
playback.playQueue(listOf(track.toSonoraTrack().copy(uri = url, id = "yt-url:${track.id}")), 0)
```

Минус: URL живёт в очереди, после истечения/потери сети seek и повтор упадут.
Нужен обработчик `Player.Listener.onPlayerError` → повторный резолв.

---

## 6. ViewModel: параллельный запуск

```kotlin
data class YtUiState(
    val results: List<YtTrack> = emptyList(),
    val loading: Boolean = false,
    val resolvingId: String? = null,
    val features: Map<String, AudioFeature> = emptyMap(),
    val error: String? = null,
)

class YtStreamViewModel(
    private val ytDlp: YtDlpResolver,
    private val telemetry: AudioFeatureRepository,
    private val playback: PlaybackController,
) : ViewModel() {

    private val _state = MutableStateFlow(YtUiState())
    val state: StateFlow<YtUiState> = _state.asStateFlow()

    fun search(query: String) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val found = runCatching { ytDlp.search(query, limit = 10) }.getOrElse { e ->
                _state.update { it.copy(loading = false, error = e.message) }
                return@launch
            }
            _state.update { it.copy(loading = false, results = found) }
            // Телеметрия для первых трёх — в фоне, UI не ждёт.
            found.take(3).forEach { t -> launch { loadFeatures(t) } }
        }
    }

    fun play(track: YtTrack) {
        viewModelScope.launch {
            _state.update { it.copy(resolvingId = track.id, error = null) }

            // Старт и телеметрия параллельно; ждём только старт.
            val featureJob = launch { loadFeatures(track) }

            playback.playQueue(listOf(track.toSonoraTrack()), 0)
            _state.update { it.copy(resolvingId = null) }
            featureJob.join()   // не обязательно: можно не ждать вовсе
        }
    }

    private suspend fun loadFeatures(track: YtTrack) {
        val feature = telemetry.featuresFor(track).await() ?: return
        _state.update { it.copy(features = it.features + (track.id to feature)) }
    }
}
```

Если нужен именно «сначала URL, потом play» (вариант §5.2):

```kotlin
val handleDeferred = async { ytDlp.streamHandle(track.id) }
val featuresDeferred = async { telemetry.featuresFor(track) }
val handle = handleDeferred.await()
playback.playQueue(listOf(track.toSonoraTrack().copy(uri = handle.url)), 0)
launch { _state.update { it.copy(features = it.features + (track.id to featuresDeferred.await())) } }
```

---

## 7. Оптимизации (предлагаю согласовать)

1. **InnerTube как основной, yt-dlp как fallback.** Экономия ~100 МБ APK и
   2–4 с на первый вызов. yt-dlp включать только для видео, где InnerTubeX
   вернул ошибку/только видеоформат.
2. **Не добавлять Retrofit и Room.** В проекте уже есть Ktor/OkHttp и
   `JsonFile`; это минус 3 зависимости и минус KSP.
3. **Кэш поиска на 10 минут** (`query → List<YtTrack>`), чтобы «назад/вперёд»
   не запускал Python.
4. **Прогрев следующего трека**: резолвить URL для `queue[index + 1]` в фоне
   за 10–15 с до конца текущего.
5. **Фрагмент 20–30 с, 96 кбит/с, mono 22 кГц** — в 2–3 раза быстрее
   загрузка/анализ, всё ещё <5 МБ.
6. **Телеметрия только для играющего трека** (и, опционально, топ-3 поиска),
   а не для всех 10 результатов.
7. **Таймаут ReccoBeats 8–10 с + один ретрай**, всё в `runCatching`: ошибка
   телеметрии никогда не влияет на воспроизведение.
8. **Ограничить параллелизм yt-dlp** до 1 процесса
   (`Dispatchers.IO.limitedParallelism(1)`), Python тяжёлый.
9. **ABI splits / App Bundle**: без этого APK вырастет до ~150 МБ.
10. **Кэш stream URL 5 минут** (не больше): ссылки привязаны к IP и клиенту.

---

## 8. Чек-лист внедрения

1. Добавить зависимости и `useLegacyPackaging`.
2. Создать `YtTrack`, `YtDumpJson`, `StreamHandle`.
3. Создать `YtDlpResolver` (init/search/streamHandle/downloadSnippet).
4. Создать `AudioFeature`, `AudioFeatureCache`, `ReccoBeatsClient`,
   `AudioFeatureRepository`.
5. Инициализировать `YtDlpResolver` и `YtDlpBridge` в `AppGraph`.
6. Добавить ветку `ytdlp:` в `InnerTubeDataSource` (или использовать прямой URL).
7. Добавить `YtStreamViewModel` (или методы в `MainViewModel`).
8. Прогнать на устройстве: поиск, старт, seek, повтор, смена сети.
9. Проверить размер APK и время первого старта.

---

## 9. Риски

* **Размер и старт.** Python-рантайм — главный минус; см. §7.1.
* **403 от googlevideo.** Причина — несовпадение User-Agent/клиента.
  Решение: брать `http_headers` из `--dump-json` (в коде так и сделано).
* **Возрастные/региональные ограничения.** yt-dlp может требовать cookies;
  для РФ-сценария без VPN часть видео недоступна — нужен graceful fallback.
* **ReccoBeats.** Бесплатный сервис без SLA: обязательно таймаут, кэш и
  «пропустить телеметрию» при ошибке.
* **Правовые ограничения YouTube ToS** — решение принимает владелец продукта.
