package app.sonora.data.recognition

import android.content.Context
import android.net.Uri
import app.sonora.AppGraph
import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.core.util.JsonFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** Контур мелодии, сохранённый в кэше (компактно: полутоны * 10). */
@Serializable
internal data class StoredMelodyContour(
    val version: Int = 2,
    val trackId: String,
    val hopMs: Int,
    val startMs: Int,
    /** Полутоны * 10; [UNVOICED] — невокализованный кадр. */
    val semitones: List<Int>,
    val updatedAt: Long,
) {
    fun toContour(): MelodyContour? {
        if (version != CACHE_SCHEMA_VERSION || semitones.isEmpty()) return null
        val hopSec = hopMs / 1000f
        val startSec = startMs / 1000f
        val times = FloatArray(semitones.size) { index -> startSec + index * hopSec }
        val values = FloatArray(semitones.size) { index ->
            val value = semitones[index]
            if (value == UNVOICED) Float.NaN else value / 10f
        }
        return MelodyContour.fromFrames(times, values)
    }

    companion object {
        const val CACHE_SCHEMA_VERSION = 2
        const val UNVOICED = Int.MIN_VALUE
    }
}

@Serializable
internal data class MelodyCacheFile(
    val version: Int = 2,
    val entries: List<StoredMelodyContour> = emptyList(),
)

/** Совпадение напетой мелодии с треком фонотеки. */
internal data class LocalMelodyMatch(
    val track: Track,
    val score: Double,
)

/**
 * Офлайн-поиск по напетой мелодии: контуры треков локальной фонотеки считаются
 * лениво (pitch tracking по первым ~20 секундам файла) и кэшируются в
 * `melody_contours_v2.json`.
 *
 * Ограничения честные: hum-режим работает **только по трекам на устройстве**
 * (у стриминговых треков нет локального аудио для анализа), а инструментальные
 * треки с плотным аккомпанементом распознаются хуже вокала — DTW сравнивает
 * контуры, а не тембр.
 */
internal class LocalLibraryMelodyIndex(private val context: Context) {

    suspend fun identify(
        query: MelodyContour,
        maxResults: Int = MAX_RESULTS,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<LocalMelodyMatch> = withContext(Dispatchers.IO) {
        if (!query.isUsable) return@withContext emptyList()
        val tracks = runCatching {
            AppGraph.get(context).library.tracks.value
                .asSequence()
                .filter { it.source == Source.LOCAL && !it.uri.isNullOrBlank() }
                .filter { it.durationMs <= 0L || it.durationMs >= MIN_TRACK_DURATION_MS }
                .take(MAX_TRACKS)
                .toList()
        }.getOrDefault(emptyList())
        if (tracks.isEmpty()) return@withContext emptyList()

        val cache = loadCache().associateBy { it.trackId }.toMutableMap()
        var dirty = false
        val matches = ArrayList<LocalMelodyMatch>()

        tracks.forEachIndexed { index, track ->
            currentCoroutineContext().ensureActive()
            val trackUri = track.uri
            if (trackUri.isNullOrBlank()) return@forEachIndexed
            val contour = cache[track.id]?.toContour()
                ?: decodeContour(track.id, trackUri)?.also {
                    cache[track.id] = it.toStored(track.id)
                    dirty = true
                }
            if (contour != null && contour.isUsable) {
                val score = MelodyMatcher.similarity(query, contour)
                if (score >= MelodyMatcher.MIN_SCORE) {
                    matches.add(LocalMelodyMatch(track, score))
                }
            }
            onProgress(index + 1, tracks.size)
            if (dirty && (index + 1) % CACHE_SAVE_EVERY == 0) {
                saveCache(trimCache(cache))
                dirty = false
            }
        }
        if (dirty) saveCache(trimCache(cache))
        matches.sortByDescending { it.score }
        if (matches.size > maxResults) matches.subList(0, maxResults).toList() else matches
    }

    private suspend fun decodeContour(trackId: String, uri: String): MelodyContour? {
        val decoded = LocalPcmDecoder.decodeToMono16k(context, Uri.parse(uri), MAX_DECODE_MS)
            ?: return null
        return MelodyContour.fromSamples(
            decoded,
            maxDurationSec = MAX_CONTOUR_SEC,
        )?.takeIf { it.isUsable }
    }

    private suspend fun loadCache(): List<StoredMelodyContour> = JsonFile.read(
        context,
        CACHE_FILE,
        MelodyCacheFile.serializer(),
        MelodyCacheFile(),
    ).entries.filter { it.version == StoredMelodyContour.CACHE_SCHEMA_VERSION }

    private suspend fun saveCache(entries: List<StoredMelodyContour>) {
        JsonFile.write(context, CACHE_FILE, MelodyCacheFile.serializer(), MelodyCacheFile(entries = entries))
    }

    private fun trimCache(cache: Map<String, StoredMelodyContour>): List<StoredMelodyContour> =
        cache.values.sortedByDescending { it.updatedAt }.take(MAX_CACHED_TRACKS)

    private fun MelodyContour.toStored(trackId: String): StoredMelodyContour {
        val semitoneValues = IntArray(semitones.size) { index ->
            val value = semitones[index]
            if (value.isNaN()) {
                StoredMelodyContour.UNVOICED
            } else {
                (value * 10f).roundToInt()
            }
        }
        return StoredMelodyContour(
            trackId = trackId,
            hopMs = (PitchTracker.HOP_SIZE * 1000 / PitchTracker.SAMPLE_RATE),
            startMs = (timesSec[0] * 1000f).roundToInt(),
            semitones = semitoneValues.toList(),
            updatedAt = System.currentTimeMillis(),
        )
    }

    companion object {
        /** Мелодийный индекс ограничен: первое построение декодирует аудио. */
        const val MAX_TRACKS = 80
        const val MAX_CACHED_TRACKS = 120
        const val MAX_DECODE_MS = 20_000
        const val MAX_CONTOUR_SEC = 20f
        const val MAX_RESULTS = 5

        private const val MIN_TRACK_DURATION_MS = 15_000L
        private const val CACHE_SAVE_EVERY = 20
        private const val CACHE_FILE = "melody_contours_v2.json"
    }
}
