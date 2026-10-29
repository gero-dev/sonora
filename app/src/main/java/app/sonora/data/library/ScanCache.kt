package app.sonora.data.library

import android.content.Context
import app.sonora.core.model.Track
import app.sonora.core.util.JsonFile
import app.sonora.core.util.ScanSignature
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** Версия формата кэша: при несовпадении старый файл просто игнорируется. */
internal const val SCAN_CACHE_VERSION = 1

/** Имя JSON-файла кэша в filesDir. */
internal const val SCAN_CACHE_FILE = "scan_cache.json"

/** Максимум записей: кэш — ускоритель, а не источник истины. */
private const val MAX_CACHE_ENTRIES = 12_000

/** Длинные тексты песен в кэш не кладём: библиотека и так хранит их в tracks. */
private const val MAX_CACHED_LYRICS_CHARS = 4_000

/**
 * Запись кэша сканирования: сигнатура файла + уже распарсенные метаданные.
 *
 * [albumId] нужен только MediaStore-трекам: если альбом переиндексирован,
 * «битый» albumart-URI нужно пересчитать, даже когда сам файл не изменился.
 * [cachedAt] — время последнего подтверждения записью (для ограничения размера).
 */
@Serializable
data class ScanCacheEntry(
    val path: String,
    val size: Long,
    val dateModified: Long,
    val folder: String,
    val track: Track,
    val albumId: Long = 0L,
    val cachedAt: Long = 0L,
) {
    fun signature(): ScanSignature = ScanSignature(path, size, dateModified, folder)
}

/** Содержимое файла кэша: версия формата + флаги сканера, при которых он построен. */
@Serializable
data class ScanCacheFile(
    val version: Int = SCAN_CACHE_VERSION,
    val flags: String = "",
    val entries: List<ScanCacheEntry> = emptyList(),
)

/**
 * Инкрементальный кэш метаданных локальных файлов (`filesDir/scan_cache.json`).
 *
 * Живёт вместе со сканером (одна копия на процесс), в файл пишется после каждого
 * скана. Ключ записи = `folder + path`, а флаги сканера (встроенные обложки /
 * sidecar) входят в заголовок: при их изменении кэш считается недействительным
 * и метаданные перечитываются заново.
 */
internal class ScanCacheStore(private val context: Context) {

    private val mutex = Mutex()
    private var loaded = false
    private var loadedFlags: String? = null
    private val entries = HashMap<String, ScanCacheEntry>()

    /** Ранее сохранённые сигнатуры выбранных папок: ключ → сигнатура. */
    suspend fun signaturesFor(
        flags: String,
        folderFilter: (String) -> Boolean,
    ): Map<String, ScanSignature> = mutex.withLock {
        loadLocked(flags)
        val result = LinkedHashMap<String, ScanSignature>()
        entries.forEach { (key, entry) ->
            if (folderFilter(entry.folder)) result[key] = entry.signature()
        }
        result
    }

    /** Записи кэша по ключам — только для файлов, которые сканер признал неизменными. */
    suspend fun entriesFor(
        flags: String,
        keys: Collection<String>,
    ): Map<String, ScanCacheEntry> = mutex.withLock {
        loadLocked(flags)
        val result = HashMap<String, ScanCacheEntry>(keys.size)
        keys.forEach { key -> entries[key]?.let { result[key] = it } }
        result
    }

    /**
     * Фиксирует результат скана одной папки/источника: удаляет исчезнувшие записи,
     * обновляет актуальные и один раз пишет файл.
     */
    suspend fun commit(
        flags: String,
        removedKeys: Collection<String>,
        freshEntries: List<ScanCacheEntry>,
    ) {
        mutex.withLock {
            loadLocked(flags)
            removedKeys.forEach { entries.remove(it) }
            freshEntries.forEach { entries[it.signature().key] = it }
            persistLocked(flags)
        }
    }

    private suspend fun loadLocked(flags: String) {
        if (loaded && loadedFlags == flags) return
        entries.clear()
        loaded = true
        loadedFlags = flags
        val file = JsonFile.read(context, SCAN_CACHE_FILE, ScanCacheFile.serializer(), ScanCacheFile())
        if (file.version != SCAN_CACHE_VERSION || file.flags != flags) return
        file.entries.forEach { entry -> entries[entry.signature().key] = entry }
    }

    private suspend fun persistLocked(flags: String) {
        var list = entries.values.toList()
        if (list.size > MAX_CACHE_ENTRIES) {
            // Держим самые свежие записи; остальные просто перечитаются при следующем скане.
            list = list.sortedByDescending { it.cachedAt }.take(MAX_CACHE_ENTRIES)
            entries.clear()
            list.forEach { entries[it.signature().key] = it }
        }
        JsonFile.write(
            context,
            SCAN_CACHE_FILE,
            ScanCacheFile.serializer(),
            ScanCacheFile(
                version = SCAN_CACHE_VERSION,
                flags = flags,
                entries = list.map { it.trimmed() },
            ),
        )
    }

    private fun ScanCacheEntry.trimmed(): ScanCacheEntry {
        var current = track
        if ((current.lyrics?.length ?: 0) > MAX_CACHED_LYRICS_CHARS) {
            current = current.copy(lyrics = null)
        }
        if ((current.syncedLyrics?.length ?: 0) > MAX_CACHED_LYRICS_CHARS) {
            current = current.copy(syncedLyrics = null)
        }
        return if (current === track) this else copy(track = current)
    }
}
