package dev.nohit.core

/**
 * DNS-апстрим: либо DoH (JSON API), либо обычный UDP:53 на указанные IP.
 *
 * GeoHide — российский DNS-сервис, который отдаёт IP-адреса, доступные из РФ
 * (обход geo-ограничений и «санкционных» заглушек). Список IP взят из
 * официальной публикации проекта (Internet-Helper/GeoHideDNS, hosts-файл, 08.10.2026).
 * Проект не связан с NoHiT; используя его, вы доверяете оператору DNS.
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

        /** GeoHide DNS (РФ): обычный DNS на их IP. Свои DoH-адреса уточняйте на dns.geohide.ru. */
        val GEOHIDE = DnsUpstream(
            id = "geohide",
            name = "GeoHide DNS (RU)",
            udpServers = listOf(
                "193.233.112.67",
                "193.233.112.68",
                "45.155.204.190",
                "37.230.192.51",
                "46.8.158.6",
                "5.175.161.3"
            )
        )

        val all = listOf(GEOHIDE, CLOUDFLARE, GOOGLE, ADGUARD, SYSTEM)
    }
}

/**
 * План фрагментации ClientHello: первые [headBytes] байт пишутся кусками
 * по [chunks] байт с паузой [delayMs]. Это ломает DPI, который смотрит
 * только первый TCP-сегмент.
 */
data class SplitPlan(
    val chunks: List<Int>,
    val delayMs: Long = 0,
    val headBytes: Int = 256,
    val tcpNoDelay: Boolean = true
) {
    companion object {
        val NONE = SplitPlan(emptyList())
        val S1 = SplitPlan(listOf(1, 1, 1, 1, 2, 4))
        val S2 = SplitPlan(listOf(2, 2, 2, 2))
        val S3 = SplitPlan(listOf(1, 2, 4, 8), delayMs = 10)
        val S4 = SplitPlan(listOf(4, 4, 4, 4))
        val S5 = SplitPlan(listOf(1, 1, 1, 1, 1, 1, 1, 1), delayMs = 5)
        val all = listOf(S1, S2, S3, S4, S5)
    }
}

data class NoHiTConfig(
    val dns: List<DnsUpstream> = listOf(
        DnsUpstream.GEOHIDE,
        DnsUpstream.CLOUDFLARE,
        DnsUpstream.GOOGLE
    ),
    val split: SplitPlan = SplitPlan.S1,
    val http2: Boolean = false,
    val timeoutsMs: Int = 15_000,
    val testUrls: List<String> = listOf(
        "https://music.youtube.com/",
        "https://www.google.com/generate_204"
    )
)
