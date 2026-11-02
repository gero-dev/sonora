package app.sonora.data.recognition

import android.content.Context
import app.sonora.core.util.JsonFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** Запись кэша результатов распознавания. */
@Serializable
internal data class CachedRecognitionEntry(
    val key: String,
    val createdAtMs: Long,
    val tracks: List<RecognizedTrack>,
    val localTrackId: String? = null,
    val fromLocalLibrary: Boolean = false,
    val mode: String = RecognitionMode.LISTEN.name,
)

@Serializable
internal data class RecognitionCacheFile(
    val version: Int = 1,
    val entries: List<CachedRecognitionEntry> = emptyList(),
)

/**
 * Кэш результатов распознавания по хэшу отпечатка фрагмента.
 *
 * Идея: один и тот же фрагмент (например, повторный импорт того же файла или
 * повторный запрос по сохранённому фрагменту) не должен каждый раз ходить в
 * сеть. Ключ — SHA-256 от содержимого отпечатка (кадры + пики + RMS), поэтому
 * кэш не зависит от пути к файлу и от порядка вызовов.
 *
 * Кэш консервативен: TTL 30 дней, максимум 50 записей (LRU по времени), любые
 * ошибки чтения/записи проглатываются — кэш не должен ломать распознавание.
 */
internal class RecognitionResultCache(private val context: Context) {

    suspend fun get(key: String): CachedRecognitionEntry? = withContext(Dispatchers.IO) {
        val entry = load().entries.firstOrNull { it.key == key } ?: return@withContext null
        val age = System.currentTimeMillis() - entry.createdAtMs
        if (age > TTL_MS) {
            remove(key)
            null
        } else {
            entry
        }
    }

    suspend fun put(
        key: String,
        tracks: List<RecognizedTrack>,
        localTrackId: String?,
        fromLocalLibrary: Boolean,
        mode: RecognitionMode,
    ) = withContext(Dispatchers.IO) {
        if (tracks.isEmpty()) return@withContext
        val file = load()
        val entry = CachedRecognitionEntry(
            key = key,
            createdAtMs = System.currentTimeMillis(),
            tracks = tracks,
            localTrackId = localTrackId,
            fromLocalLibrary = fromLocalLibrary,
            mode = mode.name,
        )
        val entries = buildList {
            add(entry)
            addAll(file.entries.filter { it.key != key })
        }.sortedByDescending { it.createdAtMs }.take(MAX_ENTRIES)
        save(RecognitionCacheFile(entries = entries))
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        save(RecognitionCacheFile())
    }

    private suspend fun remove(key: String) {
        val file = load()
        save(RecognitionCacheFile(entries = file.entries.filter { it.key != key }))
    }

    private suspend fun load(): RecognitionCacheFile {
        val file = JsonFile.read(
            context,
            CACHE_FILE,
            RecognitionCacheFile.serializer(),
            RecognitionCacheFile(),
        )
        return if (file.version == SCHEMA_VERSION) file else RecognitionCacheFile()
    }

    private suspend fun save(file: RecognitionCacheFile) {
        JsonFile.write(context, CACHE_FILE, RecognitionCacheFile.serializer(), file)
    }

    companion object {
        const val SCHEMA_VERSION = 1
        private const val CACHE_FILE = "recognition_results_v1.json"
        private const val MAX_ENTRIES = 50
        private const val TTL_MS = 30L * 24 * 60 * 60 * 1000

        /**
         * Стабильный ключ кэша по отпечатку. Чистая функция (JVM-тестируемая):
         * одинаковый фрагмент → одинаковый ключ, разные → разные.
         */
        fun keyOf(fingerprint: AudioFingerprint): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(intBytes(fingerprint.frameCount))
            digest.update(floatBytes(fingerprint.rms))
            digest.update(fingerprint.peakBands)
            return digest.digest().joinToString("") { byte ->
                (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
            }
        }

        private fun intBytes(value: Int): ByteArray = byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )

        private fun floatBytes(value: Float): ByteArray =
            intBytes(value.toRawBits())
    }
}
