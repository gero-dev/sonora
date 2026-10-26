package app.sonora.data.discover

import app.sonora.core.model.Source
import app.sonora.core.model.Track

/**
 * Открытый каталог, из которого можно легально искать и скачивать музыку.
 *
 * Контракт для реализаций (Internet Archive, Jamendo):
 *  - возвращать только контент, который авторы отдают свободно (public domain / CC);
 *  - `uri` — прямой https-аудиофайл, пригодный и для ExoPlayer, и для DownloadManager;
 *  - `downloadAllowed = true`, в `extra` обязательно `license` (название) и `licenseUrl`;
 *  - `extra["fileName"]` — предлагаемое имя файла для скачивания.
 */
interface DiscoverSource {

    val source: Source

    /** Доступен ли каталог без дополнительной настройки. */
    fun isAvailable(): Boolean

    /** Что нужно сделать, чтобы каталог заработал (null — ничего). */
    fun setupHint(): String?

    /** Поиск треков. Ошибки сети не пробрасываются — возвращается пустой список. */
    suspend fun search(query: String, limit: Int = 20): List<Track>
}
