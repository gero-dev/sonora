package app.sonora.data.remote.ytdlp

import app.sonora.core.model.Source
import app.sonora.core.model.Track
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Результат поиска yt-dlp. Хранится отдельно от [Track], чтобы слой поиска
 * не зависел от библиотеки: в очередь трек попадает как `ytdlp:<videoId>`,
 * а URL резолвится перед каждым открытием потока.
 */
data class YtTrack(
    val id: String,
    val title: String,
    val uploader: String? = null,
    val durationMs: Long = 0L,
    val url: String = "https://www.youtube.com/watch?v=$id",
) {
    fun toSonoraTrack(): Track = Track(
        id = "ytdlp:$id",
        title = title,
        artist = uploader.orEmpty(),
        durationMs = durationMs,
        uri = "ytdlp:$id",
        source = Source.YOUTUBE,
        playable = true,
        downloadAllowed = false,
        deepLink = url,
    )
}

/** Подмножество `--dump-json`; остальные поля игнорируются. */
@Serializable
internal data class YtDumpJson(
    val id: String = "",
    val title: String = "",
    val uploader: String? = null,
    val duration: Double? = null,
    val url: String? = null,
    val ext: String? = null,
    @SerialName("webpage_url") val webpageUrl: String? = null,
    @SerialName("http_headers") val httpHeaders: Map<String, String> = emptyMap(),
)

/** Прямой аудио-URL и заголовки, с которыми его нужно запрашивать. */
data class StreamHandle(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val mimeType: String = "audio/mp4",
)
