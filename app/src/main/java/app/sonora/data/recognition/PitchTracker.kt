package app.sonora.data.recognition

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Результат трекинга высоты тона: для каждого кадра — частота основного тона
 * (0 Гц, если кадр невокализованный/тихий) и время начала кадра.
 */
data class PitchTrack(
    val timesMs: IntArray,
    val f0Hz: FloatArray,
    val voiced: BooleanArray,
) {
    val isEmpty: Boolean get() = f0Hz.isEmpty()
}

/**
 * Детектор высоты тона на основе YIN (de Cheveigné & Kawahara, 2002) —
 * быстрый и устойчивый к шуму вариант autocorrelation для напетой мелодии.
 *
 * Работает на 8 кГц (для голоса/свиста 70–1100 Гц этого достаточно),
 * окно 64 мс, шаг 25 мс, нормализованная разностная функция с порогом
 * апериодичности и параболической интерполяцией лага. Чистый Kotlin без Android.
 */
object PitchTracker {

    /** Частота, к которой приводится входной сигнал. */
    const val SAMPLE_RATE = 8_000
    const val FRAME_SIZE = 512
    const val HOP_SIZE = 200

    const val MIN_F0_HZ = 70f
    const val MAX_F0_HZ = 1_100f

    /** Ниже этого RMS кадр считается тишиной. */
    const val MIN_RMS = 0.004f

    /** Порог нормированной разностной функции YIN. */
    private const val YIN_THRESHOLD = 0.10

    /**
     * Если ни один лаг не прошёл [YIN_THRESHOLD], берём лучший, но только если
     * он ниже этого значения. Иначе кадр — шум/согласная, а не мелодия.
     */
    private const val YIN_ABSOLUTE = 0.30

    /** Сколько сэмплов участвует в разностной функции. */
    private const val DIFFERENCE_WINDOW = 320

    fun track(samples: ShortArray, sampleRate: Int = Pcm.SAMPLE_RATE): PitchTrack {
        val mono = if (sampleRate == SAMPLE_RATE) {
            samples
        } else {
            Pcm.resample(samples, sampleRate, SAMPLE_RATE)
        }
        val tauMax = (SAMPLE_RATE / MIN_F0_HZ).toInt()
        val tauMin = max(2, (SAMPLE_RATE / MAX_F0_HZ).toInt())
        val needed = DIFFERENCE_WINDOW + tauMax
        if (mono.size < needed) {
            return PitchTrack(IntArray(0), FloatArray(0), BooleanArray(0))
        }
        val frameCount = (mono.size - needed) / HOP_SIZE + 1
        val times = IntArray(frameCount) { frame -> frame * HOP_SIZE * 1000 / SAMPLE_RATE }
        val f0 = FloatArray(frameCount)
        val voiced = BooleanArray(frameCount)
        val buffer = DoubleArray(needed)
        val difference = DoubleArray(tauMax + 1)

        for (frame in 0 until frameCount) {
            val start = frame * HOP_SIZE
            var mean = 0.0
            for (index in 0 until needed) {
                val value = mono[start + index] / 32768.0
                buffer[index] = value
                mean += value
            }
            mean /= needed
            var energy = 0.0
            for (index in 0 until needed) {
                buffer[index] -= mean
                energy += buffer[index] * buffer[index]
            }
            val rms = sqrt(energy / needed)
            if (rms < MIN_RMS) continue

            var runningSum = 0.0
            difference[0] = 0.0
            for (tau in 1..tauMax) {
                var sum = 0.0
                for (j in 0 until DIFFERENCE_WINDOW) {
                    val delta = buffer[j] - buffer[j + tau]
                    sum += delta * delta
                }
                difference[tau] = sum
            }

            // Нормированная разностная функция YIN: d'(tau) = d(tau) * tau / Σ d(1..tau).
            var cumulative = 0.0
            var bestTau = -1
            var bestValue = Double.MAX_VALUE
            var belowThreshold = false
            for (tau in 1..tauMax) {
                cumulative += difference[tau]
                if (tau < tauMin) continue
                val normalized = if (cumulative <= 1e-12) {
                    1.0
                } else {
                    difference[tau] * tau / cumulative
                }
                if (normalized < YIN_THRESHOLD) {
                    bestTau = tau
                    belowThreshold = true
                    break
                }
                if (normalized < bestValue) {
                    bestValue = normalized
                    bestTau = tau
                }
            }
            runningSum = cumulative
            if (runningSum <= 1e-12) continue
            if (bestTau < 0) continue
            if (!belowThreshold && bestValue > YIN_ABSOLUTE) continue

            // Уточняем период: первый «провал» YIN может попасть на 5–10 % раньше
            // истинного периода (например, на чистом синусе). В пределах +1/3 лага
            // берём лаг с минимальной ненормированной разностью — это и есть период.
            if (belowThreshold) {
                val upperTau = minOf(tauMax, bestTau + maxOf(2, bestTau / 3))
                var refinedBestTau = bestTau
                var minimalDifference = difference[bestTau]
                for (tau in bestTau + 1..upperTau) {
                    if (difference[tau] < minimalDifference) {
                        minimalDifference = difference[tau]
                        refinedBestTau = tau
                    }
                }
                bestTau = refinedBestTau
            }

            var refinedTau = bestTau.toDouble()
            if (bestTau > tauMin && bestTau < tauMax) {
                val left = difference[bestTau - 1]
                val center = difference[bestTau]
                val right = difference[bestTau + 1]
                val denominator = left - 2.0 * center + right
                if (abs(denominator) > 1e-12) {
                    val shift = 0.5 * (left - right) / denominator
                    refinedTau += shift.coerceIn(-0.5, 0.5)
                }
            }
            if (refinedTau <= 0.0) continue
            val frequency = (SAMPLE_RATE / refinedTau).toFloat()
            if (frequency !in MIN_F0_HZ..MAX_F0_HZ) continue
            f0[frame] = frequency
            voiced[frame] = true
        }
        return PitchTrack(times, f0, voiced)
    }
}
