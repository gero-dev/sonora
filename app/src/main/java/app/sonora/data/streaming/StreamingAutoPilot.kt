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
import app.sonora.net.relay.RelayGateway
import app.sonora.net.warp.WarpState
import app.sonora.net.warp.WarpTunnel
import com.metrolist.innertubex.extraction.AudioQuality as EngineQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Автопилот стриминга.
 *
 * Проверяет по очереди все известные способы получить звук YouTube и выбирает
 * рабочий:
 *
 *  1. **Прямой InnerTube** — быстрее всего, но медиа-IP может быть заблокирован.
 *  2. **yt-dlp** — свой перебор клиентов, IPv4/IPv6 и Range-проверка.
 *  3. **Медиашлюз** — публичные Invidious/Piped.
 *  4. **WARP Sonora** — собственный WireGuard-туннель (перебирает конфиги,
 *     проверяет именно воспроизведение, а не только handshake).
 *
 * Результат кэшируется на [STALE_MS] и при смене сети. Если прямой путь
 * перестал работать во время воспроизведения, [recover] заново подбирает
 * обход и возвращает его название — плеер переоткрывает трек.
 */
object StreamingAutoPilot {

    private const val TAG = "AutoPilot"
    private const val STALE_MS = 30 * 60_000L
    private const val DEFAULT_VIDEO = "dQw4w9WgXcQ"
    private const val DIRECT_TIMEOUT_MS = 15_000L
    private const val YTDLP_TIMEOUT_MS = 40_000L
    private const val RELAY_TIMEOUT_MS = 15_000L
    private const val WARP_CONNECT_TIMEOUT_MS = 20_000L
    private const val WARP_CHECK_TIMEOUT_MS = 75_000L
    private const val WARP_YTDLP_TIMEOUT_MS = 25_000L

    /** Дефолтный endpoint Cloudflare WARP: в РФ часто не поднимает handshake. */
    private const val ENGAGE_ENDPOINT = "engage.cloudflareclient.com:2408"

    private data class Deps(
        val context: Context,
        val settings: SettingsStore,
        val ytDlp: YtDlpResolver,
        val warp: WarpTunnel,
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
        warp: WarpTunnel,
    ) {
        deps = Deps(context.applicationContext, settings, ytDlp, warp)
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

    fun prefersRelayFirst(): Boolean =
        _audit.value?.best == TransportKind.RELAY && RelayGateway.isEnabled()

    fun prefersYtDlpFirst(): Boolean =
        _audit.value?.best == TransportKind.YTDLP ||
            (_audit.value?.best == TransportKind.WARP && _audit.value?.warpYtDlpOk == true)

    /**
     * Быстрый признак «API YouTube вообще доступен с этой сети».
     * На российских сетях с DPI TLS до music.youtube.com рвётся RST за
     * десятки миллисекунд — прямой путь и шлюзы там заведомо бесполезны.
     */
    private suspend fun probeApiReachable(): Boolean {
        val probe = StreamProbe.probeAny("https://music.youtube.com/")
        // Любой полученный HTTP-ответ (даже 403/405) означает, что TCP+TLS
        // до YouTube доходят. null — TLS оборвали (DPI/RST).
        return probe.ok || probe.code != null
    }

    /** Есть ли шанс поднять собственный WARP. */
    private fun warpUsable(deps: Deps): Boolean =
        deps.warp.configs().isNotEmpty() &&
            !deps.warp.externalVpnActive() &&
            deps.warp.hasVpnPermission()

    fun networkLabel(): String {
        val context = deps?.context ?: return "неизвестно"
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return "неизвестно"
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return "нет сети"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN/WARP"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "моб. сеть"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "сеть"
        }
    }

    /**
     * Быстрая проверка в фоне: прямой путь, а при неудаче — yt-dlp, шлюз и WARP.
     * Не запускается, если автопилот выключен или проверка ещё свежая.
     */
    suspend fun ensureAudited(force: Boolean = false): StreamingAudit? {
        val deps = deps ?: return null
        if (!force && !deps.settings.current.autoTransport) return _audit.value
        if (!force && !isStale()) return _audit.value
        return runAudit(deep = false)
    }

    /**
     * Полная проверка всех способов. deep=true дополнительно проверяет WARP
     * (медленно: перебор конфигов и реальное воспроизведение через туннель).
     */
    suspend fun runAudit(
        deep: Boolean = false,
        videoId: String = DEFAULT_VIDEO,
    ): StreamingAudit = mutex.withLock {
        val deps = deps ?: return StreamingAudit(
            checks = emptyList(),
            best = null,
            network = "—",
            warpConnected = false,
            warpLeftOn = false,
            deep = deep,
        )
        _running.value = true
        _message.value = if (deep) "Проверяем все способы…" else "Проверяем поток…"
        networkDirty = false
        lastNetwork = networkLabel()
        try {
            val checks = ArrayList<TransportCheck>(5)
            var warpYtDlpOk = false

            // Быстрый признак «DPI режет YouTube»: TLS до music.youtube.com
            // обрывается (RST), хотя TCP открывается. На такой сети прямой
            // путь и публичные шлюзы заведомо не работают (замеры из РФ:
            // yt-dlp 0/13, шлюзы 0/32) — сразу идём в WARP, не тратя секунды.
            val apiReachable = probeApiReachable()
            Log.i(TAG, "api reachable=$apiReachable network=${networkLabel()}")

            if (!apiReachable && deps.settings.current.autoWarpFallback && warpUsable(deps)) {
                // В авто-режиме не тратим время на заведомо мёртвый прямой путь:
                // WARP и yt-dlp проверяются сразу (это секунды вместо минуты).
                if (deep) checks += probeDirect(videoId)
                val warp = probeWarp(videoId, fast = !deep)
                checks += warp.check
                warpYtDlpOk = warp.ytDlpOk
                if (!warp.check.ok) {
                    // WARP не помог — проверяем остальные пути.
                    checks += probeYtDlp(videoId)
                    if (RelayGateway.isEnabled()) checks += probeRelay(videoId)
                }
            } else {
                val direct = probeDirect(videoId)
                checks += direct
                if (!direct.ok) {
                    val ytDlp = probeYtDlp(videoId)
                    checks += ytDlp
                    if (!ytDlp.ok) {
                        if (deps.settings.current.autoWarpFallback && warpUsable(deps)) {
                            val warp = probeWarp(videoId, fast = !deep)
                            checks += warp.check
                            warpYtDlpOk = warp.ytDlpOk
                        } else if (RelayGateway.isEnabled()) {
                            checks += probeRelay(videoId)
                        }
                    } else if (deep && RelayGateway.isEnabled()) {
                        checks += probeRelay(videoId)
                    }
                } else if (deep) {
                    // Пользователь нажал «Проверить все способы» — показываем резервы.
                    checks += probeYtDlp(videoId)
                    if (RelayGateway.isEnabled()) checks += probeRelay(videoId)
                }
            }

            var warpConnected = deps.warp.status.value.state == WarpState.CONNECTED
            var warpLeftOn = warpConnected
            if (deep && checks.none { it.kind == TransportKind.WARP }) {
                checks += TransportCheck(
                    kind = TransportKind.WARP,
                    ok = warpConnected,
                    latencyMs = 0,
                    detail = if (warpConnected) {
                        "уже подключён (не требуется)"
                    } else {
                        "не требуется — есть рабочий способ"
                    },
                )
            }

            val best = checks.firstOrNull { it.ok }?.kind
            val result = StreamingAudit(
                checks = checks,
                best = best,
                network = networkLabel(),
                warpConnected = warpConnected,
                warpLeftOn = warpLeftOn,
                deep = deep,
                warpYtDlpOk = warpYtDlpOk,
            )
            _audit.value = result
            _message.value = result.summaryLine()
            Log.i(TAG, "audit: ${result.detailsText().replace('\n', ' ')}")
            result
        } finally {
            _running.value = false
        }
    }

    /** Убедиться, что перед воспроизведением поднят нужный транспорт (WARP). */
    suspend fun prepareForPlayback() {
        val deps = deps ?: return
        if (!deps.settings.current.autoTransport) {
            if (effectiveChain().firstOrNull() == TransportKind.WARP) ensureWarp()
            return
        }
        // Первый трек после установки/смены сети: быстрая проверка решает,
        // поднимать ли WARP до первого запроса. Ограничиваем ожидание.
        if (_audit.value == null || isStale()) {
            withTimeoutOrNull(28_000) { runAudit(deep = false) }
        }
        if (effectiveChain().firstOrNull() == TransportKind.WARP) ensureWarp()
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
        RelayGateway.clear()

        // На сети с DPI-блокировкой YouTube прямой путь и шлюзы заведомо
        // мертвы — начинаем с WARP, чтобы восстановление заняло секунды.
        val dpiBlocked = !probeApiReachable()
        val order = when (deps.settings.current.streamSource) {
            app.sonora.core.settings.StreamSource.INNERTUBE ->
                listOf(TransportKind.DIRECT, TransportKind.WARP)
            app.sonora.core.settings.StreamSource.RELAY ->
                listOf(TransportKind.RELAY, TransportKind.WARP)
            app.sonora.core.settings.StreamSource.YTDLP ->
                listOf(TransportKind.YTDLP, TransportKind.WARP, TransportKind.RELAY)
            app.sonora.core.settings.StreamSource.AUTO -> if (
                dpiBlocked && deps.settings.current.autoWarpFallback
            ) {
                listOf(
                    TransportKind.WARP,
                    TransportKind.YTDLP,
                    TransportKind.DIRECT,
                    TransportKind.RELAY,
                )
            } else {
                listOf(
                    TransportKind.YTDLP,
                    TransportKind.RELAY,
                    TransportKind.WARP,
                    TransportKind.DIRECT,
                )
            }
        }.filter { it != failed }

        val checks = ArrayList<TransportCheck>(4)
        _message.value = "Подбираем рабочий способ…"
        var warpYtDlpOk = false
        for (kind in order) {
            if (kind == TransportKind.WARP && !deps.settings.current.autoWarpFallback) continue
            val check = when (kind) {
                TransportKind.DIRECT -> probeDirect(videoId)
                TransportKind.YTDLP -> probeYtDlp(videoId)
                TransportKind.RELAY -> probeRelay(videoId)
                // Для восстановления WARP проверяем быстро: один конфиг,
                // туннель уже может быть подключён.
                TransportKind.WARP -> {
                    val outcome = probeWarp(videoId, fast = true)
                    warpYtDlpOk = outcome.ytDlpOk
                    outcome.check
                }
            }
            checks += check
            if (check.ok) {
                _audit.value = StreamingAudit(
                    checks = checks,
                    best = kind,
                    network = networkLabel(),
                    warpConnected = deps.warp.status.value.state == WarpState.CONNECTED,
                    warpLeftOn = deps.warp.status.value.state == WarpState.CONNECTED,
                    deep = false,
                    warpYtDlpOk = warpYtDlpOk,
                )
                Log.i(TAG, "recovered via ${kind.label} (${check.detail})")
                _message.value = "Переключено на ${kind.label}"
                return kind.label
            }
        }
        _message.value = "Рабочий способ не найден"
        null
    }

    /** Поднять WARP по требованию автопилота (если есть разрешение VPN). */
    suspend fun ensureWarp(): Boolean = withContext(Dispatchers.IO) {
        val deps = deps ?: return@withContext false
        if (deps.warp.status.value.state == WarpState.CONNECTED) return@withContext true
        if (deps.warp.externalVpnActive()) return@withContext false
        if (!deps.warp.hasVpnPermission()) {
            Log.i(TAG, "WARP нужен, но нет разрешения VPN")
            return@withContext false
        }
        if (deps.warp.configs().isEmpty() && deps.settings.current.warpAutoRegister) {
            withTimeoutOrNull(12_000) { registerFreshWarp() }
        }
        if (deps.warp.configs().isEmpty()) return@withContext false

        preselectBestWarpConfig(deps)
        val started = deps.warp.start()
        if (started.isSuccess && waitWarpState(deps, WarpState.CONNECTED, WARP_CONNECT_TIMEOUT_MS)) {
            return@withContext true
        }
        // Встроенный конфиг мог устареть (нет handshake) — свежая регистрация.
        if (deps.settings.current.warpAutoRegister) {
            Log.i(TAG, "WARP не поднялся, пробуем свежую регистрацию")
            deps.warp.stop()
            delay(300)
            if (withTimeoutOrNull(12_000) { registerFreshWarp() } == true) {
                deps.warp.start()
                if (waitWarpState(deps, WarpState.CONNECTED, WARP_CONNECT_TIMEOUT_MS)) {
                    return@withContext true
                }
            }
        }
        false
    }

    /**
     * Регистрирует свежий WARP-профиль и импортирует его. Так решается
     * проблема устаревших встроенных конфигов — на реальной сети РФ свежий
     * профиль поднимается за ~5 секунд, а старые висят «нет handshake».
     */
    suspend fun registerFreshWarp(): Boolean = withContext(Dispatchers.IO) {
        val deps = deps ?: return@withContext false
        val registration = app.sonora.net.warp.WarpAutoRegistrar.register() ?: run {
            Log.w(TAG, "авторегистрация WARP не удалась (нет сети/API)")
            return@withContext false
        }
        deps.warp.removeConfigsByNamePrefix(app.sonora.net.warp.WarpAutoRegistrar.AUTO_CONFIG_PREFIX)
        val imported = deps.warp.importConfigText(
            registration.configText,
            app.sonora.net.warp.WarpAutoRegistrar.AUTO_CONFIG_NAME,
        )
        if (imported.isSuccess) {
            deps.settings.update { it.copy(warpAutoConfigAtMs = System.currentTimeMillis()) }
            Log.i(TAG, "свежий WARP-профиль импортирован: ${registration.endpoint}")
            true
        } else {
            Log.w(TAG, "импорт WARP-профиля не удался: ${imported.exceptionOrNull()?.message}")
            false
        }
    }

    fun needsVpnPermission(): Boolean = deps?.warp?.hasVpnPermission() == false

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

    private suspend fun probeRelay(videoId: String): TransportCheck {
        val started = SystemClock.elapsedRealtime()
        if (!RelayGateway.isEnabled()) {
            return TransportCheck(TransportKind.RELAY, false, 0, "выключен в настройках")
        }
        val stream = runCatching {
            withTimeoutOrNull(RELAY_TIMEOUT_MS) { RelayGateway.resolve(videoId, null) }
        }.getOrNull()
        val latency = SystemClock.elapsedRealtime() - started
        return if (stream != null) {
            TransportCheck(
                kind = TransportKind.RELAY,
                ok = true,
                latencyMs = latency,
                detail = "OK · ${stream.instance}",
                bitrateKbps = stream.bitrate / 1000,
            )
        } else {
            TransportCheck(TransportKind.RELAY, false, latency, "зеркала не отдали звук")
        }
    }

    private data class WarpProbeOutcome(
        val check: TransportCheck,
        val ytDlpOk: Boolean = false,
    )

    /**
     * Собственный WARP-способ: перебираем конфиги, для каждого поднимаем
     * туннель и проверяем именно воспроизведение (а не только handshake).
     * Если «голый» InnerTube через WARP не играет, пробуем yt-dlp — по
     * замерам из РФ через WARP работают клиенты tv_simply/web_safari/default.
     * Лучший конфиг запоминается, туннель остаётся включённым, если WARP —
     * единственный рабочий путь.
     */
    private suspend fun probeWarp(videoId: String, fast: Boolean = false): WarpProbeOutcome {
        fun failed(message: String, latency: Long = 0L) = WarpProbeOutcome(
            TransportCheck(TransportKind.WARP, false, latency, message),
        )
        val deps = deps ?: return failed("не инициализирован")
        if (deps.warp.configs().isEmpty() && deps.settings.current.warpAutoRegister) {
            withTimeoutOrNull(12_000) { registerFreshWarp() }
        }
        if (deps.warp.configs().isEmpty()) return failed("нет WARP-конфигов")
        if (deps.warp.externalVpnActive()) return failed("активен другой VPN")
        if (!deps.warp.hasVpnPermission()) return failed("нужно разрешение VPN")

        return withTimeoutOrNull(WARP_CHECK_TIMEOUT_MS) {
            val started = SystemClock.elapsedRealtime()
            // Приоритет: конфиг, который уже поднимал handshake → зеркальный
            // endpoint (не engage.cloudflareclient.com:2408, он в РФ часто
            // блокируется) → текущий выбранный.
            preselectBestWarpConfig(deps)
            val entries = deps.warp.configs()
            val selectedFirst = entries.sortedByDescending { it.selected }
                .take(if (fast) 1 else 2)
            val previousSelected = entries.firstOrNull { it.selected }
            val wasConnected = deps.warp.status.value.state == WarpState.CONNECTED
            var workingConfig: String? = null
            var workingDetail = ""
            var workingYtDlp = false

            suspend fun checkCurrent(): Boolean {
                // Через WARP прямой InnerTube часто бесполезен (бот-гейт),
                // поэтому проверяем его с коротким бюджетом, затем yt-dlp.
                val direct = withTimeoutOrNull(8_000) { probeDirect(videoId) }
                    ?: TransportCheck(TransportKind.DIRECT, false, 8_000, "таймаут")
                if (direct.ok) {
                    workingDetail = direct.detail
                    workingYtDlp = false
                    return true
                }
                val ytDlp = probeYtDlp(videoId, timeoutMs = WARP_YTDLP_TIMEOUT_MS)
                if (ytDlp.ok) {
                    workingDetail = "yt-dlp · ${ytDlp.detail}"
                    workingYtDlp = true
                    return true
                }
                workingDetail = "туннель есть, но нет звука (${direct.detail.take(80)})"
                return false
            }

            // Если туннель уже поднят — сначала проверяем его, без перезапуска.
            if (wasConnected && checkCurrent()) {
                // Автоперебор мог поднять не тот конфиг, что выбран: берём
                // последний испробованный (он и дал handshake).
                workingConfig = deps.warp.status.value.triedConfigIds.lastOrNull()
                    ?: deps.warp.configs().firstOrNull { entry ->
                        entry.endpoint != null &&
                            entry.endpoint == deps.warp.status.value.endpoint
                    }?.id
                    ?: previousSelected?.id
            }

            suspend fun tryConfig(entryId: String?): Boolean {
                if (entryId != null) deps.warp.selectConfig(entryId)
                val startedTunnel = deps.warp.start()
                if (startedTunnel.isFailure) {
                    Log.w(TAG, "warp start failed: ${startedTunnel.exceptionOrNull()?.message}")
                    return false
                }
                if (!waitWarpState(deps, WarpState.CONNECTED, WARP_CONNECT_TIMEOUT_MS)) {
                    deps.warp.stop()
                    delay(300)
                    return false
                }
                if (checkCurrent()) return true
                deps.warp.stop()
                delay(400)
                return false
            }

            if (workingConfig == null) {
                for (entry in selectedFirst) {
                    if (tryConfig(entry.id)) {
                        // Автоперебор мог уйти на другой конфиг: берём последний
                        // фактически испробованный (он и поднял handshake).
                        workingConfig = deps.warp.status.value.triedConfigIds.lastOrNull()
                            ?: entry.id
                        break
                    }
                }
            }

            // Встроенные конфиги могли устареть (на реальной сети РФ: «нет
            // handshake за 12 с»). Регистрируем свежий WARP-профиль и проверяем.
            if (workingConfig == null &&
                deps.settings.current.warpAutoRegister &&
                withTimeoutOrNull(12_000) { registerFreshWarp() } == true
            ) {
                val fresh = deps.warp.configs().firstOrNull { it.selected }
                if (fresh != null && tryConfig(fresh.id)) {
                    workingConfig = fresh.id
                }
            }

            if (workingConfig != null) {
                deps.warp.selectConfig(workingConfig)
                // Запоминаем рабочий конфиг: следующий запуск подключится сразу.
                deps.settings.update { it.copy(warpLastGoodConfigId = workingConfig) }
                val keepOn = deps.settings.current.autoWarpFallback
                if (keepOn) {
                    if (deps.warp.status.value.state != WarpState.CONNECTED) {
                        deps.warp.start()
                        waitWarpState(deps, WarpState.CONNECTED, WARP_CONNECT_TIMEOUT_MS)
                    }
                    // Помечаем WARP как «нужный», чтобы keep-alive его поддерживал.
                    deps.settings.update { it.copy(warpUserEnabled = true) }
                } else {
                    deps.warp.stop()
                }
                WarpProbeOutcome(
                    check = TransportCheck(
                        kind = TransportKind.WARP,
                        ok = true,
                        latencyMs = SystemClock.elapsedRealtime() - started,
                        detail = "OK · $workingDetail",
                    ),
                    ytDlpOk = workingYtDlp,
                )
            } else {
                previousSelected?.let { deps.warp.selectConfig(it.id) }
                if (wasConnected) {
                    deps.warp.start()
                    waitWarpState(deps, WarpState.CONNECTED, WARP_CONNECT_TIMEOUT_MS)
                } else {
                    deps.warp.stop()
                }
                WarpProbeOutcome(
                    TransportCheck(
                        kind = TransportKind.WARP,
                        ok = false,
                        latencyMs = SystemClock.elapsedRealtime() - started,
                        detail = "WARP поднимается, но YouTube не отдаёт звук",
                    ),
                )
            }
        } ?: failed("таймаут WARP", WARP_CHECK_TIMEOUT_MS)
    }
    /**
     * Выбирает конфиг для следующего подключения: сначала тот, что уже
     * поднимал handshake, затем зеркальный endpoint (не
     * engage.cloudflareclient.com:2408 — в РФ он часто блокируется),
     * затем текущий выбранный.
     */
    private fun preselectBestWarpConfig(deps: Deps) {
        val entries = deps.warp.configs()
        if (entries.isEmpty()) return
        val lastGood = deps.settings.current.warpLastGoodConfigId
        val candidate = entries.firstOrNull { it.id == lastGood }
            ?: entries.firstOrNull { it.selected && it.endpoint != ENGAGE_ENDPOINT }
            ?: entries.firstOrNull { it.endpoint != null && it.endpoint != ENGAGE_ENDPOINT }
            ?: entries.firstOrNull { it.selected }
            ?: entries.first()
        if (!candidate.selected) {
            deps.warp.selectConfig(candidate.id)
        }
    }

    private suspend fun waitWarpState(
        deps: Deps,
        target: WarpState,
        timeoutMs: Long,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = deps.warp.status.value.state
            if (state == target) return true
            if (state == WarpState.ERROR || state == WarpState.NOT_CONFIGURED) return false
            delay(200)
        }
        return deps.warp.status.value.state == target
    }
}