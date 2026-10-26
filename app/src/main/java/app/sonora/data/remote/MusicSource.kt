package app.sonora.data.remote

import app.sonora.core.model.Playlist
import app.sonora.core.model.Source
import app.sonora.core.model.Track

/**
 * Контракт источника музыки (Spotify / YouTube / SoundCloud).
 *
 * Все реализации обязаны:
 *  - использовать только официальные API и OAuth;
 *  - возвращать треки, которые разрешено проигрывать (preview / embed / stream, разрешённый API),
 *    иначе ставить playable = false и заполнять deepLink;
 *  - никогда не реализовывать скачивание защищённого DRM-контента.
 */
interface MusicSource {

    val source: Source

    /** Подключён ли аккаунт (есть валидный токен). */
    fun isConnected(): Boolean

    /** Имя аккаунта для UI, если известно. */
    fun accountName(): String?

    /** Поиск внутри сервиса. */
    suspend fun search(query: String, limit: Int = 30): List<Track>

    /** Плейлисты пользователя (включая «Мне нравится» / Liked). */
    suspend fun libraryPlaylists(): List<Playlist>

    /** Треки конкретного плейлиста сервиса. */
    suspend fun playlistTracks(remotePlaylistId: String): List<Track>

    /** Явно отключить аккаунт и стереть токены. */
    fun disconnect()

    /** Текст-подсказка, если источник недоступен без настройки. */
    fun setupHint(): String?
}
