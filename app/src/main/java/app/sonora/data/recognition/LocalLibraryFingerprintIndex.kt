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

/**
 * Кэш отпечатков локальной фонотеки (JSON в filesDir).
 * Храним только числа: путь/метаданные берём из LibraryRepository при сверке.
 */
@Serializable
internal data class StoredFingerprint(
    val trackId: String,
    val frameCount: Int,
    val rms: Float,
    val hashes: List<Int>,
    val deltas: List<Float>,
    val updatedAt: Long,
) {
    fun toFingerprint(): AudioFingerprint = AudioFingerprint(
        hashes = hashes.toIntArray(),
        deltas = deltas.toFloatArray(),
        frameCount = frameCount,
        rms = rms,
    )
}

@Serializable
internal data class FingerprintCache(val entries: List<StoredFingerprint> = emptyList())

/** Совпадение с треком из своей фонотеки. */
internal data class LocalMatch(val track: Track, val score: Double)

/**
 * Честный fallback без API-ключей: сверка фрагмента с **своей** фонотекой.
 *
 * Это не замена AudD: совпадение найдётся только если та же запись уже есть
 * в локальной библиотеке пользователя. Зато работает офлайн, бесплатно и
 * не отправляет звук в сеть. Отпечатки считаются лениво и кэшируются, поэтому
 * повторная сверка уже не декодирует файлы заново.
 */
internal class LocalLibraryFingerprintIndex(private val context: Context) {

    suspend fun identify(
        fragment: AudioFingerprint,
        onProgress: (done: Int, total: Int) -> Unit,
    ): LocalMatch? = withContext(Dispatchers.IO) {
        if (fragment.isEmpty) return@withContext null
        val tracks = runCatching {
            AppGraph.get(context).library.tracks.value
                .asSequence()
                .filter { it.source == Source.LOCAL && !it.uri.isNullOrBlank() }
                .filter { it.durationMs <= 0L || it.durationMs >= MIN_TRACK_DURATION_MS }
                .take(MAX_TRACKS)
                .toList()
        }.getOrDefault(emptyList())
        if (tracks.isEmpty()) return@withContext null

        val cached = loadCache().associateBy { it.trackId }.toMutableMap()
        var dirty = false
        var best: LocalMatch? = null

        tracks.forEachIndexed { index, track ->
            currentCoroutineContext().ensureActive()
            val trackUri = track.uri
            if (trackUri.isNullOrBlank()) return@forEachIndexed
            val fingerprint = cached[track.id]?.toFingerprint() ?: decodeFingerprint(track.id, trackUri)?.also {
                cached[track.id] = it.toStored(track.id)
                dirty = true
            }
            if (fingerprint != null && fingerprint.rms > 0f) {
                val match = AudioFingerprintIndex(fingerprint).match(fragment)
                val currentBest = best
                if (match.score >= FingerprintMatch.MIN_SCORE &&
                    (currentBest == null || match.score > currentBest.score)
                ) {
                    best = LocalMatch(track, match.score)
                }
            }
            onProgress(index + 1, tracks.size)
            if (dirty && (index + 1) % CACHE_SAVE_EVERY == 0) {
                saveCache(cached.values.toList())
                dirty = false
            }
        }
        if (dirty) saveCache(cached.values.toList())
        best
    }

    private suspend fun decodeFingerprint(trackId: String, uri: String): AudioFingerprint? {
        val decoded = LocalPcmDecoder.decodeToMono16k(context, Uri.parse(uri), MAX_DECODE_MS)
            ?: return null
        if (decoded.size < AudioFingerprint.FRAME_SIZE) return null
        val fingerprint = AudioFingerprint.compute(decoded)
        return fingerprint.takeUnless { it.isEmpty }
    }

    private suspend fun loadCache(): List<StoredFingerprint> =
        JsonFile.read(context, CACHE_FILE, FingerprintCache.serializer(), FingerprintCache()).entries

    private suspend fun saveCache(entries: List<StoredFingerprint>) {
        JsonFile.write(context, CACHE_FILE, FingerprintCache.serializer(), FingerprintCache(entries))
    }

    private fun AudioFingerprint.toStored(trackId: String): StoredFingerprint = StoredFingerprint(
        trackId = trackId,
        frameCount = frameCount,
        rms = rms,
        hashes = hashes.toList(),
        deltas = deltas.toList(),
        updatedAt = System.currentTimeMillis(),
    )

    companion object {
        /** Сколько треков максимум обходим за одну сверку (фон, не должно мешать). */
        const val MAX_TRACKS = 200
        const val MAX_DECODE_MS = 45_000
        private const val MIN_TRACK_DURATION_MS = 15_000L
        private const val CACHE_SAVE_EVERY = 25
        private const val CACHE_FILE = "recognition_fingerprints.json"
    }
}
