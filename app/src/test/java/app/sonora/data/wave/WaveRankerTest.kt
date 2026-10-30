package app.sonora.data.wave

import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.core.settings.WaveSettings
import app.sonora.data.telemetry.AudioFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ранжирование с аудио-фичами ReccoBeats: избранное, история, похожесть на
 * любимое, штраф за повторы, настроение и воспроизводимость по сиду.
 */
class WaveRankerTest {

    private val now = 1_700_000_000_000L

    private fun track(
        id: String,
        title: String,
        artist: String,
        source: Source = Source.LOCAL,
    ) = Track(
        id = id,
        title = title,
        artist = artist,
        durationMs = 200_000L,
        uri = "file:///$id",
        source = source,
        playable = true,
    )

    private fun entry(
        artist: String,
        title: String,
        completed: Boolean = true,
        listenedMs: Long = 180_000L,
        durationMs: Long = 200_000L,
        trackId: String = "t:$artist:$title",
    ) = WaveHistoryEntry(
        trackId = trackId,
        artist = artist,
        title = title,
        playedAtMs = now - 1_000L,
        completed = completed,
        listenedMs = listenedMs,
        durationMs = durationMs,
    )

    private fun feature(
        energy: Double,
        valence: Double,
        danceability: Double,
        acousticness: Double,
        instrumentalness: Double,
        tempo: Double,
    ) = AudioFeature(
        tempo = tempo,
        energy = energy,
        danceability = danceability,
        valence = valence,
        acousticness = acousticness,
        instrumentalness = instrumentalness,
    )

    private val calm = feature(0.2, 0.4, 0.2, 0.8, 0.6, 85.0)
    private val calmClose = feature(0.22, 0.42, 0.22, 0.78, 0.6, 88.0)
    private val energetic = feature(0.9, 0.7, 0.85, 0.05, 0.15, 128.0)

    private fun context(
        tuning: WaveSettings = WaveSettings(),
        favoriteIds: Set<String> = emptySet(),
        favoriteArtists: Set<String> = emptySet(),
        artistCounts: Map<String, Int> = emptyMap(),
        history: List<WaveHistoryEntry> = emptyList(),
        featureIndex: WaveFeatureIndex = WaveFeatureIndex.EMPTY,
        mood: WaveMood = WaveMood.ANY,
        recentTrackKeys: Set<String>? = emptySet(),
        seed: Long = 7L,
    ) = WaveRankContext(
        tuning = tuning,
        favoriteIds = favoriteIds,
        favoriteArtists = favoriteArtists,
        artistCounts = artistCounts,
        history = history,
        featureIndex = featureIndex,
        mood = mood,
        localBias = true,
        nowMs = now,
        recentTrackKeys = recentTrackKeys,
        seed = seed,
    )

    @Test
    fun `favorite track scores above a regular one`() {
        val favorite = track("1", "Polly", "Nirvana")
        val regular = track("2", "Breed", "Nirvana")
        val ctx = context(favoriteIds = setOf("1"))
        assertTrue(WaveRanker.score(favorite, ctx) > WaveRanker.score(regular, ctx))
    }

    @Test
    fun `rank by score puts favorite first`() {
        val favorite = track("1", "Polly", "Nirvana")
        val regular = track("2", "Breed", "Nirvana")
        val ranked = WaveRanker.rankByScore(listOf(regular, favorite), context(favoriteIds = setOf("1")))
        assertEquals("1", ranked.first().id)
    }

    @Test
    fun `skipped artist ranks below a neutral one`() {
        val skipped = track("1", "Song A", "Skip Artist")
        val neutral = track("2", "Song B", "Other Artist")
        val skip = entry("Skip Artist", "Old Song", completed = false, listenedMs = 1_000L)
        val ctx = context(history = listOf(skip), recentTrackKeys = null)
        assertTrue(WaveRanker.score(neutral, ctx) > WaveRanker.score(skipped, ctx))
    }

    @Test
    fun `audio similarity to liked tracks lifts similar candidates`() {
        val seedTrack = track("seed", "Seed", "Seed Artist")
        val close = track("close", "Close Song", "A Artist")
        val far = track("far", "Far Song", "B Artist")
        val history = listOf(entry("Seed Artist", "Seed", trackId = "seed"))
        val features = mapOf(
            "seed" to calm,
            "close" to calmClose,
            "far" to energetic,
        )
        val index = WaveFeatureIndex.of(listOf(seedTrack, close, far), features, history)
        assertTrue(index.seedCount > 0)

        val ctx = context(
            tuning = WaveSettings(useHistory = false),
            featureIndex = index,
            recentTrackKeys = emptySet(),
        )
        assertTrue(WaveRanker.score(close, ctx) > WaveRanker.score(far, ctx))
        assertEquals("close", WaveRanker.rankByScore(listOf(far, close), ctx).first().id)
    }

    @Test
    fun `recently played track is deprioritized but kept`() {
        val recent = track("1", "Song A", "A Artist")
        val fresh = track("2", "Song B", "B Artist")
        val ctx = context(
            tuning = WaveSettings(useHistory = false),
            recentTrackKeys = setOf(WaveEngine.waveKey(recent)),
        )
        assertTrue(WaveRanker.score(fresh, ctx) > WaveRanker.score(recent, ctx))
        assertEquals(2, WaveRanker.rank(listOf(recent, fresh), ctx).size)
    }

    @Test
    fun `mood fit adds score on top of the hard filter`() {
        val calmTrack = track("1", "Ocean", "A Artist")
        val loud = track("2", "Rage", "B Artist")
        val index = WaveFeatureIndex.of(
            listOf(calmTrack, loud),
            mapOf("1" to calm, "2" to energetic),
        )
        val ctx = context(
            tuning = WaveSettings(useHistory = false),
            featureIndex = index,
            mood = WaveMood.CALM,
        )
        assertTrue(WaveRanker.score(calmTrack, ctx) > WaveRanker.score(loud, ctx))
    }

    @Test
    fun `same seed gives the same order`() {
        val tracks = (1..20).map { track("$it", "Song $it", "Artist ${it % 5}") }
        val ctx = context(seed = 42L)
        val first = WaveRanker.rank(tracks, ctx).map { it.id }
        val second = WaveRanker.rank(tracks, ctx).map { it.id }
        assertEquals(first, second)
        assertEquals(tracks.size, first.size)
    }

    @Test
    fun `artist diversity spreads repeated artists`() {
        val tracks = listOf(
            track("1", "A", "X"),
            track("2", "B", "X"),
            track("3", "C", "X"),
            track("4", "D", "Y"),
        )
        val diversified = WaveEngine.diversify(tracks, 4, 2)
        assertEquals(4, diversified.size)
        assertEquals(listOf("1", "2", "4", "3"), diversified.map { it.id })
    }
}
