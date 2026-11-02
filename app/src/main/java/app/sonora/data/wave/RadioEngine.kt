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
 *  * какие кандидаты вообще допустимы;
 *  * как объяснить пользователю, почему трек в радио.
 *
 * Радио фильтрует **строже «Моей волны»**: короткие клипы, длинные сборники,
 * концерты, подкасты, ток-шоу и мем-нарезки не попадают в станцию, а результаты
 * обычного поиска YouTube проверяются на соответствие исполнителю сида или
 * «похожим» исполнителям. Иначе на запрос «<артист> radio» YouTube легко
 * возвращает South Park/Family Guy/ток-шоу вместо музыки.
 *
 * Веса похожести живут в [WaveRanker]: он получает [WaveRankContext.radioSeed]
 * и поднимает тот же артист / соседей из [ArtistGraph] / тот же жанр /
 * похожие аудио-фичи.
 */
object RadioEngine {

    private const val MAX_QUERIES = 8
    private const val MAX_GENRE_QUERIES = 2
    private const val MAX_SIMILAR_ARTIST_QUERIES = 3

    /** Радио строже волны: 45 секунд — 10 минут, иначе клип/сборник/подкаст. */
    const val MIN_DURATION_MS = 45_000L
    const val MAX_DURATION_MS = 600_000L

    private val LIST_SPLIT = Regex("""[,;/|&]+""")
    private val ARTIST_SPLIT = Regex("""[^\p{L}\p{N}]+""")

    /**
     * Служебные слова в имени артиста: по ним нельзя считать кандидата «тем же
     * артистом» — иначе Family Guy пройдёт проверку для артиста с «Family».
     */
    private val STOP_ARTIST_TOKENS = setOf(
        "family", "music", "official", "records", "record", "topic", "vevo",
        "channel", "band", "group", "project", "collective", "show", "radio",
        "sound", "audio", "studio", "label", "prod", "productions",
    )

    /** Маркеры нежелательного контента в названии — строже, чем у волны. */
    private val BLOCKED_TITLE_MARKERS = listOf(
        // видео/клипы и не-музыка
        "official video", "music video", "lyric video", "visualizer", "full video",
        "video clip", "клип", "видеоклип",
        // концерты/стримы
        "live at", "full concert", "концерт", "live stream", "livestream",
        "прямой эфир", "прямая трансляция",
        // разговорное
        "podcast", "подкаст", "interview", "интервью", "talks", "talk show",
        "ток-шоу", "ток шоу", "q&a", "вопрос ответ",
        // реакции/игры/обзоры
        "reaction", "реакция", "gameplay", "летсплей", "walkthrough",
        "прохождение", "unboxing", "распаковка", "review", "обзор", "vlog",
        "prank", "challenge", "челлендж",
        // детский/танцевальный контент и туториалы
        "for kids", "kids song", "kids video", "nursery", "детская песня",
        "детские песни", "cartoon", "мультик", "dance tutorial", "dance for kids",
        "choreography", "tutorial", "lesson", "how to", "karaoke", "караоке",
        // сборники/долгие миксы
        "compilation", "full album", "полный альбом", "megamix", "mega mix",
        "nonstop", "non-stop", "1 hour", "10 hours", "hours of",
        // трейлеры, мемы и «шоу», которые чаще всего подмешивает YouTube
        "trailer", "teaser", "тизер", "трейлер", "behind the scenes", "making of",
        "south park", "family guy", "rick and morty", "simpsons", "meme", "memes",
    )

    /** «Каналы-сборники» и не-музыкальные бренды: такие кандидаты не нужны. */
    private val BLOCKED_ARTIST_MARKERS = listOf(
        "south park", "family guy", "comedy central", "smosh", "collegehumor",
        "funny or die", "screen junkies", "watchmojo", "top 10", "music video",
        "vevo official",
    )

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
     *
     * [genericFallback] = false отключает общие запросы вроде «music mix»: для
     * сида с videoId они не нужны и только засоряют выдачу.
     */
    fun queries(
        seed: RadioSeed,
        tuning: WaveSettings,
        limit: Int = MAX_QUERIES,
        genericFallback: Boolean = true,
    ): List<String> {
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
        if (genericFallback) {
            if (tuning.novelty >= 60) add("new music mix")
            if (tuning.novelty <= 30) add("greatest hits mix")
            add("music mix")
        }
        return result.take(limit.coerceAtLeast(1))
    }

    /**
     * Запросы только «от артиста/похожих/жанра» — без общих подборок.
     * Именно они допустимы для сида с videoId: остальное найдёт related.
     */
    fun artistQueries(seed: RadioSeed, tuning: WaveSettings, limit: Int = 4): List<String> =
        queries(seed, tuning, limit = limit, genericFallback = false)

    /**
     * Жёсткие фильтры радио: только музыка, только играемое, не сид,
     * не дизлайкнутое, разумная длительность, без клипов/шоу/сборников.
     *
     * @param trustedIds треки из InnerTube related по видео сида: им доверяем
     *   как «похожим» и не требуем совпадения исполнителя;
     * @param relatedArtists «похожие исполнители» (из related или графа):
     *   результаты обычного поиска должны быть из этого окружения;
     * @param strictRelevance включить проверку исполнителя для поисковой выдачи.
     */
    fun filterCandidates(
        candidates: List<Track>,
        seed: RadioSeed,
        tuning: WaveSettings,
        dislikedKeys: Set<String> = emptySet(),
        trustedIds: Set<String> = emptySet(),
        relatedArtists: Set<String> = emptySet(),
        strictRelevance: Boolean = false,
    ): List<Track> {
        if (candidates.isEmpty()) return emptyList()
        val seedKey = WaveEngine.waveKeyOf(seed.artist, seed.title)
        val relatedKeys = relatedArtists
            .map { normalizeArtist(it) }
            .filter { it.isNotBlank() }
            .toSet()
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
            .filter { track -> durationAllowed(track) }
            .filter { track -> !hasBlockedText(track) }
            .filterNot { track -> WaveEngine.isDisliked(track, dislikedKeys) }
            .filter { track ->
                !strictRelevance ||
                    isRelevantCandidate(track, seed, trustedIds, relatedKeys)
            }
    }

    /** Локальный пул для радио: вся подходящая фонотека. */
    fun localCandidates(
        seed: RadioSeed,
        library: List<Track>,
        tuning: WaveSettings,
        dislikedKeys: Set<String> = emptySet(),
    ): List<Track> {
        if (library.isEmpty()) return emptyList()
        return filterCandidates(library, seed, tuning, dislikedKeys)
    }

    /** Длительность в диапазоне радио; неизвестную не отбрасываем (не все API её дают). */
    fun durationAllowed(track: Track): Boolean {
        if (track.durationMs <= 0L) return true
        return track.durationMs in MIN_DURATION_MS..MAX_DURATION_MS
    }

    /** Название без клипов/шоу/сборников; проверка по подстроке, как в волне. */
    fun hasBlockedText(track: Track): Boolean {
        val title = track.title.lowercase().replace('ё', 'е')
        if (BLOCKED_TITLE_MARKERS.any { title.contains(it) }) return true
        val artist = normalizeArtist(track.artist)
        return BLOCKED_ARTIST_MARKERS.any { artist.contains(it) }
    }

    /**
     * Кандидат из поиска принадлежит окружению сида? Доверенные related-треки
     * проходят без проверки исполнителя; для остальных требуем совпадения
     * с артистом сида или «похожим» артистом.
     */
    fun isRelevantCandidate(
        track: Track,
        seed: RadioSeed,
        trustedIds: Set<String> = emptySet(),
        relatedArtists: Set<String> = emptySet(),
    ): Boolean {
        if (track.id in trustedIds) return true
        val artist = normalizeArtist(track.artist)
        if (artist.isBlank()) return false
        val seedArtist = normalizeArtist(seed.artist)
        if (seedArtist.isNotBlank()) {
            if (artist == seedArtist ||
                artist.contains(seedArtist) ||
                seedArtist.contains(artist)
            ) {
                return true
            }
            // Одно редкое слово из имени артиста: «Maxians Family» → «maxians».
            val tokens = seedArtist.split(ARTIST_SPLIT)
                .filter { it.length >= 6 && it !in STOP_ARTIST_TOKENS }
            if (tokens.any { artist.contains(it) }) return true
        }
        val relatedKeys = relatedArtists.map { normalizeArtist(it) }.filter { it.isNotBlank() }
        return relatedKeys.any { related ->
            artist == related || artist.contains(related) || related.contains(artist)
        }
    }

    private fun normalizeArtist(value: String): String =
        value.trim().lowercase().replace('ё', 'е')
}
