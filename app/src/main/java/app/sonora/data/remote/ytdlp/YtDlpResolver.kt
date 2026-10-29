package app.sonora.data.remote.ytdlp

import android.content.Context
import android.util.Log
import app.sonora.data.remote.StreamProbe
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
 * yt-dlp как источник и резерв потока.
 *
 * Что важно для РФ:
 *  1. **Встроенный yt-dlp устаревает.** Библиотека youtubedl-android 0.18.1
 *     везёт сборку 2025.11.12, а YouTube меняет плеер каждые несколько недель:
 *     старая сборка отдаёт 403 даже там, где прямой доступ есть. Поэтому рантайм
 *     обновляется из GitHub (STABLE-канал) при первом обращении и раз в сутки.
 *  2. **Подпись ссылки привязана к IP и клиенту**, поэтому после резолва ссылка
 *     проверяется настоящим Range-запросом. Только рабочий URL попадает в плеер.
 *  3. **Перебор клиентов YouTube**: разные клиенты (tv, web_safari, ios, mweb…)
 *     получают ссылки по-разному; если один даёт 403, пробуем следующий.
 *  4. **IPv4/IPv6**: на части российских провайдеров ограничение не затрагивает
 *     IPv6 — в режиме «Авто» протоколы чередуются.
 *
 * Если ни один прямой вариант не прошёл, вызывающий слой (YtDlpBridge /
 * InnerTubeStreams) идёт в [app.sonora.net.relay.RelayGateway] — медиашлюз
 * без VPN и WARP.
 */
class YtDlpResolver(
    private val context: Context,
    private val settings: app.sonora.core.settings.SettingsStore,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val initMutex = Mutex()
    private val updateMutex = Mutex()

    @Volatile
    private var initialized = false

    @Volatile
    private var version: String? = null

    /**
     * Наборы `--extractor-args youtube:player_client=…`. Порядок проверен
     * практикой 2026 года: сначала дефолтный набор с tv_simply (не требует
     * PoToken), затем клиенты с другим способом подписи, затем web/mobile.
     */
    private val clientSets: List<String> = listOf(
        "youtube:player_client=default,tv_simply",
        "youtube:player_client=tv,android_vr",
        "youtube:player_client=web_safari,web_embedded",
        "youtube:player_client=ios,mweb",
        "youtube:player_client=android,web",
    )

    /** Инициализация Python + yt-dlp. Идемпотентно, можно звать из фона. */
    suspend fun ensureInit() = initMutex.withLock {
        if (initialized) return@withLock
        withContext(Dispatchers.IO) {
            val result = runCatching { YoutubeDL.getInstance().init(context) }
            if (result.isFailure) {
                Log.w(TAG, "init failed: ${result.exceptionOrNull()?.message}")
            } else {
                // Версию читаем прямо из установленного zipapp: library.version()
                // возвращает «unknown», пока не сработает встроенный updater.
                version = versionOfInstalledRuntime()
                    ?: runCatching { YoutubeDL.getInstance().version(context) }.getOrNull()
                Log.i(TAG, "ready, version=${version ?: "unknown"}")
                version?.let { current ->
                    if (current != settings.current.ytDlpVersion) {
                        settings.update { it.copy(ytDlpVersion = current) }
                    }
                }
            }
            // ffmpeg нужен только для нарезки фрагмента под ReccoBeats.
            runCatching { FFmpeg.getInstance().init(context) }
                .onFailure { Log.w(TAG, "ffmpeg init failed: ${it.message}") }
        }
        initialized = true
    }

    /** Текущая версия yt-dlp (после инициализации). */
    fun versionLabel(): String? = version

    /**
     * Обновляет yt-dlp из GitHub. force=true — по кнопке в настройках,
     * иначе не чаще одного раза в [UPDATE_INTERVAL_MS].
     * Возвращает версию после обновления или null при ошибке сети.
     */
    suspend fun ensureUpToDate(force: Boolean = false): String? {
        ensureInit()
        val state = settings.current
        if (!force && !state.ytDlpAutoUpdate) return version
        val fresh = System.currentTimeMillis() - state.ytDlpCheckedAtMs < UPDATE_INTERVAL_MS
        if (!force && fresh) return version

        return updateMutex.withLock {
            val latestState = settings.current
            if (!force && !latestState.ytDlpAutoUpdate) return@withLock version
            if (!force && System.currentTimeMillis() - latestState.ytDlpCheckedAtMs < UPDATE_INTERVAL_MS) {
                return@withLock version
            }
            withContext(Dispatchers.IO) {
                // Основной путь — прямое скачивание zipapp с GitHub Releases:
                // GitHub API (которым пользуется встроенный updater библиотеки)
                // быстро упирается в лимит 60 запросов/час на общий IP.
                val directVersion = runCatching {
                    withTimeoutOrNull(UPDATE_TIMEOUT_MS) { updateDirect() }
                }.getOrNull()

                if (directVersion != null) {
                    version = directVersion
                    Log.i(TAG, "обновлён напрямую: $directVersion")
                } else {
                    // Резерв: встроенный updater библиотеки (api.github.com).
                    val outcome = runCatching {
                        withTimeoutOrNull(UPDATE_TIMEOUT_MS) {
                            YoutubeDL.getInstance().updateYoutubeDL(
                                context,
                                YoutubeDL.UpdateChannel.STABLE,
                            )
                        }
                    }
                    val status = outcome.getOrNull()
                    if (status != null) {
                        version = versionOfInstalledRuntime()
                            ?: runCatching { YoutubeDL.getInstance().version(context) }
                                .getOrNull()
                            ?: version
                        Log.i(TAG, "update status=$status version=$version")
                    } else {
                        Log.w(
                            TAG,
                            "update failed: direct=null library=${outcome.exceptionOrNull()?.message}",
                        )
                    }
                }
                // Отмечаем проверку: успех — сутки, ошибка — повтор через 10 минут.
                val checkedAt = if (directVersion != null) {
                    System.currentTimeMillis()
                } else {
                    System.currentTimeMillis() - UPDATE_INTERVAL_MS + RETRY_AFTER_FAILURE_MS
                }
                settings.update {
                    it.copy(
                        ytDlpCheckedAtMs = checkedAt,
                        ytDlpVersion = version ?: it.ytDlpVersion,
                    )
                }
                version
            }
        }
    }

    /**
     * Прямое обновление yt-dlp без GitHub API: скачиваем zipapp с
     * `releases/latest/download/yt-dlp`, проверяем, что это настоящий zipapp,
     * ставим на место и делаем smoke-тест `--version`. При сбое откатываем
     * предыдущий файл, чтобы не остаться без источника потока.
     */
    private fun updateDirect(): String? {
        val url = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp"
        val tmp = java.io.File(context.cacheDir, "yt-dlp-update.bin")
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", "Sonora/1.6 (Android)")
            .build()
        val downloaded = runCatching {
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
                .newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "direct update HTTP ${response.code}")
                        return null
                    }
                    val body = response.body ?: return null
                    body.byteStream().use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    }
                    true
                }
        }.getOrNull() ?: false
        if (!downloaded || tmp.length() < MIN_RUNTIME_BYTES) {
            tmp.delete()
            return null
        }
        val newVersion = readZipappVersion(tmp)
        if (newVersion == null) {
            Log.w(TAG, "direct update: downloaded file is not a zipapp")
            tmp.delete()
            return null
        }

        val dir = runtimeDir().apply { mkdirs() }
        val binary = java.io.File(dir, YoutubeDL.ytdlpBin)
        val backup = java.io.File(dir, "yt-dlp.prev")
        runCatching { if (binary.exists()) binary.copyTo(backup, overwrite = true) }
        runCatching { tmp.copyTo(binary, overwrite = true) }
        tmp.delete()

        // Smoke-тест: обновлённый yt-dlp должен хотя бы напечатать версию.
        val smokeOk = runCatching {
            val smoke = YoutubeDL.getInstance().execute(
                YoutubeDLRequest("--version").apply { addOption("--no-warnings") },
            )
            smoke.exitCode == 0 && smoke.out.contains('.')
        }.getOrDefault(false)
        if (!smokeOk) {
            Log.w(TAG, "direct update smoke test failed, rollback")
            runCatching {
                if (backup.exists()) backup.copyTo(binary, overwrite = true) else binary.delete()
            }
            return null
        }
        runCatching { backup.delete() }
        return newVersion
    }

    /** Папка, куда youtubedl-android ставит zipapp: noBackupFilesDir/youtubedl-android/yt-dlp. */
    private fun runtimeDir(): java.io.File = java.io.File(
        java.io.File(context.noBackupFilesDir, YoutubeDL.baseName),
        YoutubeDL.ytdlpDirName,
    )

    private fun runtimeBinary(): java.io.File = java.io.File(runtimeDir(), YoutubeDL.ytdlpBin)

    private fun versionOfInstalledRuntime(): String? = runCatching {
        val binary = runtimeBinary()
        if (binary.exists()) readZipappVersion(binary) else null
    }.getOrNull()

    /**
     * Версия из zipapp: читаем `yt_dlp/version.py` прямо из архива.
     * Заодно проверяем, что файл действительно zipapp, а не HTML-ошибка.
     */
    private fun readZipappVersion(file: java.io.File): String? = runCatching {
        if (file.length() < MIN_RUNTIME_BYTES) return null
        java.util.zip.ZipFile(file).use { zip ->
            val entry = zip.getEntry("yt_dlp/version.py") ?: return null
            val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }
            Regex("""__version__\s*=\s*'([^']+)'""").find(text)?.groupValues?.get(1)
        }
    }.getOrNull()

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
                proxyOption()
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
     *
     * Перебираем наборы клиентов и IP-протоколы, каждую ссылку проверяем
     * Range-запросом. Возвращаем только тот URL, который реально играет.
     */
    suspend fun streamHandle(idOrUrl: String): StreamHandle? = withContext(Dispatchers.IO) {
        ensureInit()
        // Обновление не должно задерживать первый трек: ждём не дольше 20 с,
        // а если GitHub недоступен — играем на встроенной сборке.
        runCatching { withTimeoutOrNull(UPDATE_WAIT_MS) { ensureUpToDate() } }

        val deadline = System.currentTimeMillis() + TOTAL_RESOLVE_TIMEOUT_MS
        var lastClientSet: String? = null

        for (attempt in buildAttempts()) {
            if (System.currentTimeMillis() >= deadline) {
                Log.w(TAG, "resolve deadline")
                break
            }
            val handle = attemptStream(idOrUrl, attempt.extractorArgs, attempt.forceIp)
                ?: continue
            lastClientSet = attempt.extractorArgs
            if (!settings.current.probeBeforePlay) {
                rememberClient(attempt.extractorArgs)
                return@withContext handle
            }
            val probe = StreamProbe.probe(handle.url, handle.headers)
            if (probe.ok) {
                rememberClient(attempt.extractorArgs)
                Log.i(
                    TAG,
                    "поток OK: ${attempt.extractorArgs} ip=${attempt.forceIp ?: "auto"} " +
                        "${probe.elapsedMs} мс, ${probe.bytes} Б",
                )
                return@withContext handle
            }
            Log.w(TAG, "probe ${attempt.extractorArgs} ip=${attempt.forceIp ?: "auto"}: ${probe.reason()}")
        }

        Log.w(TAG, "no playable url; last=${lastClientSet ?: "-"}")
        null
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
                proxyOption()
            }
            val response = withTimeoutOrNull(SNIPPET_TIMEOUT_MS) {
                runCatching { YoutubeDL.getInstance().execute(request) }
                    .onFailure { Log.w(TAG, "snippet failed: ${it.message}") }
                    .getOrNull()
            } ?: run {
                Log.w(TAG, "snippet timeout")
                return@withContext null
            }
            if (response.exitCode != 0) {
                Log.w(TAG, "snippet exit=${response.exitCode} ${response.err.takeLast(200)}")
            }
            dir.listFiles()
                ?.filter { it.name.endsWith(".mp3") && it.length() in 1..MAX_SNIPPET_BYTES }
                ?.maxByOrNull { it.lastModified() }
        }

    // --------------------------------------------------------------- attempts

    private data class Attempt(val extractorArgs: String, val forceIp: String?)

    private fun buildAttempts(): List<Attempt> {
        val state = settings.current
        val ipSequence: List<String?> = when (state.youtubeIpMode) {
            app.sonora.core.settings.YoutubeIpMode.IPV4 -> listOf("--force-ipv4")
            app.sonora.core.settings.YoutubeIpMode.IPV6 -> listOf("--force-ipv6")
            app.sonora.core.settings.YoutubeIpMode.AUTO ->
                listOf(null, "--force-ipv4", "--force-ipv6")
        }
        // Клиент, который дал ссылку в прошлый раз, пробуем первым.
        val orderedClients = buildList {
            state.ytDlpLastClient.trim().takeIf { it.isNotBlank() }?.let { add(it) }
            addAll(clientSets)
        }.distinct()

        val attempts = ArrayList<Attempt>(orderedClients.size * 2)
        for (ip in ipSequence) {
            for (client in orderedClients) {
                // В режиме «Авто» не перебираем все клиенты на обоих протоколах:
                // это слишком долго. Первый протокол — все клиенты, второй — два.
                if (ip != ipSequence.first() && client != orderedClients.first()) continue
                attempts += Attempt(client, ip)
            }
        }
        return attempts
    }

    private suspend fun attemptStream(
        idOrUrl: String,
        extractorArgs: String,
        forceIp: String?,
    ): StreamHandle? = withContext(Dispatchers.IO) {
        val request = YoutubeDLRequest(watchUrl(idOrUrl)).apply {
            addOption("-f", "bestaudio/best")
            addOption("--dump-json")
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--no-progress")
            addOption("--socket-timeout", "10")
            addOption("--retries", "1")
            addOption("--extractor-args", extractorArgs)
            forceIp?.let { addOption(it) }
            proxyOption()
        }
        val response = withTimeoutOrNull(ATTEMPT_TIMEOUT_MS) {
            runCatching { YoutubeDL.getInstance().execute(request) }
                .onFailure { Log.w(TAG, "resolve failed ($extractorArgs): ${it.message}") }
                .getOrNull()
        } ?: return@withContext null

        if (response.exitCode != 0) {
            Log.w(TAG, "exit=${response.exitCode} ($extractorArgs) err=${response.err.takeLast(200)}")
        }

        val parsed = response.out.lineSequence()
            .filter { it.startsWith("{") }
            .mapNotNull { runCatching { json.decodeFromString<YtDumpJson>(it) }.getOrNull() }
            .toList()
        val chosen = parsed.firstOrNull { !it.url.isNullOrBlank() }
        if (chosen == null) {
            Log.w(TAG, "no url ($extractorArgs): lines=${parsed.size} out=${response.out.take(160)}")
            return@withContext null
        }
        StreamHandle(
            url = chosen.url!!,
            headers = chosen.httpHeaders,
            mimeType = mimeOf(chosen.ext),
        )
    }

    private fun rememberClient(extractorArgs: String) {
        if (settings.current.ytDlpLastClient != extractorArgs) {
            settings.update { it.copy(ytDlpLastClient = extractorArgs) }
        }
    }

    private fun YoutubeDLRequest.proxyOption() {
        settings.current.ytDlpProxy.trim().takeIf { it.isNotBlank() }?.let {
            addOption("--proxy", it)
        }
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
        const val TAG = "YtDlp"

        /** yt-dlp может «залипнуть» на извлечении — ограничиваем ожидание. */
        const val SEARCH_TIMEOUT_MS = 20_000L
        const val ATTEMPT_TIMEOUT_MS = 20_000L
        const val TOTAL_RESOLVE_TIMEOUT_MS = 60_000L
        const val SNIPPET_TIMEOUT_MS = 90_000L

        /** Обновление рантайма: не чаще раза в сутки. */
        const val UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L
        const val UPDATE_TIMEOUT_MS = 90_000L

        /** Первый трек не ждёт обновления дольше этого времени. */
        const val UPDATE_WAIT_MS = 20_000L

        /** После неудачного обновления пробуем снова через 10 минут. */
        const val RETRY_AFTER_FAILURE_MS = 10 * 60 * 1000L

        /** Минимальный размер zipapp yt-dlp (~3 МБ). */
        const val MIN_RUNTIME_BYTES = 1_500_000L

        /** Лимит ReccoBeats — 5 МБ; держим запас. */
        const val MAX_SNIPPET_BYTES = 5L * 1024 * 1024
    }
}
