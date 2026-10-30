package app.sonora.data.recognition

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Единая точка входа фичи «поиск песни по фрагменту».
 *
 * Жизненный цикл:
 * 1. `start()` → PermissionRequired (если нет RECORD_AUDIO) либо Recording 6–10 с;
 * 2. Identifying → AudD (если задан ключ) либо сверка с локальной фонотекой;
 * 3. Result(title/artist/album/artwork/links) + фоновое обогащение по названию/автору;
 * 4. Error с человекочитаемым текстом.
 *
 * Работает в пределах процесса приложения на Dispatchers.IO: не требует
 * foreground-сервиса, не останавливает воспроизведение и не блокирует UI.
 * Состояние переживает переход между экранами — экран поиска просто читает Flow.
 */
object RecognitionController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<RecognitionState>(RecognitionState.Idle)
    val state: StateFlow<RecognitionState> = _state.asStateFlow()

    @Volatile
    private var job: Job? = null

    /** Записать 8 секунд с микрофона и распознать. Повторный вызов во время работы игнорируется. */
    @Synchronized
    fun start(context: Context) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        _state.value = RecognitionState.Idle
        job = scope.launch { runPipeline(appContext, fileUri = null) }
    }

    /**
     * Импорт уже существующего аудиофайла как «фрагмента» — для ручной проверки
     * без микрофона (эмулятор/отладка). Открывается через SAF.
     */
    @Synchronized
    fun startWithFile(context: Context, uri: Uri) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        _state.value = RecognitionState.Idle
        job = scope.launch { runPipeline(appContext, fileUri = uri) }
    }

    /** Отменить запись/анализ и вернуться в исходное состояние. */
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = RecognitionState.Idle
    }

    /** Закрыть результат/ошибку, если ничего не выполняется. */
    fun reset() {
        if (job?.isActive != true) _state.value = RecognitionState.Idle
    }

    fun isRunning(): Boolean = job?.isActive == true

    /** Вызывается UI, когда пользователь отказал в доступе к микрофону. */
    fun permissionDenied() {
        _state.value = RecognitionState.Error(
            "Без доступа к микрофону распознать фрагмент нельзя. " +
                "Разрешение можно включить в настройках приложения.",
        )
    }

    fun hasPermission(context: Context): Boolean = hasMicrophonePermission(context)

    fun apiToken(context: Context): String = RecognitionSettings(context).auddToken

    fun setApiToken(context: Context, token: String) {
        RecognitionSettings(context).auddToken = token
    }

    private suspend fun runPipeline(context: Context, fileUri: Uri?) {
        try {
            val pcmBytes = if (fileUri != null) {
                _state.value = RecognitionState.Identifying("Читаем выбранный файл…")
                val decoded = LocalPcmDecoder.decodeToMono16k(context, fileUri, IMPORT_DURATION_MS)
                    ?: throw IllegalStateException("Не удалось прочитать аудио из файла")
                Pcm.shortsToBytes(decoded)
            } else {
                if (!hasMicrophonePermission(context)) {
                    _state.value = RecognitionState.PermissionRequired
                    return
                }
                _state.value = RecognitionState.Recording(0L, RECORD_DURATION_MS)
                FragmentRecorder(context).record(RECORD_DURATION_MS) { elapsedMs ->
                    _state.value = RecognitionState.Recording(
                        elapsedMs = elapsedMs.coerceAtMost(RECORD_DURATION_MS),
                        totalMs = RECORD_DURATION_MS,
                    )
                }
            }

            val samples = Pcm.bytesToShorts(pcmBytes)
            if (samples.size < AudioFingerprint.FRAME_SIZE) {
                throw IllegalStateException("Фрагмент слишком короткий для распознавания")
            }
            if (Pcm.rms(samples) < AudioFingerprint.MIN_RMS) {
                throw IllegalStateException(
                    "Слишком тихо: музыки почти не слышно. Поднесите телефон ближе к источнику.",
                )
            }

            val token = RecognitionSettings(context).auddToken
            if (token.isNotBlank()) {
                _state.value = RecognitionState.Identifying("Распознаём песню…")
                val outcome = withTimeoutOrNull(AUDD_TIMEOUT_MS) {
                    AudDClient(token).identify(FRAGMENT_FILE_NAME, Pcm.wav(pcmBytes))
                } ?: AudDOutcome.Failure("Сервис распознавания не ответил вовремя. Попробуйте ещё раз.")
                when (outcome) {
                    is AudDOutcome.Found -> publishResult(context, outcome.track, fromLocalLibrary = false)
                    AudDOutcome.NotFound -> _state.value = RecognitionState.Error(
                        "Песня не распознана. Попробуйте фрагмент ближе к припеву и без шума.",
                    )
                    is AudDOutcome.Failure -> _state.value = RecognitionState.Error(outcome.message)
                    AudDOutcome.MissingToken -> localFallback(context, samples, tokenMissing = true)
                }
            } else {
                localFallback(context, samples, tokenMissing = true)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _state.value = RecognitionState.Error(RecognitionErrors.describe(error))
        }
    }

    /**
     * Честный офлайн-fallback: сверка со своей фонотекой по упрощённому
     * спектральному отпечатку. Не заменяет AudD и явно сообщает об ограничении.
     */
    private suspend fun localFallback(context: Context, samples: ShortArray, tokenMissing: Boolean) {
        _state.value = RecognitionState.Identifying(
            if (tokenMissing) "Ключ AudD не задан — сверяем с вашей фонотекой…"
            else "Сверяем с вашей фонотекой…",
        )
        val fingerprint = AudioFingerprint.compute(samples)
        if (fingerprint.frameCount < AudioFingerprint.MIN_FRAMES) {
            _state.value = if (tokenMissing) {
                RecognitionState.Error(MISSING_TOKEN_MESSAGE, needsAuddToken = true)
            } else {
                RecognitionState.Error("Фрагмент слишком короткий для анализа.")
            }
            return
        }
        val match = withTimeoutOrNull(LOCAL_SCAN_TIMEOUT_MS) {
            LocalLibraryFingerprintIndex(context).identify(fingerprint) { done, total ->
                _state.value = RecognitionState.Identifying("Сверяем с фонотекой: $done из $total")
            }
        }
        if (match != null) {
            publishResult(
                context,
                RecognizedTrack(
                    artist = match.track.artist,
                    title = match.track.title,
                    album = match.track.album,
                    songLink = match.track.deepLink,
                    artworkUrl = match.track.artworkUrl,
                    provider = "Фонотека устройства",
                    score = match.score,
                ),
                fromLocalLibrary = true,
            )
            return
        }
        _state.value = if (tokenMissing) {
            RecognitionState.Error(MISSING_TOKEN_MESSAGE, needsAuddToken = true)
        } else {
            RecognitionState.Error("Не удалось распознать фрагмент. Попробуйте ближе к припеву.")
        }
    }

    private suspend fun publishResult(
        context: Context,
        track: RecognizedTrack,
        fromLocalLibrary: Boolean,
    ) {
        _state.value = RecognitionState.Result(track, fromLocalLibrary = fromLocalLibrary)
        if (fromLocalLibrary) {
            // Локальный трек уже несёт свои метаданные — сетевой поиск не нужен.
            _state.value = RecognitionState.Result(
                track = track,
                enrichment = RecognitionEnrichment(
                    coverUrl = track.artworkUrl,
                    album = track.album,
                    done = true,
                ),
                fromLocalLibrary = true,
            )
            return
        }
        // Шаг 2: фоновый поиск по названию и автору. Ошибки — не повод терять результат.
        val enrichment = withTimeoutOrNull(ENRICH_TIMEOUT_MS) {
            runCatching { RecognitionEnricher(context).enrich(track) }.getOrNull()
        } ?: RecognitionEnrichment(coverUrl = track.artworkUrl, album = track.album, done = true)
        currentCoroutineContext().ensureActive()
        _state.value = RecognitionState.Result(track, enrichment, fromLocalLibrary = false)
    }

    private const val RECORD_DURATION_MS = 8_000L
    private const val AUDD_TIMEOUT_MS = 40_000L
    private const val LOCAL_SCAN_TIMEOUT_MS = 180_000L
    private const val ENRICH_TIMEOUT_MS = 25_000L
    private const val IMPORT_DURATION_MS = 10_000
    private const val FRAGMENT_FILE_NAME = "sonora_fragment.wav"

    private const val MISSING_TOKEN_MESSAGE =
        "Ключ AudD не задан — распознать незнакомую песню нельзя. " +
            "Введите ключ (300 запросов бесплатно после регистрации), " +
            "а без ключа Sonora сверяет фрагмент только с вашей фонотекой."
}
