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

/**
 * Постоянные оценки треков («нравится» / «не нравится») для рекомендаций.
 *
 * Хранит последние [MAX_ENTRIES] оценок в `filesDir/track_ratings.json` через
 * [JsonFile] (атомарная запись, IO-диспетчер). Ключ — стабильный
 * `исполнитель|название` плюс `trackId`, поэтому оценка переживает
 * переиндексацию файла и работает для стриминговых треков.
 *
 * Запись не бросает исключений: при сбое диска оценки продолжают жить
 * в памяти до перезапуска приложения, UI и рекомендации не падают.
 */
class TrackRatingStore(context: Context) {

    private val appContext = context.applicationContext
    private val serializer = ListSerializer(TrackRatingEntry.serializer())
    private val mutex = Mutex()

    private var entries: List<TrackRatingEntry> = emptyList()

    private val _index = MutableStateFlow(TrackRatingIndex.EMPTY)

    /** Готовый для ранжирования индекс; обновляется после каждой оценки. */
    val index: StateFlow<TrackRatingIndex> = _index.asStateFlow()

    @Volatile
    private var loaded = false

    /** Прочитать оценки с диска (вызывается один раз при старте приложения). */
    suspend fun load() {
        runCatching {
            mutex.withLock {
                readLocked()
                loaded = true
            }
        }
    }

    /** Оценка трека: +1 «нравится», -1 «не нравится», 0 — оценки нет. */
    fun ratingOf(track: Track): Int = _index.value.ratingOf(track)

    /**
     * Поставить оценку. Для трека сохраняется одна запись: повторная оценка
     * заменяет предыдущую, а 0 удаляет её.
     */
    suspend fun setRating(track: Track, rating: Int) {
        runCatching {
            ensureLoaded()
            val normalized = rating.coerceIn(-1, 1)
            mutex.withLock {
                val waveKey = WaveEngine.waveKey(track)
                val next = entries
                    .filterNot { entry ->
                        entry.trackId == track.id ||
                            WaveEngine.waveKeyOf(entry.artist, entry.title) == waveKey
                    } +
                    if (normalized == 0) {
                        emptyList<TrackRatingEntry>()
                    } else {
                        listOf(
                            TrackRatingEntry(
                                trackId = track.id,
                                artist = track.artist.trim(),
                                title = track.title.trim(),
                                rating = normalized,
                                ratedAtMs = System.currentTimeMillis(),
                                genre = track.extra["genre"] ?: track.extra["genres"],
                            ),
                        )
                    }
                val limited = if (next.size > MAX_ENTRIES) next.takeLast(MAX_ENTRIES) else next
                entries = limited
                _index.value = TrackRatingIndex.of(limited)
                persistLocked(limited)
            }
        }
    }

    /** Лайк/снятие лайка. Возвращает новую оценку (0 — оценки нет). */
    suspend fun toggleLike(track: Track): Int {
        val next = if (_index.value.isLiked(track)) 0 else 1
        setRating(track, next)
        return next
    }

    /** Дизлайк/снятие дизлайка. Возвращает новую оценку (0 — оценки нет). */
    suspend fun toggleDislike(track: Track): Int {
        val next = if (_index.value.isDisliked(track)) 0 else -1
        setRating(track, next)
        return next
    }

    /** Полностью очистить оценки — и в памяти, и в файле. */
    suspend fun clear() {
        runCatching {
            ensureLoaded()
            mutex.withLock {
                entries = emptyList()
                _index.value = TrackRatingIndex.EMPTY
                persistLocked(emptyList())
            }
        }
    }

    /** Убрать только дизлайки: «вернуть скрытые треки» в настройках волны. */
    suspend fun clearDislikes() {
        runCatching {
            ensureLoaded()
            mutex.withLock {
                val next = entries.filter { it.rating >= 0 }
                entries = next
                _index.value = TrackRatingIndex.of(next)
                persistLocked(next)
            }
        }
    }

    // ---------------------------------------------------------------- внутреннее

    /** Ленивая загрузка: оценки до явного load() не затирают файл на диске. */
    private suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            readLocked()
            loaded = true
        }
    }

    /** Чтение оценок; вызывать только под [mutex]. */
    private suspend fun readLocked() {
        val stored = JsonFile.read(appContext, FILE_NAME, serializer, emptyList())
        val limited = if (stored.size > MAX_ENTRIES) stored.takeLast(MAX_ENTRIES) else stored
        entries = limited
        _index.value = TrackRatingIndex.of(limited)
    }

    private suspend fun persistLocked(values: List<TrackRatingEntry>) {
        JsonFile.write(appContext, FILE_NAME, serializer, values)
    }

    private companion object {
        const val FILE_NAME = "track_ratings.json"

        /** Оценок не может быть больше, чем разумно хранить в JSON. */
        const val MAX_ENTRIES = 4000
    }
}
