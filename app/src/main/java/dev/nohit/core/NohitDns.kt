package dev.nohit.core

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Резолвер без системного DNS: DoH (JSON) и/или обычный UDP на заданные серверы.
 * Кэш 60 секунд, деградация по цепочке, в конце — системный DNS.
 */
class NohitDns(
    private val upstreams: List<DnsUpstream>,
    private val timeoutMs: Int = 4000
) : Dns {

    private data class Entry(val addresses: List<InetAddress>, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val failures = ConcurrentHashMap<String, Int>()
    private val dohClients = ConcurrentHashMap<String, OkHttpClient>()

    override fun lookup(hostname: String): List<InetAddress> {
        literal(hostname)?.let { return listOf(it) }

        cache[hostname]?.let { entry ->
            if (entry.expiresAt > System.currentTimeMillis()) return entry.addresses
        }

        for (upstream in ordered()) {
            val found = try {
                resolve(upstream, hostname)
            } catch (_: Throwable) {
                emptyList()
            }
            if (found.isNotEmpty()) {
                cache[hostname] = Entry(found, System.currentTimeMillis() + CACHE_TTL_MS)
                failures.remove(upstream.id)
                return found
            }
            failures[upstream.id] = (failures[upstream.id] ?: 0) + 1
        }

        return Dns.SYSTEM.lookup(hostname)
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
    }
}

/** Минимальный DNS-клиент по UDP: только A/AAAA, без зависимостей. */
internal object DnsWire {

    fun query(server: String, name: String, type: Int, timeoutMs: Int): List<InetAddress> {
        val id = (System.nanoTime().toInt() and 0xFFFF)
        val payload = buildQuery(id, name, type)
        val address = InetAddress.getByName(server)
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.send(DatagramPacket(payload, payload.size, address, 53))
            val buffer = ByteArray(2048)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            return parse(packet.data, packet.length, id)
        }
    }

    private fun buildQuery(id: Int, name: String, type: Int): ByteArray {
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

    private fun parse(data: ByteArray, length: Int, id: Int): List<InetAddress> {
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
