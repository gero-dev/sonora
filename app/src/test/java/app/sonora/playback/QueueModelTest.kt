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

    // ---------------- пересборка очереди при смене режима стриминга ----------------

    /** Онлайн-трек: всё, что не файл с устройства. */
    private fun online(id: String) = track(id)

    private fun local(id: String) = track(id).copy(uri = "file:///music/$id.mp3")

    private fun onlyLocal(t: Track) = t.uri?.startsWith("file://") == true

    /**
     * Главный сценарий жалобы: очередь собрана в офлайн-режиме из одних файлов,
     * затем стриминг включают. Онлайн-треки обязаны появиться в очереди, иначе
     * поток остаётся без онлайн-музыки до ручного перезапуска волны.
     */
    @Test
    fun `enabling streaming appends online candidates`() {
        val queue = listOf(local("a"), local("b"), local("c"))
        val plan = QueueModel.rebuildPlan(
            queue = queue,
            currentIndex = 0,
            candidates = listOf(online("x"), online("y")),
            keep = { true },
        )

        assertTrue("нечего удалять: офлайн-треки допустимы и при стриминге", plan.removeIndices.isEmpty())
        assertEquals(listOf("x", "y"), plan.append.map { it.id })
        assertTrue(plan.changed)
    }

    /**
     * Обратная сторона: при выключении стриминга онлайн-треки уходят из очереди.
     */
    @Test
    fun `disabling streaming removes online tracks`() {
        val queue = listOf(local("a"), online("x"), local("b"), online("y"))
        val plan = QueueModel.rebuildPlan(
            queue = queue,
            currentIndex = 0,
            candidates = emptyList(),
            keep = ::onlyLocal,
        )

        assertEquals("индексы идут с конца, чтобы удаление не сдвигало оставшиеся", listOf(3, 1), plan.removeIndices)
        assertTrue(plan.append.isEmpty())
    }

    /**
     * Текущий трек не удаляем, даже если он не подходит под фильтр: оборвать
     * звучащую музыку из-за смены настройки нельзя. Очередь вокруг него при
     * этом чистится, и об этом сообщает [QueueModel.QueueRebuildPlan.keptCurrent].
     */
    @Test
    fun `current track survives the filter but is reported`() {
        val queue = listOf(online("cur"), online("x"), local("b"))
        val plan = QueueModel.rebuildPlan(
            queue = queue,
            currentIndex = 0,
            candidates = emptyList(),
            keep = ::onlyLocal,
        )

        assertTrue("текущий индекс не должен попасть в удаление", 0 !in plan.removeIndices)
        assertEquals(listOf(1), plan.removeIndices)
        assertTrue("о доигрывающем текущем треке нужно сообщить", plan.keptCurrent)
    }

    /** Текущий трек подходит — тревожить пользователя незачем. */
    @Test
    fun `keptCurrent is false when current fits the filter`() {
        val plan = QueueModel.rebuildPlan(
            queue = listOf(local("cur"), online("x")),
            currentIndex = 0,
            candidates = emptyList(),
            keep = ::onlyLocal,
        )

        assertEquals(listOf(1), plan.removeIndices)
        assertTrue(!plan.keptCurrent)
    }

    /**
     * Дополнение не должно дублировать то, что уже стоит в очереди: иначе один
     * и тот же трек зазвучит дважды подряд.
     */
    @Test
    fun `append skips tracks already queued`() {
        val queue = listOf(local("a"), online("x"))
        val plan = QueueModel.rebuildPlan(
            queue = queue,
            currentIndex = 0,
            candidates = listOf(online("x"), online("y")),
            keep = { true },
        )

        assertEquals(listOf("y"), plan.append.map { it.id })
    }

    /** Дубликаты внутри самих кандидатов тоже схлопываются. */
    @Test
    fun `append deduplicates candidates`() {
        val plan = QueueModel.rebuildPlan(
            queue = listOf(local("a")),
            currentIndex = 0,
            candidates = listOf(online("x"), online("x"), online("y")),
            keep = { true },
        )

        assertEquals(listOf("x", "y"), plan.append.map { it.id })
    }

    /** Неподходящие кандидаты не дописываются: фильтр действует и на них. */
    @Test
    fun `append respects the filter`() {
        val plan = QueueModel.rebuildPlan(
            queue = listOf(local("a")),
            currentIndex = 0,
            candidates = listOf(online("x"), local("b")),
            keep = ::onlyLocal,
        )

        assertEquals(listOf("b"), plan.append.map { it.id })
    }

    /**
     * Ничего не изменилось — пересборка не должна считаться изменением, иначе
     * интерфейс будет дёргаться и писать в лог на каждое переключение.
     */
    @Test
    fun `unchanged queue reports no change`() {
        val plan = QueueModel.rebuildPlan(
            queue = listOf(local("a"), local("b")),
            currentIndex = 0,
            candidates = emptyList(),
            keep = ::onlyLocal,
        )

        assertTrue("удалять нечего", plan.removeIndices.isEmpty())
        assertTrue("дописывать нечего", plan.append.isEmpty())
        assertTrue("текущий подходит", !plan.keptCurrent)
        assertTrue("изменений нет", !plan.changed)
    }

    /** Пустая очередь — вырожденный случай: план не должен падать. */
    @Test
    fun `empty queue produces empty plan`() {
        val plan = QueueModel.rebuildPlan(
            queue = emptyList(),
            currentIndex = QueueModel.NONE,
            candidates = listOf(online("x")),
            keep = { true },
        )

        assertTrue(plan.removeIndices.isEmpty())
        assertEquals(listOf("x"), plan.append.map { it.id })
        assertTrue(!plan.keptCurrent)
    }

    /**
     * Индексы удаления обязаны идти по убыванию: вызывающий удаляет их по
     * очереди через removeMediaItem, и при возрастающем порядке второй индекс
     * указывал бы уже не на тот элемент.
     */
    @Test
    fun `remove indices are strictly descending`() {
        // Чётные — файлы, нечётные — онлайн. Текущий (0) подходит под фильтр.
        val queue = List(6) { if (it % 2 == 0) local("t$it") else online("t$it") }
        val plan = QueueModel.rebuildPlan(
            queue = queue,
            currentIndex = 0,
            candidates = emptyList(),
            keep = ::onlyLocal,
        )

        assertEquals(listOf(5, 3, 1), plan.removeIndices)
        assertEquals(
            "порядок обязан убывать",
            plan.removeIndices.sortedDescending(),
            plan.removeIndices,
        )
        assertTrue("текущий подходит, тревожить незачем", !plan.keptCurrent)
    }
}
