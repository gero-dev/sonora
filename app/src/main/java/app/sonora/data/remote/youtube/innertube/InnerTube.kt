package app.sonora.data.remote.youtube.innertube

import app.sonora.core.net.Http
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Locale

/**
 * Один клиент InnerTube: имя, версия, User-Agent и параметры контекста.
 * Значения взяты из профилей Metrolist/InnerTubeX (WEB_REMIX, ANDROID_VR, IOS),
 * чтобы запросы совпадали с тем, что реально работает в Metrolist.
 */
internal data class ClientProfile(
    val name: String,
    val version: String,
    /** Числовой id клиента для заголовка X-YouTube-Client-Name. */
    val id: String,
    val userAgent: String,
    val osName: String? = null,
    val osVersion: String? = null,
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val androidSdkVersion: Int? = null,
    val includeUserAgentInContext: Boolean = false,
)

/**
 * Тонкий клиент к `music.youtube.com/youtubei/v1`.
 *
 * Никакого API-ключа и OAuth: тот же анонимный InnerTube, которым пользуется
 * Metrolist. Ответы парсятся вручную, потому что Sonora не тянет весь
 * многомегабайтный модуль Metrolist.
 */
internal object InnerTube {

    const val ORIGIN = "https://music.youtube.com"
    private const val API_BASE = "$ORIGIN/youtubei/v1"

    /** UA веб-клиента: с ним YouTube отдаёт стабильные ответы browse/search. */
    const val USER_AGENT_WEB =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    /** Основной клиент для поиска, плейлистов, текстов и рекомендаций. */
    val WEB_REMIX = ClientProfile(
        name = "WEB_REMIX",
        version = "1.20260707.12.00",
        id = "67",
        userAgent = USER_AGENT_WEB,
    )

    /** Клиент, который отдаёт прямые аудио-URL без PoToken. */
    val ANDROID_VR = ClientProfile(
        name = "ANDROID_VR",
        version = "1.65.10",
        id = "28",
        userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 " +
            "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
        osName = "Android",
        osVersion = "12L",
        deviceMake = "Oculus",
        deviceModel = "Quest 3",
        androidSdkVersion = 32,
        includeUserAgentInContext = true,
    )

    /** Более старый Android VR: другой набор форматов, полезен как fallback. */
    val ANDROID_VR_LEGACY = ClientProfile(
        name = "ANDROID_VR",
        version = "1.61.48",
        id = "28",
        userAgent = "com.google.android.apps.youtube.vr.oculus/1.61.48 " +
            "(Linux; U; Android 12; en_US; Oculus Quest 3; " +
            "Build/SQ3A.220605.009.A1; Cronet/132.0.6808.3)",
        osName = "Android",
        osVersion = "12",
        deviceMake = "Oculus",
        deviceModel = "Quest 3",
        androidSdkVersion = 32,
        includeUserAgentInContext = true,
    )

    /** iOS-клиент: второй независимый источник прямых ссылок. */
    val IOS = ClientProfile(
        name = "IOS",
        version = "21.26.4",
        id = "5",
        userAgent = "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
        osName = "iPhone",
        osVersion = "18.3.2.22D82",
        deviceMake = "Apple",
        deviceModel = "iPhone16,2",
        includeUserAgentInContext = true,
    )

    val language: String
        get() = Locale.getDefault().language
            .lowercase(Locale.ROOT)
            .takeIf { it.length in 2..3 }
            ?: "en"

    val region: String
        get() = Locale.getDefault().country
            .uppercase(Locale.ROOT)
            .takeIf { it.length == 2 }
            ?: "US"

    /**
     * POST на endpoint InnerTube. Контекст клиента подставляется автоматически,
     * поля конкретного запроса передаются в [payload].
     */
    suspend fun call(
        endpoint: String,
        profile: ClientProfile = WEB_REMIX,
        apiKey: String? = null,
        payload: JsonObject,
    ): JsonObject {
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", profile.name)
                    put("clientVersion", profile.version)
                    if (profile.includeUserAgentInContext) {
                        put("userAgent", profile.userAgent)
                    }
                    profile.osName?.let { put("osName", it) }
                    profile.osVersion?.let { put("osVersion", it) }
                    profile.deviceMake?.let { put("deviceMake", it) }
                    profile.deviceModel?.let { put("deviceModel", it) }
                    profile.androidSdkVersion?.let { put("androidSdkVersion", it) }
                    put("gl", region)
                    put("hl", language)
                    InnerTubeSession.visitorData?.let { put("visitorData", it) }
                }
                putJsonObject("user") {
                    put("lockedSafetyMode", false)
                    InnerTubeSession.onBehalfOfUser?.let { put("onBehalfOfUser", it) }
                }
                putJsonObject("request") {
                    put("useSsl", true)
                }
            }
            payload.forEach { (key, value) -> put(key, value) }
        }

        val headers = mapOf(
            "Content-Type" to "application/json; charset=utf-8",
            "User-Agent" to profile.userAgent,
            "X-YouTube-Client-Name" to profile.id,
            "X-YouTube-Client-Version" to profile.version,
            "Origin" to ORIGIN,
            "Referer" to "$ORIGIN/",
            "Accept" to "application/json",
            "Accept-Language" to "$language-$region, $language;q=0.8",
        ) + InnerTubeSession.authHeaders()

        val url = buildString {
            append(API_BASE)
            append('/')
            append(endpoint)
            append("?prettyPrint=false")
            if (!apiKey.isNullOrBlank()) {
                append("&key=")
                append(apiKey)
            }
        }
        return Http.postJson(url, body.toString(), headers)
    }

    fun streamHeaders(profile: ClientProfile): Map<String, String> = mapOf(
        "User-Agent" to profile.userAgent,
        "Accept-Language" to "$language-$region, $language;q=0.8",
    )
}
