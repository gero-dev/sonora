package app.sonora.core.util

/**
 * Нормализация, токенизация и транслитерация поискового текста.
 *
 * Чистый Kotlin без Android-зависимостей: используется индексом локального поиска
 * (см. `LibrarySearchIndex`) и покрыт обычными JVM-тестами.
 *
 * Важно: [normalize] повторяет прежнюю логику `LibraryRepository.searchLocal`
 * один в один (нижний регистр, ё→е, схлопывание пробелов), поэтому результаты
 * поиска не меняются.
 */
object SearchText {

    /** Всё, что не буква и не цифра, — разделитель токенов (пунктуация, пробелы). */
    private val TOKEN_SEPARATOR = Regex("""[^\p{L}\p{N}]+""")

    /** Пробельные последовательности — как в прежней normalizeSearch. */
    private val WHITESPACE = Regex("""\s+""")

    /** Нижний регистр, ё→е, схлопнутые пробелы, обрезанные края. */
    fun normalize(value: String): String = value
        .lowercase()
        .replace('ё', 'е')
        .replace(WHITESPACE, " ")
        .trim()

    /** Токены: [normalize] + разбиение по пунктуации и пробелам. */
    fun tokens(value: String): List<String> = tokensOfNormalized(normalize(value))

    /** Токены уже нормализованной строки (без повторной нормализации). */
    fun tokensOfNormalized(normalized: String): List<String> =
        normalized.split(TOKEN_SEPARATOR).filter { it.isNotEmpty() }

    /** Есть ли в строке кириллица (быстрая проверка без аллокаций). */
    fun containsCyrillic(value: String): Boolean = value.any { char ->
        char in 'а'..'я' || char == 'ё' || char == 'і' || char == 'ї' || char == 'є' || char == 'ґ'
    }

    private val CYRILLIC_TO_LATIN: Map<Char, String> = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e",
        'ё' to "e", 'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k",
        'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r",
        'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts",
        'ч' to "ch", 'ш' to "sh", 'щ' to "shch", 'ъ' to "", 'ы' to "y", 'ь' to "",
        'э' to "e", 'ю' to "yu", 'я' to "ya",
        // Украинские буквы: встречаются в названиях и тегах.
        'і' to "i", 'ї' to "i", 'є' to "e", 'ґ' to "g",
    )

    /**
     * Транслитерация кириллицы в латиницу: запрос «nirvana» находит «Нирвана»
     * и наоборот. Латиница и цифры остаются как есть, пунктуация тоже.
     */
    fun transliterate(value: String): String = buildString(value.length + value.length / 2) {
        value.lowercase().forEach { char ->
            append(CYRILLIC_TO_LATIN[char] ?: char.toString())
        }
    }
}
