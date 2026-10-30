package dev.nohit.core

/**
 * Разбор и переупаковка первого TLS-рекорда.
 *
 * Зачем: если резать ClientHello только по TCP, DPI может собрать поток обратно
 * (reassembly) и всё равно прочитать SNI. Другой подход — оставить TCP целым,
 * но разбить *сам TLS-рекорд* на несколько корректных записей: тогда DPI видит
 * N самостоятельных записей, каждая из которых не содержит полного ClientHello.
 *
 * Это законно с точки зрения TLS: handshake-сообщение может занимать несколько
 * записей (RFC 5246 §6.2.1, RFC 8446 §5.1).
 */
internal object TlsFraming {

    const val TYPE_HANDSHAKE = 0x16
    private const val HANDSHAKE_CLIENT_HELLO = 0x01
    private const val HANDSHAKE_HEADER_SIZE = 4
    private const val EXT_SERVER_NAME = 0x0000
    private const val NAME_TYPE_HOST_NAME = 0x00
    const val HEADER_SIZE = 5
    private const val MAX_RECORD = 16_384 + 2_048

    data class Header(val type: Int, val major: Int, val minor: Int, val length: Int)

    /** Возвращает заголовок записи или null, если это не похоже на TLS-рекорд. */
    fun parseHeader(buffer: ByteArray, length: Int = buffer.size, offset: Int = 0): Header? {
        if (length - offset < HEADER_SIZE) return null
        val type = buffer[offset].toInt() and 0xFF
        val major = buffer[offset + 1].toInt() and 0xFF
        val minor = buffer[offset + 2].toInt() and 0xFF
        val recordLength = ((buffer[offset + 3].toInt() and 0xFF) shl 8) or (buffer[offset + 4].toInt() and 0xFF)
        // TLS 1.x всегда начинается с 0x03; 0x16 — handshake.
        // Допустимые типы TLS-записей: CCS(20), Alert(21), Handshake(22), AppData(23).
        if (type < 0x14 || type > 0x17) return null
        if (major != 0x03) return null
        if (recordLength == 0 || recordLength > MAX_RECORD) return null
        return Header(type, major, minor, recordLength)
    }

    fun isClientHello(payload: ByteArray, length: Int = payload.size): Boolean =
        length > 0 && (payload[0].toInt() and 0xFF) == HANDSHAKE_CLIENT_HELLO

    /** Позиция host_name в расширении server_name внутри payload ClientHello. */
    data class ClientHelloInfo(
        /** Индекс первого байта имени сервера в payload. */
        val sniOffset: Int,
        /** Длина имени сервера в байтах. */
        val sniLength: Int
    )

    /**
     * Разбирает ClientHello и находит host_name в расширении server_name (0x0000).
     *
     * Структура (RFC 8446 §4.1.2):
     * handshake: type(1) + length(3) + body
     * body: version(2) + random(32) + session_id(1+n) + cipher_suites(2+n) +
     *       compression_methods(1+n) + extensions(2+n)
     * extension server_name: list_length(2) + { name_type(1) + name_length(2) + name }
     *
     * @return null, если payload — не ClientHello, структура битая или SNI отсутствует.
     */
    fun parseClientHello(payload: ByteArray, length: Int = payload.size): ClientHelloInfo? {
        if (length < HANDSHAKE_HEADER_SIZE + 35) return null
        if ((payload[0].toInt() and 0xFF) != HANDSHAKE_CLIENT_HELLO) return null

        val bodyLength = ((payload[1].toInt() and 0xFF) shl 16) or
            ((payload[2].toInt() and 0xFF) shl 8) or
            (payload[3].toInt() and 0xFF)
        val bodyStart = HANDSHAKE_HEADER_SIZE
        val bodyEnd = minOf(length, bodyStart + bodyLength)
        // version(2) + random(32) + session_id_len(1)
        if (bodyStart + 35 > bodyEnd) return null

        var offset = bodyStart + 2 + 32
        val sessionIdLength = payload[offset].toInt() and 0xFF
        offset += 1 + sessionIdLength
        if (offset + 2 > bodyEnd) return null

        val cipherSuitesLength = u16(payload, offset)
        offset += 2 + cipherSuitesLength
        if (offset + 1 > bodyEnd) return null

        val compressionLength = payload[offset].toInt() and 0xFF
        offset += 1 + compressionLength
        if (offset + 2 > bodyEnd) return null

        val extensionsLength = u16(payload, offset)
        offset += 2
        val extensionsEnd = minOf(bodyEnd, offset + extensionsLength)

        while (offset + 4 <= extensionsEnd) {
            val type = u16(payload, offset)
            val dataStart = offset + 4
            val dataEnd = dataStart + u16(payload, offset + 2)
            if (dataEnd > extensionsEnd) return null
            if (type == EXT_SERVER_NAME) return parseServerName(payload, dataStart, dataEnd)
            offset = dataEnd
        }
        return null
    }

    private fun parseServerName(payload: ByteArray, dataStart: Int, dataEnd: Int): ClientHelloInfo? {
        if (dataStart + 2 > dataEnd) return null
        var offset = dataStart + 2
        val listEnd = minOf(dataEnd, offset + u16(payload, dataStart))
        while (offset + 3 <= listEnd) {
            val nameType = payload[offset].toInt() and 0xFF
            val nameLength = u16(payload, offset + 1)
            val nameStart = offset + 3
            val nameEnd = nameStart + nameLength
            if (nameEnd > listEnd) return null
            if (nameType == NAME_TYPE_HOST_NAME && nameLength > 0) {
                return ClientHelloInfo(nameStart, nameLength)
            }
            offset = nameEnd
        }
        return null
    }

    /**
     * Точки разреза payload по границам SNI.
     *
     * Пустой список — резать по SNI нельзя: не ClientHello, нет SNI или
     * границы выродились (тогда вызывающий использует обычную стратегию).
     *
     * [explicitOffset] для [SniMode.MID] — сдвиг разреза от начала host_name;
     * 0 или значение вне диапазона означает «автоматически» (трети имени).
     */
    fun splitOffsets(payload: ByteArray, mode: SniMode, explicitOffset: Int = 0): List<Int> {
        if (mode == SniMode.NONE) return emptyList()
        val info = parseClientHello(payload, payload.size) ?: return emptyList()
        val hostStart = info.sniOffset
        val hostEnd = info.sniOffset + info.sniLength
        val cuts = when (mode) {
            SniMode.START -> listOf(hostStart)
            SniMode.END -> listOf(hostEnd)
            SniMode.MID -> if (explicitOffset in 1 until info.sniLength) {
                listOf(hostStart + explicitOffset)
            } else {
                val third = info.sniLength / 3
                if (third >= 1) {
                    listOf(hostStart + third, hostStart + 2 * third)
                } else {
                    listOf(hostStart + info.sniLength / 2)
                }
            }
            SniMode.NONE -> emptyList()
        }
        return cuts.filter { it > 0 && it < payload.size }.distinct().sorted()
    }

    /**
     * Режет payload на несколько TLS-записей в заданных точках.
     * Некорректные точки игнорируются; если валидных нет — возвращается одна запись.
     */
    fun splitAtOffsets(
        payload: ByteArray,
        offsets: List<Int>,
        type: Int,
        major: Int,
        minor: Int
    ): List<ByteArray> {
        val cuts = offsets.filter { it > 0 && it < payload.size }.distinct().sorted()
        if (cuts.isEmpty()) return listOf(record(type, major, minor, payload, 0, payload.size))
        val records = ArrayList<ByteArray>(cuts.size + 1)
        var offset = 0
        for (cut in cuts) {
            records.add(record(type, major, minor, payload, offset, cut - offset))
            offset = cut
        }
        records.add(record(type, major, minor, payload, offset, payload.size - offset))
        return records
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    /**
     * Делит payload ClientHello на несколько TLS-записей с тем же типом и версией.
     * Возвращает готовые к отправке байты (заголовок + полезная нагрузка).
     */
    fun splitIntoRecords(
        payload: ByteArray,
        chunkSize: Int,
        type: Int,
        major: Int,
        minor: Int
    ): List<ByteArray> {
        val safeChunk = if (chunkSize > MAX_RECORD) MAX_RECORD else chunkSize
        if (safeChunk <= 0 || payload.size <= safeChunk) {
            return listOf(record(type, major, minor, payload, 0, payload.size))
        }
        val records = ArrayList<ByteArray>(payload.size / safeChunk + 1)
        var offset = 0
        while (offset < payload.size) {
            val size = minOf(safeChunk, payload.size - offset)
            records.add(record(type, major, minor, payload, offset, size))
            offset += size
        }
        return records
    }

    private fun record(
        type: Int,
        major: Int,
        minor: Int,
        payload: ByteArray,
        offset: Int,
        size: Int
    ): ByteArray {
        val out = ByteArray(HEADER_SIZE + size)
        out[0] = type.toByte()
        out[1] = major.toByte()
        out[2] = minor.toByte()
        out[3] = ((size shr 8) and 0xFF).toByte()
        out[4] = (size and 0xFF).toByte()
        payload.copyInto(out, HEADER_SIZE, offset, offset + size)
        return out
    }
}
