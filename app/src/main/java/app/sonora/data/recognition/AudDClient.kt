package app.sonora.data.recognition

import app.sonora.core.net.Http
import app.sonora.core.net.HttpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Клиент AudD (https://api.audd.io).
 *
 * AudD — единственный проверенный вариант «распознать чужую песню по фрагменту»
 * с простым HTTP API без нативных библиотек. Ключ обязателен: 300 запросов дают
 * бесплатно при регистрации, дальше — платно (~$2–5 за 1000 запросов), поэтому
 * ключ хранится отдельно и вводится пользователем (см. [RecognitionSettings]).
 *
 * Контракт: multipart/form-data POST на https://api.audd.io/ с полями
 * `api_token` и `file` (WAV). Ответ: `{"status":"success","result":{...}}`,
 * при отсутствии совпадения `result` = null, при ошибке — `status:"error"`.
 */
class AudDClient(
    private val apiToken: String,
    private val endpoint: String = DEFAULT_ENDPOINT,
) {

    private val client by lazy {
        Http.client.newBuilder()
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Отправляет WAV-фрагмент на распознавание.
     * Никогда не бросает исключений: все сетевые проблемы возвращаются как [AudDOutcome.Failure].
     */
    suspend fun identify(fileName: String, wavBytes: ByteArray): AudDOutcome =
        withContext(Dispatchers.IO) {
            val token = apiToken.trim()
            if (token.isEmpty()) return@withContext AudDOutcome.MissingToken

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("api_token", token)
                .addFormDataPart("file", fileName, wavBytes.toRequestBody(WAV_MEDIA_TYPE))
                .build()
            val request = Request.Builder().url(endpoint).post(body).build()

            runCatching {
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        AudDOutcome.Failure(
                            "Сервис распознавания ответил HTTP ${response.code}. " +
                                "Проверьте ключ и соединение.",
                        )
                    } else {
                        AudDParser.parse(text)
                    }
                }
            }.getOrElse { error ->
                AudDOutcome.Failure(RecognitionErrors.describe(error))
            }
        }

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.audd.io/"
        private const val CALL_TIMEOUT_SECONDS = 40L
        private val WAV_MEDIA_TYPE = "audio/wav".toMediaType()
    }
}

/** Результат обращения к сервису распознавания. */
sealed interface AudDOutcome {

    data class Found(val track: RecognizedTrack) : AudDOutcome

    /** Сервис ответил, но совпадений нет. */
    data object NotFound : AudDOutcome

    /** Ключ не настроен — запрос даже не отправлялся. */
    data object MissingToken : AudDOutcome

    data class Failure(val message: String) : AudDOutcome
}

/**
 * Разбор JSON-ответа AudD. Чистая функция — вынесена отдельно, чтобы покрыть
 * её unit-тестами без Android и сети.
 */
object AudDParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(rawBody: String): AudDOutcome {
        val root = runCatching { json.parseToJsonElement(rawBody).jsonObject }.getOrElse {
            return AudDOutcome.Failure("Сервис вернул неожиданный ответ")
        }
        return when (root.str("status")) {
            "success" -> parseSuccess(root)
            "error" -> {
                val error = root.obj("error")
                val message = error?.str("error_message")
                val code = error?.int("error_code")
                AudDOutcome.Failure(
                    message ?: buildString {
                        append("Сервис вернул ошибку")
                        if (code != null) append(" ($code)")
                    },
                )
            }
            else -> AudDOutcome.Failure("Сервис вернул неожиданный ответ")
        }
    }

    private fun parseSuccess(root: JsonObject): AudDOutcome {
        // Стандартный метод возвращает объект; на всякий случай поддерживаем массив.
        val result: JsonObject = when (val element = root["result"]) {
            null, is JsonNull -> return AudDOutcome.NotFound
            is JsonObject -> element
            is JsonArray -> element.firstOrNull() as? JsonObject ?: return AudDOutcome.NotFound
            else -> return AudDOutcome.NotFound
        }
        val artist = result.str("artist").orEmpty()
        val title = result.str("title").orEmpty()
        if (artist.isBlank() && title.isBlank()) return AudDOutcome.NotFound
        return AudDOutcome.Found(
            RecognizedTrack(
                artist = artist.ifBlank { "Неизвестный исполнитель" },
                title = title.ifBlank { "Без названия" },
                album = result.str("album"),
                releaseDate = result.str("release_date"),
                label = result.str("label"),
                timecode = result.str("timecode"),
                songLink = result.str("song_link"),
                artworkUrl = artworkUrl(result),
                provider = "AudD",
            ),
        )
    }

    /** Обложка: Apple Music → Spotify → Deezer (что сервис успел вернуть). */
    private fun artworkUrl(result: JsonObject): String? {
        result.obj("apple_music")?.obj("artwork")?.str("url")
            ?.let { return it.replace("{w}x{h}", "600x600") }
        val spotifyImages = result.obj("spotify")?.obj("album")?.arr("images")
        spotifyImages?.firstOrNull()?.asObject()?.str("url")?.let { return it }
        val deezerAlbum = result.obj("deezer")?.obj("album")
        deezerAlbum?.str("cover_xl")?.let { return it }
        deezerAlbum?.str("cover_big")?.let { return it }
        return null
    }

    private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

    private fun JsonObject.obj(key: String): JsonObject? = this[key].asObject()

    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
}

/** Человекочитаемые тексты сетевых/системных ошибок. */
internal object RecognitionErrors {

    fun describe(error: Throwable): String = when (error) {
        is SocketTimeoutException -> "Сервис не ответил вовремя. Попробуйте ещё раз."
        is UnknownHostException -> "Нет соединения с интернетом."
        is ConnectException -> "Не удалось подключиться к сервису распознавания."
        is HttpException -> "Сервис распознавания ответил ошибкой HTTP ${error.code}."
        is SecurityException -> "Приложению не разрешён доступ к микрофону."
        is IOException -> "Сетевая ошибка: ${error.message ?: "проверьте соединение"}."
        else -> error.message?.takeIf { it.isNotBlank() } ?: "Не удалось выполнить распознавание."
    }
}
