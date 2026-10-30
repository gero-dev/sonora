package app.sonora.data.wave

import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.core.settings.WaveSettings
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

/**
 * Всё, что влияет на порядок кандидатов. Собирается один раз на подборку,
 * поэтому ранжирование остаётся O(n log n) даже на большой фонотеке.
 */
data class WaveRankContext(
    val tuning: WaveSettings = WaveSettings(),
    val favoriteIds: Set<String> = emptySet(),
    val favoriteArtists: Set<String> = emptySet(),
    val artistCounts: Map<String, Int> = emptyMap(),
    val history: List<WaveHistoryEntry> = emptyList(),
    /** Аудио-фичи ReccoBeats: похожесть на недавние дослушанные треки. */
    val featureIndex: WaveFeatureIndex = WaveFeatureIndex.EMPTY,
    /** Активное настроение: вклад в счёт, помимо жёсткого фильтра. */
    val mood: WaveMood = WaveMood.ANY,
    /** Локальная часть получает бонус «своей» музыки. */
    val localBias: Boolean = true,
    val nowMs: Long = System.currentTimeMillis(),
    /** Недавно игравшие треки; null — посчитать из истории. */
    val recentTrackKeys: Set<String>? = null,
    /** Сид для взвешенной выборки: одинаковый сид → одинаковый порядок (тесты). */
    val seed: Long = 0L,
)

/**
 * Взвешенное ранжирование «Моей волны».
 *
 * Счёт трека складывается из истории прослушиваний (полураспад 45 дней, штраф
 * за пропуски), избранного, любимых и популярных исполнителей, новизны,
 * **похожести аудио-фич на недавние дослушанные треки** и соответствия
 * настроению. Затем — взвешенная случайная выборка Efraimidis–Spirakis:
 * подборка каждый раз новая, но при одинаковом сиде — детерминированная.
 */
object WaveRanker {

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Минимальный вес: слабее — уже не кандидат. */
    private const val MIN_WEIGHT = 0.05

    /** Защита от U = 0: ln(0) не определён. */
    private const val MIN_RANDOM = 1e-12

    private const val BASE_WEIGHT = 1.0
    private const val FAVORITE_TRACK_BONUS = 2.5
    private const val FAVORITE_ARTIST_BONUS = 1.6
    private const val POPULARITY_STEP = 0.12
    private const val BLANK_ARTIST_PENALTY = 0.2
    private const val LOCAL_BONUS = 0.3
    private const val ONLINE_DISCOVERY_BONUS = 0.35
    private const val NEW_ARTIST_BONUS = 0.9
    private const val HIGH_NOVELTY = 60
    private const val RECENT_ARTIST_PENALTY = 0.6
    private const val TRACK_AFFINITY_BONUS = 1.2

    /** Вклад похожести на «эталон вкуса» (косинус 0..1). */
    private const val FEATURE_SIM_BONUS = 0.9

    /** Вклад соответствия настроению (0..1). */
    private const val MOOD_FIT_BONUS = 1.1

    /**
     * Взвешенная выборка без повторов. [random] передаётся явно — так тесты
     * получают воспроизводимый порядок.
     */
    fun rank(
        candidates: List<Track>,
        context: WaveRankContext,
        random: Random = Random(context.seed),
    ): List<Track> {
        if (candidates.isEmpty()) return emptyList()
        val prepared = Prepared(context)
        return candidates
            .mapNotNull { track ->
                val weight = prepared.weightOf(track)
                if (weight < MIN_WEIGHT) return@mapNotNull null
                val u = random.nextDouble(0.0, 1.0).coerceAtLeast(MIN_RANDOM)
                track to -ln(u) / weight
            }
            .sortedBy { it.second }
            .map { it.first }
    }

    /** Итоговый вес одного трека (для диагностики и фонового переупорядочивания). */
    fun score(track: Track, context: WaveRankContext): Double =
        Prepared(context).weightOf(track)

    /**
     * Детерминированно переставляет треки по убыванию веса — используется,
     * когда в фоне догрузились фичи ReccoBeats: набор треков не меняется,
     * меняется только их порядок.
     */
    fun rankByScore(tracks: List<Track>, context: WaveRankContext): List<Track> {
        if (tracks.size < 2) return tracks
        val prepared = Prepared(context)
        return tracks
            .map { it to prepared.weightOf(it) }
            .sortedWith(
                compareByDescending<Pair<Track, Double>> { it.second }
                    .thenBy { it.first.title.lowercase() }
                    .thenBy { it.first.id },
            )
            .map { it.first }
    }

    /** Предвычисленные индексы: история и артисты разбираются один раз. */
    private class Prepared(context: WaveRankContext) {
        private val tuning = context.tuning
        private val discovery = tuning.novelty.coerceIn(0, 100) / 100.0
        private val useHistory = tuning.useHistory
        private val favoriteIds = context.favoriteIds
        private val featureIndex = context.featureIndex
        private val mood = context.mood
        private val localBias = context.localBias

        private val favoriteArtistKeys: Set<String> =
            context.favoriteArtists.mapTo(HashSet()) { it.trim().lowercase() }

        private val artistCountKeys: Map<String, Int> = buildMap {
            context.artistCounts.forEach { (raw, count) ->
                val key = raw.trim().lowercase()
                if (key.isNotBlank()) put(key, (get(key) ?: 0) + count)
            }
        }

        private val affinity: Map<String, Double> =
            if (useHistory) WaveEngine.artistAffinity(context.history, context.nowMs) else emptyMap()

        private val trackScores: Map<String, Double> =
            if (useHistory) WaveEngine.trackEngagement(context.history, context.nowMs) else emptyMap()

        private val knownArtists: Set<String> =
            if (useHistory) {
                context.history.mapNotNullTo(HashSet()) { entry ->
                    entry.artist.trim().lowercase().takeIf { it.isNotBlank() }
                }
            } else {
                emptySet()
            }

        private val recentArtists: Set<String> =
            if (useHistory) {
                val threshold = context.nowMs - 2 * DAY_MS
                context.history.mapNotNullTo(HashSet()) { entry ->
                    val artist = entry.artist.trim().lowercase()
                    if (entry.playedAtMs >= threshold && artist.isNotBlank()) artist else null
                }
            } else {
                emptySet()
            }

        private val recentTracks: Set<String> = context.recentTrackKeys
            ?: if (useHistory) {
                WaveEngine.recentKeys(context.history, context.nowMs)
            } else {
                emptySet()
            }

        fun weightOf(track: Track): Double {
            val artist = track.artist.trim().lowercase()
            val key = WaveEngine.waveKey(track)

            var weight = BASE_WEIGHT
            if (track.id in favoriteIds) weight += FAVORITE_TRACK_BONUS
            if (artist.isNotBlank() && artist in favoriteArtistKeys) {
                weight += FAVORITE_ARTIST_BONUS * (1.0 - discovery * 0.7)
            }
            weight += (affinity[artist] ?: 0.0) * (1.0 - discovery * 0.6)
            weight += (trackScores[key] ?: 0.0) * TRACK_AFFINITY_BONUS * (1.0 - discovery * 0.8)
            weight += minOf(artistCountKeys[artist] ?: 0, 8) * POPULARITY_STEP
            if (artist.isBlank()) weight -= BLANK_ARTIST_PENALTY

            // Чем выше новизна, тем меньше «своей» музыки в начале подборки
            // и тем охотнее волна уходит в онлайн-новинки.
            if (track.source == Source.LOCAL) {
                if (localBias) weight += LOCAL_BONUS * (1.0 - discovery * 0.8)
            } else {
                weight += ONLINE_DISCOVERY_BONUS * discovery
            }

            val newArtist = artist.isNotBlank() &&
                artist !in favoriteArtistKeys &&
                (artistCountKeys[artist] ?: 0) == 0 &&
                artist !in knownArtists
            if (newArtist) weight += NEW_ARTIST_BONUS * (0.3 + discovery * 0.9)

            if (tuning.novelty >= HIGH_NOVELTY && artist in recentArtists) {
                weight -= (tuning.novelty - HIGH_NOVELTY) / 40.0 * RECENT_ARTIST_PENALTY
            }

            // ReccoBeats: похоже на то, что пользователь реально дослушивает.
            if (!featureIndex.isEmpty || featureIndex.seedCount > 0) {
                val similarity = featureIndex.similarityToSeeds(track)
                if (similarity > 0.0) {
                    weight += FEATURE_SIM_BONUS * similarity * (1.0 - discovery * 0.5)
                }
            }

            // Настроение: мягкий бонус поверх жёсткого фильтра.
            if (mood != WaveMood.ANY) {
                val fit = WaveMoodMatcher.score(track, mood, featureIndex.forTrack(track))
                weight += MOOD_FIT_BONUS * fit
            }

            // Недавно игравший трек отодвигаем, но не выбрасываем: иначе
            // у небольшой фонотеки волна остаётся пустой (жёстко свежее
            // вырезает WaveFilterEngine при высокой новизне).
            if (key in recentTracks) {
                weight *= 1.0 - 0.75 * (0.4 + discovery * 0.6)
            }
            return weight.coerceAtLeast(0.0)
        }
    }

    /** Полураспад веса истории: вынесен сюда как часть контракта ранжирования. */
    const val AFFINITY_HALF_LIFE_DAYS = 45.0

    /** Декоративное: exp(0) = 1 — используется в тестах отношения весов. */
    fun decayFactor(ageDays: Double): Double = exp(-ageDays / AFFINITY_HALF_LIFE_DAYS)
}
