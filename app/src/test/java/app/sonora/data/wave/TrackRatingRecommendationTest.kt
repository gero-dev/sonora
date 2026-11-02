package app.sonora.data.wave

import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.core.settings.WaveSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Оценки и negative feedback в рекомендациях: дизлайк скрывает трек,
 * приглушает его артиста и «похожих» исполнителей, а лайк — поднимает.
 */
class TrackRatingRecommendationTest {

    private val now = 1_700_000_000_000L

    private fun track(
        id: String,
        title: String,
        artist: String,
        album: String? = null,
        genre: String? = null,
        durationMs: Long = 200_000L,
        source: Source = Source.LOCAL,
    ) = Track(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        uri = "file:///$id",
        source = source,
        playable = true,
        extra = buildMap { if (genre != null) put("genre", genre) },
    )

    private fun entry(track: Track, rating: Int) = TrackRatingEntry(
        trackId = track.id,
        artist = track.artist,
        title = track.title,
        rating = rating,
        ratedAtMs = now,
        genre = track.extra["genre"],
    )

    private fun context(
        ratings: TrackRatingIndex,
        graph: ArtistGraph = ArtistGraph.EMPTY,
    ) = WaveRankContext(
        tuning = WaveSettings(useHistory = false),
        ratings = ratings,
        artistGraph = graph,
        localBias = true,
        nowMs = now,
        recentTrackKeys = emptySet(),
        seed = 7L,
    )

    @Test
    fun `disliked track is excluded from ranking`() {
        val bad = track("1", "Bad Song", "Bad Artist")
        val neutral = track("2", "Neutral Song", "Neutral Artist")
        val index = TrackRatingIndex.of(listOf(entry(bad, -1)), now)
        val ranked = WaveRanker.rank(listOf(bad, neutral), context(index))
        assertEquals(listOf("2"), ranked.map { it.id })
    }

    @Test
    fun `dislike suppresses similar artists via graph`() {
        val bad = track("1", "Bad Song", "Bad Artist", album = "Shared", genre = "rock")
        val friend = track("2", "Friend Song", "Friend Artist", album = "Shared", genre = "rock")
        val neutral = track("3", "Neutral Song", "Neutral Artist", album = "Other")
        val index = TrackRatingIndex.of(listOf(entry(bad, -1)), now)
        val graph = ArtistGraph.of(listOf(bad, friend, neutral))
        val ctx = context(index, graph)
        assertTrue(WaveRanker.score(neutral, ctx) > WaveRanker.score(friend, ctx))
    }

    @Test
    fun `like lifts the track and its artist`() {
        val liked = track("1", "Good Song", "Good Artist", genre = "pop")
        val otherBySameArtist = track("2", "Another Song", "Good Artist", genre = "pop")
        val neutral = track("3", "Neutral Song", "Neutral Artist", genre = "pop")
        val index = TrackRatingIndex.of(listOf(entry(liked, 1)), now)
        val ctx = context(index, ArtistGraph.of(listOf(liked, otherBySameArtist, neutral)))
        assertTrue(WaveRanker.score(otherBySameArtist, ctx) > WaveRanker.score(neutral, ctx))
    }

    @Test
    fun `radio queries work for a seed without video id`() {
        val seed = track("local:1", "Song", "Artist", genre = "synthwave")
        val info = RadioEngine.seedOf(seed)
        val queries = RadioEngine.queries(info, WaveSettings())
        assertTrue(queries.any { it.contains("Artist") })
        assertTrue(queries.any { it.contains("synthwave") })
    }

    @Test
    fun `radio candidates drop seed and disliked tracks`() {
        val seed = track("seed", "Song", "Artist")
        val similar = track("2", "Other Song", "Similar Artist")
        val disliked = track("3", "Hidden Song", "Hidden Artist")
        val index = TrackRatingIndex.of(listOf(entry(disliked, -1)), now)
        val filtered = RadioEngine.filterCandidates(
            candidates = listOf(seed, similar, disliked),
            seed = RadioEngine.seedOf(seed),
            tuning = WaveSettings(),
            dislikedKeys = index.dislikedWaveKeys(),
        )
        assertEquals(listOf("2"), filtered.map { it.id })
    }

    @Test
    fun `radio drops talk shows memes and non related search results`() {
        val seed = track("ytdlp:v_xXWRQ5cms", "Want To Be Closed", "Maxians Family")
        val info = RadioEngine.seedOf(seed)
        val trustedRelated = track(
            "rel1",
            "Want To Be Closed (Reload)",
            "Shoji Meguro",
            durationMs = 180_000L,
            source = Source.YOUTUBE,
        )
        val relatedSearch = track(
            "rel2",
            "Memories of You",
            "Shoji Meguro",
            durationMs = 200_000L,
            source = Source.YOUTUBE,
        )
        val southPark = track(
            "bad1",
            "South Park \"Not My Waterpark\" song + VIDEO",
            "Comedy Central",
            durationMs = 120_000L,
            source = Source.YOUTUBE,
        )
        val talkShow = track(
            "bad2",
            "Lalo Talks With Don Juan About Gus | Better Call Saul",
            "Better Call Saul",
            durationMs = 300_000L,
            source = Source.YOUTUBE,
        )
        val unrelated = track(
            "bad3",
            "Some Random Song",
            "Random Band",
            durationMs = 200_000L,
            source = Source.YOUTUBE,
        )
        val longMix = track(
            "bad4",
            "Persona Mix",
            "Maxians Family",
            durationMs = 3_600_000L,
            source = Source.YOUTUBE,
        )
        val filtered = RadioEngine.filterCandidates(
            candidates = listOf(
                seed,
                trustedRelated,
                relatedSearch,
                southPark,
                talkShow,
                unrelated,
                longMix,
            ),
            seed = info,
            tuning = WaveSettings(),
            trustedIds = setOf(trustedRelated.id),
            relatedArtists = setOf("Shoji Meguro", "Maxians Family"),
            strictRelevance = true,
        )
        assertEquals(listOf("rel1", "rel2"), filtered.map { it.id })
    }

    @Test
    fun `artist queries for video seed do not use generic fallbacks`() {
        val seed = track("youtube:abc", "Song", "Artist", genre = "video game music")
        val queries = RadioEngine.artistQueries(RadioEngine.seedOf(seed), WaveSettings())
        assertFalse(queries.any { it == "music mix" })
        assertFalse(queries.any { it == "new music mix" })
        assertTrue(queries.any { it.contains("Artist") })
    }
}
