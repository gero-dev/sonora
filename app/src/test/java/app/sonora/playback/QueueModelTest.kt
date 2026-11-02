package app.sonora.playback

import app.sonora.core.model.Track
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Юнит-тесты планировщика очереди (без Android): индексы «вперёд/назад»,
 * секции Spotify, shuffle и восстановление снимка.
 */
class QueueModelTest {

    private fun track(id: String, album: String? = null, artist: String = "Artist") =
        Track(id = id, title = id, artist = artist, album = album)

    private fun entry(id: String, section: QueueSection, uid: Long) =
        QueueEntry(trackId = id, section = section, uid = uid)

    // ---------------- next / previous ----------------

    @Test
    fun `next in repeat one goes to real neighbour`() {
        assertEquals(2, QueueModel.nextIndex(1, 3, QueueModel.REPEAT_ONE))
        assertEquals(1, QueueModel.nextIndex(0, 3, QueueModel.REPEAT_ONE))
        // В конце очереди ручной next не зацикливает текущий трек.
        assertEquals(QueueModel.NONE, QueueModel.nextIndex(2, 3, QueueModel.REPEAT_ONE))
    }

    @Test
    fun `previous in repeat one goes to real neighbour`() {
        assertEquals(1, QueueModel.previousIndex(2, 3, QueueModel.REPEAT_ONE))
        assertEquals(0, QueueModel.previousIndex(1, 3, QueueModel.REPEAT_ONE))
        // Первый трек — только перезапуск.
        assertEquals(0, QueueModel.previousIndex(0, 3, QueueModel.REPEAT_ONE))
    }

    @Test
    fun `repeat all wraps both directions`() {
        assertEquals(0, QueueModel.nextIndex(2, 3, QueueModel.REPEAT_ALL))
        assertEquals(2, QueueModel.previousIndex(0, 3, QueueModel.REPEAT_ALL))
    }

    @Test
    fun `next at end returns none until extension is appended`() {
        // Очередь из 3 треков, играет последний: next просит докупить пачку.
        assertEquals(QueueModel.NONE, QueueModel.nextIndex(2, 3, QueueModel.REPEAT_OFF))
        // После одной пачки (append в конец) next ведёт ровно на первый новый трек.
        assertEquals(3, QueueModel.nextIndex(2, 4, QueueModel.REPEAT_OFF))
        assertEquals(4, QueueModel.nextIndex(3, 5, QueueModel.REPEAT_OFF))
    }

    @Test
    fun `empty queue has no next or previous`() {
        assertEquals(QueueModel.NONE, QueueModel.nextIndex(0, 0, QueueModel.REPEAT_OFF))
        assertEquals(QueueModel.NONE, QueueModel.previousIndex(0, 0, QueueModel.REPEAT_OFF))
    }

    // ---------------- секции Spotify ----------------

    @Test
    fun `play next goes before other user tracks and before context`() {
        // played p0, current p1, user u1, context c1
        val entries = listOf(
            entry("p0", QueueSection.CONTEXT, 1),
            entry("p1", QueueSection.CONTEXT, 2),
            entry("u1", QueueSection.USER, 3),
            entry("c1", QueueSection.CONTEXT, 4),
        )
        val current = 1
        // «Играть следующим» — непосредственно после текущего.
        assertEquals(2, QueueModel.playNextInsertIndex(current, entries))
        // «В очередь» — после уже добавленного вручную u1, но до контекста.
        assertEquals(3, QueueModel.addToQueueInsertIndex(current, entries))
        assertEquals(listOf(2), QueueModel.userIndicesAfter(current, entries))
        assertEquals(listOf(3), QueueModel.contextIndicesAfter(current, entries))
        assertEquals(3, QueueModel.contextStartIndex(current, entries))
    }

    @Test
    fun `add to queue falls back to just after current when user queue is empty`() {
        val entries = listOf(
            entry("p0", QueueSection.CONTEXT, 1),
            entry("p1", QueueSection.CONTEXT, 2),
            entry("c1", QueueSection.CONTEXT, 3),
        )
        assertEquals(2, QueueModel.addToQueueInsertIndex(1, entries))
        assertEquals(2, QueueModel.playNextInsertIndex(1, entries))
    }

    // ---------------- shuffle ----------------

    @Test
    fun `shuffle reorders only context tail and keeps media ids`() {
        val entries = listOf(
            entry("p0", QueueSection.CONTEXT, 1),
            entry("p1", QueueSection.CONTEXT, 2),
            entry("u1", QueueSection.USER, 3),
            entry("c1", QueueSection.CONTEXT, 4),
            entry("c2", QueueSection.CONTEXT, 5),
            entry("c3", QueueSection.CONTEXT, 6),
        )
        val shuffled = QueueModel.shuffledUpcoming(1, entries, Random(42))
        assertEquals(entries.size, shuffled.size)
        // Проигранное, текущий и пользовательская очередь остаются на месте.
        assertEquals(listOf("p0", "p1", "u1"), shuffled.take(3).map { it.trackId })
        // Контекст — та же мультимножество mediaId, только в другом порядке.
        assertEquals(
            listOf("c1", "c2", "c3"),
            shuffled.drop(3).map { it.trackId }.sorted(),
        )
        assertEquals(
            entries.map { it.uid }.sorted(),
            shuffled.map { it.uid }.sorted(),
        )
    }

    @Test
    fun `shuffle off restores original context order`() {
        val entries = listOf(
            entry("p0", QueueSection.CONTEXT, 1),
            entry("p1", QueueSection.CONTEXT, 2),
            entry("u1", QueueSection.USER, 3),
            entry("c1", QueueSection.CONTEXT, 4),
            entry("c2", QueueSection.CONTEXT, 5),
            entry("c3", QueueSection.CONTEXT, 6),
        )
        val shuffled = QueueModel.shuffledUpcoming(1, entries, Random(7))
        val originalOrder = QueueModel.originalContextUids(entries, listOf(4L, 5L, 6L))
        val restored = QueueModel.unshuffledUpcoming(1, shuffled, originalOrder)
        assertEquals(entries.map { it.trackId }, restored.map { it.trackId })
    }

    @Test
    fun `permutation moves transform current order into target order`() {
        val current = listOf(
            entry("a", QueueSection.CONTEXT, 1),
            entry("b", QueueSection.CONTEXT, 2),
            entry("c", QueueSection.CONTEXT, 3),
            entry("d", QueueSection.CONTEXT, 4),
        )
        val target = listOf(current[2], current[0], current[3], current[1])
        val moves = QueueModel.permutationMoves(0, current, target)
        // Применяем перемещения так же, как это сделает Player.moveMediaItem.
        val working = current.toMutableList()
        moves.forEach { move ->
            val item = working.removeAt(move.from)
            working.add(move.to, item)
        }
        assertEquals(target.map { it.uid }, working.map { it.uid })
    }

    // ---------------- восстановление ----------------

    @Test
    fun `restore never overwrites a live player queue`() {
        // Живая очередь сервиса всегда побеждает, даже если размеры не совпали.
        assertEquals(RestorePlan.USE_PLAYER, QueueModel.restorePlan(snapshotSize = 5, playerItemCount = 3))
        assertEquals(RestorePlan.USE_PLAYER, QueueModel.restorePlan(snapshotSize = 0, playerItemCount = 2))
        // Пустой плеер — восстанавливаем снимок.
        assertEquals(RestorePlan.LOAD_SNAPSHOT, QueueModel.restorePlan(snapshotSize = 5, playerItemCount = 0))
        assertEquals(RestorePlan.NONE, QueueModel.restorePlan(snapshotSize = 0, playerItemCount = 0))
    }

    @Test
    fun `entries from legacy snapshot are all context`() {
        val queue = listOf(track("a"), track("b"))
        val entries = QueueModel.entriesFromSnapshot(queue, emptyList(), startUid = 10)
        assertEquals(listOf("a", "b"), entries.map { it.trackId })
        assertTrue(entries.all { it.section == QueueSection.CONTEXT })
        assertEquals(listOf(10L, 11L), entries.map { it.uid })
    }

    @Test
    fun `entries from snapshot keep user sections`() {
        val queue = listOf(track("a"), track("b"), track("c"))
        val entries = QueueModel.entriesFromSnapshot(
            queue,
            listOf("CONTEXT", "USER", "CONTEXT"),
            startUid = 1,
        )
        assertEquals(
            listOf(QueueSection.CONTEXT, QueueSection.USER, QueueSection.CONTEXT),
            entries.map { it.section },
        )
        assertEquals(
            listOf("CONTEXT", "USER", "CONTEXT"),
            QueueModel.sectionsSnapshot(entries),
        )
    }

    @Test
    fun `match original order handles duplicate tracks`() {
        val entries = listOf(
            entry("a", QueueSection.CONTEXT, 1),
            entry("b", QueueSection.CONTEXT, 2),
            entry("a", QueueSection.CONTEXT, 3),
        )
        // Исходный порядок: первый a, второй a, затем b.
        val matched = QueueModel.matchOriginalOrder(entries, listOf("a", "a", "b"))
        assertEquals(listOf(1L, 3L, 2L), matched)
    }

    // ---------------- подпись контекста ----------------

    @Test
    fun `context title is inferred from album or artist`() {
        assertEquals(
            "Альбом · Album X",
            QueueModel.inferContextTitle(listOf(track("a", album = "Album X"), track("b", album = "Album X"))),
        )
        assertEquals(
            "Исполнитель · One",
            QueueModel.inferContextTitle(
                listOf(
                    track("a", album = "A", artist = "One"),
                    track("b", album = "B", artist = "One"),
                ),
            ),
        )
        assertEquals(
            "Очередь · 2 треков",
            QueueModel.inferContextTitle(listOf(track("a", artist = "A"), track("b", artist = "B"))),
        )
        assertNull(QueueModel.inferContextTitle(listOf(track("a"))))
    }
}
