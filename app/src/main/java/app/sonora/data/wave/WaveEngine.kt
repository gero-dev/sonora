package app.sonora.data.wave

import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.core.settings.WaveSettings
import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.random.Random

/** Одна запись истории «Моей волны»: что играло и дослушано ли. */
@Serializable
data class WaveHistoryEntry(
    val trackId: String,
    val artist: String,
    val title: String,
    val playedAtMs: Long,
    val completed: Boolean,
    /** Сколько реально прослушано, мс (0 — старые записи без статистики). */
    val listenedMs: Long = 0L,
    /** Полная длительность трека на момент прослушивания, мс. */
    val durationMs: Long = 0L,
    /** Источник трека: LOCAL / YOUTUBE / ... — для истории и статистики. */
    val source: String = "",
)

/**
 * Чистые алгоритмы «Моей волны»: без Android API и сетевых вызовов.
 *
 * Здесь всё, что можно проверить юнит-тестами: ключ дедупликации, фильтр
 * «только музыка», затухающий вес артистов по истории, взвешенное ранжирование,
 * поисковые запросы для онлайн-части, микс локального с онлайн и разнообразие.
 */
object WaveEngine {

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Полураспад веса прослушивания: 45 дней. */
    private const val AFFINITY_HALF_LIFE_DAYS = 45.0

    /** Пропуск даёт отрицательный вклад (штраф). */
    private const val SKIP_WEIGHT = -0.35

    /** «Свежая» история для штрафа за повтор артиста. */
    private const val RECENT_WINDOW_MS = 2 * DAY_MS

    /** Минимальный вес кандидата при взвешенной выборке. */
    private const val MIN_WEIGHT = 0.05

    /** Защита от U = 0 в Efraimidis–Spirakis: ln(0) не определён. */
    private const val MIN_RANDOM = 1e-12

    /** Размер блока в миксе: 5 треков, внутри доля локальных/онлайн. */
    private const val MIX_BLOCK = 5

    private const val BASE_WEIGHT = 1.0
    private const val FAVORITE_TRACK_BONUS = 2.5
    private const val FAVORITE_ARTIST_BONUS = 1.6
    private const val POPULARITY_STEP = 0.12
    private const val BLANK_ARTIST_PENALTY = 0.2
    private const val LOCAL_BONUS = 0.3
    private const val NEW_ARTIST_BONUS = 0.9
    private const val HIGH_NOVELTY = 60
    private const val RECENT_ARTIST_PENALTY = 0.6

    /** Бонус за треки, которые пользователь дослушивал (а не пропускал). */
    private const val TRACK_AFFINITY_BONUS = 1.2

    private val BRACKET_NOISE = Regex("""[\(\[][^\)\]]*[\)\]]""")
    private val WHITESPACE = Regex("""\s+""")

    /** Маркеры нежелательного видеоконтента в названии. */
    val VIDEO_MARKERS = listOf(
        "official video", "music video", "клип", "концерт", "live at",
        "interview", "подкаст", "podcast", "shorts", "full album",
        "полный альбом", "reaction", "trailer", "тизер", "1 hour",
        "10 hours", "gameplay", "asmr", "обзор", "прямой эфир", "live stream",
    )

    /**
     * Демо-исполнители самого приложения. Раньше тестовые файлы вида
     * «Sonora Test» попадали в рекомендации и выглядели как отсылка плеера
     * к самому себе — в волне их больше не показываем.
     */
    private val SELF_REFERENCE_ARTISTS = setOf(
        "sonora", "sonora test", "sonora import", "sonora demo",
    )

    /** Ключ дедупликации: «исполнитель|название» без скобочных пометок. */
    fun waveKey(track: Track): String = keyOf(track.artist, track.title)

    /** Тот же ключ для записи истории: там исполнитель и название хранятся отдельно. */
    fun waveKeyOf(artist: String, title: String): String = keyOf(artist, title)

    /** Трек принадлежит демо-контенту самого приложения, а не пользователю. */
    fun isSelfReference(track: Track): Boolean {
        val artist = track.artist.trim().lowercase()
        if (artist in SELF_REFERENCE_ARTISTS) return true
        return artist.startsWith("sonora test") || artist.startsWith("sonora import")
    }

    /**
     * Пропускает только музыку: пустые поля, слишком короткие/длинные записи
     * и ролики с маркерами видео. Для локальных файлов верхнюю границу не
     * проверяем (это собственная музыка пользователя), для онлайн-треков — проверяем.
     *
     * Маркеры учитываются, если включён «только музыка» ([WaveSettings.musicOnly])
     * или «скрывать видеоклипы» ([WaveSettings.excludeVideos]). Для онлайн-треков
     * верхняя граница не может оказаться ниже нижней: в этом случае она
     * схлопывается к нижней, чтобы не отбрасывать весь онлайн-каталог.
     */
    fun isMusic(track: Track, tuning: WaveSettings): Boolean {
        if (track.title.isBlank() || track.artist.isBlank()) return false
        if (isSelfReference(track)) return false

        if (tuning.musicOnly || tuning.excludeVideos) {
            val title = track.title.lowercase()
            if (VIDEO_MARKERS.any { title.contains(it) }) return false
        }

        // Длительность проверяем последней, чтобы инвариант границ не зависел от порядка условий.
        if (track.durationMs > 0L) {
            val durationSec = track.durationMs / 1000L
            val minSeconds = tuning.minDurationSec.toLong()
            if (durationSec < minSeconds) return false
            if (track.source != Source.LOCAL) {
                val maxSeconds = (tuning.maxDurationMin * 60L).coerceAtLeast(minSeconds)
                if (durationSec > maxSeconds) return false
            }
        }
        return true
    }

    /** Убирает повторы по id и по [waveKey]; порядок первых вхождений сохраняется. */
    fun dedupe(tracks: List<Track>): List<Track> {
        if (tracks.size < 2) return tracks
        val ids = HashSet<String>(tracks.size)
        val keys = HashSet<String>(tracks.size)
        return tracks.filter { track ->
            if (!ids.add(track.id)) return@filter false
            if (!keys.add(waveKey(track))) return@filter false
            true
        }
    }

    /**
     * Вес артиста по истории. Учитываем не только «дослушал/пропустил», но и
     * долю реально прослушанного: 80 % трека весит как полное прослушивание,
     * 40–80 % — половину, короткий пропуск — отрицательно. Свежесть затухает
     * с полураспадом 45 дней.
     */
    fun artistAffinity(
        history: List<WaveHistoryEntry>,
        nowMs: Long = System.currentTimeMillis(),
    ): Map<String, Double> {
        if (history.isEmpty()) return emptyMap()
        val result = HashMap<String, Double>()
        history.forEach { entry ->
            val artist = entry.artist.trim().lowercase()
            if (artist.isBlank()) return@forEach
            val engagement = engagementScore(entry) ?: return@forEach
            if (engagement == 0.0) return@forEach
            val ageDays = (nowMs - entry.playedAtMs).coerceAtLeast(0L).toDouble() / DAY_MS
            val weight = exp(-ageDays / AFFINITY_HALF_LIFE_DAYS) * engagement
            result[artist] = (result[artist] ?: 0.0) + weight
        }
        return result.filterValues { it != 0.0 }
    }

    /**
     * Вклад одного прослушивания: +1 за полное, +0.45 за половину, −0.35 за
     * пропуск. null — запись-заглушка, которая ещё не стала прослушиванием.
     */
    fun engagementScore(entry: WaveHistoryEntry): Double? {
        // Плейсхолдер пишется в момент старта трека (0 мс, не completed).
        if (entry.listenedMs <= 0L && !entry.completed) return if (entry.durationMs > 0L) null else 0.0
        if (entry.durationMs <= 0L) return if (entry.completed) 1.0 else 0.0
        val ratio = (entry.listenedMs.toDouble() / entry.durationMs).coerceIn(0.0, 2.0)
        return when {
            ratio >= 0.8 -> 1.0
            ratio >= 0.4 -> 0.45
            ratio > 0.15 -> 0.1
            else -> SKIP_WEIGHT
        }
    }

    /** Ключи треков, которые пользователь явно отметил «не интересно». */
    fun dislikeKey(track: Track): String = "t:${waveKey(track)}"

    fun isDisliked(track: Track, disliked: Set<String>): Boolean =
        dislikeKey(track) in disliked

    /**
     * Вклад конкретных треков: дослушанные получают плюс, пропущенные — минус.
     * Нужен, чтобы «Моя волна» держала любимые композиции, но не повторяла их
     * каждый раз (за повтор отвечает штраф за свежую историю).
     */
    fun trackEngagement(
        history: List<WaveHistoryEntry>,
        nowMs: Long = System.currentTimeMillis(),
    ): Map<String, Double> {
        if (history.isEmpty()) return emptyMap()
        val result = HashMap<String, Double>()
        history.forEach { entry ->
            val engagement = engagementScore(entry) ?: return@forEach
            if (engagement == 0.0) return@forEach
            val ageDays = (nowMs - entry.playedAtMs).coerceAtLeast(0L).toDouble() / DAY_MS
            val key = keyOf(entry.artist, entry.title)
            result[key] = (result[key] ?: 0.0) +
                exp(-ageDays / AFFINITY_HALF_LIFE_DAYS) * engagement
        }
        return result.filterValues { it != 0.0 }
    }

    /** Ключи треков, игравшихся за последние [days] дней (чтобы не повторяться). */
    fun recentKeys(
        history: List<WaveHistoryEntry>,
        nowMs: Long,
        days: Int = 2,
    ): Set<String> {
        if (history.isEmpty()) return emptySet()
        val threshold = nowMs - days.coerceAtLeast(0).toLong() * DAY_MS
        return history.asSequence()
            .filter { it.playedAtMs >= threshold }
            .map { keyOf(it.artist, it.title) }
            .toSet()
    }

    /**
     * Взвешенный случайный порядок кандидатов: избранное, любимые артисты,
     * свежая история, популярность, локальный бонус и новизна. Без повторов.
     *
     * Веса из контракта: база 1.0; +2.5 за избранный трек; +1.6·(1−novelty/150)
     * за любимого артиста; + affinity·(1−novelty/160); + min(count,8)·0.12;
     * −0.2 за пустого артиста; локальный бонус гаснет с ростом новизны;
     * бонус за нового артиста растёт с новизной; штрафы за свежего артиста
     * и за недавно игравший трек (вместо жёсткого исключения).
     */
    fun rank(
        candidates: List<Track>,
        favoriteIds: Set<String>,
        favoriteArtists: Set<String>,
        artistCounts: Map<String, Int>,
        history: List<WaveHistoryEntry>,
        tuning: WaveSettings,
        localBias: Boolean,
        random: Random = Random.Default,
        nowMs: Long = System.currentTimeMillis(),
        dislikedKeys: Set<String> = emptySet(),
        recentTrackKeys: Set<String>? = null,
    ): List<Track> {
        if (candidates.isEmpty()) return emptyList()
        val novelty = tuning.novelty.coerceIn(0, 100)

        val favoriteArtistKeys = favoriteArtists.mapTo(HashSet()) { it.trim().lowercase() }
        val artistCountKeys = HashMap<String, Int>(artistCounts.size)
        artistCounts.forEach { (raw, count) ->
            val key = raw.trim().lowercase()
            if (key.isNotBlank()) artistCountKeys[key] = (artistCountKeys[key] ?: 0) + count
        }

        val affinity = if (tuning.useHistory) artistAffinity(history, nowMs) else emptyMap()
        val trackScores = if (tuning.useHistory) trackEngagement(history) else emptyMap()
        val knownArtists = if (tuning.useHistory) historyArtists(history) else emptySet()
        val recentArtists = if (tuning.useHistory) recentArtists(history, nowMs) else emptySet()
        val recentTracks = recentTrackKeys
            ?: if (tuning.useHistory) recentKeys(history, nowMs) else emptySet()
        val discovery = novelty / 100.0

        fun weightOf(track: Track): Double {
            val artist = track.artist.trim().lowercase()
            val key = waveKey(track)
            if (dislikedKeys.isNotEmpty() && (
                    dislikeKey(track) in dislikedKeys || "t:$key" in dislikedKeys
                    )
            ) {
                return 0.0
            }
            var weight = BASE_WEIGHT
            if (track.id in favoriteIds) weight += FAVORITE_TRACK_BONUS
            if (artist.isNotBlank() && artist in favoriteArtistKeys) {
                weight += FAVORITE_ARTIST_BONUS * (1.0 - discovery * 0.7)
            }
            weight += (affinity[artist] ?: 0.0) * (1.0 - discovery * 0.6)
            weight += (trackScores[key] ?: 0.0) * TRACK_AFFINITY_BONUS * (1.0 - discovery * 0.8)
            weight += minOf(artistCountKeys[artist] ?: 0, 8) * POPULARITY_STEP
            if (artist.isBlank()) weight -= BLANK_ARTIST_PENALTY
            // Чем выше новизна, тем меньше «своей» музыки в начале подборки.
            if (localBias && track.source == Source.LOCAL) {
                weight += LOCAL_BONUS * (1.0 - discovery * 0.8)
            }
            val newArtist = artist.isNotBlank() &&
                artist !in favoriteArtistKeys &&
                (artistCountKeys[artist] ?: 0) == 0 &&
                artist !in knownArtists
            if (newArtist) weight += NEW_ARTIST_BONUS * (0.3 + discovery * 0.9)
            if (novelty >= HIGH_NOVELTY && artist in recentArtists) {
                weight -= (novelty - HIGH_NOVELTY) / 40.0 * RECENT_ARTIST_PENALTY
            }
            // Недавно игравший трек отодвигаем, но не выбрасываем: иначе
            // у небольшой фонотеки волна остаётся пустой.
            if (key in recentTracks) {
                weight *= 1.0 - 0.75 * (0.4 + discovery * 0.6)
            }
            return weight.coerceAtLeast(0.0)
        }

        // Взвешенная выборка без повторов по ключам Efraimidis–Spirakis:
        // key = -ln(U)/weight, затем сортировка по возрастанию. Это O(n log n)
        // вместо O(n²) у пошагового пересчёта суммы весов, распределение то же.
        return candidates
            .mapNotNull { track ->
                val weight = weightOf(track)
                if (weight < MIN_WEIGHT) return@mapNotNull null
                val u = random.nextDouble(0.0, 1.0).coerceAtLeast(MIN_RANDOM)
                track to -ln(u) / weight
            }
            .sortedBy { it.second }
            .map { it.first }
    }

    /**
     * Поисковые запросы для онлайн-части: настроение, любимые и популярные
     * артисты, радио по топ-3 affinity, новизна/знакомое и общие миксы.
     */
    fun queries(
        moodQuery: String?,
        favoriteArtists: List<String>,
        popularArtists: List<String>,
        affinity: Map<String, Double>,
        tuning: WaveSettings,
    ): List<String> {
        val result = LinkedHashSet<String>()
        val seen = HashSet<String>()

        fun add(raw: String?) {
            val query = raw?.trim().orEmpty()
            if (query.isEmpty()) return
            if (seen.add(query.lowercase())) result.add(query)
        }

        add(moodQuery)
        favoriteArtists.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(3)
            .forEach { add("$it mix") }
        popularArtists.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(4)
            .forEach { add("$it mix") }
        affinity.entries
            .filter { it.key.isNotBlank() }
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(3)
            .forEach { add("${it.key.trim()} radio") }
        if (tuning.novelty >= 60) {
            add("new music mix")
            add("fresh music releases")
        }
        if (tuning.novelty <= 30) add("greatest hits mix")
        add("chill music mix")
        add("music mix")
        return result.toList()
    }

    /**
     * Микс локального и онлайн по [WaveSettings.localShare] процентам.
     * Доля выдерживается блоками по [MIX_BLOCK] треков (при 60% — 3 локальных + 2
     * онлайн); когда одна сторона кончилась, остаток добирается из другой.
     */
    fun mix(
        local: List<Track>,
        online: List<Track>,
        tuning: WaveSettings,
        limit: Int,
    ): List<Track> {
        if (limit <= 0) return emptyList()
        if (local.isEmpty()) return online.take(limit)
        if (online.isEmpty()) return local.take(limit)

        val share = tuning.localShare.coerceIn(0, 100)
        val localPerBlock = (share * MIX_BLOCK / 100.0).roundToInt().coerceIn(0, MIX_BLOCK)
        val result = ArrayList<Track>(limit.coerceAtMost(local.size + online.size))
        var localIndex = 0
        var onlineIndex = 0
        while (result.size < limit && (localIndex < local.size || onlineIndex < online.size)) {
            val takeLocal = when {
                localIndex >= local.size -> false
                onlineIndex >= online.size -> true
                else -> result.size % MIX_BLOCK < localPerBlock
            }
            result += if (takeLocal) local[localIndex++] else online[onlineIndex++]
        }
        return result
    }

    /**
     * Не больше [maxPerArtist] треков одного артиста в начале подборки,
     * остальные — в конце (в исходном порядке).
     */
    fun diversify(tracks: List<Track>, limit: Int, maxPerArtist: Int): List<Track> {
        if (limit <= 0) return emptyList()
        val maxPerTrack = maxPerArtist.coerceAtLeast(1)
        val result = ArrayList<Track>(limit.coerceAtMost(tracks.size))
        val deferred = ArrayList<Track>()
        val perArtist = HashMap<String, Int>()
        for (track in tracks) {
            if (result.size >= limit) break
            val artist = track.artist.trim().lowercase()
            val played = perArtist[artist] ?: 0
            if (played >= maxPerTrack) {
                deferred += track
                continue
            }
            result += track
            perArtist[artist] = played + 1
        }
        var index = 0
        while (result.size < limit && index < deferred.size) {
            result += deferred[index++]
        }
        return result
    }

    // ---------------------------------------------------------------- внутреннее

    private fun keyOf(artist: String, title: String): String =
        "${artist.trim().lowercase()}|${normalizeTitle(title)}"

    private fun normalizeTitle(raw: String): String =
        raw.lowercase()
            .replace(BRACKET_NOISE, " ")
            .replace(WHITESPACE, " ")
            .trim()

    private fun historyArtists(history: List<WaveHistoryEntry>): Set<String> =
        history.mapNotNullTo(HashSet()) { entry ->
            entry.artist.trim().lowercase().takeIf { it.isNotBlank() }
        }

    private fun recentArtists(history: List<WaveHistoryEntry>, nowMs: Long): Set<String> {
        val threshold = nowMs - RECENT_WINDOW_MS
        return history.mapNotNullTo(HashSet()) { entry ->
            val artist = entry.artist.trim().lowercase()
            if (entry.playedAtMs >= threshold && artist.isNotBlank()) artist else null
        }
    }
}
