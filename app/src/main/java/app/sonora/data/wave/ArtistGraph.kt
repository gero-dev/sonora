package app.sonora.data.wave

import app.sonora.core.model.Track

/**
 * Граф «похожих исполнителей» без внешнего API.
 *
 * Рёбра строятся по данным, которые уже есть на устройстве:
 *  * соседи по альбому (сборники, фиты, саундтреки);
 *  * общий жанр из метаданных (`extra["genre"]`, `extra["genres"]`);
 *  * явные подсказки `extra["similarArtists"]`.
 *
 * Граф используется рекомендациями: лайк артиста поднимает и его «соседей»,
 * дизлайк — приглушает, а радио по треку считает похожими исполнителей
 * из ближайшего окружения сида. Ничего не сохраняется: граф собирается по
 * текущей библиотеке за O(n) и живёт только на время сборки подборки.
 */
class ArtistGraph private constructor(
    private val neighbors: Map<String, Set<String>>,
) {

    val isEmpty: Boolean get() = neighbors.isEmpty()

    /** Прямые соседи исполнителя (без него самого). */
    fun related(artist: String): Set<String> =
        neighbors[normalize(artist)].orEmpty()

    fun isRelated(a: String, b: String): Boolean {
        val left = normalize(a)
        val right = normalize(b)
        if (left.isBlank() || right.isBlank() || left == right) return false
        return neighbors[left]?.contains(right) == true
    }

    companion object {

        val EMPTY: ArtistGraph = ArtistGraph(emptyMap())

        /** Максимум соседей на артиста: граф не должен превращаться в «все со всеми». */
        private const val MAX_DEGREE = 14

        /** Сборники/жанровые группы больше этого размера не соединяем кликой. */
        private const val MAX_CLIQUE = 24

        private val GENRE_SPLIT = Regex("""[,;/|&]+""")
        private val ARTIST_SPLIT = Regex("""[,;/|&]+""")

        fun of(tracks: List<Track>, maxDegree: Int = MAX_DEGREE): ArtistGraph {
            if (tracks.isEmpty()) return EMPTY
            val degree = maxDegree.coerceAtLeast(1)
            val edges = HashMap<String, MutableSet<String>>()

            fun link(a: String, b: String) {
                if (a.isBlank() || b.isBlank() || a == b) return
                val from = edges.getOrPut(a) { HashSet() }
                val to = edges.getOrPut(b) { HashSet() }
                if (from.size >= degree || to.size >= degree) return
                from += b
                to += a
            }

            fun linkGroup(group: Collection<String>) {
                val members = group.filter { it.isNotBlank() }.distinct()
                if (members.size < 2) return
                if (members.size > MAX_CLIQUE) {
                    // Большая жанровая группа: связываем «кольцом», чтобы не было O(n²).
                    members.forEachIndexed { index, artist ->
                        link(artist, members[(index + 1) % members.size])
                        link(artist, members[(index + 2) % members.size])
                    }
                    return
                }
                for (i in members.indices) {
                    for (j in i + 1 until members.size) link(members[i], members[j])
                }
            }

            val albums = HashMap<String, MutableSet<String>>()
            val genres = HashMap<String, MutableSet<String>>()

            tracks.forEach { track ->
                val artist = normalize(track.artist)
                if (artist.isBlank()) return@forEach
                val album = track.album?.trim()?.lowercase().orEmpty()
                if (album.isNotBlank()) {
                    albums.getOrPut(album) { HashSet() } += artist
                }
                genreValues(track).forEach { genre ->
                    genres.getOrPut(genre) { HashSet() } += artist
                }
                explicitSimilar(track).forEach { similar ->
                    link(artist, normalize(similar))
                }
            }

            albums.values.forEach { group -> linkGroup(group) }
            genres.values.forEach { group -> linkGroup(group) }
            return ArtistGraph(edges.mapValues { it.value.toSet() })
        }

        private fun genreValues(track: Track): List<String> {
            val raw = listOfNotNull(
                track.extra["genre"],
                track.extra["genres"],
                track.extra["mood"],
            ).joinToString(",")
            if (raw.isBlank()) return emptyList()
            return raw.lowercase()
                .replace('ё', 'е')
                .split(GENRE_SPLIT)
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.length in 3..40 }
                .distinct()
        }

        private fun explicitSimilar(track: Track): List<String> {
            val raw = track.extra["similarArtists"]
                ?: track.extra["similar_artists"]
                ?: return emptyList()
            if (raw.isBlank()) return emptyList()
            return raw.split(ARTIST_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }.take(8)
        }

        private fun normalize(artist: String): String =
            artist.trim().lowercase().replace('ё', 'е')
    }
}
