package app.sonora.data.wave

import app.sonora.core.model.Track

/**
 * Модели «Моей волны» без единой зависимости от Android: их используют и
 * UI, и чистые JVM-тесты алгоритмов.
 *
 * Модели живут в data-слое, а `app.sonora.ui.Wave` оставляет совместимые
 * typealias-имена, поэтому экраны продолжают импортировать их из `ui`.
 */

/** Откуда брать треки для «Моей волны». */
enum class WaveSourceMode(val label: String) {
    MIX("Микс"),
    LOCAL("Устройство"),
    ONLINE("Онлайн"),
}

/**
 * Настроение. [query] уходит в поисковый запрос InnerTube, а [WaveMoodMatcher]
 * проверяет трек по аудио-фичам ReccoBeats (или по жанру/названию, если фич нет).
 */
enum class WaveMood(val label: String, val query: String) {
    ANY("Любое", ""),
    CALM("Спокойное", "chill acoustic mix"),
    FOCUS("Фокус", "instrumental focus study mix"),
    ENERGY("Энергичное", "dance workout mix"),
    NIGHT("Ночное", "night drive mix"),
    HAPPY("Весёлое", "happy upbeat mix"),
}

enum class WaveSort(val label: String) {
    FOR_YOU("Для вас"),
    TITLE("По названию"),
    ARTIST("По артисту"),
    DURATION("По длительности"),
    SHUFFLE("Перемешать"),
}

/**
 * Активные фильтры волны. Это жёсткие фильтры: ни одно правило нельзя
 * «обойти» пустой выдачей — вместо подмешивания лишних треков волна
 * честно возвращает пустой список и причину (см. [WaveStatus]).
 */
data class WaveFilters(
    val source: WaveSourceMode = WaveSourceMode.MIX,
    val mood: WaveMood = WaveMood.ANY,
    /** Только избранное: в волну попадают исключительно треки из «Любимых». */
    val favoritesOnly: Boolean = false,
) {
    /** Для подписи под фильтрами: что именно сейчас включено. */
    fun summary(): String {
        val parts = buildList {
            add(source.label)
            if (mood != WaveMood.ANY) add(mood.label.lowercase())
            if (favoritesOnly) add("избранное")
        }
        return parts.joinToString(" · ")
    }

    val hasActiveFilter: Boolean
        get() = source != WaveSourceMode.MIX || mood != WaveMood.ANY || favoritesOnly
}

/**
 * Почему волна пустая или чем она ограничена. UI показывает [WaveUiState.hint]
 * и по этому статусу решает, предлагать ли «Сбросить фильтры».
 */
enum class WaveStatus {
    /** Подборка собрана. */
    READY,

    /** Идёт сборка. */
    LOADING,

    /** В библиотеке вообще нет музыки. */
    EMPTY_LIBRARY,

    /** Фильтр источника отсекает всё (например, «Онлайн» при выключенном стриминге). */
    EMPTY_SOURCE,

    /** «Только избранное», но в избранном нет подходящих треков. */
    EMPTY_FAVORITES,

    /** Настроение не совпало ни с одним треком. */
    EMPTY_MOOD,

    /** Все кандидаты отсеяны фильтрами (дизлайки, повторы, длительность). */
    EMPTY_FILTERED,

    /** «Ещё» догружать нечего: фильтры исчерпали пул. */
    EMPTY_EXHAUSTED,

    /** Сборка упала: сеть/диск. */
    ERROR,
}

/** Результат сборки волны: треки + честное объяснение, что с ними стало. */
data class WaveBuildResult(
    val tracks: List<Track>,
    val label: String,
    val status: WaveStatus,
    /** Причина пустоты/ограничения — показывается в UI. */
    val hint: String? = null,
    /** Сколько кандидатов ранжировано с реальными фичами ReccoBeats. */
    val featuresUsed: Int = 0,
    /** Подпись активных фильтров: «Микс · спокойное · избранное». */
    val filterSummary: String = "",
)

/** Состояние экрана «Моя волна». */
data class WaveUiState(
    val filters: WaveFilters = WaveFilters(),
    val sort: WaveSort = WaveSort.FOR_YOU,
    val tracks: List<Track> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val seedLabel: String = "",
    val generatedAt: Long = 0L,
    /** Почему волна пустая/ограничена; READY — всё в порядке. */
    val status: WaveStatus = WaveStatus.READY,
    /** Подсказка под фильтрами (причина пустоты или пояснение к подборке). */
    val hint: String? = null,
    /** Сколько треков подобрано с учётом аудио-фич ReccoBeats. */
    val featuresUsed: Int = 0,
    /** Подпись активных фильтров. */
    val filterSummary: String = "",
) {
    /** Пусто и при этом не идёт загрузка — значит, пора объяснить причину. */
    val showsEmptyReason: Boolean
        get() = tracks.isEmpty() && !loading && !loadingMore && (hint != null || status != WaveStatus.READY)
}
