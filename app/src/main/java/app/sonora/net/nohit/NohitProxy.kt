package app.sonora.net.nohit

import android.util.Log
import dev.nohit.core.FragOutputStream
import dev.nohit.core.NoHiT
import dev.nohit.core.NohitDns
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Локальный HTTP-прокси с фрагментацией TLS ClientHello (идея и код NoHiT,
 * Apache-2.0). Нужен потому, что:
 *
 *  - фрагментация на уровне `SocketFactory` не помогает Ktor/нативному
 *    Python-рантайму yt-dlp — им нужен обычный HTTP-прокси;
 *  - один локальный прокси покрывает сразу OkHttp, Ktor (InnerTubeX),
 *    ExoPlayer и yt-dlp (`--proxy`).
 *
 * Работает только на 127.0.0.1, наружу не слушает. DNS для апстрима берётся
 * из [NohitDns] (DoH/GeoHide), первые байты каждого CONNECT-туннеля режутся
 * планом [dev.nohit.core.SplitPlan] из конфигурации NoHiT.
 */
object NohitProxy {

    private const val TAG = "NohitProxy"
    private const val HEADER_LIMIT = 16 * 1024

    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "nohit-proxy").apply { isDaemon = true }
    }
    private val started = AtomicBoolean(false)

    @Volatile
    var port: Int = 0
        private set

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    private var resolverKey: String = ""

    @Volatile
    private var resolver: NohitDns? = null

    fun start(): Int {
        if (running) return port
        return runCatching {
            val socket = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
            server = socket
            port = socket.localPort
            running = true
            started.set(true)
            pool.execute { acceptLoop() }
            Log.i(TAG, "DPI-прокси запущен на 127.0.0.1:$port")
            port
        }.getOrElse {
            Log.w(TAG, "не удалось запустить прокси: ${it.message}")
            0
        }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        port = 0
        started.set(false)
    }

    /** Прокси для OkHttp/Ktor; null — если выключен. */
    fun proxyOrNull(): Proxy? =
        if (running && port > 0) Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)) else null

    /** Строка для yt-dlp `--proxy`. */
    fun proxyUrl(): String? = if (running && port > 0) "http://127.0.0.1:$port" else null

    // ------------------------------------------------------------------ inside

    private fun acceptLoop() {
        while (running) {
            val client = try {
                server?.accept()
            } catch (error: Exception) {
                if (running) Log.w(TAG, "accept: ${error.message}")
                null
            } ?: continue
            pool.execute { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            runCatching {
                socket.soTimeout = 20_000
                val input = socket.getInputStream()
                val requestLine = readLine(input) ?: return@runCatching
                val headers = readHeaders(input)
                if (!requestLine.startsWith("CONNECT ", ignoreCase = true)) {
                    writeSimple(socket, "HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\n\r\n")
                    return@runCatching
                }
                val target = requestLine.substringAfter(' ').substringBefore(' ').trim()
                val host = target.substringBeforeLast(':', target)
                val targetPort = target.substringAfterLast(':', "443").toIntOrNull() ?: 443
                if (host.isBlank()) {
                    writeSimple(socket, "HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n")
                    return@runCatching
                }
                val cfg = NoHiT.currentConfig()
                val upstream = connectUpstream(host, targetPort, cfg.timeoutsMs)
                if (upstream == null) {
                    writeSimple(socket, "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n")
                    return@runCatching
                }
                upstream.use { up ->
                    up.tcpNoDelay = cfg.split.tcpNoDelay
                    val response = "HTTP/1.1 200 Connection Established\r\n" +
                        "Proxy-Agent: Sonora-NoHiT\r\n\r\n"
                    socket.getOutputStream().apply {
                        write(response.toByteArray())
                        flush()
                    }
                    val clientToUpstream = FragOutputStream(up.getOutputStream(), cfg.split)
                    val t1 = Thread {
                        runCatching { copy(input, clientToUpstream) }
                        runCatching { up.shutdownOutput() }
                    }.apply { isDaemon = true; start() }
                    val t2 = Thread {
                        runCatching { copy(up.getInputStream(), socket.getOutputStream()) }
                        runCatching { socket.shutdownOutput() }
                    }.apply { isDaemon = true; start() }
                    t1.join(120_000)
                    t2.join(120_000)
                }
                // headers нужны только для отладки части запросов; не логируем содержимое.
                if (headers.isEmpty()) Unit
            }.onFailure { error ->
                if (running) Log.d(TAG, "connection: ${error.message}")
            }
        }
    }

    private fun connectUpstream(host: String, port: Int, timeoutMs: Int): Socket? {
        val cfg = NoHiT.currentConfig()
        val key = cfg.dns.joinToString(",") { it.id }
        if (key != resolverKey) {
            resolverKey = key
            resolver = NohitDns(cfg.dns, cfg.timeoutsMs)
        }
        val addresses = runCatching { resolver?.lookup(host) }.getOrNull()
            .orEmpty()
            .ifEmpty {
                runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
            }
        for (address in addresses) {
            val socket = Socket()
            if (runCatching {
                    socket.connect(InetSocketAddress(address, port), timeoutMs)
                    true
                }.getOrDefault(false)
            ) {
                return socket
            }
            runCatching { socket.close() }
        }
        return null
    }

    private fun readHeaders(input: InputStream): List<String> {
        val headers = ArrayList<String>(16)
        var total = 0
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            headers += line
            total += line.length
            if (total > HEADER_LIMIT) break
        }
        return headers
    }

    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder(64)
        var total = 0
        while (total < HEADER_LIMIT) {
            val value = input.read()
            if (value < 0) return if (builder.isEmpty()) null else builder.toString()
            total++
            if (value == '\n'.code) break
            if (value != '\r'.code) builder.append(value.toChar())
        }
        return builder.toString()
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = runCatching { input.read(buffer) }.getOrDefault(-1)
            if (count < 0) break
            runCatching {
                output.write(buffer, 0, count)
                output.flush()
            }.onFailure { break }
        }
    }

    private fun writeSimple(socket: Socket, text: String) {
        runCatching {
            val out = BufferedOutputStream(socket.getOutputStream())
            out.write(text.toByteArray())
            out.flush()
        }
    }
}
