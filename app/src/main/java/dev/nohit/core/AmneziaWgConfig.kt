package dev.nohit.core

import java.security.MessageDigest

/**
 * Разбор конфигураций WireGuard / AmneziaWG (включая WARP-конфиги,
 * обёрнутые AmneziaWG: S1–S4, Jc/Jmin/Jmax, H1–H4, I1–I5).
 *
 * Это только разбор и валидация: сам туннель WireGuard/AmneziaWG —
 * IP-уровень и требует VpnService + userspace-стека (v0.3).
 *
 * Безопасность: приватный ключ хранится в поле, но НИКОГДА не попадает
 * в [toString], логи или отчёты — для идентификации есть [privateKeyFingerprint].
 */
class AmneziaWgConfig internal constructor(
    val addresses: List<String>,
    val dns: List<String>,
    val mtu: Int,
    val obfuscation: Obfuscation?,
    val peers: List<Peer>,
    private val privateKey: String,
    val unknownInterfaceKeys: Map<String, String>
) {

    data class Peer(
        val publicKey: String,
        val endpoint: String,
        val allowedIps: List<String>,
        val persistentKeepalive: Int?
    )

    data class Obfuscation(
        val s1: Int?,
        val s2: Int?,
        val s3: Int?,
        val s4: Int?,
        val jc: Int?,
        val jmin: Int?,
        val jmax: Int?,
        val h1: Long?,
        val h2: Long?,
        val h3: Long?,
        val h4: Long?,
        /** I1–I5: шаблоны «впрыскиваемых» пакетов AmneziaWG 1.5+. */
        val injectedPackets: List<String>,
        /**
         * Исходные строки H1–H4 из конфига — «a» или «a-b» (AmneziaWG 2.0+ разрешает диапазоны).
         *
         * UAPI-ключи `h1..h4` принимают именно строку (`UintRange.FromString`),
         * поэтому «сырые» значения нельзя терять при разборе: числа лежат в [h1]..[h4],
         * а диапазоны — только здесь. Внутренняя деталь модуля: снаружи для UAPI
         * есть [UapiBuilder.from].
         */
        internal val headerRanges: Map<String, String> = emptyMap()
    ) {
        val isObfuscated: Boolean
            get() = jc != null || s1 != null || s2 != null || s3 != null || s4 != null ||
                h1 != null || h2 != null || h3 != null || h4 != null ||
                injectedPackets.isNotEmpty() || headerRanges.isNotEmpty()
    }

    /** Короткий отпечаток ключа (sha256, 8 байт) — безопасно показывать в UI/логах. */
    val privateKeyFingerprint: String = fingerprint(privateKey)

    /**
     * Приватный ключ в hex — ровно то, что ждёт UAPI-ключ `private_key` у amneziawg-go
     * (32 байта → 64 hex-символа).
     *
     * `internal`: наружу приватный ключ не отдаём. Внешнему коду нужен только
     * [UapiBuilder.from], который сам возьмёт ключ внутри модуля.
     */
    internal fun privateKeyHex(): String = UapiBuilder.keyToHex(privateKey)

    /** Есть ли обфускация AmneziaWG (в отличие от «чистого» WireGuard/WARP). */
    val isAmnezia: Boolean
        get() = obfuscation?.isObfuscated == true

    override fun toString(): String =
        "AmneziaWgConfig(addresses=$addresses, dns=$dns, mtu=$mtu, " +
            "peers=${peers.map { it.endpoint }}, amnezia=$isAmnezia, key=$privateKeyFingerprint)"

    companion object {

        private const val DEFAULT_MTU = 1280

        /**
         * Разбирает текст .conf. Возвращает Result с понятной ошибкой,
         * если конфиг некорректен (не бросает исключений наружу).
         */
        fun parse(text: String): Result<AmneziaWgConfig> {
            val interfaceValues = LinkedHashMap<String, String>()
            val peers = ArrayList<LinkedHashMap<String, String>>()
            var section = ""
            var currentPeer: LinkedHashMap<String, String>? = null
            var lineNumber = 0

            for (rawLine in text.lineSequence()) {
                lineNumber++
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue

                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.substring(1, line.length - 1).trim().lowercase()
                    if (section == "peer") {
                        val peer = LinkedHashMap<String, String>()
                        peers.add(peer)
                        currentPeer = peer
                    } else {
                        currentPeer = null
                    }
                    if (section != "interface" && section != "peer") {
                        return Result.failure(IllegalArgumentException("Неизвестная секция [$section] (строка $lineNumber)"))
                    }
                    continue
                }

                val separator = line.indexOf('=')
                if (separator <= 0) {
                    return Result.failure(IllegalArgumentException("Некорректная строка $lineNumber: ожидается key = value"))
                }
                val key = line.substring(0, separator).trim().lowercase()
                val value = line.substring(separator + 1).trim()
                if (key.isEmpty()) {
                    return Result.failure(IllegalArgumentException("Пустой ключ в строке $lineNumber"))
                }
                when {
                    section == "interface" -> interfaceValues[key] = value
                    section == "peer" -> currentPeer?.put(key, value)
                    else -> return Result.failure(IllegalArgumentException("Строка $lineNumber вне секции"))
                }
            }

            val privateKey = interfaceValues["privatekey"]
                ?: return Result.failure(IllegalArgumentException("Нет PrivateKey в секции [Interface]"))
            if (!isValidKey(privateKey)) {
                return Result.failure(IllegalArgumentException("PrivateKey не похож на WireGuard-ключ (ожидается base64, 32 байта)"))
            }

            if (peers.isEmpty()) {
                return Result.failure(IllegalArgumentException("Нет ни одной секции [Peer]"))
            }

            val parsedPeers = ArrayList<Peer>()
            for (peer in peers) {
                val publicKey = peer["publickey"]
                    ?: return Result.failure(IllegalArgumentException("В [Peer] отсутствует PublicKey"))
                if (!isValidKey(publicKey)) {
                    return Result.failure(IllegalArgumentException("PublicKey в [Peer] некорректен"))
                }
                val endpoint = peer["endpoint"]
                    ?: return Result.failure(IllegalArgumentException("В [Peer] отсутствует Endpoint"))
                if (!isValidEndpoint(endpoint)) {
                    return Result.failure(IllegalArgumentException("Endpoint '$endpoint' должен быть host:port"))
                }
                val allowed = peer["allowedips"].orEmpty()
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                parsedPeers.add(
                    Peer(
                        publicKey = publicKey,
                        endpoint = endpoint,
                        allowedIps = allowed,
                        persistentKeepalive = peer["persistentkeepalive"]?.toIntOrNull()
                    )
                )
            }

            val addresses = interfaceValues["address"].orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (addresses.isEmpty()) {
                return Result.failure(IllegalArgumentException("Нет Address в секции [Interface]"))
            }

            val dns = interfaceValues["dns"].orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val mtu = interfaceValues["mtu"]?.toIntOrNull()?.takeIf { it in 576..1500 } ?: DEFAULT_MTU

            val obfuscation = parseObfuscation(interfaceValues, privateKey)

            val known = setOf(
                "privatekey", "address", "dns", "mtu",
                "s1", "s2", "s3", "s4", "jc", "jmin", "jmax", "h1", "h2", "h3", "h4",
                "i1", "i2", "i3", "i4", "i5"
            )
            val unknown = interfaceValues.filterKeys { it !in known }

            return Result.success(
                AmneziaWgConfig(
                    addresses = addresses,
                    dns = dns,
                    mtu = mtu,
                    obfuscation = obfuscation,
                    peers = parsedPeers,
                    privateKey = privateKey,
                    unknownInterfaceKeys = unknown
                )
            )
        }

        private fun parseObfuscation(values: Map<String, String>, privateKey: String): Obfuscation? {
            val s1 = values["s1"]?.toIntOrNull()
            val s2 = values["s2"]?.toIntOrNull()
            val s3 = values["s3"]?.toIntOrNull()
            val s4 = values["s4"]?.toIntOrNull()
            val jc = values["jc"]?.toIntOrNull()
            val jmin = values["jmin"]?.toIntOrNull()
            val jmax = values["jmax"]?.toIntOrNull()
            val h1 = values["h1"]?.toLongOrNull()
            val h2 = values["h2"]?.toLongOrNull()
            val h3 = values["h3"]?.toLongOrNull()
            val h4 = values["h4"]?.toLongOrNull()
            // H1–H4 в AmneziaWG 2.0+ могут быть диапазоном «a-b»: toLongOrNull это не съест,
            // поэтому сырые строки сохраняем отдельно (нужны для UAPI-ключей h1..h4).
            val headerRanges = listOf("h1", "h2", "h3", "h4")
                .mapNotNull { name ->
                    values[name]?.trim()?.takeIf { it.isNotEmpty() }?.let { name to it }
                }
                .toMap()
            val injected = listOf("i1", "i2", "i3", "i4", "i5")
                .mapNotNull { values[it] }
                .filter { it.isNotEmpty() }

            val hasAny = listOf(s1, s2, s3, s4, jc, jmin, jmax, h1, h2, h3, h4).any { it != null } ||
                headerRanges.isNotEmpty() || injected.isNotEmpty()
            if (!hasAny) return null
            return Obfuscation(s1, s2, s3, s4, jc, jmin, jmax, h1, h2, h3, h4, injected, headerRanges)
        }

        private fun isValidKey(value: String): Boolean {
            if (value.length != 44 || !value.endsWith("=")) return false
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            return value.dropLast(1).all { it in alphabet }
        }

        private fun isValidEndpoint(value: String): Boolean {
            val trimmed = value.trim()
            if (trimmed.startsWith("[")) {
                // [IPv6]:port
                val close = trimmed.indexOf(']')
                if (close <= 0) return false
                val port = trimmed.substring(close + 1).removePrefix(":")
                return port.toIntOrNull() in 1..65535
            }
            val lastColon = trimmed.lastIndexOf(':')
            if (lastColon <= 0 || lastColon == trimmed.length - 1) return false
            val host = trimmed.substring(0, lastColon)
            val port = trimmed.substring(lastColon + 1).toIntOrNull() ?: return false
            return host.isNotBlank() && port in 1..65535
        }

        private fun fingerprint(key: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}
