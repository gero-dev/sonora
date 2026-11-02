package app.sonora.data.wave

import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.core.settings.WaveSettings

/**
 * Сид радио разложен на признаки, которые не зависят от источника.
 *
 * Локальный файл, InnerTube-трек, yt-dlp и SoundCloud дают разный набор полей,
 * но у всех есть исполнитель/название, а после обогащения — жанр, настроение и
 * подсказки «похожих исполнителей». Из этого собираются поисковые запросы
 * и объяснения, поэтому радио работает для любого трека, а не только для
 * YouTube-видео с `videoId`.
 */
data class RadioSeed(
    val trackId: String,
    val artist: String,
    val title: String,
    val album: String?,
    val genres: List<String>,
    val similarArtists: List<String>,
    val mood: String?,
) {
    val artistKey: String get() = artist.trim().lowercase().replace('ё', 'е')
    val titleKey: String get() = title.trim().lowercase().replace('ё', 'е')

    /** Есть чем искать похожее? Иначе честно говорим «недостаточно данных». */
    val hasQueryData: Boolean
        get() = artistKey.isNotBlank() || titleKey.isNotBlank() || genres.isNotEmpty()
}

/**
 * Чистые правила радио по треку (без сети и Android):
 *
 *  * какие запросы отправить в InnerTube, чтобы получить «похожих»;
 *  * какие кандидаты вообще допустимы (не видео, не сид, не дизлайк);
 *  * как объяснить пользователю, почему трек в радио.
 *
 * Веса похожести живут в [WaveRanker]: он получает [WaveRankContext.radioSeed]
 * и поднимает тот же артист / соседей из [ArtistGraph] / тот же жанр /
 * похожие аудио-фичи.
 */
object RadioEngine {

    private const val MAX_QUERIES = 8
    private const val MAX_GENRE_QUERIES = 2
    private const val MAX_SIMILAR_ARTIST_QUERIES = 3

    private val LIST_SPLIT = Regex("""[,;/|&]+""")

    /** Разбирает признаки сида из модели трека. */
    fun seedOf(track: Track): RadioSeed {
        val genres = mutableListOf<String>()
        listOfNotNull(track.extra["genre"], track.extra["genres"])
            .flatMap { it.split(LIST_SPLIT) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .take(4)
            .forEach(genres::add)
        val similar = (track.extra["similarArtists"] ?: track.extra["similar_artists"])
            ?.split(LIST_SPLIT)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.take(6)
            .orEmpty()
        return RadioSeed(
            trackId = track.id,
            artist = track.artist.trim(),
            title = track.title.trim(),
            album = track.album?.takeIf { it.isNotBlank() },
            genres = genres,
            similarArtists = similar,
            mood = track.extra["mood"]?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * Запросы для онлайн-части радио. Порядок важен: сначала «радио» самого
     * исполнителя, затем похожие, жанр и запасные варианты.
     */
    fun queries(seed: RadioSeed, tuning: WaveSettings, limit: Int = MAX_QUERIES): List<String> {
        val result = LinkedHashSet<String>()
        fun add(raw: String?) {
            val query = raw?.trim().orEmpty()
            if (query.isNotEmpty()) result += query
        }

        val artist = seed.artist
        val title = seed.title
        if (artist.isNotBlank()) {
            add("$artist radio")
            add("$artist mix")
            add("$artist похожие исполнители")
            if (title.isNotBlank()) add("$artist $title")
        } else if (title.isNotBlank()) {
            add("$title radio")
        }
        seed.similarArtists.take(MAX_SIMILAR_ARTIST_QUERIES).forEach { similar ->
            add("$similar radio")
        }
        seed.genres.take(MAX_GENRE_QUERIES).forEach { genre -> add("$genre mix") }
        if (!seed.mood.isNullOrBlank()) add("${seed.mood} music mix")
        if (tuning.novelty >= 60) add("new music mix")
        if (tuning.novelty <= 30) add("greatest hits mix")
        add("music mix")
        return result.take(limit.coerceAtLeast(1))
    }

    /**
     * Жёсткие фильтры радио: только музыка, только играемое, не сид,
     * не дизлайкнутое. Дедупликация — по id и по «исполнитель|название».
     */
    fun filterCandidates(
        candidates: List<Track>,
        seed: RadioSeed,
        tuning: WaveSettings,
        dislikedKeys: Set<String> = emptySet(),
    ): List<Track> {
        if (candidates.isEmpty()) return emptyList()
        val seedKey = WaveEngine.waveKeyOf(seed.artist, seed.title)
        return WaveEngine.dedupe(candidates)
            .filter { track ->
                track.id != seed.trackId &&
                    WaveEngine.waveKey(track) != seedKey
            }
            .filter { track ->
                track.playable &&
                    !track.uri.isNullOrBlank() &&
                    !track.isVideoContent &&
                    track.source != Source.SOUNDCLOUD
            }
            .filter { track -> WaveEngine.isMusic(track, tuning) }
            .filterNot { track -> WaveEngine.isDisliked(track, dislikedKeys) }
    }

    /**
     * Локальный пул для радио: вся подходящая фонотека. Ранжирование само
     * поднимет исполнителя сида, соседей по графу и похожие аудио-фичи,
     * поэтому предварительно резать пул по артисту не нужно — это ломало бы
     * «новое» в радио.
     */
    fun localCandidates(
        seed: RadioSeed,
        library: List<Track>,
        tuning: WaveSettings,
        dislikedKeys: Set<String> = emptySet(),
    ): List<Track> {
        if (library.isEmpty()) return emptyList()
        return filterCandidates(library, seed, tuning, dislikedKeys)
    }
}
