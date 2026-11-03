package app.sonora.data.import

import app.sonora.core.model.Source
import app.sonora.core.model.Track

/**
 * Индекс уже найденных онлайн-треков внутри одного импорта.
 *
 * Зачем. Экспорты часто повторяют одни и те же песни (один трек лежит и в
 * «Мне нравится», и в нескольких плейлистах). Без кэша каждая повторная строка
 * уходила бы в отдельный сетевой поиск — это и медленно, и провоцирует
 * ограничения со стороны каталога.
 *
 * Поиск словесный: строка запроса разбивается на слова, и трек подходит, если
 * содержит любое из них. Так «Nirvana Smells Like Teen Spirit» находит трек,
 * записанный в каталоге как «Smells Like Teen Spirit (Official Video)».
 *
 * Индекс намеренно не очищает себя сам: он живёт ровно столько, сколько идёт
 * один импорт, и вызывающий сам решает, когда его [clear].
 */
class RemoteTrackIndex {

    /** Кандидаты в порядке добавления: порядок важен для матчера. */
    private val candidates = ArrayList<Track>()

    /** id уже добавленных треков: повторное добавление игнорируется. */
    private val ids = HashSet<String>()

    /** Слово → позиции кандидатов, которые его содержат. */
    private val postings = HashMap<String, MutableList<Int>>()

    /** Сколько треков в индексе. */
    val size: Int get() = candidates.size

    /**
     * Добавляет треки. Только онлайн-треки (`source != LOCAL`); повторный id
     * игнорируется, поэтому индекс нельзя случайно раздуть дублями.
     */
    fun add(tracks: List<Track>) {
        for (track in tracks) {
            if (track.source == Source.LOCAL) continue
            if (!ids.add(track.id)) continue
            val position = candidates.size
            candidates += track
            for (token in tokensOf(track)) {
                postings.getOrPut(token) { ArrayList(2) }.add(position)
            }
        }
    }

    /**
     * Треки, в которых встречается хотя бы одно слово запроса, в порядке
     * добавления. Пустой запрос (или запрос без слов длиной от 3 символов)
     * возвращает все кандидаты: вызывающему нужен широкий список, чтобы
     * матчер сам выбрал лучший.
     */
    fun lookup(query: String): List<Track> {
        if (candidates.isEmpty()) return emptyList()
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return candidates.toList()

        // Собираем позиции и отдаём их по возрастанию: порядок добавления
        // сохраняется, потому что матчер при равном балле берёт первого.
        val hitPositions = sortedSetOf<Int>()
        for (token in tokens) {
            postings[token]?.let { hitPositions.addAll(it) }
        }
        if (hitPositions.isEmpty()) return emptyList()
        return hitPositions.map { candidates[it] }
    }

    /** Полностью очищает индекс. */
    fun clear() {
        candidates.clear()
        ids.clear()
        postings.clear()
    }

    /** Слова трека: название, исполнитель и альбом — всё, что видит матчер. */
    private fun tokensOf(track: Track): Set<String> {
        val text = buildString {
            append(track.title).append(' ')
            append(track.artist).append(' ')
            append(track.album.orEmpty())
        }
        return tokenize(text).toSet()
    }

    /**
     * Слова длиной от 3 символов, в нижнем регистре, с ё→е. Слишком короткие
     * слова («я», «the») давали бы ложные попадания почти на каждый трек.
     */
    private fun tokenize(value: String): List<String> {
        if (value.isBlank()) return emptyList()
        val out = ArrayList<String>(8)
        val current = StringBuilder()
        fun flush() {
            if (current.length >= MIN_TOKEN_LENGTH) out += current.toString()
            current.clear()
        }
        for (char in value.lowercase()) {
            val folded = if (char == 'ё') 'е' else char
            if (folded.isLetterOrDigit()) {
                current.append(folded)
            } else {
                flush()
            }
        }
        flush()
        return out
    }

    private companion object {
        const val MIN_TOKEN_LENGTH = 3
    }
}
