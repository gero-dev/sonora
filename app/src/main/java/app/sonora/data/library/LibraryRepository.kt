package app.sonora.data.library

import android.content.Context
import android.util.Log
import app.sonora.core.model.Album
import app.sonora.core.model.Artist
import app.sonora.core.model.Playlist
import app.sonora.core.model.Source
import app.sonora.core.model.Track
import app.sonora.core.model.TrackMetadata
import app.sonora.core.settings.SettingsStore
import app.sonora.core.util.JsonFile
import app.sonora.data.metadata.ArtistNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.util.UUID

/**
 * Единая библиотека приложения: локальные треки + кэш метаданных внешних сервисов,
 * плейлисты и избранное. Всё хранится в filesDir (JSON), ничего не уходит наружу.
 */
class LibraryRepository(
    private val context: Context,
    private val settings: SettingsStore,
    private val scanner: MediaStoreScanner,
    /** Дополнительные папки вне MediaStore (импорт из SAF, загрузки приложения). */
    private val extraDirs: () -> List<java.io.File> = { emptyList() },
) {

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val mutex = Mutex()
    /** Сканирование не должно идти параллельно: сохранение трека и кнопка «Обновить». */
    private val scanMutex = Mutex()
    private var initialized = false

    /** Версия библиотеки: растёт при каждом изменении tracks и сбрасывает поисковый индекс. */
    @Volatile
    private var libraryVersion: Long = 0L

    /** Кэш поискового индекса: (версия библиотеки, готовый индекс). */
    @Volatile
    private var searchIndexCache: Pair<Long, LibrarySearchIndex>? = null
    private val searchIndexLock = Any()

    private val trackSerializer = ListSerializer(Track.serializer())
    private val playlistSerializer = ListSerializer(Playlist.serializer())
    private val favoritesSerializer = ListSerializer(String.serializer())

    suspend fun initialize() {
        if (initialized) return
        mutex.withLock {
            if (initialized) return
            val savedTracks = JsonFile.read(context, FILE_TRACKS, trackSerializer, emptyList())
            publishTracks(savedTracks)
            _playlists.value = JsonFile.read(context, FILE_PLAYLISTS, playlistSerializer, emptyList())
            _favorites.value = JsonFile.read(context, FILE_FAVORITES, favoritesSerializer, emptyList()).toSet()
            initialized = true
        }
        if (settings.current.scanOnStart) {
            scanLocal()
        } else {
            // Индекс строим в фоне, чтобы первый поиск не делал этого на Main.
            warmSearchIndex()
        }
    }

    /**
     * Пересканировать устройство и смёржить с кэшем внешних источников.
     *
     * @param forceFull игнорировать инкрементальный кэш сканера и перечитать
     * метаданные всех файлов (например, после ручного изменения sidecar-файлов).
     * По умолчанию — быстрый инкрементальный скан, поведение прежнее.
     */
    suspend fun scanLocal(forceFull: Boolean = false): Int {
        scanMutex.lock()
        _scanning.value = true
        val startedAt = System.nanoTime()
        return try {
            val fromMediaStore = scanner.scan(
                settings.current.excludeShortTracks,
                settings.current.useEmbeddedArtwork,
                settings.current.readSidecarMetadata,
                forceFull,
            )
            val fromFolders = extraDirs().flatMap { dir ->
                scanner.scanFolder(
                    dir,
                    settings.current.useEmbeddedArtwork,
                    settings.current.readSidecarMetadata,
                    forceFull,
                )
            }
            // distinctBy: файл мог попасть и в MediaStore, и в папку импорта.
            val scanned = (fromMediaStore + fromFolders).distinctBy { it.uri ?: it.id }
            val previousLocal = _tracks.value
                .filter { it.source == Source.LOCAL }
                .associateBy { it.id }
            // Пустой ответ при непустой библиотеке почти всегда означает сбой/отзыв
            // разрешения, а не «все файлы удалены». Не затираем библиотеку.
            if (scanned.isEmpty() && previousLocal.isNotEmpty()) {
                Log.w(TAG, "scan returned no files — keeping ${previousLocal.size} local tracks")
                return previousLocal.size
            }
            val local = scanned.map { fresh ->
                val old = previousLocal[fresh.id]
                if (old == null) fresh else mergeScanned(fresh, old)
            }
            // Файлы, которые не прочитались MediaMetadataRetriever (например, webm
            // из InnerTube), не должны исчезать из библиотеки после пересканирования.
            val scannedUris = local.mapNotNull { it.uri }.toSet()
            val preserved = previousLocal.values.filter { old ->
                old.uri != null && old.uri !in scannedUris && uriExists(old.uri)
            }
            val remote = _tracks.value.filter { it.source != Source.LOCAL }
            publishTracks((local + preserved).distinctBy { it.id } + remote)
            persistTracks()
            Log.i(TAG, "scan total: ${elapsedMs(startedAt)} ms, ${local.size} local tracks")
            // Прогреваем поисковый индекс в фоне: первый запрос после скана
            // не должен строить его на главном потоке.
            warmSearchIndex()
            local.size
        } finally {
            _scanning.value = false
            scanMutex.unlock()
        }
    }

    /**
     * Слияние свежего результата сканера со старыми данными. Сканер не знает
     * онлайн-метаданные и теги скачанных файлов, поэтому «пустые» значения
     * не должны затирать уже найденные обложку, альбом, исполнителя и текст.
     */
    private fun mergeScanned(fresh: Track, old: Track): Track {
        val freshArtistMissing = ArtistNames.isSuspicious(fresh.artist)
        val oldArtistMissing = ArtistNames.isSuspicious(old.artist)
        val freshAlbumMissing = fresh.album.isNullOrBlank() ||
            fresh.album.lowercase() in PLACEHOLDER_ALBUMS
        val freshTitleIsFileName = fresh.uri?.let { uri ->
            // URI из MediaStore/scanner бывает percent-encoded: «Nirvana%20-%20…».
            // Декодируем, иначе скачанный трек теряет красивое название из стриминга
            // и остаётся с именем файла «Исполнитель - Название».
            val rawName = uri.substringAfterLast('/').substringBeforeLast('.')
            val name = runCatching { android.net.Uri.decode(rawName) }.getOrDefault(rawName)
            fresh.title.equals(name, ignoreCase = true) || fresh.title == name
        } == true
        val title = if (freshTitleIsFileName && old.title.isNotBlank()) old.title else fresh.title
        val artist = when {
            freshArtistMissing && !oldArtistMissing -> ArtistNames.clean(old.artist)
            else -> ArtistNames.clean(fresh.artist)
        }
        return fresh.copy(
            title = title,
            artist = artist,
            album = if (freshAlbumMissing) old.album else fresh.album,
            durationMs = fresh.durationMs.takeIf { it > 0L } ?: old.durationMs,
            artworkUri = if (isMediaStoreAlbumArtUri(old.artworkUri)) {
                fresh.artworkUri ?: old.artworkUri
            } else {
                old.artworkUri ?: fresh.artworkUri
            },
            artworkUrl = fresh.artworkUrl ?: old.artworkUrl,
            lyrics = fresh.lyrics ?: old.lyrics,
            syncedLyrics = fresh.syncedLyrics ?: old.syncedLyrics,
            deepLink = old.deepLink,
            extra = old.extra + fresh.extra,
        )
    }

    private fun uriExists(uri: String?): Boolean {
        if (uri.isNullOrBlank()) return false
        if (!uri.startsWith("file:", ignoreCase = true)) return true
        val path = uri.removePrefix("file://").removePrefix("file:")
        return runCatching { java.io.File(path).isFile }.getOrDefault(false)
    }

    /**
     * `content://media/external/audio/albumart/...` из MediaStore: на Android 10+
     * такой URI часто не открывается, поэтому его можно заменять свежей обложкой.
     */
    private fun isMediaStoreAlbumArtUri(uri: String?): Boolean =
        uri?.startsWith(MEDIASTORE_ALBUM_ART_PREFIX, ignoreCase = true) == true

    /**
     * Регистрирует локальный файл (в том числе скачанный из InnerTube) с полными
     * метаданными. Обычный [scanLocal] потом смёржит его с файлом на диске.
     */
    suspend fun upsertLocalTracks(incoming: List<Track>) {
        val locals = incoming.filter { it.source == Source.LOCAL }
        if (locals.isEmpty()) return
        val map = LinkedHashMap<String, Track>()
        _tracks.value.forEach { map[it.id] = it }
        locals.forEach { fresh ->
            val old = map[fresh.id]
            map[fresh.id] = if (old == null) {
                fresh
            } else {
                fresh.copy(
                    lyrics = fresh.lyrics ?: old.lyrics,
                    syncedLyrics = fresh.syncedLyrics ?: old.syncedLyrics,
                    artworkUri = fresh.artworkUri ?: old.artworkUri,
                    artworkUrl = fresh.artworkUrl ?: old.artworkUrl,
                    album = fresh.album ?: old.album,
                    extra = old.extra + fresh.extra,
                )
            }
        }
        publishTracks(map.values.toList())
        persistTracks()
    }

    /** Добавить/обновить треки, полученные из внешнего сервиса. */
    suspend fun cacheRemoteTracks(incoming: List<Track>) {
        val remoteOnly = incoming.filter { it.source != Source.LOCAL }
        if (remoteOnly.isEmpty()) return
        val map = LinkedHashMap<String, Track>()
        _tracks.value.forEach { map[it.id] = it }
        remoteOnly.forEach { map[it.id] = it }
        publishTracks(map.values.toList())
        persistTracks()
    }

    suspend fun cacheRemotePlaylists(incoming: List<Playlist>) {
        if (incoming.isEmpty()) return
        val map = LinkedHashMap<String, Playlist>()
        _playlists.value.forEach { map[it.id] = it }
        incoming.forEach { map[it.id] = it }
        _playlists.value = map.values.toList()
        persistPlaylists()
    }

    fun trackById(id: String): Track? = _tracks.value.firstOrNull { it.id == id }

    fun tracksByIds(ids: List<String>): List<Track> {
        val index = _tracks.value.associateBy { it.id }
        return ids.mapNotNull { index[it] }
    }

    // ---------- Плейлисты ----------

    suspend fun createPlaylist(name: String, trackIds: List<String> = emptyList()): Playlist {
        val playlist = Playlist(
            id = "local:playlist:${UUID.randomUUID()}",
            name = name,
            source = Source.LOCAL,
            trackIds = trackIds,
            editable = true,
        )
        _playlists.value = _playlists.value + playlist
        persistPlaylists()
        return playlist
    }

    suspend fun deletePlaylist(id: String) {
        _playlists.value = _playlists.value.filterNot { it.id == id }
        persistPlaylists()
    }

    suspend fun renamePlaylist(id: String, name: String) {
        _playlists.value = _playlists.value.map { if (it.id == id) it.copy(name = name) else it }
        persistPlaylists()
    }

    suspend fun addToPlaylist(playlistId: String, trackIds: List<String>) {
        _playlists.value = _playlists.value.map { playlist ->
            if (playlist.id == playlistId) {
                playlist.copy(trackIds = (playlist.trackIds + trackIds).distinct())
            } else playlist
        }
        persistPlaylists()
    }

    suspend fun removeFromPlaylist(playlistId: String, trackId: String) {
        _playlists.value = _playlists.value.map { playlist ->
            if (playlist.id == playlistId) {
                playlist.copy(trackIds = playlist.trackIds - trackId)
            } else playlist
        }
        persistPlaylists()
    }

    /**
     * Меняет порядок треков внутри плейлиста — как перетаскивание в Spotify.
     * Индексы проверяются, чтобы не потерять состав при гонке с удалением.
     */
    suspend fun moveInPlaylist(playlistId: String, from: Int, to: Int) {
        if (from == to) return
        var changed = false
        _playlists.value = _playlists.value.map { playlist ->
            if (playlist.id != playlistId) return@map playlist
            val ids = playlist.trackIds.toMutableList()
            if (from !in ids.indices || to !in ids.indices) return@map playlist
            val item = ids.removeAt(from)
            ids.add(to, item)
            changed = true
            playlist.copy(trackIds = ids)
        }
        if (changed) persistPlaylists()
    }

    /** Полное замещение состава: нужно для сортировки плейлиста. */
    suspend fun setPlaylistTracks(playlistId: String, trackIds: List<String>) {
        _playlists.value = _playlists.value.map { playlist ->
            if (playlist.id == playlistId) playlist.copy(trackIds = trackIds) else playlist
        }
        persistPlaylists()
    }

    // ---------- Избранное ----------

    suspend fun toggleFavorite(trackId: String): Boolean {
        val current = _favorites.value
        val next = if (current.contains(trackId)) current - trackId else current + trackId
        _favorites.value = next
        JsonFile.write(context, FILE_FAVORITES, favoritesSerializer, next.toList())
        return next.contains(trackId)
    }

    fun isFavorite(trackId: String): Boolean = _favorites.value.contains(trackId)

    fun favoriteTracks(): List<Track> = tracksByIds(_favorites.value.toList())

    /**
     * После сохранения онлайн-трека переносим ссылки на локальную копию:
     * избранное и плейлисты не должны указывать на стриминговый id, иначе
     * после перезапуска они откроют сетевую версию вместо файла.
     */
    suspend fun transferTrackReferences(oldId: String, newId: String) {
        if (oldId == newId) return
        val favorites = _favorites.value
        if (favorites.contains(oldId)) {
            val next = (favorites - oldId) + newId
            _favorites.value = next
            JsonFile.write(context, FILE_FAVORITES, favoritesSerializer, next.toList())
        }
        val playlists = _playlists.value
        var changed = false
        val updated = playlists.map { playlist ->
            if (oldId !in playlist.trackIds) {
                playlist
            } else {
                changed = true
                playlist.copy(
                    trackIds = playlist.trackIds
                        .map { if (it == oldId) newId else it }
                        .distinct(),
                )
            }
        }
        if (changed) {
            _playlists.value = updated
            persistPlaylists()
        }
    }

    // ---------- Онлайн-метаданные ----------

    /**
     * Мёржит найденные онлайн метаданные в трек. Существующие значения не затираются:
     * онлайн-поиск заполняет только пустые поля.
     */
    suspend fun updateTrackMetadata(trackId: String, metadata: TrackMetadata) {
        if (metadata.isEmpty) return
        var changed = false
        val updated = _tracks.value.map { track ->
            if (track.id != trackId) return@map track
            changed = true
            val extra = track.extra.toMutableMap()
            metadata.year?.let { extra["year"] = it.toString() }
            metadata.musicBrainzId?.let { extra["musicBrainzId"] = it }
            val existingAlbum = track.album?.takeIf { it.isNotBlank() }
            // MediaStore часто подставляет имя папки («Music», «Download») вместо альбома:
            // такой «альбом» считаем пустым и заменяем данными из каталогов.
            val albumIsPlaceholder = existingAlbum != null &&
                existingAlbum.lowercase() in PLACEHOLDER_ALBUMS
            /** Альбом, который мы сами записали из метаданных, можно уточнять повторно. */
            val autoAlbum = extra["albumFromMetadata"]
            val albumLooksLikeCompilation = existingAlbum != null &&
                COMPILATION_HINTS.any { hint -> existingAlbum.lowercase().contains(hint) }
            val canReplaceAlbum = existingAlbum == null ||
                albumIsPlaceholder ||
                existingAlbum == autoAlbum ||
                albumLooksLikeCompilation
            val album = if (canReplaceAlbum) {
                metadata.album?.takeIf { it.isNotBlank() } ?: existingAlbum
            } else {
                existingAlbum
            }
            if (album != null && album != existingAlbum) {
                extra["albumFromMetadata"] = album
            }
            val existingCleanedArtist = ArtistNames.clean(track.artist)
            val artistSuspicious = ArtistNames.isSuspicious(track.artist) ||
                isUnknownArtist(track.artist)
            val artist = if (artistSuspicious) {
                ArtistNames.clean(metadata.artist?.takeIf { it.isNotBlank() } ?: track.artist)
            } else {
                existingCleanedArtist.ifBlank { track.artist }
            }
            val titleMissing = track.title.isBlank() ||
                track.title.equals(track.uri?.substringAfterLast('/')?.substringBeforeLast('.'), true)
            val title = if (titleMissing) {
                metadata.title?.takeIf { it.isNotBlank() } ?: track.title
            } else {
                track.title
            }
            // Обложку улучшаем, если текущая — превью YouTube или её нет, но
            // не затираем встроенную обложку локального файла.
            val existingArtworkUrl = track.artworkUrl?.takeIf { it.isNotBlank() }
            // «Битый» content://…/albumart из MediaStore — не настоящая обложка:
            // он не должен блокировать онлайн-обложку.
            val hasEmbeddedArtwork = !track.artworkUri.isNullOrBlank() &&
                !isMediaStoreAlbumArtUri(track.artworkUri)
            val artworkUrl = when {
                metadata.coverUrl.isNullOrBlank() -> existingArtworkUrl
                existingArtworkUrl == null && !hasEmbeddedArtwork -> metadata.coverUrl
                existingArtworkUrl == null -> null
                isLowResolutionArtwork(existingArtworkUrl) -> metadata.coverUrl
                else -> existingArtworkUrl
            }
            metadata.artistImageUrl?.let { extra["artistImageUrl"] = it }
            metadata.artistBio?.let { extra["artistBio"] = it }
            metadata.genre?.let { extra["genre"] = it }
            metadata.provider.takeIf { it.isNotBlank() }?.let { extra["metadataProvider"] = it }
            track.copy(
                title = title,
                artist = artist,
                album = album,
                artworkUrl = artworkUrl,
                lyrics = track.lyrics?.takeIf { it.isNotBlank() } ?: metadata.lyrics,
                syncedLyrics = track.syncedLyrics?.takeIf { it.isNotBlank() }
                    ?: metadata.syncedLyrics,
                extra = extra,
            )
        }
        if (changed) {
            publishTracks(updated)
            persistTracks()
        }
    }

    /**
     * Явное применение «телеметрии» из файла пользователя: перекрывает даже уже
     * заполненные альбом/год/обложку/текст, потому что это осознанный импорт
     * подготовленных данных, а не автоматический поиск по каталогам.
     */
    suspend fun applyImportedMetadata(
        trackId: String,
        album: String?,
        year: Int?,
        coverUrl: String?,
        lyrics: String?,
    ) {
        val index = _tracks.value.indexOfFirst { it.id == trackId }
        if (index < 0) return
        val track = _tracks.value[index]
        val extra = track.extra.toMutableMap()
        if (year != null && year > 0) extra["year"] = year.toString()
        if (!coverUrl.isNullOrBlank()) extra["coverUrl"] = coverUrl
        extra["metadataFromFile"] = "1"
        val updated = track.copy(
            album = album?.takeIf { it.isNotBlank() } ?: track.album,
            artworkUrl = coverUrl?.takeIf { it.isNotBlank() } ?: track.artworkUrl,
            lyrics = lyrics?.takeIf { it.isNotBlank() } ?: track.lyrics,
            extra = extra,
        )
        val list = _tracks.value.toMutableList()
        list[index] = updated
        publishTracks(list)
        persistTracks()
    }

    /** Треки, у которых нет обложки/года/текста — кандидаты на обогащение. */
    fun metadataCandidates(): List<Track> {
        val now = System.currentTimeMillis()
        return _tracks.value.filter { track ->
            if (track.source != Source.LOCAL) return@filter false
            // Недавно проверенные не трогаем: всё найденное уже в постоянном кэше.
            val checkedAt = track.extra["metadataCheckedAt"]?.toLongOrNull()
            if (checkedAt != null && now - checkedAt < METADATA_RECHECK_MS) return@filter false
            val album = track.album
            // «Music»/«Download» из MediaStore — не альбом, считаем поле пустым.
            val albumMissing = album.isNullOrBlank() || album.lowercase() in PLACEHOLDER_ALBUMS
            // Сборник, подставленный ранее, — повод перепроверить альбом.
            val albumLooksLikeCompilation = album != null &&
                COMPILATION_HINTS.any { hint -> album.lowercase().contains(hint) }
            val artistMissing = ArtistNames.isSuspicious(track.artist)
            val artistExtrasMissing = track.extra["artistImageUrl"].isNullOrBlank() &&
                track.extra["artistBio"].isNullOrBlank()
            albumMissing ||
                albumLooksLikeCompilation ||
                artistMissing ||
                artistExtrasMissing ||
                (track.artworkUrl.isNullOrBlank() && track.artworkUri.isNullOrBlank()) ||
                track.lyrics.isNullOrBlank() ||
                track.syncedLyrics.isNullOrBlank()
        }
    }

    /**
     * Помечает треки как проверенные: повторный онлайн-поиск для них не запускается
     * [METADATA_RECHECK_MS], данные уже лежат в MetadataCache на диске.
     */
    suspend fun markMetadataChecked(trackIds: Collection<String>) {        if (trackIds.isEmpty()) return
        val ids = trackIds.toSet()
        val now = System.currentTimeMillis().toString()
        var changed = false
        val updated = _tracks.value.map { track ->
            if (track.id in ids) {
                changed = true
                track.copy(extra = track.extra + ("metadataCheckedAt" to now))
            } else {
                track
            }
        }
        if (changed) {
            publishTracks(updated)
            persistTracks()
        }
    }

    /** Сбрасывает отметки «проверено» — после очистки кэша метаданных. */
    suspend fun resetMetadataChecked() {
        var changed = false
        val updated = _tracks.value.map { track ->
            if (track.extra.containsKey("metadataCheckedAt")) {
                changed = true
                track.copy(extra = track.extra - "metadataCheckedAt")
            } else {
                track
            }
        }
        if (changed) {
            publishTracks(updated)
            persistTracks()
        }
    }

    // ---------- Группировки ----------

    fun albums(): List<Album> {
        val local = _tracks.value.filter { it.source == Source.LOCAL }
        return local
            .groupBy { track ->
                val album = track.album
                    ?.takeIf { name -> name.isNotBlank() && name.lowercase() !in PLACEHOLDER_ALBUMS }
                    ?: "Без альбома"
                album to ArtistNames.clean(track.artist)
            }
            .map { (key, list) ->
                Album(
                    name = key.first,
                    artist = key.second,
                    trackIds = list.sortedBy { it.extra["trackNo"]?.toIntOrNull() ?: 0 }.map { it.id },
                    artworkUri = list.firstOrNull { !it.artworkUri.isNullOrBlank() }?.artworkUri,
                    artworkUrl = list.firstOrNull { !it.artworkUrl.isNullOrBlank() }?.artworkUrl,
                    year = list.firstOrNull { it.extra["year"] != null }?.extra?.get("year")?.toIntOrNull(),
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    fun artists(): List<Artist> {
        val local = _tracks.value.filter { it.source == Source.LOCAL }
        return local
            .groupBy { ArtistNames.clean(it.artist) }
            .map { (name, list) ->
                // Сначала фото исполнителя из TheAudioDB/Deezer/Wikipedia,
                // затем — обложка его альбома как запасной вариант.
                val artistImage = list.firstNotNullOfOrNull { track ->
                    track.extra["artistImageUrl"]?.takeIf { it.isNotBlank() }
                }
                val artistImageUri = list.firstNotNullOfOrNull { track ->
                    track.extra["artistImageUri"]?.takeIf { it.isNotBlank() }
                }
                Artist(
                    name = name,
                    trackIds = list.map { it.id },
                    artworkUri = artistImageUri
                        ?: list.firstOrNull { !it.artworkUri.isNullOrBlank() }?.artworkUri,
                    artworkUrl = artistImage
                        ?: list.firstOrNull { !it.artworkUrl.isNullOrBlank() }?.artworkUrl,
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    /**
     * Поиск по локальной фонотеке.
     *
     * Нормализованные поля и токены индексируются один раз на версию библиотеки
     * (см. [currentSearchIndex]): на каждый запрос больше нет нормализации всех
     * треков и аллокаций regexp'ом. Ранжирование прежнее: title > artist > album,
     * при равенстве score — по названию трека.
     */
    fun searchLocal(query: String): List<Track> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val startedAt = System.nanoTime()
        // Только файлы на устройстве: кэш онлайн-треков не должен попадать
        // в секцию «На устройстве» и дублировать результаты стриминга.
        val results = currentSearchIndex().search(trimmed)
        if (LOG_SEARCH_TIMING) {
            Log.i(
                TAG,
                "search '${trimmed}': ${elapsedMs(startedAt)} ms, ${results.size} local results",
            )
        }
        return results
    }

    /**
     * Актуальный поисковый индекс. Перестраивается только после изменения
     * библиотеки ([publishTracks] увеличивает версию), поэтому повторные запросы
     * бесплатны. Потокобезопасно: immutable-индекс читается без блокировки.
     */
    private fun currentSearchIndex(): LibrarySearchIndex {
        val version = libraryVersion
        val cached = searchIndexCache
        if (cached != null && cached.first == version) return cached.second
        synchronized(searchIndexLock) {
            val versionNow = libraryVersion
            val current = searchIndexCache
            if (current != null && current.first == versionNow) return current.second
            val built = LibrarySearchIndex.build(_tracks.value)
            searchIndexCache = versionNow to built
            return built
        }
    }

    /** Публикует новый список треков и сбрасывает кэш поискового индекса. */
    private fun publishTracks(next: List<Track>) {
        _tracks.value = next
        synchronized(searchIndexLock) {
            libraryVersion += 1
            searchIndexCache = null
        }
    }

    /** Строит поисковый индекс вне главного потока (для прогрева после скана/старта). */
    private suspend fun warmSearchIndex() {
        withContext(Dispatchers.Default) { currentSearchIndex() }
    }

    private fun elapsedMs(startedAtNanos: Long): Long =
        (System.nanoTime() - startedAtNanos) / 1_000_000

    // ---------- persistence ----------

    private suspend fun persistTracks() =
        JsonFile.write(context, FILE_TRACKS, trackSerializer, _tracks.value)

    private suspend fun persistPlaylists() =
        JsonFile.write(context, FILE_PLAYLISTS, playlistSerializer, _playlists.value)

    companion object {
        const val FILE_TRACKS = "library_tracks.json"
        const val FILE_PLAYLISTS = "library_playlists.json"
        const val FILE_FAVORITES = "library_favorites.json"

        /** Тег logcat-метрик производительности: `adb logcat -s LibraryPerf`. */
        private const val TAG = "LibraryPerf"

        /** Логировать время поиска (дёшево, но полезно для ловли регрессий). */
        private const val LOG_SEARCH_TIMING = true

        /** Префикс «битых» обложек альбомов MediaStore (Android 10+ часто пустые). */
        const val MEDIASTORE_ALBUM_ART_PREFIX = "content://media/external/audio/albumart"

        /** Повторная проверка метаданных — не чаще раза в сутки. */
        const val METADATA_RECHECK_MS = 24L * 60 * 60 * 1000

        /** Имена папок, которые MediaStore отдаёт как «альбом» — это не альбомы. */
        val PLACEHOLDER_ALBUMS = setOf(
            "music", "download", "downloads", "audio", "sounds", "ringtones",
            "podcasts", "notifications", "unknown", "unknown album",
            "без альбома", "<unknown>", "разное", "various",
        )

        /**
         * Признаки сборников: если MusicBrainz подставил сборник вместо студийного
         * альбома, при следующем обогащении его можно заменить на нормальный.
         */
        val COMPILATION_HINTS = listOf(
            "collection", "compilation", "anniversary", "greatest hits",
            "the best of", "best of", "anthology", "box set", "hits",
            "сборник", "лучшее", "хиты",
        )

        /** Исполнители-заглушки: их можно заменить данными MusicBrainz. */
        fun isUnknownArtist(value: String): Boolean {
            return ArtistNames.isPlaceholder(value)
        }

        /** Обложка-превью YouTube/каталога: её можно заменить более крупной. */
        fun isLowResolutionArtwork(url: String): Boolean {
            val wh = Regex("""=w(\d+)-h(\d+)""").find(url)
            if (wh != null) {
                val width = wh.groupValues.getOrNull(1)?.toIntOrNull() ?: 0
                val height = wh.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
                return minOf(width, height) in 1..599
            }
            val s = Regex("""=s(\d+)""").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
            return s != null && s in 1..599
        }
    }
}
