package app.sonora.data.wave

import app.sonora.core.model.Track
import app.sonora.data.telemetry.AudioFeature
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Проверка и оценка настроения.
 *
 * Фильтр настроения обязан быть жёстким: трек попадает в волну, только если
 * он реально подходит. Поэтому проверка двухступенчатая:
 *
 *  1. **Аудио-фичи ReccoBeats** — профиль настроения задан точкой в
 *     нормированном пространстве (энергия, валентность, танцевальность,
 *     акустичность, инструментальность, темп). Трек подходит, если
 *     взвешенное расстояние до профиля не больше допуска.
 *  2. **Подсказки в тексте** (жанр/название/альбом) — используются, когда
 *     фич ещё нет: бакет настроения по фичам строится в фоне, и до этого
 *     момента волна не должна «ломаться» в пустоту.
 *
 * Если ни фичи, ни подсказки не подтвердили настроение — трек отсеивается.
 * Никаких «если совпадений мало, снимаем фильтр» здесь нет.
 */
object WaveMoodMatcher {

    /** Достаточное число известных измерений, чтобы доверять фичам. */
    private const val MIN_FEATURE_DIMS = 3

    /** Темп нормируется так: 200 BPM → 1.0. */
    private const val TEMPO_SCALE = 200.0

    /** Веса измерений: энергия и валентность важнее темпа. */
    private val WEIGHTS = doubleArrayOf(1.6, 1.2, 1.0, 1.2, 1.0, 0.8)

    /** Профиль настроения в аудио-пространстве. */
    data class MoodProfile(
        val energy: Double,
        val valence: Double,
        val danceability: Double,
        val acousticness: Double,
        val instrumentalness: Double,
        /** Темп, BPM (нормируется при сравнении). */
        val tempoBpm: Double,
        /** Допустимое взвешенное расстояние (0..1). */
        val tolerance: Double,
        /** Подсказки в жанре/названии для треков без фич. */
        val keywords: List<String>,
    )

    private val PROFILES: Map<WaveMood, MoodProfile> = mapOf(
        WaveMood.CALM to MoodProfile(
            energy = 0.25,
            valence = 0.45,
            danceability = 0.30,
            acousticness = 0.70,
            instrumentalness = 0.55,
            tempoBpm = 90.0,
            tolerance = 0.34,
            keywords = listOf(
                "chill", "acoustic", "ambient", "piano", "lofi", "lo-fi", "soft", "calm",
                "sleep", "relax", "спокой", "акуст", "эмбиент", "пианино",
            ),
        ),
        WaveMood.FOCUS to MoodProfile(
            energy = 0.45,
            valence = 0.42,
            danceability = 0.32,
            acousticness = 0.55,
            instrumentalness = 0.88,
            tempoBpm = 100.0,
            tolerance = 0.34,
            keywords = listOf(
                "instrumental", "focus", "study", "ambient", "piano", "beat",
                "concentration", "инструмент", "фокус", "концентрац",
            ),
        ),
        WaveMood.ENERGY to MoodProfile(
            energy = 0.88,
            valence = 0.62,
            danceability = 0.82,
            acousticness = 0.10,
            instrumentalness = 0.30,
            tempoBpm = 132.0,
            tolerance = 0.34,
            keywords = listOf(
                "dance", "workout", "rock", "metal", "drum", "bass", "edm", "party",
                "energy", "танц", "энергич", "рок", "метал",
            ),
        ),
        WaveMood.NIGHT to MoodProfile(
            energy = 0.48,
            valence = 0.25,
            danceability = 0.48,
            acousticness = 0.28,
            instrumentalness = 0.58,
            tempoBpm = 110.0,
            tolerance = 0.34,
            keywords = listOf(
                "night", "drive", "synth", "dark", "midnight", "noir",
                "ноч", "драйв", "синт",
            ),
        ),
        WaveMood.HAPPY to MoodProfile(
            energy = 0.75,
            valence = 0.88,
            danceability = 0.75,
            acousticness = 0.20,
            instrumentalness = 0.18,
            tempoBpm = 124.0,
            tolerance = 0.34,
            keywords = listOf(
                "happy", "pop", "upbeat", "sunny", "funk", "disco", "smile",
                "весел", "поп", "солнечн",
            ),
        ),
    )

    fun profileFor(mood: WaveMood): MoodProfile? = PROFILES[mood]

    fun keywordsFor(mood: WaveMood): List<String> = PROFILES[mood]?.keywords.orEmpty()

    /**
     * Подходит ли трек настроению. Это жёсткое решение фильтра:
     * ANY пропускает всё, иначе нужен подтверждающий сигнал.
     */
    fun matches(track: Track, mood: WaveMood, feature: AudioFeature?): Boolean {
        if (mood == WaveMood.ANY) return true
        if (matchesByFeature(feature, mood) == true) return true
        return matchesByKeywords(track, mood)
    }

    /**
     * Оценка соответствия настроению 0..1 для ранжирования. Трек может пройти
     * фильтр по подсказке, но получить низкую оценку — тогда он окажется ниже
     * в подборке, а не будет выброшен.
     */
    fun score(track: Track, mood: WaveMood, feature: AudioFeature?): Double {
        if (mood == WaveMood.ANY) return 1.0
        val byFeature = featureDistance(feature, mood)?.let { distance ->
            (1.0 - distance).coerceIn(0.0, 1.0)
        }
        val byKeywords = if (matchesByKeywords(track, mood)) 0.65 else 0.0
        return maxOf(byFeature ?: 0.0, byKeywords)
    }

    /** true/false по фичам; null — фич недостаточно, нужна текстовая подсказка. */
    fun matchesByFeature(feature: AudioFeature?, mood: WaveMood): Boolean? {
        val distance = featureDistance(feature, mood) ?: return null
        return distance <= (PROFILES[mood]?.tolerance ?: return null)
    }

    /** Взвешенное расстояние до профиля; null, если известных измерений мало. */
    fun featureDistance(feature: AudioFeature?, mood: WaveMood): Double? {
        if (feature == null) return null
        val profile = PROFILES[mood] ?: return null
        val actual = arrayOf(
            feature.energy,
            feature.valence,
            feature.danceability,
            feature.acousticness,
            feature.instrumentalness,
            feature.tempo?.let { (it / TEMPO_SCALE).coerceIn(0.0, 1.0) },
        )
        val target = doubleArrayOf(
            profile.energy,
            profile.valence,
            profile.danceability,
            profile.acousticness,
            profile.instrumentalness,
            (profile.tempoBpm / TEMPO_SCALE).coerceIn(0.0, 1.0),
        )
        var weighted = 0.0
        var weightSum = 0.0
        var known = 0
        for (i in actual.indices) {
            val value = actual[i] ?: continue
            val weight = WEIGHTS[i]
            weighted += weight * (value - target[i]) * (value - target[i])
            weightSum += weight
            known++
        }
        if (known < MIN_FEATURE_DIMS || weightSum <= 0.0) return null
        return sqrt(weighted / weightSum)
    }

    /** Проверка по жанру/названию/альбому: до появления фич это единственный сигнал. */
    fun matchesByKeywords(track: Track, mood: WaveMood): Boolean {
        val keywords = PROFILES[mood]?.keywords ?: return mood == WaveMood.ANY
        if (keywords.isEmpty()) return false
        val haystack = haystackOf(track)
        return keywords.any { haystack.contains(it) }
    }

    private fun haystackOf(track: Track): String = buildString {
        append(track.title).append(' ')
        append(track.album.orEmpty()).append(' ')
        append(track.extra["genre"].orEmpty()).append(' ')
        append(track.extra["mood"].orEmpty())
    }.lowercase().replace('ё', 'е')

    /** Расстояние по одному измерению (для тестов и диагностики). */
    fun dimensionGap(actual: Double?, target: Double?): Double? {
        if (actual == null || target == null) return null
        return abs(actual - target)
    }
}
