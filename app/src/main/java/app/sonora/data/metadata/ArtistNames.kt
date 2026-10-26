package app.sonora.data.metadata

import java.text.Normalizer

/**
 * Приведение имён исполнителей к одному виду.
 *
 * YouTube/InnerTube и MediaStore часто отдают не имя артиста, а название
 * канала: «Nirvana - Topic», «NirvanaVEVO», «Nirvana Official». Из-за этого
 * один исполнитель распадался на несколько карточек в Медиатеке. Здесь мы
 * убираем только очевидные служебные хвосты, не трогая настоящие имена
 * вроде «Earth, Wind & Fire» или «Tyler, The Creator».
 */
object ArtistNames {

    private val TOPIC_SUFFIX = Regex("""(?i)\s*-\s*topic$""")
    private val VEVO_SUFFIX = Regex("""(?i)vevo$""")
    private val OFFICIAL_SUFFIX = Regex(
        """(?i)\s*(?:-\s*)?official(?:\s+(?:artist|channel|audio|video|music))?$""",
    )
    private val CHANNEL_SUFFIX = Regex("""(?i)\s*-\s*(?:music|channel|records)$""")
    private val WHITESPACE = Regex("""\s+""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val LEADING_THE = Regex("""^the\s+""")

    private val PLACEHOLDERS = setOf(
        "неизвестный исполнитель",
        "неизвестен",
        "unknown artist",
        "unknown",
        "<unknown>",
        "youtube music",
        "youtube",
        "various artists",
        "va",
    )

    /** Убирает служебные суффиксы канала и лишние пробелы. */
    fun clean(raw: String): String {
        val original = raw.trim()
        if (original.isBlank()) return ""
        var value = original
        value = value.replace(TOPIC_SUFFIX, "")
        value = value.replace(VEVO_SUFFIX, "")
        value = value.replace(OFFICIAL_SUFFIX, "")
        value = value.replace(CHANNEL_SUFFIX, "")
        value = value.replace(WHITESPACE, " ").trim(' ', '-', '–', '—')
        return value.ifBlank { original }
    }

    /** Ключ для группировки: без регистра, диакритики, пунктуации и ведущего «The». */
    fun key(raw: String): String {
        val cleaned = clean(raw)
        if (cleaned.isBlank()) return ""
        val withoutDiacritics = Normalizer.normalize(cleaned, Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
        return withoutDiacritics
            .lowercase()
            .replace(LEADING_THE, "")
            .replace(NON_WORD, " ")
            .replace(WHITESPACE, " ")
            .trim()
    }

    /** Заглушка вместо имени исполнителя. */
    fun isPlaceholder(raw: String): Boolean {
        val value = clean(raw).lowercase().trim()
        return value.isBlank() || value in PLACEHOLDERS
    }

    /** Имя похоже на канал/заглушку и его стоит заменить метаданными. */
    fun isSuspicious(raw: String): Boolean =
        isPlaceholder(raw) || clean(raw).trim() != raw.trim()

    fun matches(first: String, second: String): Boolean {
        val a = key(first)
        val b = key(second)
        return a.isNotBlank() && a == b
    }
}
