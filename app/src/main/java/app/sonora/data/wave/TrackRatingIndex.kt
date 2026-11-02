package app.sonora.data.wave

import app.sonora.core.model.Track
import kotlinx.serialization.Serializable
import kotlin.math.exp

/**
 * Одна оценка трека: `+1` — «нравится», `-1` — «не нравится».
 *
 * Хранится вместе с «исполнитель|название» и жанром: ключ [WaveEngine.waveKey]
 * переживает переиндексацию файла и совпадает у локальной копии и стримингового
 * трека, поэтому одна и та же песня не получает две разные оценки.
 */
@Serializable
data class TrackRatingEntry(
    val trackId: String = "",
    val artist: String = "",
    val title: String = "",
    /** +1 «нравится», -1 «не нравится»; 0 в файл не пишется. */
    val rating: Int = 0,
    val ratedAtMs: Long = 0L,
    /** Жанр на момент оценки — нужен, чтобы штрафовать похожие треки. */
    val genre: String? = null,
)

/**
 * Чистый индекс оценок для ранжирования волны и радио: без Android и сети.
 *
 * Из оценок треков выводятся агрегаты по исполнителю и жанру. Штраф за
 * дизлайк работает мягко: сам трек скрывается жёстко (через
 * [dislikedWaveKeys]), а похожие треки того же артиста/жанра получают
 * пониженный вес — как negative feedback в Spotify.
 */
class TrackRatingIndex private constructor(
    private val byTrackId: Map<String, Int>,
    private val byWaveKey: Map<String, Int>,
    private val artistScores: Map<String, Double>,
    private val genreScores: Map<String, Double>,
    /** Артисты, у которых есть хотя бы один лайк. */
    val likedArtists: Set<String>,
    /** Артисты, у которых есть хотя бы один дизлайк. */
    val dislikedArtists: Set<String>,
    val likedCount: Int,
    val dislikedCount: Int,
) {

    val isEmpty: Boolean
        get() = byTrackId.isEmpty() && byWaveKey.isEmpty()

    /** Оценка трека: сначала точный id (стабилен для стриминга), затем волновой ключ. */
    fun ratingOf(track: Track): Int =
        byTrackId[track.id] ?: byWaveKey[WaveEngine.waveKey(track)] ?: 0

    fun ratingOfWaveKey(waveKey: String): Int = byWaveKey[waveKey] ?: 0

    fun isLiked(track: Track): Boolean = ratingOf(track) > 0

    fun isDisliked(track: Track): Boolean = ratingOf(track) < 0

    /** Суммарный вкус к исполнителю: лайки плюс, дизлайки минус, свежесть затухает. */
    fun artistScore(artist: String): Double =
        artistScores[normalizeArtist(artist)] ?: 0.0

    /** Вкус к жанру: совпадение по подстроке, потому что жанры приходят разными строками. */
    fun genreScore(genre: String?): Double {
        val target = genre?.trim()?.lowercase().orEmpty()
        if (target.isEmpty()) return 0.0
        genreScores.entries.firstOrNull { (key, _) ->
            key.isNotBlank() && (target.contains(key) || key.contains(target))
        }?.let { return it.value }
        return 0.0
    }

    /**
     * Треки, которые больше не должны попадать в рекомендации.
     * Ключи в том же формате, что у [WaveEngine.dislikeKey] (`t:исполнитель|название`),
     * поэтому набор можно объединять с дизлайками [PlayHistoryStore].
     */
    fun dislikedWaveKeys(): Set<String> =
        byWaveKey.filterValues { it < 0 }.keys.mapTo(HashSet()) { WaveEngine.dislikeKeyOf(it) }

    companion object {

        val EMPTY: TrackRatingIndex = of(emptyList())

        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** Вкус к артисту затухает с полураспадом 180 дней: старые дизлайки слабее. */
        private const val HALF_LIFE_DAYS = 180.0

        /** Максимальный вклад одного трека в вкус артиста. */
        private const val ARTIST_WEIGHT = 0.6

        /** Вклад жанра слабее: жанр — грубый сигнал. */
        private const val GENRE_WEIGHT = 0.35

        fun of(
            entries: Collection<TrackRatingEntry>,
            nowMs: Long = System.currentTimeMillis(),
        ): TrackRatingIndex {
            if (entries.isEmpty()) {
                return TrackRatingIndex(
                    byTrackId = emptyMap(),
                    byWaveKey = emptyMap(),
                    artistScores = emptyMap(),
                    genreScores = emptyMap(),
                    likedArtists = emptySet(),
                    dislikedArtists = emptySet(),
                    likedCount = 0,
                    dislikedCount = 0,
                )
            }
            val byTrackId = HashMap<String, Int>(entries.size)
            val byWaveKey = HashMap<String, Int>(entries.size)
            val artistScores = HashMap<String, Double>()
            val genreScores = HashMap<String, Double>()
            val likedArtists = HashSet<String>()
            val dislikedArtists = HashSet<String>()
            var liked = 0
            var disliked = 0

            entries.forEach { entry ->
                val rating = entry.rating.coerceIn(-1, 1)
                if (rating == 0) return@forEach
                if (rating > 0) liked++ else disliked++
                if (entry.trackId.isNotBlank()) byTrackId[entry.trackId] = rating
                val waveKey = WaveEngine.waveKeyOf(entry.artist, entry.title)
                byWaveKey[waveKey] = rating

                val artist = normalizeArtist(entry.artist)
                if (artist.isNotBlank()) {
                    if (rating > 0) likedArtists += artist else dislikedArtists += artist
                    val ageDays = (nowMs - entry.ratedAtMs).coerceAtLeast(0L).toDouble() / DAY_MS
                    val decay = exp(-ageDays / HALF_LIFE_DAYS)
                    val delta = rating * ARTIST_WEIGHT * decay
                    artistScores[artist] = (artistScores[artist] ?: 0.0) + delta
                }
                val genre = normalizeGenre(entry.genre)
                if (genre.isNotBlank()) {
                    val ageDays = (nowMs - entry.ratedAtMs).coerceAtLeast(0L).toDouble() / DAY_MS
                    val decay = exp(-ageDays / HALF_LIFE_DAYS)
                    val delta = rating * GENRE_WEIGHT * decay
                    genreScores[genre] = (genreScores[genre] ?: 0.0) + delta
                }
            }

            return TrackRatingIndex(
                byTrackId = byTrackId,
                byWaveKey = byWaveKey,
                artistScores = artistScores.filterValues { it != 0.0 },
                genreScores = genreScores.filterValues { it != 0.0 },
                likedArtists = likedArtists,
                dislikedArtists = dislikedArtists,
                likedCount = liked,
                dislikedCount = disliked,
            )
        }

        private fun normalizeArtist(artist: String): String =
            artist.trim().lowercase().replace('ё', 'е')

        private fun normalizeGenre(genre: String?): String =
            genre?.trim()?.lowercase()?.replace('ё', 'е').orEmpty()
    }
}
