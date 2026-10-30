package app.sonora.data.recognition

/**
 * Публичные модели модуля «поиск песни по фрагменту».
 *
 * Модуль намеренно изолирован: он не знает про MainViewModel, плеер и навигацию.
 * Экран поиска только читает [RecognitionState] и вызывает [RecognitionController],
 * поэтому распознавание можно включать/выключать, не трогая остальное приложение.
 */

/** Состояние распознавания. Один общий StateFlow на всё приложение. */
sealed interface RecognitionState {

    /** Ничего не происходит: показываем компактную кнопку входа. */
    data object Idle : RecognitionState

    /** Нужно разрешение RECORD_AUDIO: UI запрашивает его и снова вызывает start(). */
    data object PermissionRequired : RecognitionState

    /** Идёт запись фрагмента с микрофона. */
    data class Recording(val elapsedMs: Long, val totalMs: Long) : RecognitionState {
        val progress: Float
            get() = if (totalMs <= 0L) 0f else (elapsedMs.toFloat() / totalMs).coerceIn(0f, 1f)
    }

    /** Фрагмент записан: анализ/сверка/ожидание ответа сервиса. */
    data class Identifying(val stage: String, val progress: Float? = null) : RecognitionState

    /**
     * Песня распознана (или найдена в своей фонотеке).
     * [enrichment] может прийти позже вторым обновлением состояния.
     */
    data class Result(
        val track: RecognizedTrack,
        val enrichment: RecognitionEnrichment = RecognitionEnrichment(),
        val fromLocalLibrary: Boolean = false,
    ) : RecognitionState

    /** Ошибка с человекочитаемым текстом. [needsAuddToken] — нужен ключ сервиса. */
    data class Error(
        val message: String,
        val needsAuddToken: Boolean = false,
    ) : RecognitionState
}

/** Распознанный трек: то, что вернул сервис/локальный матчер. */
data class RecognizedTrack(
    val artist: String,
    val title: String,
    val album: String? = null,
    val releaseDate: String? = null,
    val label: String? = null,
    /** Позиция фрагмента внутри песни, если сервис её сообщает. */
    val timecode: String? = null,
    /** Ссылка на страницу песни (lis.tn у AudD, deepLink у локального трека). */
    val songLink: String? = null,
    val artworkUrl: String? = null,
    /** Откуда пришло распознавание: «AudD», «Фонотека устройства». */
    val provider: String = "AudD",
    /** Оценка уверенности 0..1 там, где она осмысленна (локальный матчер). */
    val score: Double? = null,
) {
    /** Подпись для UI: «Исполнитель — Название». */
    val displayTitle: String
        get() = listOf(artist, title)
            .filter { it.isNotBlank() }
            .joinToString(" — ")
            .ifBlank { "Неизвестный трек" }

    /** Запрос для обычного поиска по названию и автору. */
    val searchQuery: String
        get() = listOf(artist, title)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
}

/** Дополнительные данные, которые ищутся в фоне уже по названию и автору. */
data class RecognitionEnrichment(
    val coverUrl: String? = null,
    val album: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val artistBio: String? = null,
    /** Кандидаты из онлайн-источника приложения (YouTube и т. п.). */
    val candidates: List<RecognitionCandidate> = emptyList(),
    /** true, когда фоновый поиск метаданных завершён (успешно или нет). */
    val done: Boolean = false,
)

/** Ссылка на найденный вариант в источнике приложения. */
data class RecognitionCandidate(
    val title: String,
    val artist: String,
    val sourceLabel: String,
    val subtitle: String? = null,
) {
    val displayTitle: String
        get() = listOf(artist, title)
            .filter { it.isNotBlank() }
            .joinToString(" — ")
}
