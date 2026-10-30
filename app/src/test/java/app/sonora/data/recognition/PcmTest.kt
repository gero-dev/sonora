package app.sonora.data.recognition

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmTest {

    @Test
    fun `wav header describes pcm payload`() {
        val pcm = ByteArray(3_200) // 100 мс при 16 кГц моно 16 бит
        val wav = Pcm.wav(pcm)

        assertEquals(44 + pcm.size, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))

        // RIFF size = 36 + размер данных, размер data = размер PCM.
        assertEquals(36 + pcm.size, readIntLe(wav, 4))
        assertEquals(pcm.size, readIntLe(wav, 40))
        assertEquals(1, readShortLe(wav, 20))           // формат PCM
        assertEquals(1, readShortLe(wav, 22))           // моно
        assertEquals(Pcm.SAMPLE_RATE, readIntLe(wav, 24))
        assertEquals(2, readShortLe(wav, 32))           // blockAlign = 2 байта
        assertEquals(16, readShortLe(wav, 34))          // бит на сэмпл
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
    }

    @Test
    fun `bytes and shorts roundtrip`() {
        val samples = shortArrayOf(0, 1, -1, 32767, -32768, 1234, -4321)
        val bytes = Pcm.shortsToBytes(samples)
        assertEquals(samples.size * 2, bytes.size)
        assertArrayEquals(samples, Pcm.bytesToShorts(bytes))
    }

    @Test
    fun `downmix averages channels`() {
        val interleaved = shortArrayOf(100, 200, -100, -200)
        assertArrayEquals(shortArrayOf(150, -150), Pcm.downmixToMono(interleaved, 2))
    }

    @Test
    fun `resample keeps duration`() {
        val ramp = ShortArray(1_000) { it.toShort() }
        val upsampled = Pcm.resample(ramp, 8_000, 16_000)
        assertEquals(2_000, upsampled.size)
        assertTrue(upsampled.first() < upsampled.last())

        val downsampled = Pcm.resample(ramp, 16_000, 8_000)
        assertEquals(500, downsampled.size)
    }

    @Test
    fun `rms is zero for silence and positive for signal`() {
        assertEquals(0f, Pcm.rms(ShortArray(100)), 1e-6f)
        val constant = ShortArray(100) { 1_000 }
        assertTrue(Pcm.rms(constant) > 0f)
    }

    private fun readIntLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun readShortLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}
