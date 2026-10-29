package app.sonora.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Результат проверки медиа-ссылки.
 *
 * YouTube отдаёт подписанный URL, который может быть валиден для одного IP и
 * клиента и отдавать 403 для другого, а в РФ провайдер может блокировать сам
 * `googlevideo`. Единственный надёжный способ узнать, заиграет ли ссылка, —
 * сделать настоящий Range-запрос и прочитать первые байты.
 */
data class ProbeResult(
    val ok: Boolean,
    val code: Int? = null,
    val bytes: Int = 0,
    val contentType: String? = null,
    val elapsedMs: Long = 0,
    val error: String? = null,
) {
    /** Причина отказа человеческим языком: пригодится в UI и логах. */
    fun reason(): String = when {
        ok -> "OK"
        code == 403 -> "403: медиа-сервер отклонил запрос (IP/клиент)"
        code == 410 || code == 416 -> "HTTP $code: ссылка устарела"
        code == 429 -> "HTTP 429: слишком много запросов"
        code != null && code >= 500 -> "HTTP $code: сервер источника недоступен"
        code != null -> "HTTP $code"
        error != null -> error
        else -> "нет ответа"
    }
}

/**
 * Проверка прямых медиа-ссылок маленьким Range-запросом.
 *
 * Проверяем именно тело, а не HEAD: часть CDN отвечает на HEAD 200, а на GET
 * отдаёт 403, и наоборот. Читаем 2 КБ и сразу закрываем — трафик минимальный.
 */
object StreamProbe {

    private const val PROBE_BYTES = 2048

    /** Отдельный клиент: у проверки короткие таймауты, чтобы не ждать минуту. */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(false)
            .build()
    }

    suspend fun probe(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 8_000L,
    ): ProbeResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val request = try {
            Request.Builder()
                .url(url)
                .header("Range", "bytes=0-${PROBE_BYTES - 1}")
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
        } catch (error: IllegalArgumentException) {
            return@withContext ProbeResult(
                ok = false,
                error = "некорректная ссылка",
                elapsedMs = System.currentTimeMillis() - started,
            )
        }
        try {
            client.newCall(request).execute().use { response ->
                val contentType = response.header("Content-Type")
                if (!response.isSuccessful) {
                    // Считываем чуть-чуть тела: иногда там диагностика источника.
                    runCatching { response.body?.source()?.skip(256) }
                    return@withContext ProbeResult(
                        ok = false,
                        code = response.code,
                        contentType = contentType,
                        elapsedMs = System.currentTimeMillis() - started,
                    )
                }
                val source = response.body?.source()
                val read = if (source == null) 0 else {
                    runCatching { source.read(ByteArray(PROBE_BYTES)) }.getOrDefault(0)
                }
                // Некоторые источники отдают 200 на пустое тело — это не поток.
                val ok = response.code == 206 || response.code == 200 && read > 0
                ProbeResult(
                    ok = ok,
                    code = response.code,
                    bytes = read,
                    contentType = contentType,
                    elapsedMs = System.currentTimeMillis() - started,
                )
            }
        } catch (error: Exception) {
            ProbeResult(
                ok = false,
                error = error.message?.take(120) ?: "сеть недоступна",
                elapsedMs = System.currentTimeMillis() - started,
            )
        }
    }

    /**
     * Быстрая проверка, что URL отвечает (без обязательного аудио-контента).
     * Нужна для health-check публичных зеркал: у них Range на видео может быть
     * недоступен, но JSON API — работать.
     */
    suspend fun probeAny(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): ProbeResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        try {
            val request = Request.Builder()
                .url(url)
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
            client.newCall(request).execute().use { response ->
                ProbeResult(
                    ok = response.isSuccessful,
                    code = response.code,
                    contentType = response.header("Content-Type"),
                    elapsedMs = System.currentTimeMillis() - started,
                )
            }
        } catch (error: Exception) {
            ProbeResult(
                ok = false,
                error = error.message?.take(120) ?: "сеть недоступна",
                elapsedMs = System.currentTimeMillis() - started,
            )
        }
    }
}
