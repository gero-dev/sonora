package app.sonora.net.warp

import android.util.Log
import app.sonora.core.net.Http
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant

/**
 * Автоматическая регистрация свежего WARP-профиля.
 *
 * Зачем: конфиги, зашитые в APK, со временем перестают поднимать handshake
 * (Cloudflare меняет пиров/регистрации). Замеры на реальной сети РФ показали:
 * свежий WireGuard-профиль WARP подключается за ~5 секунд, а старые встроенные
 * конфиги висят «Нет handshake за 12 с». Поэтому Sonora умеет сама получить
 * новый профиль через публичный API регистрации Cloudflare WARP и импортировать
 * его как обычный `.conf` — без ручных файлов и без VPN-клиента.
 *
 * Приватный ключ генерируется на устройстве (Curve25519 из wireguard-lib),
 * наружу уходит только публичная часть. Это тот же механизм, что у wgcf.
 */
object WarpAutoRegistrar {

    private const val TAG = "WarpRegistrar"
    private const val REG_URL = "https://api.cloudflareclient.com/v0a2158/reg"
    private const val DEFAULT_ENDPOINT = "engage.cloudflareclient.com:2408"
    const val AUTO_CONFIG_PREFIX = "WARP Авто"
    const val AUTO_CONFIG_NAME = "WARP Авто (регистрация)"

    data class Registration(
        val configText: String,
        val endpoint: String,
        val accountId: String?,
    )

    /** Регистрирует новый анонимный WARP-профиль. null — сеть/API недоступны. */
    suspend fun register(): Registration? = withContext(Dispatchers.IO) {
        runCatching {
            val keyPair = KeyPair()
            val privateKey = keyPair.privateKey.toBase64()
            val publicKey = keyPair.publicKey.toBase64()

            val payload = buildJsonObject {
                put("key", publicKey)
                put("install_id", "")
                put("fcm_token", "")
                put("tos", Instant.now().toString())
                put("model", "PC")
                put("type", "Linux")
                put("locale", "en_US")
                put("warp_enabled", true)
            }.toString()

            val request = Request.Builder()
                .url(REG_URL)
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("Accept", "application/json")
                .header("User-Agent", "okhttp/3.12.1")
                .header("CF-Client-Version", "a-6.10-2158")
                .post(payload.toRequestBody("application/json; charset=UTF-8".toMediaType()))
                .build()

            // Отдельный клиент с короткими таймаутами: регистрация — не тот
            // запрос, на котором можно залипнуть на минуту.
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "регистрация HTTP ${response.code}: ${body.take(160)}")
                    return@runCatching null
                }
                val root = runCatching { Http.parseJson(body) }.getOrNull() as? JsonObject
                    ?: return@runCatching null
                val config = root["config"] as? JsonObject
                    ?: return@runCatching null
                val interfaceObject = config["interface"] as? JsonObject
                    ?: return@runCatching null
                val addresses = interfaceObject["addresses"] as? JsonObject
                val v4 = (addresses?.get("v4") as? JsonPrimitive)?.content?.trim()
                val v6 = (addresses?.get("v6") as? JsonPrimitive)?.content?.trim()
                val peers = config["peers"] as? JsonArray
                val peer = peers?.firstOrNull() as? JsonObject
                    ?: return@runCatching null
                val peerPublicKey = (peer["public_key"] as? JsonPrimitive)?.content
                    ?: return@runCatching null
                val endpointObject = peer["endpoint"] as? JsonObject
                val endpointHost = (endpointObject?.get("host") as? JsonPrimitive)?.content
                    ?.takeIf { it.isNotBlank() } ?: "engage.cloudflareclient.com"
                val endpoint = "$endpointHost:2408"

                val configText = buildString {
                    appendLine("[Interface]")
                    appendLine("PrivateKey = $privateKey")
                    v4?.takeIf { it.isNotBlank() }?.let { appendLine("Address = $it/32") }
                    v6?.takeIf { it.isNotBlank() }?.let { appendLine("Address = $it/128") }
                    appendLine("DNS = 1.1.1.1, 1.0.0.1")
                    appendLine("MTU = 1280")
                    appendLine("[Peer]")
                    appendLine("PublicKey = $peerPublicKey")
                    appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
                    appendLine("Endpoint = $endpoint")
                    appendLine("PersistentKeepalive = 25")
                }.trim()
                val accountId = (root["id"] as? JsonPrimitive)?.content
                Log.i(TAG, "профиль зарегистрирован, endpoint=$endpoint account=${accountId?.take(8)}")
                Registration(configText = configText, endpoint = endpoint, accountId = accountId)
            }
        }.getOrNull()
    }
}
