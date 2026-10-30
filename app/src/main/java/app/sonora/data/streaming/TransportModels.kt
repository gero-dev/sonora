package app.sonora.data.streaming

/**
 * Способ получить звук YouTube.
 *
 * Порядок в [fallbackOrder] — это «здравый смысл», а не истина: на конкретной
 * сети порядок пересобирается по результатам автопроверки
 * ([StreamingAutoPilot]).
 *
 * VPN-туннелей и медиашлюзов в приложении нет: единственный обход блокировок —
 * NoHiT, поэтому проверяются только прямой InnerTube и yt-dlp (который ходит
 * через локальный прокси NoHiT).
 */
enum class TransportKind(val label: String, val shortLabel: String) {
    DIRECT("Прямой YouTube (InnerTube)", "прямой"),
    YTDLP("yt-dlp", "yt-dlp"),
    ;

    companion object {
        /** Если автопроверки ещё не было. */
        val fallbackOrder: List<TransportKind> = listOf(DIRECT, YTDLP)
    }
}

/** Результат проверки одного способа. */
data class TransportCheck(
    val kind: TransportKind,
    val ok: Boolean,
    val latencyMs: Long,
    val detail: String,
    val bitrateKbps: Int? = null,
)

/**
 * Итог автопроверки всех способов: что работает, за сколько и что лучше
 * использовать первым.
 */
data class StreamingAudit(
    val checks: List<TransportCheck>,
    val best: TransportKind?,
    val network: String,
    val deep: Boolean,
    val measuredAtMs: Long = System.currentTimeMillis(),
) {
    fun check(kind: TransportKind): TransportCheck? =
        checks.firstOrNull { it.kind == kind }

    fun ok(kind: TransportKind): Boolean = check(kind)?.ok == true

    /** Порядок транспортов для воспроизведения. */
    fun chain(): List<TransportKind> = when (best) {
        TransportKind.DIRECT -> listOf(
            TransportKind.DIRECT, TransportKind.YTDLP,
        )
        TransportKind.YTDLP -> listOf(
            TransportKind.YTDLP, TransportKind.DIRECT,
        )
        null -> TransportKind.fallbackOrder
    }

    fun summaryLine(): String = when (best) {
        null -> "Ни один способ не работает"
        else -> "Лучший способ: ${best.label}"
    }

    /** Многострочный отчёт для диалога «Проверить все способы». */
    fun detailsText(): String = buildString {
        appendLine(summaryLine())
        appendLine("Сеть: $network")
        appendLine()
        for (check in checks) {
            val mark = if (check.ok) "✓" else "✕"
            val bitrate = check.bitrateKbps?.takeIf { it > 0 }?.let { " · $it кбит/с" } ?: ""
            appendLine("$mark ${check.kind.label}: ${check.detail}$bitrate · ${check.latencyMs} мс")
        }
    }
}
