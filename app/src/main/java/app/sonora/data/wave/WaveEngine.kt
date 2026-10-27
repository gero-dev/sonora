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

    private val BRACKET_NOISE = Regex("""[\(\[][^\)\]]*[\)\]]""")
    private val WHITESPACE = Regex("""\s+""")

    /** Маркеры нежелательного видеоконтента в названии. */
    private val VIDEO_MARKERS = listOf(
        "official video", "music video", "клип", "концерт", "live at",
        "interview", "подкаст", "podcast", "shorts", "full album",
        "полный альбом", "reaction", "trailer", "тизер", "1 hour",
        "10 hours", "gameplay", "asmr", "обзор", "прямой эфир", "live stream",
    )

    /** Ключ дедупликации: «исполнитель|название» без скобочных пометок. */
    fun waveKey(track: Track): String = keyOf(track.artist, track.title)

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

    /** Вес артиста по истории: свежие дослушивания +, пропуски −. */
    fun artistAffinity(
        history: List<WaveHistoryEntry>,
        nowMs: Long = System.currentTimeMillis(),
    ): Map<String, Double> {
        if (history.isEmpty()) return emptyMap()
        val result = HashMap<String, Double>()
        history.forEach { entry ->
            val artist = entry.artist.trim().lowercase()
            if (artist.isBlank()) return@forEach
            val ageDays = (nowMs - entry.playedAtMs).coerceAtLeast(0L).toDouble() / DAY_MS
            val sign = if (entry.completed) 1.0 else SKIP_WEIGHT
            val weight = exp(-ageDays / AFFINITY_HALF_LIFE_DAYS) * sign
            result[artist] = (result[artist] ?: 0.0) + weight
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
     * −0.2 за пустого артиста; +0.3 локальному при [localBias]; + (novelty/100)·0.9
     * за нового артиста; штраф за артиста из свежей истории при novelty ≥ [HIGH_NOVELTY].
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
        val knownArtists = if (tuning.useHistory) historyArtists(history) else emptySet()
        val recentArtists = if (tuning.useHistory) recentArtists(history, nowMs) else emptySet()

        fun weightOf(track: Track): Double {
            val artist = track.artist.trim().lowercase()
            var weight = BASE_WEIGHT
            if (track.id in favoriteIds) weight += FAVORITE_TRACK_BONUS
            if (artist.isNotBlank() && artist in favoriteArtistKeys) {
                weight += FAVORITE_ARTIST_BONUS * (1.0 - novelty / 150.0)
            }
            weight += (affinity[artist] ?: 0.0) * (1.0 - novelty / 160.0)
            weight += minOf(artistCountKeys[artist] ?: 0, 8) * POPULARITY_STEP
            if (artist.isBlank()) weight -= BLANK_ARTIST_PENALTY
            if (localBias && track.source == Source.LOCAL) weight += LOCAL_BONUS
            val newArtist = artist.isNotBlank() &&
                artist !in favoriteArtistKeys &&
                (artistCountKeys[artist] ?: 0) == 0 &&
                artist !in knownArtists
            if (newArtist) weight += novelty / 100.0 * NEW_ARTIST_BONUS
            if (novelty >= HIGH_NOVELTY && artist in recentArtists) {
                weight -= (novelty - HIGH_NOVELTY) / 40.0 * RECENT_ARTIST_PENALTY
            }
            return weight.coerceAtLeast(MIN_WEIGHT)
        }

        // Взвешенная выборка без повторов по ключам Efraimidis–Spirakis:
        // key = -ln(U)/weight, затем сортировка по возрастанию. Это O(n log n)
        // вместо O(n²) у пошагового пересчёта суммы весов, распределение то же.
        return candidates
            .map { track ->
                val weight = weightOf(track).coerceAtLeast(MIN_WEIGHT)
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
