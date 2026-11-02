package app.sonora.data.recognition

import kotlinx.serialization.Serializable

/**
 * Публичные модели модуля «поиск песни по фрагменту» и «поиск по напетой мелодии».
 *
 * Модуль намеренно изолирован: он не знает про MainViewModel, плеер и навигацию.
 * Экран поиска только читает [RecognitionState] и вызывает [RecognitionController],
 * поэтому распознавание можно включать/выключать, не трогая остальное приложение.
 */

/** Режим поиска: слушаем играющий трек вокруг или напетую/просвистанную мелодию. */
enum class RecognitionMode {
    /** Shazam-подобный режим: микрофон слушает музыку вокруг. */
    LISTEN,

    /** Hum-to-search: пользователь напевает или просвистывает мелодию. */
    HUM,
}

/**
 * Шаг пайплайна — для понятного статуса в UI.
 * Тексты русские, потому что это пользовательские подписи.
 */
enum class RecognitionPhase(val label: String) {
    RECORDING("Запись фрагмента"),
    RECOGNIZING("Распознавание"),
    SEARCHING("Поиск по фонотеке"),
    ENRICHING("Поиск информации"),
    VERIFYING("Фоновая проверка"),
    DONE("Готово"),
}

/** Состояние распознавания. Один общий StateFlow на всё приложение. */
sealed interface RecognitionState {

    /** Ничего не происходит: показываем компактную кнопку входа. */
    data object Idle : RecognitionState

    /** Нужно разрешение RECORD_AUDIO: UI запрашивает его и снова вызывает start(). */
    data object PermissionRequired : RecognitionState

    /** Идёт запись фрагмента с микрофона. */
    data class Recording(
        val elapsedMs: Long,
        val totalMs: Long,
        val mode: RecognitionMode = RecognitionMode.LISTEN,
    ) : RecognitionState {
        val progress: Float
            get() = if (totalMs <= 0L) 0f else (elapsedMs.toFloat() / totalMs).coerceIn(0f, 1f)
    }

    /** Фрагмент записан: анализ/сверка/ожидание ответа сервиса. */
    data class Identifying(
        val stage: String,
        val progress: Float? = null,
        val phase: RecognitionPhase = RecognitionPhase.RECOGNIZING,
    ) : RecognitionState

    /**
     * Песня распознана (или найдена в своей фонотеке).
     *
     * [enrichment], [alternatives] и [verification] могут прийти позже вторым
     * обновлением состояния — экран поиска просто перерисуется.
     */
    data class Result(
        val track: RecognizedTrack,
        val enrichment: RecognitionEnrichment = RecognitionEnrichment(),
        val fromLocalLibrary: Boolean = false,
        val mode: RecognitionMode = RecognitionMode.LISTEN,
        /** Top-N альтернатив по уверенности (для локальной сверки/мелодии). */
        val alternatives: List<RecognizedTrack> = emptyList(),
        /** id локального трека, если совпадение есть в фонотеке: можно сразу слушать. */
        val localTrackId: String? = null,
        /** Результат фоновой проверки — приходит асинхронно. */
        val verification: RecognitionVerification? = null,
        /** true, пока фоновая проверка ещё идёт. */
        val verifying: Boolean = false,
        /** Результат взят из кэша по хэшу отпечатка. */
        val fromCache: Boolean = false,
    ) : RecognitionState

    /** Ошибка с человекочитаемым текстом. [needsAuddToken] — нужен ключ сервиса. */
    data class Error(
        val message: String,
        val needsAuddToken: Boolean = false,
    ) : RecognitionState
}

/** Распознанный трек: то, что вернул сервис/локальный матчер. */
@Serializable
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
    /** Оценка уверенности 0..1 там, где она осмысленна (локальный матчер/мелодия). */
    val score: Double? = null,
    /** id трека в локальной библиотеке, если совпадение найдено на устройстве. */
    val localTrackId: String? = null,
    /** Видео-id кандидата (для фоновой проверки коротким сниппетом). */
    val videoId: String? = null,
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

    /** Процент уверенности для UI (0..100). */
    val confidencePercent: Int?
        get() = score?.let { (it.coerceIn(0.0, 1.0) * 100).toInt() }
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
    /** id трека в модели приложения (для воспроизведения/телеметрии). */
    val trackId: String? = null,
    /** Видео-id кандидата — по нему можно скачать короткий сниппет для проверки. */
    val videoId: String? = null,
    val playable: Boolean = false,
    /** Подпись аудио-фич из кэша телеметрии (BPM/энергия), если уже известны. */
    val audioFeatureLabel: String? = null,
    /** Уверенность фоновой проверки 0..1, если кандидат сверялся по аудио. */
    val confidence: Double? = null,
) {
    val displayTitle: String
        get() = listOf(artist, title)
            .filter { it.isNotBlank() }
            .joinToString(" — ")

    /** Процент уверенности для UI (0..100). */
    val confidencePercent: Int?
        get() = confidence?.let { (it.coerceIn(0.0, 1.0) * 100).toInt() }
}

/**
 * Результат фоновой проверки найденного совпадения: локальная копия, сверка
 * короткого сниппета кандидата и аудио-фичи. Всё это необязательные данные —
 * их отсутствие не мешает показать результат.
 */
data class RecognitionVerification(
    /** Найдена ли та же запись в локальной фонотеке. */
    val localAvailable: Boolean = false,
    val localTrackId: String? = null,
    val localScore: Double? = null,
    /** Скачивали ли короткий сниппет кандидата и сверяли ли его с фрагментом. */
    val snippetChecked: Boolean = false,
    val snippetScore: Double? = null,
    val snippetCandidateTitle: String? = null,
    /** Подпись аудио-фич (ReccoBeats) для лучшего кандидата. */
    val featuresSummary: String? = null,
    /** Человекочитаемое пояснение для UI. */
    val note: String? = null,
)
