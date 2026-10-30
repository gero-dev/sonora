package dev.nohit.core

import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * SocketFactory, которая вмешивается только в самое начало TLS-соединения:
 * 1) при необходимости переупаковывает ClientHello в несколько TLS-записей
 *    (см. [TlsFraming]) — защита от сборки TCP обратно;
 * 2) при необходимости режет поток на мелкие TCP-сегменты.
 *
 * Всё, что идёт после первого рекорда, проходит без изменений.
 *
 * CORE-5: если в [HostPolicy] заданы правила по доменам, решение принимается
 * в `connect()` по remote IP — имя хоста уже разрезолвил [NohitDns] и записал
 * в [ResolvedHostTable]. Для хоста из skip-списка возвращается исходный поток
 * сокета без переупаковки. URL/заголовки не логируются и не сохраняются.
 */
class FragSocketFactory private constructor(
    private val delegate: SocketFactory,
    private val plan: SplitPlan,
    private val policy: HostPolicy
) : SocketFactory() {

    companion object {

        /** Фрагментация без правил по доменам (поведение по умолчанию). */
        fun create(plan: SplitPlan, delegate: SocketFactory = SocketFactory.getDefault()): SocketFactory =
            create(plan, delegate, HostPolicy.DEFAULT)

        /** Фрагментация с правилами по доменам (CORE-5). */
        fun create(plan: SplitPlan, delegate: SocketFactory, policy: HostPolicy): SocketFactory =
            if (!plan.isActive()) delegate else FragSocketFactory(delegate, plan, policy)
    }

    override fun createSocket(): Socket =
        FragSocket(delegate.createSocket(), plan, policy)

    override fun createSocket(host: String?, port: Int): Socket =
        FragSocket(delegate.createSocket(host, port), plan, policy)

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        FragSocket(delegate.createSocket(host, port, localHost, localPort), plan, policy)

    override fun createSocket(host: InetAddress?, port: Int): Socket =
        FragSocket(delegate.createSocket(host, port), plan, policy)

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        FragSocket(delegate.createSocket(address, port, localAddress, localPort), plan, policy)
}

internal class FragSocket(
    private val inner: Socket,
    private val plan: SplitPlan,
    private val policy: HostPolicy = HostPolicy.DEFAULT
) : Socket() {

    private var output: OutputStream? = null

    /** Итоговое решение по этому соединению; `null` — ещё не подключено/не решено. */
    @Volatile
    private var decision: SplitPlan? = null

    init {
        try {
            inner.tcpNoDelay = plan.tcpNoDelay
        } catch (_: Throwable) {
            // не критично: некоторые платформы не дают выставить
        }
        if (inner.isConnected) latch()
    }

    override fun getInputStream() = inner.getInputStream()

    override fun getOutputStream(): OutputStream = output ?: run {
        val effective = effectivePlan()
        val stream = if (effective.isActive()) FragOutputStream(inner.getOutputStream(), effective)
        else inner.getOutputStream()
        output = stream
        stream
    }

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        inner.connect(endpoint, timeout)
        latch()
    }

    override fun connect(endpoint: SocketAddress?) {
        inner.connect(endpoint)
        latch()
    }

    /**
     * Фиксирует решение по домену. До `connect()` remote IP неизвестен, поэтому
     * ничего не кэшируем и не блокируем соединение.
     */
    private fun latch() {
        if (!policy.isEnabled) return
        effectivePlan()
    }

    private fun effectivePlan(): SplitPlan {
        decision?.let { return it }
        if (!policy.isEnabled) return plan
        val address = try {
            inner.inetAddress
        } catch (_: Throwable) {
            null
        } ?: return plan
        val shouldFragment = ResolvedHostTable.shouldFragment(address)
        // null (хост не найден в таблице) — сохраняем прежнее поведение: фрагментируем.
        val chosen = if (shouldFragment == false) SplitPlan.NONE else plan
        decision = chosen
        return chosen
    }

    override fun bind(bindpoint: SocketAddress?) = inner.bind(bindpoint)
    override fun close() = inner.close()
    override fun isClosed(): Boolean = inner.isClosed
    override fun isConnected(): Boolean = inner.isConnected
    override fun isBound(): Boolean = inner.isBound
    override fun isInputShutdown(): Boolean = inner.isInputShutdown
    override fun isOutputShutdown(): Boolean = inner.isOutputShutdown
    override fun shutdownInput() = inner.shutdownInput()
    override fun shutdownOutput() = inner.shutdownOutput()
    override fun setSoTimeout(timeout: Int) { inner.soTimeout = timeout }
    override fun getSoTimeout(): Int = inner.soTimeout
    override fun setTcpNoDelay(on: Boolean) { inner.tcpNoDelay = on }
    override fun getTcpNoDelay(): Boolean = inner.tcpNoDelay
    override fun setKeepAlive(on: Boolean) { inner.keepAlive = on }
    override fun setSoLinger(on: Boolean, linger: Int) { inner.setSoLinger(on, linger) }
    override fun getInetAddress(): InetAddress? = inner.inetAddress
    override fun getPort(): Int = inner.port
    override fun getLocalAddress(): InetAddress = inner.localAddress
    override fun getLocalPort(): Int = inner.localPort
    override fun getRemoteSocketAddress(): SocketAddress? = inner.remoteSocketAddress
    override fun getLocalSocketAddress(): SocketAddress? = inner.localSocketAddress
    override fun setPerformancePreferences(connectionTime: Int, latency: Int, bandwidth: Int) {
        inner.setPerformancePreferences(connectionTime, latency, bandwidth)
    }
}

/**
 * Поток, который буферизует первый TLS-рекорд целиком (заголовок может прийти
 * отдельным write), при необходимости переупаковывает его и дальше пропускает
 * все байты без изменений.
 */
internal class FragOutputStream(
    private val out: OutputStream,
    private val plan: SplitPlan
) : OutputStream() {

    private val pending = java.io.ByteArrayOutputStream()
    private var passthrough = false
    private var header: TlsFraming.Header? = null
    private var expected = 0

    override fun write(b: Int) {
        if (passthrough) {
            out.write(b)
            out.flush()
            return
        }
        pending.write(b)
        process()
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (passthrough) {
            writeRaw(buffer, offset, length)
            return
        }
        pending.write(buffer, offset, length)
        process()
    }

    private fun process() {
        if (header == null) {
            if (pending.size() < TlsFraming.HEADER_SIZE) return
            val data = pending.toByteArray()
            val parsed = TlsFraming.parseHeader(data, data.size)
            if (parsed == null) {
                pending.reset()
                passthrough = true
                writeRaw(data, 0, data.size)
                return
            }
            header = parsed
            expected = TlsFraming.HEADER_SIZE + parsed.length
        }
        if (pending.size() < expected) return

        val data = pending.toByteArray()
        val recordHeader = header ?: return
        val record = data.copyOfRange(0, expected)
        val rest = data.copyOfRange(expected, data.size)
        emitRecord(recordHeader, record)
        pending.reset()
        passthrough = true
        if (rest.isNotEmpty()) writeRaw(rest, 0, rest.size)
    }

    private fun emitRecord(recordHeader: TlsFraming.Header, record: ByteArray) {
        val payload = record.copyOfRange(TlsFraming.HEADER_SIZE, record.size)
        val isHandshake = recordHeader.type == TlsFraming.TYPE_HANDSHAKE
        if (isHandshake && TlsFraming.isClientHello(payload, payload.size)) {
            for (part in clientHelloParts(recordHeader, payload)) {
                writeRaw(part, 0, part.size)
            }
        } else {
            writeRaw(record, 0, record.size)
        }
    }

    /**
     * Режет payload ClientHello на TLS-записи.
     *
     * Приоритет: SNI-aware разрез (границы host_name) → фиксированный
     * [SplitPlan.tlsRecordChunk] → одна запись без изменений.
     * Если SNI не найдён (редкий случай: ESNI/ECH, нестандартный клиент),
     * молча откатываемся на tlsRecordChunk, чтобы соединение не ломалось.
     */
    private fun clientHelloParts(header: TlsFraming.Header, payload: ByteArray): List<ByteArray> {
        if (plan.sniSplit != SniMode.NONE) {
            val cuts = TlsFraming.splitOffsets(payload, plan.sniSplit, plan.sniOffset)
            if (cuts.isNotEmpty()) {
                return TlsFraming.splitAtOffsets(
                    payload = payload,
                    offsets = cuts,
                    type = header.type,
                    major = header.major,
                    minor = header.minor
                )
            }
        }
        if (plan.tlsRecordChunk > 0) {
            return TlsFraming.splitIntoRecords(
                payload = payload,
                chunkSize = plan.tlsRecordChunk,
                type = header.type,
                major = header.major,
                minor = header.minor
            )
        }
        return TlsFraming.splitAtOffsets(payload, emptyList(), header.type, header.major, header.minor)
    }

    private fun writeRaw(data: ByteArray, offset: Int, length: Int) {
        val chunks = plan.chunks
        if (chunks.isEmpty()) {
            out.write(data, offset, length)
            out.flush()
            return
        }
        var position = offset
        var remaining = length
        var index = 0
        while (remaining > 0) {
            val size = chunks[index % chunks.size].coerceAtMost(remaining)
            out.write(data, position, size)
            out.flush()
            position += size
            remaining -= size
            index++
            if (plan.delayMs > 0) sleep()
        }
    }

    private fun sleep() {
        try {
            Thread.sleep(plan.delayMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    override fun flush() = out.flush()

    override fun close() {
        // Не теряем данные, если поток закрыли, не дописав заголовок/короткий пакет.
        if (!passthrough && pending.size() > 0) {
            val data = pending.toByteArray()
            pending.reset()
            passthrough = true
            writeRaw(data, 0, data.size)
        }
        out.close()
    }
}
