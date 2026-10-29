package app.sonora.data.remote.ytdlp

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/**
 * yt-dlp как резервный источник потока.
 *
 * Зачем: InnerTube и InnerTubeX закрывают почти всё, но часть видео отдаётся
 * только с ограничениями (возраст, регион, SABR-only), и тогда yt-dlp с
 * собственной логикой клиентов YouTube — единственный путь получить звук.
 *
 * Все вызовы — в IO-диспетчере: Python-рантайм тяжёлый и блокирующий.
 */
class YtDlpResolver(
    private val context: Context,
    private val settings: app.sonora.core.settings.SettingsStore,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val initMutex = Mutex()

    @Volatile
    private var initialized = false

    /** Инициализация Python + yt-dlp. Идемпотентно, можно звать из фона. */
    suspend fun ensureInit() = initMutex.withLock {
        if (initialized) return@withLock
        withContext(Dispatchers.IO) {
            val result = runCatching { YoutubeDL.getInstance().init(context) }
            if (result.isFailure) {
                Log.w("YtDlp", "init failed: ${result.exceptionOrNull()?.message}")
            } else {
                val version = runCatching { YoutubeDL.getInstance().version(context) }.getOrNull()
                Log.i("YtDlp", "ready, version=${version ?: "unknown"}")
            }
            // ffmpeg нужен только для нарезки фрагмента под ReccoBeats.
            runCatching { FFmpeg.getInstance().init(context) }
                .onFailure { Log.w("YtDlp", "ffmpeg init failed: ${it.message}") }
        }
        initialized = true
    }

    /** `ytsearchN:<query> --dump-json --flat-playlist` → N результатов. */
    suspend fun search(query: String, limit: Int = 10): List<YtTrack> =
        withContext(Dispatchers.IO) {
            ensureInit()
            val request = YoutubeDLRequest("ytsearch$limit:$query").apply {
                addOption("--dump-json")
                addOption("--flat-playlist")
                addOption("--no-warnings")
                addOption("--no-playlist")
                addOption("--socket-timeout", "10")
                settings.current.ytDlpProxy.trim().takeIf { it.isNotBlank() }?.let {
                    addOption("--proxy", it)
                }
            }
            val response = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                runCatching { YoutubeDL.getInstance().execute(request) }.getOrNull()
            } ?: return@withContext emptyList()

            response.out.lineSequence()
                .filter { it.startsWith("{") }
                .mapNotNull { line ->
                    runCatching { json.decodeFromString<YtDumpJson>(line) }.getOrNull()
                }
                .filter { it.id.isNotBlank() }
                .map { dto ->
                    YtTrack(
                        id = dto.id,
                        title = dto.title.ifBlank { dto.id },
                        uploader = dto.uploader,
                        durationMs = ((dto.duration ?: 0.0) * 1000).toLong(),
                        url = dto.webpageUrl ?: "https://www.youtube.com/watch?v=${dto.id}",
                    )
                }
                .take(limit)
                .toList()
        }

    /**
     * Прямой аудио-URL. Используем `--dump-json`, а не `-g`: в JSON приходят
     * `http_headers`, без которых googlevideo отдаёт 403 при чужом User-Agent.
     */
    suspend fun streamHandle(idOrUrl: String): StreamHandle? = withContext(Dispatchers.IO) {
        ensureInit()
        val request = YoutubeDLRequest(watchUrl(idOrUrl)).apply {
            addOption("-f", "bestaudio")
            addOption("--dump-json")
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--no-progress")
            addOption("--socket-timeout", "10")
            settings.current.ytDlpProxy.trim().takeIf { it.isNotBlank() }?.let {
                addOption("--proxy", it)
            }
        }
        val response = withTimeoutOrNull(STREAM_TIMEOUT_MS) {
            runCatching { YoutubeDL.getInstance().execute(request) }
                .onFailure { Log.w("YtDlp", "resolve failed: ${it.message}") }
                .getOrNull()
        } ?: run {
            Log.w("YtDlp", "resolve timeout")
            return@withContext null
        }

        if (response.exitCode != 0) {
            Log.w("YtDlp", "exit=${response.exitCode} err=${response.err.takeLast(300)}")
        }

        val parsed = response.out.lineSequence()
            .filter { it.startsWith("{") }
            .mapNotNull { runCatching { json.decodeFromString<YtDumpJson>(it) }.getOrNull() }
            .toList()
        val chosen = parsed.firstOrNull { !it.url.isNullOrBlank() }
        if (chosen == null) {
            Log.w("YtDlp", "no url: lines=${parsed.size} out=${response.out.take(200)}")
        }
        chosen?.let {
            StreamHandle(
                url = it.url!!,
                headers = it.httpHeaders,
                mimeType = mimeOf(it.ext),
            )
        }
    }

    /**
     * 30-секундный фрагмент в MP3 для ReccoBeats. Требует ffmpeg:
     * `--download-sections` режет по ключевым кадрам, `-x` конвертирует.
     */
    suspend fun downloadSnippet(idOrUrl: String, seconds: Int = 30): java.io.File? =
        withContext(Dispatchers.IO) {
            ensureInit()
            val dir = java.io.File(context.cacheDir, "recco").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val template = java.io.File(dir, "snip-%(id)s.%(ext)s").absolutePath
            val request = YoutubeDLRequest(watchUrl(idOrUrl)).apply {
                addOption("-f", "bestaudio")
                // Нарезка идёт постпроцессором ffmpeg: --download-sections
                // заставляет ffmpeg качать URL напрямую, а googlevideo отдаёт
                // ему 403. Здесь скачивает сам yt-dlp (со всеми заголовками),
                // а ffmpeg только режет локальный файл.
                addOption("-x")
                addOption("--audio-format", "mp3")
                addOption("--audio-quality", "96K")
                addOption("--postprocessor-args", "ExtractAudio+ffmpeg:-t $seconds -ac 1 -ar 44100")
                addOption("-o", template)
                addOption("--no-playlist")
                addOption("--no-warnings")
                addOption("--no-progress")
                addOption("--max-filesize", "20M")
            }
            val response = withTimeoutOrNull(SNIPPET_TIMEOUT_MS) {
                runCatching { YoutubeDL.getInstance().execute(request) }
                    .onFailure { Log.w("YtDlp", "snippet failed: ${it.message}") }
                    .getOrNull()
            } ?: run {
                Log.w("YtDlp", "snippet timeout")
                return@withContext null
            }
            if (response.exitCode != 0) {
                Log.w("YtDlp", "snippet exit=${response.exitCode} ${response.err.takeLast(200)}")
            }
            dir.listFiles()
                ?.filter { it.name.endsWith(".mp3") && it.length() in 1..MAX_SNIPPET_BYTES }
                ?.maxByOrNull { it.lastModified() }
        }

    private fun mimeOf(ext: String?): String = when (ext?.lowercase()) {
        "webm", "opus" -> "audio/webm"
        "mp3" -> "audio/mpeg"
        "ogg" -> "audio/ogg"
        else -> "audio/mp4"
    }

    private fun watchUrl(idOrUrl: String): String =
        if (idOrUrl.startsWith("http")) idOrUrl
        else "https://www.youtube.com/watch?v=$idOrUrl"

    private companion object {
        /** yt-dlp может «залипнуть» на извлечении — ограничиваем ожидание. */
        const val SEARCH_TIMEOUT_MS = 20_000L
        const val STREAM_TIMEOUT_MS = 30_000L
        const val SNIPPET_TIMEOUT_MS = 90_000L
        /** Лимит ReccoBeats — 5 МБ; держим запас. */
        const val MAX_SNIPPET_BYTES = 5L * 1024 * 1024
    }
}
