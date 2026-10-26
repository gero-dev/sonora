package app.sonora.net.warp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import app.sonora.MainActivity
import app.sonora.R
import app.sonora.core.security.SecureStore
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import java.io.StringReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Foreground-сервис-координатор WARP.
 *
 * Важно: сам VPN-интерфейс создаёт НЕ этот класс, а официальная библиотека
 * `com.wireguard.android:tunnel`: `GoBackend` внутри объявляет и запускает
 * собственный `com.wireguard.android.backend.GoBackend$VpnService` (он приходит
 * в манифест из AAR автоматически). Этот сервис:
 *  - держит процесс в foreground (уведомление, живучесть туннеля);
 *  - implements автоперебор: выбранный конфиг → остальные, пока не получен
 *    handshake или не кончились попытки ([WarpTunnel.HANDSHAKE_TIMEOUT_MS]);
 *  - строит `Config` с `IncludedApplications = app.sonora` (только трафик Sonora);
 *  - управляет `GoBackend.setState(UP/DOWN)` и публикует [WarpStatus];
 *  - раз в 2 секунды снимает статистику туннеля.
 *
 * Поэтому `WarpVpnService` — обычный `Service`, а не `android.net.VpnService`:
 * второго VPN-интерфейса в приложении быть не должно.
 */
class WarpVpnService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val backendMutex = Mutex()

    private var secure: SecureStore? = null
    private var store: WarpConfigStore? = null
    private var preferences: WarpPreferences? = null
    private var goBackend: GoBackend? = null
    private var tunnel: Tunnel? = null

    private var rotationJob: Job? = null
    private var statsJob: Job? = null

    @Volatile
    private var connected = false

    @Volatile
    private var stopping = false

    @Volatile
    private var lastEndpoint: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        tunnel = object : Tunnel {
            override fun getName(): String = TUNNEL_NAME

            override fun onStateChange(newState: Tunnel.State) {
                if (newState == Tunnel.State.DOWN && !stopping && connected) {
                    connected = false
                    statsJob?.cancel()
                    WarpTunnelState.update {
                        it.copy(
                            state = WarpState.DISCONNECTED,
                            connectedSinceMs = null,
                            latestHandshakeMs = null,
                            attempt = 0,
                            totalAttempts = 0,
                            triedConfigIds = emptyList(),
                        )
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> requestStop()
            ACTION_RESTART -> requestRestart()
            else -> startTunnel()
        }
        return Service.START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- запуск

    private fun startTunnel() {
        stopping = false
        // startForegroundService требует startForeground() в течение 5 секунд.
        startForeground(NOTIFICATION_ID, buildNotification(WarpState.CONNECTING, null))
        if (connected) {
            updateNotification(WarpState.CONNECTED, null)
            return
        }
        rotationJob?.cancel()
        rotationJob = scope.launch { runRotation() }
    }

    /**
     * Полный цикл: выбранный конфиг первым, далее — остальные по порядку импорта
     * (если автоперебор включён). Каждая попытка считается успешной только после
     * handshake; иначе туннель гасится и пробуется следующий конфиг.
     */
    private suspend fun runRotation() {
        val store = try {
            ensureStore()
        } catch (error: Throwable) {
            failTerminal(
                "Не удалось открыть хранилище WARP: ${error.message ?: error.javaClass.simpleName}",
            )
            return
        }
        val preferences = ensurePreferences()
        val autoRotate = preferences.autoRotate
        val entries = runCatching { store.entries() }.getOrDefault(emptyList())
        if (entries.isEmpty()) {
            failTerminal("WARP-конфигурация не импортирована. Импортируйте .conf заново.")
            return
        }

        val selected = entries.firstOrNull { it.selected } ?: entries.first()
        val ordered = buildList {
            add(selected)
            entries.forEach { entry -> if (entry.id != selected.id) add(entry) }
        }
        val attempts = if (autoRotate) ordered else listOf(selected)
        val total = attempts.size
        val tried = mutableListOf<String>()
        var lastAttemptError: String? = null

        WarpTunnelState.update {
            it.copy(
                state = WarpState.CONNECTING,
                error = null,
                attempt = 0,
                totalAttempts = total,
                triedConfigIds = emptyList(),
                autoRotate = autoRotate,
            )
        }

        val currentTunnel = tunnel ?: run {
            failTerminal("VPN-сервис не инициализирован.")
            return
        }
        val engine = try {
            ensureBackend()
        } catch (error: Throwable) {
            failTerminal(
                "Не удалось загрузить WireGuard: ${error.message ?: error.javaClass.simpleName}",
            )
            return
        }

        for ((index, entry) in attempts.withIndex()) {
            if (stopping) return
            tried += entry.id
            lastEndpoint = entry.endpoint
            WarpTunnelState.update {
                it.copy(
                    state = WarpState.CONNECTING,
                    error = null,
                    endpoint = entry.endpoint,
                    connectedSinceMs = null,
                    latestHandshakeMs = null,
                    attempt = index + 1,
                    totalAttempts = total,
                    triedConfigIds = tried.toList(),
                    autoRotate = autoRotate,
                )
            }
            updateNotification(WarpState.CONNECTING, attemptLabel(index + 1, total, entry))
            Log.i(TAG, "WARP attempt ${index + 1}/$total config=${entry.id} endpoint=${entry.endpoint}")

            val config = runCatching { store.load(entry.id) }.getOrNull()
            if (config == null) {
                lastAttemptError = "Не удалось прочитать конфиг «${entry.name}»"
                logAttemptFailure(entry, lastAttemptError)
                continue
            }

            // IncludedApplications заставляет GoBackend вызвать
            // Builder.addAllowedApplication(packageName): в VPN попадает только Sonora.
            val wgConfig = runCatching {
                Config.parse(
                    StringReader(
                        config.toWgQuickText(includedApplications = listOf(packageName)),
                    ).buffered(),
                )
            }.getOrElse { error ->
                lastAttemptError =
                    "Конфиг «${entry.name}» отклонён: ${error.message ?: error.javaClass.simpleName}"
                logAttemptFailure(entry, lastAttemptError)
                null
            } ?: continue

            val up = try {
                backendMutex.withLock {
                    engine.setState(currentTunnel, Tunnel.State.UP, wgConfig)
                }
                true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                lastAttemptError = describeError(error)
                logAttemptFailure(entry, lastAttemptError)
                false
            }
            if (!up) {
                teardown(engine, currentTunnel)
                delay(ATTEMPT_TEARDOWN_DELAY_MS)
                continue
            }

            val handshake = awaitHandshake(engine, currentTunnel)
            if (stopping) return
            if (handshake > 0L) {
                connected = true
                WarpTunnelState.update {
                    it.copy(
                        state = WarpState.CONNECTED,
                        error = null,
                        endpoint = entry.endpoint,
                        connectedSinceMs = System.currentTimeMillis(),
                        latestHandshakeMs = handshake,
                        rxBytes = 0L,
                        txBytes = 0L,
                        attempt = index + 1,
                        totalAttempts = total,
                        triedConfigIds = tried.toList(),
                        autoRotate = autoRotate,
                    )
                }
                updateNotification(WarpState.CONNECTED, null)
                Log.i(TAG, "WARP connected via config=${entry.id} endpoint=${entry.endpoint}")
                startStats()
                return
            }

            lastAttemptError = "Нет handshake за ${WarpTunnel.HANDSHAKE_TIMEOUT_MS / 1000} с"
            logAttemptFailure(entry, lastAttemptError)
            teardown(engine, currentTunnel)
            delay(ATTEMPT_TEARDOWN_DELAY_MS)
        }

        if (stopping) return
        val detail = lastAttemptError
        failTerminal("Ни один WARP-конфиг не подключился", detail)
    }

    private suspend fun teardown(engine: GoBackend, currentTunnel: Tunnel) {
        runCatching {
            backendMutex.withLock {
                engine.setState(currentTunnel, Tunnel.State.DOWN, null)
            }
        }
        connected = false
    }

    /**
     * Ждём первый handshake. WireGuard инициирует handshake при первом пакете,
     * поэтому пока ждём — периодически отправляем probe-соединение (1.1.1.1:80);
     * сам пакет идёт через только что поднятый туннель.
     */
    private suspend fun awaitHandshake(engine: GoBackend, currentTunnel: Tunnel): Long {
        val deadline = SystemClock.elapsedRealtime() + WarpTunnel.HANDSHAKE_TIMEOUT_MS
        var nextProbeAt = 0L
        while (!stopping && SystemClock.elapsedRealtime() < deadline) {
            val handshake = latestHandshake(engine, currentTunnel)
            if (handshake > 0L) return handshake
            val now = SystemClock.elapsedRealtime()
            if (now >= nextProbeAt) {
                triggerHandshakeProbe()
                nextProbeAt = now + PROBE_INTERVAL_MS
            }
            delay(POLL_INTERVAL_MS)
        }
        return latestHandshake(engine, currentTunnel)
    }

    private suspend fun latestHandshake(engine: GoBackend, currentTunnel: Tunnel): Long {
        val stats = runCatching {
            backendMutex.withLock { engine.getStatistics(currentTunnel) }
        }.getOrNull() ?: return 0L
        var latest = 0L
        for (key in stats.peers()) {
            val peerStats = stats.peer(key) ?: continue
            latest = maxOf(latest, peerStats.latestHandshakeEpochMillis())
        }
        return latest
    }

    private suspend fun triggerHandshakeProbe() {
        withContext(Dispatchers.IO) {
            runCatching {
                Socket().use { socket ->
                    socket.connect(
                        InetSocketAddress(PROBE_HOST, PROBE_PORT),
                        PROBE_CONNECT_TIMEOUT_MS,
                    )
                }
            }
        }
    }

    private fun attemptLabel(attempt: Int, total: Int, entry: WarpConfigEntry): String {
        val suffix = entry.endpoint?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        return "Попытка $attempt из $total$suffix"
    }

    private fun logAttemptFailure(entry: WarpConfigEntry, message: String) {
        Log.w(TAG, "WARP config=${entry.id} (${entry.name}) failed: $message")
    }

    // ---------------------------------------------------------------- остановка

    private fun requestStop() {
        stopping = true
        rotationJob?.cancel()
        statsJob?.cancel()
        WarpTunnelState.update { it.copy(state = WarpState.DISCONNECTING, error = null) }
        scope.launch {
            disconnectEngine()
            val hasConfig = runCatching { ensureStore().hasConfig() }.getOrDefault(false)
            val preferences = ensurePreferences()
            WarpTunnelState.update {
                it.copy(
                    state = if (hasConfig) WarpState.DISCONNECTED else WarpState.NOT_CONFIGURED,
                    error = null,
                    connectedSinceMs = null,
                    latestHandshakeMs = null,
                    rxBytes = 0L,
                    txBytes = 0L,
                    attempt = 0,
                    totalAttempts = 0,
                    triedConfigIds = emptyList(),
                    autoRotate = preferences.autoRotate,
                )
            }
            cancelForegroundAndNotification()
            stopSelf()
        }
    }

    /**
     * Перезапуск с новым выбранным конфигом (после selectConfig/removeConfig):
     * гасим текущий туннель и запускаем новый цикл перебора.
     */
    private fun requestRestart() {
        stopping = false
        rotationJob?.cancel()
        statsJob?.cancel()
        runCatching {
            startForeground(NOTIFICATION_ID, buildNotification(WarpState.CONNECTING, "Перезапуск…"))
        }
        scope.launch {
            disconnectEngine()
            startTunnel()
        }
    }

    private fun failTerminal(message: String, detail: String? = null) {
        if (stopping) return
        connected = false
        statsJob?.cancel()
        val preferences = ensurePreferences()
        WarpTunnelState.update {
            it.copy(
                state = WarpState.ERROR,
                error = message,
                connectedSinceMs = null,
                latestHandshakeMs = null,
                autoRotate = preferences.autoRotate,
            )
        }
        val notificationText = if (detail.isNullOrBlank()) message else "$message · $detail"
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(WarpState.ERROR, notificationText))
        }
        // Detach: уведомление об ошибке остаётся видимым после остановки сервиса.
        runCatching { stopForeground(Service.STOP_FOREGROUND_DETACH) }
        stopSelf()
    }

    private suspend fun disconnectEngine() {
        val engine = goBackend
        val currentTunnel = tunnel
        if (engine != null && currentTunnel != null) {
            runCatching {
                backendMutex.withLock {
                    engine.setState(currentTunnel, Tunnel.State.DOWN, null)
                }
            }
        }
        connected = false
    }

    private fun cancelForegroundAndNotification() {
        runCatching { stopForeground(Service.STOP_FOREGROUND_REMOVE) }
        runCatching {
            getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }

    // ---------------------------------------------------------------- статистика

    private fun startStats() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive && connected) {
                delay(STATS_INTERVAL_MS)
                val engine = goBackend ?: break
                val currentTunnel = tunnel ?: break
                val stats = runCatching {
                    backendMutex.withLock { engine.getStatistics(currentTunnel) }
                }.getOrNull() ?: continue
                var latestHandshake = 0L
                for (key in stats.peers()) {
                    val peerStats = stats.peer(key) ?: continue
                    latestHandshake = maxOf(latestHandshake, peerStats.latestHandshakeEpochMillis())
                }
                val rx = stats.totalRx()
                val tx = stats.totalTx()
                val autoRotate = ensurePreferences().autoRotate
                WarpTunnelState.update {
                    it.copy(
                        rxBytes = rx,
                        txBytes = tx,
                        latestHandshakeMs = latestHandshake.takeIf { handshake -> handshake > 0L },
                        autoRotate = autoRotate,
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onDestroy() {
        stopping = true
        rotationJob?.cancel()
        statsJob?.cancel()
        connected = false

        // Best-effort синхронная остановка: сервис мог быть убит системой,
        // а VPN-интерфейс обязан быть снят.
        val engine = goBackend
        val currentTunnel = tunnel
        if (engine != null && currentTunnel != null) {
            runCatching {
                runBlocking {
                    withTimeoutOrNull(2_000) {
                        backendMutex.withLock {
                            engine.setState(currentTunnel, Tunnel.State.DOWN, null)
                        }
                    }
                }
            }
        }

        if (WarpTunnelState.status.value.state != WarpState.ERROR) {
            val hasConfig = runCatching { store?.hasConfig() == true }.getOrDefault(false)
            val autoRotate = ensurePreferences().autoRotate
            WarpTunnelState.update {
                it.copy(
                    state = if (hasConfig) WarpState.DISCONNECTED else WarpState.NOT_CONFIGURED,
                    connectedSinceMs = null,
                    latestHandshakeMs = null,
                    attempt = 0,
                    totalAttempts = 0,
                    triedConfigIds = emptyList(),
                    autoRotate = autoRotate,
                )
            }
        }
        cancelForegroundAndNotification()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- зависимости

    @Synchronized
    private fun ensureSecure(): SecureStore =
        secure ?: SecureStore(applicationContext).also { secure = it }

    @Synchronized
    private fun ensureStore(): WarpConfigStore =
        store ?: WarpConfigStore(applicationContext, ensureSecure()).also { store = it }

    @Synchronized
    private fun ensurePreferences(): WarpPreferences =
        preferences ?: WarpPreferences(applicationContext).also { preferences = it }

    @Synchronized
    private fun ensureBackend(): GoBackend =
        goBackend ?: GoBackend(applicationContext).also { goBackend = it }

    // ---------------------------------------------------------------- уведомление

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "WARP-туннель",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Состояние встроенного WireGuard-туннеля Sonora"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun updateNotification(state: WarpState, message: String?) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(state, message))
        }
    }

    private fun buildNotification(state: WarpState, message: String?): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopAction = PendingIntent.getService(
            this,
            1,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when (state) {
            WarpState.CONNECTING -> "WARP: подключение…"
            WarpState.CONNECTED -> "WARP: подключён"
            WarpState.ERROR -> "WARP: ошибка"
            else -> "WARP"
        }
        val text = when {
            !message.isNullOrBlank() &&
                (state == WarpState.ERROR || state == WarpState.CONNECTING) -> message
            state == WarpState.CONNECTED && !lastEndpoint.isNullOrBlank() ->
                "Только трафик Sonora · $lastEndpoint"
            state == WarpState.CONNECTED -> "Только трафик Sonora"
            else -> "Туннель только для собственного трафика приложения"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(state == WarpState.CONNECTED || state == WarpState.CONNECTING)
            .setContentIntent(openApp)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Отключить",
                stopAction,
            )
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun describeError(error: Throwable): String = when (error) {
        is BackendException -> when (error.reason) {
            BackendException.Reason.VPN_NOT_AUTHORIZED ->
                "Android не выдал разрешение VPN. Разрешите его и включите WARP снова."
            BackendException.Reason.DNS_RESOLUTION_FAILURE ->
                "Не удалось разрешить адрес WARP-сервера " +
                    "(${error.format.firstOrNull() ?: "endpoint"}). Проверьте интернет."
            BackendException.Reason.TUN_CREATION_ERROR ->
                "Android не смог создать VPN-интерфейс — возможно, активен другой VPN."
            BackendException.Reason.UNABLE_TO_START_VPN ->
                "Не удалось запустить VPN-сервис WireGuard."
            BackendException.Reason.TUNNEL_MISSING_CONFIG ->
                "Внутренняя ошибка: конфигурация WireGuard не передана."
            BackendException.Reason.GO_ACTIVATION_ERROR_CODE ->
                "wireguard-go не поднял туннель (код ${error.format.firstOrNull()})."
            BackendException.Reason.WG_QUICK_CONFIG_ERROR_CODE ->
                "wireguard-go отклонил конфигурацию (код ${error.format.firstOrNull()})."
            BackendException.Reason.UNKNOWN_KERNEL_MODULE_NAME ->
                "Не удалось определить имя туннеля."
        }
        is UnsatisfiedLinkError ->
            "Нативная библиотека WireGuard не поддерживается на этом устройстве/ABI."
        else -> error.message ?: error.javaClass.simpleName
    }

    companion object {
        const val ACTION_START = "app.sonora.net.warp.action.START"
        const val ACTION_STOP = "app.sonora.net.warp.action.STOP"
        const val ACTION_RESTART = "app.sonora.net.warp.action.RESTART"

        private const val TAG = "WarpVpnService"
        private const val CHANNEL_ID = "warp_tunnel"
        private const val NOTIFICATION_ID = 0x5A17
        private const val TUNNEL_NAME = "sonora-warp"
        private const val STATS_INTERVAL_MS = 2_000L
        private const val POLL_INTERVAL_MS = 500L
        private const val PROBE_INTERVAL_MS = 2_000L
        private const val PROBE_HOST = "1.1.1.1"
        private const val PROBE_PORT = 80
        private const val PROBE_CONNECT_TIMEOUT_MS = 1_200
        private const val ATTEMPT_TEARDOWN_DELAY_MS = 400L

        fun startIntent(context: Context): Intent =
            Intent(context, WarpVpnService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, WarpVpnService::class.java).setAction(ACTION_STOP)

        fun restartIntent(context: Context): Intent =
            Intent(context, WarpVpnService::class.java).setAction(ACTION_RESTART)
    }
}
