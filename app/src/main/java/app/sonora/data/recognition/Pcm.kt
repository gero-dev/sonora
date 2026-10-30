package app.sonora.data.recognition

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Работа с PCM 16-bit: конвертация, WAV-контейнер, ресемплинг и RMS.
 *
 * Чистый Kotlin без Android — покрывается обычными unit-тестами.
 * Все функции терпимы к «мусорным» входным данным: не бросают исключений.
 */
object Pcm {

    /** Частота, с которой пишет микрофон и в которую приводится любой фрагмент. */
    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16

    /** PCM-байты (little-endian) → сэмплы. */
    fun bytesToShorts(bytes: ByteArray, length: Int = bytes.size): ShortArray {
        val safeLength = length.coerceIn(0, bytes.size)
        val count = safeLength / 2
        val out = ShortArray(count)
        for (i in 0 until count) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort()
        }
        return out
    }

    /** Сэмплы → PCM-байты (little-endian). */
    fun shortsToBytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val value = samples[i].toInt()
            out[i * 2] = (value and 0xFF).toByte()
            out[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Заголовок WAV (RIFF/WAVE, PCM) + сами данные. */
    fun wav(
        pcm: ByteArray,
        sampleRate: Int = SAMPLE_RATE,
        channels: Int = CHANNELS,
        bitsPerSample: Int = BITS_PER_SAMPLE,
    ): ByteArray {
        val blockAlign = channels * bitsPerSample / 8
        val byteRate = sampleRate * blockAlign
        val out = ByteArray(44 + pcm.size)
        writeAscii(out, 0, "RIFF")
        writeIntLe(out, 4, 36 + pcm.size)
        writeAscii(out, 8, "WAVE")
        writeAscii(out, 12, "fmt ")
        writeIntLe(out, 16, 16)                       // размер fmt-блока
        writeShortLe(out, 20, 1)                      // PCM
        writeShortLe(out, 22, channels)
        writeIntLe(out, 24, sampleRate)
        writeIntLe(out, 28, byteRate)
        writeShortLe(out, 32, blockAlign)
        writeShortLe(out, 34, bitsPerSample)
        writeAscii(out, 36, "data")
        writeIntLe(out, 40, pcm.size)
        pcm.copyInto(out, destinationOffset = 44)
        return out
    }

    /** Многоканальный PCM → моно (усреднение каналов). */
    fun downmixToMono(interleaved: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return interleaved
        val frames = interleaved.size / channels
        val out = ShortArray(frames)
        for (frame in 0 until frames) {
            var sum = 0
            for (ch in 0 until channels) sum += interleaved[frame * channels + ch].toInt()
            out[frame] = (sum / channels).toShort()
        }
        return out
    }

    /** Линейный ресемплинг: для распознавания этого достаточно. */
    fun resample(samples: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate <= 0 || toRate <= 0 || samples.isEmpty()) return ShortArray(0)
        if (fromRate == toRate) return samples
        val ratio = toRate.toDouble() / fromRate.toDouble()
        val outLength = (samples.size * ratio).roundToInt().coerceAtLeast(1)
        val out = ShortArray(outLength)
        for (i in out.indices) {
            val position = i / ratio
            val index = position.toInt()
            if (index >= samples.size - 1) {
                out[i] = samples[samples.size - 1]
            } else {
                val fraction = position - index
                val a = samples[index].toDouble()
                val b = samples[index + 1].toDouble()
                out[i] = (a + (b - a) * fraction).roundToInt().coerceIn(-32768, 32767).toShort()
            }
        }
        return out
    }

    /** RMS в долях от полной шкалы (0..1). */
    fun rms(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (sample in samples) {
            val value = sample.toDouble() / 32768.0
            sum += value * value
        }
        return sqrt(sum / samples.size).toFloat()
    }

    private fun writeAscii(target: ByteArray, offset: Int, text: String) {
        for (i in text.indices) target[offset + i] = text[i].code.toByte()
    }

    private fun writeIntLe(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
        target[offset + 2] = ((value shr 16) and 0xFF).toByte()
        target[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeShortLe(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }
}
