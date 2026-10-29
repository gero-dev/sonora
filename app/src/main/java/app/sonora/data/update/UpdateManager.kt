package app.sonora.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import app.sonora.BuildConfig
import app.sonora.core.settings.SettingsStore
import app.sonora.core.util.JsonFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Проверка обновлений и добровольная установка новой версии из GitHub Releases.
 *
 *  - Проверка автоматическая (не чаще раза в сутки) и по кнопке.
 *  - Сначала GitHub API; если он упёрся в лимит 60 запросов/час — резервный
 *    путь через `releases/latest` (без API) и предсказуемое имя APK.
 *  - Скачивание только по нажатию пользователя, с прогрессом и проверкой
 *    SHA-256 из `digest` релиза.
 *  - Установка отдаётся системному установщику через FileProvider. Никакой
 *    самопроизвольной установки: без подтверждения пользователя ничего не
 *    происходит.
 */
object UpdateManager {

    private const val TAG = "UpdateManager"
    private const val REPO = "gero-dev/sonora"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val LATEST_PAGE = "https://github.com/$REPO/releases/latest"
    private const val CACHE_FILE = "update_cache.json"
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
    private const val RETRY_AFTER_FAIL_MS = 60 * 60 * 1000L
    private const val UA = "Sonora/${BuildConfig.VERSION_NAME} (Android)"

    private data class Deps(val context: Context, val settings: SettingsStore)

    @Volatile
    private var deps: Deps? = null

    private val mutex = Mutex()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val _available = MutableStateFlow<UpdateInfo?>(null)
    val available: StateFlow<UpdateInfo?> = _available.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val _downloading = MutableStateFlow(false)
    val downloading: StateFlow<Boolean> = _downloading.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _readyToInstall = MutableStateFlow(false)
    val readyToInstall: StateFlow<Boolean> = _readyToInstall.asStateFlow()

    @Volatile
    private var cancelled = false

    fun init(context: Context, settings: SettingsStore) {
        deps = Deps(context.applicationContext, settings)
    }

    fun currentVersion(): String = BuildConfig.VERSION_NAME

    fun releasePageUrl(): String = "https://github.com/$REPO/releases"

    /** Автопроверка: не чаще раза в сутки, если пользователь её не выключил. */
    suspend fun checkIfDue(): UpdateInfo? {
        val deps = deps ?: return null
        if (!deps.settings.current.updateAutoCheck) return _available.value
        val cache = readCache()
        val now = System.currentTimeMillis()
        if (cache != null && now - cache.checkedAtMs < CHECK_INTERVAL_MS) {
            return publish(cache)
        }
        return checkForUpdates(force = false)
    }

    /** Проверка обновлений. force=true — по кнопке (игнорирует кэш/лимит частоты). */
    suspend fun checkForUpdates(force: Boolean = false): UpdateInfo? = mutex.withLock {
        val deps = deps ?: return null
        if (!force && !deps.settings.current.updateAutoCheck) return _available.value
        if (_checking.value) return _available.value
        _checking.value = true
        _error.value = null
        try {
            val cached = readCache()
            val api = fetchFromApi(cached)
            val info = when {
                api.notModified -> cached?.toInfo()
                api.info != null -> api.info
                else -> fetchFromRedirect()
            }
            if (info != null) {
                val fresh = UpdateCache.from(info, api.etag ?: cached?.etag)
                writeCache(fresh)
                publish(fresh)
            } else {
                if (cached != null) {
                    // Сеть/лимит GitHub: не долбим чаще раза в час.
                    writeCache(cached.copy(checkedAtMs = System.currentTimeMillis()))
                    publish(cached)
                } else {
                    Log.w(TAG, "не удалось проверить обновления")
                    _error.value = "Не удалось проверить обновления"
                }
                null
            }
        } catch (error: Exception) {
            Log.w(TAG, "check failed: ${error.message}")
            _error.value = "Не удалось проверить обновления: ${error.message?.take(80)}"
            null
        } finally {
            _checking.value = false
        }
    }

    /** Пользователь отложил это обновление — не показываем его в авто-режиме. */
    fun dismiss() {
        val info = _available.value ?: return
        deps?.settings?.update { it.copy(updateSkippedVersion = info.versionName) }
        _available.value = null
    }

    fun setAutoCheck(enabled: Boolean) {
        deps?.settings?.update { it.copy(updateAutoCheck = enabled) }
    }

    fun clearError() {
        _error.value = null
    }

    /** Скачать APK и отдать системному установщику. Возвращает true, если запущен установщик. */
    suspend fun downloadAndInstall(): Boolean = withContext(Dispatchers.IO) {
        val deps = deps ?: return@withContext false
        val info = _available.value ?: return@withContext false
        if (_downloading.value) return@withContext false
        if (!deps.context.packageManager.canRequestPackageInstalls()) {
            _error.value = "Разрешите установку из неизвестных источников для Sonora"
            return@withContext false
        }
        _downloading.value = true
        cancelled = false
        _progress.value = 0f
        _error.value = null
        try {
            val dir = File(deps.context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "Sonora-${info.versionName}-debug.apk")
            if (!downloadWithResume(info, target)) return@withContext false
            if (!verifySha256(target, info.sha256)) {
                target.delete()
                _error.value = "Контрольная сумма не совпала — скачивание отменено"
                return@withContext false
            }
            _progress.value = 1f
            _readyToInstall.value = true
            installApk(deps.context, target)
            true
        } catch (error: Exception) {
            Log.w(TAG, "download failed: ${error.message}")
            _error.value = "Не удалось скачать: ${error.message?.take(80)}"
            false
        } finally {
            _downloading.value = false
        }
    }

    /**
     * Скачивание с докачкой: GitHub-ассеты поддерживают `Range`, поэтому при
     * обрыве (а на мобильных сетях это норма) продолжаем с места, а не с нуля.
     * До трёх попыток, с проверкой итогового размера.
     */
    private suspend fun downloadWithResume(info: UpdateInfo, target: File): Boolean {
        val expected = info.sizeBytes?.takeIf { it > 0 }
        val downloader = client.newBuilder()
            .readTimeout(2, TimeUnit.MINUTES)
            .build()
        var attempt = 0
        while (attempt < 3 && !cancelled) {
            attempt++
            try {
                val existing = if (target.exists()) target.length() else 0L
                if (expected != null && existing >= expected) break
                val requestBuilder = Request.Builder()
                    .url(info.apkUrl)
                    .header("User-Agent", UA)
                if (existing > 0L && (expected == null || existing < expected)) {
                    requestBuilder.header("Range", "bytes=$existing-")
                }
                var completed = false
                downloader.newCall(requestBuilder.build()).execute().use { response ->
                    if (response.code == 416) {
                        // Range не принят — начинаем файл заново.
                        target.delete()
                        return@use
                    }
                    if (!response.isSuccessful) {
                        _error.value = "HTTP ${response.code} при скачивании"
                        return false
                    }
                    val body = response.body ?: return false
                    val append = response.code == 206 && existing > 0L
                    if (!append) target.delete()
                    val start = if (append) target.length() else 0L
                    val total = expected ?: (body.contentLength().takeIf { it > 0 }?.plus(start) ?: -1L)
                    var read = start
                    body.byteStream().use { input ->
                        java.io.FileOutputStream(target, append).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                if (cancelled) break
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                read += count
                                if (total > 0) {
                                    _progress.value = (read.toFloat() / total).coerceIn(0f, 1f)
                                }
                            }
                        }
                    }
                    completed = !cancelled && (total <= 0 || target.length() >= total)
                }
                val length = target.length()
                if (cancelled) {
                    target.delete()
                    return false
                }
                if (expected != null) {
                    if (length >= expected) break
                } else if (completed) {
                    break
                }
            } catch (error: Exception) {
                Log.w(TAG, "download attempt $attempt failed: ${error.message}")
                if (attempt >= 3) {
                    _error.value = "Не удалось скачать: ${error.message?.take(80)}"
                    return false
                }
                kotlinx.coroutines.delay(800L)
            }
        }
        if (cancelled) {
            target.delete()
            return false
        }
        if (target.length() < 1_000_000L) {
            target.delete()
            _error.value = "Скачанный файл повреждён"
            return false
        }
        return true
    }

    fun cancelDownload() {
        cancelled = true
    }

    /** Системный диалог «разрешить установку из неизвестных источников»; null — уже можно. */
    fun installPermissionIntent(): Intent? {
        val deps = deps ?: return null
        if (deps.context.packageManager.canRequestPackageInstalls()) return null
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${deps.context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun markInstalled() {
        _available.value = null
        _readyToInstall.value = false
    }

    // ------------------------------------------------------------------ сеть

    private data class ApiFetch(
        val info: UpdateInfo? = null,
        val etag: String? = null,
        val notModified: Boolean = false,
    )

    private fun fetchFromApi(cached: UpdateCache?): ApiFetch {
        val builder = Request.Builder()
            .url(API_URL)
            .header("User-Agent", UA)
            .header("Accept", "application/vnd.github+json")
        cached?.etag?.takeIf { it.isNotBlank() }?.let { builder.header("If-None-Match", it) }
        return runCatching {
            client.newCall(builder.build()).execute().use { response ->
                when {
                    response.code == 304 -> ApiFetch(notModified = true)
                    !response.isSuccessful -> {
                        Log.w(TAG, "GitHub API HTTP ${response.code}")
                        ApiFetch()
                    }
                    else -> {
                        val body = response.body?.string().orEmpty()
                        ApiFetch(
                            info = parseRelease(body),
                            etag = response.header("ETag"),
                        )
                    }
                }
            }
        }.getOrElse {
            Log.w(TAG, "GitHub API error: ${it.message}")
            ApiFetch()
        }
    }

    private fun parseRelease(body: String): UpdateInfo? = runCatching {
        val root = app.sonora.core.net.Http.parseJsonObject(body)
        val tag = (root["tag_name"] as? JsonPrimitive)?.content ?: return null
        val version = tag.removePrefix("v").trim()
        if (version.isBlank()) return null
        val assetObjects = (root["assets"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
        val apk = assetObjects.firstOrNull { asset ->
            val name = (asset["name"] as? JsonPrimitive)?.content.orEmpty()
            name.endsWith(".apk", ignoreCase = true) && name.contains("debug", ignoreCase = true)
        } ?: assetObjects.firstOrNull { asset ->
            (asset["name"] as? JsonPrimitive)?.content
                ?.endsWith(".apk", ignoreCase = true) == true
        } ?: return null
        val url = (apk["browser_download_url"] as? JsonPrimitive)?.content ?: return null
        val size = (apk["size"] as? JsonPrimitive)?.content?.toLongOrNull()
        val digest = (apk["digest"] as? JsonPrimitive)?.content
        val sha = digest?.removePrefix("sha256:")?.takeIf { it.length == 64 }
        UpdateInfo(
            versionName = version,
            tag = tag,
            apkUrl = url,
            notes = (root["body"] as? JsonPrimitive)?.content.orEmpty(),
            publishedAt = (root["published_at"] as? JsonPrimitive)?.content,
            sizeBytes = size,
            sha256 = sha,
        )
    }.getOrNull()

    /** Резерв без API: `releases/latest` редиректит на страницу тега. */
    private fun fetchFromRedirect(): UpdateInfo? = runCatching {
        val request = Request.Builder().url(LATEST_PAGE).header("User-Agent", UA).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val finalUrl = response.request.url.toString()
            val tag = Regex("/tag/(v?[0-9][0-9A-Za-z._-]*)").find(finalUrl)
                ?.groupValues?.get(1) ?: return null
            val version = tag.removePrefix("v").trim()
            if (version.isBlank()) return null
            UpdateInfo(
                versionName = version,
                tag = tag,
                apkUrl = "https://github.com/$REPO/releases/download/$tag/Sonora-$version-debug.apk",
                notes = "",
                publishedAt = null,
                sizeBytes = null,
                sha256 = null,
            )
        }
    }.getOrNull()

    // -------------------------------------------------------------- служебное

    private fun publish(cache: UpdateCache): UpdateInfo? {
        val info = cache.toInfo() ?: return null
        val newer = isNewerVersion(info.versionName, BuildConfig.VERSION_NAME)
        val skipped = deps?.settings?.current?.updateSkippedVersion.orEmpty()
        val visible = newer && info.versionName != skipped
        _available.value = if (visible) info else null
        return if (visible) info else null
    }

    private suspend fun readCache(): UpdateCache? = runCatching {
        val deps = deps ?: return null
        JsonFile.read(deps.context, CACHE_FILE, UpdateCache.serializer(), UpdateCache())
            .takeIf { it.versionName.isNotBlank() }
    }.getOrNull()

    private suspend fun writeCache(cache: UpdateCache) {
        val deps = deps ?: return
        JsonFile.write(deps.context, CACHE_FILE, UpdateCache.serializer(), cache)
    }

    private fun verifySha256(file: File, expected: String?): Boolean {
        if (expected.isNullOrBlank()) return true
        val digest = runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    md.update(buffer, 0, count)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull() ?: return false
        return digest.equals(expected, ignoreCase = true)
    }

    private fun installApk(context: Context, file: File) {
        runCatching {
            val authority = "${context.packageName}.updates"
            val uri = FileProvider.getUriForFile(context, authority, file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.onFailure {
            Log.w(TAG, "install failed: ${it.message}")
            _error.value = "Не удалось открыть установщик: ${it.message?.take(80)}"
        }
    }
}
