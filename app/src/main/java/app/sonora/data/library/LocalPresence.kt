package app.sonora.data.library

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import app.sonora.core.model.Source
import app.sonora.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Дешёвая проверка: какие локальные треки ещё существуют на устройстве.
 *
 * Зачем это нужно. Пользователь может удалить файл вне Sonora — через
 * файловый менеджер, из другого плеера, очисткой памяти. Раньше приложение об
 * этом не узнавало: трек оставался в библиотеке, открывался, но не играл, а
 * `uri` вёл в никуда. После перезапуска трек не исчезал, потому что
 * `LibraryRepository.uriExists` возвращал `true` для **любого** `content://`
 * URI, даже когда записи в MediaStore уже нет.
 *
 * Зачем отдельный объект, а не просто пересканирование. Полный `scanLocal`
 * читает метаданные каждого файла через `MediaMetadataRetriever` —
 * это сотни миллисекунд на большой фонотеке. Здесь задача обратная и намного
 * более узкая: не «узнать треки», а «узнать, чего больше нет». Поэтому:
 *
 *  * для треков из MediaStore — **один** курсор с проекцией из одного `_ID`
 *    на всю библиотеку; никаких `openAssetFileDescriptor` по треку;
 *  * для файлов в папках приложения — **один** `File.list()` на каталог,
 *    а не `File.isFile` на каждый файл (на общей памяти это FUSE-вызов).
 *
 * Итог: стоимость проверки не зависит от числа треков так, как полный скан, и
 * её можно запускать при возврате в приложение, ничего не подвешивая.
 */
internal object LocalPresence {

    private const val TAG = "LocalPresence"

    /** `content://media/external/audio/media/<id>` — формат URI из MediaStore. */
    private const val MEDIA_URI_PREFIX = "content://media/external/audio/media/"

    /**
     * Возвращает id локальных треков, файлов которых на устройстве больше нет.
     *
     * Пустое множество означает «всё на месте». Если проверку выполнить не
     * удалось (нет разрешения на чтение медиа), тоже возвращается пустое
     * множество: лучше оставить лишний трек, чем вычистить библиотеку целиком
     * из-за одной неудачной проверки.
     *
     * Сетевые треки не проверяются вовсе — у них нет файла на устройстве, и
     * «отсутствие файла» для них нормальное состояние.
     */
    suspend fun missingIds(context: Context, tracks: List<Track>): Set<String> =
        withContext(Dispatchers.IO) {
            val local = tracks.filter { it.source == Source.LOCAL && !it.uri.isNullOrBlank() }
            if (local.isEmpty()) return@withContext emptySet()

            // Один запрос на всю библиотеку. null — запрос не удался.
            val presentMediaIds = presentMediaIds(context)

            // Каталоги, которые понадобится листить, собираем заранее: так на
            // каждый каталог приходится ровно один list(), даже если в нём
            // сотня проверяемых треков.
            val relevantDirs = local.asSequence()
                .mapNotNull { track -> filePathOf(track.uri) }
                .mapNotNull { path -> File(path).parent }
                .toSet()
            val listings = HashMap<String, Set<String>?>(relevantDirs.size)
            relevantDirs.forEach { dir ->
                listings[dir] = runCatching { File(dir).list()?.toSet() }.getOrNull()
            }

            val isFilePresent: (String) -> Boolean = { path ->
                val file = File(path)
                val dir = file.parent
                val listing = if (dir == null) null else listings[dir]
                if (listing != null) {
                    file.name in listing
                } else {
                    // Каталог не залистился (или его нет в списке) — честная
                    // точечная проверка вместо догадки.
                    runCatching { file.isFile }.getOrDefault(false)
                }
            }

            val missing = missingIds(local, presentMediaIds, isFilePresent)
            if (missing.isNotEmpty()) {
                Log.i(TAG, "проверка наличия: нет ${missing.size} из ${local.size}")
            }
            missing
        }

    /**
     * Чистая часть проверки: по списку треков, множеству живых id в MediaStore
     * и предикату существования файла вычисляет пропавшие id.
     *
     * Вынесена отдельно, чтобы её можно было покрыть JVM-тестом без Android:
     * именно здесь живёт вся логика решения, а не в курсоре.
     *
     * @param presentMediaIds id, которые MediaStore отдал сейчас; `null` —
     *   запрос не удался, и такие треки заведомо не считаются пропавшими.
     */
    internal fun missingIds(
        tracks: List<Track>,
        presentMediaIds: Set<Long>?,
        isFilePresent: (String) -> Boolean,
    ): Set<String> {
        if (tracks.isEmpty()) return emptySet()
        val missing = HashSet<String>()
        tracks.forEach { track ->
            val uri = track.uri ?: return@forEach
            val mediaId = mediaIdOf(uri)
            if (mediaId != null) {
                if (presentMediaIds != null && mediaId !in presentMediaIds) {
                    missing += track.id
                }
                return@forEach
            }
            val path = filePathOf(uri) ?: return@forEach
            if (!isFilePresent(path)) missing += track.id
        }
        return missing
    }

    /** id из `content://media/external/audio/media/<id>`; иначе null. */
    internal fun mediaIdOf(uri: String?): Long? {
        if (uri.isNullOrBlank()) return null
        if (!uri.startsWith(MEDIA_URI_PREFIX, ignoreCase = true)) return null
        return uri.substring(MEDIA_URI_PREFIX.length).substringBefore('/').toLongOrNull()
    }

    /**
     * Путь из `file:///storage/...`; для прочих схем null.
     *
     * Схема срезается без учёта регистра: `removePrefix` регистрозависим, и на
     * `FILE:///…` путь оставался вместе со схемой — файл «не находился», и
     * живой трек вычищался из библиотеки.
     */
    internal fun filePathOf(uri: String?): String? {
        if (uri.isNullOrBlank()) return null
        if (!uri.startsWith("file:", ignoreCase = true)) return null
        var path = uri.substring("file:".length)
        if (path.startsWith("//")) {
            // `file://<authority>/<path>`: authority отбрасываем, путь оставляем.
            // Для обычного `file:///storage/...` authority пустой, и результатом
            // снова становится `/storage/...`.
            val rest = path.substring(2)
            val slash = rest.indexOf('/')
            path = if (slash >= 0) rest.substring(slash) else ""
        }
        if (path.isBlank()) return null
        return percentDecode(path)
    }

    /**
     * Раскодирует `%20` и прочие percent-escape.
     *
     * Своя реализация вместо `android.net.Uri.decode` намеренно: вся логика
     * этого объекта должна работать на JVM без Android, иначе её нельзя покрыть
     * тестом (в юнит-тестах `Uri.decode` — заглушка, возвращающая null, и
     * проверка существования файла молча ломалась бы на путях с пробелами).
     */
    private fun percentDecode(value: String): String {
        if ('%' !in value) return value
        val bytes = ArrayList<Byte>(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '%' && index + 2 <= value.length - 1) {
                val hex = value.substring(index + 1, index + 3)
                val decoded = hex.toIntOrNull(16)
                if (decoded != null) {
                    bytes += decoded.toByte()
                    index += 3
                    continue
                }
            }
            bytes += char.toString().toByteArray(Charsets.UTF_8).toList()
            index += 1
        }
        return runCatching { String(bytes.toByteArray(), Charsets.UTF_8) }.getOrDefault(value)
    }

    /**
     * Все id аудио, которые MediaStore видит сейчас.
     *
     * Проекция — только `_ID`: этого достаточно, а чем уже курсор, тем дешевле
     * его чтение. `IS_MUSIC` намеренно НЕ фильтруется: файл, у которого слетел
     * флаг, всё равно существует, и считать его удалённым было бы ошибкой —
     * библиотека потеряла бы живые треки.
     *
     * Возвращает `null`, если запрос не удался (например, отозвано разрешение).
     */
    private fun presentMediaIds(context: Context): Set<Long>? = runCatching {
        val ids = HashSet<Long>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID),
            null,
            null,
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            while (cursor.moveToNext()) {
                ids += cursor.getLong(idColumn)
            }
        } ?: return@runCatching null
        ids
    }.getOrElse { error ->
        Log.w(TAG, "MediaStore недоступен, проверка пропущена: ${error.message}")
        null
    }

    /**
     * Снимок id на несколько секунд: во время одного скана `uriExists` зовётся
     * по треку, и без кэша это был бы отдельный курсор на каждый вызов.
     */
    @Volatile
    private var cachedAtMs: Long = 0L
    @Volatile
    private var cachedIds: Set<Long>? = null

    /** id треков, живых в MediaStore прямо сейчас (с коротким кэшем). */
    fun presentIds(context: Context): Set<Long>? {
        val now = System.currentTimeMillis()
        val cached = cachedIds
        if (cached != null && now - cachedAtMs < CACHE_TTL_MS) return cached
        val fresh = presentMediaIds(context) ?: return cached
        cachedIds = fresh
        cachedAtMs = now
        return fresh
    }

    /**
     * Точечная проверка одного `content://` URI, не покрытого MediaStore-аудио
     * (например, `content://` из SAF). Один вызов, поэтому дорогой путь
     * `openAssetFileDescriptor` здесь допустим.
     */
    fun exists(context: Context, uri: String?): Boolean {
        if (uri.isNullOrBlank()) return false
        filePathOf(uri)?.let { path -> return runCatching { File(path).isFile }.getOrDefault(false) }
        if (!uri.startsWith("content:", ignoreCase = true)) return false
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
        }.getOrDefault(false)
    }

    private const val CACHE_TTL_MS = 5_000L
}
