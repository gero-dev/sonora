package app.sonora.core.model

import kotlinx.serialization.Serializable

/**
 * Откуда пришёл трек. Локальные файлы играются напрямую,
 * остальные источники — через официальные API/открытые каталоги.
 */
@Serializable
enum class Source {
    LOCAL,
    SPOTIFY,
    YOUTUBE,
    SOUNDCLOUD,
    /** Internet Archive: свободные/CC-записи, прямой аудиофайл. */
    ARCHIVE,
    /** Jamendo: Creative Commons каталог, прямой аудиофайл (нужен client_id). */
    JAMENDO;

    val title: String
        get() = when (this) {
            LOCAL -> "Устройство"
            SPOTIFY -> "Spotify"
            YOUTUBE -> "YouTube Music"
            SOUNDCLOUD -> "SoundCloud"
            ARCHIVE -> "Internet Archive"
            JAMENDO -> "Jamendo"
        }

    val isRemote: Boolean get() = this != LOCAL

    companion object {
        fun fromKey(key: String?): Source =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: LOCAL

        /** Источники, из которых Sonora умеет искать и скачивать свободные треки. */
        val openCatalogs: List<Source> get() = listOf(ARCHIVE, JAMENDO)
    }
}

/**
 * Единая модель трека для всех источников.
 *
 * [uri] — то, что можно отдать ExoPlayer напрямую (content://, file:// или https://).
 * Если uri == null, трек в приложении не играется: для него доступен только
 * переход в официальное приложение сервиса (см. [deepLink]).
 */
@Serializable
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long = 0L,
    val uri: String? = null,
    val artworkUri: String? = null,
    val artworkUrl: String? = null,
    val source: Source = Source.LOCAL,
    val playable: Boolean = true,
    /** Разрешено ли скачивание (только локальный/свободный контент и то, что явно отдаёт API). */
    val downloadAllowed: Boolean = false,
    val deepLink: String? = null,
    /** Текст песни, найденный онлайн (LRCLIB/MusicBrainz-обогащение). */
    val lyrics: String? = null,
    /** Синхронизированный текст в формате LRC (`[00:12.34] строка`) для караоке-режима. */
    val syncedLyrics: String? = null,
    val extra: Map<String, String> = emptyMap(),
) {
    val displaySubtitle: String
        get() = buildString {
            append(artist)
            val a = album
            if (!a.isNullOrBlank()) {
                append(" · ")
                append(a)
            }
        }

    val year: Int? get() = extra["year"]?.toIntOrNull()

    /** Локальная копия, сохранённая из стриминга: показываем её вместе с файлами. */
    val savedFromStreaming: Boolean
        get() = source == Source.LOCAL &&
            (extra.containsKey("downloadedTrackId") || extra.containsKey("youtubeId"))

    /** Трек пришёл из сети и ещё не сохранён на устройство. */
    val streamingOnly: Boolean get() = source != Source.LOCAL

    /**
     * Видеоконтент: клипы, концерты, реакции, подкасты и локальные файлы
     * с видеодорожкой. Sonora — музыкальный плеер, такие записи не играем.
     */
    val isVideoContent: Boolean
        get() {
            if (extra["hasVideo"] == "1") return true
            if (source == Source.LOCAL) return false
            val haystack = "$title ${extra["videoType"].orEmpty()}".lowercase()
            return VIDEO_MARKERS.any { haystack.contains(it) }
        }
}

private val VIDEO_MARKERS = listOf(
    "official video", "music video", "lyric video", "visualizer",
    "full concert", "full album",
    "reaction", "interview", "podcast", "shorts", "trailer", "teaser",
    "1 hour", "10 hours", "gameplay", "asmr", "live stream",
    "клип", "концерт", "прямой эфир", "подкаст", "интервью",
    "реакция", "обзор", "полный альбом", "тизер", "трейлер",
)

/**
 * Результат онлайн-поиска метаданных: то, чего нет в тегах файла.
 * Ничего не скачивает и не меняет сам трек — решение принимает LibraryRepository.
 */
data class TrackMetadata(
    val album: String? = null,
    val year: Int? = null,
    val coverUrl: String? = null,
    val lyrics: String? = null,
    val syncedLyrics: String? = null,
    val musicBrainzId: String? = null,
    /** Уточнённые артист/название: заполняются только если текущие — заглушки. */
    val artist: String? = null,
    val title: String? = null,
    /** Фото исполнителя (TheAudioDB/Deezer/Wikipedia), если удалось найти. */
    val artistImageUrl: String? = null,
    /** Краткая биография исполнителя. */
    val artistBio: String? = null,
    /** Жанр из каталога (TheAudioDB и т. п.). */
    val genre: String? = null,
    val provider: String = "MusicBrainz",
) {
    val isEmpty: Boolean
        get() = album == null && year == null && coverUrl == null && lyrics == null &&
            syncedLyrics == null && artist == null && title == null &&
            artistImageUrl == null && artistBio == null && genre == null
}

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val description: String? = null,
    val artworkUrl: String? = null,
    val source: Source = Source.LOCAL,
    /** id плейлиста в сервисе-источнике (Spotify playlist id, YouTube id, ...). */
    val remoteId: String? = null,
    val trackIds: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val editable: Boolean = true,
    val ownerName: String? = null,
)

/** Альбом/артист — вычисляемые группировки, не хранятся отдельно. */
data class Album(
    val name: String,
    val artist: String,
    val trackIds: List<String>,
    val artworkUri: String?,
    val artworkUrl: String?,
    val year: Int? = null,
)

data class Artist(
    val name: String,
    val trackIds: List<String>,
    val artworkUri: String?,
    val artworkUrl: String?,
)

/** Состояние подключения к внешнему сервису. */
data class ConnectionState(
    val source: Source,
    val connected: Boolean = false,
    val accountName: String? = null,
    val lastSyncAt: Long? = null,
    val error: String? = null,
    val available: Boolean = true,
    val hint: String? = null,
)
