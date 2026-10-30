package app.sonora.data.wave

import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.core.settings.WaveSettings
import app.sonora.data.telemetry.AudioFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Жёсткость фильтров «Моей волны».
 *
 * Главное правило этих тестов: фильтр никогда не «снимается» из-за пустой
 * выдачи. Если после настроения/избранного/источника не осталось треков —
 * волна пустая, и есть понятная причина.
 */
class WaveFilterEngineTest {

    private fun track(
        id: String,
        title: String = "Track $id",
        artist: String = "Artist",
        source: Source = Source.LOCAL,
        album: String? = null,
        extra: Map<String, String> = emptyMap(),
    ) = Track(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = 200_000L,
        uri = "file:///$id",
        source = source,
        playable = true,
        extra = extra,
    )

    private fun feature(
        energy: Double? = null,
        valence: Double? = null,
        danceability: Double? = null,
        acousticness: Double? = null,
        instrumentalness: Double? = null,
        tempo: Double? = null,
    ) = AudioFeature(
        tempo = tempo,
        energy = energy,
        danceability = danceability,
        valence = valence,
        acousticness = acousticness,
        instrumentalness = instrumentalness,
    )

    private fun request(
        filters: WaveFilters = WaveFilters(),
        tuning: WaveSettings = WaveSettings(),
        favoriteIds: Set<String> = emptySet(),
        dislikedKeys: Set<String> = emptySet(),
        featureIndex: WaveFeatureIndex = WaveFeatureIndex.EMPTY,
        history: List<WaveHistoryEntry> = emptyList(),
        nowMs: Long = System.currentTimeMillis(),
        excludeIds: Set<String> = emptySet(),
        excludeKeys: Set<String> = emptySet(),
        onlineAllowed: Boolean = true,
    ) = WaveFilterRequest(
        filters = filters,
        tuning = tuning,
        favoriteIds = favoriteIds,
        dislikedKeys = dislikedKeys,
        featureIndex = featureIndex,
        history = history,
        nowMs = nowMs,
        excludeIds = excludeIds,
        excludeKeys = excludeKeys,
        onlineAllowed = onlineAllowed,
    )

    @Test
    fun `local mode rejects remote tracks`() {
        val local = track("1")
        val remote = track("2", source = Source.YOUTUBE)
        val outcome = WaveFilterEngine.apply(
            listOf(local, remote),
            request(filters = WaveFilters(source = WaveSourceMode.LOCAL)),
        )
        assertEquals(listOf("1"), outcome.accepted.map { it.id })
        assertEquals(1, outcome.rejectedCount(WaveRejectReason.SOURCE))
        assertEquals(WaveStatus.READY, outcome.status)
    }

    @Test
    fun `online mode rejects local tracks`() {
        val local = track("1")
        val remote = track("2", source = Source.YOUTUBE)
        val outcome = WaveFilterEngine.apply(
            listOf(local, remote),
            request(filters = WaveFilters(source = WaveSourceMode.ONLINE)),
        )
        assertEquals(listOf("2"), outcome.accepted.map { it.id })
        assertEquals(1, outcome.rejectedCount(WaveRejectReason.SOURCE))
    }

    @Test
    fun `mix accepts both sources`() {
        val outcome = WaveFilterEngine.apply(
            listOf(track("1"), track("2", source = Source.YOUTUBE)),
            request(),
        )
        assertEquals(2, outcome.accepted.size)
    }

    @Test
    fun `favorites only keeps favorites and never falls back`() {
        val favorite = track("1")
        val other = track("2")
        val outcome = WaveFilterEngine.apply(
            listOf(favorite, other),
            request(
                filters = WaveFilters(favoritesOnly = true),
                favoriteIds = setOf("1"),
            ),
        )
        assertEquals(listOf("1"), outcome.accepted.map { it.id })
        assertEquals(1, outcome.rejectedCount(WaveRejectReason.NOT_FAVORITE))
    }

    @Test
    fun `empty favorites returns empty wave with reason instead of leaking tracks`() {
        val outcome = WaveFilterEngine.apply(
            listOf(track("1"), track("2")),
            request(filters = WaveFilters(favoritesOnly = true), favoriteIds = emptySet()),
        )
        assertTrue(outcome.accepted.isEmpty())
        assertEquals(WaveStatus.EMPTY_FAVORITES, outcome.status)
        assertNotNull(outcome.hint)
        assertTrue(outcome.hint!!.isNotBlank())
    }

    @Test
    fun `mood filter uses audio features strictly`() {
        val calm = track("calm", "Ocean", "Ambient")
        val loud = track("loud", "Rage", "Metal")
        val features = mapOf(
            "calm" to feature(
                energy = 0.2, valence = 0.4, danceability = 0.2,
                acousticness = 0.8, instrumentalness = 0.6, tempo = 85.0,
            ),
        )
        val index = WaveFeatureIndex.of(listOf(calm, loud), features)
        val outcome = WaveFilterEngine.apply(
            listOf(calm, loud),
            request(filters = WaveFilters(mood = WaveMood.CALM), featureIndex = index),
        )
        assertEquals(listOf("calm"), outcome.accepted.map { it.id })
        assertEquals(1, outcome.rejectedCount(WaveRejectReason.MOOD))
    }

    @Test
    fun `empty mood match returns empty wave with mood reason`() {
        val loud = track("loud", "Rage", "Metal")
        val features = mapOf(
            "loud" to feature(
                energy = 0.9, valence = 0.7, danceability = 0.85,
                acousticness = 0.05, instrumentalness = 0.15, tempo = 128.0,
            ),
        )
        val index = WaveFeatureIndex.of(listOf(loud), features)
        val outcome = WaveFilterEngine.apply(
            listOf(loud),
            request(filters = WaveFilters(mood = WaveMood.CALM), featureIndex = index),
        )
        assertTrue(outcome.accepted.isEmpty())
        assertEquals(WaveStatus.EMPTY_MOOD, outcome.status)
        assertTrue(outcome.hint!!.contains("Спокойное"))
    }

    @Test
    fun `mood falls back to keywords when features are missing`() {
        val chill = track("chill", "Chill Acoustic Evening", "Someone")
        val outcome = WaveFilterEngine.apply(
            listOf(chill),
            request(filters = WaveFilters(mood = WaveMood.CALM)),
        )
        assertEquals(listOf("chill"), outcome.accepted.map { it.id })
    }

    @Test
    fun `disliked track never leaks into the wave`() {
        val liked = track("1")
        val disliked = track("2")
        val outcome = WaveFilterEngine.apply(
            listOf(liked, disliked),
            request(dislikedKeys = setOf(WaveEngine.dislikeKey(disliked))),
        )
        assertEquals(listOf("1"), outcome.accepted.map { it.id })
        assertEquals(1, outcome.rejectedCount(WaveRejectReason.DISLIKED))
    }

    @Test
    fun `music only is exact`() {
        val live = track("live", "Track (Official Video)")
        val withMarkers = WaveFilterEngine.apply(listOf(live), request())
        assertTrue(withMarkers.accepted.isEmpty())
        assertEquals(1, withMarkers.rejectedCount(WaveRejectReason.MUSIC_ONLY))

        val relaxed = WaveFilterEngine.apply(
            listOf(live),
            request(tuning = WaveSettings(musicOnly = false, excludeVideos = false)),
        )
        assertEquals(listOf("live"), relaxed.accepted.map { it.id })
    }

    @Test
    fun `already shown tracks are excluded by id and by key`() {
        val shown = track("1")
        val byKey = track("2", title = "Other")
        val outcome = WaveFilterEngine.apply(
            listOf(shown, byKey, track("3", title = "Fresh")),
            request(
                excludeIds = setOf("1"),
                excludeKeys = setOf(WaveEngine.waveKey(byKey)),
            ),
        )
        assertEquals(listOf("3"), outcome.accepted.map { it.id })
        assertEquals(2, outcome.rejectedCount(WaveRejectReason.ALREADY_SHOWN))
    }

    @Test
    fun `high novelty suppresses fresh repeats and low novelty keeps them`() {
        val now = 1_700_000_000_000L
        val entry = WaveHistoryEntry(
            trackId = "1",
            artist = "Artist",
            title = "Track 1",
            playedAtMs = now - 60L * 60L * 1000L,
            completed = true,
        )
        val track = track("1")
        val strict = WaveFilterEngine.apply(
            listOf(track),
            request(
                tuning = WaveSettings(useHistory = true, novelty = 80),
                history = listOf(entry),
                nowMs = now,
            ),
        )
        assertTrue(strict.accepted.isEmpty())
        assertEquals(1, strict.rejectedCount(WaveRejectReason.FRESH_HISTORY))

        val soft = WaveFilterEngine.apply(
            listOf(track),
            request(
                tuning = WaveSettings(useHistory = true, novelty = 20),
                history = listOf(entry),
                nowMs = now,
            ),
        )
        assertEquals(listOf("1"), soft.accepted.map { it.id })
    }

    @Test
    fun `empty pool produces a status and a hint`() {
        val outcome = WaveFilterEngine.apply(emptyList(), request())
        assertTrue(outcome.accepted.isEmpty())
        assertFalse(outcome.status == WaveStatus.READY)
        assertTrue(!outcome.hint.isNullOrBlank())
    }

    @Test
    fun `online request with streaming disabled explains itself`() {
        val outcome = WaveFilterEngine.apply(
            emptyList(),
            request(
                filters = WaveFilters(source = WaveSourceMode.ONLINE),
                onlineAllowed = false,
            ),
        )
        assertEquals(WaveStatus.EMPTY_SOURCE, outcome.status)
        assertTrue(outcome.hint!!.contains("стриминг"))
    }
}
