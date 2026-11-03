package app.sonora.data.import

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты плана поисковых запросов. План — сердце исправления импорта: раньше на
 * каждую строку уходил ровно один запрос, и строка с расхождением в написании
 * терялась навсегда.
 */
class ImportSearchPlannerTest {

    private fun entry(
        title: String,
        artist: String? = null,
    ) = ImportedTrackEntry(title = title, artist = artist)

    @Test
    fun exactQueryComesFirst() {
        val queries = ImportSearchPlanner.queries(entry("Bohemian Rhapsody", "Queen"))
        assertEquals("Queen Bohemian Rhapsody", queries.first())
    }

    @Test
    fun planFallsBackFromNarrowToBroad() {
        val queries = ImportSearchPlanner.queries(entry("Bohemian Rhapsody", "Queen"))
        assertEquals(
            listOf("Queen Bohemian Rhapsody", "Bohemian Rhapsody"),
            queries,
        )
    }

    @Test
    fun decoratedTitleAddsCleanedVariant() {
        // Каталог нашёл бы этот трек и без украшений — пробуем оба варианта.
        val queries = ImportSearchPlanner.queries(
            entry("Nirvana - Smells Like Teen Spirit (Official Video)", "Nirvana"),
        )
        assertEquals("Nirvana Nirvana - Smells Like Teen Spirit (Official Video)", queries[0])
        assertEquals("Nirvana Nirvana - Smells Like Teen Spirit", queries[1])
        assertEquals("Nirvana - Smells Like Teen Spirit (Official Video)", queries[2])
        assertEquals("Nirvana - Smells Like Teen Spirit", queries[3])
    }

    @Test
    fun featTailIsStrippedInCleanedVariant() {
        val queries = ImportSearchPlanner.queries(entry("Burn (feat. ROOKIES)", "Xdinary Heroes"))
        assertTrue(queries.any { it == "Xdinary Heroes Burn" })
    }

    @Test
    fun blankArtistSearchesByTitleOnly() {
        val queries = ImportSearchPlanner.queries(entry("Bohemian Rhapsody"))
        assertEquals(listOf("Bohemian Rhapsody"), queries)
    }

    @Test
    fun blankArtistWithDecoratedTitleDoesNotDuplicate() {
        // Исполнителя нет, поэтому «артист + название» не строится: план не
        // должен раздуваться повторами одного и того же названия.
        val queries = ImportSearchPlanner.queries(entry("Song (Official Audio)"))
        assertEquals(listOf("Song (Official Audio)", "Song"), queries)
    }

    @Test
    fun duplicatesAreRemovedCaseInsensitively() {
        // Исполнитель повторяет название — запросы схлопываются.
        val queries = ImportSearchPlanner.queries(entry("Voyager", "Voyager"))
        assertEquals(listOf("Voyager Voyager", "Voyager"), queries)
    }

    @Test
    fun neverExceedsMaxQueries() {
        val queries = ImportSearchPlanner.queries(
            entry("Some Very Decorated Song (Official Video) (Remastered 2011)", "An Artist"),
        )
        assertTrue(
            "план длиннее MAX_QUERIES: $queries",
            queries.size <= ImportSearchPlanner.MAX_QUERIES,
        )
    }

    @Test
    fun allQueriesAreNonBlankAndTrimmed() {
        val queries = ImportSearchPlanner.queries(entry("  Spaced   Out  ", "  Artist  "))
        assertTrue(queries.isNotEmpty())
        for (query in queries) {
            assertTrue("пустой запрос в плане", query.isNotBlank())
            assertEquals("запрос не обрезан: «$query»", query, query.trim())
            assertTrue("двойные пробелы: «$query»", !query.contains("  "))
        }
    }

    @Test
    fun whitespaceOnlyInputReturnsEmptyPlan() {
        assertTrue(ImportSearchPlanner.queries(entry("   ", "   ")).isEmpty())
        assertTrue(ImportSearchPlanner.queries(entry("")).isEmpty())
    }

    @Test
    fun titleOnlyFallbackSurvivesAggressiveCleaning() {
        // Название целиком состоит из «украшения»: очистка даст пустоту, но
        // исходное название обязано остаться последним рубежом.
        val queries = ImportSearchPlanner.queries(entry("(Official Video)", "Artist"))
        assertTrue(queries.isNotEmpty())
        assertTrue(queries.any { it.contains("(Official Video)") })
    }

    @Test
    fun cyrillicEntriesKeepTheirOriginalTitle() {
        val queries = ImportSearchPlanner.queries(entry("Ларёк", "ДДТ"))
        assertEquals("ДДТ Ларёк", queries.first())
        assertTrue(queries.any { it == "Ларёк" })
    }

    @Test
    fun typographicDashTitleIsPreserved() {
        val queries = ImportSearchPlanner.queries(entry("Artist — Title", "Band"))
        assertEquals("Band Artist — Title", queries.first())
    }

    @Test
    fun candidateLimitIsSane() {
        assertTrue(ImportSearchPlanner.CANDIDATE_LIMIT > 0)
        assertEquals(4, ImportSearchPlanner.MAX_QUERIES)
    }
}
