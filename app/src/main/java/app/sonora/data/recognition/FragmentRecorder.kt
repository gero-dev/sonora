package app.sonora.data.recognition

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Запись короткого PCM-фрагмента с микрофона: 16 кГц, моно, 16 бит.
 *
 * Всё выполняется на Dispatchers.IO, главный поток не блокируется. Запись
 * прекращается по таймеру или при отмене корутины; AudioRecord гарантированно
 * останавливается и освобождается в finally.
 */
class FragmentRecorder(private val context: Context) {

    // Разрешение RECORD_AUDIO проверяется вызывающим контроллером до записи.
    @SuppressLint("MissingPermission")
    suspend fun record(durationMs: Long, onProgress: (elapsedMs: Long) -> Unit): ByteArray =
        withContext(Dispatchers.IO) {
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN_MONO, ENCODING)
            check(minBufferSize > 0) { "Микрофон недоступен: не удалось определить размер буфера" }
            val bufferSize = max(minBufferSize, CHUNK_BYTES)
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_IN_MONO,
                ENCODING,
                bufferSize,
            )
            try {
                check(recorder.state == AudioRecord.STATE_INITIALIZED) {
                    "Микрофон недоступен: возможно, его использует другое приложение"
                }
                recorder.startRecording()
                val output = ByteArrayOutputStream(SAMPLE_RATE * 2)
                val buffer = ByteArray(bufferSize)
                val startedAt = SystemClock.elapsedRealtime()
                var lastReportedMs = -PROGRESS_STEP_MS
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                    if (elapsedMs >= durationMs) break
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        output.write(buffer, 0, read)
                    } else if (read < 0) {
                        throw IllegalStateException("Ошибка записи с микрофона ($read)")
                    }
                    if (elapsedMs - lastReportedMs >= PROGRESS_STEP_MS) {
                        lastReportedMs = elapsedMs
                        onProgress(elapsedMs)
                    }
                }
                onProgress(durationMs)
                output.toByteArray()
            } finally {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
            }
        }

    companion object {
        const val SAMPLE_RATE = Pcm.SAMPLE_RATE
        private const val CHUNK_BYTES = 4096
        private const val PROGRESS_STEP_MS = 120L
        private const val CHANNEL_IN_MONO = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}

/** Есть ли у приложения разрешение RECORD_AUDIO. */
fun hasMicrophonePermission(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
