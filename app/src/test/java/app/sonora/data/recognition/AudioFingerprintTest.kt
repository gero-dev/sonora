package app.sonora.data.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Тесты улучшенного отпечатка. Синтетический сигнал детерминирован, поэтому
 * тесты стабильны и не требуют ни Android, ни аудиофайлов.
 */
class AudioFingerprintTest {

    @Test
    fun `identical fragment matches itself`() {
        val samples = song(seconds = 8.0, seed = 1L)
        val fingerprint = AudioFingerprint.compute(samples)
        assertFalse(fingerprint.isEmpty)
        assertTrue("peaks=${fingerprint.peakCount}", fingerprint.peakCount > 0)
        val match = AudioFingerprintIndex(fingerprint).match(fingerprint)
        assertTrue("score=${match.score}", match.score > 0.90)
    }

    @Test
    fun `shifted fragment is found with correct offset`() {
        val track = song(seconds = 10.0, seed = 7L)
        val startSample = Pcm.SAMPLE_RATE * 2
        val fragment = track.copyOfRange(startSample, startSample + Pcm.SAMPLE_RATE * 3)
        val match = AudioFingerprintIndex(AudioFingerprint.compute(track))
            .match(AudioFingerprint.compute(fragment))
        assertTrue("score=${match.score}", match.score > 0.85)
        val expectedOffset = (startSample / AudioFingerprint.HOP_SIZE.toDouble()).roundToInt()
        assertTrue(
            "offset=${match.offsetFrames} expected=$expectedOffset",
            abs(match.offsetFrames - expectedOffset) <= 3,
        )
    }

    @Test
    fun `different recording scores clearly lower`() {
        val track = AudioFingerprint.compute(song(seconds = 10.0, seed = 11L))
        val sameFragment = AudioFingerprint.compute(song(seconds = 3.0, seed = 11L))
        val otherFragment = AudioFingerprint.compute(song(seconds = 3.0, seed = 99L))

        val index = AudioFingerprintIndex(track)
        val sameScore = index.match(sameFragment).score
        val otherScore = index.match(otherFragment).score

        assertTrue("same=$sameScore other=$otherScore", sameScore > otherScore + 0.05)
        assertTrue("other=$otherScore", otherScore < FingerprintMatch.MIN_SCORE)
    }

    @Test
    fun `noisy copy of same fragment still matches`() {
        val track = song(seconds = 10.0, seed = 21L)
        val cleanFragment = track.copyOfRange(Pcm.SAMPLE_RATE * 3, Pcm.SAMPLE_RATE * 3 + Pcm.SAMPLE_RATE * 2)
        for (amplitude in listOf(0.02, 0.05)) {
            val noisyFragment = withNoise(cleanFragment, seed = 5L, amplitude = amplitude)
            val match = AudioFingerprintIndex(AudioFingerprint.compute(track))
                .match(AudioFingerprint.compute(noisyFragment))
            assertTrue(
                "amplitude=$amplitude score=${match.score}",
                match.score > FingerprintMatch.MIN_SCORE,
            )
        }
    }

    @Test
    fun `same track with noise outranks other tracks`() {
        val library = (0 until 6).map { index ->
            IndexedFingerprint("track-$index", AudioFingerprint.compute(song(8.0, 1000L + index)))
        }
        val target = song(8.0, 1003L)
        val fragment = target.copyOfRange(Pcm.SAMPLE_RATE * 2, Pcm.SAMPLE_RATE * 6)
        val noisyFragment = withNoise(fragment, seed = 17L, amplitude = 0.06)

        val ranked = AudioFingerprintSearch.rank(
            AudioFingerprint.compute(noisyFragment),
            library,
            limit = 3,
        )
        assertTrue("ranked=$ranked", ranked.isNotEmpty())
        assertEquals("track-3", ranked.first().id)
        assertTrue("score=${ranked.first().score}", ranked.first().score > 0.6)
    }

    @Test
    fun `candidate ranking respects limit and threshold`() {
        val library = (0 until 4).map { index ->
            IndexedFingerprint("t$index", AudioFingerprint.compute(song(6.0, 50L + index)))
        }
        val ranked = AudioFingerprintSearch.rank(
            AudioFingerprint.compute(song(6.0, 50L)),
            library,
            limit = 2,
        )
        assertTrue("ranked=$ranked", ranked.size <= 2)
        assertEquals("t0", ranked.first().id)
    }

    @Test
    fun `too short fragment has no fingerprint`() {
        val fingerprint = AudioFingerprint.compute(ShortArray(AudioFingerprint.FRAME_SIZE - 1))
        assertTrue(fingerprint.isEmpty)
        assertTrue(AudioFingerprintIndex(fingerprint).match(fingerprint).score == 0.0)
    }

    @Test
    fun `silence has near zero rms and no peaks`() {
        val fingerprint = AudioFingerprint.compute(ShortArray(16_000))
        assertTrue(fingerprint.rms < AudioFingerprint.MIN_RMS)
        assertEquals(0, fingerprint.peakCount)
    }

    @Test
    fun `noise only fragment is not a confident match`() {
        val track = AudioFingerprint.compute(song(8.0, 31L))
        val noise = withNoise(ShortArray(Pcm.SAMPLE_RATE * 4), seed = 3L, amplitude = 0.08)
        val match = AudioFingerprintIndex(track).match(AudioFingerprint.compute(noise))
        assertTrue("score=${match.score}", match.score < FingerprintMatch.MIN_SCORE)
    }

    @Test
    fun `result cache key is stable and distinguishes fragments`() {
        val a = AudioFingerprint.compute(song(4.0, 1L))
        val b = AudioFingerprint.compute(song(4.0, 1L))
        val c = AudioFingerprint.compute(song(4.0, 2L))
        assertEquals(RecognitionResultCache.keyOf(a), RecognitionResultCache.keyOf(b))
        assertNotEquals(RecognitionResultCache.keyOf(a), RecognitionResultCache.keyOf(c))
    }

    /** Детерминированная «песня»: случайная несущая каждые 100 мс + амплитудная модуляция. */
    private fun song(seconds: Double, seed: Long, sampleRate: Int = Pcm.SAMPLE_RATE): ShortArray {
        val random = Random(seed)
        val total = (seconds * sampleRate).toInt()
        val step = sampleRate / 10
        val samples = ShortArray(total)
        var phase = 0.0
        var frequency = 200.0 + random.nextDouble() * 700.0
        for (index in 0 until total) {
            if (index % step == 0) frequency = 180.0 + random.nextDouble() * 1_500.0
            phase += 2.0 * PI * frequency / sampleRate
            val envelope = 0.35 + 0.65 * abs(sin(2.0 * PI * 3.0 * index / sampleRate))
            samples[index] = (sin(phase) * envelope * 10_000.0).toInt().toShort()
        }
        return samples
    }

    private fun withNoise(samples: ShortArray, seed: Long, amplitude: Double): ShortArray {
        val random = Random(seed)
        return ShortArray(samples.size) { index ->
            val noise = (random.nextDouble() * 2.0 - 1.0) * amplitude * 32_767.0
            (samples[index] + noise).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
