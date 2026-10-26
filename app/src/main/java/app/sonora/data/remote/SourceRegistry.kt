package app.sonora.data.remote

import app.sonora.core.model.Source
import app.sonora.core.model.Track
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** Реестр всех внешних источников: единый поиск, статусы подключений. */
class SourceRegistry(val sources: List<ConnectableSource>) {

    fun bySource(source: Source): ConnectableSource? = sources.firstOrNull { it.source == source }

    val connected: List<ConnectableSource> get() = sources.filter { it.isConnected() }

    /**
     * Параллельный поиск по всем подключённым сервисам.
     * Ошибки отдельных источников не ломают общий результат.
     */
    suspend fun searchAll(query: String, limitPerSource: Int = 20): List<Track> {
        if (query.isBlank()) return emptyList()
        val targets = connected
        if (targets.isEmpty()) return emptyList()
        return withTimeoutOrNull(15_000) {
            coroutineScope {
                targets.map { src ->
                    async {
                        runCatching { src.search(query, limitPerSource) }.getOrDefault(emptyList())
                    }
                }.awaitAll().flatten()
            }
        } ?: emptyList()
    }
}
