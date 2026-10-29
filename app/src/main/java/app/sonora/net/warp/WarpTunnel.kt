package app.sonora.net.warp

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.VpnService
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.ContextCompat
import app.sonora.core.security.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

private const val MAX_CONFIG_BYTES = 128 * 1024

/** Общий lock для файлов конфигов: store создаётся и в UI, и в сервисе. */
private val WARP_STORE_LOCK = Any()

private const val WARP_LOG_TAG = "WarpTunnel"

/** Имя папки встроенных конфигов внутри APK (`assets/warp/`). */
private const val BUNDLED_ASSETS_DIR = "warp"

/**
 * Встроенный в APK WARP-конфиг.
 *
 * ВНИМАНИЕ: в `[Interface]` лежит приватный ключ — он попадает в APK. Это
 * осознанное решение пользователя для личного использования: ключ нельзя
 * логировать/передавать, в UI показываются только имя и endpoint.
 */
private data class BundledWarpConfig(
    val fileName: String,
    val displayName: String,
)

/** Ровно те конфиги, что лежат в `app/src/main/assets/warp/`. */
private val BUNDLED_WARP_CONFIGS = listOf(
    BundledWarpConfig("warp_09027.conf", "Встроенный WARP 09027"),
    BundledWarpConfig("warp_25786.conf", "Встроенный WARP 25786"),
    BundledWarpConfig("warp_65867.conf", "Встроенный WARP 65867"),
)

/** Состояние встроенного WARP-туннеля. */
enum class WarpState {
    /** Конфигурация не импортирована. */
    NOT_CONFIGURED,

    /** Конфигурации есть, туннель выключен. */
    DISCONNECTED,

    /** Идёт поднятие туннеля (возможно, с перебором конфигов). */
    CONNECTING,

    /** Туннель работает и handshake получен. */
    CONNECTED,

    /** Идёт остановка. */
    DISCONNECTING,

    /** Ошибка подключения (текст в [WarpStatus.error]). */
    ERROR,
}

/** Публичный снимок состояния WARP для UI. */
data class WarpStatus(
    val state: WarpState = WarpState.NOT_CONFIGURED,
    val error: String? = null,
    val endpoint: String? = null,
    val connectedSinceMs: Long? = null,
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val latestHandshakeMs: Long? = null,
    /** Номер текущей попытки при переборе конфигов (1..totalAttempts; 0 — не идёт). */
    val attempt: Int = 0,
    /** Сколько всего попыток запланировано в текущем цикле (0 — не идёт). */
    val totalAttempts: Int = 0,
    /** id конфигов, которые уже пробовали в текущем цикле (в порядке попыток). */
    val triedConfigIds: List<String> = emptyList(),
    /** Текущий режим автоперебора (true по умолчанию). */
    val autoRotate: Boolean = true,
)

/** Элемент списка импортированных WARP-конфигов (без приватного ключа). */
data class WarpConfigEntry(
    val id: String,
    val name: String,
    val endpoint: String?,
    val importedAtMs: Long,
    val selected: Boolean,
)

/** Безопасная сводка импортированного конфига — без приватного ключа. */
data class WarpConfigSummary(
    val endpoint: String?,
    val addresses: List<String>,
    val dnsServers: List<String>,
    val mtu: Int?,
    val peerCount: Int,
    val ignoredAmneziaKeys: List<String>,
    val configuredAtMs: Long,
)

/** Процесс-глобальное состояние: сервис и VM смотрят в один StateFlow. */
internal object WarpTunnelState {
    private val _status = MutableStateFlow(WarpStatus())
    val status: StateFlow<WarpStatus> = _status.asStateFlow()

    fun update(transform: (WarpStatus) -> WarpStatus) {
        _status.update(transform)
    }

    fun set(status: WarpStatus) {
        _status.value = status
    }
}

/**
 * Настройки WARP, хранящиеся в SharedPreferences пакета (`warp_settings`):
 * автоперебор конфигов. Приватных ключей здесь нет.
 */
internal class WarpPreferences(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** Автоперебор включён по умолчанию. */
    var autoRotate: Boolean
        get() = prefs.getBoolean(KEY_AUTO_ROTATE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_ROTATE, value).apply()
        }

    /**
     * Флаг «встроенные конфиги уже добавлялись» (`warp_bundled_seeded`), чтобы
     * не пересевать их при каждом запуске. [WarpTunnel.restoreBundled] его игнорирует.
     */
    fun isBundledSeeded(): Boolean = prefs.getBoolean(KEY_BUNDLED_SEEDED, false)

    fun setBundledSeeded(value: Boolean) {
        prefs.edit().putBoolean(KEY_BUNDLED_SEEDED, value).apply()
    }

    private companion object {
        const val PREFS_FILE = "warp_settings"
        const val KEY_AUTO_ROTATE = "auto_rotate"
        const val KEY_BUNDLED_SEEDED = "warp_bundled_seeded"
    }
}

/**
 * Встроенный Cloudflare WARP-туннель (WireGuard) только для трафика Sonora.
 *
 * Поддерживает до [MAX_CONFIGS] импортированных `.conf`; каждый текст лежит в
 * `filesDir/warp/configs/<id>.conf` БЕЗ строки `PrivateKey`, а ключ — в
 * [SecureStore] под ключом `warp.key.<id>`. Выбранный конфиг можно менять.
 *
 * Если включён автоперебор ([setAutoRotate], по умолчанию true), то при старте
 * сначала пробуется выбранный конфиг, затем остальные по порядку импорта; после
 * каждого поднятия туннеля ждём handshake [HANDSHAKE_TIMEOUT_MS] (WireGuard
 * поднимает handshake при первом пакете — сервис специально шлёт probe-пакет).
 * Неудачный конфиг гасится, идёт следующая попытка; цикл — не больше числа
 * конфигов. Успех = handshake получен ([WarpState.CONNECTED]).
 *
 * [externalVpnActive] позволяет UI не воевать с уже включённым системным VPN:
 * только собственный активный туннель не считается «внешним».
 *
 * ВНИМАНИЕ: встроенные конфиги из `assets/warp/` содержат приватные ключи
 * прямо в APK — это осознанное решение пользователя для личного использования.
 * Приватные ключи не логируются и не показываются в UI (только имя и endpoint).
 *
 * Публичный API для AppGraph/VM описан в docs/WARP.md.
 */
class WarpTunnel(
    context: Context,
    private val secure: SecureStore,
) {

    /** Удобный вариант, если SecureStore ещё не создан (в приложении он уже есть). */
    constructor(context: Context) : this(context, SecureStore(context))

    private val appContext: Context = context.applicationContext
    private val store = WarpConfigStore(appContext, secure)
    private val preferences = WarpPreferences(appContext)

    @Volatile
    private var cachedSummary: WarpConfigSummary? = null

    @Volatile
    private var cachedSummaryForId: String? = null

    /** Единый поток состояния для UI. */
    val status: StateFlow<WarpStatus> = WarpTunnelState.status

    init {
        // Встроенные WARP-конфиги: при первом запуске уже есть 3 готовых профиля.
        runCatching { ensureBundledConfigs() }
            .onFailure { error ->
                Log.w(WARP_LOG_TAG, "bundled WARP seeding failed: ${error.javaClass.simpleName}")
            }
        syncIdleState()
    }

    // ---------------------------------------------------------------- конфиги

    /** Есть ли выбранный конфиг (файл + ключ на месте). */
    fun hasConfig(): Boolean = store.hasConfig()

    /** Все импортированные конфиги (без ключей), выбранный помечен `selected`. */
    fun configs(): List<WarpConfigEntry> = store.entries()

    /** Сводка выбранного конфига (endpoint, адреса, DNS, MTU, AmneziaWG-ключи). */
    fun configSummary(): WarpConfigSummary? {
        val selectedId = store.selectedId()
        if (selectedId != null && selectedId == cachedSummaryForId) {
            cachedSummary?.let { return it }
        }
        val summary = store.summary() ?: return null
        cachedSummary = summary
        cachedSummaryForId = selectedId
        return summary
    }

    /**
     * Импорт одного `.conf` из SAF. Добавляет новый конфиг (или обновляет уже
     * импортированный с тем же содержимым) и делает его выбранным.
     */
    suspend fun importConfig(uri: Uri): Result<WarpConfigSummary> =
        importConfigInternal(uri)

    /**
     * Импорт конфига из текста: так Sonora ставит свежий WARP-профиль,
     * полученный автоматической регистрацией (без ручного файла).
     */
    suspend fun importConfigText(
        text: String,
        name: String,
    ): Result<WarpConfigSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val config = WarpConfig.parse(text)
            val entry = store.add(config, name)
            invalidateSummary()
            val current = WarpTunnelState.status.value
            if (current.state == WarpState.CONNECTED ||
                current.state == WarpState.CONNECTING ||
                current.state == WarpState.DISCONNECTING
            ) {
                WarpTunnelState.update { it.copy(autoRotate = preferences.autoRotate) }
            } else {
                WarpTunnelState.set(
                    WarpStatus(
                        state = WarpState.DISCONNECTED,
                        endpoint = entry.endpoint,
                        autoRotate = preferences.autoRotate,
                    ),
                )
            }
            store.summary() ?: config.toSummary(entry.importedAtMs)
        }
    }

    /** Удалить конфиги с именем, начинающимся с [prefix] (авторегистрация). */
    fun removeConfigsByNamePrefix(prefix: String) {
        store.entries()
            .filter { it.name.startsWith(prefix) }
            .forEach { removeConfig(it.id) }
    }

    /**
     * Пакетный импорт (SAF `OpenMultipleDocuments`). Возвращает по одному
     * `Result` на каждый URI в исходном порядке; последний успешный становится
     * выбранным.
     */
    suspend fun importConfigs(uris: List<Uri>): List<Result<WarpConfigSummary>> =
        uris.map { uri -> importConfigInternal(uri) }

    /**
     * Выбрать конфиг по id. Если туннель уже поднимается/работает — перезапустит
     * его с новым выбранным конфигом (с учётом автоперебора). false — нет такого id.
     */
    fun selectConfig(id: String): Boolean {
        if (!store.select(id)) return false
        invalidateSummary()
        val current = WarpTunnelState.status.value
        if (current.state == WarpState.CONNECTED || current.state == WarpState.CONNECTING) {
            requestServiceRestart()
        } else {
            publishSelectionIdle()
        }
        return true
    }

    /**
     * Удалить конфиг и его приватный ключ. Если удалён выбранный — выберется
     * следующий (или NOT_CONFIGURED); активный туннель перезапустится на новом
     * выбранном, либо остановится, если конфигов не осталось.
     */
    fun removeConfig(id: String) {
        val wasSelected = store.selectedId() == id
        if (!store.remove(id)) return
        invalidateSummary()
        val current = WarpTunnelState.status.value
        val hasAny = store.hasConfig()
        if (wasSelected && (current.state == WarpState.CONNECTED || current.state == WarpState.CONNECTING)) {
            if (hasAny) requestServiceRestart() else stop()
        } else if (current.state != WarpState.CONNECTED &&
            current.state != WarpState.CONNECTING &&
            current.state != WarpState.DISCONNECTING
        ) {
            publishSelectionIdle()
        } else {
            WarpTunnelState.update { it.copy(autoRotate = preferences.autoRotate) }
        }
    }

    /** Удалить ВСЕ конфиги и ключи (активный туннель выключается). */
    fun clearConfig() {
        val state = WarpTunnelState.status.value.state
        if (state == WarpState.CONNECTED ||
            state == WarpState.CONNECTING ||
            state == WarpState.DISCONNECTING
        ) {
            stop()
        }
        store.clearAll()
        invalidateSummary()
        WarpTunnelState.set(
            WarpStatus(state = WarpState.NOT_CONFIGURED, autoRotate = preferences.autoRotate),
        )
    }

    // ---------------------------------------------------------------- встроенные конфиги

    /**
     * Добавляет встроенные конфиги из `assets/warp/`, если они ещё не добавлялись,
     * и выбирает первый встроенный. Вызывается в [init] (синхронно, файлы ~3 КБ).
     *
     * ВНИМАНИЕ: приватные ключи встроенных конфигов попадают в APK — это осознанное
     * решение пользователя для личного использования; ключи не логируются.
     *
     * @return число реально добавленных конфигов (0, если уже сеяли).
     */
    @Synchronized
    fun ensureBundledConfigs(): Int {
        if (preferences.isBundledSeeded()) return 0
        val added = runCatching { addBundledConfigs(selectFirstBundled = true) }
            .onFailure { error ->
                Log.w(WARP_LOG_TAG, "bundled WARP seeding failed: ${error.javaClass.simpleName}")
            }
            .getOrDefault(0)
        preferences.setBundledSeeded(true)
        return added
    }

    /**
     * Повторно добавляет отсутствующие встроенные конфиги (например, после того
     * как пользователь их удалил). Флаг «уже сеяли» игнорируется; [WarpConfigStore.add]
     * дедуплицирует по id, поэтому существующие конфиги не дублируются.
     * Текущий выбранный конфиг сохраняется, если он ещё существует.
     *
     * @return число реально добавленных конфигов.
     */
    @Synchronized
    fun restoreBundled(): Int =
        runCatching { addBundledConfigs(selectFirstBundled = false) }
            .onFailure { error ->
                Log.w(WARP_LOG_TAG, "bundled WARP restore failed: ${error.javaClass.simpleName}")
            }
            .getOrDefault(0)

    /** Сколько встроенных конфигов реально присутствует в assets. */
    fun bundledConfigsCount(): Int = runCatching {
        BUNDLED_WARP_CONFIGS.count { descriptor ->
            runCatching {
                appContext.assets.open(assetPath(descriptor.fileName)).use { }
            }.isSuccess
        }
    }.getOrDefault(0)

    /**
     * Общий код [ensureBundledConfigs]/[restoreBundled]: читает assets, парсит их
     * через [WarpConfig.parse] и добавляет через store. Ошибки отдельных файлов
     * не фатальны; в лог пишем только имя файла и класс ошибки — без ключей.
     *
     * Переполнение [MAX_CONFIGS] просто пропускается: [WarpConfigStore.add]
     * бросает IOException, он ловится на уровне файла.
     */
    private fun addBundledConfigs(selectFirstBundled: Boolean): Int {
        val previousSelectedId = store.selectedId()
        var added = 0
        var firstProcessedId: String? = null
        for (descriptor in BUNDLED_WARP_CONFIGS) {
            try {
                val text = appContext.assets
                    .open(assetPath(descriptor.fileName))
                    .bufferedReader(Charsets.UTF_8)
                    .use { reader -> reader.readText() }
                val config = WarpConfig.parse(text)
                val sizeBefore = store.entries().size
                val entry = store.add(config, descriptor.displayName)
                val isNew = store.entries().size > sizeBefore
                if (isNew) added += 1
                if (firstProcessedId == null) firstProcessedId = entry.id
            } catch (error: Exception) {
                Log.w(
                    WARP_LOG_TAG,
                    "bundled config ${descriptor.fileName} skipped: ${error.javaClass.simpleName}",
                )
            }
        }
        when {
            // ensure: по контракту выбираем первый встроенный.
            selectFirstBundled -> firstProcessedId?.let { store.select(it) }
            // restore: не сбиваем выбор пользователя, если он ещё существует.
            previousSelectedId != null &&
                store.entries().any { entry -> entry.id == previousSelectedId } ->
                previousSelectedId?.let { store.select(it) }
            // restore на пустом списке: выбираем первый встроенный.
            previousSelectedId == null -> firstProcessedId?.let { store.select(it) }
        }
        invalidateSummary()
        publishSelectionIdle()
        return added
    }

    private fun assetPath(fileName: String): String = "$BUNDLED_ASSETS_DIR/$fileName"

    // ---------------------------------------------------------------- автоперебор

    /** Включить/выключить автоматический перебор конфигов при подключении. */
    fun setAutoRotate(enabled: Boolean) {
        preferences.autoRotate = enabled
        WarpTunnelState.update { it.copy(autoRotate = enabled) }
    }

    fun isAutoRotate(): Boolean = preferences.autoRotate

    // ---------------------------------------------------------------- внешний VPN

    /**
     * Активен ли на устройстве чужой VPN. Собственный туннель Sonora
     * (CONNECTING/CONNECTED/DISCONNECTING) внешним не считается.
     */
    fun externalVpnActive(): Boolean {
        if (isOurTunnelActive()) return false
        return findExternalVpnNetwork() != null
    }

    /**
     * Best-effort имя внешнего VPN — имя tun-интерфейса (например, "tun0");
     * null, если внешнего VPN нет или имя недоступно.
     */
    fun externalVpnName(): String? {
        if (isOurTunnelActive()) return null
        val network = findExternalVpnNetwork() ?: return null
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return null
        return runCatching { manager.getLinkProperties(network)?.interfaceName }.getOrNull()
    }

    // ---------------------------------------------------------------- запуск/стоп

    /** Intent системного запроса разрешения VPN; null — разрешение уже выдано. */
    fun vpnPermissionIntent(): Intent? = VpnService.prepare(appContext)

    fun hasVpnPermission(): Boolean = vpnPermissionIntent() == null

    /**
     * Как [start], но сначала проверяет внешний VPN: если он активен — НЕ
     * запускает туннель и НЕ трогает разрешение VPN, возвращает failure с
     * сообщением «На устройстве уже активен VPN/WARP — туннель Sonora не включён.».
     */
    fun startIfNoExternalVpn(): Result<Unit> {
        if (externalVpnActive()) {
            return Result.failure(
                IllegalStateException(
                    "На устройстве уже активен VPN/WARP — туннель Sonora не включён.",
                ),
            )
        }
        return start()
    }

    /**
     * Запуск туннеля.
     *
     * Требования: хотя бы один импортированный конфиг и уже выданное разрешение
     * VPN (`vpnPermissionIntent() == null`). Если разрешения нет — Result.failure,
     * UI должен сначала показать системный диалог через `vpnPermissionIntent()`.
     * При включённом автопереборе сервис сам переберёт конфиги; прогресс — в [status].
     */
    fun start(): Result<Unit> {
        val entries = store.entries()
        if (entries.isEmpty()) {
            return Result.failure(
                IllegalStateException("WARP-конфигурация не импортирована."),
            )
        }
        if (vpnPermissionIntent() != null) {
            return Result.failure(
                IllegalStateException(
                    "Нужно разрешение Android на VPN: запросите его через vpnPermissionIntent() " +
                        "и повторите start().",
                ),
            )
        }
        val autoRotate = preferences.autoRotate
        val selected = entries.firstOrNull { it.selected } ?: entries.first()
        val totalAttempts = if (autoRotate) entries.size else 1
        return try {
            WarpTunnelState.update {
                it.copy(
                    state = WarpState.CONNECTING,
                    error = null,
                    endpoint = selected.endpoint,
                    connectedSinceMs = null,
                    rxBytes = 0L,
                    txBytes = 0L,
                    latestHandshakeMs = null,
                    attempt = 0,
                    totalAttempts = totalAttempts,
                    triedConfigIds = emptyList(),
                    autoRotate = autoRotate,
                )
            }
            ContextCompat.startForegroundService(appContext, WarpVpnService.startIntent(appContext))
            Result.success(Unit)
        } catch (error: Exception) {
            val message = error.message ?: error.javaClass.simpleName
            WarpTunnelState.set(
                WarpStatus(
                    state = WarpState.ERROR,
                    endpoint = selected.endpoint,
                    error = "Не удалось запустить WARP-сервис: $message",
                    autoRotate = autoRotate,
                ),
            )
            Result.failure(error)
        }
    }

    /** Асинхронная остановка туннеля (результат — через [status]). */
    fun stop() {
        val current = WarpTunnelState.status.value.state
        if (current == WarpState.NOT_CONFIGURED) return
        WarpTunnelState.update { it.copy(state = WarpState.DISCONNECTING, error = null) }
        val sent = runCatching {
            appContext.startService(WarpVpnService.stopIntent(appContext))
        }.isSuccess
        if (!sent) {
            // Например, фоновая отправка сервису запрещена — тогда останавливаем напрямую.
            runCatching { appContext.stopService(Intent(appContext, WarpVpnService::class.java)) }
            publishSelectionIdle()
        }
    }

    // ---------------------------------------------------------------- внутреннее

    /**
     * Создать и выбрать новый конфиг. Тяжёлое чтение/разбор — на IO.
     * Уже существующий конфиг (тот же id) обновляет ключ/имя и становится выбранным.
     */
    private suspend fun importConfigInternal(uri: Uri): Result<WarpConfigSummary> =
        withContext(Dispatchers.IO) {
            try {
                val text = readConfigText(uri)
                val config = WarpConfig.parse(text)
                val name = displayName(uri) ?: defaultConfigName(config)
                val entry = store.add(config, name)
                invalidateSummary()
                val current = WarpTunnelState.status.value
                if (current.state == WarpState.CONNECTED ||
                    current.state == WarpState.CONNECTING ||
                    current.state == WarpState.DISCONNECTING
                ) {
                    // Импорт во время работы не рвёт текущий туннель:
                    // новый конфиг станет выбранным для следующего запуска.
                    WarpTunnelState.update { it.copy(autoRotate = preferences.autoRotate) }
                } else {
                    WarpTunnelState.set(
                        WarpStatus(
                            state = WarpState.DISCONNECTED,
                            endpoint = entry.endpoint,
                            autoRotate = preferences.autoRotate,
                        ),
                    )
                }
                val summary = store.summary() ?: config.toSummary(entry.importedAtMs)
                Result.success(summary)
            } catch (error: Exception) {
                Result.failure(error)
            }
        }

    private fun publishSelectionIdle() {
        val hasConfig = store.hasConfig()
        WarpTunnelState.set(
            WarpStatus(
                state = if (hasConfig) WarpState.DISCONNECTED else WarpState.NOT_CONFIGURED,
                endpoint = store.selectedEntry()?.endpoint,
                autoRotate = preferences.autoRotate,
            ),
        )
    }

    private fun syncIdleState() {
        val current = WarpTunnelState.status.value
        if (current.state == WarpState.CONNECTED ||
            current.state == WarpState.CONNECTING ||
            current.state == WarpState.DISCONNECTING
        ) {
            return
        }
        publishSelectionIdle()
    }

    private fun invalidateSummary() {
        cachedSummary = null
        cachedSummaryForId = null
    }

    private fun requestServiceRestart() {
        val sent = runCatching {
            appContext.startService(WarpVpnService.restartIntent(appContext))
        }.isSuccess
        if (!sent) {
            WarpTunnelState.update {
                it.copy(
                    state = WarpState.ERROR,
                    error = "Не удалось перезапустить WARP-сервис.",
                )
            }
        }
    }

    private fun isOurTunnelActive(): Boolean = when (WarpTunnelState.status.value.state) {
        WarpState.CONNECTED, WarpState.CONNECTING, WarpState.DISCONNECTING -> true
        else -> false
    }

    @Suppress("DEPRECATION")
    private fun findExternalVpnNetwork(): Network? {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return null
        return runCatching {
            manager.allNetworks.firstOrNull { network ->
                manager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        }.getOrNull()
    }

    private fun readConfigText(uri: Uri): String {
        val stream = appContext.contentResolver.openInputStream(uri)
            ?: throw IOException("Не удалось открыть выбранный файл.")
        return stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_CONFIG_BYTES) {
                    throw IOException("Файл больше 128 КБ — это не wg-конфиг.")
                }
                output.write(buffer, 0, read)
            }
            output.toString("UTF-8")
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        appContext.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index < 0) return@use null
                cursor.getString(index)
            }
            ?.substringBeforeLast('.')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(MAX_CONFIG_NAME_LENGTH)
    }.getOrNull()

    private fun defaultConfigName(config: WarpConfig): String =
        config.endpoint?.substringBefore(':')?.takeIf { it.isNotBlank() } ?: "WARP"

    companion object {
        /** Максимальное количество хранимых конфигов. */
        const val MAX_CONFIGS = 8

        /** Ожидание handshake после поднятия туннеля, мс. */
        const val HANDSHAKE_TIMEOUT_MS = 12_000L

        private const val MAX_CONFIG_NAME_LENGTH = 60
    }
}

// ---------------------------------------------------------------------------
// Хранилище конфигов
// ---------------------------------------------------------------------------

@Serializable
internal data class WarpIndexEntry(
    val id: String,
    val name: String,
    val importedAtMs: Long,
    val endpoint: String? = null,
)

@Serializable
internal data class WarpIndex(
    val selectedId: String? = null,
    val entries: List<WarpIndexEntry> = emptyList(),
)

/**
 * Диск + SecureStore для нескольких WARP-конфигов.
 *
 * Раскладка:
 *  - `filesDir/warp/configs/<id>.conf` — wg-quick текст без `PrivateKey`
 *    (первая строка — комментарий `# name: <имя>`);
 *  - `filesDir/warp/index.json` — порядок, имена, выбранный id;
 *  - `SecureStore["warp.key.<id>"]` — приватный ключ конкретного конфига.
 *
 * Старый одиночный `warp.conf` + `warp.private_key` автоматически мигрирует в
 * multi-config при первом обращении (один раз на процесс).
 */
internal class WarpConfigStore(
    context: Context,
    private val secure: SecureStore,
) {
    private val appContext = context.applicationContext
    private val warpDir = File(appContext.filesDir, WARP_DIR)
    private val configsDir = File(warpDir, CONFIGS_DIR)
    private val indexFile = File(warpDir, INDEX_FILE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile
    private var migrationDone = false

    fun entries(): List<WarpConfigEntry> = synchronized(WARP_STORE_LOCK) {
        val index = loadIndex()
        index.entries.map { entry ->
            WarpConfigEntry(
                id = entry.id,
                name = entry.name,
                endpoint = entry.endpoint,
                importedAtMs = entry.importedAtMs,
                selected = entry.id == index.selectedId,
            )
        }
    }

    fun selectedId(): String? = synchronized(WARP_STORE_LOCK) {
        loadIndex().selectedId
    }

    fun selectedEntry(): WarpConfigEntry? = synchronized(WARP_STORE_LOCK) {
        val index = loadIndex()
        val selected = index.selectedId ?: return@synchronized null
        val entry = index.entries.firstOrNull { it.id == selected } ?: return@synchronized null
        WarpConfigEntry(entry.id, entry.name, entry.endpoint, entry.importedAtMs, selected = true)
    }

    fun hasConfig(): Boolean = synchronized(WARP_STORE_LOCK) {
        val selected = loadIndex().selectedId ?: return@synchronized false
        val file = File(configsDir, "$selected.conf")
        file.isFile && !secure.get(keyFor(selected)).isNullOrBlank()
    }

    fun summary(): WarpConfigSummary? = synchronized(WARP_STORE_LOCK) {
        val index = loadIndex()
        val selected = index.selectedId ?: return@synchronized null
        val entry = index.entries.firstOrNull { it.id == selected } ?: return@synchronized null
        val config = readStoredConfig(selected) ?: return@synchronized null
        config.toSummary(entry.importedAtMs)
    }

    fun configuredAtMs(): Long = synchronized(WARP_STORE_LOCK) {
        val selected = loadIndex().selectedId ?: return@synchronized 0L
        val file = File(configsDir, "$selected.conf")
        if (file.isFile) file.lastModified() else 0L
    }

    /**
     * Добавить/обновить конфиг и сделать его выбранным.
     * @throws IOException при достижении [WarpTunnel.MAX_CONFIGS].
     */
    fun add(config: WarpConfig, name: String): WarpConfigEntry = synchronized(WARP_STORE_LOCK) {
        ensureReady()
        val index = loadIndex()
        val id = computeId(config)
        val existing = index.entries.firstOrNull { it.id == id }
        if (existing == null && index.entries.size >= WarpTunnel.MAX_CONFIGS) {
            throw IOException(
                "Достигнут лимит ${WarpTunnel.MAX_CONFIGS} WARP-конфигов. Удалите лишние.",
            )
        }
        val safeName = sanitizeName(name).ifBlank { defaultName(config) }
        writeConfigFile(id, safeName, config)
        secure.put(keyFor(id), config.privateKey)
        val entry = WarpIndexEntry(
            id = id,
            name = safeName,
            importedAtMs = System.currentTimeMillis(),
            endpoint = config.endpoint,
        )
        val entries = if (existing == null) {
            index.entries + entry
        } else {
            index.entries.map { if (it.id == id) entry else it }
        }
        saveIndex(WarpIndex(selectedId = id, entries = entries))
        WarpConfigEntry(entry.id, entry.name, entry.endpoint, entry.importedAtMs, selected = true)
    }

    fun load(id: String): WarpConfig? = synchronized(WARP_STORE_LOCK) {
        ensureReady()
        readStoredConfig(id)
    }

    fun select(id: String): Boolean = synchronized(WARP_STORE_LOCK) {
        ensureReady()
        val index = loadIndex()
        if (index.entries.none { it.id == id }) return@synchronized false
        if (index.selectedId == id) return@synchronized true
        saveIndex(index.copy(selectedId = id))
        true
    }

    fun remove(id: String): Boolean = synchronized(WARP_STORE_LOCK) {
        ensureReady()
        val index = loadIndex()
        if (index.entries.none { it.id == id }) return@synchronized false
        runCatching { File(configsDir, "$id.conf").delete() }
        runCatching { File(configsDir, "$id.conf.tmp").delete() }
        secure.remove(keyFor(id))
        val remaining = index.entries.filterNot { it.id == id }
        val newSelected = if (index.selectedId == id) {
            remaining.firstOrNull()?.id
        } else {
            index.selectedId?.takeIf { selected -> remaining.any { it.id == selected } }
                ?: remaining.firstOrNull()?.id
        }
        saveIndex(WarpIndex(selectedId = newSelected, entries = remaining))
        true
    }

    fun clearAll() = synchronized(WARP_STORE_LOCK) {
        ensureReady()
        val index = runCatching { loadIndex() }.getOrDefault(WarpIndex())
        index.entries.forEach { entry -> secure.remove(keyFor(entry.id)) }
        runCatching { configsDir.listFiles()?.forEach { file -> file.delete() } }
        runCatching { File(warpDir, LEGACY_CONFIG_FILE).delete() }
        runCatching { indexFile.delete() }
        secure.remove(LEGACY_PRIVATE_KEY)
    }

    // ------------------------------------------------------------ внутреннее

    private fun ensureReady() {
        if (!warpDir.exists()) warpDir.mkdirs()
        if (!configsDir.exists()) configsDir.mkdirs()
        if (!migrationDone) {
            migrationDone = true
            runCatching { migrateLegacy() }
        }
    }

    private fun loadIndex(): WarpIndex {
        ensureReady()
        val parsed = if (indexFile.isFile) {
            runCatching {
                json.decodeFromString<WarpIndex>(indexFile.readText(Charsets.UTF_8))
            }.getOrNull()
        } else {
            null
        }
        val base = parsed ?: rebuildIndexFromDisk()
        val existing = base.entries.filter { entry ->
            File(configsDir, "${entry.id}.conf").isFile
        }
        val selected = base.selectedId?.takeIf { id -> existing.any { it.id == id } }
            ?: existing.firstOrNull()?.id
        val normalized = WarpIndex(selectedId = selected, entries = existing)
        if (normalized != base) saveIndex(normalized)
        return normalized
    }

    private fun rebuildIndexFromDisk(): WarpIndex {
        val files = runCatching {
            configsDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".conf") }
                ?.sortedBy { it.lastModified() }
                .orEmpty()
        }.getOrDefault(emptyList())
        val entries = files.map { file ->
            val id = file.nameWithoutExtension
            val config = readStoredConfig(id)
            WarpIndexEntry(
                id = id,
                name = readHeaderName(file) ?: id,
                importedAtMs = file.lastModified(),
                endpoint = config?.endpoint,
            )
        }
        return WarpIndex(selectedId = entries.firstOrNull()?.id, entries = entries)
    }

    private fun readStoredConfig(id: String): WarpConfig? {
        val file = File(configsDir, "$id.conf")
        if (!file.isFile) return null
        val privateKey = secure.get(keyFor(id))?.takeIf { it.isNotBlank() } ?: return null
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        return runCatching { WarpConfig.parse(text, privateKeyOverride = privateKey) }.getOrNull()
    }

    private fun writeConfigFile(id: String, name: String, config: WarpConfig) {
        if (!configsDir.exists() && !configsDir.mkdirs()) {
            throw IOException("Не удалось создать папку WARP.")
        }
        val header = "# name: ${sanitizeName(name)}"
        val body = config.toWgQuickText(includePrivateKey = false)
        val temp = File(configsDir, "$id.conf.tmp")
        temp.writeText(header + "\n" + body, Charsets.UTF_8)
        temp.copyTo(File(configsDir, "$id.conf"), overwrite = true)
        temp.delete()
    }

    private fun saveIndex(index: WarpIndex) {
        if (!warpDir.exists() && !warpDir.mkdirs()) return
        runCatching {
            val temp = File(warpDir, "$INDEX_FILE.tmp")
            temp.writeText(json.encodeToString(index), Charsets.UTF_8)
            temp.copyTo(indexFile, overwrite = true)
            temp.delete()
        }
    }

    private fun readHeaderName(file: File): String? = runCatching {
        val line = file.bufferedReader(Charsets.UTF_8).use { it.readLine().orEmpty() }
        if (!line.startsWith("#")) return@runCatching null
        line.removePrefix("#").trim().removePrefix("name:").trim()
            .takeIf { it.isNotEmpty() }
    }.getOrNull()

    /**
     * Миграция старого одиночного `warp.conf` + `warp.private_key` в multi-config.
     * Ошибки игнорируются (старый файл остаётся, попытка повторится в новом процессе).
     */
    private fun migrateLegacy() {
        val legacy = File(warpDir, LEGACY_CONFIG_FILE)
        if (!legacy.isFile) return
        val privateKey = secure.get(LEGACY_PRIVATE_KEY)?.takeIf { it.isNotBlank() } ?: return
        val config = runCatching {
            WarpConfig.parse(legacy.readText(Charsets.UTF_8), privateKeyOverride = privateKey)
        }.getOrNull() ?: return
        val added = runCatching { add(config, "WARP") }.isSuccess
        if (added) {
            runCatching { legacy.delete() }
            secure.remove(LEGACY_PRIVATE_KEY)
        }
    }

    private fun computeId(config: WarpConfig): String {
        val material = config.privateKey + "\n" + config.toWgQuickText(includePrivateKey = false)
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(16)
        for (i in 0 until 8) {
            val value = digest[i].toInt() and 0xFF
            hex.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return hex.toString()
    }

    private fun keyFor(id: String): String = "$KEY_PREFIX$id"

    private fun sanitizeName(name: String): String =
        name.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim()
            .take(MAX_NAME_LENGTH)

    private fun defaultName(config: WarpConfig): String =
        config.endpoint?.substringBefore(':')?.takeIf { it.isNotBlank() } ?: "WARP"

    private companion object {
        const val WARP_DIR = "warp"
        const val CONFIGS_DIR = "configs"
        const val INDEX_FILE = "index.json"
        const val LEGACY_CONFIG_FILE = "warp.conf"
        const val KEY_PREFIX = "warp.key."
        const val LEGACY_PRIVATE_KEY = "warp.private_key"
        const val MAX_NAME_LENGTH = 60
        const val HEX = "0123456789abcdef"
    }
}

internal fun WarpConfig.toSummary(configuredAtMs: Long): WarpConfigSummary = WarpConfigSummary(
    endpoint = endpoint,
    addresses = addresses,
    dnsServers = dnsServers,
    mtu = mtu,
    peerCount = peers.size,
    ignoredAmneziaKeys = amneziaKeys,
    configuredAtMs = configuredAtMs,
)
