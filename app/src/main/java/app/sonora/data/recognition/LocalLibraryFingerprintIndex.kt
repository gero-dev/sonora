package app.sonora.data.recognition

import android.content.Context
import android.net.Uri
import android.util.Base64
import app.sonora.AppGraph
import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.core.util.JsonFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Компактная запись отпечатка для кэша в filesDir.
 *
 * Пики пакуются в Base64 (1 байт на пик), поэтому файл кэша в разы меньше, чем
 * при хранении FloatArray в JSON. Формат версионирован: старый файл
 * `recognition_fingerprints.json` (v1) не читается и не удаляется — просто
 * игнорируется, а отпечатки пересчитываются в новом формате.
 */
@Serializable
internal data class StoredFingerprint(
    val version: Int = 2,
    val trackId: String,
    val frameCount: Int,
    val rms: Float,
    /** [AudioFingerprint.peakBands] в Base64 (по байту на пик, -1 = нет пика). */
    val peaks: String,
    val updatedAt: Long,
) {
    fun toFingerprint(): AudioFingerprint? {
        if (version != CACHE_SCHEMA_VERSION || frameCount <= 0) return null
        val bytes = runCatching { Base64.decode(peaks, Base64.NO_WRAP) }.getOrNull() ?: return null
        val expected = frameCount * AudioFingerprint.PEAKS_PER_FRAME
        if (bytes.size != expected) return null
        return AudioFingerprint(frameCount = frameCount, rms = rms, peakBands = bytes)
    }

    companion object {
        const val CACHE_SCHEMA_VERSION = 2
    }
}

@Serializable
internal data class FingerprintCache(val entries: List<StoredFingerprint> = emptyList())

/** Совпадение с треком из своей фонотеки. */
internal data class LocalMatch(
    val track: Track,
    val score: Double,
    /** Смещение фрагмента внутри трека в кадрах. */
    val offsetFrames: Int = 0,
)

/**
 * Честный fallback без API-ключей: сверка фрагмента с **своей** фонотекой.
 *
 * Это не замена AudD: совпадение найдётся только если та же запись уже есть
 * в локальной библиотеке пользователя. Зато работает офлайн, бесплатно и
 * не отправляет звук в сеть. Отпечатки считаются лениво и кэшируются в
 * `fingerprint_cache_v2.json`, поэтому повторная сверка уже не декодирует
 * файлы заново.
 */
internal class LocalLibraryFingerprintIndex(private val context: Context) {

    /**
     * Сверяет фрагмент с локальной фонотекой и возвращает top-N совпадений
     * (по убыванию оценки). Тяжёлая работа идёт на [Dispatchers.IO], прогресс
     * отдаётся в [onProgress], отмена корутины прекращает скан.
     */
    suspend fun identify(
        fragment: AudioFingerprint,
        maxResults: Int = MAX_RESULTS,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<LocalMatch> = withContext(Dispatchers.IO) {
        if (fragment.isEmpty) return@withContext emptyList()
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
        val matches = ArrayList<LocalMatch>()

        tracks.forEachIndexed { index, track ->
            currentCoroutineContext().ensureActive()
            val trackUri = track.uri
            if (trackUri.isNullOrBlank()) return@forEachIndexed
            val fingerprint = cache[track.id]?.toFingerprint() ?: decodeFingerprint(track.id, trackUri)?.also {
                cache[track.id] = it.toStored(track.id)
                dirty = true
            }
            if (fingerprint != null && fingerprint.rms > 0f) {
                val match = AudioFingerprintIndex(fingerprint).match(fragment)
                if (match.score >= FingerprintMatch.MIN_SCORE) {
                    matches.add(LocalMatch(track, match.score, match.offsetFrames))
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

    private suspend fun decodeFingerprint(trackId: String, uri: String): AudioFingerprint? {
        val decoded = LocalPcmDecoder.decodeToMono16k(context, Uri.parse(uri), MAX_DECODE_MS)
            ?: return null
        if (decoded.size < AudioFingerprint.FRAME_SIZE) return null
        val fingerprint = AudioFingerprint.compute(decoded)
        return fingerprint.takeUnless { it.isEmpty }
    }

    private suspend fun loadCache(): List<StoredFingerprint> {
        val loaded = JsonFile.read(
            context,
            CACHE_FILE,
            FingerprintCache.serializer(),
            FingerprintCache(),
        ).entries
        return loaded.filter { it.version == StoredFingerprint.CACHE_SCHEMA_VERSION }
    }

    private suspend fun saveCache(entries: List<StoredFingerprint>) {
        JsonFile.write(context, CACHE_FILE, FingerprintCache.serializer(), FingerprintCache(entries))
    }

    /** Ограничиваем размер кэша по давности обновления. */
    private fun trimCache(cache: Map<String, StoredFingerprint>): List<StoredFingerprint> =
        cache.values
            .sortedByDescending { it.updatedAt }
            .take(MAX_CACHED_TRACKS)

    private fun AudioFingerprint.toStored(trackId: String): StoredFingerprint = StoredFingerprint(
        trackId = trackId,
        frameCount = frameCount,
        rms = rms,
        peaks = Base64.encodeToString(peakBands, Base64.NO_WRAP),
        updatedAt = System.currentTimeMillis(),
    )

    companion object {
        /** Сколько треков максимум обходим за одну сверку (фон, не должно мешать). */
        const val MAX_TRACKS = 200
        const val MAX_CACHED_TRACKS = 250
        const val MAX_DECODE_MS = 45_000
        const val MAX_RESULTS = 5

        private const val MIN_TRACK_DURATION_MS = 15_000L
        private const val CACHE_SAVE_EVERY = 25
        private const val CACHE_FILE = "fingerprint_cache_v2.json"
    }
}
