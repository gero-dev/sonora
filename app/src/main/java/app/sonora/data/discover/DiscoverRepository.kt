package app.sonora.data.discover

import app.sonora.core.model.Source
import app.sonora.core.model.Track

/**
 * Объединённый поиск по открытым каталогам (Internet Archive, Jamendo).
 * Здесь нет ни одного стримингового сервиса по подписке — только свободный контент.
 */
class DiscoverRepository(private val sources: List<DiscoverSource>) {

    val all: List<DiscoverSource> get() = sources

    val available: List<DiscoverSource> get() = sources.filter { it.isAvailable() }

    fun bySource(source: Source): DiscoverSource? = sources.firstOrNull { it.source == source }

    /** Источники для переключателя в UI: доступные + недоступные (с подсказкой). */
    fun chips(): List<DiscoverSource> = sources

    suspend fun search(source: Source, query: String, limit: Int = 20): List<Track> {
        if (query.isBlank()) return emptyList()
        val target = bySource(source) ?: return emptyList()
        if (!target.isAvailable()) return emptyList()
        return runCatching { target.search(query.trim(), limit) }.getOrDefault(emptyList())
    }

    /** Поиск по всем доступным каталогам сразу (для «Все источники»). */
    suspend fun searchAll(query: String, limitPerSource: Int = 12): List<Track> {
        if (query.isBlank()) return emptyList()
        return available.flatMap { src ->
            runCatching { src.search(query.trim(), limitPerSource) }.getOrDefault(emptyList())
        }
    }
}
