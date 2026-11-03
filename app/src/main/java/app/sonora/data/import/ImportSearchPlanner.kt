package app.sonora.data.import

/**
 * План поисковых запросов для одной строки импортируемого списка.
 *
 * Зачем это нужно. Раньше импорт делал ровно один запрос на строку —
 * `"артист название"` — и если каталог не отвечал на него подходящим треком,
 * строка молча считалась ненайденной. Но экспорты Spotify/Apple Music пишут
 * названия иначе, чем каталог YouTube Music:
 *
 * - экспорт: `Nirvana — Smells Like Teen Spirit`;
 * - каталог: `Smells Like Teen Spirit (Official Video)` с каналом `Nirvana - Topic`.
 *
 * Поэтому план идёт от точного запроса к широкому: сначала «артист + название»,
 * затем варианты с очищенным названием (без `(Official Video)`, `- Remastered`,
 * `feat. …`), затем одно голое название. Вызывающий код пробует их по порядку и
 * останавливается на первом, который матчер признал совпадением.
 *
 * Объект чистый: никаких Android- и сетевых вызовов, поэтому проверяется на JVM.
 */
object ImportSearchPlanner {

    /** Сколько запросов максимум отдаёт [queries]. */
    const val MAX_QUERIES: Int = 4

    /**
     * Сколько кандидатов просить у источника на один запрос. InnerTube отдаёт
     * за ответ ~20 позиций, поэтому 40 — практический потолок выборки.
     */
    const val CANDIDATE_LIMIT: Int = 40

    /** Скобки любого вида: `(Official Video)`, `[Official Audio]`, `（Live）`, `【MV】`. */
    private val BRACKETS = Regex("""[\(\[（【][^\)\]）】]*[\)\]）】]""")

    /** `feat. X`, `ft. X`, `featuring X`, `при участии X` и всё, что после них. */
    private val FEAT_TAIL = Regex(
        """\b(?:feat|ft|featuring|при\s+участии)\b.*$""",
        RegexOption.IGNORE_CASE,
    )

    private val WHITESPACE = Regex("""\s+""")

    /**
     * Запросы для [entry] от самого точного к самому широкому.
     *
     * Гарантии:
     * - не длиннее [MAX_QUERIES];
     * - без пустых строк;
     * - без повторов (сравнение по регистру и пробелам);
     * - при пустом исполнителе первым идёт само название;
     * - если всё пустое — пустой список, иначе он никогда не пуст.
     */
    fun queries(entry: ImportedTrackEntry): List<String> {
        val title = entry.title.trim()
        val cleaned = cleanTitle(title)
        val artist = entry.artist?.trim().orEmpty()
        val hasArtist = artist.isNotBlank()
        val hasTitle = title.isNotBlank()
        val hasCleaned = cleaned.isNotBlank()

        val raw = buildList {
            // 1. Самый точный запрос: исполнитель вместе с названием.
            if (hasArtist && hasTitle) add("$artist $title")
            // 2. То же, но название без украшений каталога.
            if (hasArtist && hasCleaned) add("$artist $cleaned")
            // 3. Широкий запрос только по названию. При пустом исполнителе это
            //    фактически первый пункт — отдельный запрос не нужен, дедуп ниже
            //    сам схлопнёт повторы.
            if (hasTitle) add(title)
            // 4. Самое широкое: очищенное название без исполнителя. Полезно и
            //    без исполнителя: у строки «Song (Official Audio)» второй шанс
            //    найтись как «Song».
            if (hasCleaned) add(cleaned)
        }

        val out = ArrayList<String>(MAX_QUERIES)
        val seen = HashSet<String>(MAX_QUERIES)
        for (candidate in raw) {
            val display = WHITESPACE.replace(candidate, " ").trim()
            if (display.isEmpty()) continue
            if (!seen.add(display.lowercase())) continue
            out += display
            if (out.size >= MAX_QUERIES) break
        }
        // План не должен оказаться пустым из-за агрессивной очистки: исходное
        // название всегда остаётся последним рубежом.
        if (out.isEmpty() && hasTitle) out += WHITESPACE.replace(title, " ").trim()
        return out
    }

    /**
     * Название без того, что каталоги добавляют к песне: скобочных пометок
     * (`(Official Video)`, `[Remastered 2011]`) и хвоста `feat. …`.
     */
    private fun cleanTitle(title: String): String {
        var text = BRACKETS.replace(title, " ")
        text = FEAT_TAIL.replace(text, " ")
        return WHITESPACE.replace(text, " ").trim()
    }
}
