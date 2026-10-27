package app.sonora.data.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import app.sonora.core.net.Http
import app.sonora.core.net.HttpException
import app.sonora.core.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Состояние одной загрузки/импорта для UI. [fileName] — уникальный id. */
data class DownloadItem(
    val fileName: String,
    val bytes: Long = 0L,
    val totalBytes: Long = 0L,
    val status: String,
    val localPath: String? = null,
    /** Логический id трека — по нему UI находит прогресс сохранения. */
    val tag: String? = null,
) {
    /** 0f..1f, если сервер сообщил размер; иначе 0f. */
    val progress: Float
        get() = if (totalBytes > 0L) (bytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    /** Прогресс в процентах; -1, пока сервер не сообщил размер. */
    val percent: Int
        get() = if (totalBytes > 0L) (progress * 100f).toInt().coerceIn(0, 100) else -1
}

/**
 * Загрузки в папку приложения.
 *
 * Сюда попадают свои файлы, Creative Commons, явно разрешённые API-ссылки и —
 * по запросу пользователя — поток InnerTube для личного офлайн-доступа.
 * Файлы не публикуются и не покидают устройство; ответственность за соблюдение
 * условий сервисов лежит на пользователе (см. docs/LEGAL.md).
 */
class DownloadManager(
    context: Context,
    private val settings: SettingsStore,
) {

    private val appContext = context.applicationContext

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    /**
     * Папка загрузок: getExternalFilesDir(Music)/<downloadFolder>, fallback —
     * filesDir/music/<downloadFolder>. Папка создаётся при обращении.
     */
    fun downloadDirectory(): File {
        val folder = safeFolderName(settings.current.downloadFolder)
        val base = appContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: File(appContext.filesDir, "music")
        val dir = File(base, folder)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Скачивает прямой URL в папку загрузок (OkHttp-поток, прогресс в [items]).
     * Работает при любом типе подключения: Wi-Fi, мобильная сеть, VPN/WARP.
     *
     * [contentLengthHint], [rangeChunkSizeBytes] и [requireBoundedRange] нужны
     * для потоков InnerTubeX, которые отдают файл только кусками с `Range`.
     * Без этого полный GET такого URL получает 403 — именно из-за этого
     * раньше появлялось «не удалось скачать» для треков из InnerTube.
     */
    suspend fun download(
        url: String,
        suggestedFileName: String,
        headers: Map<String, String> = emptyMap(),
        mime: String? = null,
        contentLengthHint: Long? = null,
        rangeChunkSizeBytes: Long = 0L,
        requireBoundedRange: Boolean = false,
        /** Логический id трека: по нему UI показывает прогресс сохранения. */
        tag: String? = null,
        /** Дополнительный колбэк прогресса (байты записи, всего байт; -1 — неизвестно). */
        onProgress: ((Long, Long) -> Unit)? = null,
    ): Result<File> =
        withContext(Dispatchers.IO) {
            if (url.isBlank()) {
                return@withContext Result.failure(IOException("Пустая ссылка"))
            }
            val dir = downloadDirectory()
            if (!dir.exists() && !dir.mkdirs()) {
                return@withContext Result.failure(
                    IOException("Не удалось создать папку загрузок"),
                )
            }
            val target = uniqueFile(dir, safeFileName(suggestedFileName, url, mime))
            val itemName = target.name
            val partial = File(dir, "$itemName.part")
            val totalHint = contentLengthHint?.takeIf { it > 0L }
            val useRanges = requireBoundedRange || rangeChunkSizeBytes > 0L
            upsert(DownloadItem(fileName = itemName, status = STATUS_DOWNLOADING, tag = tag))
            try {
                val requestHeaders = mediaHeaders(headers)
                var lastReported = 0L
                val progressSink: (Long, Long) -> Unit = { written, total ->
                    val safeTotal = total.takeIf { it > 0L } ?: 0L
                    if (written - lastReported >= PROGRESS_STEP_BYTES || written == total) {
                        lastReported = written
                        onProgress?.invoke(written, safeTotal)
                        upsert(
                            DownloadItem(
                                fileName = itemName,
                                bytes = written,
                                totalBytes = safeTotal,
                                status = STATUS_DOWNLOADING,
                                tag = tag,
                            ),
                        )
                    }
                }
                if (useRanges) {
                    downloadRanged(
                        url = url,
                        headers = requestHeaders,
                        partial = partial,
                        contentLengthHint = totalHint,
                        chunkSizeHint = rangeChunkSizeBytes,
                        onProgress = progressSink,
                    )
                } else {
                    downloadFull(
                        url = url,
                        headers = requestHeaders,
                        partial = partial,
                        onProgress = progressSink,
                    )
                }
                val finalSize = partial.length()
                if (finalSize <= 0L) throw IOException("Сервер вернул пустой файл")
                if (!partial.renameTo(target)) {
                    partial.copyTo(target, overwrite = true)
                    partial.delete()
                }
                val size = target.length()
                upsert(
                    DownloadItem(
                        fileName = itemName,
                        bytes = size,
                        totalBytes = size,
                        status = STATUS_DONE,
                        localPath = target.absolutePath,
                        tag = tag,
                    ),
                )
                onProgress?.invoke(size, size)
                Result.success(target)
            } catch (error: Exception) {
                runCatching { partial.delete() }
                failItem(itemName, error, tag)
                Result.failure(error)
            }
        }

    /** Заголовки для медиа-запроса: UA и Accept нельзя терять при кастомных headers. */
    private fun mediaHeaders(headers: Map<String, String>): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        headers.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) result[key] = value
        }
        fun has(name: String) = result.keys.any { it.equals(name, ignoreCase = true) }
        if (!has("User-Agent")) result["User-Agent"] = Http.USER_AGENT
        if (!has("Accept")) result["Accept"] = "*/*"
        // Для аудио/видео не нужен gzip: иначе Content-Length не совпадает с файлом.
        if (!has("Accept-Encoding")) result["Accept-Encoding"] = "identity"
        return result
    }

    private fun mediaRequest(url: String, headers: Map<String, String>): Request.Builder {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (key, value) -> builder.header(key, value) }
        return builder
    }

    /** Обычная загрузка одним GET-запросом. */
    private fun downloadFull(
        url: String,
        headers: Map<String, String>,
        partial: File,
        onProgress: (Long, Long) -> Unit,
    ) {
        Http.client.newCall(mediaRequest(url, headers).build()).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = runCatching {
                    response.body?.string().orEmpty().take(200)
                }.getOrDefault("")
                throw HttpException(
                    response.code,
                    errorBody.ifBlank { "download failed" },
                )
            }
            val body = response.body ?: throw IOException("Пустой ответ сервера")
            val total = body.contentLength()
            var written = 0L
            body.byteStream().use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(written, total)
                    }
                    output.flush()
                }
            }
            if (written <= 0L) throw IOException("Сервер вернул пустой файл")
        }
    }

    /**
     * Загрузка bounded-range потоков: последовательные `Range: bytes=start-end`.
     * Используется, когда InnerTubeX сообщил `requireBoundedRange`/`useRangeChunks`.
     */
    private fun downloadRanged(
        url: String,
        headers: Map<String, String>,
        partial: File,
        contentLengthHint: Long?,
        chunkSizeHint: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        val chunkSize = chunkSizeHint.takeIf { it > 0L } ?: DEFAULT_RANGE_CHUNK_BYTES
        var total = contentLengthHint?.takeIf { it > 0L } ?: -1L
        var written = 0L
        runCatching { partial.delete() }
        while (true) {
            if (total > 0L && written >= total) break
            val start = written
            val end = if (total > 0L) {
                minOf(start + chunkSize - 1L, total - 1L)
            } else {
                start + chunkSize - 1L
            }
            val request = mediaRequest(url, headers)
                .header("Range", "bytes=$start-$end")
                .build()
            var finished = false
            Http.client.newCall(request).execute().use { response ->
                when {
                    response.code == 416 -> {
                        if (written > 0L && (total <= 0L || written >= total)) {
                            finished = true
                        } else {
                            throw HttpException(416, "Range не принят сервером")
                        }
                    }

                    response.code == 200 -> {
                        // Сервер проигнорировал Range. Полный ответ можно писать
                        // только с начала файла, иначе получим дубли.
                        if (start > 0L) {
                            throw IOException("Сервер не поддержал Range для потока")
                        }
                        val body = response.body ?: throw IOException("Пустой ответ сервера")
                        val fullLength = body.contentLength()
                        var chunkWritten = 0L
                        body.byteStream().use { input ->
                            FileOutputStream(partial, false).buffered().use { output ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read <= 0) break
                                    output.write(buffer, 0, read)
                                    chunkWritten += read
                                    onProgress(chunkWritten, fullLength)
                                }
                                output.flush()
                            }
                        }
                        written = chunkWritten
                        total = fullLength.takeIf { it > 0L } ?: chunkWritten
                        finished = true
                    }

                    response.code == 206 -> {
                        val body = response.body ?: throw IOException("Пустой ответ сервера")
                        if (total <= 0L) {
                            total = parseContentRangeTotal(response.header("Content-Range"))
                        }
                        var chunkWritten = 0L
                        body.byteStream().use { input ->
                            FileOutputStream(partial, start > 0L).buffered().use { output ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read <= 0) break
                                    output.write(buffer, 0, read)
                                    chunkWritten += read
                                    onProgress(written + chunkWritten, total)
                                }
                                output.flush()
                            }
                        }
                        if (chunkWritten <= 0L) {
                            if (total > 0L && written >= total) {
                                finished = true
                            } else {
                                throw IOException("Сервер вернул пустой фрагмент")
                            }
                        } else {
                            written += chunkWritten
                            if (total > 0L && written >= total) finished = true
                        }
                    }

                    else -> {
                        val errorBody = runCatching {
                            response.body?.string().orEmpty().take(200)
                        }.getOrDefault("")
                        throw HttpException(
                            response.code,
                            errorBody.ifBlank { "download failed" },
                        )
                    }
                }
            }
            if (finished) break
        }
        if (written <= 0L) throw IOException("Сервер вернул пустой файл")
    }

    /** `bytes 0-1048575/12345678` → 12345678; неизвестно → -1. */
    private fun parseContentRangeTotal(value: String?): Long {
        if (value.isNullOrBlank()) return -1L
        return value.substringAfterLast('/', "").trim().toLongOrNull()?.takeIf { it > 0L } ?: -1L
    }

    /**
     * Копирует файл, выбранный пользователем (content:// через SAF), в папку
     * загрузок. Это локальная копия своего файла, а не загрузка из сервиса.
     */
    suspend fun importFromUri(uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        val dir = downloadDirectory()
        if (!dir.exists() && !dir.mkdirs()) {
            return@withContext Result.failure(
                IOException("Не удалось создать папку загрузок"),
            )
        }
        val mime = runCatching { appContext.contentResolver.getType(uri) }.getOrNull()
        val displayName = queryDisplayName(uri) ?: "import_${System.currentTimeMillis()}"
        val target = uniqueFile(
            dir,
            safeFileName(displayName, uri.toString(), mime),
        )
        upsert(DownloadItem(fileName = target.name, status = STATUS_IMPORTING))
        try {
            val input = appContext.contentResolver.openInputStream(uri)
                ?: throw IOException("Не удалось открыть файл")
            var bytes = 0L
            input.use { source ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = source.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        bytes += read
                    }
                    output.flush()
                }
            }
            if (bytes <= 0L) throw IOException("Файл пустой")
            upsert(
                DownloadItem(
                    fileName = target.name,
                    bytes = bytes,
                    totalBytes = bytes,
                    status = STATUS_DONE,
                    localPath = target.absolutePath,
                ),
            )
            Result.success(target)
        } catch (error: Exception) {
            runCatching { if (target.exists()) target.delete() }
            failItem(target.name, error)
            Result.failure(error)
        }
    }

    /**
     * Скачивает обложку в приватную папку приложения, чтобы она была видна
     * офлайн и не зависела от кэша Coil. Возвращает null, если не получилось.
     */
    suspend fun downloadArtwork(
        url: String,
        key: String,
        headers: Map<String, String> = emptyMap(),
    ): File? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        val dir = File(appContext.filesDir, "covers")
        if (!dir.exists() && !dir.mkdirs()) return@withContext null
        val id = key.replace(Regex("""[^A-Za-z0-9_-]"""), "_").take(80)
        val existing = dir.listFiles()?.firstOrNull {
            it.nameWithoutExtension == id && it.isFile
        }
        if (existing != null && existing.length() > 0) return@withContext existing
        runCatching {
            val request = Request.Builder().url(url).get()
            if (headers.isEmpty()) {
                request.header("User-Agent", Http.USER_AGENT)
            } else {
                headers.forEach { (name, value) -> request.header(name, value) }
            }
            Http.client.newCall(request.build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body ?: return@use null
                val mime = body.contentType()?.toString().orEmpty()
                val extension = when {
                    mime.contains("png", true) -> ".png"
                    mime.contains("webp", true) -> ".webp"
                    else -> ".jpg"
                }
                val target = File(dir, "$id$extension")
                body.byteStream().use { input ->
                    target.outputStream().buffered().use { output -> input.copyTo(output) }
                }
                if (target.length() > 0) target else null
            }
        }.getOrNull()
    }

    /** Удаляет файл из папки загрузок. Возвращает true, если файла больше нет. */
    suspend fun delete(fileName: String): Boolean = withContext(Dispatchers.IO) {
        val safeName = fileName.substringAfterLast('/').substringAfterLast('\\').trim()
        if (safeName.isBlank()) return@withContext false
        val dir = downloadDirectory()
        val dirCanonical = runCatching { dir.canonicalFile }.getOrNull()
            ?: return@withContext false
        val file = runCatching { File(dir, safeName).canonicalFile }.getOrNull()
            ?: return@withContext false
        val insideDir = file.path == dirCanonical.path ||
            file.path.startsWith(dirCanonical.path + File.separator)
        if (!insideDir) return@withContext false
        runCatching { File(dir, "$safeName.part").takeIf { it.isFile }?.delete() }
        if (!file.isFile) {
            removeItem(safeName)
            return@withContext true
        }
        val deleted = runCatching { file.delete() }.getOrDefault(false)
        if (deleted) removeItem(safeName)
        deleted
    }

    /** Суммарный размер скачанных файлов (без .part). */
    fun sizeBytes(): Long = runCatching {
        downloadDirectory().walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".part") }
            .sumOf { it.length() }
    }.getOrDefault(0L)

    /**
     * Активное подключение — мобильное (нет Wi-Fi/Ethernet, но есть сотовая сеть)?
     * VPN/WARP не мешает: проверяются underlying-сети, а не только activeNetwork.
     */
    fun isOnMobileData(): Boolean = runCatching {
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return@runCatching false
        fun capsOf(network: android.net.Network?): NetworkCapabilities? =
            manager.getNetworkCapabilities(network)
        fun isWifi(caps: NetworkCapabilities?): Boolean =
            caps != null && (
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                )
        fun hasInternet(caps: NetworkCapabilities?): Boolean =
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val all = manager.allNetworks.mapNotNull { capsOf(it) }
        // При активном VPN физический Wi-Fi может быть не default-сетью.
        if (all.any { isWifi(it) && hasInternet(it) }) return@runCatching false
        val active = capsOf(manager.activeNetwork)
        if (active != null && active.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return@runCatching true
        }
        all.any { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && hasInternet(it) }
    }.getOrDefault(false)

    // ---------------------------------------------------------------- внутреннее

    private fun upsert(item: DownloadItem) {
        _items.update { current ->
            val index = current.indexOfFirst { it.fileName == item.fileName }
            if (index < 0) {
                current + item
            } else {
                current.toMutableList().also { it[index] = item }
            }
        }
    }

    private fun removeItem(fileName: String) {
        _items.update { current -> current.filterNot { it.fileName == fileName } }
    }

    private fun failItem(fileName: String, error: Throwable, tag: String? = null) {
        val message = error.message?.takeIf { it.isNotBlank() } ?: "неизвестная ошибка"
        upsert(DownloadItem(fileName = fileName, status = "$STATUS_ERROR: $message", tag = tag))
    }

    private fun uniqueFile(dir: File, fileName: String): File {
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val extension = if (dot > 0) fileName.substring(dot) else ""
        var candidate = File(dir, fileName)
        var counter = 2
        while (candidate.exists()) {
            candidate = File(dir, "$base ($counter)$extension")
            counter++
            if (counter > 999) {
                return File(dir, "${base}_${System.currentTimeMillis()}$extension")
            }
        }
        return candidate
    }

    private fun safeFileName(
        suggested: String,
        sourceUrl: String,
        mime: String? = null,
    ): String {
        val raw = suggested.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = raw.replace(ILLEGAL_CHARS, "_").trim().trim('.')
        var name = cleaned.ifBlank { "audio_${System.currentTimeMillis()}" }
        if (!name.contains('.')) {
            val extension = extensionFromMime(mime) ?: extensionFromUrl(sourceUrl) ?: ".mp3"
            name += extension
        }
        if (name.length > MAX_FILE_NAME) {
            val extension = extensionOf(name)
            name = name.take(MAX_FILE_NAME - extension.length) + extension
        }
        return name
    }

    private fun extensionOf(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) fileName.substring(dot) else ""
    }

    private fun extensionFromUrl(url: String): String? {
        val path = url.substringBefore('?').substringBefore('#')
        val lastSegment = path.substringAfterLast('/')
        val extension = lastSegment.substringAfterLast('.', "").lowercase()
        return if (extension in AUDIO_EXTENSIONS) ".$extension" else null
    }

    private fun extensionFromMime(mime: String?): String? = when (mime?.lowercase()) {
        "audio/mpeg", "audio/mp3" -> ".mp3"
        "audio/mp4", "audio/m4a", "audio/x-m4a" -> ".m4a"
        "audio/aac", "audio/aacp" -> ".aac"
        "audio/ogg", "audio/opus" -> ".ogg"
        "audio/flac", "audio/x-flac" -> ".flac"
        "audio/wav", "audio/x-wav", "audio/wave" -> ".wav"
        "audio/webm" -> ".webm"
        else -> null
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        appContext.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else {
                    null
                }
            }
    }.getOrNull()

    private fun safeFolderName(raw: String): String {
        val cleaned = raw.trim().replace(ILLEGAL_CHARS, "_").trim().trim('.')
        return cleaned.ifBlank { DEFAULT_FOLDER }.take(MAX_FOLDER_NAME)
    }

    companion object {
        const val STATUS_DOWNLOADING = "Скачивание"
        const val STATUS_IMPORTING = "Импорт"
        const val STATUS_DONE = "Готово"
        const val STATUS_ERROR = "Ошибка"

        private const val DEFAULT_FOLDER = "Sonora"
        private const val MAX_FOLDER_NAME = 48
        private const val MAX_FILE_NAME = 96
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_STEP_BYTES = 256 * 1024L
        private const val DEFAULT_RANGE_CHUNK_BYTES = 1L * 1024 * 1024

        private val ILLEGAL_CHARS = Regex("""[\\/:*?"<>|\p{Cntrl}]""")
        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus",
            "flac", "wav", "webm", "weba", "mp4",
        )
    }
}
