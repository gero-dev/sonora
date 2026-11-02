package app.sonora.data.import

import app.sonora.core.model.Source
import app.sonora.core.model.Track

/**
 * Индекс локальной библиотеки по нормализованному ключу «артист|название».
 *
 * Зачем. Импорт списка прогоняет через матчер каждую строку файла, а строк в
 * экспорте «Мне нравится» бывают тысячи. Полное сравнение с каждым локальным
 * треком — O(строк × треков); точное совпадение ключа закрывает самый частый
 * случай (в файле и в библиотеке написано одинаково) за O(1).
 *
 * Нормализация не дублируется: используется тот же [ImportTrackMatcher.normalize],
 * что и в самом матчере, поэтому индекс и матчер не могут разойтись в понимании
 * того, что считать одинаковыми названиями.
 *
 * @param tracks исходный список; в индекс попадают только треки, которые
 *   действительно могут быть кандидатом: `playable && source == LOCAL`.
 */
class LibraryTrackIndex(tracks: List<Track>) {

    /**
     * Проиндексированные треки в исходном порядке. Вызывающий использует этот
     * список для полного сравнения матчером, когда точный ключ не совпал, —
     * так «что считается локальным кандидатом» определяется в одном месте.
     */
    val tracks: List<Track> = tracks.filter { it.playable && it.source == Source.LOCAL }

    /** Сколько треков проиндексировано. */
    val size: Int get() = tracks.size

    /**
     * Ключ → первый трек с таким ключом. Повтор позже по списку не перезатирает
     * предыдущий: при равном ключе выигрывает тот, кто идёт раньше, — ровно как
     * в [ImportTrackMatcher.best], который при равном балле берёт первого.
     */
    private val byKey: Map<String, Track> = buildMap(tracks.size) {
        for (track in this@LibraryTrackIndex.tracks) {
            val key = keyOf(track.title, track.artist) ?: continue
            putIfAbsent(key, track)
        }
    }

    /**
     * Трек с точно совпадающим ключом «артист|название», или `null`.
     *
     * `null` также когда исполнитель пуст (тогда однозначного ключа нет) или
     * когда название после нормализации ничего не содержит.
     */
    fun exact(title: String, artist: String?): Track? {
        val key = keyOf(title, artist) ?: return null
        return byKey[key]
    }

    private fun keyOf(title: String, artist: String?): String? {
        val normalizedTitle = ImportTrackMatcher.normalize(title)
        if (normalizedTitle.isEmpty()) return null
        val normalizedArtist = ImportTrackMatcher.normalize(artist)
        if (normalizedArtist.isEmpty()) return null
        return "$normalizedArtist|$normalizedTitle"
    }
}
