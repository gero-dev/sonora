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
 *  1. «Запоминание последнего трека»: при запуске приложения очередь, её
 *     секции (пользовательская очередь / контекст) и позиция восстанавливаются
 *     (на паузе), мини-плеер сразу показывает трек;
 *  2. виджет рисует актуальный трек/статус, даже когда приложение не запущено;
 *  3. кнопка Play на виджете может запустить музыку без открытия приложения —
 *     `PlaybackService` читает этот снимок.
 *
 * ВАЖНО: очередь и индекс пишет только `PlaybackController` (единственный
 * владелец очереди). `PlaybackService` обновляет лишь позицию/play-pause и
 * только если его медиаэлементы всё ещё совпадают со снимком по mediaId.
 * Раньше оба писали очередь независимо, из-за чего снимок мог сохранить
 * чужой индекс — после перезапуска играл не тот трек.
 */
@Serializable
data class PlaybackSnapshot(
    /** Версия формата. Файлы без поля считаются legacy-версией 1. */
    val version: Int = LEGACY_VERSION,
    /** Очередь в фактическом порядке проигрывания (включая уже проигранное). */
    val queue: List<Track> = emptyList(),
    /** Секции очереди по индексам: `USER` / `CONTEXT` (пусто = legacy, всё CONTEXT). */
    val queueSections: List<String> = emptyList(),
    /** Подпись контекста для «Далее из: …». */
    val contextTitle: String? = null,
    /** mediaId контекстных треков в исходном (не перемешанном) порядке. */
    val contextOrderIds: List<String> = emptyList(),
    val index: Int = 0,
    val positionMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeatMode: Int = 0,
    val isPlaying: Boolean = false,
    val updatedAtMs: Long = 0L,
) {
    val current: Track? get() = queue.getOrNull(index)

    /** Секция элемента очереди; старые снимки трактуем как единый контекст. */
    fun sectionAt(position: Int): QueueSection =
        if (queueSections.getOrNull(position) == QueueSection.USER.name) {
            QueueSection.USER
        } else {
            QueueSection.CONTEXT
        }

    companion object {
        /** Актуальная версия формата снимка. */
        const val CURRENT_VERSION = 2

        /** Версия файлов, записанных Sonora ≤ 3.4.0 (без поля version). */
        const val LEGACY_VERSION = 1
    }
}

/**
 * Синхронное JSON-хранилище снимка (файл маленький, пишем редко).
 *
 * Все операции синхронизированы: `PlaybackController` (очередь/индекс) и
 * `PlaybackService` (позиция/статус) работают в одном процессе и не должны
 * перетирать друг друга частичными записями.
 */
object PlaybackSnapshotStore {

    private const val FILE_NAME = "playback_state.json"
    private const val TAG = "SonoraPlayback"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val lock = Any()

    @Volatile
    private var cached: PlaybackSnapshot? = null

    fun load(context: Context): PlaybackSnapshot = synchronized(lock) { loadLocked(context) }

    private fun loadLocked(context: Context): PlaybackSnapshot {
        // cached != null означает, что файл уже читали/писали в этом процессе.
        cached?.let { return it }
        val raw = runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) null else {
                json.decodeFromString(PlaybackSnapshot.serializer(), file.readText())
            }
        }.getOrNull()
        val normalized = when {
            raw == null -> PlaybackSnapshot()
            raw.version <= PlaybackSnapshot.LEGACY_VERSION -> {
                // Старый формат: одна очередь без секций, пользовательской очереди нет.
                android.util.Log.i(TAG, "snapshot migrate v=${raw.version} -> v=${PlaybackSnapshot.CURRENT_VERSION}")
                raw.copy(version = PlaybackSnapshot.CURRENT_VERSION)
            }
            raw.version > PlaybackSnapshot.CURRENT_VERSION -> {
                // Снимок из более новой версии приложения: молча не читаем.
                android.util.Log.w(TAG, "snapshot v=${raw.version} is newer; ignored")
                PlaybackSnapshot()
            }
            else -> raw
        }
        cached = normalized
        return normalized
    }

    fun save(context: Context, snapshot: PlaybackSnapshot) {
        if (snapshot.queue.isEmpty()) {
            clear(context)
            return
        }
        synchronized(lock) { saveLocked(context, snapshot) }
    }

    private fun saveLocked(context: Context, snapshot: PlaybackSnapshot) {
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
        synchronized(lock) { clearLocked(context) }
    }

    private fun clearLocked(context: Context) {
        cached = PlaybackSnapshot()
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }

    /**
     * Точечное обновление позиции/статуса от `PlaybackService`.
     *
     * Очередь и индекс НЕ меняются. Запись выполняется только если плеер сервиса
     * всё ещё играет ровно ту очередь, что записана в снимке ([itemCount]) и
     * текущий элемент совпадает по [currentMediaId] — иначе владелец очереди
     * (`PlaybackController`) перезапишет снимок сам, а частичная запись лишь
     * испортила бы индекс.
     */
    fun updatePlayback(
        context: Context,
        itemCount: Int,
        index: Int,
        currentMediaId: String?,
        positionMs: Long,
        isPlaying: Boolean,
        repeatMode: Int,
    ): Boolean = synchronized(lock) {
        val snapshot = loadLocked(context)
        if (snapshot.queue.isEmpty()) return@synchronized false
        if (snapshot.queue.size != itemCount) return@synchronized false
        if (index !in snapshot.queue.indices) return@synchronized false
        if (currentMediaId != null && snapshot.queue[index].id != currentMediaId) {
            return@synchronized false
        }
        saveLocked(
            context,
            snapshot.copy(
                index = index,
                positionMs = positionMs,
                isPlaying = isPlaying,
                repeatMode = repeatMode,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
        return@synchronized true
    }

    /** Снимок «на лету» без чтения файла — для UI/виджета. */
    fun cachedOrNull(): PlaybackSnapshot? = synchronized(lock) { cached }
}
