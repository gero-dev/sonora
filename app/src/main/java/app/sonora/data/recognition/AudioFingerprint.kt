package app.sonora.data.recognition

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Упрощённый спектральный отпечаток фрагмента (рифф на «Шазам» без нативных библиотек).
 *
 * Идея: сигнал режется на кадры по 2048 сэмплов с перекрытием, для каждого кадра
 * считается FFT, энергия раскладывается по 12 лог-полосам (120–4200 Гц) и
 * сглаживается по времени. Отпечаток — это:
 *  - [hashes]: 12 бит «выросла ли энергия полосы относительно предыдущего кадра»
 *    (быстрый инвертированный индекс для поиска смещения);
 *  - [deltas]: сами приращения лог-энергий по полосам (косинус между ними даёт
 *    устойчивую к шуму оценку «та же это запись или нет»).
 *
 * Честные ограничения: это не нейросетевой fingerprint. Он уверенно ловит ту же
 * запись (в т. ч. перекодированную, сдвинутую по времени и записанную с шумом),
 * но не заменяет AudD для распознавания незнакомой музыки. Отпечаток считается
 * по локальным файлам и по фрагменту с микрофона одним и тем же кодом.
 */
class AudioFingerprint(
    /** 12-битный хэш на кадр (биты роста энергии по полосам). */
    val hashes: IntArray,
    /** Приращения лог-энергий полос: [frameCount] * [BANDS]. */
    val deltas: FloatArray,
    val frameCount: Int,
    /** RMS исходного сигнала 0..1 — чтобы отличить тишину от музыки. */
    val rms: Float,
) {

    val isEmpty: Boolean get() = frameCount <= 0

    companion object {
        const val BANDS = 12
        const val FRAME_SIZE = 2048
        const val HOP_SIZE = 1024
        const val SAMPLE_RATE = Pcm.SAMPLE_RATE
        const val HASH_MASK = 0xFFF

        /** Порог тишины: ниже него фрагмент бессмысленно и распознавать, и сверять. */
        const val MIN_RMS = 0.008f
        const val MIN_FRAMES = 8

        /** Насколько дБ должна вырасти энергия полосы, чтобы поставить бит. */
        private const val DELTA_THRESHOLD_DB = 1.0f

        private const val LOW_HZ = 120.0
        private const val HIGH_HZ = 4200.0

        fun compute(samples: ShortArray): AudioFingerprint {
            val frameCount = if (samples.size < FRAME_SIZE) {
                0
            } else {
                (samples.size - FRAME_SIZE) / HOP_SIZE + 1
            }
            if (frameCount <= 0) {
                return AudioFingerprint(IntArray(0), FloatArray(0), 0, Pcm.rms(samples))
            }

            val energy = FloatArray(frameCount * BANDS)
            val window = hannWindow(FRAME_SIZE)
            val bandRanges = bandRanges(FRAME_SIZE)
            val re = DoubleArray(FRAME_SIZE)
            val im = DoubleArray(FRAME_SIZE)

            // Первый проход: лог-энергии полос на каждом кадре.
            for (frame in 0 until frameCount) {
                val start = frame * HOP_SIZE
                for (i in 0 until FRAME_SIZE) {
                    re[i] = (samples[start + i] / 32768.0) * window[i]
                    im[i] = 0.0
                }
                fft(re, im)

                for (band in 0 until BANDS) {
                    val range = bandRanges[band]
                    var energySum = 0.0
                    for (bin in range) {
                        energySum += re[bin] * re[bin] + im[bin] * im[bin]
                    }
                    // Децибелы: громкие полосы не должны доминировать над тихими.
                    energy[frame * BANDS + band] = (10.0 * log10(energySum + 1e-12)).toFloat()
                }
            }

            // Второй проход: сглаживание по времени — даёт устойчивость к шуму
            // микрофона (одиночные всплески почти не меняют приращения).
            val smooth = FloatArray(frameCount * BANDS)
            for (frame in 0 until frameCount) {
                for (band in 0 until BANDS) {
                    val current = energy[frame * BANDS + band]
                    val previous = if (frame > 0) energy[(frame - 1) * BANDS + band] else current
                    val next = if (frame < frameCount - 1) {
                        energy[(frame + 1) * BANDS + band]
                    } else {
                        current
                    }
                    smooth[frame * BANDS + band] =
                        0.25f * previous + 0.5f * current + 0.25f * next
                }
            }

            // Третий проход: приращения и биты.
            val deltas = FloatArray(frameCount * BANDS)
            val hashes = IntArray(frameCount)
            for (frame in 1 until frameCount) {
                var bits = 0
                for (band in 0 until BANDS) {
                    val index = frame * BANDS + band
                    val delta = smooth[index] - smooth[index - BANDS]
                    deltas[index] = delta
                    if (delta > DELTA_THRESHOLD_DB) bits = bits or (1 shl band)
                }
                hashes[frame] = bits
            }
            return AudioFingerprint(hashes, deltas, frameCount, Pcm.rms(samples))
        }

        private fun hannWindow(size: Int): DoubleArray = DoubleArray(size) { i ->
            0.5 * (1.0 - cos(2.0 * PI * i / (size - 1)))
        }

        /** Индексы FFT-бинов для каждой лог-полосы. */
        private fun bandRanges(frameSize: Int): Array<IntRange> {
            val binHz = SAMPLE_RATE.toDouble() / frameSize
            val maxBin = frameSize / 2 - 1
            val ratio = HIGH_HZ / LOW_HZ
            return Array(BANDS) { band ->
                val lowHz = LOW_HZ * Math.pow(ratio, band.toDouble() / BANDS)
                val highHz = LOW_HZ * Math.pow(ratio, (band + 1).toDouble() / BANDS)
                val lowBin = (lowHz / binHz).roundToInt().coerceIn(1, maxBin)
                val highBin = (highHz / binHz).roundToInt().coerceIn(lowBin + 1, maxBin)
                lowBin..highBin
            }
        }

        /** Классический итеративный radix-2 FFT (in-place). */
        private fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j or bit
                if (i < j) {
                    val tmpRe = re[i]; re[i] = re[j]; re[j] = tmpRe
                    val tmpIm = im[i]; im[i] = im[j]; im[j] = tmpIm
                }
            }
            var length = 2
            while (length <= n) {
                val angle = -2.0 * PI / length
                val wRe = cos(angle)
                val wIm = sin(angle)
                var block = 0
                while (block < n) {
                    var curRe = 1.0
                    var curIm = 0.0
                    val half = length / 2
                    for (k in 0 until half) {
                        val i0 = block + k
                        val i1 = i0 + half
                        val tRe = curRe * re[i1] - curIm * im[i1]
                        val tIm = curRe * im[i1] + curIm * re[i1]
                        re[i1] = re[i0] - tRe
                        im[i1] = im[i0] - tIm
                        re[i0] += tRe
                        im[i0] += tIm
                        val nextRe = curRe * wRe - curIm * wIm
                        curIm = curRe * wIm + curIm * wRe
                        curRe = nextRe
                    }
                    block += length
                }
                length = length shl 1
            }
        }
    }
}

/** Результат сверки двух отпечатков. */
data class FingerprintMatch(
    val score: Double,
    /** Смещение фрагмента внутри трека в кадрах (может быть отрицательным). */
    val offsetFrames: Int,
) {
    companion object {
        /**
         * Нижний порог, ниже которого совпадение не считаем распознаванием.
         * Подобран экспериментально: та же запись с шумом даёт ~0.75–1.0,
         * разные записи — обычно ниже 0.5.
         */
        const val MIN_SCORE = 0.62
        val NONE = FingerprintMatch(0.0, 0)
    }
}

/**
 * Быстрый поиск фрагмента внутри одного отпечатка.
 *
 * Сначала по битовым хэшам набираются «голоса» за возможные смещения
 * (инвертированный индекс), затем полная оценка считается только для нескольких
 * лучших смещений. Это даёт линейную сложность вместо перебора всех сдвигов.
 */
class AudioFingerprintIndex(val fingerprint: AudioFingerprint) {

    private val hashSpace = AudioFingerprint.HASH_MASK + 1
    private val bucketStart = IntArray(hashSpace + 1)
    private val bucketItems = IntArray(fingerprint.frameCount)

    init {
        val frameCount = fingerprint.frameCount
        for (frame in 0 until frameCount) {
            bucketStart[(fingerprint.hashes[frame] and AudioFingerprint.HASH_MASK) + 1]++
        }
        for (hash in 1..hashSpace) {
            bucketStart[hash] += bucketStart[hash - 1]
        }
        val cursor = bucketStart.copyOf()
        for (frame in 0 until frameCount) {
            val hash = fingerprint.hashes[frame] and AudioFingerprint.HASH_MASK
            bucketItems[cursor[hash]++] = frame
        }
    }

    fun match(fragment: AudioFingerprint): FingerprintMatch {
        val trackFrames = fingerprint.frameCount
        val fragmentFrames = fragment.frameCount
        if (trackFrames <= 0 || fragmentFrames <= 0) return FingerprintMatch.NONE

        // offset = trackFrame - fragmentFrame, диапазон [-(fragmentFrames-1), trackFrames-1]
        val votes = IntArray(trackFrames + fragmentFrames)
        val base = fragmentFrames - 1
        var maxVotes = 0
        for (fragmentFrame in 0 until fragmentFrames) {
            val hash = fragment.hashes[fragmentFrame] and AudioFingerprint.HASH_MASK
            var index = bucketStart[hash]
            val end = bucketStart[hash + 1]
            while (index < end) {
                val offset = bucketItems[index] - fragmentFrame
                val voteIndex = offset + base
                if (voteIndex in votes.indices) {
                    val value = votes[voteIndex] + 1
                    votes[voteIndex] = value
                    if (value > maxVotes) maxVotes = value
                }
                index++
            }
        }

        // Оцениваем только несколько смещений с максимумом голосов.
        val candidates = ArrayList<Int>(8)
        if (maxVotes > 0) {
            val threshold = maxOf(2, maxVotes * 3 / 5)
            for (voteIndex in votes.indices) {
                if (votes[voteIndex] >= threshold) {
                    candidates.add(voteIndex - base)
                    if (candidates.size >= 8) break
                }
            }
        }
        if (candidates.isEmpty()) candidates.add(0)

        var bestMatch = FingerprintMatch.NONE
        for (offset in candidates) {
            val score = scoreAt(fragment, offset)
            if (score > bestMatch.score) bestMatch = FingerprintMatch(score, offset)
        }
        return bestMatch
    }

    /** Полная оценка совпадения при конкретном смещении. */
    fun scoreAt(fragment: AudioFingerprint, offset: Int): Double {
        val trackFrames = fingerprint.frameCount
        val fragmentFrames = fragment.frameCount
        val startFrame = maxOf(0, -offset)
        val endFrame = minOf(fragmentFrames, trackFrames - offset)
        if (endFrame <= startFrame) return 0.0

        var usedFrames = 0
        var similaritySum = 0.0
        for (frame in startFrame until endFrame) {
            val trackFrame = frame + offset
            val fragmentNorm = vectorNorm(fragment.deltas, frame * AudioFingerprint.BANDS)
            val trackNorm = vectorNorm(fingerprint.deltas, trackFrame * AudioFingerprint.BANDS)
            // Тихие кадры не несут информации: пропускаем, чтобы не разбавлять оценку.
            if (fragmentNorm < MIN_DELTA_NORM || trackNorm < MIN_DELTA_NORM) continue
            val cosine = cosine(
                fragment.deltas, frame * AudioFingerprint.BANDS,
                fingerprint.deltas, trackFrame * AudioFingerprint.BANDS,
                fragmentNorm, trackNorm,
            )
            similaritySum += cosine.coerceAtLeast(0.0)
            usedFrames++
        }
        if (usedFrames < MIN_USED_FRAMES) return 0.0
        val deltaSimilarity = similaritySum / usedFrames
        val coverage = usedFrames.toDouble() / fragmentFrames
        return 0.85 * deltaSimilarity + 0.15 * coverage.coerceIn(0.0, 1.0)
    }

    private fun vectorNorm(values: FloatArray, offset: Int): Double {
        var sum = 0.0
        for (band in 0 until AudioFingerprint.BANDS) {
            val value = values[offset + band].toDouble()
            sum += value * value
        }
        return sqrt(sum)
    }

    private fun cosine(
        a: FloatArray,
        aOffset: Int,
        b: FloatArray,
        bOffset: Int,
        normA: Double,
        normB: Double,
    ): Double {
        if (normA < 1e-9 || normB < 1e-9) return 0.0
        var dot = 0.0
        for (band in 0 until AudioFingerprint.BANDS) {
            dot += a[aOffset + band].toDouble() * b[bOffset + band].toDouble()
        }
        return dot / (normA * normB)
    }

    private companion object {
        /** Ниже этой нормы приращений кадр считается тишиной. */
        const val MIN_DELTA_NORM = 0.5
        const val MIN_USED_FRAMES = 8
    }
}
