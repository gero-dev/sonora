package app.sonora.data.metadata

import android.content.Context
import app.sonora.core.model.TrackMetadata
import app.sonora.core.util.JsonFile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Постоянный кэш метаданных песен.
 *
 * Всё, что пришло из MusicBrainz / Cover Art Archive / LRCLIB, сохраняется на диск сразу
 * после получения и переживает перезапуск приложения. Повторный поиск для той же песни
 * не выполняется: [app.sonora.data.metadata.MetadataService] сначала смотрит сюда.
 *
 * Кэшируются и неудачные попытки ([CachedMetadata.failed]) — чтобы не дёргать сеть
 * каждый запуск для инструменталов и редких треков. Записи старше [TTL_MS]
 * считаются устаревшими и перезапрашиваются.
 */
class MetadataCache(private val context: Context) {

    private val mutex = Mutex()
    private var loaded = false
    private val entries = LinkedHashMap<String, CachedMetadata>()
    private val serializer = MapSerializer(String.serializer(), CachedMetadata.serializer())

    /** Загруженная запись или null, если песня ещё не искалась. */
    suspend fun get(key: String): CachedMetadata? = mutex.withLock {
        ensureLoaded()
        entries[key]
    }

    /** Сохраняет результат поиска (включая null — как «ничего не нашлось»). */
    suspend fun put(key: String, metadata: TrackMetadata?): CachedMetadata = mutex.withLock {
        ensureLoaded()
        val previous = entries[key]
        val cached = CachedMetadata(
            album = metadata?.album,
            year = metadata?.year,
            coverUrl = metadata?.coverUrl,
            lyrics = metadata?.lyrics,
            syncedLyrics = metadata?.syncedLyrics,
            musicBrainzId = metadata?.musicBrainzId,
            artist = metadata?.artist,
            title = metadata?.title,
            artistImageUrl = metadata?.artistImageUrl,
            artistBio = metadata?.artistBio,
            genre = metadata?.genre,
            provider = metadata?.provider ?: PROVIDER_NONE,
            fetchedAt = System.currentTimeMillis(),
            failed = metadata == null || metadata.isEmpty,
            attempts = (previous?.attempts ?: 0) + 1,
            schemaVersion = SCHEMA_VERSION,
        )
        entries[key] = cached
        persist()
        cached
    }

    suspend fun size(): Int = mutex.withLock {
        ensureLoaded()
        entries.size
    }

    suspend fun clear() = mutex.withLock {
        ensureLoaded()
        entries.clear()
        persist()
    }

    /** Сколько записей реально содержит данные (не «пустых»). */
    suspend fun successCount(): Int = mutex.withLock {
        ensureLoaded()
        entries.values.count { !it.failed }
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        val restored = JsonFile.read(context, FILE, serializer, emptyMap())
        entries.clear()
        entries.putAll(restored)
        loaded = true
    }

    private suspend fun persist() {
        JsonFile.write(context, FILE, serializer, entries)
    }

    private companion object {
        const val FILE = "metadata_cache.json"
        const val PROVIDER_NONE = "none"

        /** 2: добавлены TheAudioDB, Deezer-артист, Wikipedia и InnerTube-fallback. */
        const val SCHEMA_VERSION = 2
    }
}

@Serializable
data class CachedMetadata(
    val album: String? = null,
    val year: Int? = null,
    val coverUrl: String? = null,
    val lyrics: String? = null,
    val syncedLyrics: String? = null,
    val musicBrainzId: String? = null,
    val artist: String? = null,
    val title: String? = null,
    val artistImageUrl: String? = null,
    val artistBio: String? = null,
    val genre: String? = null,
    val provider: String = "MusicBrainz",
    val fetchedAt: Long = 0L,
    val failed: Boolean = false,
    val attempts: Int = 0,
    /** Версия схемы: старые записи перезапрашиваются после добавления источников. */
    val schemaVersion: Int = 1,
) {
    /**
     * Успешные данные живут 30 дней, «ничего не найдено» — только 6 часов:
     * если провайдер был недоступен или база обновилась, поиск повторится
     * в тот же день, а не через месяц.
     */
    val isFresh: Boolean
        get() {
            val ttl = if (failed) FAILED_TTL_MS else TTL_MS
            return System.currentTimeMillis() - fetchedAt < ttl
        }

    fun toTrackMetadata(): TrackMetadata = TrackMetadata(
        album = album,
        year = year,
        coverUrl = coverUrl,
        lyrics = lyrics,
        syncedLyrics = syncedLyrics,
        musicBrainzId = musicBrainzId,
        artist = artist,
        title = title,
        artistImageUrl = artistImageUrl,
        artistBio = artistBio,
        genre = genre,
        provider = provider,
    )

    companion object {
        /** 30 дней: метаданные песен меняются редко. */
        const val TTL_MS = 30L * 24 * 60 * 60 * 1000

        /** Отрицательный результат перепроверяем через 6 часов. */
        const val FAILED_TTL_MS = 6L * 60 * 60 * 1000

        /** Нормализованный ключ «исполнитель|название». */
        fun key(artist: String, title: String): String =
            "${artist.trim().lowercase()}|${title.trim().lowercase()}"
    }
}
