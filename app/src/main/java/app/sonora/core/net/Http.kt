package app.sonora.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Тонкая синхронная обёртка над OkHttp: все запросы выполняются на Dispatchers.IO.
 * Никаких ретраев-магии, чтобы поведение было предсказуемым.
 */
object Http {

    const val USER_AGENT = "Sonora/0.1 (Android)"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .apply {
                // NoHiT: локальный прокси с фрагментацией TLS ClientHello (DPI-обход без VPN).
                app.sonora.net.nohit.NohitProxy.proxyOrNull()?.let { proxy(it) }
            }
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw HttpException(response.code, body.take(300))
            }
            body
        }
    }

    suspend fun getJson(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): JsonElement = json.parseToJsonElement(get(url, headers))

    /** POST с JSON-телом: так общаются все запросы InnerTube. */
    suspend fun postJson(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): JsonObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        headers.forEach { (key, value) -> request.header(key, value) }
        client.newCall(request.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw HttpException(response.code, text.take(300))
            }
            json.parseToJsonElement(text).jsonObject
        }
    }

    suspend fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        val body = form.entries.joinToString("&") { (k, v) ->
            "${k.urlEncode()}=${v.urlEncode()}"
        }.toRequestBody("application/x-www-form-urlencoded".toMediaType())

        val builder = Request.Builder().url(url).post(body)
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw HttpException(response.code, text.take(300))
            }
            text
        }
    }

    fun parseJson(text: String): JsonElement = json.parseToJsonElement(text)

    fun parseJsonObject(text: String) = json.parseToJsonElement(text).jsonObject
}

class HttpException(val code: Int, message: String) : Exception("HTTP $code: $message")

internal fun String.urlEncode(): String =
    java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
