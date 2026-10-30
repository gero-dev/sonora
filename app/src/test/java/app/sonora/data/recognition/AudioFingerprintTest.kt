package app.sonora.data.recognition

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Тесты упрощённого отпечатка. Синтетический сигнал детерминирован, поэтому
 * тесты стабильны и не требуют ни Android, ни аудиофайлов.
 */
class AudioFingerprintTest {

    @Test
    fun `identical fragment matches itself`() {
        val samples = song(seconds = 8.0, seed = 1L)
        val fingerprint = AudioFingerprint.compute(samples)
        assertFalse(fingerprint.isEmpty)
        val match = AudioFingerprintIndex(fingerprint).match(fingerprint)
        assertTrue("score=${match.score}", match.score > 0.95)
    }

    @Test
    fun `shifted fragment is found with correct offset`() {
        val track = song(seconds = 10.0, seed = 7L)
        val fragment = track.copyOfRange(16_000 * 2, 16_000 * 2 + 16_000 * 3)
        val match = AudioFingerprintIndex(AudioFingerprint.compute(track))
            .match(AudioFingerprint.compute(fragment))
        assertTrue("score=${match.score}", match.score > 0.85)
        // 2 секунды при шаге 1024 сэмпла (64 мс) — это ~31 кадр.
        assertTrue("offset=${match.offsetFrames}", abs(match.offsetFrames - 31) <= 3)
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
    }

    @Test
    fun `noisy copy of same fragment still matches`() {
        val track = song(seconds = 10.0, seed = 21L)
        val cleanFragment = track.copyOfRange(16_000 * 3, 16_000 * 3 + 16_000 * 2)
        val noisyFragment = withNoise(cleanFragment, seed = 5L, amplitude = 0.06)
        val match = AudioFingerprintIndex(AudioFingerprint.compute(track))
            .match(AudioFingerprint.compute(noisyFragment))
        assertTrue("score=${match.score}", match.score > FingerprintMatch.MIN_SCORE)
    }

    @Test
    fun `too short fragment has no fingerprint`() {
        val fingerprint = AudioFingerprint.compute(ShortArray(AudioFingerprint.FRAME_SIZE - 1))
        assertTrue(fingerprint.isEmpty)
        assertTrue(AudioFingerprintIndex(fingerprint).match(fingerprint).score == 0.0)
    }

    @Test
    fun `silence has near zero rms`() {
        val fingerprint = AudioFingerprint.compute(ShortArray(16_000))
        assertTrue(fingerprint.rms < AudioFingerprint.MIN_RMS)
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
