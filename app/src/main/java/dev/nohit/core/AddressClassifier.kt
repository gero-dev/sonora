package dev.nohit.core

import java.net.InetAddress

/**
 * Классификатор IP-адресов: «публичный» (маршрутизируемый в интернете) или
 * служебный/приватный.
 *
 * Нужен для защиты от DNS-rebinding (M-7): злонамеренный/скомпрометированный
 * DNS-апстрим может вернуть `127.0.0.1`, `169.254.169.254` (cloud metadata),
 * адрес роутера `192.168.1.1` или ULA `fd00::/8`, чтобы запросы приложения
 * ушли на внутренний сервис. Резолвер ([NohitDns]) по умолчанию отбрасывает
 * такие адреса; исключение — явно выключенный
 * [NoHiTConfig.filterPrivateAddresses].
 *
 * Всё здесь — чистые функции без сети и без Android, поэтому покрыто
 * юнит-тестами ([AddressClassifierTest]).
 *
 * Заблокированные диапазоны (RFC 6890 и смежные):
 *  * IPv4: `0.0.0.0/8`, `10.0.0.0/8`, `100.64.0.0/10` (CGNAT),
 *    `127.0.0.0/8` (loopback), `169.254.0.0/16` (link-local + metadata),
 *    `172.16.0.0/12`, `192.0.0.0/24`, `192.0.2.0/24` (TEST-NET-1),
 *    `192.88.99.0/24` (6to4 relay anycast), `192.168.0.0/16`,
 *    `198.18.0.0/15` (benchmark), `198.51.100.0/24` (TEST-NET-2),
 *    `203.0.113.0/24` (TEST-NET-3), `224.0.0.0/4` (multicast),
 *    `240.0.0.0/4` (reserved, включая broadcast `255.255.255.255`);
 *  * IPv6: `::/128` (unspecified), `::1/128` (loopback), `::/8` (прочее
 *    зарезервированное, включая IPv4-compatible), `100::/64` (discard-only),
 *    `2001:db8::/32` (documentation), `fc00::/7` (ULA), `fe80::/10`
 *    (link-local), `ff00::/8` (multicast). Адреса, встраивающие IPv4
 *    (`::ffff:0:0/96`, `64:ff9b::/96`, `2002::/16`), классифицируются по
 *    встроенному IPv4 — так `::ffff:127.0.0.1` тоже не считается публичным.
 */
internal object AddressClassifier {

    /** Публичный ли адрес. Неизвестная длина (не 4/16 байт) — не публичный. */
    fun isPublic(address: InetAddress): Boolean = isPublic(address.address)

    /** То же по сырым байтам адреса (4 — IPv4, 16 — IPv6). */
    fun isPublic(raw: ByteArray): Boolean = when (raw.size) {
        4 -> isPublicIpv4(raw)
        16 -> isPublicIpv6(raw)
        else -> false
    }

    /** Публичный ли IPv4 (4 байта, big-endian). */
    fun isPublicIpv4(raw: ByteArray): Boolean {
        if (raw.size != 4) return false
        val a = raw[0].toInt() and 0xFF
        val b = raw[1].toInt() and 0xFF
        val c = raw[2].toInt() and 0xFF
        return when {
            a == 0 -> false                                   // 0.0.0.0/8
            a == 10 -> false                                  // 10.0.0.0/8
            a == 100 && b in 64..127 -> false                 // 100.64.0.0/10 (CGNAT)
            a == 127 -> false                                 // 127.0.0.0/8 (loopback)
            a == 169 && b == 254 -> false                     // 169.254.0.0/16 (link-local/metadata)
            a == 172 && b in 16..31 -> false                  // 172.16.0.0/12
            a == 192 && b == 0 && (c == 0 || c == 2) -> false // 192.0.0.0/24, 192.0.2.0/24
            a == 192 && b == 88 && c == 99 -> false           // 192.88.99.0/24 (6to4 relay)
            a == 192 && b == 168 -> false                     // 192.168.0.0/16
            a == 198 && (b == 18 || b == 19) -> false         // 198.18.0.0/15 (benchmark)
            a == 198 && b == 51 && c == 100 -> false          // 198.51.100.0/24 (TEST-NET-2)
            a == 203 && b == 0 && c == 113 -> false           // 203.0.113.0/24 (TEST-NET-3)
            a >= 224 -> false                                  // 224.0.0.0/4 multicast + 240.0.0.0/4 reserved
            else -> true
        }
    }

    /** Публичный ли IPv6 (16 байт, big-endian). */
    fun isPublicIpv6(raw: ByteArray): Boolean {
        if (raw.size != 16) return false

        // IPv4-mapped ::ffff:a.b.c.d — судим по встроенному IPv4.
        if (isZeroRange(raw, 0, 10) && (raw[10].toInt() and 0xFF) == 0xFF && (raw[11].toInt() and 0xFF) == 0xFF) {
            return isPublicIpv4(raw.copyOfRange(12, 16))
        }
        // NAT64 well-known prefix 64:ff9b::/96 — тоже по встроенному IPv4.
        if ((raw[0].toInt() and 0xFF) == 0x00 && (raw[1].toInt() and 0xFF) == 0x64 &&
            (raw[2].toInt() and 0xFF) == 0xFF && (raw[3].toInt() and 0xFF) == 0x9B &&
            isZeroRange(raw, 4, 12)
        ) {
            return isPublicIpv4(raw.copyOfRange(12, 16))
        }
        // 6to4 2002:AABB:CCDD::/48 — встроенный IPv4 в байтах 2..5.
        if ((raw[0].toInt() and 0xFF) == 0x20 && (raw[1].toInt() and 0xFF) == 0x02) {
            return isPublicIpv4(raw.copyOfRange(2, 6))
        }
        // ::/8 — unspecified/loopback и прочее зарезервированное.
        if ((raw[0].toInt() and 0xFF) == 0x00) return false
        // 100::/64 (discard-only).
        if ((raw[0].toInt() and 0xFF) == 0x01 && isZeroRange(raw, 1, 8)) return false
        // 2001:db8::/32 (documentation).
        if ((raw[0].toInt() and 0xFF) == 0x20 && (raw[1].toInt() and 0xFF) == 0x01 &&
            (raw[2].toInt() and 0xFF) == 0x0D && (raw[3].toInt() and 0xFF) == 0xB8
        ) {
            return false
        }
        // fc00::/7 (unique local).
        if (((raw[0].toInt() and 0xFF) and 0xFE) == 0xFC) return false
        // fe80::/10 (link-local).
        if ((raw[0].toInt() and 0xFF) == 0xFE && ((raw[1].toInt() and 0xC0) == 0x80)) return false
        // ff00::/8 (multicast).
        if ((raw[0].toInt() and 0xFF) == 0xFF) return false
        return true
    }

    /**
     * Лежит ли IPv4-адрес [ip] (4 байта) в подсети [cidr] вида `1.2.3.0/24`.
     * Невалидный CIDR/адрес — `false` (fail-closed).
     */
    fun ipv4InCidr(ip: ByteArray, cidr: String): Boolean {
        if (ip.size != 4) return false
        val slash = cidr.indexOf('/')
        if (slash <= 0 || slash == cidr.length - 1) return false
        val network = parseIpv4(cidr.substring(0, slash)) ?: return false
        val prefix = cidr.substring(slash + 1).toIntOrNull() ?: return false
        if (prefix !in 0..32) return false
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        val address = ipv4ToInt(ip)
        return (address and mask) == (network and mask)
    }

    private fun ipv4ToInt(raw: ByteArray): Int =
        ((raw[0].toInt() and 0xFF) shl 24) or
            ((raw[1].toInt() and 0xFF) shl 16) or
            ((raw[2].toInt() and 0xFF) shl 8) or
            (raw[3].toInt() and 0xFF)

    private fun parseIpv4(text: String): Int? {
        val parts = text.trim().split('.')
        if (parts.size != 4) return null
        var value = 0
        for (part in parts) {
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            value = (value shl 8) or octet
        }
        return value
    }

    private fun isZeroRange(raw: ByteArray, from: Int, toExclusive: Int): Boolean {
        for (index in from until toExclusive) {
            if ((raw[index].toInt() and 0xFF) != 0) return false
        }
        return true
    }
}
