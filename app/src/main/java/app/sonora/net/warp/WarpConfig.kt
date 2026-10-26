package app.sonora.net.warp

/**
 * Ошибка разбора wg-quick-конфигурации WARP (сообщение пригодно для показа в UI).
 */
class WarpConfigException(message: String) : Exception(message)

/**
 * Разобранная конфигурация WireGuard/WARP из пользовательского `.conf`-файла.
 *
 * Поддерживаются поля:
 *  - [Interface]: PrivateKey, Address, DNS, MTU;
 *  - [Peer]: PublicKey, PresharedKey, AllowedIPs, Endpoint, PersistentKeepalive.
 *
 * Неизвестные ключи (включая AmneziaWG-параметры S1/S2/Jc/Jmin/Jmax/H1–H4/I1)
 * НЕ ломают импорт и складываются в [ignoredInterfaceKeys]/[ignoredPeerKeys]:
 * официальная библиотека WireGuard их не понимает, поэтому применяется только
 * «обычный» WireGuard-конфиг, а в UI можно честно показать предупреждение.
 *
 * Объект содержит приватный ключ. Не логируйте его и не сериализуйте целиком:
 * [toString] намеренно скрывает ключ.
 */
data class WarpConfig(
    val privateKey: String,
    val addresses: List<String>,
    val dnsServers: List<String>,
    val mtu: Int?,
    val peers: List<WarpPeer>,
    val ignoredInterfaceKeys: List<String> = emptyList(),
    val ignoredPeerKeys: List<String> = emptyList(),
    val ignoredSections: List<String> = emptyList(),
) {

    data class WarpPeer(
        val publicKey: String,
        val presharedKey: String? = null,
        val allowedIps: List<String> = emptyList(),
        val endpoint: String? = null,
        val persistentKeepalive: Int? = null,
    )

    /** Endpoint основного (первого) пира — для статуса и заметки. */
    val endpoint: String?
        get() = peers.firstOrNull()?.endpoint?.takeIf { it.isNotBlank() }

    /**
     * AmneziaWG-параметры, которые были в файле, но игнорируются: стоковый
     * WireGuard-бэкенд не умеет обфускацию трафика.
     */
    val amneziaKeys: List<String>
        get() {
            val known = AMNEZIA_KEY_NAMES
            return (ignoredInterfaceKeys + ignoredPeerKeys)
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.uppercase() in known }
                .distinct()
        }

    /**
     * Собирает валидный wg-quick-текст для `com.wireguard.config.Config.parse`.
     *
     * @param includePrivateKey false — для хранения на диске (ключ живёт в SecureStore).
     * @param includedApplications непустой список заставляет WireGuard Android
     *   установить `Builder.addAllowedApplication(...)`, то есть туннелировать
     *   только трафик перечисленных пакетов (в нашем случае — только Sonora).
     */
    fun toWgQuickText(
        includePrivateKey: Boolean = true,
        includedApplications: List<String> = emptyList(),
    ): String = buildString {
        appendLine("[Interface]")
        if (includePrivateKey) appendLine("PrivateKey = $privateKey")
        if (addresses.isNotEmpty()) appendLine("Address = ${addresses.joinToString(", ")}")
        if (dnsServers.isNotEmpty()) appendLine("DNS = ${dnsServers.joinToString(", ")}")
        mtu?.let { appendLine("MTU = $it") }
        if (includedApplications.isNotEmpty()) {
            appendLine("IncludedApplications = ${includedApplications.joinToString(", ")}")
        }
        for (peer in peers) {
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = ${peer.publicKey}")
            peer.presharedKey?.let { appendLine("PresharedKey = $it") }
            if (peer.allowedIps.isNotEmpty()) {
                appendLine("AllowedIPs = ${peer.allowedIps.joinToString(", ")}")
            }
            peer.endpoint?.let { appendLine("Endpoint = $it") }
            peer.persistentKeepalive?.let { appendLine("PersistentKeepalive = $it") }
        }
    }.trimEnd() + "\n"

    /** Не даём приватному ключу утечь в логи через data-class toString. */
    override fun toString(): String =
        "WarpConfig(privateKey=***, addresses=$addresses, dnsServers=$dnsServers, " +
            "mtu=$mtu, peers=${peers.size}, ignored=${(ignoredInterfaceKeys + ignoredPeerKeys).distinct()})"

    companion object {
        private const val MAX_PEERS = 16
        private const val MAX_TEXT_LENGTH = 128 * 1024

        private val KEY_PATTERN = Regex("^[A-Za-z0-9+/]{43}=$")
        private val IPV4_PATTERN = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        private val IPV6_PATTERN = Regex("^[0-9a-fA-F:]+$")
        private val ENDPOINT_HOST_PORT = Regex("^.+:\\d{1,5}$")
        private val BRACKETED_ENDPOINT = Regex("^\\[[0-9a-fA-F:.]+]:\\d{1,5}$")

        private val AMNEZIA_KEY_NAMES = setOf(
            "S1", "S2", "S3", "S4",
            "JC", "JMIN", "JMAX",
            "H1", "H2", "H3", "H4",
            "I1", "I2", "I3", "I4", "I5",
        )

        /**
         * Разбирает текст `.conf`. Бросает [WarpConfigException] с понятным
         * сообщением; приватный ключ можно передать отдельно ([privateKeyOverride]),
         * если на диске он не хранится.
         */
        fun parse(text: String, privateKeyOverride: String? = null): WarpConfig {
            if (text.isBlank()) throw WarpConfigException("Файл конфигурации пуст.")
            if (text.length > MAX_TEXT_LENGTH) {
                throw WarpConfigException("Файл конфигурации слишком большой — это не wg-конфиг.")
            }

            var section = ConfigSection.NONE
            var privateKey: String? = privateKeyOverride?.trim()?.takeIf { it.isNotEmpty() }
            val addresses = mutableListOf<String>()
            val dnsServers = mutableListOf<String>()
            var mtu: Int? = null
            val peers = mutableListOf<PeerDraft>()
            var currentPeer: PeerDraft? = null
            val ignoredInterfaceKeys = linkedSetOf<String>()
            val ignoredPeerKeys = linkedSetOf<String>()
            val ignoredSections = linkedSetOf<String>()

            fun finishPeer() {
                currentPeer?.let { peers += it }
                currentPeer = null
            }

            val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            for ((index, rawLine) in lines.withIndex()) {
                val lineNo = index + 1
                val withoutComment = rawLine.substringBefore('#').trim()
                if (withoutComment.isEmpty()) continue

                if (withoutComment.startsWith("[")) {
                    finishPeer()
                    if (!withoutComment.endsWith("]")) {
                        throw WarpConfigException("Строка $lineNo: незакрытый заголовок секции.")
                    }
                    val name = withoutComment.substring(1, withoutComment.length - 1).trim()
                    when (name.lowercase()) {
                        "interface" -> {
                            section = ConfigSection.INTERFACE
                        }
                        "peer" -> {
                            section = ConfigSection.PEER
                            currentPeer = PeerDraft()
                        }
                        else -> {
                            section = ConfigSection.IGNORED
                            ignoredSections += name
                        }
                    }
                    continue
                }

                if (section == ConfigSection.IGNORED) continue
                if (section == ConfigSection.NONE) {
                    throw WarpConfigException(
                        "Строка $lineNo: параметр вне секции [Interface] или [Peer].",
                    )
                }

                val equals = withoutComment.indexOf('=')
                if (equals <= 0) {
                    throw WarpConfigException("Строка $lineNo: ожидается формат «Ключ = Значение».")
                }
                val key = withoutComment.substring(0, equals).trim()
                val value = withoutComment.substring(equals + 1).trim()
                if (key.isEmpty()) {
                    throw WarpConfigException("Строка $lineNo: пустое имя параметра.")
                }

                if (section == ConfigSection.INTERFACE) {
                    when (key.lowercase()) {
                        "privatekey" -> {
                            if (privateKeyOverride.isNullOrBlank()) {
                                privateKey = value
                            }
                        }
                        "address" -> addresses += parseList(value, "Address", lineNo).map {
                            validateCidr(it, lineNo)
                        }
                        "dns" -> dnsServers += parseList(value, "DNS", lineNo).map {
                            validateDnsValue(it, lineNo)
                        }
                        "mtu" -> mtu = parseMtu(value, lineNo)
                        // Это Android-специфичные ключи библиотеки; мы принудительно
                        // задаём собственный allow-list приложений, поэтому не сохраняем.
                        "includedapplications", "excludedapplications" -> ignoredInterfaceKeys += key
                        else -> ignoredInterfaceKeys += key
                    }
                } else {
                    val peer = currentPeer ?: throw WarpConfigException(
                        "Строка $lineNo: параметр [Peer] до заголовка секции.",
                    )
                    when (key.lowercase()) {
                        "publickey" -> peer.publicKey = value
                        "presharedkey" -> peer.presharedKey = value
                        "allowedips" -> peer.allowedIps += parseList(value, "AllowedIPs", lineNo).map {
                            validateCidr(it, lineNo)
                        }
                        "endpoint" -> peer.endpoint = value
                        "persistentkeepalive" -> peer.persistentKeepalive =
                            parseKeepalive(value, lineNo)
                        else -> ignoredPeerKeys += key
                    }
                }
            }
            finishPeer()

            val key = privateKey?.takeIf { it.isNotBlank() }
                ?: throw WarpConfigException("В [Interface] нет PrivateKey.")
            validateKey(key, "PrivateKey")

            if (addresses.isEmpty()) {
                throw WarpConfigException("В [Interface] нет Address (например, 172.16.0.2/32).")
            }
            if (dnsServers.isEmpty()) {
                throw WarpConfigException("В [Interface] нет DNS — без него не разрешится music.youtube.com.")
            }
            if (peers.isEmpty()) {
                throw WarpConfigException("В конфиге нет ни одной секции [Peer].")
            }
            if (peers.size > MAX_PEERS) {
                throw WarpConfigException("Слишком много пиров (${peers.size}); поддерживается до $MAX_PEERS.")
            }

            val parsedPeers = peers.mapIndexed { peerIndex, draft ->
                val publicKey = draft.publicKey?.takeIf { it.isNotBlank() }
                    ?: throw WarpConfigException("В [Peer] №${peerIndex + 1} нет PublicKey.")
                validateKey(publicKey, "PublicKey")
                draft.presharedKey?.takeIf { it.isNotBlank() }?.let { psk ->
                    validateKey(psk, "PresharedKey")
                }
                val endpoint = draft.endpoint?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw WarpConfigException("В [Peer] №${peerIndex + 1} нет Endpoint.")
                validateEndpoint(endpoint, peerIndex + 1)
                if (draft.allowedIps.isEmpty()) {
                    throw WarpConfigException("В [Peer] №${peerIndex + 1} нет AllowedIPs.")
                }
                WarpPeer(
                    publicKey = publicKey,
                    presharedKey = draft.presharedKey?.takeIf { it.isNotBlank() },
                    allowedIps = draft.allowedIps.toList(),
                    endpoint = endpoint,
                    persistentKeepalive = draft.persistentKeepalive,
                )
            }

            return WarpConfig(
                privateKey = key,
                addresses = addresses.toList(),
                dnsServers = dnsServers.toList(),
                mtu = mtu,
                peers = parsedPeers,
                ignoredInterfaceKeys = ignoredInterfaceKeys.toList(),
                ignoredPeerKeys = ignoredPeerKeys.toList(),
                ignoredSections = ignoredSections.toList(),
            )
        }

        // ------------------------------------------------------------ валидация

        private fun parseList(value: String, field: String, lineNo: Int): List<String> {
            val parts = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) {
                throw WarpConfigException("Строка $lineNo: пустое значение $field.")
            }
            return parts
        }

        private fun parseMtu(value: String, lineNo: Int): Int {
            val mtu = value.toIntOrNull()
                ?: throw WarpConfigException("Строка $lineNo: MTU должен быть числом.")
            if (mtu !in 576..65535) {
                throw WarpConfigException("Строка $lineNo: MTU $mtu вне диапазона 576..65535.")
            }
            return mtu
        }

        private fun parseKeepalive(value: String, lineNo: Int): Int {
            val keepalive = value.toIntOrNull()
                ?: throw WarpConfigException("Строка $lineNo: PersistentKeepalive должен быть числом.")
            if (keepalive !in 0..65535) {
                throw WarpConfigException(
                    "Строка $lineNo: PersistentKeepalive $keepalive вне диапазона 0..65535.",
                )
            }
            return keepalive
        }

        private fun validateKey(value: String, field: String) {
            if (!KEY_PATTERN.matches(value)) {
                throw WarpConfigException(
                    "$field: ожидается base64-ключ WireGuard из 44 символов.",
                )
            }
        }

        private fun validateDnsValue(value: String, lineNo: Int): String {
            val ipv4 = IPV4_PATTERN.matches(value) && value.split('.').all {
                it.toIntOrNull()?.let { octet -> octet in 0..255 } == true
            }
            val ipv6 = value.contains(':') && IPV6_PATTERN.matches(value)
            if (!ipv4 && !ipv6) {
                throw WarpConfigException("Строка $lineNo: DNS «$value» не является IP-адресом.")
            }
            return value
        }

        private fun validateCidr(value: String, lineNo: Int): String {
            val slash = value.lastIndexOf('/')
            // wg-quick и wgcf допускают Address/AllowedIPs без префикса:
            // «172.16.0.2» означает 172.16.0.2/32, IPv6 — /128.
            val hasPrefix = slash > 0 && slash < value.lastIndex
            val address = if (slash > 0) value.substring(0, slash) else value
            val ipv4 = IPV4_PATTERN.matches(address) && address.split('.').all {
                it.toIntOrNull()?.let { octet -> octet in 0..255 } == true
            }
            val ipv6 = address.contains(':') && IPV6_PATTERN.matches(address)
            if (!ipv4 && !ipv6) {
                throw WarpConfigException("Строка $lineNo: «$address» не является IP-адресом.")
            }
            val maxPrefix = if (ipv4) 32 else 128
            if (!hasPrefix) {
                return "$address/$maxPrefix"
            }
            val prefix = value.substring(slash + 1).toIntOrNull()
                ?: throw WarpConfigException("Строка $lineNo: неверный префикс у «$value».")
            if (prefix !in 0..maxPrefix) {
                throw WarpConfigException(
                    "Строка $lineNo: префикс /$prefix вне диапазона 0..$maxPrefix.",
                )
            }
            return value
        }

        private fun validateEndpoint(value: String, peerNumber: Int) {
            val valid = if (value.startsWith("[")) {
                BRACKETED_ENDPOINT.matches(value)
            } else {
                ENDPOINT_HOST_PORT.matches(value) && run {
                    val port = value.substringAfterLast(':').toIntOrNull() ?: return@run false
                    port in 1..65535
                }
            }
            if (!valid) {
                throw WarpConfigException(
                    "В [Peer] №$peerNumber Endpoint «$value» должен иметь вид host:port.",
                )
            }
        }
    }
}

private class PeerDraft {
    var publicKey: String? = null
    var presharedKey: String? = null
    val allowedIps = mutableListOf<String>()
    var endpoint: String? = null
    var persistentKeepalive: Int? = null
}

private enum class ConfigSection { NONE, INTERFACE, PEER, IGNORED }
