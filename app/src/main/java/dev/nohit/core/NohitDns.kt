package dev.nohit.core

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Тестовый шов резолвера: подменяет реальный поход в сеть (DoH/UDP).
 * В проде всегда `null` — [NohitDns] работает через OkHttp и UDP напрямую.
 */
internal fun interface DnsTransport {
    fun resolve(upstream: DnsUpstream, host: String): List<InetAddress>
}

/**
 * Резолвер без системного DNS: DoH (JSON) и/или обычный UDP на заданные серверы.
 * Кэш 60 секунд, деградация по цепочке, в конце — системный DNS.
 *
 * CORE-3: [clearCache] вызывается при смене сети ([NetworkChangeWatcher]),
 * чтобы в новой сети не отдавались мёртвые ответы из старой.
 * CORE-5: если задана [policy], резолвер запоминает «хост → IP → фрагментировать ли»
 * в [ResolvedHostTable]; решает уже [FragSocketFactory] в момент `connect()`.
 *
 * M-7 (DNS-rebinding): при [filterPrivateAddresses] (по умолчанию включено)
 * адреса из служебных/приватных диапазонов ([AddressClassifier]) не покидают
 * резолвер — ни из DoH, ни из UDP, ни из литерала, ни из системного фолбэка.
 * Если апстрим вернул только такие адреса, резолв считается заблокированным
 * (а не «пустым»): откат на системный DNS в этом случае запрещён, иначе фильтр
 * обходится одним и тем же ответом.
 */
class NohitDns internal constructor(
    private val upstreams: List<DnsUpstream>,
    private val timeoutMs: Int,
    private val policy: HostPolicy,
    private val transport: DnsTransport?,
    private val cacheTtlMs: Long,
    private val filterPrivateAddresses: Boolean = true
) : Dns {

    constructor(
        upstreams: List<DnsUpstream>,
        timeoutMs: Int = 4000,
        policy: HostPolicy = HostPolicy.DEFAULT,
        filterPrivateAddresses: Boolean = true
    ) : this(upstreams, timeoutMs, policy, null, CACHE_TTL_MS, filterPrivateAddresses)

    private data class Entry(val addresses: List<InetAddress>, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val failures = ConcurrentHashMap<String, Int>()
    private val dohClients = ConcurrentHashMap<String, OkHttpClient>()

    override fun lookup(hostname: String): List<InetAddress> {
        literal(hostname)?.let { address ->
            // Литерал не резолвится по сети, но приватный литерал — такой же
            // rebinding-вектор (приложение может получить его из конфига/API).
            if (filterPrivateAddresses && !AddressClassifier.isPublic(address)) {
                throw UnknownHostException(BLOCKED_MESSAGE)
            }
            return listOf(address)
        }

        cache[hostname]?.let { entry ->
            if (entry.expiresAt > System.currentTimeMillis()) {
                // В кэш попадают только уже отфильтрованные адреса.
                remember(hostname, entry.addresses)
                return entry.addresses
            }
            cache.remove(hostname, entry)
        }

        var sawNonPublic = false
        for (upstream in ordered()) {
            val raw = try {
                fetch(upstream, hostname)
            } catch (_: Throwable) {
                emptyList()
            }
            if (raw.isNotEmpty()) {
                val found = filterPublic(raw)
                if (found.isNotEmpty()) {
                    cache[hostname] = Entry(found, System.currentTimeMillis() + cacheTtlMs)
                    failures.remove(upstream.id)
                    remember(hostname, found)
                    return found
                }
                // Ответ был, но весь отфильтрован как непубличный: это попытка
                // rebinding, а не «апстрим не ответил». Системный фолбэк ниже
                // в таком случае запрещён (иначе он вернёт тот же приватный IP).
                sawNonPublic = true
            }
            failures[upstream.id] = (failures[upstream.id] ?: 0) + 1
        }

        if (sawNonPublic) throw UnknownHostException(BLOCKED_MESSAGE)

        val system = filterPublic(Dns.SYSTEM.lookup(hostname))
        if (filterPrivateAddresses && system.isEmpty()) throw UnknownHostException(BLOCKED_MESSAGE)
        remember(hostname, system)
        return system
    }

    /**
     * Сброс DNS-кэша (CORE-3). Вызывается при смене сети — конфиг при этом
     * не трогается. Заодно сбрасываются штрафы апстримов: в новой сети сервер,
     * который не отвечал в старой, вполне может работать.
     */
    fun clearCache() {
        cache.clear()
        failures.clear()
    }

    /** Сколько хостов сейчас в кэше (диагностика). */
    internal fun cachedHosts(): Int = cache.size

    private fun filterPublic(addresses: List<InetAddress>): List<InetAddress> =
        if (!filterPrivateAddresses) addresses else addresses.filter(AddressClassifier::isPublic)

    private fun fetch(upstream: DnsUpstream, host: String): List<InetAddress> =
        transport?.resolve(upstream, host) ?: resolve(upstream, host)

    private fun remember(hostname: String, addresses: List<InetAddress>) {
        if (!policy.isEnabled || addresses.isEmpty()) return
        ResolvedHostTable.record(addresses, policy.shouldFragment(hostname))
    }

    private fun ordered(): List<DnsUpstream> =
        upstreams.sortedBy { failures[it.id] ?: 0 }

    private fun resolve(upstream: DnsUpstream, host: String): List<InetAddress> {
        if (upstream.dohUrl != null) {
            val v4 = doh(upstream, host, 1)
            val v6 = doh(upstream, host, 28)
            val merged = v4 + v6
            if (merged.isNotEmpty()) return merged
        }
        if (upstream.udpServers.isNotEmpty()) {
            val v4 = udp(upstream, host, 1)
            val v6 = udp(upstream, host, 28)
            val merged = v4 + v6
            if (merged.isNotEmpty()) return merged
        }
        return emptyList()
    }

    private fun doh(upstream: DnsUpstream, host: String, type: Int): List<InetAddress> {
        val base = upstream.dohUrl ?: return emptyList()
        val separator = if (base.contains("?")) "&" else "?"
        val query = base + separator + "name=" + host + "&type=" + if (type == 28) "AAAA" else "A"
        val client = dohClients.getOrPut(upstream.id) {
            OkHttpClient.Builder()
                .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .build()
        }
        val request = Request.Builder()
            .url(query)
            .header("Accept", "application/dns-json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val answers = JSONObject(body).optJSONArray("Answer") ?: return emptyList()
            val result = ArrayList<InetAddress>(answers.length())
            for (i in 0 until answers.length()) {
                val answer = answers.optJSONObject(i) ?: continue
                if (answer.optInt("type") != type) continue
                literal(answer.optString("data"))?.let { result.add(it) }
            }
            return result
        }
    }

    private fun udp(upstream: DnsUpstream, host: String, type: Int): List<InetAddress> {
        for (server in upstream.udpServers) {
            try {
                val found = DnsWire.query(server, host, type, timeoutMs)
                if (found.isNotEmpty()) return found
            } catch (_: Throwable) {
                // пробуем следующий сервер
            }
        }
        return emptyList()
    }

    private fun literal(value: String): InetAddress? {
        val text = value.trim()
        if (text.isEmpty()) return null
        val v4 = text.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))
        val v6 = text.contains(":") && text.matches(Regex("^[0-9a-fA-F:]+$"))
        if (!v4 && !v6) return null
        return try {
            InetAddress.getByName(text)
        } catch (_: Throwable) {
            null
        }
    }

    private companion object {
        const val CACHE_TTL_MS = 60_000L
        const val BLOCKED_MESSAGE = "NoHiT: host resolves only to non-public addresses"
    }
}

/** Минимальный DNS-клиент по UDP: только A/AAAA, без зависимостей. */
internal object DnsWire {

    private const val DNS_PORT = 53

    /** Сколько подряд чужих/невалидных датаграмм игнорируем в одном запросе. */
    private const val MAX_REJECTED_DATAGRAMS = 32

    private const val CLASS_IN = 1
    private const val TYPE_A = 1
    private const val TYPE_CNAME = 5
    private const val TYPE_AAAA = 28

    private const val MAX_POINTER_JUMPS = 16
    private const val MAX_NAME_LENGTH = 255

    /**
     * Криптостойкий источник 16-битного ID (H-3). `System.nanoTime()` был
     * предсказуем: on-path DPI читал ID из запроса и подделывал ответ.
     */
    private val random = SecureRandom()

    /** Случайный 16-битный идентификатор DNS-запроса (`0..65535`). */
    internal fun newId(): Int = random.nextInt(0x10000)

    fun query(server: String, name: String, type: Int, timeoutMs: Int): List<InetAddress> {
        val id = newId()
        val payload = buildQuery(id, name, type)
        val address = InetAddress.getByName(server)
        DatagramSocket().use { socket ->
            // H-3: connect() — фильтрация источника на уровне ОС, `receive()`
            // принимает датаграммы только с этого адреса и только с порта 53.
            socket.connect(InetSocketAddress(address, DNS_PORT))
            socket.send(DatagramPacket(payload, payload.size))

            val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(1) * 1_000_000L
            val buffer = ByteArray(4096)
            var rejected = 0
            while (true) {
                val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
                if (remainingMs <= 0L) return emptyList()
                socket.soTimeout = remainingMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)

                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    return emptyList()
                }

                // ОС уже отфильтровала источник, но проверяем ещё раз — так
                // защита не зависит от особенностей платформы (H-3).
                if (packet.address != address || packet.port != DNS_PORT) {
                    if (++rejected > MAX_REJECTED_DATAGRAMS) return emptyList()
                    continue
                }

                // Эхо вопроса + заголовок: ответ без нашего question-секции —
                // чужой пакет (спуфинг/перебор), а не ответ сервера.
                val parsed = parseValidated(packet.data, packet.length, id, name, type)
                if (parsed == null) {
                    if (++rejected > MAX_REJECTED_DATAGRAMS) return emptyList()
                    continue
                }
                return parsed
            }
        }
    }

    internal fun buildQuery(id: Int, name: String, type: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write((id shr 8) and 0xFF)
        out.write(id and 0xFF)
        out.write(0x01)
        out.write(0x00)
        out.write(0x00)
        out.write(0x01)
        repeat(6) { out.write(0) }
        for (label in name.trim('.').split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size and 0xFF)
            out.write(bytes)
        }
        out.write(0)
        out.write((type shr 8) and 0xFF)
        out.write(type and 0xFF)
        out.write(0x00)
        out.write(0x01)
        return out.toByteArray()
    }

    /**
     * Строгий разбор ответа (H-3). Возвращает адреса A/AAAA либо `null`,
     * если пакет не является ответом именно на наш запрос:
     *  * не совпал ID;
     *  * QR=0 / OPCODE≠0 / RCODE≠0 / TC=1;
     *  * QDCOUNT≠1 или question-секция не совпала с запросом
     *    (QNAME без учёта регистра, QTYPE, QCLASS=IN);
     *  * имя владельца A/AAAA-записи не относится к запрошенному имени
     *    (с учётом цепочки CNAME).
     *
     * `emptyList()` — валидный ответ без A/AAAA (NODATA): ждать дальше
     * бессмысленно, ретрай делает вызывающий по следующему апстриму.
     */
    internal fun parseValidated(
        data: ByteArray,
        length: Int,
        id: Int,
        name: String,
        type: Int
    ): List<InetAddress>? {
        if (length < 12 || length > data.size) return null
        if (u16(data, 0) != id) return null

        val flags = u16(data, 2)
        if (flags and 0x8000 == 0) return null       // QR: это должен быть ответ
        if (flags and 0x7800 != 0) return null       // OPCODE: только QUERY
        if (flags and 0x000F != 0) return null       // RCODE: только NOERROR
        if (flags and 0x0200 != 0) return null       // TC: усечённый ответ не используем

        val questions = u16(data, 4)
        val answers = u16(data, 6)
        if (questions != 1) return null              // вопрос должен быть ровно один

        var offset = 12
        val question = readName(data, offset, length) ?: return null
        offset = question.next
        if (offset + 4 > length) return null
        val questionType = u16(data, offset)
        val questionClass = u16(data, offset + 2)
        offset += 4
        if (questionClass != CLASS_IN) return null
        if (questionType != type) return null
        if (normalizeName(question.name) != normalizeName(name)) return null

        // Имена, которым «разрешено» владеть A/AAAA: запрошенное имя и цели
        // встреченных CNAME-цепочек, начинающихся с него.
        val validOwners = HashSet<String>()
        validOwners.add(normalizeName(name))

        val result = ArrayList<InetAddress>(answers)
        repeat(answers) {
            val owner = readName(data, offset, length) ?: return null
            offset = owner.next
            if (offset + 10 > length) return null
            val answerType = u16(data, offset)
            val answerClass = u16(data, offset + 2)
            val rdLength = u16(data, offset + 8)
            val rdStart = offset + 10
            if (rdStart + rdLength > length) return null
            val ownerName = normalizeName(owner.name)
            when {
                answerClass != CLASS_IN -> Unit
                answerType == TYPE_CNAME && ownerName in validOwners -> {
                    val target = readName(data, rdStart, rdStart + rdLength)
                    if (target != null) validOwners.add(normalizeName(target.name))
                }
                answerType == TYPE_A && rdLength == 4 && ownerName in validOwners ->
                    result.add(InetAddress.getByAddress(data.copyOfRange(rdStart, rdStart + 4)))
                answerType == TYPE_AAAA && rdLength == 16 && ownerName in validOwners ->
                    result.add(InetAddress.getByAddress(data.copyOfRange(rdStart, rdStart + 16)))
            }
            offset = rdStart + rdLength
        }
        return result
    }

    /**
     * Прежний «мягкий» парсер: извлекает A/AAAA без проверки question-секции.
     * Оставлен для обратной совместимости внутренних тестов/утилит; сетевой
     * путь [query] использует только [parseValidated].
     */
    internal fun parse(data: ByteArray, length: Int, id: Int): List<InetAddress> {
        if (length < 12) return emptyList()
        val answerId = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        if (answerId != id) return emptyList()

        val questions = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        val answers = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)

        var offset = 12
        repeat(questions) { offset = skipName(data, offset) + 4 }

        val result = ArrayList<InetAddress>(answers)
        repeat(answers) {
            if (offset + 10 > length) return result
            offset = skipName(data, offset)
            if (offset + 10 > length) return result
            val type = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            val rdLength = ((data[offset + 8].toInt() and 0xFF) shl 8) or (data[offset + 9].toInt() and 0xFF)
            val rdStart = offset + 10
            if (rdStart + rdLength > length) return result
            when {
                type == 1 && rdLength == 4 ->
                    result.add(InetAddress.getByAddress(data.copyOfRange(rdStart, rdStart + 4)))
                type == 28 && rdLength == 16 ->
                    result.add(InetAddress.getByAddress(data.copyOfRange(rdStart, rdStart + 16)))
            }
            offset = rdStart + rdLength
        }
        return result
    }

    private class ParsedName(val name: String, val next: Int)

    /**
     * Читает DNS-имя с поддержкой сжатия. Указатель обязан смотреть назад
     * (защита от петель) и проходить не более [MAX_POINTER_JUMPS] раз.
     * [limit] — граница, за которую имя не выходит (пакет или rdata).
     */
    private fun readName(data: ByteArray, start: Int, limit: Int): ParsedName? {
        if (start !in 0 until limit) return null
        var offset = start
        var afterPointer = -1
        var guard = 0
        var total = 0
        val labels = StringBuilder()
        while (true) {
            if (offset >= limit) return null
            val lengthByte = data[offset].toInt() and 0xFF
            if (lengthByte == 0) {
                return ParsedName(labels.toString(), if (afterPointer >= 0) afterPointer else offset + 1)
            }
            if ((lengthByte and 0xC0) == 0xC0) {
                if (offset + 1 >= limit) return null
                val target = ((lengthByte and 0x3F) shl 8) or (data[offset + 1].toInt() and 0xFF)
                if (afterPointer < 0) afterPointer = offset + 2
                if (++guard > MAX_POINTER_JUMPS) return null
                if (target >= offset) return null
                offset = target
                continue
            }
            if ((lengthByte and 0xC0) != 0) return null
            if (lengthByte > 63) return null
            if (offset + 1 + lengthByte > limit) return null
            total += lengthByte + 1
            if (total > MAX_NAME_LENGTH) return null
            if (labels.isNotEmpty()) labels.append('.')
            labels.append(String(data, offset + 1, lengthByte, Charsets.US_ASCII))
            offset += 1 + lengthByte
        }
    }

    private fun normalizeName(value: String): String = value.trim().trimEnd('.').lowercase()

    private fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun skipName(data: ByteArray, start: Int): Int {
        var offset = start
        var guard = 0
        while (offset < data.size && guard < 128) {
            val length = data[offset].toInt() and 0xFF
            if (length == 0) return offset + 1
            if ((length and 0xC0) == 0xC0) return offset + 2
            offset += 1 + length
            guard++
        }
        return data.size
    }
}
