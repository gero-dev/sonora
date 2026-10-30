package app.sonora.data.streaming

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import app.sonora.core.settings.SettingsStore
import app.sonora.data.remote.StreamProbe
import app.sonora.data.remote.isAudioContentType
import app.sonora.data.remote.youtube.innertube.InnerTubeApi
import app.sonora.data.remote.youtube.innertube.InnerTubeStreams
import app.sonora.data.remote.youtube.innertube.InnerTubeXEngine
import app.sonora.data.remote.ytdlp.YtDlpBridge
import app.sonora.data.remote.ytdlp.YtDlpResolver
import com.metrolist.innertubex.extraction.AudioQuality as EngineQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Автопилот стриминга.
 *
 * Проверяет доступные способы получить звук YouTube и выбирает рабочий:
 *
 *  1. **Прямой InnerTube** — быстрее всего, но медиа-IP может быть заблокирован.
 *  2. **yt-dlp** — свой перебор клиентов, IPv4/IPv6 и Range-проверка; ходит
 *     через локальный прокси NoHiT.
 *
 * VPN-туннелей и публичных медиашлюзов в приложении нет: единственный обход
 * блокировок — NoHiT. Результат кэшируется на [STALE_MS] и при смене сети.
 * Если прямой путь перестал работать во время воспроизведения, [recover]
 * заново подбирает способ и возвращает его название — плеер переоткрывает трек.
 */
object StreamingAutoPilot {

    private const val TAG = "AutoPilot"
    private const val STALE_MS = 30 * 60_000L
    private const val DEFAULT_VIDEO = "dQw4w9WgXcQ"
    private const val DIRECT_TIMEOUT_MS = 15_000L
    private const val YTDLP_TIMEOUT_MS = 40_000L

    private data class Deps(
        val context: Context,
        val settings: SettingsStore,
        val ytDlp: YtDlpResolver,
    )

    @Volatile
    private var deps: Deps? = null

    private val mutex = Mutex()

    private val _audit = MutableStateFlow<StreamingAudit?>(null)
    val audit: StateFlow<StreamingAudit?> = _audit.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    @Volatile
    private var lastNetwork: String? = null

    /** Сеть сменилась — результат автопроверки устарел. */
    @Volatile
    private var networkDirty = false

    fun init(
        context: Context,
        settings: SettingsStore,
        ytDlp: YtDlpResolver,
    ) {
        deps = Deps(context.applicationContext, settings, ytDlp)
        runCatching {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            manager?.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) {
                        networkDirty = true
                    }

                    override fun onLost(network: android.net.Network) {
                        networkDirty = true
                    }
                },
            )
        }
    }

    fun lastAudit(): StreamingAudit? = _audit.value

    fun isStale(): Boolean {
        if (networkDirty) return true
        val current = _audit.value ?: return true
        return System.currentTimeMillis() - current.measuredAtMs > STALE_MS ||
            current.network != networkLabel()
    }

    fun enabled(): Boolean = deps?.settings?.current?.autoTransport ?: true

    /** Текущий порядок транспортов: из последней проверки или разумный дефолт. */
    fun effectiveChain(): List<TransportKind> =
        _audit.value?.chain() ?: TransportKind.fallbackOrder

    fun prefersYtDlpFirst(): Boolean = _audit.value?.best == TransportKind.YTDLP

    /**
     * Быстрый признак «API YouTube вообще доступен с этой сети».
     * На российских сетях с DPI TLS до music.youtube.com рвётся RST за
     * десятки миллисекунд — прямой путь там заведомо бесполезен.
     */
    private suspend fun probeApiReachable(): Boolean {
        val probe = StreamProbe.probeAny("https://music.youtube.com/")
        // Любой полученный HTTP-ответ (даже 403/405) означает, что TCP+TLS
        // до YouTube доходят. null — TLS оборвали (DPI/RST).
        return probe.ok || probe.code != null
    }

    fun networkLabel(): String {
        val context = deps?.context ?: return "неизвестно"
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return "неизвестно"
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return "нет сети"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "моб. сеть"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "сеть"
        }
    }

    /**
     * Быстрая проверка в фоне: прямой путь, а при неудаче — yt-dlp.
     * Не запускается, если автопилот выключен или проверка ещё свежая.
     */
    suspend fun ensureAudited(force: Boolean = false): StreamingAudit? {
        val deps = deps ?: return null
        if (!force && !deps.settings.current.autoTransport) return _audit.value
        if (!force && !isStale()) return _audit.value
        return runAudit(deep = false)
    }

    /**
     * Полная проверка способов. deep=true дополнительно проверяет yt-dlp,
     * даже если прямой путь уже работает (медленнее, зато видно резерв).
     */
    suspend fun runAudit(
        deep: Boolean = false,
        videoId: String = DEFAULT_VIDEO,
    ): StreamingAudit = mutex.withLock {
        val deps = deps ?: return StreamingAudit(
            checks = emptyList(),
            best = null,
            network = "—",
            deep = deep,
        )
        _running.value = true
        _message.value = if (deep) "Проверяем все способы…" else "Проверяем поток…"
        networkDirty = false
        lastNetwork = networkLabel()
        try {
            val checks = ArrayList<TransportCheck>(2)

            val direct = probeDirect(videoId)
            checks += direct
            if (!direct.ok) {
                checks += probeYtDlp(videoId)
            } else if (deep) {
                // Пользователь нажал «Проверить все способы» — показываем резерв.
                checks += probeYtDlp(videoId)
            }

            val best = checks.firstOrNull { it.ok }?.kind
            val result = StreamingAudit(
                checks = checks,
                best = best,
                network = networkLabel(),
                deep = deep,
            )
            _audit.value = result
            _message.value = result.summaryLine()
            Log.i(TAG, "audit: ${result.detailsText().replace('\n', ' ')}")
            result
        } finally {
            _running.value = false
        }
    }

    /** Убедиться, что перед воспроизведением известен рабочий способ. */
    suspend fun prepareForPlayback() {
        val deps = deps ?: return
        if (!deps.settings.current.autoTransport) {
            return
        }
        // Первый трек после установки/смены сети: быстрая проверка решает,
        // какой источник использовать. Ограничиваем ожидание.
        if (_audit.value == null || isStale()) {
            withTimeoutOrNull(28_000) { runAudit(deep = false) }
        }
    }

    /**
     * После ошибки воспроизведения: сбросить кэши и подобрать другой способ.
     * Возвращает название способа, на который удалось переключиться, или null.
     */
    suspend fun recover(videoId: String): String? = mutex.withLock {
        val deps = deps ?: return null
        val failed = _audit.value?.best
        InnerTubeStreams.invalidate(videoId)
        YtDlpBridge.invalidate(videoId)

        // На сети с DPI-блокировкой YouTube прямой путь заведомо мёртв —
        // начинаем с yt-dlp, чтобы восстановление заняло секунды.
        val order = when (deps.settings.current.streamSource) {
            app.sonora.core.settings.StreamSource.INNERTUBE ->
                listOf(TransportKind.DIRECT, TransportKind.YTDLP)
            app.sonora.core.settings.StreamSource.YTDLP ->
                listOf(TransportKind.YTDLP, TransportKind.DIRECT)
            app.sonora.core.settings.StreamSource.AUTO ->
                if (probeApiReachable()) {
                    listOf(TransportKind.DIRECT, TransportKind.YTDLP)
                } else {
                    listOf(TransportKind.YTDLP, TransportKind.DIRECT)
                }
        }.filter { it != failed }

        val checks = ArrayList<TransportCheck>(2)
        _message.value = "Подбираем рабочий способ…"
        for (kind in order) {
            val check = when (kind) {
                TransportKind.DIRECT -> probeDirect(videoId)
                TransportKind.YTDLP -> probeYtDlp(videoId)
            }
            checks += check
            if (check.ok) {
                _audit.value = StreamingAudit(
                    checks = checks,
                    best = kind,
                    network = networkLabel(),
                    deep = false,
                )
                Log.i(TAG, "recovered via ${kind.label} (${check.detail})")
                _message.value = "Переключено на ${kind.label}"
                return kind.label
            }
        }
        _message.value = "Рабочий способ не найден"
        null
    }

    // ------------------------------------------------------------- проверки

    private suspend fun probeDirect(videoId: String): TransportCheck {
        val started = SystemClock.elapsedRealtime()
        val failures = ArrayList<String>(2)

        // 1. InnerTubeX — быстрый движок.
        val engine = runCatching {
            withTimeoutOrNull(DIRECT_TIMEOUT_MS) {
                InnerTubeXEngine.resolve(videoId, EngineQuality.AUTO)
            }
        }.getOrNull()
        if (engine != null) {
            val probe = StreamProbe.probe(engine.url, engine.headers)
            if (probe.ok && probe.contentType.isAudioContentType()) {
                return TransportCheck(
                    kind = TransportKind.DIRECT,
                    ok = true,
                    latencyMs = SystemClock.elapsedRealtime() - started,
                    detail = "OK · ${probe.elapsedMs} мс",
                    bitrateKbps = engine.bitrate / 1000,
                )
            }
            failures += probe.reason()
        } else {
            failures += "нет ссылки"
        }

        // 2. Прямой player-ответ.
        val direct = runCatching {
            withTimeoutOrNull(DIRECT_TIMEOUT_MS) { InnerTubeApi().stream(videoId) }
        }.getOrNull()
        if (direct != null) {
            val probe = StreamProbe.probe(direct.url, direct.headers)
            if (probe.ok && probe.contentType.isAudioContentType()) {
                return TransportCheck(
                    kind = TransportKind.DIRECT,
                    ok = true,
                    latencyMs = SystemClock.elapsedRealtime() - started,
                    detail = "OK (player) · ${probe.elapsedMs} мс",
                    bitrateKbps = direct.bitrate / 1000,
                )
            }
            failures += probe.reason()
        } else {
            failures += "нет ответа player"
        }

        return TransportCheck(
            kind = TransportKind.DIRECT,
            ok = false,
            latencyMs = SystemClock.elapsedRealtime() - started,
            detail = failures.distinct().joinToString(" · ").take(120),
        )
    }

    private suspend fun probeYtDlp(
        videoId: String,
        timeoutMs: Long = YTDLP_TIMEOUT_MS,
    ): TransportCheck {
        val started = SystemClock.elapsedRealtime()
        val resolver = deps?.ytDlp
            ?: return TransportCheck(TransportKind.YTDLP, false, 0, "не инициализирован")
        val handle = runCatching {
            withTimeoutOrNull(timeoutMs) { resolver.streamHandle(videoId) }
        }.getOrNull()
        val latency = SystemClock.elapsedRealtime() - started
        return if (handle != null) {
            TransportCheck(
                kind = TransportKind.YTDLP,
                ok = true,
                latencyMs = latency,
                detail = "OK · ${handle.mimeType}",
            )
        } else {
            TransportCheck(
                kind = TransportKind.YTDLP,
                ok = false,
                latencyMs = latency,
                detail = "нет играющей ссылки (403/ошибка)",
            )
        }
    }
}
