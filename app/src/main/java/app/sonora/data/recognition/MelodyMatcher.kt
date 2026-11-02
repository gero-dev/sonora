package app.sonora.data.recognition

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** Индексный контур для сравнения (id трека + его мелодия). */
data class IndexedMelody(val id: String, val contour: MelodyContour)

/** Кандидат, найденный по напетой мелодии. */
data class MelodyCandidate(val id: String, val score: Double)

/**
 * Сравнение мелодических контуров через DTW (Dynamic Time Warping).
 *
 * DTW устойчив к разному темпу: напетую мелодию можно растянуть/сжать по времени
 * и всё равно сопоставить с треком. Сравниваются относительные высоты
 * (полутоны от медианы), поэтому транспонирование не мешает. Невокализованные
 * кадры штрафуются, но не ломают сопоставление (паузы/согласные).
 *
 * Оценка similarity: 1 — контуры совпали, 0 — ничего общего. Чистый Kotlin
 * без Android — покрывается unit-тестами.
 */
object MelodyMatcher {

    /**
     * Порог для «это та же мелодия». На синтетике верная мелодия даёт 0.85–1.0
     * даже при транспонировании, смене темпа на ±30 % и шуме, чужие мелодии —
     * обычно ниже 0.70. Порог 0.72 отсекает случайные совпадения, но оставляет
     * запас на живого человека, который поёт неточно.
     */
    const val MIN_SCORE = 0.72

    /** Максимальный штраф за расхождение высот (полутонов) на одном кадре. */
    private const val MAX_FRAME_DISTANCE = 4.0
    private const val UNVOICED_PENALTY = 3.0
    private const val BOTH_UNVOICED_COST = 0.4
    private const val INF = 1e18

    /** Насколько сильно путь может отклоняться от диагонали (Sakoe-Chiba band). */
    private const val BAND_DIVISOR = 4.0
    private const val MIN_BAND = 8.0

    fun similarity(query: MelodyContour, track: MelodyContour): Double {
        if (!query.isUsable || !track.isUsable) return 0.0
        val n = query.size
        val m = track.size
        val band = max(MIN_BAND, max(n, m) / BAND_DIVISOR)

        var previous = DoubleArray(m + 1) { INF }
        var current = DoubleArray(m + 1) { INF }
        previous[0] = 0.0

        for (i in 1..n) {
            current.fill(INF)
            val center = i.toDouble() * m / n
            val startJ = max(1, floor(center - band).toInt())
            val endJ = minOf(m, ceil(center + band).toInt())
            for (j in startJ..endJ) {
                val best = minOf(previous[j], previous[j - 1], current[j - 1])
                if (best >= INF) continue
                current[j] = best + frameDistance(query.semitones[i - 1], track.semitones[j - 1])
            }
            val swap = previous
            previous = current
            current = swap
        }

        val cost = previous[m]
        if (cost >= INF) return 0.0
        val normalized = cost / max(n, m)
        return (1.0 - normalized / MAX_FRAME_DISTANCE).coerceIn(0.0, 1.0)
    }

    /** Ранжирует набор контуров и возвращает top-N с оценкой не ниже [minScore]. */
    fun rank(
        query: MelodyContour,
        entries: List<IndexedMelody>,
        limit: Int = 5,
        minScore: Double = MIN_SCORE,
    ): List<MelodyCandidate> {
        if (!query.isUsable || entries.isEmpty()) return emptyList()
        val candidates = ArrayList<MelodyCandidate>(limit)
        for (entry in entries) {
            val score = similarity(query, entry.contour)
            if (score >= minScore) candidates.add(MelodyCandidate(entry.id, score))
        }
        candidates.sortByDescending { it.score }
        return if (candidates.size > limit) candidates.subList(0, limit).toList() else candidates
    }

    private fun frameDistance(queryValue: Float, trackValue: Float): Double {
        val queryVoiced = !queryValue.isNaN()
        val trackVoiced = !trackValue.isNaN()
        return when {
            queryVoiced && trackVoiced ->
                minOf(abs(queryValue - trackValue).toDouble(), MAX_FRAME_DISTANCE)
            queryVoiced || trackVoiced -> UNVOICED_PENALTY
            else -> BOTH_UNVOICED_COST
        }
    }
}
