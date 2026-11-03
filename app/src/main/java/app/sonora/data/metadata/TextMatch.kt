package app.sonora.data.metadata

import java.text.Normalizer

/**
 * Сравнение названий и исполнителей для сопоставления локального файла с
 * записью из каталога (iTunes, Deezer, MusicBrainz, LRCLIB).
 *
 * Зачем отдельный объект: раньше сравнение было реализовано прямо в
 * `MetadataService` четырьмя методами, которые сводились к `lowercase()` +
 * удалению скобок + `contains`. Этого мало, и именно отсюда брались
 * «неправильные» треки: строка каталога и тег файла почти никогда не совпадают
 * посимвольно, зато различаются тем, что не должно влиять на смысл —
 *
 *  * диакритика: `Beyoncé` против `Beyonce`, `Mötley Crüe` против `Motley Crue`;
 *  * типографские тире и кавычки: `–`, `—`, `−`, `«»`, `""` против `-` и `"`;
 *  * хвосты-пометки: `(Remastered 2011)`, `[Official Video]`, `- Radio Edit`;
 *  * приглашённые исполнители: `Artist feat. X`, `Artist ft. X`, `Artist при уч. X`;
 *  * разные алфавиты: `Машина времени` в теге против `Mashina Vremeni` не
 *    сопоставить, но `Ария` против `Aria` — это одна и та же запись, набранная
 *    латиницей-двойником.
 *
 * Объект чистый: ни `android.*`, ни сети, поэтому проверяется юнит-тестами.
 */
internal object TextMatch {

    /** Пары «кириллица → латинский двойник» для смешанных написаний. */
    private val CYRILLIC_LOOKALIKES = mapOf(
        'а' to 'a', 'е' to 'e', 'о' to 'o', 'с' to 'c', 'р' to 'p', 'х' to 'x',
        'у' to 'y', 'к' to 'k', 'м' to 'm', 'т' to 't', 'в' to 'b', 'н' to 'h',
        'і' to 'i', 'ѕ' to 's', 'ј' to 'j', 'ԁ' to 'd', 'ɡ' to 'g',
    )

    private val PARENS = Regex("""[\(\[\{][^)\]}]*[)\]\}]""")

    /** Хвост приглашённого исполнителя: `feat.`, `ft.`, `featuring`, `при уч.`. */
    private val FEAT_TAIL = Regex(
        """\s*[\(\[]?\s*(?:feat|ft|featuring|with|prod|при\s*уч)\.?\s.*$""",
        RegexOption.IGNORE_CASE,
    )

    /** Пометки издания отдельным хвостом через дефис: `- Remastered 2011`. */
    private val DASH_EDITION = Regex(
        """\s*[-–—]\s*(?:remaster(?:ed)?|radio\s+edit|album\s+version|single\s+version|""" +
            """deluxe|bonus\s+track|official\s+(?:video|audio)|hd|hq|lyric\s+video)\b.*$""",
        RegexOption.IGNORE_CASE,
    )

    private val PUNCT = Regex("""[^\p{L}\p{N}]+""")
    private val SPACES = Regex("""\s+""")

    /**
     * Приводит строку к виду, пригодному для сравнения «по смыслу».
     *
     * Порядок шагов важен: сначала снимаем скобочные группы целиком (иначе
     * `Song (Live)` и `Song` не сойдутся), потом хвосты feat./издания, потом
     * диакритику и пунктуацию.
     */
    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var text = raw
        text = PARENS.replace(text, " ")
        text = FEAT_TAIL.replace(text, " ")
        text = DASH_EDITION.replace(text, " ")
        text = foldDiacritics(text)
        text = text.lowercase()
        // «ё» — отдельная буква, а не диакритика: в тегах её пишут через «е».
        text = text.replace('ё', 'е').replace('ї', 'и')
        // Латиница-двойник: нужно, чтобы `Ария` и `Aria` дали одну основу.
        text = text.map { CYRILLIC_LOOKALIKES[it] ?: it }.joinToString("")
        text = PUNCT.replace(text, " ")
        return SPACES.replace(text, " ").trim()
    }

    /** Снимает диакритику: `Beyoncé` → `Beyonce`, `Mötley` → `Motley`. */
    private fun foldDiacritics(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
        return decomposed.filter { !isCombining(it) }
    }

    private fun isCombining(ch: Char): Boolean =
        Character.getType(ch) == Character.NON_SPACING_MARK.toInt() ||
            Character.getType(ch) == Character.COMBINING_SPACING_MARK.toInt()

    /**
     * Совпадают ли названия.
     *
     * Требуется именно равенство нормализованных форм, а не `contains`: раньше
     * подстрока принималась за совпадение, из-за чего `The Man` совпадал с
     * `The Man Who Sold the World`, и на такой файл подставлялись чужие
     * метаданные. Исключение — случай, когда одна форма целиком является
     * словесным хвостом другой: `Song` и `Song (Live)` после нормализации
     * обычно уже равны, а `Artist - Song` против `Song` — это как раз тот
     * случай, когда совпадение настоящее.
     */
    fun titlesMatch(candidate: String?, expected: String?): Boolean {
        val a = normalize(candidate)
        val b = normalize(expected)
        if (a.isBlank() || b.isBlank()) return false
        if (a == b) return true
        return isWordSuffixOrPrefix(a, b)
    }

    /**
     * Совпадают ли исполнители. Пустое значение с любой стороны — «неизвестно»,
     * и вызывающий решает сам; здесь это `true`, чтобы не отбрасывать верную
     * запись только из-за отсутствия тега.
     */
    fun artistsMatch(candidate: String?, expected: String?): Boolean {
        val a = normalize(candidate)
        val b = normalize(expected)
        if (a.isBlank() || b.isBlank()) return true
        if (a == b) return true
        if (isWordSuffixOrPrefix(a, b)) return true
        // «The Beatles» против «Beatles».
        val aNoThe = a.removePrefix("the ").trim()
        val bNoThe = b.removePrefix("the ").trim()
        return aNoThe.isNotBlank() && aNoThe == bNoThe
    }

    /**
     * Одно значение целиком является словесным ОКОНЧАНИЕМ другого.
     *
     * Только окончание, и именно по целым словам. Совпадение по началу
     * запрещено намеренно: иначе `The Man` снова сошлось бы с
     * `The Man Who Sold the World` — ровно та ошибка, из-за которой файлу
     * подставлялись чужие метаданные. Окончание же — это нормальный случай
     * `Artist - Title` против `Title`: после нормализации строка каталога
     * выглядит как `artist title`, и название лежит в её хвосте.
     */
    private fun isWordSuffixOrPrefix(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length <= b.length) b else a
        if (shorter == longer) return true
        // Слишком короткая основа ничего не доказывает: «я», «the», «of».
        if (shorter.length < MIN_STEM_LENGTH) return false
        return longer.endsWith(" $shorter")
    }

    /** Короче этого основа считается служебным словом, а не совпадением. */
    private const val MIN_STEM_LENGTH = 4

    /**
     * Допустимое расхождение длительности.
     *
     * Когда исполнитель совпал, расхождение в 10 с — это то же самое издание
     * (разные мастеринги, пауза в конце). Когда исполнитель НЕ совпал, такая
     * терпимость опасна: под неё попадает кавер или живая версия, и именно так
     * в файл попадали чужие метаданные. Поэтому там порог жёсткий.
     */
    fun durationMatches(
        candidateMs: Long?,
        expectedMs: Long?,
        artistAgrees: Boolean,
    ): Boolean {
        if (candidateMs == null || candidateMs <= 0L) return true
        if (expectedMs == null || expectedMs <= 0L) return true
        val tolerance = if (artistAgrees) LENIENT_DURATION_MS else STRICT_DURATION_MS
        return kotlin.math.abs(candidateMs - expectedMs) <= tolerance
    }

    const val LENIENT_DURATION_MS = 10_000L
    const val STRICT_DURATION_MS = 3_000L
}
