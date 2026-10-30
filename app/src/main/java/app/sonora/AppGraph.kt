package app.sonora

import android.content.Context
import android.net.Uri
import app.sonora.core.security.SecureStore
import kotlinx.coroutines.launch
import app.sonora.core.settings.SettingsStore
import app.sonora.data.discover.ArchiveDiscoverSource
import app.sonora.data.discover.DiscoverRepository
import app.sonora.data.discover.JamendoDiscoverSource
import app.sonora.data.download.DownloadManager
import app.sonora.data.import.PlaylistFileImporter
import app.sonora.data.import.TakeoutImporter
import app.sonora.data.library.LibraryRepository
import app.sonora.data.library.MediaStoreScanner
import app.sonora.data.metadata.MetadataCache
import app.sonora.data.metadata.MetadataLookup
import app.sonora.data.metadata.MetadataService
import app.sonora.data.remote.ConnectableSource
import app.sonora.data.remote.SourceRegistry
import app.sonora.data.remote.soundcloud.SoundCloudSource
import app.sonora.data.remote.spotify.SpotifySource
import app.sonora.data.remote.youtube.YouTubeSource
import app.sonora.data.wave.PlayHistoryStore
import app.sonora.playback.PlaybackController

/**
 * Ручной DI-контейнер: приложение небольшое, поэтому обходимся без Hilt.
 * Всё создаётся один раз на процесс.
 */
class AppGraph private constructor(context: Context) {

    val appContext: Context = context.applicationContext
    val settings = SettingsStore(appContext)
    val secure = SecureStore(appContext)

    /** yt-dlp: поиск/поток/фрагменты; настройки читает на каждый вызов. */
    val ytDlp = app.sonora.data.remote.ytdlp.YtDlpResolver(appContext, settings)

    /** ReccoBeats: аудио-фичи по 30-секундному фрагменту. */
    val audioFeatures = app.sonora.data.telemetry.AudioFeatureRepository(
        appContext,
        ytDlp,
        settings,
    )

    init {
        // Движок потоков InnerTubeX требует Context для кэша player-config.
        app.sonora.data.remote.youtube.innertube.InnerTubeXEngine.initialize(appContext)
        // Восстанавливаем cookie/visitorData Google-аккаунта после перезапуска.
        app.sonora.data.remote.youtube.innertube.InnerTubeSession.restore(secure)
        // yt-dlp — источник потока и резерв для ограниченных видео.
        app.sonora.data.remote.ytdlp.YtDlpBridge.init(ytDlp, settings)
        // Автопилот стриминга: проверяет прямой путь и yt-dlp через NoHiT.
        app.sonora.data.streaming.StreamingAutoPilot.init(appContext, settings, ytDlp)
        // Проверка обновлений из GitHub Releases (добровольная установка).
        app.sonora.data.update.UpdateManager.init(appContext, settings)

        // NoHiT: единственный DPI-обход — фрагментация TLS ClientHello и DoH-DNS.
        // Работает без VPN и применяется ко всему трафику Sonora: OkHttp, Ktor,
        // ExoPlayer, yt-dlp.
        runCatching { dev.nohit.core.NoHiT.init(appContext) }
            .onFailure { android.util.Log.w("NoHiT", "init: ${it.message}") }
        // NoHiT — единственный обход и он работает автоматически: не оставляем
        // пользователю выключателей, которые можно случайно сломать.
        if (!settings.current.nohitEnabled) {
            settings.update { it.copy(nohitEnabled = true) }
        }
        app.sonora.net.nohit.NohitProxy.start()
        maybeTuneNohit()
    }

    /**
     * Автоподбор стратегии NoHiT в фоне: раз в неделю и только если включён.
     * Подбор перебирает DNS и планы фрагментации реальными запросами — не
     * запускаем его на каждом старте.
     */
    private fun maybeTuneNohit() {
        val cfg = runCatching { dev.nohit.core.NoHiT.currentConfig() }.getOrNull()
        val strategyInactive = cfg == null ||
            (!cfg.split.isActive())
        val stale = System.currentTimeMillis() - settings.current.nohitTunedAtMs >
            24L * 60 * 60 * 1000
        if (!stale && !strategyInactive) return
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
        ).launch {
            // Пробуем подобрать рабочую стратегию автоматически; повтор — при
            // следующем запуске, если сеть не позволила.
            var report = runCatching { dev.nohit.core.AutoTuner.tune(appContext) }.getOrNull()
            if (report?.ok != true && cfg != null) {
                report = runCatching {
                    dev.nohit.core.AutoTuner.tune(
                        appContext,
                        base = cfg.copy(
                            split = dev.nohit.core.SplitPlan(
                                sniSplit = dev.nohit.core.SniMode.MID,
                                tlsRecordChunk = 128,
                            ),
                        ),
                    )
                }.getOrNull()
            }
            android.util.Log.i(
                "NoHiT",
                "tune: ok=${report?.ok} dns=${report?.dns?.name} strategy=${report?.strategyName} ${report?.latencyMs}ms",
            )
            settings.update { it.copy(nohitTunedAtMs = System.currentTimeMillis()) }
        }
    }

    val scanner = MediaStoreScanner(appContext)
    val downloads = DownloadManager(appContext, settings)
    val library = LibraryRepository(appContext, settings, scanner) {
        listOf(downloads.downloadDirectory())
    }

    val spotify = SpotifySource(appContext, settings, secure)
    val youtube = YouTubeSource()
    val soundcloud = SoundCloudSource(appContext, settings, secure)

    /** История прослушиваний/пропусков для «Моей волны» (файл play_history.json). */
    val playHistory = PlayHistoryStore(appContext)

    val sources = SourceRegistry(listOf(spotify, youtube, soundcloud))

    /** Импорт библиотеки YouTube Music из Google Takeout (CSV/ZIP), без API-ключей. */
    val takeout = TakeoutImporter(appContext)

    /** Импорт списков треков из CSV/JSON/M3U/TXT (Spotify, Apple Music, YouTube Music). */
    val playlistImporter = PlaylistFileImporter(appContext)

    // Открытые каталоги: поиск и скачивание свободной музыки (Archive.org, Jamendo).
    val archiveDiscover = ArchiveDiscoverSource(appContext)
    val jamendoDiscover = JamendoDiscoverSource(settings)
    val discover = DiscoverRepository(listOf(archiveDiscover, jamendoDiscover))

    // Онлайн-метаданные и тексты песен (MusicBrainz, Cover Art Archive,
    // LRCLIB, iTunes, Deezer, TheAudioDB, Wikipedia + InnerTube-fallback).
    // Всё найденное сразу пишется в постоянный кэш на диске.
    val metadataCache = MetadataCache(appContext)
    val metadata: MetadataLookup = MetadataService(metadataCache) { query ->
        youtube.search(query, 8)
    }

    /** Создаётся лениво: не дёргаем сервис воспроизведения при холодном старте. */
    val playback: PlaybackController by lazy { PlaybackController(appContext) }

    /**
     * Возвращает имя сервиса, если OAuth-колбэк успешно обработан.
     * Вызывается из MainActivity для ссылок sonora://oauth/<service>?code=...
     */
    suspend fun handleOAuth(uri: Uri): String? {
        val service = uri.path?.trimStart('/')?.lowercase() ?: return null
        val source: ConnectableSource = when (service) {
            "spotify" -> spotify
            "soundcloud" -> soundcloud
            else -> return null
        }
        return if (source.handleCallback(uri.toString())) source.source.title else null
    }

    companion object {
        @Volatile
        private var instance: AppGraph? = null

        fun get(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context).also { instance = it }
            }
    }
}
