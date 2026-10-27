package app.sonora.data.metadata

import android.os.SystemClock
import app.sonora.core.model.Track
import app.sonora.core.model.TrackMetadata
import app.sonora.core.net.Http
import app.sonora.core.net.HttpException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder

/**
 * Онлайн-метаданные для локальных треков: MusicBrainz + Cover Art Archive,
 * LRCLIB, iTunes/Apple Music, Deezer, TheAudioDB, Wikipedia и InnerTube.
 *
 * - **MusicBrainz** — альбом, год, MBID записи и канонический artist-credit.
 * - **Cover Art Archive** — обложка по release id (может отдать 404).
 * - **iTunes Search API** — обложка 1000×1000, альбом, год; ключ не нужен.
 * - **Deezer API** — обложка до 1000×1000, альбом и фото исполнителя.
 * - **TheAudioDB** — обложка альбома, фото исполнителя, биография и жанр.
 * - **Wikipedia REST** — фото и краткая биография исполнителя (fallback).
 * - **InnerTube** — поиск YouTube Music, если каталоги не дали обложку/артиста.
 * - **LRCLIB** — текст песни (обычный или LRC). Ключ не нужен, UA рекомендуется.
 *
 * Провайдеры изолированы: падение одного не мешает остальным. Обложка
 * выбирается по качеству и надёжности источника; фото исполнителя хранится
 * отдельно от обложки альбома. Ничего не скачивается в файлы — решение
 * принимает [app.sonora.data.library.LibraryRepository].
 */
class MetadataService(
    private val cache: MetadataCache,
    /**
     * Необязательный fallback через поиск InnerTube. Передаётся из AppGraph,
     * чтобы модуль метаданных не зависел от YouTubeSource напрямую.
     */
    private val innerTubeSearch: (suspend (String) -> List<Track>)? = null,
) : MetadataLookup {

    /** Сериализует запросы к MusicBrainz: их API разрешает ~1 rps на клиента. */
    private val rateMutex = Mutex()

    /** Время (elapsedRealtime) последнего запроса к MusicBrainz; 0 — запросов ещё не было. */
    private var lastMusicBrainzRequestAt = 0L

    override suspend fun lookup(track: Track): TrackMetadata? {
        val rawArtist = track.artist
        val artistIsUnknown = ArtistNames.isPlaceholder(rawArtist)
        // Файлы без тегов часто называются «Artist - Title»: без этого даже
        // MusicBrainz не найдёт трек при исполнителе «Неизвестный исполнитель».
        val nameParts = if (artistIsUnknown) track.title.split(" - ", limit = 2) else emptyList()
        val titleSource = if (nameParts.size == 2) nameParts[1] else track.title
        val artistSource = if (nameParts.size == 2) nameParts[0] else rawArtist
        val title = normalizeTitle(titleSource)
        if (title.isBlank()) return null
        val artist = normalizeArtist(artistSource)

        val cacheKey = CachedMetadata.key(artist, title)
        // 1) Постоянный кэш: если песня уже искалась и запись свежая — в сеть не идём.
        val cached = cache.get(cacheKey)
        if (cached != null && cached.isFresh && cached.schemaVersion >= CACHE_SCHEMA_VERSION) {
            return if (cached.failed) null else cached.toTrackMetadata()
        }

        // 2) Сеть: основные провайдеры независимы и идут параллельно. Ошибка
        // одного не убивает остальные, а обложку можно взять из нескольких
        // каталогов. Важно различать «сервис ответил, данных нет» и «запрос
        // не дошёл»: сетевые сбои нельзя кэшировать как «ничего не найдено».
        return coroutineScope {
            val brainzResult = async {
                runCatching { withTimeout(PROVIDER_TIMEOUT_MS) { lookupMusicBrainz(title, artist, track.durationMs) } }
            }
            val lyricsResult = async {
                runCatching { withTimeout(PROVIDER_TIMEOUT_MS) { lookupLyrics(title, artist, track.durationMs) } }
            }
            val itunesResult = async {
                runCatching {
                    withTimeout(PROVIDER_TIMEOUT_MS) {
                        lookupItunes(title, artist, track.durationMs, track.album)
                    }
                }
            }
            val deezerResult = async {
                runCatching {
                    withTimeout(PROVIDER_TIMEOUT_MS) {
                        lookupDeezer(title, artist, track.durationMs, track.album)
                    }
                }
            }
            val audioDbResult = async {
                runCatching { withTimeout(PROVIDER_TIMEOUT_MS) { lookupTheAudioDb(title, artist, track.durationMs) } }
            }
            val brainz = brainzResult.await().getOrNull()
            val lyrics = lyricsResult.await().getOrNull()
            val itunes = itunesResult.await().getOrNull()
            val deezer = deezerResult.await().getOrNull()
            val audioDb = audioDbResult.await().getOrNull()

            val needCover = listOf(brainz?.coverUrl, itunes?.coverUrl, deezer?.coverUrl, audioDb?.coverUrl)
                .all { it.isNullOrBlank() }
            val needArtist = listOf(
                brainz?.artist, itunes?.artist, deezer?.artist, audioDb?.artist,
            ).all { it.isNullOrBlank() }
            val needArtistImage = listOf(audioDb?.artistImageUrl, deezer?.artistImageUrl)
                .all { it.isNullOrBlank() }
            val needBio = audioDb?.artistBio.isNullOrBlank()
            // Если трек не нашёлся по названию, обложку альбома всё ещё можно взять
            // из iTunes по исполнителю и альбому — именно так находятся обложки
            // у треков с «грязными» тегами.
            val albumCoverDeferred = if (needCover && !track.album.isNullOrBlank()) {
                val album = track.album
                async {
                    runCatching {
                        withTimeout(PROVIDER_TIMEOUT_MS) { lookupItunesAlbumCover(artist, album) }
                    }
                }
            } else {
                null
            }

            val innerTubeDeferred = if (
                innerTubeSearch != null && (needCover || needArtist)
            ) {
                async { runCatching { withTimeout(PROVIDER_TIMEOUT_MS) { lookupInnerTube(title, artist, track.durationMs) } } }
            } else {
                null
            }
            val wikiDeferred = if (
                artist.isNotBlank() && (needArtistImage || needBio)
            ) {
                async { runCatching { withTimeout(WIKIPEDIA_TIMEOUT_MS) { lookupWikipediaArtist(artist) } } }
            } else {
                null
            }
            val innerTube = innerTubeDeferred?.await()?.getOrNull()
            val wiki = wikiDeferred?.await()?.getOrNull()

            val result = merge(
                brainz = brainz,
                lyrics = lyrics,
                itunes = itunes,
                deezer = deezer,
                audioDb = audioDb,
                innerTube = innerTube,
                wikipedia = wiki,
                fallbackCover = albumCoverDeferred?.await()?.getOrNull(),
            )
            val providersResponded = brainzResult.await().isSuccess ||
                lyricsResult.await().isSuccess ||
                itunesResult.await().isSuccess ||
                deezerResult.await().isSuccess ||
                audioDbResult.await().isSuccess ||
                innerTubeDeferred?.await()?.isSuccess == true ||
                wikiDeferred?.await()?.isSuccess == true
            // 3) Сохраняем сразу и навсегда — но только если хотя бы один провайдер ответил.
            if (providersResponded) {
                runCatching { cache.put(cacheKey, result) }
            }
            result
        }
    }

    override fun isComplete(track: Track): Boolean =
        !track.album.isNullOrBlank() &&
            !track.artworkUrl.isNullOrBlank() &&
            !track.lyrics.isNullOrBlank()

    // ---------------------------------------------------------------- MusicBrainz

    /**
     * Поиск записи в MusicBrainz. Если исполнитель неизвестен, ограничение по
     * artist не добавляется — ищем только по названию.
     */
    private suspend fun lookupMusicBrainz(
        title: String,
        artist: String,
        durationMs: Long = 0L,
    ): BrainzInfo? {
        val query = buildString {
            append("recording:\"")
            append(escapeLucene(title))
            append('"')
            if (artist.isNotBlank()) {
                append(" AND artist:\"")
                append(escapeLucene(artist))
                append('"')
            }
        }
        val url = "https://musicbrainz.org/ws/2/recording" +
            "?query=${encode(query)}&fmt=json&limit=5"
        val body = musicBrainzGet(url)
        val root = Http.parseJsonObject(body)
        val recordings = root["recordings"] as? JsonArray ?: return null
        val recording = recordings
            .mapNotNull { it as? JsonObject }
            .maxByOrNull { candidate ->
                val score = candidate.text("score")?.toIntOrNull() ?: 0
                val length = candidate.text("length")?.toLongOrNull() ?: 0L
                val mismatchSeconds = if (durationMs > 0L && length > 0L) {
                    kotlin.math.abs(length - durationMs) / 1_000L
                } else {
                    0L
                }
                // Голос MusicBrainz важнее длительности, но длительность отсекает
                // live/remix-версии с тем же названием.
                score * 1_000L - mismatchSeconds
            }
            ?: return null

        val recordingId = recording.text("id")
        val recordingTitle = recording.text("title")
        val artistCredit = (recording["artist-credit"] as? JsonArray)
            ?.mapNotNull { element ->
                val credit = element as? JsonObject ?: return@mapNotNull null
                credit.text("name") ?: (credit["artist"] as? JsonObject)?.text("name")
            }
            ?.map(ArtistNames::clean)
            ?.filter { it.isNotBlank() }
            ?.joinToString(", ")
        val releases = recording["releases"] as? JsonArray
        // Первый release в ответе MusicBrainz часто оказывается сборником
        // («…50th Anniversary Collection»), а не оригинальным альбомом.
        // Выбираем официальный полноформатный альбом с самой ранней датой.
        val release = releases
            ?.mapNotNull { it as? JsonObject }
            ?.maxByOrNull { candidate -> releaseScore(candidate) }
        val album = release?.text("title")
        val date = recording.text("first-release-date") ?: release?.text("date")
        val year = date?.trim()?.take(4)?.toIntOrNull()
        val releaseId = release?.text("id")
        val coverUrl = releaseId?.let {
            "https://coverartarchive.org/release/${encode(it)}/front-500"
        }

        if (recordingId == null && album == null && year == null && coverUrl == null &&
            recordingTitle == null && artistCredit == null
        ) {
            return null
        }
        return BrainzInfo(
            album = album,
            year = year,
            coverUrl = coverUrl,
            recordingId = recordingId,
            artist = artistCredit,
            title = recordingTitle,
        )
    }

    /**
     * Оценка релиза MusicBrainz для выбора «правильного» альбома:
     * официальный студийный альбом важнее сингла, сборники и концертники
     * штрафуются, более ранний год получает небольшой бонус.
     */
    private fun releaseScore(release: JsonObject): Int {
        var score = 0
        if (release.text("status")?.equals("Official", ignoreCase = true) == true) score += 5
        val group = release["release-group"] as? JsonObject
        when (group?.text("primary-type")) {
            "Album" -> score += 8
            "EP" -> score += 4
            "Single" -> score += 3
            else -> {}
        }
        val secondary = (group?.get("secondary-types") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: emptyList()
        if (secondary.any { it.equals("Compilation", true) || it.equals("Live", true) }) {
            score -= 10
        }
        val year = release.text("date")?.take(4)?.toIntOrNull()
        if (year != null && year in 1900..2100) {
            // Чем раньше релиз, тем выше шанс, что это оригинальный альбом.
            score += (2100 - year) / 10
        }
        return score
    }

    /**
     * GET с рейт-лимитом MusicBrainz. На 503 — одна повторная попытка через
     * 1.5 с (API просит подождать при перегрузке). Mutex держим на время
     * запроса: так 1 rps соблюдается глобально, а не на корутину.
     */
    private suspend fun musicBrainzGet(url: String): String = rateMutex.withLock {
        if (lastMusicBrainzRequestAt > 0L) {
            val elapsed = SystemClock.elapsedRealtime() - lastMusicBrainzRequestAt
            if (elapsed < MIN_REQUEST_INTERVAL_MS) {
                delay(MIN_REQUEST_INTERVAL_MS - elapsed)
            }
        }
        try {
            try {
                Http.get(url, MUSICBRAINZ_HEADERS)
            } catch (error: HttpException) {
                if (error.code == 503) {
                    delay(RETRY_AFTER_503_MS)
                    Http.get(url, MUSICBRAINZ_HEADERS)
                } else {
                    throw error
                }
            }
        } finally {
            lastMusicBrainzRequestAt = SystemClock.elapsedRealtime()
        }
    }

    // ---------------------------------------------------------------- каталоги обложек

    /**
     * Обложка, альбом, год и исполнитель из iTunes Search API. Ключ не нужен,
     * отдаёт готовые ссылки на обложку; запрашиваем максимальный размер 1000 px.
     *
     * Теги у локальных файлов часто «грязные» (лишние пометки, другой порядок
     * слов, отсутствие исполнителя), поэтому перебираем несколько вариантов
     * запроса и выбираем лучшего кандидата по очкам: название + исполнитель +
     * длительность + альбом + наличие обложки. Именно это раньше отсекало
     * большинство обложек строгим фильтром.
     */
    private suspend fun lookupItunes(
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
    ): ProviderData? {
        val variants = searchVariants(title, artist, album)
        var best: JsonObject? = null
        var bestScore = 0.0
        for (term in variants) {
            val url = "https://itunes.apple.com/search" +
                "?term=${encode(term)}&entity=song&limit=25"
            val root = runCatching {
                Http.parseJsonObject(Http.get(url, JSON_HEADERS))
            }.getOrNull() ?: continue
            val results = root["results"] as? JsonArray ?: continue
            for (candidate in results.mapNotNull { it as? JsonObject }) {
                val name = candidate.text("trackName") ?: continue
                if (!sameTitle(name, title)) continue
                val artistMatch = sameArtist(candidate.text("artistName"), artist)
                val length = candidate.text("trackTimeMillis")?.toLongOrNull() ?: 0L
                val timeMatch = durationMatches(length, durationMs)
                // Название совпало, плюс хотя бы один признак записи (исполнитель
                // или длительность) — кандидат годится, дальше решают очки.
                if (!artistMatch && !timeMatch) continue
                var score = 1.0
                if (artistMatch) score += 2.0
                if (!album.isNullOrBlank() &&
                    sameTitle(candidate.text("collectionName").orEmpty(), album)
                ) {
                    score += 1.5
                }
                if (durationMs > 0L && length > 0L) {
                    score -= kotlin.math.abs(length - durationMs) / 60_000.0
                }
                if (!candidate.text("artworkUrl100").isNullOrBlank()) score += 1.0
                if (score > bestScore) {
                    bestScore = score
                    best = candidate
                }
            }
            if (bestScore >= 4.5) break
        }
        val track = best ?: return null
        val artwork = upgradeItunesArtwork(track.text("artworkUrl100"))
        val albumName = track.text("collectionName")
        val year = track.text("releaseDate")?.take(4)?.toIntOrNull()
        val artistName = track.text("artistName")
        if (artwork == null && albumName == null && year == null && artistName == null) return null
        return ProviderData(
            coverUrl = artwork,
            album = albumName,
            year = year,
            artist = artistName,
            source = "iTunes",
        )
    }

    /**
     * Обложка, альбом и фото исполнителя из публичного Deezer API (без ключа).
     * Deezer отдаёт обложку до 1000×1000 и `picture_xl` исполнителя.
     * Как и iTunes, перебираем варианты запроса и мягко оцениваем кандидатов.
     */
    private suspend fun lookupDeezer(
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
    ): ProviderData? {
        val cleanTitle = title.replace("\"", " ")
        val cleanArtist = artist.replace("\"", " ")
        val queries = buildList {
            if (artist.isNotBlank()) {
                add("track:\"$cleanTitle\" artist:\"$cleanArtist\"")
                add("$artist $title")
                if (!album.isNullOrBlank()) add("$artist $album")
            } else {
                add(title)
            }
        }.map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .take(3)
        var best: JsonObject? = null
        var bestScore = 0.0
        for (query in queries) {
            val url = "https://api.deezer.com/search?q=${encode(query)}&limit=25"
            val root = runCatching {
                Http.parseJsonObject(Http.get(url, JSON_HEADERS))
            }.getOrNull() ?: continue
            val items = root["data"] as? JsonArray ?: continue
            for (candidate in items.mapNotNull { it as? JsonObject }) {
                val name = candidate.text("title") ?: continue
                if (!sameTitle(name, title)) continue
                val candidateArtist = (candidate["artist"] as? JsonObject)?.text("name")
                val artistMatch = sameArtist(candidateArtist, artist)
                val length = candidate.text("duration")?.toLongOrNull()?.times(1_000L) ?: 0L
                val timeMatch = durationMatches(length, durationMs)
                if (!artistMatch && !timeMatch) continue
                var score = 1.0
                if (artistMatch) score += 2.0
                val albumObject = candidate["album"] as? JsonObject
                if (!album.isNullOrBlank() &&
                    sameTitle(albumObject?.text("title").orEmpty(), album)
                ) {
                    score += 1.5
                }
                if (durationMs > 0L && length > 0L) {
                    score -= kotlin.math.abs(length - durationMs) / 60_000.0
                }
                if (!albumObject?.text("cover_xl").isNullOrBlank()) score += 1.0
                if (score > bestScore) {
                    bestScore = score
                    best = candidate
                }
            }
            if (bestScore >= 4.5) break
        }
        val track = best ?: return null
        val albumObject = track["album"] as? JsonObject
        val artistObject = track["artist"] as? JsonObject
        val cover = albumObject?.text("cover_xl")
            ?: albumObject?.text("cover_big")
        val albumName = albumObject?.text("title")
        val year = albumObject?.text("release_date")?.take(4)?.toIntOrNull()
        val artistName = artistObject?.text("name")
        val artistId = artistObject?.text("id")
        val artistImage = artistId?.let { id ->
            runCatching { lookupDeezerArtistImage(id) }.getOrNull()
        }
        if (cover == null && albumName == null && year == null && artistName == null &&
            artistImage == null
        ) {
            return null
        }
        return ProviderData(
            coverUrl = cover,
            album = albumName,
            year = year,
            artist = artistName,
            artistImageUrl = artistImage,
            source = "Deezer",
        )
    }

    /** Варианты поискового запроса: чем «грязнее» теги, тем важнее перебор. */
    private fun searchVariants(title: String, artist: String, album: String?): List<String> =
        buildList {
            if (artist.isNotBlank()) {
                add("$artist $title")
                add("$title $artist")
            } else {
                add(title)
            }
            if (!album.isNullOrBlank()) add("$artist $album".trim())
            // В title из имени файла иногда попадает «Artist - Title» целиком.
            title.split(" - ").lastOrNull()?.trim()
                ?.takeIf { it.isNotBlank() && it != title }
                ?.let { add(it) }
        }.map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .take(4)

    private fun upgradeItunesArtwork(url: String?): String? = url
        ?.replace("100x100bb", "1000x1000bb")
        ?.replace("100x100", "1000x1000")

    /** Обложка альбома из iTunes по исполнителю и альбому (entity=album). */
    private suspend fun lookupItunesAlbumCover(artist: String, album: String): String? {
        val term = listOf(artist, album).filter { it.isNotBlank() }.joinToString(" ")
        if (term.isBlank()) return null
        val url = "https://itunes.apple.com/search" +
            "?term=${encode(term)}&entity=album&limit=10"
        val root = runCatching {
            Http.parseJsonObject(Http.get(url, JSON_HEADERS))
        }.getOrNull() ?: return null
        val results = root["results"] as? JsonArray ?: return null
        val best = results
            .mapNotNull { it as? JsonObject }
            .filter { candidate ->
                val name = candidate.text("collectionName") ?: return@filter false
                sameTitle(name, album) && sameArtist(candidate.text("artistName"), artist)
            }
            .firstOrNull { !it.text("artworkUrl100").isNullOrBlank() }
        return upgradeItunesArtwork(best?.text("artworkUrl100"))
    }

    /** `picture_xl` исполнителя Deezer; ошибка/404 не ломает основной поиск. */
    private suspend fun lookupDeezerArtistImage(artistId: String): String? {
        if (artistId.isBlank()) return null
        val root = Http.parseJsonObject(
            Http.get("https://api.deezer.com/artist/${encode(artistId)}", JSON_HEADERS),
        )
        return root.text("picture_xl")
            ?: root.text("picture_big")
            ?: root.text("picture_medium")
    }

    /**
     * TheAudioDB: обложка альбома, фото исполнителя, биография и жанр.
     * Используется бесплатный тестовый ключ `2`; при недоступности сервиса
     * провайдер просто не попадает в выдачу.
     */
    /** TheAudioDB иногда блокирует тестовый ключ; после 403 не дёргаем его процесс. */
    @Volatile
    private var audioDbDisabled = false

    private suspend fun lookupTheAudioDb(
        title: String,
        artist: String,
        durationMs: Long,
    ): ProviderData? {
        if (artist.isBlank() || audioDbDisabled) return null
        val trackUrl = "https://www.theaudiodb.com/api/v1/json/2/searchtrack.php" +
            "?s=${encode(artist)}&t=${encode(title)}"
        val trackRoot = audioDbGet(trackUrl)
        val tracks = trackRoot?.get("track") as? JsonArray
        val best = tracks
            ?.mapNotNull { it as? JsonObject }
            ?.filter { candidate ->
                val name = candidate.text("strTrack") ?: return@filter false
                sameTitle(name, title) &&
                    sameArtist(candidate.text("strArtist"), artist) &&
                    durationMatches(audioDbDurationMs(candidate), durationMs)
            }
            ?.maxByOrNull { candidate ->
                val length = audioDbDurationMs(candidate) ?: 0L
                if (durationMs > 0L && length > 0L) {
                    -kotlin.math.abs(length - durationMs) / 1_000L
                } else {
                    0L
                }
            }

        val artistData = runCatching { lookupTheAudioDbArtist(artist) }.getOrNull()
        if (best == null && artistData == null) return null
        val album = best?.text("strAlbum")
        val year = best?.text("intYearReleased")?.toIntOrNull()
        val cover = best?.text("strAlbumThumb")
            ?: best?.text("strTrackThumb")
        val genre = best?.text("strGenre") ?: artistData?.genre
        val artistName = best?.text("strArtist") ?: artistData?.name
        val artistImage = best?.text("strArtistThumb")
            ?: best?.text("strArtistFanart")
            ?: artistData?.imageUrl
        val bio = artistData?.bio ?: cleanText(best?.text("strDescriptionEN"))
        return ProviderData(
            coverUrl = cover,
            album = album,
            year = year,
            artist = artistName,
            artistImageUrl = artistImage,
            artistBio = bio,
            genre = genre,
            source = "TheAudioDB",
        )
    }

    private suspend fun lookupTheAudioDbArtist(artist: String): ArtistInfo? {
        if (audioDbDisabled) return null
        val url = "https://www.theaudiodb.com/api/v1/json/2/searchartist.php" +
            "?s=${encode(artist)}"
        val root = audioDbGet(url) ?: return null
        val artists = root["artists"] as? JsonArray ?: return null
        val best = artists
            .mapNotNull { it as? JsonObject }
            .firstOrNull { sameArtist(it.text("strArtist"), artist) }
            ?: return null
        val image = best.text("strArtistThumb")
            ?: best.text("strArtistFanart")
        val bio = cleanText(best.text("strBiographyEN"))
            ?: cleanText(best.text("strBiography"))
        val genre = best.text("strGenre")
        if (image == null && bio == null && genre == null) return null
        return ArtistInfo(
            name = best.text("strArtist"),
            imageUrl = image,
            bio = bio,
            genre = genre,
            source = "TheAudioDB",
        )
    }

    private suspend fun audioDbGet(url: String): JsonObject? {
        if (audioDbDisabled) return null
        return try {
            Http.parseJsonObject(Http.get(url, JSON_HEADERS))
        } catch (error: app.sonora.core.net.HttpException) {
            if (error.code == 403 || error.code == 401) audioDbDisabled = true
            null
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            null
        }
    }

    /**
     * Wikipedia REST: фото и краткая биография исполнителя. Прямой запрос по
     * имени часто попадает на другое значение («Nirvana» — религиозное
     * понятие), поэтому результат проверяется на музыкальные признаки, а при
     * неудаче используется поиск «<artist> band/singer/musician».
     */
    private suspend fun lookupWikipediaArtist(artist: String): ArtistInfo? {
        if (artist.isBlank()) return null
        return lookupWikipediaArtistOnHost(
            host = "en.wikipedia.org",
            artist = artist,
            queries = listOf("$artist band", "$artist singer", "$artist musician"),
        ) ?: lookupWikipediaArtistOnHost(
            host = "ru.wikipedia.org",
            artist = artist,
            queries = listOf("$artist группа", "$artist певец", "$artist музыкант"),
        )
    }

    private suspend fun lookupWikipediaArtistOnHost(
        host: String,
        artist: String,
        queries: List<String>,
    ): ArtistInfo? {
        directWikiSummary(host, artist)
            ?.takeIf(::looksLikeMusician)
            ?.let { return it }
        for (query in queries) {
            val searchUrl = "https://$host/w/api.php" +
                "?action=query&list=search&srsearch=${encode(query)}" +
                "&format=json&utf8=1&srlimit=3"
            val searchRoot = runCatching {
                Http.parseJsonObject(Http.get(searchUrl, WIKIPEDIA_HEADERS))
            }.getOrNull() ?: continue
            val titles = ((searchRoot["query"] as? JsonObject)
                ?.get("search") as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.text("title") }
                .orEmpty()
            for (title in titles) {
                val info = directWikiSummary(host, title) ?: continue
                if (looksLikeMusician(info)) return info
            }
        }
        return null
    }

    private fun looksLikeMusician(info: ArtistInfo): Boolean {
        val text = buildString {
            append(info.bio.orEmpty())
            append(' ')
            append(info.name.orEmpty())
        }.lowercase()
        return MUSIC_WORDS.any { word ->
            Regex("""(?i)\b${Regex.escape(word)}\b""").containsMatchIn(text)
        }
    }

    private suspend fun directWikiSummary(host: String, title: String): ArtistInfo? {
        val url = "https://$host/api/rest_v1/page/summary/${encode(title)}"
        val root = runCatching {
            Http.parseJsonObject(Http.get(url, WIKIPEDIA_HEADERS))
        }.getOrNull() ?: return null
        if (root.text("type") == "disambiguation") return null
        val image = (root["originalimage"] as? JsonObject)?.text("source")
            ?: (root["thumbnail"] as? JsonObject)?.text("source")
        val bio = cleanText(root.text("extract"))
        val description = root.text("description")
        if (image == null && bio == null) return null
        return ArtistInfo(
            imageUrl = image,
            bio = bio ?: description,
            source = "Wikipedia",
        )
    }

    /**
     * InnerTube-поиск как fallback: если каталоги не дали обложку или
     * исполнителя, ищем трек в YouTube Music и берём его метаданные.
     */
    private suspend fun lookupInnerTube(
        title: String,
        artist: String,
        durationMs: Long,
    ): ProviderData? {
        val search = innerTubeSearch ?: return null
        val query = listOfNotNull(artist.takeIf { it.isNotBlank() }, title)
            .joinToString(" ")
            .ifBlank { title }
        val candidates = runCatching { search(query) }.getOrDefault(emptyList())
        val best = candidates
            .asSequence()
            .filter { it.playable }
            .maxByOrNull { candidate ->
                var score = 0
                if (sameTitle(candidate.title, title)) score += 3
                if (artist.isNotBlank() && sameArtist(candidate.artist, artist)) score += 2
                if (durationMs > 0L && candidate.durationMs > 0L &&
                    kotlin.math.abs(candidate.durationMs - durationMs) <= 10_000L
                ) {
                    score++
                }
                score
            }
            ?.takeIf { candidate ->
                sameTitle(candidate.title, title) ||
                    (artist.isNotBlank() && sameArtist(candidate.artist, artist))
            }
            ?: return null
        return ProviderData(
            coverUrl = upscaleImageUrl(best.artworkUrl),
            album = best.album,
            artist = ArtistNames.clean(best.artist).takeIf { it.isNotBlank() },
            source = "InnerTube",
        )
    }

    private fun audioDbDurationMs(value: JsonObject): Long? {
        val raw = value.text("intDuration")?.toLongOrNull() ?: return null
        return if (raw in 1..20_000) raw * 1_000L else raw
    }

    private fun cleanText(value: String?): String? {
        val text = value
            ?.replace(HTML_TAG_REGEX, " ")
            ?.replace(WHITESPACE_REGEX, " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        return text?.take(MAX_BIO_LENGTH)
    }

    private fun upscaleImageUrl(url: String?): String? = url
        ?.replace(Regex("""=w\d+-h\d+"""), "=w1000-h1000")
        ?.replace(Regex("""=s\d+"""), "=s1000")

    // ---------------------------------------------------------------- LRCLIB

    /**
     * Текст песни из LRCLIB: сначала с исполнителем, при пустом результате —
     * повторный запрос только по названию.
     *
     * Результаты фильтруются по названию/исполнителю и длительности: в LRCLIB
     * попадаются ошибочные записи (например, чужие тексты под верным названием),
     * поэтому неподходящие по длительности варианты отбрасываем.
     */
    private suspend fun lookupLyrics(
        title: String,
        artist: String,
        durationMs: Long,
    ): LyricsResult? {
        if (artist.isNotBlank()) {
            runCatching { lrclibSearch(title, artist, durationMs) }.getOrNull()?.let { return it }
        }
        return runCatching { lrclibSearch(title, null, durationMs) }.getOrNull()
    }

    private suspend fun lrclibSearch(
        title: String,
        artist: String?,
        durationMs: Long,
    ): LyricsResult? {
        val url = buildString {
            append("https://lrclib.net/api/search?track_name=")
            append(encode(title))
            if (!artist.isNullOrBlank()) {
                append("&artist_name=")
                append(encode(artist))
            }
            append("&limit=5")
        }
        val root = Http.getJson(url, LYRICS_HEADERS) as? JsonArray ?: return null
        val candidates = root.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val plain = obj.text("plainLyrics")
            val synced = obj.text("syncedLyrics")
            if (plain.isNullOrBlank() && synced.isNullOrBlank()) return@mapNotNull null
            LrclibItem(
                plain = plain,
                synced = synced,
                durationSec = obj.double("duration"),
                trackName = obj.text("trackName"),
                artistName = obj.text("artistName"),
            )
        }
        val suitable = candidates.filter { matches(it, title, artist, durationMs) }
        if (suitable.isEmpty()) return null

        // Синхронизированный текст (LRC) оставляем как есть — плеер использует таймкоды
        // для караоке-подсветки; обычный текст получаем из него же или из plainLyrics.
        val synced = suitable.firstNotNullOfOrNull { item ->
            item.synced?.takeIf { it.isNotBlank() }
        }
        val plain = suitable.firstNotNullOfOrNull { item ->
            item.plain?.takeIf { it.isNotBlank() }
        } ?: synced?.let(::stripTimestamps)?.takeIf { it.isNotBlank() }
        if (plain == null && synced == null) return null
        return LyricsResult(plain = plain, synced = synced)
    }

    private fun matches(
        item: LrclibItem,
        title: String,
        artist: String?,
        durationMs: Long,
    ): Boolean {
        val ourTitle = title.lowercase()
        val itemTitle = item.trackName?.lowercase().orEmpty()
        if (itemTitle.isNotBlank() && ourTitle.isNotBlank()) {
            if (!itemTitle.contains(ourTitle) && !ourTitle.contains(itemTitle)) return false
        }
        if (!artist.isNullOrBlank() && !item.artistName.isNullOrBlank()) {
            val a = item.artistName.lowercase()
            val b = artist.lowercase()
            if (!a.contains(b) && !b.contains(a)) return false
        }
        val itemDuration = item.durationSec
        if (durationMs > 0 && itemDuration != null && itemDuration > 0) {
            val tolerance = maxOf(15_000L, (durationMs * 0.2).toLong())
            if (kotlin.math.abs(itemDuration * 1000.0 - durationMs) > tolerance) return false
        }
        return true
    }

    /** Убирает таймкоды `[00:12.34]` из syncedLyrics и чистит пустые строки. */
    private fun stripTimestamps(synced: String): String =
        synced.replace(TIMESTAMP_REGEX, "")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .trim()

    // ---------------------------------------------------------------- нормализация

    /**
     * Чистит название для поиска: убирает расширение файла, `[...]`, фичеринги
     * и служебные пометки `(Remastered)`/`(Live)`. Если после чистки пусто —
     * возвращает исходное значение.
     */
    private fun normalizeTitle(raw: String): String {
        val original = raw.trim()
        if (original.isBlank()) return ""
        var value = original
        value = value.replace(EXTENSION_TAIL_REGEX, "")
        value = value.replace(BRACKETS_REGEX, " ")
        value = value.replace(FEATURING_REGEX, " ")
        value = value.replace(SERVICE_NOTE_REGEX, " ")
        val cleaned = value.replace(WHITESPACE_REGEX, " ").trim(' ', '-', '–', '—')
        return cleaned.ifBlank { original }
    }

    /**
     * Исполнитель для запроса. Не режем по запятой/амперсанду: «Tyler, The Creator»
     * и «Earth, Wind & Fire» — это один исполнитель. Отбрасываем только feat./ft.
     * Пустой результат или «Неизвестный исполнитель» означают «не ограничивать».
     */
    private fun normalizeArtist(raw: String): String {
        val cleaned = ArtistNames.clean(raw)
        val first = cleaned.split(ARTIST_SPLIT_REGEX)
            .firstOrNull()
            ?.trim()
            .orEmpty()
        if (first.isBlank()) return ""
        if (ArtistNames.isPlaceholder(first)) return ""
        return ArtistNames.clean(first)
    }

    /** Экранирует символы, ломающие Lucene-запрос MusicBrainz. */
    private fun escapeLucene(value: String): String =
        value.replace(LUCENE_SPECIAL_REGEX, " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
            .take(MAX_QUERY_LENGTH)

    private fun merge(
        brainz: BrainzInfo?,
        lyrics: LyricsResult?,
        itunes: ProviderData? = null,
        deezer: ProviderData? = null,
        audioDb: ProviderData? = null,
        innerTube: ProviderData? = null,
        wikipedia: ArtistInfo? = null,
        fallbackCover: String? = null,
    ): TrackMetadata? {
        if (brainz == null && lyrics == null && itunes == null && deezer == null &&
            audioDb == null && innerTube == null && wikipedia == null
        ) {
            return null
        }
        // Обложки: сначала самые крупные и надёжные каталоги, затем Cover Art
        // Archive, InnerTube и в последнюю очередь — обложка альбома из iTunes.
        val cover = listOf(
            deezer?.coverUrl,
            audioDb?.coverUrl,
            itunes?.coverUrl,
            brainz?.coverUrl,
            innerTube?.coverUrl,
            fallbackCover,
        ).firstOrNull { !it.isNullOrBlank() }

        // Автор: канонический artist-credit MusicBrainz важнее названия канала.
        val artist = listOf(
            brainz?.artist,
            audioDb?.artist,
            deezer?.artist,
            itunes?.artist,
            innerTube?.artist,
        ).firstOrNull { !it.isNullOrBlank() }
            ?.let(ArtistNames::clean)
            ?.takeIf { it.isNotBlank() }

        val artistImage = listOf(
            audioDb?.artistImageUrl,
            deezer?.artistImageUrl,
            wikipedia?.imageUrl,
        ).firstOrNull { !it.isNullOrBlank() }

        val artistBio = listOf(
            audioDb?.artistBio,
            wikipedia?.bio,
        ).firstOrNull { !it.isNullOrBlank() }

        val genre = listOf(
            audioDb?.genre,
            deezer?.genre,
        ).firstOrNull { !it.isNullOrBlank() }

        val providers = buildList {
            if (brainz != null) add("MusicBrainz")
            if (lyrics?.plain != null || lyrics?.synced != null) add("LRCLIB")
            if (itunes != null) add("iTunes")
            if (deezer != null) add("Deezer")
            if (audioDb != null) add("TheAudioDB")
            if (innerTube != null) add("InnerTube")
            if (wikipedia != null) add("Wikipedia")
        }.distinct()
        return TrackMetadata(
            // iTunes/Deezer/TheAudioDB привязывают альбом именно к найденной
            // записи; MusicBrainz — фолбэк и источник MBID.
            album = itunes?.album ?: deezer?.album ?: audioDb?.album
                ?: brainz?.album ?: innerTube?.album,
            year = itunes?.year ?: deezer?.year ?: audioDb?.year ?: brainz?.year,
            coverUrl = cover,
            lyrics = lyrics?.plain?.takeIf { it.isNotBlank() },
            syncedLyrics = lyrics?.synced,
            musicBrainzId = brainz?.recordingId,
            artist = artist,
            title = brainz?.title,
            artistImageUrl = artistImage,
            artistBio = artistBio,
            genre = genre,
            provider = providers.joinToString(" / ").ifBlank { PROVIDER },
        )
    }

    /** Сравнивает названия без регистра и лишних пометок («(Official Video)», «[Remastered]»). */
    private fun sameTitle(candidate: String, expected: String): Boolean {
        val a = cleanForCompare(candidate)
        val b = cleanForCompare(expected)
        if (a.isBlank() || b.isBlank()) return false
        return a == b || a.contains(b) || b.contains(a)
    }

    private fun sameArtist(candidate: String?, expected: String): Boolean {
        if (expected.isBlank()) return true
        val a = cleanForCompare(candidate ?: "")
        val b = cleanForCompare(expected)
        if (a.isBlank()) return false
        if (ArtistNames.matches(candidate ?: "", expected)) return true
        return a == b || a.contains(b) || b.contains(a) ||
            // «The Beatles» и «Beatles», «feat.»-хвосты.
            a.replace("the ", "") == b.replace("the ", "")
    }

    private fun durationMatches(candidateMs: Long?, expectedMs: Long): Boolean {
        if (candidateMs == null || candidateMs <= 0L) return true
        if (expectedMs <= 0L) return true
        return kotlin.math.abs(candidateMs - expectedMs) <= 25_000L
    }

    private fun cleanForCompare(value: String): String = value
        .lowercase()
        .replace(PARENS_REGEX, " ")
        .replace(WHITESPACE_REGEX, " ")
        .trim()

    // ---------------------------------------------------------------- утилиты

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun JsonObject.double(key: String): Double? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

    private data class BrainzInfo(
        val album: String?,
        val year: Int?,
        val coverUrl: String?,
        val recordingId: String?,
        val artist: String? = null,
        val title: String? = null,
    )

    private data class LrclibItem(
        val plain: String?,
        val synced: String?,
        val durationSec: Double?,
        val trackName: String?,
        val artistName: String?,
    )

    private data class LyricsResult(
        val plain: String?,
        val synced: String?,
    )

    /** Обложка/альбом/год и дополнительные поля из каталога-источника. */
    private data class ProviderData(
        val coverUrl: String? = null,
        val album: String? = null,
        val year: Int? = null,
        val artist: String? = null,
        val artistImageUrl: String? = null,
        val artistBio: String? = null,
        val genre: String? = null,
        val source: String,
    )

    private data class ArtistInfo(
        val name: String? = null,
        val imageUrl: String? = null,
        val bio: String? = null,
        val genre: String? = null,
        val source: String,
    )

    private companion object {
        const val PROVIDER = "MusicBrainz / LRCLIB / iTunes / Deezer / TheAudioDB"
        const val CACHE_SCHEMA_VERSION = 2

        /** MusicBrainz требует осмысленный User-Agent с контактом. */
        const val USER_AGENT = "Sonora/0.9.5 (Android; contact: sonora.app@example.com)"

        const val MIN_REQUEST_INTERVAL_MS = 1_000L
        const val RETRY_AFTER_503_MS = 1_500L
        const val MAX_QUERY_LENGTH = 120
        const val MAX_BIO_LENGTH = 1_500
        const val PROVIDER_TIMEOUT_MS = 12_000L
        const val WIKIPEDIA_TIMEOUT_MS = 15_000L

        val MUSICBRAINZ_HEADERS = mapOf("User-Agent" to USER_AGENT)
        val LYRICS_HEADERS = mapOf("User-Agent" to USER_AGENT)
        val WIKIPEDIA_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json",
        )
        val JSON_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json",
        )
        val HTML_TAG_REGEX = Regex("""<[^>]+>""")
        val MUSIC_WORDS = listOf(
            "band", "musician", "singer", "songwriter", "rapper", "vocalist",
            "composer", "record producer", "dj", "rock", "pop", "hip hop",
            "musical artist", "recording artist", "music group", "duo", "trio",
            "группа", "певец", "певица", "музыкант", "композитор", "рэпер",
            "рок", "поп", "исполнитель", "дуэт", "трио",
        )

        val EXTENSION_TAIL_REGEX =
            Regex("""(?i)\s*\.(mp3|mp4|m4a|flac|ogg|oga|opus|wav|aac|wma)\s*$""")
        val BRACKETS_REGEX = Regex("""\[[^\[\]]*]""")
        /** Скобки любого вида: «(Official Video)», «[Remastered]». */
        val PARENS_REGEX = Regex("""[\(\[][^\)\]]*[\)\]]""")
        val FEATURING_REGEX =
            Regex("""(?i)\(\s*(?:feat\.?|ft\.?|featuring|with)\b[^()]*\)""")
        val SERVICE_NOTE_REGEX = Regex(
            """(?i)\(\s*(?:remaster(?:ed)?|version|edit|deluxe|mono|stereo|live|""" +
                """radio edit|acoustic|bonus|official\s*(?:video|audio|music\s*video|lyric\s*video)?|""" +
                """lyrics?|visuali[sz]er|hd|hq|4k|8k)\b[^()]*\)""",
        )
        val ARTIST_SPLIT_REGEX =
            Regex("""(?i)\s*(?:\bfeat\.?|\bft\.?|\bfeaturing|\bwith)\s*""")
        val LUCENE_SPECIAL_REGEX = Regex("""[\\"]""")
        val WHITESPACE_REGEX = Regex("""\s+""")
        val TIMESTAMP_REGEX = Regex("""\[\d{1,2}:\d{2}(?:\.\d{1,3})?]""")
    }
}
