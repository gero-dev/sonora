package app.sonora.data.wave

import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.core.settings.WaveSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Алгоритмы «Моей волны»: вклад прослушиваний, дизлайки и штраф за повторы.
 * Тесты чистые — без Android и сети.
 */
class WaveEngineTest {

    private fun track(
        id: String,
        title: String,
        artist: String = "Artist",
        source: Source = Source.LOCAL,
    ) = Track(
        id = id,
        title = title,
        artist = artist,
        source = source,
        playable = true,
        uri = "file:///$id",
    )

    private fun entry(
        artist: String,
        title: String,
        listenedMs: Long,
        durationMs: Long,
        completed: Boolean = false,
        playedAtMs: Long = System.currentTimeMillis(),
        trackId: String = "t:$artist:$title",
    ) = WaveHistoryEntry(
        trackId = trackId,
        artist = artist,
        title = title,
        playedAtMs = playedAtMs,
        completed = completed,
        listenedMs = listenedMs,
        durationMs = durationMs,
    )

    @Test
    fun `full listen counts as one and skip is negative`() {
        val full = entry("Nirvana", "Polly", listenedMs = 170_000, durationMs = 180_000)
        val half = entry("Nirvana", "Breed", listenedMs = 90_000, durationMs = 180_000)
        val skip = entry("Queen", "Bohemian", listenedMs = 3_000, durationMs = 350_000)

        assertEquals(1.0, WaveEngine.engagementScore(full)!!, 0.001)
        assertEquals(0.45, WaveEngine.engagementScore(half)!!, 0.001)
        assertTrue(WaveEngine.engagementScore(skip)!! < 0.0)
    }

    @Test
    fun `placeholder entry is ignored by affinity`() {
        // Плейсхолдер пишется в момент старта: 0 мс, не completed.
        val placeholder = entry("Nirvana", "Polly", listenedMs = 0, durationMs = 180_000)
        val affinity = WaveEngine.artistAffinity(listOf(placeholder))
        assertTrue(affinity.isEmpty())
    }

    @Test
    fun `affinity rewards completed plays and penalizes skips`() {
        val now = System.currentTimeMillis()
        val history = listOf(
            entry("Nirvana", "Polly", 170_000, 180_000, playedAtMs = now - 1_000),
            entry("Queen", "Bohemian", 2_000, 350_000, playedAtMs = now - 1_000),
        )
        val affinity = WaveEngine.artistAffinity(history, now)
        assertNotNull(affinity["nirvana"])
        assertTrue((affinity["nirvana"] ?: 0.0) > 0.5)
        assertTrue((affinity["queen"] ?: 0.0) < 0.0)
    }

    @Test
    fun `disliked track is excluded from ranking`() {
        val liked = track("1", "Polly")
        val disliked = track("2", "Breed")
        val dislikedKeys = setOf(WaveEngine.dislikeKey(disliked))

        val ranked = WaveEngine.rank(
            candidates = listOf(disliked, liked),
            favoriteIds = emptySet(),
            favoriteArtists = emptySet(),
            artistCounts = emptyMap(),
            history = emptyList(),
            tuning = WaveSettings(),
            localBias = true,
            random = Random(7),
            dislikedKeys = dislikedKeys,
        )

        assertEquals(listOf("1"), ranked.map { it.id })
    }

    @Test
    fun `recently played track is kept but deprioritized`() {
        val recent = track("1", "Polly")
        val fresh = track("2", "Breed")
        val tuning = WaveSettings()
        val recentKeys = setOf(WaveEngine.waveKey(recent))

        val ranked = WaveEngine.rank(
            candidates = listOf(recent, fresh),
            favoriteIds = emptySet(),
            favoriteArtists = emptySet(),
            artistCounts = emptyMap(),
            history = emptyList(),
            tuning = tuning,
            localBias = true,
            random = Random(1),
            recentTrackKeys = recentKeys,
        )

        // Ничего не выброшено: у небольшой фонотеки волна не должна пустеть.
        assertEquals(2, ranked.size)
        assertEquals(setOf("1", "2"), ranked.map { it.id }.toSet())
    }

    @Test
    fun `track engagement sums completed plays by key`() {
        val now = System.currentTimeMillis()
        val history = listOf(
            entry("Nirvana", "Polly", 170_000, 180_000, playedAtMs = now - 1_000),
            entry("Nirvana", "Breed", 2_000, 180_000, playedAtMs = now - 1_000),
        )
        val scores = WaveEngine.trackEngagement(history, now)
        val polly = WaveEngine.waveKey(track("1", "Polly", "Nirvana"))
        val breed = WaveEngine.waveKey(track("2", "Breed", "Nirvana"))

        assertTrue((scores[polly] ?: 0.0) > 0.5)
        assertTrue((scores[breed] ?: 0.0) < 0.0)
    }
}
