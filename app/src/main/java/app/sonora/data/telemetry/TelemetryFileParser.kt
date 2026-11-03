package app.sonora.data.telemetry

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Одна строка файла с данными о треке: опознание трека плюс его характеристики.
 *
 * Поля трека повторяют `ImportedTrackEntry` из импорта списков, поэтому файл,
 * пригодный для импорта списка, годится и здесь — достаточно, чтобы в нём были
 * числа характеристик.
 *
 * @param feature `null`, если в строке не было ни одного числа. Такая строка не
 *   отбрасывается: файл может нести только опознание треков, и терять его нельзя.
 */
data class TelemetryFileEntry(
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val videoId: String? = null,
    val url: String? = null,
    val feature: AudioFeature? = null,
)

/**
 * Разобранный файл данных.
 *
 * @param warnings замечания разбора в порядке появления, чтобы показать их
 *   пользователю: молча проглоченная строка выглядит как потеря данных.
 */
data class TelemetryFileData(
    val fileName: String,
    val entries: List<TelemetryFileEntry>,
    val warnings: List<String> = emptyList(),
)

/**
 * Разбор файлов с данными о треках, принесённых с других платформ.
 *
 * Задача разбора — принять файл **того же вида, что и обычный импорт списка
 * треков** (JSON, CSV, TSV, TXT), но дополнительно вычитать аудио-характеристики:
 * темп, энергию, танцевальность и остальные девять полей [AudioFeature].
 *
 * Так сделано намеренно. Пользователь уже умеет выгружать списки треков из
 * Spotify и YouTube Music, и требовать от него второй, несовместимый формат
 * только ради чисел было бы лишним барьером: числа — это те же строки, в
 * которых появилось несколько новых столбцов. Поэтому структура контейнеров и
 * названия полей трека повторяют импорт списков один в один, а новыми являются
 * лишь имена характеристик.
 *
 * Разбор никогда не бросает исключение на кривой строке: строка пропускается, а
 * причина попадает в [TelemetryFileData.warnings]. Иначе один битый столбец
 * ломал бы импорт всего файла.
 */
object TelemetryFileParser {

    /** Потолок числа строк: тот же, что у импорта списков треков. */
    const val MAX_ENTRIES: Int = 20_000

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Разбирает содержимое файла.
     *
     * @param fileName имя файла — по расширению выбирается способ разбора;
     * @param raw содержимое целиком, уже раскодированное в текст.
     */
    fun parse(fileName: String, raw: String): TelemetryFileData {
        val text = raw.removePrefix("\uFEFF").trim()
        if (text.isBlank()) {
            return TelemetryFileData(fileName, emptyList())
        }
        val warnings = ArrayList<String>()
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val looksJson = extension == "json" || text.startsWith("[") || text.startsWith("{")
        val parsed = when {
            // Откат к построчному разбору здесь был бы вреден: неразобранный JSON
            // превращался в одну «песню», у которой в названии лежала вся строка
            // JSON целиком, и такой мусор доходил до библиотеки. Если содержимое
            // выглядит как JSON, разбираем его только как JSON.
            looksJson -> parseJson(text, warnings).ifEmpty {
                if (warnings.isEmpty()) {
                    warnings += "Треков в файле не найдено"
                }
                emptyList()
            }

            extension == "csv" || extension == "tsv" || looksLikeTable(text) ->
                parseTable(text, warnings).ifEmpty { parseText(text) }

            else -> parseText(text)
        }
        val entries = parsed
            .filter { it.title.isNotBlank() }
            .distinctBy { entry ->
                "${entry.title.lowercase().trim()}|${entry.artist.orEmpty().lowercase().trim()}"
            }
            .take(MAX_ENTRIES)
        return TelemetryFileData(fileName, entries, warnings)
    }

    /** Числовые поля фич: ключ разбора → как называется в [AudioFeature]. */
    private val FEATURE_KEYS: Map<String, String> = linkedMapOf(
        "tempo" to "tempo",
        "bpm" to "tempo",
        "energy" to "energy",
        "danceability" to "danceability",
        "dance" to "danceability",
        "valence" to "valence",
        "mood" to "valence",
        "acousticness" to "acousticness",
        "acoustic" to "acousticness",
        "instrumentalness" to "instrumentalness",
        "instrumental" to "instrumentalness",
        "liveness" to "liveness",
        "speechiness" to "speechiness",
        "speech" to "speechiness",
        "loudness" to "loudness",
    )

    /** Контейнеры, в которых характеристики могут лежать вложенно. */
    private val FEATURE_CONTAINERS = listOf(
        "audio_features", "audioFeatures", "features",
        "audio_feature", "audioFeature",
    )

    // ---------------------------------------------------------------- JSON

    private fun parseJson(text: String, warnings: MutableList<String>): List<TelemetryFileEntry> {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull()
            ?: run {
                warnings += "Файл не разобран как JSON"
                return emptyList()
            }
        val out = ArrayList<TelemetryFileEntry>()
        fun visit(element: JsonElement) {
            when (element) {
                is JsonArray -> element.forEach(::visit)
                is JsonObject -> {
                    // Обёртка Spotify: {"track": {...}}.
                    val nested = element.obj("track")
                    if (nested != null && element.firstString("name") == null) {
                        visit(nested)
                    } else {
                        val entry = toEntry(element, warnings)
                        if (entry != null) {
                            out += entry
                        } else {
                            element.values.forEach { value ->
                                if (value !== nested) visit(value)
                            }
                        }
                    }
                }

                else -> Unit
            }
        }
        visit(root)
        return out
    }

    private fun toEntry(
        obj: JsonObject,
        warnings: MutableList<String>,
    ): TelemetryFileEntry? {
        val kind = obj.firstString("type", "kind", "typeName")?.lowercase()
        if (kind in CONTAINER_KINDS) return null
        if (obj["album_type"] != null) return null
        if (obj.obj("tracks") != null && obj.obj("album") == null) return null

        val title = obj.firstString(
            "title", "trackName", "track_name", "songTitle", "song_title", "song", "name",
        )?.trim()?.takeIf { it.isNotBlank() } ?: return null

        // channelTitle добавлен к ключам исполнителя не случайно: выгрузка
        // YouTube Music хранит название канала внутри snippet, а сам объект
        // трека при спуске по дереву оказывается этим самым snippet — и без
        // него исполнитель терялся.
        val artist = obj.stringList("artists")
            ?: obj.firstString(
                "artist", "artistName", "artist_name", "albumArtist", "album_artist", "channelTitle",
            )
            ?: obj.obj("album")?.stringList("artists")
            ?: obj.obj("snippet")?.firstString("channelTitle")
        val album = obj.firstString("albumName", "album_name", "collectionName", "collection_name")
            ?: obj.obj("album")?.firstString("name", "title")
            ?: obj.stringValue("album")
            ?: obj.obj("snippet")?.firstString("album")
        val duration = obj.firstLong(
            "duration_ms", "durationMs", "durationInMillis", "duration_in_millis", "duration", "length",
        )?.let { if (it in 1..20_000) it * 1_000 else it }
        val videoId = obj.firstString("videoId", "video_id", "youtubeId", "youtube_id")
            ?: obj.obj("resourceId")?.firstString("videoId")
            ?: obj.obj("snippet")?.obj("resourceId")?.firstString("videoId")
        val url = obj.firstString("url", "link", "spotifyUrl", "spotify_url", "youtubeUrl", "youtube_url")
            ?: obj.obj("external_urls")?.values
                ?.filterIsInstance<JsonPrimitive>()
                ?.mapNotNull { it.contentOrNull }
                ?.firstOrNull { it.startsWith("http") }
            ?: obj.obj("external_urls")?.stringValue("spotify")

        val trackLike = obj.obj("tracks") == null &&
            (artist != null || duration != null || videoId != null ||
                obj.firstString("type", "kind")?.contains("track", ignoreCase = true) == true)
        if (!trackLike) return null

        return TelemetryFileEntry(
            title = title,
            artist = artist?.trim()?.takeIf { it.isNotBlank() },
            album = album?.trim()?.takeIf { it.isNotBlank() },
            durationMs = duration?.takeIf { it > 0 },
            videoId = videoId?.trim()?.takeIf { it.isNotBlank() },
            url = url?.trim()?.takeIf { it.isNotBlank() },
            feature = readFeature(obj, title, warnings),
        )
    }

    /**
     * Собирает [AudioFeature] из плоских полей объекта или из вложенного
     * контейнера. Пустая фича превращается в `null`: строка без чисел — это
     * нормальный случай (файл несёт только опознание трека), и такой строке
     * незачем занимать место в кэше.
     */
    private fun readFeature(
        obj: JsonObject,
        title: String,
        warnings: MutableList<String>,
    ): AudioFeature? {
        val holders = ArrayList<JsonObject>(1 + FEATURE_CONTAINERS.size)
        holders += obj
        FEATURE_CONTAINERS.forEach { key -> obj.obj(key)?.let { holders += it } }

        var tempo: Double? = null
        var energy: Double? = null
        var danceability: Double? = null
        var valence: Double? = null
        var acousticness: Double? = null
        var instrumentalness: Double? = null
        var liveness: Double? = null
        var speechiness: Double? = null
        var loudness: Double? = null

        // Плоские поля: берём первое непустое значение по всем контейнерам.
        holders.forEach { holder ->
            FEATURE_KEYS.forEach { (rawKey, canonical) ->
                val value = holder.numberValue(rawKey) ?: return@forEach
                val accepted = when (canonical) {
                    // Темп — это BPM, а не доля: у ReccoBeats он лежит в 0..400.
                    "tempo" -> value.takeIf { it in 0.0..MAX_TEMPO }
                    else -> value.takeIf { it in 0.0..1.0 }
                }
                if (accepted == null) {
                    // Зажимать значение нельзя: 5.0 вместо 0.5 означает, что поле
                    // разобрано неверно, и «тихая» правка испортила бы данные.
                    warnings += "«$title»: значение $rawKey=$value вне диапазона, пропущено"
                    return@forEach
                }
                when (canonical) {
                    "tempo" -> if (tempo == null) tempo = accepted
                    "energy" -> if (energy == null) energy = accepted
                    "danceability" -> if (danceability == null) danceability = accepted
                    "valence" -> if (valence == null) valence = accepted
                    "acousticness" -> if (acousticness == null) acousticness = accepted
                    "instrumentalness" -> if (instrumentalness == null) instrumentalness = accepted
                    "liveness" -> if (liveness == null) liveness = accepted
                    "speechiness" -> if (speechiness == null) speechiness = accepted
                    "loudness" -> if (loudness == null) loudness = accepted
                }
            }
        }

        val feature = AudioFeature(
            tempo = tempo,
            energy = energy,
            danceability = danceability,
            valence = valence,
            acousticness = acousticness,
            instrumentalness = instrumentalness,
            liveness = liveness,
            speechiness = speechiness,
            loudness = loudness,
        )
        return feature.takeIf { !it.isEmpty }
    }

    private fun JsonObject.firstString(vararg keys: String): String? {
        for (key in keys) {
            val value = this[key] ?: continue
            stringValue(value)?.let { return it }
        }
        return null
    }

    private fun JsonObject.stringValue(key: String): String? = stringValue(this[key])

    private fun stringValue(element: JsonElement?): String? = when (element) {
        is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }
        is JsonObject -> element.firstString("name", "title", "text")
        else -> null
    }

    private fun JsonObject.stringList(key: String): String? {
        val array = this[key] as? JsonArray ?: return null
        val values = array.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull
                is JsonObject -> item.firstString("name", "title")
                else -> null
            }
        }.filter { it.isNotBlank() }
        return values.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    /**
     * Число из плоского поля. Принимает и строку `"0.42"`: выгрузки часто
     * содержат числа в кавычках, и отказываться от них было бы незачем.
     */
    private fun JsonObject.numberValue(key: String): Double? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        val raw = primitive.contentOrNull?.trim() ?: return null
        if (raw.isEmpty()) return null
        return raw.toDoubleOrNull()
    }

    private fun JsonObject.firstLong(vararg keys: String): Long? {
        for (key in keys) {
            val value = this[key] as? JsonPrimitive ?: continue
            val raw = value.contentOrNull ?: continue
            raw.toLongOrNull()?.let { return it }
            raw.toDoubleOrNull()?.let { return it.toLong() }
            parseClockMs(raw)?.let { return it }
        }
        return null
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    // ---------------------------------------------------------------- CSV / TSV

    private fun looksLikeTable(text: String): Boolean {
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        if (firstLine.count { it == '\t' } >= 1) return true
        if (firstLine.count { it == ';' } >= 1) return true
        if (firstLine.count { it == ',' } < 1) return false
        val normalized = firstLine.lowercase()
        return TITLE_HEADERS.any { it in normalized } || ARTIST_HEADERS.any { it in normalized }
    }

    private fun parseTable(text: String, warnings: MutableList<String>): List<TelemetryFileEntry> {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return emptyList()
        val delimiter = when {
            lines.first().count { it == '\t' } >= 1 -> '\t'
            lines.first().count { it == ';' } >= 1 -> ';'
            else -> ','
        }
        val rows = lines.map { parseDelimitedLine(it, delimiter) }
        val header = rows.firstOrNull().orEmpty().map { normalizeHeader(it) }
        val hasHeader = header.any { it in TITLE_HEADERS || it in ARTIST_HEADERS }
        val dataRows = if (hasHeader) rows.drop(1) else rows

        fun column(row: List<String>, headers: Set<String>, fallback: Int): String? {
            if (hasHeader) {
                val index = header.indexOfFirst { it in headers }
                return row.getOrNull(index)?.trim()?.takeIf { it.isNotBlank() }
            }
            return row.getOrNull(fallback)?.trim()?.takeIf { it.isNotBlank() }
        }

        fun featureColumn(row: List<String>, key: String): Double? {
            if (!hasHeader) return null
            val index = header.indexOfFirst { it == key }
            val raw = row.getOrNull(index)?.trim()?.takeIf { it.isNotBlank() } ?: return null
            return raw.toDoubleOrNull()
        }

        return dataRows.mapNotNull { row ->
            val title = column(row, TITLE_HEADERS, 0) ?: return@mapNotNull null
            val artist = column(row, ARTIST_HEADERS, 1)
            val album = column(row, ALBUM_HEADERS, 2)
            val duration = column(row, DURATION_HEADERS, 3)?.let(::parseClockMs)
            val videoId = column(row, VIDEO_ID_HEADERS, -1)
                ?.takeIf { VIDEO_ID.matches(it) }
            val url = column(row, URL_HEADERS, -1)

            fun read(key: String, max: Double): Double? {
                val value = featureColumn(row, key) ?: return null
                if (value in 0.0..max) return value
                warnings += "«$title»: значение $key=$value вне диапазона, пропущено"
                return null
            }

            val feature = AudioFeature(
                tempo = read("tempo", MAX_TEMPO) ?: read("bpm", MAX_TEMPO),
                energy = read("energy", 1.0),
                danceability = read("danceability", 1.0),
                valence = read("valence", 1.0),
                acousticness = read("acousticness", 1.0),
                instrumentalness = read("instrumentalness", 1.0),
                liveness = read("liveness", 1.0),
                speechiness = read("speechiness", 1.0),
                loudness = read("loudness", 1.0),
            ).takeIf { !it.isEmpty }

            TelemetryFileEntry(
                title = title,
                artist = artist,
                album = album,
                durationMs = duration,
                videoId = videoId,
                url = url,
                feature = feature,
            )
        }
    }

    private fun parseDelimitedLine(line: String, delimiter: Char): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }

                char == '"' -> quoted = !quoted
                char == delimiter && !quoted -> {
                    out += current.toString()
                    current.clear()
                }

                else -> current.append(char)
            }
            index++
        }
        out += current.toString()
        return out
    }

    // ---------------------------------------------------------------- TXT

    private fun parseText(text: String): List<TelemetryFileEntry> {
        return text.lines().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
                return@mapNotNull null
            }
            val (artist, title) = splitArtistTitle(trimmed)
            if (title.isBlank()) return@mapNotNull null
            TelemetryFileEntry(
                title = title,
                artist = artist,
                videoId = youtubeId(trimmed),
                url = trimmed.takeIf { it.startsWith("http") },
            )
        }
    }

    private fun splitArtistTitle(value: String): Pair<String?, String> {
        val trimmed = value.trim()
        val separators = listOf(" – ", " — ", " - ")
        for (separator in separators) {
            val index = trimmed.indexOf(separator)
            if (index > 0 && index < trimmed.length - separator.length) {
                val artist = trimmed.substring(0, index).trim().takeIf { it.isNotBlank() }
                val title = trimmed.substring(index + separator.length).trim()
                return artist to title
            }
        }
        return null to trimmed
    }

    private fun parseClockMs(value: String): Long? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        if (trimmed.all { it.isDigit() }) {
            val number = trimmed.toLongOrNull() ?: return null
            // В таблице «225» — это секунды, в JSON — обычно миллисекунды.
            return if (number in 1..20_000) number * 1_000 else number
        }
        val parts = trimmed.split(':').mapNotNull { it.toDoubleOrNull() }
        if (parts.isEmpty()) return null
        var seconds = 0.0
        parts.forEach { seconds = seconds * 60 + it }
        return (seconds * 1_000).toLong().takeIf { it > 0 }
    }

    private fun youtubeId(value: String): String? {
        val patterns = listOf(
            Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
            Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
            Regex("""music\.youtube\.com/watch\?v=([A-Za-z0-9_-]{11})"""),
        )
        for (pattern in patterns) {
            pattern.find(value)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return null
    }

    private fun normalizeHeader(value: String): String =
        value.trim().lowercase().replace(Regex("[^a-zа-я0-9]+"), " ").trim()

    private val TITLE_HEADERS = setOf(
        "title", "name", "track", "track name", "song", "song title", "song name",
        "название", "трек", "песня",
    )
    private val ARTIST_HEADERS = setOf(
        "artist", "artists", "artist name", "artist name s", "artist s", "исполнитель",
        "album artist", "channel", "channel title",
    )
    private val ALBUM_HEADERS = setOf("album", "album name", "collection", "collection name", "альбом")
    private val DURATION_HEADERS = setOf("duration", "duration ms", "length", "time", "длительность")
    private val VIDEO_ID_HEADERS = setOf("video id", "videoid", "youtube id", "youtubeid")
    private val URL_HEADERS = setOf(
        "url", "link", "spotify url", "youtube url", "external url", "track uri", "spotify uri",
    )

    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    private val CONTAINER_KINDS = setOf(
        "album", "playlist", "artist", "show", "podcast", "episode", "user", "collection",
    )

    /** Верхняя граница темпа: быстрее 400 BPM музыки практически не бывает. */
    private const val MAX_TEMPO = 400.0
}
