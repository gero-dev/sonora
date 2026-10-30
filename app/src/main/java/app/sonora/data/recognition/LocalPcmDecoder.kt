package app.sonora.data.recognition

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Декодирование начала аудиофайла в моно PCM 16 кГц.
 *
 * Нужен только локальному fallback'у (сверка с фонотекой) и импорту файла для
 * ручной проверки фичи. Используются штатные MediaExtractor/MediaCodec — никаких
 * новых нативных зависимостей.
 */
object LocalPcmDecoder {

    /**
     * Читает первые [maxDurationMs] миллисекунд файла и приводит к 16 кГц моно.
     * Возвращает null, если файл не открывается или не содержит аудиодорожки.
     */
    suspend fun decodeToMono16k(
        context: Context,
        uri: Uri,
        maxDurationMs: Int = DEFAULT_MAX_DURATION_MS,
    ): ShortArray? = withContext(Dispatchers.IO) {
        runCatching { decode(context.applicationContext, uri, maxDurationMs) }.getOrNull()
    }

    private fun decode(context: Context, uri: Uri, maxDurationMs: Int): ShortArray? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        val pcm = ByteArrayOutputStream(1 shl 16)
        try {
            openDataSource(context, extractor, uri)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(trackIndex)

            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
            val estimatedRate = inputFormat.intOr(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            val estimatedChannels = inputFormat.intOr(MediaFormat.KEY_CHANNEL_COUNT, 2)
            // Ограничиваем объём чтения: не декодируем на фоне целый час.
            val maxBytes = estimatedRate.toLong() * estimatedChannels * 2 * maxDurationMs / 1000

            val codec = MediaCodec.createDecoderByType(mime)
            decoder = codec
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var outputRate = estimatedRate
            var outputChannels = estimatedChannels

            while (!outputDone && pcm.size().toLong() < maxBytes) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        if (inputBuffer != null) {
                            inputBuffer.clear()
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(
                                    inputIndex, 0, sampleSize, extractor.sampleTime, 0,
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        outputRate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, estimatedRate)
                        outputChannels = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, estimatedChannels)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        if (bufferInfo.size > 0) {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)
                            if (outputBuffer != null) {
                                val chunk = ByteArray(bufferInfo.size)
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                outputBuffer.get(chunk)
                                pcm.write(chunk)
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            val raw = pcm.toByteArray()
            if (raw.isEmpty()) return null
            val samples = Pcm.bytesToShorts(raw)
            val mono = Pcm.downmixToMono(samples, outputChannels)
            return Pcm.resample(mono, outputRate, Pcm.SAMPLE_RATE)
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun openDataSource(context: Context, extractor: MediaExtractor, uri: Uri) {
        when {
            uri.scheme.isNullOrBlank() -> extractor.setDataSource(uri.toString())
            uri.scheme == "file" -> extractor.setDataSource(uri.path ?: uri.toString())
            else -> extractor.setDataSource(context, uri, null)
        }
    }

    private fun MediaFormat.intOr(key: String, fallback: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback

    const val DEFAULT_MAX_DURATION_MS = 45_000
    private const val DEQUEUE_TIMEOUT_US = 10_000L
}
