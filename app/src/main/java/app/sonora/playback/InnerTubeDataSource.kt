package app.sonora.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import app.sonora.core.settings.AudioQuality
import app.sonora.data.remote.youtube.innertube.InnerTube
import app.sonora.data.remote.youtube.innertube.InnerTubeStreams
import app.sonora.data.remote.ytdlp.YtDlpBridge
import java.io.IOException

/**
 * Источник данных для `innertube:<videoId>`.
 *
 * ExoPlayer видит обычный MediaItem с URI, а при открытии мы подменяем его
 * на прямой аудио-URL InnerTube. Побочный эффект: очередь, seek, уведомление
 * и виджет работают без отдельного «резолвера перед play».
 *
 * [quality] читается при каждом открытии потока, поэтому смена качества
 * в настройках применяется к следующему треку без пересоздания сервиса.
 */
@OptIn(UnstableApi::class)
class InnerTubeDataSourceFactory(
    context: Context,
    private val quality: () -> AudioQuality = { AudioQuality.AUTO },
) : DataSource.Factory {

    private val appContext = context.applicationContext

    // Медиа-поток тоже идёт через общий OkHttp-клиент Sonora, к которому
    // подключён NoHiT-прокси: DPI-обход работает и для ExoPlayer.
    private val httpFactory = OkHttpDataSource.Factory(app.sonora.core.net.Http.client)
        .setUserAgent(InnerTube.USER_AGENT_WEB)

    private val upstreamFactory = DefaultDataSource.Factory(appContext, httpFactory)

    private val resolver = ResolvingDataSource.Resolver { dataSpec ->
        val raw = dataSpec.uri.toString()

        // yt-dlp: ссылка резолвится в момент открытия потока, поэтому
        // протухшие URL не живут в очереди.
        if (raw.startsWith("ytdlp:", ignoreCase = true)) {
            val videoId = raw.removePrefix("ytdlp:").substringBefore('?').trim()
            if (videoId.isBlank()) {
                throw IOException("Пустой yt-dlp id")
            }
            val handle = YtDlpBridge.resolveBlocking(videoId)
                ?: throw IOException("yt-dlp не смог получить поток для $videoId")
            return@Resolver dataSpec
                .withUri(Uri.parse(handle.url))
                .withRequestHeaders(dataSpec.httpRequestHeaders + handle.headers)
        }

        val videoId = InnerTubeStreams.videoIdOf(raw)
        if (videoId == null) {
            dataSpec
        } else {
            val stream = try {
                InnerTubeStreams.resolveBlocking(videoId, quality())
            } catch (error: Exception) {
                throw IOException(
                    error.message ?: "Не удалось открыть поток YouTube",
                    error,
                )
            }
            val headers = LinkedHashMap<String, String>().apply {
                putAll(dataSpec.httpRequestHeaders)
                putAll(stream.headers)
            }
            val resolved = dataSpec
                .withUri(Uri.parse(stream.url))
                .withRequestHeaders(headers)
            if (!stream.needsRangeRequests) {
                resolved
            } else {
                // Некоторые клиенты InnerTubeX отдают поток только bounded-range
                // кусками. ExoPlayer просит DataSpec без длины — ограничиваем.
                val chunk = stream.rangeChunkSizeBytes
                val length = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                    chunk
                } else {
                    minOf(dataSpec.length, chunk)
                }
                resolved.subrange(0, length)
            }
        }
    }

    override fun createDataSource(): DataSource =
        ResolvingDataSource.Factory(upstreamFactory, resolver).createDataSource()
}
