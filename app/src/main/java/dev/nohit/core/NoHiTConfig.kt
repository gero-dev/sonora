package dev.nohit.core

/**
 * DNS-апстрим: DoH (JSON API) и/или обычный UDP:53.
 *
 * GeoHide — российский DNS-сервис, отдающий IP, доступные из РФ.
 * Список IP взят из публичного hosts-файла проекта (Internet-Helper/GeoHideDNS, 08.10.2026).
 * NoHiT не связан с GeoHide; используя его, вы доверяете оператору DNS.
 */
data class DnsUpstream(
    val id: String,
    val name: String,
    val dohUrl: String? = null,
    val udpServers: List<String> = emptyList()
) {
    companion object {
        val SYSTEM = DnsUpstream("system", "Системный DNS")

        val CLOUDFLARE = DnsUpstream(
            id = "cloudflare",
            name = "Cloudflare DoH",
            dohUrl = "https://cloudflare-dns.com/dns-query"
        )

        val GOOGLE = DnsUpstream(
            id = "google",
            name = "Google DoH",
            dohUrl = "https://dns.google/resolve"
        )

        val ADGUARD = DnsUpstream(
            id = "adguard",
            name = "AdGuard DoH",
            dohUrl = "https://dns.adguard-dns.com/dns-query"
        )

        /**
         * GeoHide DNS (РФ): обычный DNS на их IP.
         *
         * Список — зашитый снимок [GeoHideList.SNAPSHOT_SERVERS] (версия
         * [GeoHideList.SNAPSHOT_VERSION]). Он же фолбэк: [GeoHideUpdater] раз в сутки
         * обновляет список из официального hosts-файла и подставляет актуальные
         * серверы через [GeoHideUpdater.applyTo]; сломанная загрузка оставляет снимок.
         */
        val GEOHIDE = DnsUpstream(
            id = "geohide",
            name = "GeoHide DNS (RU)",
            udpServers = GeoHideList.SNAPSHOT_SERVERS
        )

        /** GeoHide DoH: тот же GeoHide, но поверх HTTPS (не зависит от UDP:53). */
        val GEOHIDE_DOH = DnsUpstream(
            id = "geohide-doh",
            name = "GeoHide DoH (RU)",
            dohUrl = "https://geohide.ru/dns-query"
        )

        val all = listOf(GEOHIDE, GEOHIDE_DOH, CLOUDFLARE, GOOGLE, ADGUARD, SYSTEM)
    }
}

/**
 * Режим разреза ClientHello относительно SNI (server_name).
 *
 * ClientHello — это первое handshake-сообщение TLS. Если разбить *TLS-запись*
 * ровно по границам имени сервера, ни одна запись не содержит SNI целиком,
 * поэтому DPI не может прочитать имя даже при полной склейке TCP.
 */
enum class SniMode {
    /** Не разрезать по SNI (используются только [SplitPlan.chunks] / [SplitPlan.tlsRecordChunk]). */
    NONE,

    /** Граница — первый байт host_name. */
    START,

    /** Граница проходит через середину host_name (2 разреза, 3 записи). */
    MID,

    /** Граница — сразу после последнего байта host_name. */
    END;

    /** Суффикс для [SplitPlan.label]: "sni-start", "sni-mid", "sni-end" или "". */
    fun label(): String = when (this) {
        NONE -> ""
        START -> "sni-start"
        MID -> "sni-mid"
        END -> "sni-end"
    }

    companion object {
        val presets: List<SniMode> = listOf(START, MID, END)
    }
}

/**
 * Стратегия против DPI.
 *
 * [sniSplit] — резать ClientHello на TLS-записи по границам SNI (см. [SniMode]).
 * [sniOffset] — явный сдвиг разреза относительно host_name для [SniMode.MID]
 * (0 — автоматически, середина host_name).
 *
 * [tlsRecordChunk] — разбить ClientHello на несколько TLS-записей такого размера
 * (0 — не разбивать). Это работает даже если DPI собирает TCP обратно: он видит
 * несколько самостоятельных TLS-записей, а не один ClientHello.
 *
 * [chunks] — дополнительно резать поток на TCP-сегменты такого размера (циклически).
 * [headBytes] — сколько первых байт потока вообще подвергать обработке.
 */
data class SplitPlan(
    val chunks: List<Int> = emptyList(),
    val tlsRecordChunk: Int = 0,
    val sniSplit: SniMode = SniMode.NONE,
    val sniOffset: Int = 0,
    val delayMs: Long = 0,
    val headBytes: Int = 512,
    val tcpNoDelay: Boolean = true
) {
    /** Есть ли что применять: без этого плана сокет не оборачивается вообще. */
    fun isActive(): Boolean = chunks.isNotEmpty() || tlsRecordChunk > 0 || sniSplit != SniMode.NONE

    /** Человекочитаемое имя для логов и UI. */
    fun label(): String {        val sni = sniSplit.label()
        val record = if (tlsRecordChunk > 0) "tls$tlsRecordChunk" else ""
        val tcp = if (chunks.isEmpty()) "" else chunks.joinToString("-")
        val base = listOf(sni, record, tcp).filter { it.isNotEmpty() }.joinToString("+")
        val name = if (base.isEmpty()) "none" else base
        return if (delayMs > 0) "$name+${delayMs}ms" else name
    }

    companion object {
        val NONE = SplitPlan()

        // SNI-aware: разрезы проходят по границам host_name (главная стратегия v0.2).
        val SNI_MID = SplitPlan(sniSplit = SniMode.MID)
        val SNI_START = SplitPlan(sniSplit = SniMode.START)
        val SNI_END = SplitPlan(sniSplit = SniMode.END)
        val SNI_MID_TCP_2 = SplitPlan(chunks = listOf(2, 2), sniSplit = SniMode.MID)

        // Только TCP-сегментация (как в v0.1).
        val TCP_1 = SplitPlan(chunks = listOf(1, 1, 1, 1, 2, 4))
        val TCP_2 = SplitPlan(chunks = listOf(2, 2, 2, 2))
        val TCP_4 = SplitPlan(chunks = listOf(4, 4, 4, 4))

        // Only TLS-record fragmentation: DPI видит N корректных записей.
        val TLS_64 = SplitPlan(tlsRecordChunk = 64)
        val TLS_128 = SplitPlan(tlsRecordChunk = 128)
        val TLS_256 = SplitPlan(tlsRecordChunk = 256)

        // Комбинации.
        val TLS_128_TCP_2 = SplitPlan(chunks = listOf(2, 2), tlsRecordChunk = 128)
        val TLS_64_TCP_1 = SplitPlan(chunks = listOf(1, 1, 1), tlsRecordChunk = 64, delayMs = 2)

        val all = listOf(
            SNI_MID, SNI_START, SNI_END, SNI_MID_TCP_2,
            TLS_64, TLS_128, TLS_256,
            TLS_128_TCP_2, TLS_64_TCP_1,
            TCP_1, TCP_2, TCP_4
        )
    }
}

data class NoHiTConfig(
    val dns: List<DnsUpstream> = listOf(
        DnsUpstream.GEOHIDE,
        DnsUpstream.GEOHIDE_DOH,
        DnsUpstream.CLOUDFLARE,
        DnsUpstream.GOOGLE
    ),
    /**
     * По умолчанию — SNI-aware разрез с откатом на TLS-записи по 128 байт:
     * если SNI найден, режем по его границам; если нет (ECH/нестандартный клиент),
     * остаётся обычная фрагментация TLS-записей.
     */
    val split: SplitPlan = SplitPlan(tlsRecordChunk = 128, sniSplit = SniMode.MID),
    val http2: Boolean = false,
    val timeoutsMs: Int = 15_000,
    /**
     * Хосты, которым фрагментация не нужна и может мешать (банки, госуслуги,
     * внутренние сервисы). Шаблоны: `sberbank.ru`, `*.gosuslugi.ru`, `cdn.*`.
     * Совпадение — фрагментация выключается для этого домена (CORE-5).
     */
    val skipHosts: List<String> = emptyList(),
    /**
     * Если список не пуст — это whitelist: фрагментируем только совпавшие хосты,
     * остальные идут как есть. `skipHosts` всё равно имеет приоритет.
     */
    val fragmentHosts: List<String> = emptyList(),
    /**
     * Тестовые URL автоподбора. Первый используется для перебора стратегий
     * (быстро), победитель затем проверяется на всех трёх.
     */
    val testUrls: List<String> = listOf(
        "https://music.youtube.com/",
        "https://www.google.com/generate_204",
        "https://i.ytimg.com/generate_204"
    ),
    /**
     * M-7 (DNS-rebinding): отбрасывать непубличные адреса в ответах DoH/UDP
     * и в IP-литералах — loopback, link-local (`169.254.169.254` в том числе),
     * RFC1918 (`10/8`, `172.16/12`, `192.168/16`), CGNAT `100.64/10`,
     * multicast, unspecified, IPv6 ULA `fc00::/7`, `::1`, `fe80::/10` и др.
     *
     * По умолчанию **включено**. Выключать имеет смысл только осознанно:
     * например, если приложение обязано ходить на внутренние хосты корпоративной
     * сети, которые резолвятся через этот же DNS-апстрим (GeoHide в корп. сети
     * тоже может отдавать приватные адреса). При включённом фильтре резолв,
     * давший только приватные адреса, считается заблокированным и системный
     * DNS-фолбэк для него не выполняется.
     *
     * Параметр добавлен в конец data-класса, чтобы не сдвигать позиционные
     * аргументы существующих вызовов.
     */
    val filterPrivateAddresses: Boolean = true
) {
    /** Правила по доменам одним объектом (см. [HostPolicy]). */
    val hostPolicy: HostPolicy
        get() = HostPolicy(skipHosts = skipHosts, fragmentHosts = fragmentHosts)
}
