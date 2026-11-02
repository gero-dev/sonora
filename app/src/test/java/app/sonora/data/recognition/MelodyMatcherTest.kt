package app.sonora.data.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Тесты hum-to-search: детектор высоты тона, построение контура и DTW-сопоставление.
 *
 * Синтетические мелодии детерминированы (тот же генератор, что и в прототипе),
 * поэтому тесты не требуют Android, микрофона и аудиофайлов.
 */
class MelodyMatcherTest {

    @Test
    fun `pitch tracker detects sine frequency`() {
        val samples = sine(220.0, seconds = 1.0)
        val track = PitchTracker.track(samples)
        assertFalse(track.isEmpty)
        val voiced = track.f0Hz.filter { it > 0f }
        assertTrue("voiced=${voiced.size}", voiced.size >= 10)
        val median = median(voiced)
        assertTrue("median=$median", abs(median - 220.0) < 10.0)
    }

    @Test
    fun `pitch tracker ignores silence`() {
        val track = PitchTracker.track(ShortArray(Pcm.SAMPLE_RATE))
        assertTrue(track.isEmpty || track.voiced.none { it })
    }

    @Test
    fun `transposed and slower melody is matched`() {
        val track = MelodyContour.fromSamples(melody(seed = 42L))
        val query = MelodyContour.fromSamples(
            melody(seed = 42L, transposeSemitones = 7, tempoScale = 0.85, noise = 0.02),
        )
        assertNotNull(track)
        assertNotNull(query)
        val trackContour = requireNotNull(track)
        val queryContour = requireNotNull(query)
        assertTrue(trackContour.isUsable)
        assertTrue(queryContour.isUsable)

        val same = MelodyMatcher.similarity(queryContour, trackContour)
        val distractors = (1L..6L).map { seed ->
            MelodyMatcher.similarity(
                queryContour,
                MelodyContour.fromSamples(melody(seed = seed))!!,
            )
        }
        assertTrue("same=$same", same >= MelodyMatcher.MIN_SCORE)
        assertTrue(
            "same=$same distractors=$distractors",
            same > distractors.max() + 0.05,
        )
    }

    @Test
    fun `tempo change does not break matching`() {
        val track = MelodyContour.fromSamples(melody(seed = 7L))!!
        for (tempo in listOf(0.75, 0.9, 1.15, 1.3)) {
            val query = MelodyContour.fromSamples(melody(seed = 7L, tempoScale = tempo))!!
            val score = MelodyMatcher.similarity(query, track)
            assertTrue("tempo=$tempo score=$score", score >= MelodyMatcher.MIN_SCORE)
        }
    }

    @Test
    fun `ranking finds correct melody among library`() {
        val targetSeed = 42L
        val query = MelodyContour.fromSamples(
            melody(targetSeed, transposeSemitones = 5, tempoScale = 0.9, noise = 0.015),
        )!!
        val entries = buildList {
            add(IndexedMelody("target", MelodyContour.fromSamples(melody(targetSeed))!!))
            for (seed in 0L until 6L) {
                add(IndexedMelody("other-$seed", MelodyContour.fromSamples(melody(100L + seed))!!))
            }
        }
        val ranked = MelodyMatcher.rank(query, entries)
        assertTrue("ranked=$ranked", ranked.isNotEmpty())
        assertEquals("target", ranked.first().id)
        assertTrue("score=${ranked.first().score}", ranked.first().score >= MelodyMatcher.MIN_SCORE)
    }

    @Test
    fun `very short or silent fragment has no contour`() {
        assertNull(MelodyContour.fromSamples(ShortArray(100)))
        assertNull(MelodyContour.fromSamples(ShortArray(Pcm.SAMPLE_RATE * 2)))
    }

    @Test
    fun `melody contour is transposition invariant`() {
        val track = MelodyContour.fromSamples(melody(seed = 11L))!!
        val transposed = MelodyContour.fromSamples(melody(seed = 11L, transposeSemitones = -5))!!
        val score = MelodyMatcher.similarity(transposed, track)
        assertTrue("score=$score", score >= 0.9)
    }

    /** Детерминированная мелодия: случайное блуждание по нотам, синус + гармоники. */
    private fun melody(
        seed: Long,
        noteCount: Int = 12,
        noteDurationSec: Double = 0.35,
        transposeSemitones: Int = 0,
        tempoScale: Double = 1.0,
        noise: Double = 0.0,
    ): ShortArray {
        val random = Random(seed)
        val notes = IntArray(noteCount)
        var note = 60
        for (index in notes.indices) {
            note = (note + random.nextInt(9) - 4).coerceIn(52, 76)
            notes[index] = note
        }
        val noteSamples = (noteDurationSec / tempoScale * Pcm.SAMPLE_RATE).roundToInt()
            .coerceAtLeast(1)
        val attack = (0.02 * Pcm.SAMPLE_RATE).roundToInt().coerceAtLeast(1)
        val release = (0.03 * Pcm.SAMPLE_RATE).roundToInt().coerceAtLeast(1)
        val total = noteSamples * noteCount
        val samples = DoubleArray(total)
        for (index in 0 until noteCount) {
            val midi = notes[index] + transposeSemitones
            val frequency = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)
            val offset = index * noteSamples
            for (sample in 0 until noteSamples) {
                var envelope = 1.0
                if (sample < attack) envelope = sample.toDouble() / attack
                if (noteSamples - sample < release) {
                    envelope = minOf(envelope, (noteSamples - sample).toDouble() / release)
                }
                val time = sample.toDouble() / Pcm.SAMPLE_RATE
                val value = sin(2.0 * PI * frequency * time) * 0.7 +
                    sin(2.0 * PI * 2.0 * frequency * time) * 0.2 +
                    sin(2.0 * PI * 3.0 * frequency * time) * 0.1
                samples[offset + sample] = value * envelope * 12_000.0
            }
        }
        val noiseRandom = Random(seed + 7)
        return ShortArray(total) { index ->
            val noiseValue = if (noise > 0.0) {
                (noiseRandom.nextDouble() * 2.0 - 1.0) * noise * 32_767.0
            } else {
                0.0
            }
            (samples[index] + noiseValue).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun sine(frequencyHz: Double, seconds: Double): ShortArray {
        val total = (seconds * Pcm.SAMPLE_RATE).toInt()
        return ShortArray(total) { index ->
            (sin(2.0 * PI * frequencyHz * index / Pcm.SAMPLE_RATE) * 12_000.0).toInt().toShort()
        }
    }

    private fun median(values: List<Float>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle].toDouble()
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }
}
