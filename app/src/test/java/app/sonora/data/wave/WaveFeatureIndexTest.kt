package app.sonora.data.wave

import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.data.telemetry.AudioFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Индекс аудио-фич: быстрый поиск по id и по ключу, эталон вкуса, обогащение. */
class WaveFeatureIndexTest {

    private fun track(id: String, title: String, artist: String) = Track(
        id = id,
        title = title,
        artist = artist,
        durationMs = 200_000L,
        uri = "file:///$id",
        source = Source.LOCAL,
        playable = true,
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
    private val energetic = feature(0.9, 0.7, 0.85, 0.05, 0.15, 128.0)

    @Test
    fun `empty index has no features and no similarity`() {
        val track = track("1", "Song", "Artist")
        assertNull(WaveFeatureIndex.EMPTY.forTrack(track))
        assertEquals(0.0, WaveFeatureIndex.EMPTY.similarityToSeeds(track), 0.0001)
        assertTrue(WaveFeatureIndex.EMPTY.isEmpty)
    }

    @Test
    fun `features are found by track id`() {
        val track = track("1", "Song", "Artist")
        val index = WaveFeatureIndex.of(listOf(track), mapOf("1" to calm))
        assertNotNull(index.forTrack(track))
        assertEquals(calm, index.forTrack(track))
    }

    @Test
    fun `features are found by wave key for another id`() {
        val original = track("1", "Polly", "Nirvana")
        val copy = track("2", "Polly", "Nirvana")
        val index = WaveFeatureIndex.of(listOf(original), mapOf("1" to calm))
        // По id копии фичи нет, но ключ «исполнитель|название» общий.
        assertEquals(calm, index.forTrack(copy))
        assertNull(index.forTrack(track("3", "Another Song", "Nirvana")))
        assertEquals(WaveEngine.waveKey(original), WaveEngine.waveKey(copy))
    }

    @Test
    fun `liked history becomes the taste seed and similar tracks score higher`() {
        val seedTrack = track("seed", "Seed", "Seed Artist")
        val close = track("close", "Close", "A")
        val far = track("far", "Far", "B")
        val history = listOf(
            WaveHistoryEntry(
                trackId = "seed",
                artist = "Seed Artist",
                title = "Seed",
                playedAtMs = 1_700_000_000_000L,
                completed = true,
                listenedMs = 180_000L,
                durationMs = 200_000L,
            ),
        )
        val index = WaveFeatureIndex.of(
            listOf(seedTrack, close, far),
            mapOf("seed" to calm, "close" to calm, "far" to energetic),
            history,
        )
        assertEquals(1, index.seedCount)
        assertTrue(index.similarityToSeeds(close) > index.similarityToSeeds(far))
    }

    @Test
    fun `with features adds background results`() {
        val track = track("1", "Song", "Artist")
        val before = WaveFeatureIndex.of(listOf(track), emptyMap())
        assertTrue(before.isEmpty)

        val after = before.withFeatures(
            featuresById = mapOf("1" to calm),
            featuresByKey = mapOf(WaveEngine.waveKey(track) to calm),
        )
        assertEquals(1, after.size)
        assertEquals(calm, after.forTrack(track))
    }
}
