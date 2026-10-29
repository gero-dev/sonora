package app.sonora.playback

import android.content.Context
import app.sonora.core.model.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Последнее состояние проигрывания, которое переживает смерть процесса.
 *
 * Нужно для трёх вещей:
 *  1. «Запоминание последнего трека»: при запуске приложения очередь и позиция
 *     восстанавливаются (на паузе), мини-плеер сразу показывает трек;
 *  2. виджет рисует актуальный трек/статус, даже когда приложение не запущено;
 *  3. кнопка Play на виджете может запустить музыку без открытия приложения —
 *     `PlaybackService` читает этот снимок.
 */
@Serializable
data class PlaybackSnapshot(
    val queue: List<Track> = emptyList(),
    val index: Int = 0,
    val positionMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeatMode: Int = 0,
    val isPlaying: Boolean = false,
    val updatedAtMs: Long = 0L,
) {
    val current: Track? get() = queue.getOrNull(index)
}

/** Синхронное JSON-хранилище снимка (файл маленький, пишем редко). */
object PlaybackSnapshotStore {

    private const val FILE_NAME = "playback_state.json"
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var cached: PlaybackSnapshot? = null

    fun load(context: Context): PlaybackSnapshot {
        cached?.let { return it }
        val loaded = runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return PlaybackSnapshot()
            json.decodeFromString(PlaybackSnapshot.serializer(), file.readText())
        }.getOrDefault(PlaybackSnapshot())
        cached = loaded
        return loaded
    }

    fun save(context: Context, snapshot: PlaybackSnapshot) {
        if (snapshot.queue.isEmpty()) {
            clear(context)
            return
        }
        cached = snapshot
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(json.encodeToString(PlaybackSnapshot.serializer(), snapshot))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    fun clear(context: Context) {
        cached = PlaybackSnapshot()
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }

    /** Снимок «на лету» без чтения файла — для UI/виджета. */
    fun cachedOrNull(): PlaybackSnapshot? = cached
}
