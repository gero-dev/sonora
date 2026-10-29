package app.sonora.data.wave

import android.content.Context
import app.sonora.core.model.Track
import app.sonora.core.util.JsonFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Постоянная история прослушиваний/пропусков для «Моей волны».
 *
 * Хранит последние [MAX_ENTRIES] записей в `filesDir/play_history.json` через
 * [JsonFile]. Запись никогда не бросает исключений: при сбое диска история
 * продолжает жить в памяти до перезапуска приложения.
 */
class PlayHistoryStore(context: Context) {

    private val appContext = context.applicationContext
    private val serializer = ListSerializer(WaveHistoryEntry.serializer())
    private val dislikedSerializer = ListSerializer(String.serializer())
    private val mutex = Mutex()

    private val _entries = MutableStateFlow<List<WaveHistoryEntry>>(emptyList())
    val entries: StateFlow<List<WaveHistoryEntry>> = _entries.asStateFlow()

    private val _size = MutableStateFlow(0)
    val size: StateFlow<Int> = _size.asStateFlow()

    /** Треки, отмеченные «не интересно» (ключи `t:<исполнитель|название>`). */
    private val _disliked = MutableStateFlow<Set<String>>(emptySet())
    val disliked: StateFlow<Set<String>> = _disliked.asStateFlow()

    @Volatile
    private var loaded = false

    /** Прочитать историю с диска (вызывается при инициализации приложения). */
    suspend fun load() {
        runCatching {
            mutex.withLock {
                readLocked()
                readDislikedLocked()
                loaded = true
            }
        }
    }

    /**
     * Записать прослушивание ([completed] = true) или пропуск (false).
     * Повторный вызов для того же трека подряд обновляет последнюю запись,
     * а не создаёт вторую.
     */
    suspend fun record(
        track: Track,
        completed: Boolean,
        atMs: Long = System.currentTimeMillis(),
        listenedMs: Long = 0L,
        durationMs: Long = track.durationMs,
    ) {
        runCatching {
            ensureLoaded()
            mutex.withLock {
                val entry = WaveHistoryEntry(
                    trackId = track.id,
                    artist = track.artist.trim(),
                    title = track.title.trim(),
                    playedAtMs = atMs,
                    completed = completed,
                    listenedMs = listenedMs.coerceAtLeast(0L),
                    durationMs = durationMs.coerceAtLeast(0L),
                    source = track.source.name,
                )
                val current = _entries.value
                val next = if (current.lastOrNull()?.trackId == entry.trackId) {
                    current.dropLast(1) + entry
                } else {
                    current + entry
                }
                val limited = if (next.size > MAX_ENTRIES) next.takeLast(MAX_ENTRIES) else next
                _entries.value = limited
                _size.value = limited.size
                JsonFile.write(appContext, FILE_NAME, serializer, limited)
            }
        }
    }

    /** Вес артистов по истории: свежие дослушивания плюс, пропуски минус. */
    fun affinity(nowMs: Long = System.currentTimeMillis()): Map<String, Double> =
        WaveEngine.artistAffinity(_entries.value, nowMs)

    /** «Не интересно»: трек больше не попадает в волну. */
    suspend fun dislike(track: Track) {
        runCatching {
            ensureLoaded()
            mutex.withLock {
                val next = _disliked.value + WaveEngine.dislikeKey(track)
                _disliked.value = next
                persistDislikedLocked(next)
            }
        }
    }

    /** Вернуть трек в рекомендации. */
    suspend fun undislike(track: Track) {
        runCatching {
            ensureLoaded()
            mutex.withLock {
                val next = _disliked.value - WaveEngine.dislikeKey(track)
                _disliked.value = next
                persistDislikedLocked(next)
            }
        }
    }

    suspend fun clearDislikes() {
        runCatching {
            ensureLoaded()
            mutex.withLock {
                _disliked.value = emptySet()
                persistDislikedLocked(emptySet())
            }
        }
    }

    /** Полностью очистить историю — и в памяти, и в файле. */
    suspend fun clear() {
        runCatching {
            mutex.withLock {
                _entries.value = emptyList()
                _size.value = 0
                loaded = true
                JsonFile.write(appContext, FILE_NAME, serializer, emptyList())
            }
        }
    }

    // ---------------------------------------------------------------- внутреннее

    /** Ленивая загрузка: record/clear до явного load() не затирают файл на диске. */
    private suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            readLocked()
            readDislikedLocked()
            loaded = true
        }
    }

    /** Чтение истории; вызывать только под [mutex]. */
    private suspend fun readLocked() {
        val stored = JsonFile.read(appContext, FILE_NAME, serializer, emptyList())
        val limited = if (stored.size > MAX_ENTRIES) stored.takeLast(MAX_ENTRIES) else stored
        _entries.value = limited
        _size.value = limited.size
    }

    private suspend fun readDislikedLocked() {
        val stored = JsonFile.read(
            appContext,
            FILE_DISLIKED,
            dislikedSerializer,
            emptyList(),
        )
        _disliked.value = stored.toSet()
    }

    private suspend fun persistDislikedLocked(keys: Set<String>) {
        JsonFile.write(appContext, FILE_DISLIKED, dislikedSerializer, keys.toList())
    }

    private companion object {
        const val FILE_NAME = "play_history.json"
        const val FILE_DISLIKED = "wave_disliked.json"
        /** История для экрана «История» и статистики; файл остаётся небольшим. */
        const val MAX_ENTRIES = 2000
    }
}
