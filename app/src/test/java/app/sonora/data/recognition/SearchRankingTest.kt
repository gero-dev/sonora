package app.sonora.data.recognition

import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.data.telemetry.AudioFeature
import app.sonora.data.telemetry.SearchRankItem
import app.sonora.data.telemetry.SearchRanker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты переранжирования результатов поиска: текст + локальная доступность +
 * уверенность распознавания + аудио-фичи. Чистый Kotlin, без Android и сети.
 */
class SearchRankingTest {

    @Test
    fun `local track ranks above identical stream result`() {
        val local = track("local-1", "Nirvana", "Smells Like Teen Spirit", source = Source.LOCAL)
        val stream = track("yt-1", "Nirvana", "Smells Like Teen Spirit", source = Source.YOUTUBE)
        val ranked = SearchRanker.rank(
            "smells like teen spirit",
            listOf(
                SearchRankItem(stream, localAvailable = false),
                SearchRankItem(local, localAvailable = true),
            ),
        )
        assertEquals("local-1", ranked.first().track.id)
    }

    @Test
    fun `exact title match ranks above album match`() {
        val exact = track("a", "Nirvana", "Smells Like Teen Spirit")
        val albumOnly = track("b", "Nirvana", "Nevermind", album = "Smells Like Teen Spirit")
        val ranked = SearchRanker.rank(
            "smells like teen spirit",
            listOf(
                SearchRankItem(albumOnly),
                SearchRankItem(exact),
            ),
        )
        assertEquals("a", ranked.first().track.id)
    }

    @Test
    fun `recognized track gets a ranking boost`() {
        val plain = track("plain", "Artist", "Song")
        val recognized = track("recognized", "Artist", "Song")
        val ranked = SearchRanker.rank(
            "song",
            listOf(
                SearchRankItem(plain),
                SearchRankItem(recognized, recognitionScore = 0.9),
            ),
        )
        assertEquals("recognized", ranked.first().track.id)
    }

    @Test
    fun `audio feature confidence nudges equal results`() {
        val noFeature = track("no-feature", "Artist", "Song")
        val withFeature = track("feature", "Artist", "Song")
        val feature = AudioFeature(
            tempo = 120.0,
            energy = 0.8,
            danceability = 0.7,
            valence = 0.6,
            acousticness = 0.1,
            instrumentalness = 0.0,
            liveness = 0.2,
            speechiness = 0.05,
            loudness = -5.0,
        )
        val ranked = SearchRanker.rank(
            "song",
            listOf(
                SearchRankItem(noFeature),
                SearchRankItem(withFeature, audioFeature = feature),
            ),
        )
        assertEquals("feature", ranked.first().track.id)
        assertTrue(SearchRanker.score("song", SearchRankItem(withFeature, audioFeature = feature)) >
            SearchRanker.score("song", SearchRankItem(noFeature)))
    }

    @Test
    fun `ranking keeps all items and is stable for equal scores`() {
        val first = track("first", "Artist", "Song")
        val second = track("second", "Artist", "Song")
        val input = listOf(SearchRankItem(first), SearchRankItem(second))
        val ranked = SearchRanker.rank("song", input)
        assertEquals(listOf("first", "second"), ranked.map { it.track.id })
        assertEquals(2, ranked.size)
    }

    private fun track(
        id: String,
        artist: String,
        title: String,
        album: String? = null,
        source: Source = Source.YOUTUBE,
    ): Track = Track(
        id = id,
        title = title,
        artist = artist,
        album = album,
        source = source,
        playable = true,
    )
}
