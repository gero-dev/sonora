package app.sonora.data.wave

import app.sonora.core.model.Source
import app.sonora.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Сортировки волны: смысл у каждой свой, SHUFFLE воспроизводим по сиду. */
class WaveSorterTest {

    private fun track(id: String, title: String, artist: String, durationMs: Long = 200_000L) =
        Track(
            id = id,
            title = title,
            artist = artist,
            durationMs = durationMs,
            uri = "file:///$id",
            source = Source.LOCAL,
            playable = true,
        )

    private val tracks = listOf(
        track("1", "beta", "Zed", durationMs = 300_000L),
        track("2", "Alpha", "alice", durationMs = 100_000L),
        track("3", "gamma", "Mike", durationMs = 200_000L),
    )

    @Test
    fun `for you keeps ranked order`() {
        assertEquals(
            listOf("1", "2", "3"),
            WaveSorter.sort(tracks, WaveSort.FOR_YOU).map { it.id },
        )
    }

    @Test
    fun `title sort is case insensitive`() {
        assertEquals(
            listOf("2", "1", "3"),
            WaveSorter.sort(tracks, WaveSort.TITLE).map { it.id },
        )
    }

    @Test
    fun `artist sort is case insensitive`() {
        assertEquals(
            listOf("2", "3", "1"),
            WaveSorter.sort(tracks, WaveSort.ARTIST).map { it.id },
        )
    }

    @Test
    fun `duration sort is ascending`() {
        assertEquals(
            listOf("2", "3", "1"),
            WaveSorter.sort(tracks, WaveSort.DURATION).map { it.id },
        )
    }

    @Test
    fun `shuffle is deterministic for the same seed`() {
        val first = WaveSorter.sort(tracks, WaveSort.SHUFFLE, seed = 42L).map { it.id }
        val second = WaveSorter.sort(tracks, WaveSort.SHUFFLE, seed = 42L).map { it.id }
        assertEquals(first, second)
        assertEquals(tracks.map { it.id }.sorted(), first.sorted())
    }

    @Test
    fun `shuffle changes order for another seed`() {
        val many = (1..10).map { track("$it", "Song $it", "Artist $it") }
        val a = WaveSorter.sort(many, WaveSort.SHUFFLE, seed = 1L).map { it.id }
        val b = WaveSorter.sort(many, WaveSort.SHUFFLE, seed = 2L).map { it.id }
        assertEquals(many.map { it.id }.sorted(), a.sorted())
        assertEquals(many.map { it.id }.sorted(), b.sorted())
        assertEquals(seedSignature(a), seedSignature(WaveSorter.sort(many, WaveSort.SHUFFLE, 1L).map { it.id }))
        assertNotEquals(seedSignature(a), seedSignature(b))
    }

    private fun seedSignature(ids: List<String>): String = ids.joinToString(",")
}
