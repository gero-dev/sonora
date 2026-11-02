package app.sonora.data.recognition

import android.content.Context
import app.sonora.AppGraph
import app.sonora.core.model.Source
import app.sonora.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Фоновый поиск информации по уже распознанным названию и автору.
 *
 * После идентификации песни (шаг 1) запускается обогащение (шаг 2): метаданные
 * из встроенных провайдеров приложения (MusicBrainz/iTunes/Deezer/… с дисковым
 * кэшем) и варианты из источника YouTube. Всё это не трогает плеер и не
 * блокирует UI, ошибки и таймауты приводят к частичному результату.
 *
 * Кандидатам добавляется видео-id (для фоновой проверки коротким сниппетом) и
 * подпись аудио-фич из кэша телеметрии, если она уже посчитана — сеть здесь
 * не дёргаем, чтобы обогащение оставалось быстрым.
 */
internal class RecognitionEnricher(private val context: Context) {

    suspend fun enrich(track: RecognizedTrack): RecognitionEnrichment = withContext(Dispatchers.IO) {
        val graph = runCatching { AppGraph.get(context) }.getOrNull()
            ?: return@withContext RecognitionEnrichment(
                coverUrl = track.artworkUrl,
                album = track.album,
                done = true,
            )

        val lookupQuery = Track(
            id = "recognition:${track.artist}:${track.title}",
            title = track.title,
            artist = track.artist,
            album = track.album,
            source = Source.LOCAL,
            playable = false,
        )
        val metadata = withTimeoutOrNull(METADATA_TIMEOUT_MS) {
            runCatching { graph.metadata.lookup(lookupQuery) }.getOrNull()
        }
        val candidates = withTimeoutOrNull(YOUTUBE_TIMEOUT_MS) {
            runCatching { graph.youtube.search(track.searchQuery, YOUTUBE_LIMIT) }.getOrNull()
        }.orEmpty()
            .filter { it.title.isNotBlank() }
            .distinctBy { "${it.artist}|${it.title}" }
            .take(MAX_CANDIDATES)

        // Только кэш фич: сеть подтянет фоновая проверка, если пользователь её не выключил.
        val cachedFeatures = runCatching { graph.audioFeatures.cachedFeatures(candidates) }
            .getOrDefault(emptyMap())

        RecognitionEnrichment(
            coverUrl = metadata?.coverUrl ?: track.artworkUrl,
            album = metadata?.album ?: track.album,
            year = metadata?.year,
            genre = metadata?.genre,
            artistBio = metadata?.artistBio,
            candidates = candidates.map { candidate ->
                RecognitionCandidate(
                    title = candidate.title,
                    artist = candidate.artist,
                    sourceLabel = candidate.source.title,
                    subtitle = candidate.album,
                    trackId = candidate.id,
                    videoId = videoIdOf(candidate),
                    playable = candidate.playable,
                    audioFeatureLabel = cachedFeatures[candidate.id]
                        ?.shortLabel()
                        ?.takeIf { it.isNotBlank() },
                )
            },
            done = true,
        )
    }

    /**
     * Достаёт видео-id кандидата тем же способом, что и телеметрия, чтобы потом
     * можно было скачать короткий сниппет для проверки.
     */
    private fun videoIdOf(track: Track): String? {
        track.extra["youtubeId"]?.takeIf { it.isNotBlank() }?.let { return it }
        track.id.takeIf { it.startsWith("youtube:") }?.removePrefix("youtube:")
            ?.substringBefore('?')?.takeIf { it.isNotBlank() }?.let { return it }
        track.id.takeIf { it.startsWith("ytdlp:") }?.removePrefix("ytdlp:")
            ?.substringBefore('?')?.takeIf { it.isNotBlank() }?.let { return it }
        val uri = track.uri.orEmpty()
        if (uri.startsWith("innertube:")) return uri.removePrefix("innertube:").substringBefore('?')
        if (uri.startsWith("ytdlp:")) return uri.removePrefix("ytdlp:").substringBefore('?')
        return track.deepLink
            ?.let { Regex("""[?&]v=([A-Za-z0-9_-]{11})""").find(it) }
            ?.groupValues
            ?.getOrNull(1)
    }

    companion object {
        private const val METADATA_TIMEOUT_MS = 12_000L
        private const val YOUTUBE_TIMEOUT_MS = 10_000L
        private const val YOUTUBE_LIMIT = 6
        private const val MAX_CANDIDATES = 5
    }
}
