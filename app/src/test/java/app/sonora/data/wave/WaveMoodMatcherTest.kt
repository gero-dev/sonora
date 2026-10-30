package app.sonora.data.wave

import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.data.telemetry.AudioFeature
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Бакеты настроения по аудио-фичам ReccoBeats и текстовая подсказка как
 * запасной сигнал, когда фич ещё нет.
 */
class WaveMoodMatcherTest {

    private fun track(
        title: String = "Track",
        artist: String = "Artist",
        album: String? = null,
        genre: String? = null,
    ) = Track(
        id = "id-$title",
        title = title,
        artist = artist,
        album = album,
        durationMs = 200_000L,
        uri = "file:///$title",
        source = Source.LOCAL,
        playable = true,
        extra = genre?.let { mapOf("genre" to it) } ?: emptyMap(),
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
    private val focus = feature(0.45, 0.4, 0.32, 0.55, 0.9, 100.0)
    private val night = feature(0.5, 0.2, 0.5, 0.3, 0.6, 110.0)
    private val happy = feature(0.8, 0.9, 0.8, 0.2, 0.2, 124.0)

    @Test
    fun `any mood accepts everything`() {
        assertTrue(WaveMoodMatcher.matches(track(), WaveMood.ANY, null))
        assertTrue(WaveMoodMatcher.matches(track(), WaveMood.ANY, energetic))
    }

    @Test
    fun `calm bucket accepts calm audio and rejects energetic audio`() {
        val calmTrack = track("Ocean")
        assertTrue(WaveMoodMatcher.matchesByFeature(calm, WaveMood.CALM) == true)
        assertTrue(WaveMoodMatcher.matchesByFeature(energetic, WaveMood.CALM) == false)
        assertTrue(WaveMoodMatcher.matches(calmTrack, WaveMood.CALM, calm))
        assertFalse(WaveMoodMatcher.matches(calmTrack, WaveMood.CALM, energetic))
    }

    @Test
    fun `energetic bucket accepts dance audio`() {
        assertTrue(WaveMoodMatcher.matchesByFeature(energetic, WaveMood.ENERGY) == true)
        assertTrue(WaveMoodMatcher.matchesByFeature(calm, WaveMood.ENERGY) == false)
    }

    @Test
    fun `focus bucket accepts instrumental low valence audio`() {
        assertTrue(WaveMoodMatcher.matchesByFeature(focus, WaveMood.FOCUS) == true)
        assertTrue(WaveMoodMatcher.matchesByFeature(happy, WaveMood.FOCUS) == false)
    }

    @Test
    fun `night and happy buckets are separated`() {
        assertTrue(WaveMoodMatcher.matchesByFeature(night, WaveMood.NIGHT) == true)
        assertTrue(WaveMoodMatcher.matchesByFeature(happy, WaveMood.HAPPY) == true)
        assertFalse(WaveMoodMatcher.matchesByFeature(happy, WaveMood.NIGHT) == true)
    }

    @Test
    fun `insufficient features fall back to keywords`() {
        // Всего одно измерение: доверять фичам нельзя.
        val partial = AudioFeature(energy = 0.95)
        assertNull(WaveMoodMatcher.matchesByFeature(partial, WaveMood.CALM))

        val chillByTitle = track("Chill Acoustic Evening")
        assertTrue(WaveMoodMatcher.matches(chillByTitle, WaveMood.CALM, partial))

        val plain = track("Untitled")
        assertFalse(WaveMoodMatcher.matches(plain, WaveMood.CALM, partial))
    }

    @Test
    fun `genre tag works as keyword evidence`() {
        val track = track("Song", genre = "ambient piano")
        assertTrue(WaveMoodMatcher.matches(track, WaveMood.CALM, null))
    }

    @Test
    fun `score rewards matching audio over mismatching`() {
        val track = track("Song")
        val fit = WaveMoodMatcher.score(track, WaveMood.CALM, calm)
        val mismatch = WaveMoodMatcher.score(track, WaveMood.CALM, energetic)
        assertTrue(fit > mismatch)
        assertTrue(fit > 0.5)
    }

    @Test
    fun `score without any evidence is zero`() {
        assertEqualsZero(WaveMoodMatcher.score(track("Untitled"), WaveMood.CALM, null))
    }

    private fun assertEqualsZero(value: Double) {
        assertTrue("score must be 0 but was $value", value == 0.0)
    }
}
