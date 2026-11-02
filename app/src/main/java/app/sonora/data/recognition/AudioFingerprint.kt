package app.sonora.data.recognition

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Улучшенный офлайн-отпечаток фрагмента: spectral peak picking + constellation hash.
 *
 * Чем отличается от «просто спектрального отпечатка» версии 2.3.0:
 *  - кадры 64 мс с шагом 32 мс (75 % перекрытие) — несколько перекрывающихся окон;
 *  - энергия раскладывается по 48 лог-частотным полосам (100–5500 Гц), а не по 12;
 *  - в каждом кадре выбираются до [PEAKS_PER_FRAME] спектральных пиков (локальные
 *    максимумы в лог-полосах, не ниже [PEAK_RANGE_DB] от самого громкого пика кадра);
 *  - из пар «якорный пик → пик в одном из следующих [PAIR_FRAMES] кадров» строятся
 *    компактные хэши (как в Shazam): это резко уменьшает число ложных совпадений;
 *  - смещение фрагмента внутри трека ищется голосованием по хэшам (инвертированный
 *    индекс), а итоговая оценка в лучшем смещении считается как F1 совпавших пиков
 *    (с допуском ±1 лог-полосу), доля совпавших пар и покрытие.
 *
 * Устойчивость к шуму: пики ищутся по динамике кадра (шум «поднимает пол», но не
 * меняет взаимное расположение громких пиков), допуск по частоте — целая лог-полоса,
 * по времени — один кадр, а оценка симметрична (precision/recall).
 *
 * Честные ограничения: это не нейросетевой fingerprint. Он уверенно ловит ту же
 * запись (в т. ч. перекодированную, сдвинутую по времени и записанную с шумом),
 * но не заменяет AudD для распознавания незнакомой музыки. Отпечаток считается
 * по локальным файлам и по фрагменту с микрофона одним и тем же кодом.
 */
class AudioFingerprint(
    val frameCount: Int,
    /** RMS исходного сигнала 0..1 — чтобы отличить тишину от музыки. */
    val rms: Float,
    /**
     * Индекс лог-полосы пика: [frameCount] * [PEAKS_PER_FRAME] байт.
     * Значение [NO_PEAK] — в этом слоте пика нет.
     */
    val peakBands: ByteArray,
) {

    val isEmpty: Boolean get() = frameCount <= 0

    /** Сколько пиков всего — быстрая проверка «фрагмент вообще содержит музыку». */
    val peakCount: Int = peakBands.count { it >= 0 }

    /** Битовая маска пиковых полос кадра: 48 полос помещаются в Long. */
    val frameMasks: LongArray = LongArray(frameCount) { frame ->
        var mask = 0L
        val base = frame * PEAKS_PER_FRAME
        for (slot in 0 until PEAKS_PER_FRAME) {
            val position = base + slot
            if (position >= peakBands.size) break
            val band = peakBands[position].toInt()
            if (band in 0 until PEAK_BANDS) mask = mask or (1L shl band)
        }
        mask
    }

    companion object {
        /** Частотные полосы для пиков: 48 лог-полос 100–5500 Гц. */
        const val PEAK_BANDS = 48
        const val PEAKS_PER_FRAME = 5

        const val FRAME_SIZE = 1024
        const val HOP_SIZE = 512
        const val SAMPLE_RATE = Pcm.SAMPLE_RATE

        /** Сколько следующих кадров просматривать для пары «якорь → цель». */
        const val PAIR_FRAMES = 8

        /** Сколько целей максимум у одного якорного пика (ограничение индекса). */
        const val PAIR_TARGETS = 4

        /** Значение «пика нет» в [AudioFingerprint.peakBands]. */
        const val NO_PEAK: Byte = -1

        /** Порог тишины: ниже него фрагмент бессмысленно и распознавать, и сверять. */
        const val MIN_RMS = 0.008f
        const val MIN_FRAMES = 8

        /** Насколько дБ пик может быть тише самого громкого пика кадра. */
        private const val PEAK_RANGE_DB = 26.0

        /**
         * Абсолютный порог тишины для кадра: ниже −90 дБ пиков не ищем.
         * Относительного порога («тише самого громкого пика кадра») мало:
         * в цифровой тишине все полосы равны, и он выдал бы «пики» из шума.
         */
        private const val SILENCE_DB = -90.0

        private const val PEAK_LOW_HZ = 100.0
        private const val PEAK_HIGH_HZ = 5500.0

        fun compute(samples: ShortArray): AudioFingerprint {
            val rms = Pcm.rms(samples)
            val frameCount = if (samples.size < FRAME_SIZE) {
                0
            } else {
                (samples.size - FRAME_SIZE) / HOP_SIZE + 1
            }
            if (frameCount <= 0) return AudioFingerprint(0, rms, ByteArray(0))

            val peakBands = ByteArray(frameCount * PEAKS_PER_FRAME) { NO_PEAK }
            val window = hannWindow(FRAME_SIZE)
            val peakRanges = bandRanges(PEAK_BANDS, PEAK_LOW_HZ, PEAK_HIGH_HZ)
            val re = DoubleArray(FRAME_SIZE)
            val im = DoubleArray(FRAME_SIZE)
            val bandDb = DoubleArray(PEAK_BANDS)
            val candidates = IntArray(PEAK_BANDS)

            for (frame in 0 until frameCount) {
                val start = frame * HOP_SIZE
                for (i in 0 until FRAME_SIZE) {
                    re[i] = (samples[start + i] / 32768.0) * window[i]
                    im[i] = 0.0
                }
                fft(re, im)

                // Лог-энергия каждой полосы + максимум по кадру.
                var frameMaxDb = SILENCE_DB
                for (band in 0 until PEAK_BANDS) {
                    val range = peakRanges[band]
                    var power = 0.0
                    for (bin in range) {
                        val value = re[bin] * re[bin] + im[bin] * im[bin]
                        if (value > power) power = value
                    }
                    val db = 10.0 * log10(power + 1e-12)
                    bandDb[band] = db
                    if (db > frameMaxDb) frameMaxDb = db
                }
                if (frameMaxDb <= SILENCE_DB) continue

                // Локальные максимумы в полосах не ниже порога динамики кадра.
                val threshold = frameMaxDb - PEAK_RANGE_DB
                var candidateCount = 0
                for (band in 0 until PEAK_BANDS) {
                    val db = bandDb[band]
                    if (db < threshold) continue
                    val left = if (band > 0) bandDb[band - 1] else SILENCE_DB
                    val right = if (band < PEAK_BANDS - 1) bandDb[band + 1] else SILENCE_DB
                    if (db >= left && db >= right) {
                        candidates[candidateCount++] = band
                    }
                }

                // До PEAKS_PER_FRAME самых громких пиков кадра.
                val base = frame * PEAKS_PER_FRAME
                var used = 0
                while (used < PEAKS_PER_FRAME && candidateCount > 0) {
                    var bestSlot = -1
                    var bestDb = Double.NEGATIVE_INFINITY
                    for (slot in 0 until candidateCount) {
                        val band = candidates[slot]
                        if (band < 0) continue
                        val db = bandDb[band]
                        if (db > bestDb) {
                            bestDb = db
                            bestSlot = slot
                        }
                    }
                    if (bestSlot < 0) break
                    peakBands[base + used] = candidates[bestSlot].toByte()
                    candidates[bestSlot] = -1
                    used++
                }
            }
            return AudioFingerprint(frameCount, rms, peakBands)
        }

        private fun hannWindow(size: Int): DoubleArray = DoubleArray(size) { i ->
            0.5 * (1.0 - cos(2.0 * PI * i / (size - 1)))
        }

        /** Индексы FFT-бинов для каждой лог-полосы. */
        internal fun bandRanges(bandCount: Int, lowHz: Double, highHz: Double): Array<IntRange> {
            val binHz = SAMPLE_RATE.toDouble() / FRAME_SIZE
            val maxBin = FRAME_SIZE / 2 - 1
            val ratio = highHz / lowHz
            return Array(bandCount) { band ->
                val low = lowHz * Math.pow(ratio, band.toDouble() / bandCount)
                val high = lowHz * Math.pow(ratio, (band + 1).toDouble() / bandCount)
                val lowBin = (low / binHz).roundToInt().coerceIn(1, maxBin)
                val highBin = (high / binHz).roundToInt().coerceIn(lowBin, maxBin)
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
         * Подобран на синтетике: та же запись с шумом даёт ~0.75–1.0, разные
         * записи — обычно ниже 0.35. Порог оставляет запас на реальные записи.
         */
        const val MIN_SCORE = 0.50

        /** «Уверенное» совпадение: можно сразу предлагать открыть трек. */
        const val MIN_SCORE_RELIABLE = 0.70

        val NONE = FingerprintMatch(0.0, 0)
    }
}

/** Отпечаток, известный индекс: используется для ранжирования top-N. */
data class IndexedFingerprint(val id: String, val fingerprint: AudioFingerprint)

/** Кандидат, найденный по отпечатку. */
data class FingerprintCandidate(
    val id: String,
    val score: Double,
    val offsetFrames: Int,
)

/**
 * Быстрый поиск фрагмента внутри одного отпечатка.
 *
 * Хэши пар пиков складываются в инвертированный индекс (hash → кадры-якоря).
 * Совпадение фрагмента даёт «голоса» за смещения; полная (но дешёвая) оценка
 * считается только для нескольких смещений с максимумом голосов.
 */
class AudioFingerprintIndex(val fingerprint: AudioFingerprint) {

    private val trackFrames = fingerprint.frameCount
    private val pairBuckets: Map<Int, IntArray> = buildPairBuckets(fingerprint)

    fun match(fragment: AudioFingerprint): FingerprintMatch {
        if (trackFrames <= 0 || fragment.frameCount <= 0) return FingerprintMatch.NONE
        val pairs = pairHashesOf(fragment)
        if (pairs.hashes.isEmpty()) return FingerprintMatch.NONE

        val base = fragment.frameCount - 1
        val votes = IntArray(trackFrames + fragment.frameCount)
        for (index in pairs.hashes.indices) {
            val bucket = pairBuckets[pairs.hashes[index]] ?: continue
            val fragmentFrame = pairs.frames[index]
            for (trackFrame in bucket) {
                val voteIndex = trackFrame - fragmentFrame + base
                if (voteIndex in votes.indices) votes[voteIndex]++
            }
        }

        var maxVotes = 0
        for (vote in votes) if (vote > maxVotes) maxVotes = vote
        if (maxVotes <= 0) return FingerprintMatch.NONE

        val voteThreshold = maxOf(2, maxVotes / 2)
        val candidates = ArrayList<Int>(MAX_CANDIDATES)
        for (voteIndex in votes.indices) {
            if (votes[voteIndex] >= voteThreshold) candidates.add(voteIndex - base)
        }
        if (candidates.isEmpty()) candidates.add(0)
        candidates.sortWith(Comparator { a, b ->
            votes[b + base].compareTo(votes[a + base])
        })

        var best = FingerprintMatch.NONE
        val checked = minOf(candidates.size, MAX_CANDIDATES)
        for (index in 0 until checked) {
            val score = scoreAt(fragment, candidates[index], pairs)
            if (score > best.score) best = FingerprintMatch(score, candidates[index])
        }
        return best
    }

    /** Полная оценка совпадения при конкретном смещении. */
    fun scoreAt(fragment: AudioFingerprint, offset: Int): Double =
        scoreAt(fragment, offset, pairHashesOf(fragment))

    private fun scoreAt(
        fragment: AudioFingerprint,
        offset: Int,
        fragmentPairs: PairHashes,
    ): Double {
        val startFrame = maxOf(0, -offset)
        val endFrame = minOf(fragment.frameCount, trackFrames - offset)
        if (endFrame <= startFrame) return 0.0

        var fragmentPeaks = 0
        var matchedFragmentPeaks = 0
        var trackPeaks = 0
        var matchedTrackPeaks = 0
        for (frame in startFrame until endFrame) {
            val trackFrame = frame + offset
            val trackMask = fingerprint.frameMasks[trackFrame]
            val trackMaskDilated = dilate(trackMask)
            val fragmentMask = fragment.frameMasks[frame]
            val fragmentMaskDilated = dilate(fragmentMask)

            val fragmentBase = frame * AudioFingerprint.PEAKS_PER_FRAME
            for (slot in 0 until AudioFingerprint.PEAKS_PER_FRAME) {
                val band = fragment.peakBands[fragmentBase + slot].toInt()
                if (band < 0) break
                fragmentPeaks++
                if (trackMaskDilated and (1L shl band) != 0L) matchedFragmentPeaks++
            }
            val trackBase = trackFrame * AudioFingerprint.PEAKS_PER_FRAME
            for (slot in 0 until AudioFingerprint.PEAKS_PER_FRAME) {
                val band = fingerprint.peakBands[trackBase + slot].toInt()
                if (band < 0) break
                trackPeaks++
                if (fragmentMaskDilated and (1L shl band) != 0L) matchedTrackPeaks++
            }
        }

        // Доля парных хэшей, подтверждённых в этом смещении (±1 кадр).
        var matchedPairs = 0
        for (index in fragmentPairs.hashes.indices) {
            val bucket = pairBuckets[fragmentPairs.hashes[index]] ?: continue
            val fragmentFrame = fragmentPairs.frames[index]
            for (trackFrame in bucket) {
                if (kotlin.math.abs(trackFrame - fragmentFrame - offset) <= 1) {
                    matchedPairs++
                    break
                }
            }
        }
        if (matchedPairs < MIN_MATCHED_PAIRS) return 0.0

        val precision = if (fragmentPeaks == 0) 0.0 else matchedFragmentPeaks.toDouble() / fragmentPeaks
        val recall = if (trackPeaks == 0) 0.0 else matchedTrackPeaks.toDouble() / trackPeaks
        val f1 = if (precision + recall == 0.0) 0.0 else 2 * precision * recall / (precision + recall)
        val pairRatio = matchedPairs.toDouble() / fragmentPairs.hashes.size.coerceAtLeast(1)
        val coverage = (endFrame - startFrame).toDouble() / fragment.frameCount
        val score = 0.40 * pairRatio + 0.50 * f1 + 0.10 * coverage.coerceIn(0.0, 1.0)
        return score.coerceIn(0.0, 1.0)
    }

    private fun dilate(mask: Long): Long = mask or (mask shl 1) or (mask ushr 1)

    private class PairHashes(val hashes: IntArray, val frames: IntArray)

    private fun buildPairBuckets(source: AudioFingerprint): Map<Int, IntArray> {
        val buckets = HashMap<Int, MutableList<Int>>()
        collectPairs(source) { hash, frame ->
            buckets.getOrPut(hash) { ArrayList(2) }.add(frame)
        }
        return buckets.mapValues { (_, value) -> value.toIntArray() }
    }

    private fun pairHashesOf(source: AudioFingerprint): PairHashes {
        val hashes = ArrayList<Int>(source.frameCount * 2)
        val frames = ArrayList<Int>(source.frameCount * 2)
        collectPairs(source) { hash, frame ->
            hashes.add(hash)
            frames.add(frame)
        }
        return PairHashes(hashes.toIntArray(), frames.toIntArray())
    }

    /** Обходит пары «якорный пик → пик в следующих кадрах» и отдаёт (hash, кадр-якорь). */
    private inline fun collectPairs(
        source: AudioFingerprint,
        onPair: (hash: Int, anchorFrame: Int) -> Unit,
    ) {
        for (frame in 0 until source.frameCount) {
            val anchorBase = frame * AudioFingerprint.PEAKS_PER_FRAME
            for (anchorSlot in 0 until AudioFingerprint.PEAKS_PER_FRAME) {
                val anchorBand = source.peakBands[anchorBase + anchorSlot].toInt()
                if (anchorBand < 0) break
                var targets = 0
                for (delta in 1..AudioFingerprint.PAIR_FRAMES) {
                    val targetFrame = frame + delta
                    if (targetFrame >= source.frameCount) break
                    val targetBase = targetFrame * AudioFingerprint.PEAKS_PER_FRAME
                    for (targetSlot in 0 until AudioFingerprint.PEAKS_PER_FRAME) {
                        val targetBand = source.peakBands[targetBase + targetSlot].toInt()
                        if (targetBand < 0) break
                        val hash = (anchorBand and 0x3F) or
                            ((targetBand and 0x3F) shl 6) or
                            ((delta and 0x0F) shl 12)
                        onPair(hash, frame)
                        targets++
                        if (targets >= AudioFingerprint.PAIR_TARGETS) break
                    }
                    if (targets >= AudioFingerprint.PAIR_TARGETS) break
                }
            }
        }
    }

    private companion object {
        /** Сколько смещений-кандидатов проверять полной оценкой. */
        const val MAX_CANDIDATES = 8
        const val MIN_MATCHED_PAIRS = 4
    }
}

/** Поиск top-N кандидатов по набору отпечатков (чистая функция — покрывается тестами). */
object AudioFingerprintSearch {

    /**
     * Сверяет [fragment] со всеми [entries] и возвращает лучшие совпадения
     * с уверенностью не ниже [minScore], отсортированные по оценке.
     */
    fun rank(
        fragment: AudioFingerprint,
        entries: List<IndexedFingerprint>,
        limit: Int = 5,
        minScore: Double = FingerprintMatch.MIN_SCORE,
    ): List<FingerprintCandidate> {
        if (fragment.isEmpty || entries.isEmpty()) return emptyList()
        val candidates = ArrayList<FingerprintCandidate>(limit)
        for (entry in entries) {
            if (entry.fingerprint.isEmpty) continue
            val match = AudioFingerprintIndex(entry.fingerprint).match(fragment)
            if (match.score >= minScore) {
                candidates.add(FingerprintCandidate(entry.id, match.score, match.offsetFrames))
            }
        }
        candidates.sortByDescending { it.score }
        return if (candidates.size > limit) candidates.subList(0, limit).toList() else candidates
    }
}
