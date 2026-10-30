package app.sonora.data.wave

import app.sonora.core.model.Track
import kotlin.random.Random

/**
 * Сортировка уже собранной волны. Чистая функция: одинаковый сид даёт
 * одинаковый порядок, поэтому SHUFFLE можно тестировать.
 *
 * `FOR_YOU` порядок не трогает — он уже задан [WaveRanker].
 */
object WaveSorter {

    fun sort(tracks: List<Track>, sort: WaveSort, seed: Long = 0L): List<Track> {
        if (tracks.size < 2) return tracks
        return when (sort) {
            WaveSort.FOR_YOU -> tracks
            WaveSort.TITLE -> tracks.sortedWith(
                compareBy({ it.title.lowercase() }, { it.artist.lowercase() }, { it.id }),
            )
            WaveSort.ARTIST -> tracks.sortedWith(
                compareBy({ it.artist.lowercase() }, { it.title.lowercase() }, { it.id }),
            )
            WaveSort.DURATION -> tracks.sortedWith(
                compareBy({ it.durationMs }, { it.title.lowercase() }, { it.id }),
            )
            WaveSort.SHUFFLE -> tracks.shuffled(Random(seed))
        }
    }
}
