package app.sonora.data.import

import app.sonora.core.model.Source
import app.sonora.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Тесты индекса локальной библиотеки: точный ключ должен совпадать у строки
 * импорта и у файла на устройстве, несмотря на регистр, диакритику и украшения.
 */
class LibraryTrackIndexTest {

    private fun local(
        id: String,
        title: String,
        artist: String,
        playable: Boolean = true,
    ) = Track(
        id = id,
        title = title,
        artist = artist,
        uri = "file:///music/$id.m4a",
        source = Source.LOCAL,
        playable = playable,
    )

    private fun remote(id: String, title: String, artist: String) = Track(
        id = id,
        title = title,
        artist = artist,
        uri = "innertube:$id",
        source = Source.YOUTUBE,
        playable = true,
    )

    @Test
    fun exactFindsTrackByNormalizedKey() {
        val index = LibraryTrackIndex(
            listOf(
                local("a", "Bohemian Rhapsody", "Queen"),
                local("b", "Voyager", "Xdinary Heroes"),
            ),
        )
        assertEquals(2, index.size)
        assertEquals("a", index.exact("Bohemian Rhapsody", "Queen")?.id)
        assertEquals("b", index.exact("Voyager", "Xdinary Heroes")?.id)
    }

    @Test
    fun exactIsCaseAndDiacriticInsensitive() {
        val index = LibraryTrackIndex(listOf(local("a", "SARÀ PERCHÉ TI AMO", "Ricchi e Poveri")))
        assertEquals("a", index.exact("sara perche ti amo", "ricchi e poveri")?.id)
        assertEquals("a", index.exact("Sara Perche Ti Amo", "RICCHI E POVERI")?.id)
    }

    @Test
    fun exactFoldsCyrillicYo() {
        val index = LibraryTrackIndex(listOf(local("a", "Ларёк", "ДДТ")))
        assertEquals("a", index.exact("Ларек", "ДДТ")?.id)
        assertEquals("a", index.exact("Ларёк", "ДДТ")?.id)
    }

    @Test
    fun exactIgnoresCatalogueDecorations() {
        // Локальный файл мог быть скачан с YouTube и подписан украшением.
        val index = LibraryTrackIndex(
            listOf(local("a", "Billie Jean (Official Video)", "Michael Jackson")),
        )
        assertEquals("a", index.exact("Billie Jean", "Michael Jackson")?.id)
    }

    @Test
    fun exactReturnsNullForUnknownTrack() {
        val index = LibraryTrackIndex(listOf(local("a", "Bohemian Rhapsody", "Queen")))
        assertNull(index.exact("Thriller", "Michael Jackson"))
    }

    @Test
    fun exactReturnsNullWhenArtistIsBlank() {
        // Без исполнителя ключ неоднозначен: совпадение названия ничего не значит.
        val index = LibraryTrackIndex(listOf(local("a", "Bohemian Rhapsody", "Queen")))
        assertNull(index.exact("Bohemian Rhapsody", null))
        assertNull(index.exact("Bohemian Rhapsody", "   "))
    }

    @Test
    fun exactReturnsNullWhenTitleNormalizesToNothing() {
        val index = LibraryTrackIndex(listOf(local("a", "Bohemian Rhapsody", "Queen")))
        assertNull(index.exact("   ", "Queen"))
        assertNull(index.exact("", "Queen"))
    }

    @Test
    fun nonLocalTracksAreExcluded() {
        val index = LibraryTrackIndex(
            listOf(
                local("a", "Bohemian Rhapsody", "Queen"),
                remote("b", "Voyager", "Xdinary Heroes"),
            ),
        )
        // Онлайн-трек не может быть «локальной копией»: его ещё надо сохранить.
        assertEquals(1, index.size)
        assertNull(index.exact("Voyager", "Xdinary Heroes"))
    }

    @Test
    fun unplayableTracksAreExcluded() {
        val index = LibraryTrackIndex(
            listOf(
                local("a", "Bohemian Rhapsody", "Queen"),
                local("b", "Voyager", "Xdinary Heroes", playable = false),
            ),
        )
        assertEquals(1, index.size)
        assertNull(index.exact("Voyager", "Xdinary Heroes"))
    }

    @Test
    fun earliestTrackWinsOnKeyCollision() {
        // Два файла с одинаковым названием и исполнителем: побеждает первый,
        // как и в ImportTrackMatcher.best при равном балле.
        val index = LibraryTrackIndex(
            listOf(
                local("first", "Voyager", "Xdinary Heroes"),
                local("second", "Voyager", "Xdinary Heroes"),
            ),
        )
        assertEquals(2, index.size)
        assertEquals("first", index.exact("Voyager", "Xdinary Heroes")?.id)
    }

    @Test
    fun exposesIndexedTracksInOriginalOrder() {
        // MainViewModel использует этот список для полного сравнения матчером,
        // поэтому порядок обязан совпадать с исходным.
        val index = LibraryTrackIndex(
            listOf(
                local("a", "Bohemian Rhapsody", "Queen"),
                remote("r", "Voyager", "Xdinary Heroes"),
                local("b", "Ларёк", "ДДТ"),
                local("c", "Unplayable", "Nobody", playable = false),
            ),
        )
        assertEquals(listOf("a", "b"), index.tracks.map { it.id })
    }

    @Test
    fun emptyLibraryProducesEmptyIndex() {
        val index = LibraryTrackIndex(emptyList())
        assertEquals(0, index.size)
        assertEquals(emptyList<Track>(), index.tracks)
        assertNull(index.exact("Anything", "Anyone"))
    }

    @Test
    fun indexAgreesWithMatcherForExactCases() {
        // Индекс — только быстрый путь для точного случая. Если он находит трек,
        // матчер обязан согласиться, иначе поведение импорта станет зависимым от
        // того, попал ли ключ в индекс.
        val tracks = listOf(
            local("a", "Bohemian Rhapsody", "Queen"),
            local("b", "Ларёк", "ДДТ"),
            local("c", "SARÀ PERCHÉ TI AMO", "Ricchi e Poveri"),
        )
        val index = LibraryTrackIndex(tracks)
        val candidates = tracks.map {
            MatchCandidate(id = it.id, title = it.title, artist = it.artist, durationMs = it.durationMs)
        }
        val entries = listOf(
            ImportedTrackEntry(title = "Bohemian Rhapsody", artist = "Queen"),
            ImportedTrackEntry(title = "Ларек", artist = "ДДТ"),
            ImportedTrackEntry(title = "Sara Perche Ti Amo", artist = "Ricchi e Poveri"),
        )
        for (entry in entries) {
            val viaIndex = index.exact(entry.title, entry.artist)
            val viaMatcher = ImportTrackMatcher.best(candidates, entry)
            assertEquals(
                "индекс и матчер разошлись на «${entry.title}»",
                viaMatcher?.id,
                viaIndex?.id,
            )
        }
    }
}
