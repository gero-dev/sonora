package app.sonora.data.library

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Обложки из файлов, без сети: встроенная картинка аудиофайла и `cover.*` рядом с ним.
 *
 * Встроенная обложка кэшируется в `filesDir/covers/<sha1(trackId)>.jpg` (или `.png`
 * по сигнатуре файла), поэтому повторное сканирование не дёргает метаданные заново.
 *
 * Кэшируется и ОТСУТСТВИЕ обложки — пустым файлом `…​.none`. Без этого файлы без
 * встроенной картинки (а их большинство: на реальной фонотеке нашлось 355 треков
 * и лишь 61 обложка) заново открывались `MediaMetadataRetriever` при каждом
 * полном скане: извлечение возвращало `null`, результат никуда не записывался, и
 * следующий скан повторял ту же работу. На холодном скане это давало ~2 с на
 * трек — около двух минут на 355 файлов.
 */
object LocalArtwork {

    private const val COVERS_DIR = "covers"

    /** Метка «встроенной обложки нет»: пустой файл рядом с кэшем картинок. */
    private const val NONE_SUFFIX = ".none"

    /** Имена файлов-обложек в порядке приоритета. */
    private val COVER_BASE_NAMES = listOf("cover", "folder", "albumart", "front")

    /** Расширения картинок в порядке приоритета. */
    private val IMAGE_EXTENSIONS = listOf("jpg", "jpeg", "png", "webp")

    /** Сигнатура PNG: 89 50 4E 47 0D 0A 1A 0A. */
    private val PNG_SIGNATURE = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /**
     * Достаёт встроенную обложку трека и возвращает file-URI закэшированного файла.
     * Если файл уже извлечён раньше — сразу возвращает его. Известное отсутствие
     * обложки тоже берётся из кэша. Ошибки → null.
     */
    suspend fun embedded(context: Context, trackId: String, uri: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val appContext = context.applicationContext
                val dir = File(appContext.filesDir, COVERS_DIR)
                val hash = sha1(trackId)
                val jpg = File(dir, "$hash.jpg")
                val png = File(dir, "$hash.png")
                if (jpg.isFile && jpg.length() > 0L) return@runCatching Uri.fromFile(jpg).toString()
                if (png.isFile && png.length() > 0L) return@runCatching Uri.fromFile(png).toString()
                // Уже выясняли, что обложки нет, — не открываем файл заново.
                if (File(dir, "$hash$NONE_SUFFIX").isFile) return@runCatching null

                var retriever: MediaMetadataRetriever? = null
                try {
                    retriever = createRetriever(appContext, uri)
                    val picture = retriever.embeddedPicture
                        ?: return@runCatching markAbsent(dir, hash)
                    if (picture.isEmpty()) return@runCatching markAbsent(dir, hash)
                    if (!dir.isDirectory && !dir.mkdirs()) return@runCatching null
                    val target = if (isPng(picture)) png else jpg
                    // Пишем через .tmp и переименовываем: недописанный кэш не должен
                    // навсегда остаться «валидной» обложкой.
                    val tmp = File(dir, "${target.name}.tmp")
                    tmp.writeBytes(picture)
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true)
                        runCatching { tmp.delete() }
                    }
                    Uri.fromFile(target).toString()
                } finally {
                    runCatching { retriever?.release() }
                }
            }.getOrNull()
        }

    /**
     * Запоминает, что у трека нет встроенной обложки, и возвращает `null` —
     * чтобы вызывающий код остался прежним.
     *
     * Файл-метка пустой, поэтому её создание не может стоить дорого и не может
     * оставить «битую» картинку. Если записать не удалось, просто возвращаем
     * `null`: отсутствие кэша означает лишь повторную проверку в следующий раз.
     */
    private fun markAbsent(dir: File, hash: String): String? {
        runCatching {
            if (!dir.isDirectory && !dir.mkdirs()) return@runCatching
            File(dir, "$hash$NONE_SUFFIX").createNewFile()
        }
        return null
    }

    /**
     * Кэш листингов папок на время скана.
     *
     * `File.listFiles()` на общей памяти идёт через FUSE и стоит дорого, а
     * вызывался он многократно для одной и той же папки: `folderCover` — раз,
     * и `SidecarMetadata.resolveSibling` — ещё до четырёх раз на каждый трек
     * (`.json`, `.nfo`, `.lrc`, `album.json`). На реальной фонотеке 345 из 355
     * файлов лежат в одной папке `Music/`, то есть один и тот же каталог
     * перечислялся больше тысячи раз за скан.
     *
     * Кэш живёт только на время скана: [clearListingCache] вызывается перед его
     * началом, чтобы появившиеся за это время файлы не потерялись и чтобы
     * устаревший листинг не пережил изменение папки.
     */
    private val listingCache = java.util.concurrent.ConcurrentHashMap<String, List<File>>()

    /** Сбрасывает кэш листингов. Вызывать перед полным сканом. */
    fun clearListingCache() {
        listingCache.clear()
    }

    /**
     * Содержимое папки с кэшированием на время скана. Возвращает только файлы.
     * `null` от [File.listFiles] превращается в пустой список: вызывающему коду
     * в обоих случаях делать нечего.
     */
    fun listFilesCached(dir: File): List<File> =
        listingCache.getOrPut(dir.absolutePath) {
            runCatching { dir.listFiles()?.filter { it.isFile }.orEmpty() }.getOrDefault(emptyList())
        }

    /** Первый найденный `cover|folder|albumart|front` (jpg/jpeg/png/webp) в папке. */
    fun folderCover(dir: File): String? = runCatching {
        val files = listFilesCached(dir)
        COVER_BASE_NAMES.forEach { base ->
            IMAGE_EXTENSIONS.forEach { extension ->
                val match = files.firstOrNull { file ->
                    file.nameWithoutExtension.equals(base, ignoreCase = true) &&
                        file.extension.equals(extension, ignoreCase = true)
                }
                if (match != null) return@runCatching Uri.fromFile(match).toString()
            }
        }
        null
    }.getOrNull()

    // ---------------------------------------------------------------- внутреннее

    /**
     * Открывает аудиофайл: для file-URI используем путь, для content-URI —
     * `setDataSource(context, uri)` (работает на всех поддерживаемых API).
     */
    private fun createRetriever(context: Context, uri: String): MediaMetadataRetriever {
        val parsed = Uri.parse(uri)
        return MediaMetadataRetriever().apply {
            if (uri.startsWith("file:", ignoreCase = true)) {
                setDataSource(parsed.path ?: parsed.toString())
            } else {
                setDataSource(context, parsed)
            }
        }
    }

    private fun isPng(bytes: ByteArray): Boolean {
        if (bytes.size < PNG_SIGNATURE.size) return false
        return PNG_SIGNATURE.indices.all { index ->
            (bytes[index].toInt() and 0xFF) == PNG_SIGNATURE[index]
        }
    }

    /** SHA-1 в hex (без локали): стабильное имя файла кэша. */
    private fun sha1(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val int = byte.toInt() and 0xFF
                append(hex[int ushr 4])
                append(hex[int and 0x0F])
            }
        }
    }
}
