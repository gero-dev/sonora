package app.sonora.ui

import app.sonora.core.model.Track

/** Откуда брать треки для «Моей волны». */
enum class WaveSourceMode(val label: String) {
    MIX("Микс"),
    LOCAL("Устройство"),
    ONLINE("Онлайн"),
}

/** Настроение влияет и на фильтр локальной выдачи, и на поисковый запрос в InnerTube. */
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

data class WaveFilters(
    val source: WaveSourceMode = WaveSourceMode.MIX,
    val mood: WaveMood = WaveMood.ANY,
    /** Только избранное: локальная часть волны ограничивается любимыми треками. */
    val favoritesOnly: Boolean = false,
)

data class WaveUiState(
    val filters: WaveFilters = WaveFilters(),
    val sort: WaveSort = WaveSort.FOR_YOU,
    val tracks: List<Track> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val seedLabel: String = "",
    val generatedAt: Long = 0L,
)
