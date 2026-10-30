package dev.nohit.core

import android.content.Context
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.json.JSONArray
import org.json.JSONObject
import javax.net.SocketFactory
import java.util.concurrent.TimeUnit

/**
 * Фасад модуля.
 *
 * Типичная интеграция в приложение:
 *
 *   // Application.onCreate()
 *   NoHiT.init(this)
 *
 *   // Там, где создаётся OkHttp:
 *   val client = NoHiT.client()
 *
 *   // Однократно (в фоне) — подобрать рабочую стратегию:
 *   val report = AutoTuner.tune(context) { line -> Log.d("NoHiT", line) }
 */
object NoHiT {

    @Volatile
    private var config: NoHiTConfig = NoHiTConfig()

    @Volatile
    private var client: OkHttpClient? = null

    @Volatile
    private var plain: OkHttpClient? = null

    /** Активный резолвер: нужен, чтобы чистить кэш при смене сети (CORE-3). */
    @Volatile
    private var resolver: NohitDns? = null

    /** Подписка на смену сети; `null` — не удалось/не нужно. */
    @Volatile
    private var networkWatcher: NetworkChangeWatcher? = null

    private val lock = Any()

    fun init(context: Context) {
        val app = context.applicationContext
        val loaded = GeoHideUpdater.applyTo(ConfigStore.load(app), app)
        // Миграция старых конфигов: чистые TCP-чанки (S1–S5 из 0.1) на современных
        // ТСПУ бесполезны — они собирают поток. Переводим на SNI/TLS-стратегию
        // по умолчанию; иначе пользователь останется с нерабочим обходом.
        val normalized = if (
            loaded.split.chunks.isNotEmpty() &&
            loaded.split.sniSplit == SniMode.NONE &&
            loaded.split.tlsRecordChunk == 0
        ) {
            loaded.copy(split = NoHiTConfig().split)
        } else {
            loaded
        }
        install(app, normalized)
        if (normalized != loaded) ConfigStore.save(app, normalized)
        startNetworkWatcher(app)
        GeoHideUpdater.refreshInBackground(app) { servers -> onGeoHideUpdated(app, servers) }
    }

    fun configure(context: Context, updated: NoHiTConfig) {
        val app = context.applicationContext
        install(app, updated)
        ConfigStore.save(app, updated)
    }

    fun currentConfig(): NoHiTConfig = config

    fun client(): OkHttpClient =
        client ?: throw IllegalStateException("Вызовите NoHiT.init(context) до NoHiT.client()")

    /**
     * Сброс DNS-кэша активного резолвера без изменения конфига.
     * Вызывается автоматически при смене сети (Wi-Fi ↔ мобильный) и может
     * пригодиться приложению вручную (например, после смены VPN).
     */
    fun resetDnsCache() {
        resolver?.clearCache()
        ResolvedHostTable.clear()
    }

    /**
     * Клиент без вмешательства в TLS (только DoH/GeoHide DNS).
     * Полезно для доменов, которые не нужно трогать: банки, госуслуги и т.п.
     */
    fun plainClient(): OkHttpClient = plain ?: buildClient(config.copy(split = SplitPlan.NONE)).also { plain = it }

    private fun install(context: Context, cfg: NoHiTConfig) {
        synchronized(lock) {
            val active = NohitDns(cfg.dns, cfg.timeoutsMs, cfg.hostPolicy, cfg.filterPrivateAddresses)
            resolver = active
            config = cfg
            plain = null
            client = buildClient(cfg, active)
        }
    }

    private fun startNetworkWatcher(context: Context) {
        if (networkWatcher != null) return
        val watcher = NetworkChangeWatcher(context) { resetDnsCache() }
        if (watcher.start()) networkWatcher = watcher
    }

    /** Список GeoHide обновился в фоне — пересобираем клиенты на актуальных серверах. */
    private fun onGeoHideUpdated(context: Context, servers: List<String>) {
        synchronized(lock) {
            val current = config
            val updated = GeoHideUpdater.applyServers(current, servers)
            if (updated != current) install(context, updated)
        }
    }

    /**
     * Собирает клиент с DoH/GeoHide DNS и фрагментацией ClientHello.
     * Всё, что делает приложение через этот клиент, получает новый резолвер и разбивку TLS.
     */
    fun buildClient(cfg: NoHiTConfig, dns: Dns? = null): OkHttpClient {
        val policy = cfg.hostPolicy
        val resolver = dns ?: NohitDns(cfg.dns, cfg.timeoutsMs, policy, cfg.filterPrivateAddresses)
        val builder = OkHttpClient.Builder()
            .dns(resolver)
            .socketFactory(FragSocketFactory.create(cfg.split, SocketFactory.getDefault(), policy))
            .connectTimeout(cfg.timeoutsMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(cfg.timeoutsMs.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(cfg.timeoutsMs.toLong(), TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT))

        // HTTP/2 мультиплексирует запросы в одном TLS-соединении;
        // для части DPI-стратегий выгоднее HTTP/1.1.
        if (!cfg.http2) {
            builder.protocols(listOf(Protocol.HTTP_1_1))
        }
        return builder.build()
    }
}

internal object ConfigStore {

    private const val PREFS = "nohit_settings"
    private const val KEY = "config_v1"

    fun save(context: Context, cfg: NoHiTConfig) {
        val json = JSONObject()
        json.put("dns", JSONArray().apply { cfg.dns.forEach { put(it.id) } })
        json.put("chunks", JSONArray().apply { cfg.split.chunks.forEach { put(it) } })
        json.put("delay", cfg.split.delayMs)
        json.put("head", cfg.split.headBytes)
        json.put("tlsrec", cfg.split.tlsRecordChunk)
        json.put("sni", cfg.split.sniSplit.name)
        json.put("snioff", cfg.split.sniOffset)
        json.put("nodelay", cfg.split.tcpNoDelay)
        json.put("http2", cfg.http2)
        json.put("timeout", cfg.timeoutsMs)
        json.put("skip", JSONArray().apply { cfg.skipHosts.forEach { put(it) } })
        json.put("frag", JSONArray().apply { cfg.fragmentHosts.forEach { put(it) } })
        // M-7: переключатель фильтра приватных адресов переживает перезапуск.
        json.put("filter_private", cfg.filterPrivateAddresses)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, json.toString()).apply()
    }

    fun load(context: Context): NoHiTConfig {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return NoHiTConfig()
        return try {
            val json = JSONObject(raw)
            val dnsIds = json.optJSONArray("dns")
            val dns = if (dnsIds != null) {
                (0 until dnsIds.length()).mapNotNull { index ->
                    val id = dnsIds.optString(index)
                    DnsUpstream.all.firstOrNull { it.id == id }
                }
            } else {
                NoHiTConfig().dns
            }
            val chunkArray = json.optJSONArray("chunks")
            val chunks = if (chunkArray != null) {
                (0 until chunkArray.length()).map { index -> chunkArray.optInt(index, 1) }
            } else {
                NoHiTConfig().split.chunks
            }
            NoHiTConfig(
                dns = dns.ifEmpty { NoHiTConfig().dns },
                split = SplitPlan(
                    chunks = chunks,
                    delayMs = json.optLong("delay", 0L),
                    tlsRecordChunk = json.optInt("tlsrec", NoHiTConfig().split.tlsRecordChunk),
                    sniSplit = runCatching {
                        SniMode.valueOf(json.optString("sni", SniMode.NONE.name))
                    }.getOrDefault(SniMode.NONE),
                    sniOffset = json.optInt("snioff", 0),
                    headBytes = json.optInt("head", NoHiTConfig().split.headBytes),
                    tcpNoDelay = json.optBoolean("nodelay", true)
                ),
                http2 = json.optBoolean("http2", false),
                timeoutsMs = json.optInt("timeout", 15_000),
                skipHosts = readHosts(json.optJSONArray("skip")),
                fragmentHosts = readHosts(json.optJSONArray("frag")),
                filterPrivateAddresses = json.optBoolean("filter_private", true)
            )
        } catch (_: Throwable) {
            NoHiTConfig()
        }
    }

    /** Читает список хостов из JSON-массива, отбрасывая мусор и пустые строки. */
    private fun readHosts(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val result = ArrayList<String>(array.length())
        for (index in 0 until array.length()) {
            val value = array.optString(index).trim()
            if (value.isNotEmpty()) result.add(value)
        }
        return result
    }
}
