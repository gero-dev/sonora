package app.sonora.data.import

import app.sonora.core.model.Source
import app.sonora.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты кэша найденных онлайн-треков. Смысл индекса — не искать одну и ту же
 * песню дважды в пределах импорта, поэтому проверяются и словесный поиск, и
 * защита от дублей, и поведение на пустом запросе.
 */
class RemoteTrackIndexTest {

    private fun online(id: String, title: String, artist: String, album: String? = null) = Track(
        id = "youtube:$id",
        title = title,
        artist = artist,
        album = album,
        uri = "innertube:$id",
        source = Source.YOUTUBE,
        playable = true,
    )

    private fun local(id: String, title: String, artist: String) = Track(
        id = id,
        title = title,
        artist = artist,
        uri = "file:///music/$id.m4a",
        source = Source.LOCAL,
        playable = true,
    )

    @Test
    fun startsEmpty() {
        val index = RemoteTrackIndex()
        assertEquals(0, index.size)
        assertEquals(emptyList<Track>(), index.lookup("anything"))
    }

    @Test
    fun addIsAdditive() {
        val index = RemoteTrackIndex()
        index.add(listOf(online("a", "Bohemian Rhapsody", "Queen")))
        assertEquals(1, index.size)
        index.add(listOf(online("b", "Voyager", "Xdinary Heroes")))
        assertEquals(2, index.size)
    }

    @Test
    fun duplicateIdsAreIgnored() {
        val index = RemoteTrackIndex()
        val track = online("a", "Bohemian Rhapsody", "Queen")
        index.add(listOf(track))
        index.add(listOf(track))
        index.add(listOf(online("a", "Different Title", "Different Artist")))
        assertEquals(1, index.size)
        // Побеждает первое добавление: id уже занят.
        assertEquals("Bohemian Rhapsody", index.lookup("Bohemian").single().title)
    }

    @Test
    fun localTracksAreNotStored() {
        // Локальный трек не является «найденным онлайн» — его не нужно кэшировать.
        val index = RemoteTrackIndex()
        index.add(listOf(local("l1", "Bohemian Rhapsody", "Queen")))
        assertEquals(0, index.size)
    }

    @Test
    fun mixedInputKeepsOnlyOnlineTracks() {
        val index = RemoteTrackIndex()
        index.add(
            listOf(
                local("l1", "Local Song", "Someone"),
                online("a", "Bohemian Rhapsody", "Queen"),
                local("l2", "Another Local", "Someone"),
            ),
        )
        assertEquals(1, index.size)
    }

    @Test
    fun lookupMatchesAnyWordOfTheQuery() {
        val index = RemoteTrackIndex()
        index.add(
            listOf(
                online("a", "Smells Like Teen Spirit", "Nirvana"),
                online("b", "Voyager", "Xdinary Heroes"),
            ),
        )
        // Запрос в форме «артист + название»: достаточно совпадения по словам.
        assertEquals(1, index.lookup("Nirvana Smells Like Teen Spirit").size)
        assertEquals("youtube:a", index.lookup("Nirvana Smells Like Teen Spirit").single().id)
    }

    @Test
    fun lookupFindsTrackViaArtistOnly() {
        val index = RemoteTrackIndex()
        index.add(listOf(online("a", "Bohemian Rhapsody", "Queen")))
        assertEquals("youtube:a", index.lookup("Queen").single().id)
    }

    @Test
    fun lookupFindsTrackViaAlbum() {
        val index = RemoteTrackIndex()
        index.add(listOf(online("a", "Voyager", "Xdinary Heroes", album = "Troubleshooting")))
        assertEquals("youtube:a", index.lookup("Troubleshooting").single().id)
    }

    @Test
    fun lookupReturnsEmptyWhenNothingMatches() {
        val index = RemoteTrackIndex()
        index.add(listOf(online("a", "Bohemian Rhapsody", "Queen")))
        assertEquals(emptyList<Track>(), index.lookup("Thriller Michael Jackson"))
    }

    @Test
    fun emptyQueryReturnsEverything() {
        // Пустой запрос — это «дай всех кандидатов»: матчер сам выберет лучшего.
        val index = RemoteTrackIndex()
        index.add(
            listOf(
                online("a", "Bohemian Rhapsody", "Queen"),
                online("b", "Voyager", "Xdinary Heroes"),
            ),
        )
        assertEquals(2, index.lookup("").size)
        assertEquals(2, index.lookup("   ").size)
    }

    @Test
    fun queryWithoutUsableTokensReturnsEverything() {
        // Слова короче 3 символов отбрасываются; «я и он» не сужает выборку.
        val index = RemoteTrackIndex()
        index.add(
            listOf(
                online("a", "Bohemian Rhapsody", "Queen"),
                online("b", "Voyager", "Xdinary Heroes"),
            ),
        )
        assertEquals(2, index.lookup("я и он").size)
    }

    @Test
    fun shortTokensDoNotCauseFalseHits() {
        val index = RemoteTrackIndex()
        index.add(listOf(online("a", "Bohemian Rhapsody", "Queen")))
        // «the» короче порога — по нему ничего не находится.
        assertEquals(emptyList<Track>(), index.lookup("the"))
    }

    @Test
    fun lookupIsCaseInsensitiveAndFoldsYo() {
        val index = RemoteTrackIndex()
        index.add(listOf(online("a", "Ларёк", "ДДТ")))
        assertEquals("youtube:a", index.lookup("Ларек").single().id)
        assertEquals("youtube:a", index.lookup("ларек").single().id)
        assertEquals("youtube:a", index.lookup("ДДТ").single().id)
    }

    @Test
    fun lookupResultsKeepInsertOrderAndAreDeduplicated() {
        val index = RemoteTrackIndex()
        index.add(
            listOf(
                online("a", "Queen Bohemian Rhapsody", "Queen"),
                online("b", "Queen Another One Bites the Dust", "Queen"),
            ),
        )
        // Оба трека совпали по «Queen», но каждый должен вернуться один раз.
        val hits = index.lookup("Queen")
        assertEquals(listOf("youtube:a", "youtube:b"), hits.map { it.id })
    }

    @Test
    fun clearEmptiesTheIndex() {
        val index = RemoteTrackIndex()
        index.add(
            listOf(
                online("a", "Bohemian Rhapsody", "Queen"),
                online("b", "Voyager", "Xdinary Heroes"),
            ),
        )
        assertEquals(2, index.size)
        index.clear()
        assertEquals(0, index.size)
        assertEquals(emptyList<Track>(), index.lookup("Queen"))
        // После очистки индекс снова принимает треки.
        index.add(listOf(online("a", "Bohemian Rhapsody", "Queen")))
        assertEquals(1, index.size)
    }

    @Test
    fun repeatedLookupIsStable() {
        val index = RemoteTrackIndex()
        index.add(listOf(online("a", "Bohemian Rhapsody", "Queen")))
        val first = index.lookup("Queen")
        val second = index.lookup("Queen")
        assertEquals(first.map { it.id }, second.map { it.id })
        assertTrue(first.isNotEmpty())
    }
}
