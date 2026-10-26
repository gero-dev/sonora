package app.sonora.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import app.sonora.data.remote.youtube.innertube.InnerTube
import app.sonora.data.remote.youtube.innertube.InnerTubeStreams
import java.io.IOException

/**
 * Источник данных для `innertube:<videoId>`.
 *
 * ExoPlayer видит обычный MediaItem с URI, а при открытии мы подменяем его
 * на прямой аудио-URL InnerTube. Побочный эффект: очередь, seek, уведомление
 * и виджет работают без отдельного «резолвера перед play».
 */
@OptIn(UnstableApi::class)
class InnerTubeDataSourceFactory(context: Context) : DataSource.Factory {

    private val appContext = context.applicationContext

    private val httpFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(InnerTube.USER_AGENT_WEB)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(20_000)
        .setReadTimeoutMs(30_000)

    private val upstreamFactory = DefaultDataSource.Factory(appContext, httpFactory)

    private val resolver = ResolvingDataSource.Resolver { dataSpec ->
        val videoId = InnerTubeStreams.videoIdOf(dataSpec.uri.toString())
        if (videoId == null) {
            dataSpec
        } else {
            val stream = try {
                InnerTubeStreams.resolveBlocking(videoId)
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
