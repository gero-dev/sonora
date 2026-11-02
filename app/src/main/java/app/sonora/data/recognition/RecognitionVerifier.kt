package app.sonora.data.recognition

import android.content.Context
import android.net.Uri
import app.sonora.AppGraph
import app.sonora.core.model.Source
import app.sonora.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Итог фоновой проверки: то, что показываем в карточке результата. */
internal data class VerificationOutcome(
    val verification: RecognitionVerification,
    /** Локальные совпадения — контроллер добавляет их в альтернативы. */
    val localMatches: List<LocalMatch> = emptyList(),
)

/**
 * Фоновая проверка найденного совпадения — не блокирует UI и не трогает плеер.
 *
 * Что делает (всё на [Dispatchers.IO], с таймаутами и поддержкой отмены):
 *  1. сверяет 5–10-секундный фрагмент с локальной фонотекой по отпечатку;
 *  2. если есть кандидат из онлайн-источника и телеметрия включена — скачивает
 *     **короткий сниппет** (5–10 с) существующим сниппет-резолвером yt-dlp,
 *     декодирует его [LocalPcmDecoder] и сравнивает отпечатки;
 *  3. подтягивает аудио-фичи ReccoBeats для лучшего кандидата (из кэша/сети).
 *
 * Важно: здесь нет ни ExoPlayer, ни запроса аудио-фокуса, ни записи в историю —
 * проверка не может прервать или изменить текущее воспроизведение. Сниппет
 * удаляется сразу после анализа, полные треки не скачиваются.
 */
internal class RecognitionVerifier(private val context: Context) {

    suspend fun verify(
        fragment: AudioFingerprint,
        result: RecognitionState.Result,
    ): VerificationOutcome = withContext(Dispatchers.IO) {
        val graph = runCatching { AppGraph.get(context) }.getOrNull()

        val localMatches = withTimeoutOrNull(LOCAL_VERIFY_TIMEOUT_MS) {
            runCatching {
                LocalLibraryFingerprintIndex(context).identify(fragment, MAX_LOCAL_RESULTS)
            }.getOrDefault(emptyList())
        }.orEmpty()
        val bestLocal = localMatches.firstOrNull()

        val telemetryEnabled = graph?.settings?.current?.audioTelemetry == true
        var snippetChecked = false
        var snippetScore: Double? = null
        var snippetTitle: String? = null
        var snippetConfirmed = false
        val candidate = result.enrichment.candidates.firstOrNull {
            it.playable && !it.videoId.isNullOrBlank()
        }
        if (candidate != null && telemetryEnabled) {
            snippetChecked = true
            val videoId = candidate.videoId
            if (videoId != null) {
                val measuredScore = withTimeoutOrNull(SNIPPET_VERIFY_TIMEOUT_MS) {
                    runCatching { verifySnippet(graph, fragment, videoId) }.getOrNull()
                }
                snippetScore = measuredScore
                if (measuredScore != null && measuredScore >= FingerprintMatch.MIN_SCORE) {
                    snippetConfirmed = true
                    snippetTitle = candidate.displayTitle
                }
            }
        }

        var featuresSummary: String? = null
        val featureTrack = bestLocal?.track ?: candidate?.let { candidateToTrack(it) }
        if (featureTrack != null && telemetryEnabled) {
            featuresSummary = withTimeoutOrNull(FEATURES_TIMEOUT_MS) {
                runCatching {
                    graph?.audioFeatures?.featuresFor(featureTrack)?.shortLabel()?.ifBlank { null }
                }.getOrNull()
            }
        }

        val note = when {
            bestLocal != null && snippetConfirmed ->
                "Совпадение подтверждено: локальная копия и аудио кандидата."
            bestLocal != null ->
                "Та же запись найдена в вашей фонотеке — можно слушать сразу."
            snippetConfirmed ->
                "Аудио кандидата сверено с фрагментом."
            snippetChecked ->
                "Аудио кандидата не подтвердило совпадение — проверьте вариант вручную."
            telemetryEnabled ->
                "Локальной копии нет. Онлайн-кандидат не сверялся по аудио."
            else ->
                "Локальной копии нет. Телеметрия выключена — проверка по аудио пропущена."
        }

        VerificationOutcome(
            verification = RecognitionVerification(
                localAvailable = bestLocal != null,
                localTrackId = bestLocal?.track?.id,
                localScore = bestLocal?.score,
                snippetChecked = snippetChecked,
                snippetScore = snippetScore,
                snippetCandidateTitle = snippetTitle,
                featuresSummary = featuresSummary,
                note = note,
            ),
            localMatches = localMatches,
        )
    }

    /**
     * Скачивает короткий сниппет кандидата и сверяет его отпечаток с фрагментом.
     * Возвращает оценку 0..1 или null, если сниппет недоступен/не декодируется.
     */
    private suspend fun verifySnippet(
        graph: AppGraph?,
        fragment: AudioFingerprint,
        videoId: String,
    ): Double? {
        val resolver = graph?.ytDlp ?: return null
        val file = resolver.downloadSnippet(videoId, SNIPPET_SECONDS) ?: return null
        return try {
            val pcm = LocalPcmDecoder.decodeToMono16k(
                context,
                Uri.fromFile(file),
                SNIPPET_DECODE_MS,
            ) ?: return null
            if (pcm.size < AudioFingerprint.FRAME_SIZE) return null
            val fingerprint = AudioFingerprint.compute(pcm)
            if (fingerprint.isEmpty) return null
            AudioFingerprintIndex(fingerprint).match(fragment).score
        } finally {
            runCatching { file.delete() }
        }
    }

    private fun candidateToTrack(candidate: RecognitionCandidate): Track = Track(
        id = candidate.trackId ?: "recognition-candidate:${candidate.artist}:${candidate.title}",
        title = candidate.title,
        artist = candidate.artist,
        album = candidate.subtitle,
        source = Source.YOUTUBE,
        playable = candidate.playable,
        extra = candidate.videoId?.let { mapOf("youtubeId" to it) } ?: emptyMap(),
    )

    private companion object {
        const val LOCAL_VERIFY_TIMEOUT_MS = 120_000L
        const val SNIPPET_VERIFY_TIMEOUT_MS = 60_000L
        const val FEATURES_TIMEOUT_MS = 45_000L
        const val SNIPPET_SECONDS = 8
        const val SNIPPET_DECODE_MS = 10_000
        const val MAX_LOCAL_RESULTS = 4
    }
}
