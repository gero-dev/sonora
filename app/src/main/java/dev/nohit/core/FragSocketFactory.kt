package dev.nohit.core

import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * SocketFactory, которая режет первые байты исходящего потока на мелкие TCP-сегменты.
 *
 * Зачем: TLS ClientHello (с SNI) обычно уходит одним сегментом, и DPI читает SNI
 * из первого пакета. Если разбить ClientHello на куски по 1-4 байта, часть DPI
 * не собирает его обратно. Это НЕ панацея: современные ТСПУ умеют склеивать TCP,
 * поэтому план подбирается автоматически (см. AutoTuner).
 */
class FragSocketFactory private constructor(
    private val delegate: SocketFactory,
    private val plan: SplitPlan
) : SocketFactory() {

    companion object {
        fun create(plan: SplitPlan, delegate: SocketFactory = SocketFactory.getDefault()): SocketFactory =
            if (plan.chunks.isEmpty()) delegate else FragSocketFactory(delegate, plan)
    }

    override fun createSocket(): Socket =
        FragSocket(delegate.createSocket(), plan)

    override fun createSocket(host: String?, port: Int): Socket =
        FragSocket(delegate.createSocket(host, port), plan)

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        FragSocket(delegate.createSocket(host, port, localHost, localPort), plan)

    override fun createSocket(host: InetAddress?, port: Int): Socket =
        FragSocket(delegate.createSocket(host, port), plan)

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        FragSocket(delegate.createSocket(address, port, localAddress, localPort), plan)
}

internal class FragSocket(
    private val inner: Socket,
    private val plan: SplitPlan
) : Socket() {

    private var output: OutputStream? = null

    init {
        try {
            inner.tcpNoDelay = plan.tcpNoDelay
        } catch (_: Throwable) {
            // некоторые платформы не дают выставить — не критично
        }
    }

    override fun getInputStream() = inner.getInputStream()

    override fun getOutputStream(): OutputStream =
        output ?: FragOutputStream(inner.getOutputStream(), plan).also { output = it }

    override fun connect(endpoint: SocketAddress?, timeout: Int) = inner.connect(endpoint, timeout)
    override fun connect(endpoint: SocketAddress?) = inner.connect(endpoint)
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

internal class FragOutputStream(
    private val out: OutputStream,
    private val plan: SplitPlan
) : OutputStream() {

    private val chunks = plan.chunks
    private val headBytes = plan.headBytes
    private var written = 0
    private var chunkIndex = 0

    override fun write(b: Int) {
        out.write(b)
        written++
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        var position = offset
        var remaining = length
        while (remaining > 0) {
            if (written >= headBytes || chunks.isEmpty()) {
                out.write(buffer, position, remaining)
                written += remaining
                out.flush()
                return
            }
            val size = chunks[chunkIndex % chunks.size].coerceAtMost(remaining)
            out.write(buffer, position, size)
            out.flush()
            written += size
            position += size
            remaining -= size
            chunkIndex++
            if (plan.delayMs > 0) {
                try {
                    Thread.sleep(plan.delayMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        out.flush()
    }

    override fun flush() = out.flush()
    override fun close() = out.close()
}
