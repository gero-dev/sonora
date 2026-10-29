package app.sonora.data.telemetry

import android.content.Context
import app.sonora.core.util.JsonFile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Аудио-фичи трека из ReccoBeats. Поля совпадают с ответом API; null означает,
 * что сервис не смог посчитать конкретную характеристику.
 */
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
) {
    val isEmpty: Boolean
        get() = listOf(
            tempo, energy, danceability, valence, acousticness,
            instrumentalness, liveness, speechiness, loudness,
        ).all { it == null }

    /** Короткая подпись для UI: «124 BPM · энергия 82%». */
    fun shortLabel(): String {
        val parts = buildList {
            tempo?.takeIf { it > 0 }?.let { add("${it.toInt()} BPM") }
            energy?.let { add("энергия ${(it * 100).toInt()}%") }
            danceability?.let { add("танцевальность ${(it * 100).toInt()}%") }
        }
        return parts.joinToString(" · ")
    }

    /** Вектор для косинусного сходства (0..1 в каждой координате). */
    fun vector(): DoubleArray = doubleArrayOf(
        energy ?: 0.0,
        danceability ?: 0.0,
        valence ?: 0.0,
        acousticness ?: 0.0,
        instrumentalness ?: 0.0,
        ((tempo ?: 0.0) / 200.0).coerceIn(0.0, 1.0),
    )

    companion object {
        fun cosine(a: AudioFeature, b: AudioFeature): Double {
            val va = a.vector()
            val vb = b.vector()
            val dot = va.indices.sumOf { va[it] * vb[it] }
            val na = kotlin.math.sqrt(va.sumOf { it * it })
            val nb = kotlin.math.sqrt(vb.sumOf { it * it })
            return if (na == 0.0 || nb == 0.0) 0.0 else dot / (na * nb)
        }
    }
}

@Serializable
private data class CachedFeature(
    val key: String,
    val feature: AudioFeature,
    val savedAtMs: Long,
)

/**
 * Кэш фич в `filesDir/audio_features.json`. Room не нужен: записей немного,
 * а JsonFile уже используется библиотекой и историей.
 */
class AudioFeatureCache(context: Context) {

    private val appContext = context.applicationContext
    private val serializer = ListSerializer(CachedFeature.serializer())
    private val mutex = Mutex()
    private var loaded = false
    private val map = LinkedHashMap<String, AudioFeature>()

    private suspend fun ensureLoaded() = mutex.withLock {
        if (loaded) return@withLock
        JsonFile.read(appContext, FILE, serializer, emptyList())
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
            while (map.size > MAX_ENTRIES) {
                val oldest = map.keys.firstOrNull() ?: break
                map.remove(oldest)
            }
            JsonFile.write(
                appContext,
                FILE,
                serializer,
                map.map { (k, v) -> CachedFeature(k, v, System.currentTimeMillis()) },
            )
        }
    }

    suspend fun clear() {
        ensureLoaded()
        mutex.withLock {
            map.clear()
            JsonFile.write(appContext, FILE, serializer, emptyList())
        }
    }

    private companion object {
        const val FILE = "audio_features.json"
        const val MAX_ENTRIES = 1000
    }
}
