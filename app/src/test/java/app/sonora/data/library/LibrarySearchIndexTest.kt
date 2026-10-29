package app.sonora.data.library

import app.sonora.core.model.Source
import app.sonora.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySearchIndexTest {

    private fun local(
        id: String,
        title: String,
        artist: String = "Artist",
        album: String? = null,
    ): Track = Track(
        id = id,
        title = title,
        artist = artist,
        album = album,
        source = Source.LOCAL,
        uri = "file:///music/$id.mp3",
    )

    private fun indexOf(vararg tracks: Track): LibrarySearchIndex =
        LibrarySearchIndex.build(tracks.toList())

    @Test
    fun emptyQueryReturnsNothing() {
        val index = indexOf(local("1", "Nirvana"))
        assertTrue(index.search("").isEmpty())
        assertTrue(index.search("   ").isEmpty())
    }

    @Test
    fun ranksTitleThenArtistThenAlbum() {
        val titleExact = local("t1", "Nirvana", artist = "Other")
        val titlePrefix = local("t2", "Nirvana - Nevermind", artist = "Other")
        val artistExact = local("t3", "Song", artist = "Nirvana")
        val albumOnly = local("t4", "Song", artist = "Other", album = "Nirvana")
        val index = indexOf(titleExact, titlePrefix, artistExact, albumOnly)

        assertEquals(
            listOf("t1", "t2", "t3", "t4"),
            index.search("nirvana").map { it.id },
        )
    }

    @Test
    fun artistPrefixBeatsAlbumSubstring() {
        val artistPrefix = local("a", "Song", artist = "Nirvana Band")
        val albumSubstring = local("b", "Song", artist = "Other", album = "Best of Nirvana")
        val index = indexOf(artistPrefix, albumSubstring)

        assertEquals(listOf("a", "b"), index.search("nirvana").map { it.id })
    }

    @Test
    fun ordersEqualScoresByTitle() {
        val zebra = local("z", "Zebra Nirvana")
        val alpha = local("a", "Alpha Nirvana")
        val index = indexOf(zebra, alpha)

        // Оба — substring-совпадения (score 50): сортируем по названию.
        assertEquals(listOf("a", "z"), index.search("nirvana").map { it.id })
    }

    @Test
    fun findsPrefixAndSubstring() {
        val prefix = local("p", "Nevermind")
        val substring = local("s", "Smells Like Nirvana")
        val index = indexOf(prefix, substring)

        // "never" — начало title (score 80); "van" — подстрока (score 50).
        assertEquals(listOf("p"), index.search("never").map { it.id })
        assertEquals(listOf("s"), index.search("van").map { it.id })
    }

    @Test
    fun yoAndYeAreEquivalent() {
        val track = local("ru", "Черёмуха", artist = "Группа")
        val index = indexOf(track)

        assertEquals(listOf("ru"), index.search("черемуха").map { it.id })
        assertEquals(listOf("ru"), index.search("Черёмуха").map { it.id })
        assertEquals(listOf("ru"), index.search("ЧЕРЁМУХА").map { it.id })
    }

    @Test
    fun cyrillicQueryFindsTransliteratedLatinTrack() {
        val track = local("en", "Nirvana", artist = "Kurt")
        val index = indexOf(track)

        assertEquals(listOf("en"), index.search("нирвана").map { it.id })
    }

    @Test
    fun latinQueryFindsCyrillicTrackAsFallback() {
        val track = local("ru", "Нирвана", artist = "Группа")
        val index = indexOf(track)

        // Прямого совпадения нет, работает только резервная транслитерация.
        assertEquals(listOf("ru"), index.search("nirvana").map { it.id })
        assertEquals(listOf("ru"), index.search("группа").map { it.id })
    }

    @Test
    fun transliterationDoesNotReplaceDirectMatches() {
        val latin = local("en", "Nirvana")
        val cyrillic = local("ru", "Нирвана")
        val index = indexOf(latin, cyrillic)

        // Есть прямое совпадение — резервный транслит-поиск не подмешивается.
        assertEquals(listOf("en"), index.search("nirvana").map { it.id })
    }

    @Test
    fun multiWordQueryRequiresContiguousMatchLikeBefore() {
        val track = local("1", "Nirvana Song")
        val index = indexOf(track)

        assertEquals(listOf("1"), index.search("nirvana song").map { it.id })
        // Слова из запроса есть в библиотеке, но подстрокой целиком — нет.
        assertTrue(index.search("nirvana nevermind").isEmpty())
    }

    @Test
    fun excludesRemoteAndVideoTracks() {
        val local = local("l", "Nirvana")
        val remote = Track(
            id = "r",
            title = "Nirvana",
            artist = "Artist",
            source = Source.YOUTUBE,
        )
        val video = Track(
            id = "v",
            title = "Nirvana - Official Video",
            artist = "Artist",
            source = Source.LOCAL,
            extra = mapOf("hasVideo" to "1"),
        )
        val index = indexOf(local, remote, video)

        assertEquals(listOf("l"), index.search("nirvana").map { it.id })
    }

    @Test
    fun returnsOriginalTrackInstances() {
        val track = local("1", "Nirvana")
        val index = indexOf(track)

        assertTrue(index.search("nirvana").single() === track)
    }
}
