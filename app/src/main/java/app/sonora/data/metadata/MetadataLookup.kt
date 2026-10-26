package app.sonora.data.metadata

import app.sonora.core.model.Track
import app.sonora.core.model.TrackMetadata

/** Онлайн-поиск недостающих метаданных и текста песни. */
interface MetadataLookup {

    /**
     * Ищет альбом/год/обложку/текст для трека.
     * Никогда не бросает исключений: при отсутствии данных возвращает null.
     */
    suspend fun lookup(track: Track): TrackMetadata?

    /** Есть ли у трека уже всё, что мы умеем искать. */
    fun isComplete(track: Track): Boolean
}
