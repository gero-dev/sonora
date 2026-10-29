package app.sonora.data.telemetry

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * Клиент ReccoBeats: бесплатный API аудио-аналитики без ключей.
 *
 * Два пути:
 *  1. по известному Spotify/ReccoBeats id — GET /v1/track/{id}/audio-features;
 *  2. универсальный — POST /v1/analysis/audio-features с 30-секундным MP3.
 *
 * Любая ошибка возвращает null: телеметрия не должна ломать воспроизведение.
 */
class ReccoBeatsClient {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 45_000
            socketTimeoutMillis = 45_000
        }
    }

    suspend fun byId(id: String): AudioFeature? = runCatching {
        parse(client.get("$BASE/v1/track/$id/audio-features").bodyAsText())
    }.getOrNull()

    suspend fun analyze(file: File): AudioFeature? = runCatching {
        val response = client.submitFormWithBinaryData(
            url = "$BASE/v1/analysis/audio-features",
            formData = formData {
                append(
                    key = "audioFile",
                    value = file.readBytes(),
                    headers = Headers.build {
                        append(HttpHeaders.ContentType, "audio/mpeg")
                        append(
                            HttpHeaders.ContentDisposition,
                            "filename=\"${file.name}\"",
                        )
                    },
                )
            },
        )
        parse(response.bodyAsText())
    }.getOrNull()

    /** Ответ бывает плоским или завёрнутым в `content`/`data`. */
    private fun parse(raw: String): AudioFeature? {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return null
        val candidates = listOfNotNull(
            root["content"]?.let { runCatching { it.jsonObject }.getOrNull() },
            root["data"]?.let { runCatching { it.jsonObject }.getOrNull() },
            root,
        )
        val obj: JsonObject = candidates.firstOrNull {
            it.containsKey("tempo") || it.containsKey("energy") || it.containsKey("danceability")
        } ?: return null
        return AudioFeature(
            tempo = obj.double("tempo"),
            energy = obj.double("energy"),
            danceability = obj.double("danceability"),
            valence = obj.double("valence"),
            acousticness = obj.double("acousticness"),
            instrumentalness = obj.double("instrumentalness"),
            liveness = obj.double("liveness"),
            speechiness = obj.double("speechiness"),
            loudness = obj.double("loudness"),
        ).takeIf { !it.isEmpty }
    }

    private fun JsonObject.double(key: String): Double? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.contentOrNull?.toDoubleOrNull()
    }

    private companion object {
        const val BASE = "https://api.reccobeats.com"
    }
}
