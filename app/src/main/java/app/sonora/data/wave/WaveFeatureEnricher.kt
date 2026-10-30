package app.sonora.data.wave

import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.data.telemetry.AudioFeature
import app.sonora.data.telemetry.AudioFeatureProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Фоновое обогащение волны аудио-фичами ReccoBeats.
 *
 * Правила, без которых телеметрия мешала бы:
 *  * только фон: вызов не блокирует сборку волны и воспроизведение;
 *  * [requestTimeoutMs] на трек — зависшая сеть не держит job вечно;
 *  * пауза [betweenRequestsMs] между треками — вежливо к бесплатному API;
 *  * [cancel] перед каждой новой волной — старые запросы не нужны;
 *  * телеметрия выключена → [AudioFeatureProvider.available] = false → сразу выход;
 *  * любая ошибка/офлайн проглатываются, волна остаётся рабочей.
 */
class WaveFeatureEnricher(
    private val provider: AudioFeatureProvider,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val requestTimeoutMs: Long = 25_000L,
    private val betweenRequestsMs: Long = 400L,
) {

    private var job: Job? = null

    val running: Boolean get() = job?.isActive == true

    fun cancel() {
        job?.cancel()
        job = null
    }

    /**
     * Посчитать фичи для первых [limit] треков волны. [onFetched] вызывается
     * один раз на фоновом потоке и только если job не отменён; карта —
     * `track.id → AudioFeature`.
     */
    fun enrich(
        tracks: List<Track>,
        limit: Int = DEFAULT_LIMIT,
        onFetched: (Map<String, AudioFeature>) -> Unit,
    ) {
        cancel()
        if (!provider.available) return
        if (limit <= 0 || tracks.isEmpty()) return
        val targets = tracks.asSequence()
            .filter { it.source != Source.LOCAL || !it.extra["youtubeId"].isNullOrBlank() }
            .distinctBy { it.id }
            .take(limit)
            .toList()
        if (targets.isEmpty()) return

        job = scope.launch(dispatcher) {
            val fetched = LinkedHashMap<String, AudioFeature>(targets.size)
            for (track in targets) {
                if (!isActive) break
                val feature = withTimeoutOrNull(requestTimeoutMs) {
                    try {
                        provider.fetchFeature(track)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                }
                if (!isActive) break
                if (feature == null) continue
                fetched[track.id] = feature
                if (betweenRequestsMs > 0L) delay(betweenRequestsMs)
            }
            if (isActive && fetched.isNotEmpty()) onFetched(fetched)
        }
    }

    companion object {
        /** Сколько треков досчитываем за одну сборку волны: API бесплатное. */
        const val DEFAULT_LIMIT = 4
    }
}
