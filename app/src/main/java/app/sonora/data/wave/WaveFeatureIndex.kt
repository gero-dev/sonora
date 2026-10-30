package app.sonora.data.wave

import app.sonora.core.model.Track
import app.sonora.data.telemetry.AudioFeature

/**
 * Индекс аудио-фич ReccoBeats для кандидатов волны.
 *
 * Фичи ищутся дважды: по id трека и по ключу `исполнитель|название`. Второй
 * ключ нужен, потому что в кэше трек мог быть сохранён под другим id (локальная
 * копия стримингового трека, повторный импорт плейлиста и т. п.).
 *
 * Отдельно хранится «эталон» — фичи недавних дослушанных треков. Косинусное
 * сходство с этим эталоном поднимает в волне то, что похоже на реально
 * любимую пользователем музыку, а не только на «популярное».
 *
 * Класс чистый: никакой сети и Android, поэтому легко тестируется и
 * пересобирается один раз на сборку волны (никаких O(n²) по библиотеке).
 */
class WaveFeatureIndex private constructor(
    private val byId: Map<String, AudioFeature>,
    private val byKey: Map<String, AudioFeature>,
    private val seeds: List<AudioFeature>,
) {

    val size: Int get() = byId.size

    val seedCount: Int get() = seeds.size

    val isEmpty: Boolean get() = byId.isEmpty()

    /** Фичи конкретного трека; null, если ReccoBeats ещё не считал его. */
    fun forTrack(track: Track): AudioFeature? =
        byId[track.id] ?: byKey[WaveEngine.waveKey(track)]

    /** Похожесть трека на недавние дослушанные: 0..1 (0 — нет данных/не похоже). */
    fun similarityToSeeds(track: Track): Double {
        if (seeds.isEmpty()) return 0.0
        val feature = forTrack(track) ?: return 0.0
        var sum = 0.0
        var count = 0
        seeds.forEach { seed ->
            sum += AudioFeature.cosine(feature, seed).coerceIn(0.0, 1.0)
            count++
        }
        return if (count == 0) 0.0 else sum / count
    }

    /** Индекс с дополнительно найденными фичами (фоновое обогащение волны). */
    fun withFeatures(
        featuresById: Map<String, AudioFeature>,
        featuresByKey: Map<String, AudioFeature> = emptyMap(),
    ): WaveFeatureIndex {
        if (featuresById.isEmpty() && featuresByKey.isEmpty()) return this
        return WaveFeatureIndex(byId + featuresById, byKey + featuresByKey, seeds)
    }

    companion object {

        val EMPTY: WaveFeatureIndex = WaveFeatureIndex(emptyMap(), emptyMap(), emptyList())

        /**
         * Собирает индекс по кандидатам: берём только фичи, которые уже есть
         * в кэше, и эталон из истории (сначала дослушанные, потом свежие).
         */
        fun of(
            tracks: List<Track>,
            featuresById: Map<String, AudioFeature>,
            history: List<WaveHistoryEntry> = emptyList(),
            maxSeeds: Int = 20,
        ): WaveFeatureIndex {
            if (featuresById.isEmpty() && history.isEmpty()) return EMPTY
            val byId = HashMap<String, AudioFeature>(featuresById.size)
            val byKey = HashMap<String, AudioFeature>(featuresById.size)
            for (track in tracks) {
                val feature = featuresById[track.id] ?: continue
                byId[track.id] = feature
                byKey[WaveEngine.waveKey(track)] = feature
            }
            val seeds = ArrayList<AudioFeature>(maxSeeds.coerceAtLeast(0))
            if (byKey.isNotEmpty() && maxSeeds > 0) {
                // Сначала «понравившееся», затем просто свежее — так эталон
                // отражает вкус, а не случайный последний клик.
                val liked = history.asReversed().filter { entry ->
                    entry.completed || (entry.durationMs > 0L &&
                        entry.listenedMs * 10L >= entry.durationMs * 4L)
                }
                val ordered = liked + history.asReversed()
                for (entry in ordered) {
                    if (seeds.size >= maxSeeds) break
                    val key = WaveEngine.waveKeyOf(entry.artist, entry.title)
                    val feature = byKey[key] ?: continue
                    seeds += feature
                }
            }
            return WaveFeatureIndex(byId, byKey, seeds.distinct().toList())
        }

        /** Только эталон (например, если фичи есть у истории, но не у кандидатов). */
        fun withSeeds(seeds: List<AudioFeature>): WaveFeatureIndex =
            WaveFeatureIndex(emptyMap(), emptyMap(), seeds.toList())
    }
}
