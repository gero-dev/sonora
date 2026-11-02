package app.sonora.data.recognition

import android.content.Context
import android.net.Uri
import app.sonora.AppGraph
import app.sonora.core.model.Source
import app.sonora.core.model.Track
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
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Единая точка входа фичи «поиск по мелодии/треку».
 *
 * Два режима:
 *  - [RecognitionMode.LISTEN] — Shazam-подобный: 8 секунд микрофона, AudD при
 *    наличии ключа, иначе честный офлайн-поиск по отпечаткам своей фонотеки;
 *  - [RecognitionMode.HUM] — hum-to-search: 10 секунд напетой/просвистанной
 *    мелодии → pitch-контур → DTW-сравнение с контурами локальных треков.
 *
 * Пайплайн (всё на [Dispatchers.IO], UI не блокируется, воспроизведение не трогаем):
 *  1. запись фрагмента / чтение файла;
 *  2. распознавание (AudD) или поиск по фонотеке — top-N кандидатов с оценкой;
 *  3. публикация результата — экран сразу показывает его и кнопку «Слушать»,
 *     если совпадение есть в фонотеке;
 *  4. фоновое обогащение (метаданные) и проверка (5–10 с аудио, короткий
 *     сниппет кандидата, аудио-фичи) — отдельной корутиной, отменяемой.
 *
 * Состояние живёт в процессе приложения: уход с экрана не отменяет поиск;
 * свайп приложения — отменяет (foreground-сервиса нет).
 */
object RecognitionController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<RecognitionState>(RecognitionState.Idle)
    val state: StateFlow<RecognitionState> = _state.asStateFlow()

    @Volatile
    private var job: Job? = null

    /** Фоновая проверка/обогащение — отдельно, чтобы не блокировать запись ответа. */
    @Volatile
    private var backgroundJob: Job? = null

    /** Записать фрагмент с микрофона и распознать играющий трек. */
    @Synchronized
    fun start(context: Context) = startInternal(context, fileUri = null, mode = RecognitionMode.LISTEN)

    /** Записать напетую/просвистанную мелодию и найти её в локальной фонотеке. */
    @Synchronized
    fun startHumming(context: Context) = startInternal(context, fileUri = null, mode = RecognitionMode.HUM)

    /**
     * Импорт уже существующего аудиофайла как «фрагмента» — для ручной проверки
     * без микрофона (эмулятор/отладка). Открывается через SAF.
     */
    @Synchronized
    fun startWithFile(context: Context, uri: Uri) =
        startInternal(context, fileUri = uri, mode = RecognitionMode.LISTEN)

    private fun startInternal(context: Context, fileUri: Uri?, mode: RecognitionMode) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        backgroundJob?.cancel()
        backgroundJob = null
        _state.value = RecognitionState.Idle
        job = scope.launch { runPipeline(appContext, fileUri, mode) }
    }

    /** Отменить запись/анализ/фоновую проверку и вернуться в исходное состояние. */
    fun cancel() {
        job?.cancel()
        job = null
        backgroundJob?.cancel()
        backgroundJob = null
        _state.value = RecognitionState.Idle
    }

    /** Закрыть результат/ошибку, если ничего не выполняется. */
    fun reset() {
        if (job?.isActive != true) {
            backgroundJob?.cancel()
            backgroundJob = null
            _state.value = RecognitionState.Idle
        }
    }

    fun isRunning(): Boolean = job?.isActive == true

    /** Идёт ли фоновая проверка уже показанного результата. */
    fun isVerifying(): Boolean = backgroundJob?.isActive == true

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

    // ------------------------------------------------------------------ пайплайн

    private suspend fun runPipeline(context: Context, fileUri: Uri?, mode: RecognitionMode) {
        try {
            if (fileUri == null && !hasMicrophonePermission(context)) {
                _state.value = RecognitionState.PermissionRequired
                return
            }
            val samples = readSamples(context, fileUri, mode)
            if (samples.size < AudioFingerprint.FRAME_SIZE) {
                throw IllegalStateException("Фрагмент слишком короткий для распознавания")
            }
            if (Pcm.rms(samples) < AudioFingerprint.MIN_RMS) {
                throw IllegalStateException(
                    "Слишком тихо: музыки почти не слышно. Поднесите телефон ближе к источнику.",
                )
            }
            when (mode) {
                RecognitionMode.LISTEN -> runListenPipeline(context, samples)
                RecognitionMode.HUM -> runHumPipeline(context, samples)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _state.value = RecognitionState.Error(RecognitionErrors.describe(error))
        }
    }

    private suspend fun readSamples(context: Context, fileUri: Uri?, mode: RecognitionMode): ShortArray {
        if (fileUri != null) {
            _state.value = RecognitionState.Identifying(
                stage = "Читаем выбранный файл…",
                phase = RecognitionPhase.RECOGNIZING,
            )
            val decoded = LocalPcmDecoder.decodeToMono16k(context, fileUri, IMPORT_DURATION_MS)
                ?: throw IllegalStateException("Не удалось прочитать аудио из файла")
            return decoded
        }
        val durationMs = if (mode == RecognitionMode.HUM) HUM_DURATION_MS else RECORD_DURATION_MS
        _state.value = RecognitionState.Recording(0L, durationMs, mode)
        val bytes = FragmentRecorder(context).record(durationMs) { elapsedMs ->
            _state.value = RecognitionState.Recording(
                elapsedMs = elapsedMs.coerceAtMost(durationMs),
                totalMs = durationMs,
                mode = mode,
            )
        }
        return Pcm.bytesToShorts(bytes)
    }

    // ------------------------------------------------------- режим «слушаем вокруг»

    private suspend fun runListenPipeline(context: Context, samples: ShortArray) {
        val fingerprint = AudioFingerprint.compute(samples)
        if (fingerprint.frameCount < AudioFingerprint.MIN_FRAMES || fingerprint.peakCount == 0) {
            _state.value = RecognitionState.Error(
                "Фрагмент слишком короткий или в нём не слышно музыки. Попробуйте записать ещё раз.",
            )
            return
        }

        // 1. Кэш по хэшу отпечатка: повторный фрагмент не ходит в сеть.
        val cacheKey = RecognitionResultCache.keyOf(fingerprint)
        val cached = runCatching { RecognitionResultCache(context).get(cacheKey) }.getOrNull()
        if (cached != null && cached.tracks.isNotEmpty()) {
            val first = cached.tracks.first()
            val isLocal = cached.fromLocalLibrary || first.localTrackId != null
            val result = RecognitionState.Result(
                track = first,
                enrichment = if (isLocal) {
                    RecognitionEnrichment(coverUrl = first.artworkUrl, album = first.album, done = true)
                } else {
                    RecognitionEnrichment()
                },
                fromLocalLibrary = isLocal,
                mode = RecognitionMode.LISTEN,
                alternatives = cached.tracks.drop(1),
                localTrackId = cached.localTrackId ?: first.localTrackId,
                fromCache = true,
            )
            _state.value = result
            launchBackgroundWork(context, fingerprint, result, enrich = !isLocal)
            return
        }

        val token = RecognitionSettings(context).auddToken
        if (token.isBlank()) {
            localFallback(context, fingerprint, tokenMissing = true, fallbackMessage = null)
            return
        }

        // 2. AudD: распознаёт незнакомую музыку.
        _state.value = RecognitionState.Identifying(
            stage = "Распознаём песню…",
            phase = RecognitionPhase.RECOGNIZING,
        )
        val outcome = withTimeoutOrNull(AUDD_TIMEOUT_MS) {
            AudDClient(token).identify(FRAGMENT_FILE_NAME, Pcm.wav(Pcm.shortsToBytes(samples)))
        } ?: AudDOutcome.Failure("Сервис распознавания не ответил вовремя. Попробуйте ещё раз.")

        when (outcome) {
            is AudDOutcome.Found -> publishRemoteResult(context, fingerprint, outcome.track)
            AudDOutcome.NotFound -> localFallback(
                context,
                fingerprint,
                tokenMissing = false,
                fallbackMessage = "Песня не распознана. Попробуйте фрагмент ближе к припеву и без шума.",
            )
            is AudDOutcome.Failure -> localFallback(
                context,
                fingerprint,
                tokenMissing = false,
                fallbackMessage = outcome.message,
            )
            AudDOutcome.MissingToken -> localFallback(
                context,
                fingerprint,
                tokenMissing = true,
                fallbackMessage = null,
            )
        }
    }

    private suspend fun publishRemoteResult(
        context: Context,
        fingerprint: AudioFingerprint,
        track: RecognizedTrack,
    ) {
        val result = RecognitionState.Result(
            track = track,
            fromLocalLibrary = false,
            mode = RecognitionMode.LISTEN,
        )
        _state.value = result
        cacheResult(context, fingerprint, result)
        launchBackgroundWork(context, fingerprint, result, enrich = true)
    }

    /**
     * Честный офлайн-fallback: сверка фрагмента со своей фонотекой по отпечатку.
     * Возвращает top-N кандидатов; лучший сразу можно слушать.
     */
    private suspend fun localFallback(
        context: Context,
        fingerprint: AudioFingerprint,
        tokenMissing: Boolean,
        fallbackMessage: String?,
    ) {
        _state.value = RecognitionState.Identifying(
            stage = if (tokenMissing) {
                "Ключ AudD не задан — сверяем с вашей фонотекой…"
            } else {
                "Сверяем с вашей фонотекой…"
            },
            phase = RecognitionPhase.SEARCHING,
        )
        val matches = withTimeoutOrNull(LOCAL_SCAN_TIMEOUT_MS) {
            LocalLibraryFingerprintIndex(context).identify(fingerprint) { done, total ->
                _state.value = RecognitionState.Identifying(
                    stage = "Сверяем с фонотекой: $done из $total",
                    phase = RecognitionPhase.SEARCHING,
                )
            }
        }.orEmpty()

        if (matches.isNotEmpty()) {
            val best = matches.first()
            val alternatives = matches.drop(1).map { it.toRecognizedTrack() }
            val result = RecognitionState.Result(
                track = best.toRecognizedTrack(),
                enrichment = RecognitionEnrichment(
                    coverUrl = best.track.artworkUrl,
                    album = best.track.album,
                    done = true,
                ),
                fromLocalLibrary = true,
                mode = RecognitionMode.LISTEN,
                alternatives = alternatives,
                localTrackId = best.track.id,
                verification = RecognitionVerification(
                    localAvailable = true,
                    localTrackId = best.track.id,
                    localScore = best.score,
                    note = "Та же запись найдена в вашей фонотеке.",
                ),
            )
            _state.value = result
            cacheResult(context, fingerprint, result)
            launchBackgroundWork(context, fingerprint, result, enrich = false)
            return
        }

        _state.value = when {
            tokenMissing -> RecognitionState.Error(MISSING_TOKEN_MESSAGE, needsAuddToken = true)
            fallbackMessage != null -> RecognitionState.Error(
                "$fallbackMessage\n\nВ вашей фонотеке совпадений тоже нет.",
            )
            else -> RecognitionState.Error(
                "Не удалось распознать фрагмент. Попробуйте ближе к припеву и без шума.",
            )
        }
    }

    // ------------------------------------------------------ режим «напеть мелодию»

    private suspend fun runHumPipeline(context: Context, samples: ShortArray) {
        _state.value = RecognitionState.Identifying(
            stage = "Разбираем напетую мелодию…",
            phase = RecognitionPhase.RECOGNIZING,
        )
        val contour = MelodyContour.fromSamples(samples)
        if (contour == null || !contour.isUsable) {
            _state.value = RecognitionState.Error(
                "Не удалось выделить мелодию. Напойте или просвистите её громче " +
                    "и ближе к микрофону, без слов и шума.",
            )
            return
        }

        _state.value = RecognitionState.Identifying(
            stage = "Сверяем мелодию с фонотекой…",
            phase = RecognitionPhase.SEARCHING,
        )
        val matches = withTimeoutOrNull(MELODY_SCAN_TIMEOUT_MS) {
            LocalLibraryMelodyIndex(context).identify(contour) { done, total ->
                _state.value = RecognitionState.Identifying(
                    stage = "Сверяем мелодию: $done из $total",
                    phase = RecognitionPhase.SEARCHING,
                )
            }
        }.orEmpty()

        if (matches.isEmpty()) {
            _state.value = RecognitionState.Error(HUM_NOT_FOUND_MESSAGE)
            return
        }
        val best = matches.first()
        val result = RecognitionState.Result(
            track = best.toRecognizedTrack(),
            enrichment = RecognitionEnrichment(
                coverUrl = best.track.artworkUrl,
                album = best.track.album,
                done = true,
            ),
            fromLocalLibrary = true,
            mode = RecognitionMode.HUM,
            alternatives = matches.drop(1).map { it.toRecognizedTrack() },
            localTrackId = best.track.id,
            verification = RecognitionVerification(
                localAvailable = true,
                localTrackId = best.track.id,
                localScore = best.score,
                note = "Мелодия совпала с треком в вашей фонотеке.",
            ),
        )
        _state.value = result
        launchBackgroundWork(context, fingerprint = null, initial = result, enrich = false)
    }

    // ------------------------------------------------------- фоновое обогащение/проверка

    /**
     * Фоновые шаги после показа результата: метаданные, проверка по аудио,
     * аудио-фичи. Работает отдельной корутиной на IO, отменяется [cancel].
     */
    private fun launchBackgroundWork(
        context: Context,
        fingerprint: AudioFingerprint?,
        initial: RecognitionState.Result,
        enrich: Boolean,
    ) {
        backgroundJob?.cancel()
        backgroundJob = scope.launch {
            try {
                var current = initial
                updateResultIfSame(initial) { it.copy(verifying = true) }

                if (enrich) {
                    val enrichment = withTimeoutOrNull(ENRICH_TIMEOUT_MS) {
                        runCatching { RecognitionEnricher(context).enrich(initial.track) }.getOrNull()
                    } ?: RecognitionEnrichment(
                        coverUrl = initial.track.artworkUrl,
                        album = initial.track.album,
                        done = true,
                    )
                    currentCoroutineContext().ensureActive()
                    current = current.copy(enrichment = enrichment)
                    updateResultIfSame(initial) { it.copy(enrichment = enrichment, verifying = true) }
                }

                // Для локального совпадения повторная сверка с фонотекой не нужна:
                // оно уже найдено основным пайплайном. Верификатор запускаем только
                // для результата из сети (AudD), где есть что подтверждать.
                val outcome = if (enrich && fingerprint != null) {
                    withTimeoutOrNull(BACKGROUND_VERIFY_TIMEOUT_MS) {
                        runCatching { RecognitionVerifier(context).verify(fingerprint, current) }.getOrNull()
                    }
                } else {
                    null
                }
                currentCoroutineContext().ensureActive()

                val features = if (outcome == null) {
                    withTimeoutOrNull(FEATURES_TIMEOUT_MS) {
                        runCatching { loadFeaturesLabel(context, current) }.getOrNull()
                    }
                } else {
                    null
                }

                var updated = current
                if (outcome != null) {
                    updated = current.copy(
                        alternatives = mergeAlternatives(current, outcome.localMatches),
                        localTrackId = outcome.verification.localTrackId ?: current.localTrackId,
                        verification = outcome.verification,
                        verifying = false,
                    )
                } else {
                    val verification = (current.verification ?: RecognitionVerification()).copy(
                        featuresSummary = features,
                    )
                    updated = current.copy(verification = verification, verifying = false)
                }
                updateResultIfSame(initial) { updated }
                cacheResult(context, fingerprint, updated)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                updateResultIfSame(initial) { it.copy(verifying = false) }
            }
        }
    }

    /** Аудио-фичи лучшего локального трека/кандидата (кэш + сеть, best-effort). */
    private suspend fun loadFeaturesLabel(context: Context, result: RecognitionState.Result): String? {
        val graph = runCatching { AppGraph.get(context) }.getOrNull() ?: return null
        if (!graph.settings.current.audioTelemetry) return null
        val track = result.localTrackId?.let { id ->
            runCatching { graph.library.tracks.value.firstOrNull { it.id == id } }.getOrNull()
        } ?: result.enrichment.candidates.firstOrNull { it.playable && it.videoId != null }
            ?.let { candidate ->
                Track(
                    id = candidate.trackId ?: "recognition-candidate:${candidate.title}",
                    title = candidate.title,
                    artist = candidate.artist,
                    album = candidate.subtitle,
                    source = Source.YOUTUBE,
                    playable = candidate.playable,
                    extra = candidate.videoId?.let { mapOf("youtubeId" to it) } ?: emptyMap(),
                )
            } ?: return null
        return graph.audioFeatures.featuresFor(track)?.shortLabel()?.takeIf { it.isNotBlank() }
    }

    /** Добавляет локальные совпадения в альтернативы и сортирует по уверенности. */
    private fun mergeAlternatives(
        result: RecognitionState.Result,
        localMatches: List<LocalMatch>,
    ): List<RecognizedTrack> {
        if (localMatches.isEmpty()) return result.alternatives
        val existing = LinkedHashMap<String, RecognizedTrack>()
        result.alternatives.forEach { track ->
            existing[track.localTrackId ?: track.displayTitle] = track
        }
        localMatches.forEach { match ->
            val key = match.track.id
            if (!existing.containsKey(key)) existing[key] = match.toRecognizedTrack()
        }
        return existing.values
            .sortedByDescending { it.score ?: 0.0 }
            .take(MAX_ALTERNATIVES)
    }

    private fun updateResultIfSame(
        expected: RecognitionState.Result,
        transform: (RecognitionState.Result) -> RecognitionState.Result,
    ) {
        val current = _state.value
        if (current is RecognitionState.Result &&
            current.mode == expected.mode &&
            current.track.displayTitle == expected.track.displayTitle
        ) {
            _state.value = transform(current)
        }
    }

    private suspend fun cacheResult(
        context: Context,
        fingerprint: AudioFingerprint?,
        result: RecognitionState.Result,
    ) {
        if (fingerprint == null) return
        runCatching {
            val tracks = buildList {
                add(result.track)
                addAll(result.alternatives)
            }
            RecognitionResultCache(context).put(
                key = RecognitionResultCache.keyOf(fingerprint),
                tracks = tracks,
                localTrackId = result.localTrackId,
                fromLocalLibrary = result.fromLocalLibrary,
                mode = result.mode,
            )
        }
    }

    private fun LocalMatch.toRecognizedTrack(): RecognizedTrack = RecognizedTrack(
        artist = track.artist,
        title = track.title,
        album = track.album,
        songLink = track.deepLink,
        artworkUrl = track.artworkUrl,
        provider = LOCAL_PROVIDER,
        score = score,
        localTrackId = track.id,
    )

    private fun LocalMelodyMatch.toRecognizedTrack(): RecognizedTrack = RecognizedTrack(
        artist = track.artist,
        title = track.title,
        album = track.album,
        songLink = track.deepLink,
        artworkUrl = track.artworkUrl,
        provider = "$LOCAL_PROVIDER · мелодия",
        score = score,
        localTrackId = track.id,
    )

    private const val RECORD_DURATION_MS = 8_000L
    private const val HUM_DURATION_MS = 10_000L
    private const val AUDD_TIMEOUT_MS = 40_000L
    private const val LOCAL_SCAN_TIMEOUT_MS = 180_000L
    private const val MELODY_SCAN_TIMEOUT_MS = 180_000L
    private const val ENRICH_TIMEOUT_MS = 25_000L
    private const val BACKGROUND_VERIFY_TIMEOUT_MS = 150_000L
    private const val FEATURES_TIMEOUT_MS = 35_000L
    private const val IMPORT_DURATION_MS = 10_000
    private const val MAX_ALTERNATIVES = 5
    private const val FRAGMENT_FILE_NAME = "sonora_fragment.wav"
    private const val LOCAL_PROVIDER = "Фонотека устройства"

    private const val MISSING_TOKEN_MESSAGE =
        "Ключ AudD не задан — распознать незнакомую песню нельзя. " +
            "Введите ключ (300 запросов бесплатно после регистрации), " +
            "а без ключа Sonora сверяет фрагмент только с вашей фонотекой."

    private const val HUM_NOT_FOUND_MESSAGE =
        "Мелодия не найдена в вашей фонотеке. Поиск по напеву работает только " +
            "по трекам на устройстве: стриминговые треки не анализируются. " +
            "Попробуйте напеть ближе к припеву."
}
