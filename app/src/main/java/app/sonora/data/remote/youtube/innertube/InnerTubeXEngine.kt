package app.sonora.data.remote.youtube.innertube

import android.content.Context
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.PoTokenResult as InnerTubePoTokenResult
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import app.sonora.data.remote.youtube.innertube.potoken.PoTokenGenerator
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/**
 * Потоковый движок InnerTubeX — тот же стек, что в Metrolist:
 * перебор клиентов, деобфускация подписи/n-параметра и выбор аудиоформата.
 *
 * Поиск и разбор ответов остаются в [InnerTubeApi]: они простые и не требуют
 * тяжёлого движка. А вот воспроизведение доверяем InnerTubeX, потому что
 * YouTube всё чаще отдаёт только `signatureCipher`, который надо расшифровывать.
 */
internal object InnerTubeXEngine {

    private const val PLAYER_CONFIG_URL =
        "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/" +
            "library/src/main/assets/player_configs.json"
    private const val DEFAULT_TTL_MS = 15 * 60 * 1000L

    private var applicationContext: Context? = null
    private val logger = InnerTubeLogger.NONE

    private val httpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(20, TimeUnit.SECONDS)
                    readTimeout(30, TimeUnit.SECONDS)
                    writeTimeout(30, TimeUnit.SECONDS)
                    retryOnConnectionFailure(true)
                }
            }
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        explicitNulls = false
                        encodeDefaults = true
                    },
                )
            }
            install(ContentEncoding) {
                gzip(0.9f)
                deflate(0.8f)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 20_000
                socketTimeoutMillis = 30_000
            }
        }
    }

    private val innerTube: InnerTube by lazy { InnerTube(httpClient, logger = logger) }

    private val configRepository: PlayerConfigRepository by lazy {
        val context = applicationContext
        if (context == null) {
            PlayerConfigRepository.disabled()
        } else {
            AndroidPlayerConfigRepository(context)
        }
    }

    private val remoteConfigStore: RemotePlayerConfigStore by lazy {
        RemotePlayerConfigStore(httpClient, configRepository, logger)
    }

    private val cipherService: YouTubeCipherService by lazy {
        YouTubeCipherService(httpClient, remoteConfigStore, logger)
    }

    /**
     * PoToken через замаскированный WebView (BotGuard), как в Metrolist.
     * WebView создаётся лениво и только когда есть Context приложения.
     */
    private val poTokenGenerator: PoTokenGenerator? by lazy {
        applicationContext?.let { PoTokenGenerator(it) }
    }

    private val tokenProvider = object : TokenProvider {
        override val capabilities = TokenProviderCapabilities(
            providers = setOf(PoTokenProviderKind.WEB_BOTGUARD),
            usesWebView = true,
        )

        override suspend fun getPoToken(
            videoId: String,
            visitorData: String,
            cookie: String?,
        ): InnerTubePoTokenResult? {
            val generator = poTokenGenerator ?: return null
            val token = try {
                generator.getWebClientPoToken(videoId, visitorData)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                // Сломанный WebView не должен ломать воспроизведение: движок
                // откатится на клиентов без токена.
                null
            } ?: return null
            return InnerTubePoTokenResult(
                playerRequestToken = token.playerRequestPoToken,
                streamingDataToken = token.streamingDataPoToken,
                visitorData = visitorData,
            )
        }

        override suspend fun close() {
            poTokenGenerator?.close()
        }
    }

    private val extractor: InnerTubeExtractor by lazy {
        InnerTubeExtractor(
            configParser = YtConfigParserImpl(
                httpClient = httpClient,
                innerTube = innerTube,
                remotePlayerConfigStore = remoteConfigStore,
                logger = logger,
                cipherService = cipherService,
            ),
            cipherService = cipherService,
            innerTube = innerTube,
            tokenProvider = tokenProvider,
            logger = logger,
        )
    }

    fun initialize(context: Context) {
        if (applicationContext == null) {
            applicationContext = context.applicationContext
        }
    }

    /** Прогревает движок: первый трек не ждёт компиляции плеера. */
    suspend fun prewarm() {
        runCatching { extractor.prewarm() }
    }

    suspend fun resolve(
        videoId: String,
        quality: AudioQuality = AudioQuality.HIGH,
    ): StreamInfo {
        // SABR ExoPlayer не умеет: просим обычный progressive-поток.
        val hints = ContentHints(wantVideo = false)
            .withStreamCapabilities(
                allowHls = false,
                allowSabr = false,
                allowBoundedRange = true,
            )
        val stream = extractor.extract(
            videoId = videoId,
            hints = hints,
            audioQuality = quality,
            clientPlaybackNonce = generateClientPlaybackNonce(),
        ) ?: throw IllegalStateException("InnerTubeX не вернул аудиопоток")

        check(stream.sabrBootstrap == null) { "InnerTubeX вернул SABR-поток" }

        val mime = buildString {
            append(stream.mimeType.orEmpty())
            stream.codecs?.takeIf { it.isNotBlank() }?.let { codecs ->
                if (isNotEmpty()) append("; ")
                append("codecs=\"").append(codecs).append('"')
            }
        }
        val expiresAt = runCatching { stream.expiresAt?.toEpochMilliseconds() }.getOrNull()
            ?: (System.currentTimeMillis() + DEFAULT_TTL_MS)

        return StreamInfo(
            videoId = videoId,
            url = stream.audioUrl,
            mimeType = mime,
            bitrate = stream.bitrate ?: 0,
            itag = stream.itag,
            expiresAtMs = expiresAt,
            headers = stream.headers,
            contentLength = stream.contentLengthBytes?.takeIf { it > 0L },
            requireBoundedRange = stream.requireBoundedRange,
            rangeChunkSizeBytes = stream.rangeChunkSizeBytes,
            useRangeChunks = stream.useRangeChunks,
        )
    }

    private class AndroidPlayerConfigRepository(context: Context) : PlayerConfigRepository {
        private val prefs = context.applicationContext
            .getSharedPreferences("sonora_innertubex_config", Context.MODE_PRIVATE)

        override val enabled: Boolean = true
        override val sourceUrl: String = PLAYER_CONFIG_URL
        override val defaultSourceUrl: String = PLAYER_CONFIG_URL

        override var cachedJson: String
            get() = prefs.getString(KEY_JSON, "").orEmpty()
            set(value) = prefs.edit().putString(KEY_JSON, value).apply()

        override var cachedAtMs: Long
            get() = prefs.getLong(KEY_CACHED_AT, 0L)
            set(value) = prefs.edit().putLong(KEY_CACHED_AT, value).apply()

        override var cachedSourceUrl: String
            get() = prefs.getString(KEY_SOURCE_URL, "").orEmpty()
            set(value) = prefs.edit().putString(KEY_SOURCE_URL, value).apply()

        override var cachedEtag: String
            get() = prefs.getString(KEY_ETAG, "").orEmpty()
            set(value) = prefs.edit().putString(KEY_ETAG, value).apply()

        private companion object {
            const val KEY_JSON = "json"
            const val KEY_CACHED_AT = "cached_at_ms"
            const val KEY_SOURCE_URL = "source_url"
            const val KEY_ETAG = "etag"
        }
    }
}
