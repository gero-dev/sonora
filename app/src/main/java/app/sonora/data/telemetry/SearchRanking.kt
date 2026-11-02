package app.sonora.data.telemetry

import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.core.util.SearchText

/**
 * Один результат поиска с сигналами для переранжирования.
 *
 * [audioFeature] — фичи ReccoBeats из кэша (или уже посчитанные в фоне),
 * [localAvailable] — трек уже есть на устройстве, [recognitionScore] — уверенность,
 * если трек пришёл из распознавания/напетой мелодии.
 */
data class SearchRankItem(
    val track: Track,
    val audioFeature: AudioFeature? = null,
    val localAvailable: Boolean = false,
    val recognitionScore: Double? = null,
)

/**
 * Переранжирование результатов поиска: текстовое соответствие + локальная
 * доступность + уверенность распознавания + наличие аудио-фич.
 *
 * Зачем: онлайн-источник иногда отдаёт「похожие」результаты выше точного
 * совпадения, а трек, который уже есть на устройстве, удобнее показать первым
 * (мгновенное воспроизведение). Распознанный трек поднимается наверх, чтобы
 * после «поиска по мелодии» не искать его глазами.
 *
 * Чистый Kotlin без Android и сети — покрывается JVM-тестами.
 */
object SearchRanker {

    fun rank(query: String, items: List<SearchRankItem>): List<SearchRankItem> =
        items.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<SearchRankItem>> { score(query, it.value) }
                    .thenBy { it.index },
            )
            .map { it.value }

    /** Оценка релевантности (не нормирована, важны только порядок и разрывы). */
    fun score(query: String, item: SearchRankItem): Double {
        val normalizedQuery = SearchText.normalize(query)
        val track = item.track
        val title = SearchText.normalize(track.title)
        val artist = SearchText.normalize(track.artist)
        val album = SearchText.normalize(track.album.orEmpty())
        val tokens = SearchText.tokensOfNormalized(normalizedQuery).filter { it.length >= 2 }

        var textScore = if (tokens.isEmpty()) {
            0.5
        } else {
            var sum = 0.0
            for (token in tokens) {
                sum += when {
                    title.contains(token) -> 1.0
                    artist.contains(token) -> 0.9
                    album.contains(token) -> 0.6
                    else -> 0.0
                }
            }
            sum / tokens.size
        }
        if (normalizedQuery.isNotBlank()) {
            textScore += when {
                title == normalizedQuery -> 0.35
                title.startsWith(normalizedQuery) || normalizedQuery.startsWith(title) -> 0.20
                "$title $artist $album".contains(normalizedQuery) -> 0.12
                else -> 0.0
            }
        }

        val localBonus = if (item.localAvailable) 0.18 else 0.0
        val recognitionBonus = (item.recognitionScore ?: 0.0).coerceIn(0.0, 1.0) * 0.30
        val featureBonus = item.audioFeature?.let { FEATURE_BASE_BONUS + it.confidence * FEATURE_CONFIDENCE_BONUS }
            ?: 0.0
        return textScore + localBonus + recognitionBonus + featureBonus
    }

    /** Почему трек стоит здесь — для отладки/подписи в UI (не обязательно). */
    fun reason(query: String, item: SearchRankItem): String {
        val track = item.track
        val local = item.localAvailable || track.source == Source.LOCAL
        return buildList {
            if (local) add("на устройстве")
            item.recognitionScore?.takeIf { it > 0 }?.let { add("распознан ${(it * 100).toInt()}%") }
            item.audioFeature?.let { add("есть аудио-фичи") }
        }.joinToString(" · ").ifBlank { "по совпадению названия" }
    }

    private const val FEATURE_BASE_BONUS = 0.03
    private const val FEATURE_CONFIDENCE_BONUS = 0.04
}
