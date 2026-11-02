package app.sonora.data.recognition

import kotlin.math.log2
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Мелодический контур: последовательность высот тона в полутонах (MIDI-ноты)
 * с привязкой ко времени. Невыделенные кадры помечены `Float.NaN`.
 *
 * Контур нормируется по медиане (транспонирование не влияет) и сглаживается
 * медианным фильтром — так напетую мелодию можно сравнивать с мелодией трека
 * независимо от тональности, регистра и темпа (темп учитывает DTW).
 *
 * Чистый Kotlin без Android: используется и для микрофона, и для локальных
 * файлов, и в unit-тестах.
 */
class MelodyContour(
    /** Время начала кадра в секундах, 0 — начало контура. */
    val timesSec: FloatArray,
    /** Высота в полутонах относительно медианы; `NaN` — невокализованный кадр. */
    val semitones: FloatArray,
) {

    val size: Int get() = semitones.size

    val voiced: BooleanArray = BooleanArray(semitones.size) { !semitones[it].isNaN() }

    val voicedCount: Int = voiced.count { it }

    val durationSec: Float
        get() = if (size == 0) 0f else timesSec[size - 1] - timesSec[0]

    /** Достаточно ли данных, чтобы вообще пытаться сравнивать контуры. */
    val isUsable: Boolean
        get() = size >= MIN_VOICED_FRAMES &&
            voicedCount >= MIN_VOICED_FRAMES &&
            voicedCount.toFloat() / size >= MIN_VOICED_FRACTION &&
            durationSec >= MIN_DURATION_SEC

    companion object {
        const val MIN_VOICED_FRAMES = 10
        const val MIN_VOICED_FRACTION = 0.25f
        const val MIN_DURATION_SEC = 1.5f

        /** По умолчанию не анализируем больше 30 секунд мелодии. */
        const val DEFAULT_MAX_DURATION_SEC = 30f

        private const val MEDIAN_WINDOW = 5

        /**
         * Строит контур из PCM 16 кГц моно. Возвращает null, если мелодию
         * выделить не удалось (тишина, шум, слишком короткий фрагмент).
         */
        fun fromSamples(
            samples: ShortArray,
            sampleRate: Int = Pcm.SAMPLE_RATE,
            maxDurationSec: Float = DEFAULT_MAX_DURATION_SEC,
        ): MelodyContour? {
            if (samples.size < PitchTracker.FRAME_SIZE) return null
            val pitch = PitchTracker.track(samples, sampleRate)
            if (pitch.isEmpty) return null

            val frameMs = PitchTracker.HOP_SIZE * 1000f / PitchTracker.SAMPLE_RATE
            val maxFrames = max(
                1,
                ((maxDurationSec * 1000f) / frameMs).roundToInt(),
            ).coerceAtMost(pitch.f0Hz.size)

            // Частота → MIDI-нота (полутоны), невокализованные кадры — NaN.
            val midi = FloatArray(maxFrames) { index ->
                val frequency = pitch.f0Hz[index]
                if (frequency <= 0f) {
                    Float.NaN
                } else {
                    (69.0 + 12.0 * log2(frequency / 440.0)).toFloat()
                }
            }
            val smoothed = medianFilter(midi)

            var first = -1
            var last = -1
            for (index in smoothed.indices) {
                if (!smoothed[index].isNaN()) {
                    if (first < 0) first = index
                    last = index
                }
            }
            if (first < 0 || last < first) return null
            val length = last - first + 1
            if (length < MIN_VOICED_FRAMES) return null

            var voicedValues = 0
            for (index in first..last) if (!smoothed[index].isNaN()) voicedValues++
            if (voicedValues < MIN_VOICED_FRAMES) return null

            // Нормировка по медиане: мелодия в любой тональности даёт похожий контур.
            val pitchValues = FloatArray(voicedValues)
            var pitchCursor = 0
            for (index in first..last) {
                val value = smoothed[index]
                if (!value.isNaN()) pitchValues[pitchCursor++] = value
            }
            val median = medianOf(pitchValues, pitchValues.size)

            val times = FloatArray(length) { index ->
                (pitch.timesMs[first + index] - pitch.timesMs[first]) / 1000f
            }
            val semitones = FloatArray(length) { index ->
                val value = smoothed[first + index]
                if (value.isNaN()) Float.NaN else value - median
            }
            return MelodyContour(times, semitones)
        }

        /** Восстановление контура из кэша. */
        internal fun fromFrames(timesSec: FloatArray, semitones: FloatArray): MelodyContour =
            MelodyContour(timesSec, semitones)

        /** Медианный фильтр по окну; невокализованные кадры в медиану не входят. */
        private fun medianFilter(source: FloatArray): FloatArray {
            if (source.isEmpty()) return source
            val result = FloatArray(source.size)
            val window = FloatArray(MEDIAN_WINDOW)
            val half = MEDIAN_WINDOW / 2
            for (index in source.indices) {
                var count = 0
                for (offset in -half..half) {
                    val position = index + offset
                    if (position !in source.indices) continue
                    val value = source[position]
                    if (!value.isNaN()) window[count++] = value
                }
                result[index] = if (count == 0) Float.NaN else medianOf(window, count)
            }
            return result
        }

        private fun medianOf(values: FloatArray, count: Int): Float {
            if (count <= 0) return Float.NaN
            val copy = FloatArray(count) { values[it] }
            copy.sort()
            val middle = count / 2
            return if (count % 2 == 1) copy[middle] else (copy[middle - 1] + copy[middle]) / 2f
        }
    }
}
