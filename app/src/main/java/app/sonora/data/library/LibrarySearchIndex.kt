package app.sonora.data.library

import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.core.util.SearchText

/**
 * Неизменяемый поисковый индекс по локальной фонотеке.
 *
 * Строится один раз на версию библиотеки (см. version-счётчик в LibraryRepository):
 * заранее нормализованные title/artist/album + инвертированный индекс токенов.
 * [search] больше не нормализует всю библиотеку на каждый запрос: он лишь
 * подмешивает кандидатов из индекса, считает те же score, что и раньше, и
 * сортирует так же (score ↓, затем название).
 *
 * Ранжирование сохранено 1:1 с прежним `LibraryRepository.searchLocal`:
 * title == q (100) → title.startsWith (80) → artist == q (70) → artist.startsWith (60)
 * → title.contains (50) → artist.contains (40) → album.contains (30).
 *
 * Все поля immutable, поэтому поиск можно вызывать из любого потока.
 */
class LibrarySearchIndex private constructor(
    private val entries: List<Entry>,
    private val tokenKeys: Array<String>,
    private val postings: Map<String, IntArray>,
) {

    /** Одна запись индекса: трек + заранее нормализованные поля. */
    private class Entry(
        val track: Track,
        /** normalize(title). */
        val title: String,
        /** normalize(artist). */
        val artist: String,
        /** normalize(album) (пустая строка, если альбома нет). */
        val album: String,
        /** Прежний ключ сортировки при равных score — title.lowercase(). */
        val titleLower: String,
    )

    private class Scored(
        val score: Int,
        val order: Int,
        val track: Track,
        val titleLower: String,
    )

    /**
     * Поиск по локальной библиотеке. Поведение повторяет прежний searchLocal:
     * пустой запрос → пусто; результаты отсортированы по score, при равенстве —
     * по названию трека (lowercase), при полном равенстве сохраняется порядок
     * библиотеки.
     */
    fun search(query: String): List<Track> {
        val q = SearchText.normalize(query)
        if (q.isEmpty()) return emptyList()
        val direct = searchNormalized(q)
        if (direct.isNotEmpty()) return direct
        // Транслитерация — только как резерв, когда обычный поиск ничего не дал:
        // «nirvana» находит «Нирвана» и наоборот, но это не расширяет и не
        // переупорядочивает результаты, которые находились и раньше.
        val transliterated = SearchText.transliterate(q)
        if (transliterated.isEmpty()) return emptyList()
        return searchTransliterated(transliterated)
    }

    // ---------------------------------------------------------------- обычный поиск

    private fun searchNormalized(q: String): List<Track> {
        val scored = ArrayList<Scored>()
        val candidates = candidateOrdinals(q)
        if (candidates == null) {
            entries.forEachIndexed { order, entry -> scoreEntry(entry, order, q, scored) }
        } else {
            candidates.forEach { order -> scoreEntry(entries[order], order, q, scored) }
        }
        return rank(scored)
    }

    private fun scoreEntry(entry: Entry, order: Int, q: String, out: MutableList<Scored>) {
        val score = scoreOf(entry.title, entry.artist, entry.album, q)
        if (score > 0) out += Scored(score, order, entry.track, entry.titleLower)
    }

    /** Та же таблица score, что и в прежней реализации поиска. */
    private fun scoreOf(title: String, artist: String, album: String, q: String): Int = when {
        title == q -> 100
        title.startsWith(q) -> 80
        artist == q -> 70
        artist.startsWith(q) -> 60
        title.contains(q) -> 50
        artist.contains(q) -> 40
        album.contains(q) -> 30
        else -> 0
    }

    /**
     * Номера записей-кандидатов по инвертированному индексу токенов.
     *
     * Возвращает `null`, если сузить выборку нельзя/незачем (короткий запрос,
     * нет токенов, пересечение пусто) — тогда точный score считается по всем
     * записям, как раньше. Возвращённый набор всегда надмножество настоящих
     * совпадений: для каждой записи, где встречается вся строка запроса,
     * в её полях найдётся токен, содержащий токен запроса.
     */
    private fun candidateOrdinals(q: String): IntArray? {
        if (q.length < 2) return null
        val queryTokens = SearchText.tokensOfNormalized(q)
        if (queryTokens.isEmpty()) return null
        var current: MutableSet<Int>? = null
        for (token in queryTokens) {
            val matched = HashSet<Int>()
            for (key in tokenKeys) {
                if (!key.contains(token)) continue
                val ordinals = postings[key] ?: continue
                for (ordinal in ordinals) matched.add(ordinal)
            }
            val previous = current
            val narrowed: MutableSet<Int> = if (previous == null) {
                matched
            } else {
                previous.retainAll(matched)
                previous
            }
            current = narrowed
            // Пустое пересечение означает «совпадений нет»; полный скан ещё раз
            // проверит это точным score — безопасный fallback.
            if (narrowed.isEmpty()) return null
        }
        return current?.toIntArray()
    }

    private fun rank(scored: List<Scored>): List<Track> {
        if (scored.isEmpty()) return emptyList()
        return scored
            .sortedWith(
                compareByDescending<Scored> { it.score }
                    .thenBy { it.titleLower },
            )
            .map { it.track }
    }

    // ---------------------------------------------------------------- транслитерация

    private fun searchTransliterated(latinQ: String): List<Track> {
        val scored = ArrayList<Scored>()
        entries.forEachIndexed { order, entry ->
            val title = SearchText.transliterate(entry.title)
            val artist = SearchText.transliterate(entry.artist)
            val album = SearchText.transliterate(entry.album)
            val score = scoreOf(title, artist, album, latinQ)
            if (score > 0) scored += Scored(score, order, entry.track, entry.titleLower)
        }
        return rank(scored)
    }

    companion object {
        /**
         * Строит индекс по всем локальным трекам (онлайн-кэш и видеоконтент
         * в локальном поиске не участвуют — как и раньше).
         */
        fun build(tracks: List<Track>): LibrarySearchIndex {
            val entries = ArrayList<Entry>(tracks.size)
            val postingsBuilder = HashMap<String, MutableList<Int>>(tracks.size * 2)
            tracks.forEach { track ->
                if (track.source != Source.LOCAL || track.isVideoContent) return@forEach
                val title = SearchText.normalize(track.title)
                val artist = SearchText.normalize(track.artist)
                val album = SearchText.normalize(track.album.orEmpty())
                val order = entries.size
                entries += Entry(track, title, artist, album, track.title.lowercase())
                val seen = HashSet<String>()
                addTokens(postingsBuilder, title, order, seen)
                addTokens(postingsBuilder, artist, order, seen)
                addTokens(postingsBuilder, album, order, seen)
            }
            val postings = HashMap<String, IntArray>(postingsBuilder.size)
            postingsBuilder.forEach { (token, ordinals) -> postings[token] = ordinals.toIntArray() }
            return LibrarySearchIndex(
                entries = entries,
                tokenKeys = postings.keys.toTypedArray(),
                postings = postings,
            )
        }

        private fun addTokens(
            postings: HashMap<String, MutableList<Int>>,
            normalizedField: String,
            order: Int,
            seen: MutableSet<String>,
        ) {
            SearchText.tokensOfNormalized(normalizedField).forEach { token ->
                if (!seen.add(token)) return@forEach
                postings.getOrPut(token) { ArrayList() }.add(order)
            }
        }
    }
}
