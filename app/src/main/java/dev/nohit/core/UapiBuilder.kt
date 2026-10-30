package dev.nohit.core

import okio.ByteString.Companion.decodeBase64

/**
 * Сборка конфигурации в формате UAPI для демона amneziawg-go.
 *
 * Формат проверен по исходникам amneziawg-go v3.1.20260828:
 * `device/uapi.go`, `Device.IpcSet` → `IpcSetOperation(strings.NewReader(uapiConf))`.
 *
 * Особенности протокола, которые здесь соблюдаются:
 * - строки вида `key=value\n`, **без** префикса `set=1` (он нужен только сокетному
 *   `IpcHandle`, а не `IpcSet`);
 * - ключи устройства (`private_key`, `listen_port`, `jc`…, `h1`…, `i1`…) обязаны идти
 *   **до** первого `public_key`: после него `IpcSetOperation` переключается на разбор
 *   пировых ключей и любой device-ключ вернёт `invalid UAPI peer key`;
 * - `replace_allowed_ips=true` должен идти **до** `allowed_ip=`: он применяется сразу
 *   (`allowedips.RemoveByPeer`), а не в конце блока;
 * - пустая строка в потоке означает конец операции, поэтому пустых строк не бывает;
 * - ключи WireGuard/AmneziaWG передаются в hex (32 байта → 64 hex-символа).
 *
 * Приватный ключ в hex берётся из [AmneziaWgConfig] через internal-геттер и наружу
 * из библиотеки не выходит.
 */
object UapiBuilder {

    /** Длина ключа Curve25519 в байтах. */
    private const val KEY_SIZE = 32

    private const val MAX_PORT = 65535

    /** Максимум I-пакетов, которые понимает amneziawg-go (`i1..i5`). */
    private const val MAX_INJECTED_PACKETS = 5

    /**
     * Собирает UAPI-строку для [config].
     *
     * @param listenPort порт UDP-сокета устройства; `0` — выбрать случайный свободный
     *   (это значение по умолчанию, как у `wg-quick` без `ListenPort`).
     * @param keepalive `persistent_keepalive_interval` по умолчанию — для пиров, у которых
     *   в конфиге `PersistentKeepalive` не задан. Значение из конфига пира важнее параметра.
     * @throws IllegalArgumentException если ключ не 32 байта, порт вне `0..65535`,
     *   либо в значениях есть перевод строки (попытка инъекции UAPI-ключей).
     */
    fun from(config: AmneziaWgConfig, listenPort: Int = 0, keepalive: Int = 25): String {
        require(listenPort in 0..MAX_PORT) { "listenPort должен быть в 0..$MAX_PORT, получено $listenPort" }
        require(keepalive in 0..MAX_PORT) { "keepalive должен быть в 0..$MAX_PORT, получено $keepalive" }

        val out = StringBuilder(512)

        // --- Ключи устройства: строго до первого public_key ---
        out.put("private_key", config.privateKeyHex())
        out.put("listen_port", listenPort.toString())

        // --- Параметры обфускации AmneziaWG: только если конфиг не «чистый» WireGuard ---
        config.obfuscation?.let { obfuscation ->
            require(obfuscation.injectedPackets.size <= MAX_INJECTED_PACKETS) {
                "AmneziaWG поддерживает не больше $MAX_INJECTED_PACKETS I-пакетов, " +
                    "получено ${obfuscation.injectedPackets.size}"
            }

            obfuscation.jc?.let { out.put("jc", it.toString()) }
            obfuscation.jmin?.let { out.put("jmin", it.toString()) }
            obfuscation.jmax?.let { out.put("jmax", it.toString()) }
            obfuscation.s1?.let { out.put("s1", it.toString()) }
            obfuscation.s2?.let { out.put("s2", it.toString()) }
            obfuscation.s3?.let { out.put("s3", it.toString()) }
            obfuscation.s4?.let { out.put("s4", it.toString()) }

            // H1–H4: UAPI ждёт строку «a» или «a-b», поэтому сырое значение из конфига
            // приоритетнее разобранного числа.
            headerValue(obfuscation, "h1", obfuscation.h1)?.let { out.put("h1", it) }
            headerValue(obfuscation, "h2", obfuscation.h2)?.let { out.put("h2", it) }
            headerValue(obfuscation, "h3", obfuscation.h3)?.let { out.put("h3", it) }
            headerValue(obfuscation, "h4", obfuscation.h4)?.let { out.put("h4", it) }

            obfuscation.injectedPackets.forEachIndexed { index, packet ->
                out.put("i${index + 1}", packet)
            }
        }

        // --- NoHiT-расширения: собственный шумовой профиль поверх AmneziaWG ---
        nohitLines(config.unknownInterfaceKeys).forEach { (key, value) -> out.put(key, value) }
        // --- Пиры: каждый блок начинается с public_key ---
        for (peer in config.peers) {
            require(peer.endpoint.isNotBlank()) { "У пира пустой endpoint" }

            out.put("public_key", keyToHex(peer.publicKey))
            out.put("endpoint", peer.endpoint.trim())
            out.put("persistent_keepalive_interval", (peer.persistentKeepalive ?: keepalive).toString())
            out.put("replace_allowed_ips", "true")
            for (allowedIp in peer.allowedIps) {
                out.put("allowed_ip", allowedIp.trim())
            }
        }

        return out.toString()
    }

    /**
     * base64-ключ WireGuard/AmneziaWG (32 байта) → hex для UAPI (64 символа, нижний регистр).
     *
     * Декодирование — через okio, а не `android.util.Base64`: okio приходит транзитивно
     * с OkHttp и работает в обычных JVM-юнит-тестах.
     *
     * @throws IllegalArgumentException если строка не является base64 или ключ не 32 байта.
     */
    /**
     * Строки nh_* для собственного шумового слоя NoHiT.
     * Ключи читаются из .conf (NHProfile, NHSeed, NHImitate, NHJitterMs, NHBucket);
     * парсер складывает их в unknownInterfaceKeys в нижнем регистре.
     */
    private fun nohitLines(keys: Map<String, String>): List<Pair<String, String>> {
        val profile = keys["nhprofile"]?.trim()?.lowercase() ?: return emptyList()
        if (profile == "off") return emptyList()
        require(profile in setOf("balanced", "stealth", "paranoid")) {
            "nhprofile: ожидается off|balanced|stealth|paranoid, получено '$profile'"
        }
        val result = mutableListOf("nh_version" to "1", "nh_profile" to profile)
        keys["nhseed"]?.trim()?.takeIf { it.isNotEmpty() }?.let { seed ->
            val clean = seed.removePrefix("0x").removePrefix("0X")
            require(clean.matches(Regex("^[0-9a-fA-F]{1,16}$"))) {
                "nhseed: ожидается hex до 16 символов, получено '$seed'"
            }
            result += "nh_seed" to clean.lowercase()
        }
        keys["nhimitate"]?.trim()?.lowercase()?.let { imitate ->
            require(imitate in setOf("mixed", "quic", "dns", "dtls")) {
                "nhimitate: ожидается mixed|quic|dns|dtls, получено '$imitate'"
            }
            result += "nh_imitate" to imitate
        }
        (keys["nhjitterms"] ?: keys["nhjitter"])?.trim()?.let { jitter ->
            val value = jitter.toIntOrNull()
                ?: throw IllegalArgumentException("nhjitter: ожидается число 0..500")
            require(value in 0..500) { "nhjitter: ожидается 0..500, получено $value" }
            result += "nh_jitter_ms" to value.toString()
        }
        keys["nhdecoys"]?.trim()?.let { decoys ->
            val value = decoys.toIntOrNull()
                ?: throw IllegalArgumentException("nhdecoys: ожидается число 0..8")
            require(value in 0..8) { "nhdecoys: ожидается 0..8, получено $value" }
            result += "nh_decoys" to value.toString()
        }
        keys["nhbucket"]?.trim()?.lowercase()?.let { bucket ->
            result += "nh_bucket" to if (bucket in setOf("on", "true", "1")) "on" else "off"
        }
        return result
    }
    fun keyToHex(base64Key: String): String {
        require(base64Key.isNotEmpty()) { "Ключ пустой: ожидалось 44 base64-символа (32 байта)" }
        val bytes = base64Key.decodeBase64()
            ?: throw IllegalArgumentException("Ключ не является корректным base64")
        require(bytes.size == KEY_SIZE) {
            "Ключ должен быть $KEY_SIZE байт, получено ${bytes.size}"
        }
        return bytes.hex()
    }

    private fun headerValue(
        obfuscation: AmneziaWgConfig.Obfuscation,
        name: String,
        numeric: Long?
    ): String? = obfuscation.headerRanges[name] ?: numeric?.toString()

    /**
     * Дописывает одну строку UAPI. Значения проверяются на перевод строки: иначе
     * строка из конфига могла бы подсунуть произвольный UAPI-ключ (например, `private_key`).
     */
    private fun StringBuilder.put(key: String, value: String) {
        require(key.isNotEmpty() && key.none { it == '=' || it == '\n' || it == '\r' }) {
            "Недопустимый UAPI-ключ: $key"
        }
        require(value.isNotEmpty()) { "Пустое значение UAPI-ключа $key" }
        require(value.none { it == '\n' || it == '\r' }) {
            "Значение UAPI-ключа $key содержит перевод строки"
        }
        append(key).append('=').append(value).append('\n')
    }
}
