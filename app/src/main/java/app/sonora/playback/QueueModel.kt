package app.sonora.playback

import app.sonora.core.model.Track
import kotlin.random.Random

/**
 * Модель очереди «как в Spotify».
 *
 * Очередь — это единый список проигрывания, который реально стоит в плеере.
 * Каждый элемент помечен секцией:
 *  - [QueueSection.USER] — «Далее в очереди»: треки, добавленные вручную
 *    («Играть следующим» / «В очередь»);
 *  - [QueueSection.CONTEXT] — «Далее из: <контекст>»: альбом, плейлист, волна.
 *
 * Инвариант порядка после текущего трека: сначала USER, потом CONTEXT.
 * Благодаря этому «Играть следующим» всегда вставляет трек непосредственно
 * после текущего, а «В очередь» — после других добавленных вручную треков,
 * но всё ещё до контекста.
 *
 * Файл намеренно не зависит от Android/Media3: вся арифметика индексов
 * покрыта юнит-тестами без эмулятора (см. QueueModelTest).
 */
enum class QueueSection { USER, CONTEXT }

/** Один элемент очереди. [uid] уникален для каждого вхождения: треки могут повторяться. */
data class QueueEntry(
    val trackId: String,
    val section: QueueSection,
    val uid: Long,
)

/** Перемещение элемента очереди, соответствующее `Player.moveMediaItem(from, to)`. */
data class QueueMove(val from: Int, val to: Int)

/** Что делать при подключении контроллера к уже существующей сессии. */
enum class RestorePlan {
    /** Плеер жив и в нём есть очередь: источник истины — он, снимок не применяем. */
    USE_PLAYER,

    /** Плеер пуст, но снимок есть: восстанавливаем очередь из файла. */
    LOAD_SNAPSHOT,

    /** Нечего восстанавливать. */
    NONE,
}

object QueueModel {

    /** «Нет такого индекса» (как Player.INDEX_UNSET, но без зависимости от Media3). */
    const val NONE = -1

    // Значения совпадают с androidx.media3.common.Player.REPEAT_MODE_*.
    const val REPEAT_OFF = 0
    const val REPEAT_ONE = 1
    const val REPEAT_ALL = 2

    /**
     * Следующий индекс для ручного «вперёд».
     *
     * REPEAT_ONE намеренно не блокирует ручное листание: как в Spotify, кнопка
     * «вперёд» переходит на реальный следующий трек, а повтор текущего делает
     * только авто-переход в конце трека. В конце очереди возвращает [NONE]
     * (кроме REPEAT_ALL, где очередь зациклена).
     */
    fun nextIndex(currentIndex: Int, itemCount: Int, repeatMode: Int): Int {
        if (itemCount <= 0) return NONE
        if (currentIndex !in 0 until itemCount) return 0
        if (currentIndex < itemCount - 1) return currentIndex + 1
        return if (repeatMode == REPEAT_ALL) 0 else NONE
    }

    /**
     * Предыдущий индекс для ручного «назад». В начале очереди возвращает 0
     * (перезапуск текущего трека) либо последний индекс при REPEAT_ALL.
     */
    fun previousIndex(currentIndex: Int, itemCount: Int, repeatMode: Int): Int {
        if (itemCount <= 0) return NONE
        if (currentIndex !in 0 until itemCount) return 0
        if (currentIndex > 0) return currentIndex - 1
        return if (repeatMode == REPEAT_ALL) itemCount - 1 else 0
    }

    /** Позиция вставки «Играть следующим»: сразу после текущего. */
    fun playNextInsertIndex(currentIndex: Int, entries: List<QueueEntry>): Int =
        (currentIndex + 1).coerceIn(0, entries.size)

    /** Позиция вставки «В очередь»: после последнего добавленного вручную трека. */
    fun addToQueueInsertIndex(currentIndex: Int, entries: List<QueueEntry>): Int {
        var at = playNextInsertIndex(currentIndex, entries)
        while (at < entries.size && entries[at].section == QueueSection.USER) at++
        return at
    }

    /** Индекс первого контекстного элемента после текущего («Далее из: …»). */
    fun contextStartIndex(currentIndex: Int, entries: List<QueueEntry>): Int {
        for (index in (currentIndex + 1) until entries.size) {
            if (entries[index].section == QueueSection.CONTEXT) return index
        }
        return entries.size
    }

    /** Глобальные индексы треков «Далее в очереди» (добавленных вручную). */
    fun userIndicesAfter(currentIndex: Int, entries: List<QueueEntry>): List<Int> =
        (currentIndex + 1 until entries.size)
            .filter { entries[it].section == QueueSection.USER }

    /** Глобальные индексы треков «Далее из: <контекст>». */
    fun contextIndicesAfter(currentIndex: Int, entries: List<QueueEntry>): List<Int> =
        (currentIndex + 1 until entries.size)
            .filter { entries[it].section == QueueSection.CONTEXT }

    /**
     * Контекстный хвост в случайном порядке. Проигранное, текущий трек и
     * пользовательская очередь не меняются — это важно для корректного
     * «назад» и для того, чтобы «Играть следующим» срабатывал и при shuffle.
     */
    fun shuffledUpcoming(
        currentIndex: Int,
        entries: List<QueueEntry>,
        random: Random,
    ): List<QueueEntry> {
        val start = contextStartIndex(currentIndex, entries)
        if (start >= entries.size) return entries
        return entries.subList(0, start) + entries.subList(start, entries.size).shuffled(random)
    }

    /**
     * Исходный порядок контекстного хвоста (когда пользователь выключает
     * перемешивание). [originalOrder] — uid контекстных элементов в исходном
     * порядке добавления.
     */
    fun unshuffledUpcoming(
        currentIndex: Int,
        entries: List<QueueEntry>,
        originalOrder: List<Long>,
    ): List<QueueEntry> {
        val start = contextStartIndex(currentIndex, entries)
        if (start >= entries.size) return entries
        val rank = HashMap<Long, Int>(originalOrder.size * 2)
        originalOrder.forEachIndexed { index, uid -> rank[uid] = index }
        val position = HashMap<Long, Int>(entries.size * 2)
        entries.forEachIndexed { index, entry -> position[entry.uid] = index }
        val tail = entries.subList(start, entries.size).sortedWith(
            compareBy<QueueEntry> { rank[it.uid] ?: Int.MAX_VALUE }
                .thenBy { position[it.uid] ?: Int.MAX_VALUE },
        )
        return entries.subList(0, start) + tail
    }

    /** UID контекстных элементов в исходном (не перемешанном) порядке. */
    fun originalContextUids(entries: List<QueueEntry>, originalOrder: List<Long>): List<Long> {
        val rank = HashMap<Long, Int>(originalOrder.size * 2)
        originalOrder.forEachIndexed { index, uid -> rank[uid] = index }
        return entries.filter { it.section == QueueSection.CONTEXT }
            .sortedWith(
                compareBy<QueueEntry>(
                    { rank[it.uid] ?: Int.MAX_VALUE },
                    { it.uid },
                ),
            )
            .map { it.uid }
    }

    /**
     * Сопоставляет сохранённый порядок контекста (mediaId) с элементами
     * очереди. Дубликаты треков различаются по вхождению.
     */
    fun matchOriginalOrder(entries: List<QueueEntry>, contextOrderIds: List<String>): List<Long> {
        val used = HashSet<Long>()
        val result = ArrayList<Long>(contextOrderIds.size)
        for (id in contextOrderIds) {
            val entry = entries.firstOrNull {
                it.section == QueueSection.CONTEXT && it.trackId == id && it.uid !in used
            } ?: continue
            used += entry.uid
            result += entry.uid
        }
        return result
    }

    /**
     * Перемещения `moveMediaItem(from, to)`, превращающие [current] в [target]
     * (сравнение по uid). [base] — глобальный индекс первого элемента, если
     * переставляется только хвост; для полного списка base = 0.
     */
    fun permutationMoves(base: Int, current: List<QueueEntry>, target: List<QueueEntry>): List<QueueMove> {
        require(current.size == target.size) { "permutation size mismatch" }
        val working = current.toMutableList()
        val moves = ArrayList<QueueMove>()
        for (offset in target.indices) {
            val to = base + offset
            val desiredUid = target[offset].uid
            var from = to
            while (from < working.size && working[from].uid != desiredUid) from++
            if (from >= working.size) continue
            if (from != to) {
                val item = working.removeAt(from)
                working.add(to, item)
                moves += QueueMove(from, to)
            }
        }
        return moves
    }

    /** Восстанавливает записи очереди из снимка (секции старого формата = CONTEXT). */
    fun entriesFromSnapshot(
        queue: List<Track>,
        sections: List<String>,
        startUid: Long = 1L,
    ): List<QueueEntry> = queue.mapIndexed { index, track ->
        val section = when (sections.getOrNull(index)) {
            QueueSection.USER.name -> QueueSection.USER
            else -> QueueSection.CONTEXT
        }
        QueueEntry(track.id, section, startUid + index)
    }

    /** Секции в формате снимка. */
    fun sectionsSnapshot(entries: List<QueueEntry>): List<String> = entries.map { it.section.name }

    /** Что делать при подключении контроллера: живую очередь никогда не перезаписываем. */
    fun restorePlan(snapshotSize: Int, playerItemCount: Int): RestorePlan = when {
        playerItemCount > 0 -> RestorePlan.USE_PLAYER
        snapshotSize > 0 -> RestorePlan.LOAD_SNAPSHOT
        else -> RestorePlan.NONE
    }

    /**
     * Подпись контекста для «Далее из: …». Точного названия альбома/плейлиста
     * на этом уровне нет, поэтому выводим из состава очереди.
     */
    fun inferContextTitle(tracks: List<Track>): String? {
        if (tracks.size <= 1) return null
        val albums = tracks.mapNotNull { it.album?.takeIf { album -> album.isNotBlank() } }
            .distinct()
        val artists = tracks.map { it.artist }
            .filter { it.isNotBlank() }
            .distinct()
        return when {
            albums.size == 1 -> "Альбом · ${albums.first()}"
            artists.size == 1 -> "Исполнитель · ${artists.first()}"
            else -> "Очередь · ${tracks.size} треков"
        }
    }
}
