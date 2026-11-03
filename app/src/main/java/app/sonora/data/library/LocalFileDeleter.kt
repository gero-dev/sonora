package app.sonora.data.library

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Удаление файла локального трека с устройства.
 *
 * Раньше удалить можно было только то, что скачано из стриминга
 * (`DownloadManager.delete`), а собственные mp3 пользователя — нет: файл лежал в
 * общей памяти, и приложение не имело права его трогать.
 *
 * На Android 10+ (API 29) приложение не может удалить чужой файл в общей памяти
 * напрямую — система отдаёт `RecoverableSecurityException` и требует
 * подтверждения владельца. Поэтому API разделён на две части:
 *
 *  * [resolveFile] — чистая функция, достаёт путь к файлу из uri трека;
 *    не требует разрешений и потому тестируется на JVM;
 *  * [deletePermissionsIntent] — то, что нужно показать пользователю на API 29
 *    и ниже 33, когда система требует подтверждение;
 *  * [deleteFile] — удаление, когда разрешение уже есть.
 *
 * Разбирать uri вручную приходится потому, что у `file:`-uri нет ContentResolver-
 * пути: `ContentResolver.delete` умеет только `content://`. Оба вида встречаются
 * в библиотеке: свои mp3 из MediaStore приходят как `content://`, а скачанное
 * приложением — как `file://`.
 */
internal object LocalFileDeleter {

    private const val TAG = "LocalFileDelete"

    /** `file:///storage/...` → `File`. Для `content://` возвращает null. */
    fun resolveFile(uri: String?): File? {
        if (uri.isNullOrBlank()) return null
        if (!uri.startsWith("file:", ignoreCase = true)) return null
        val path = uri.removePrefix("file://").removePrefix("file:")
        if (path.isBlank()) return null
        return File(Uri.decode(path))
    }

    /**
     * Нужно ли спрашивать у системы разрешение перед удалением.
     *
     * Только для `content://` на API 29..32: с Android 13 (API 33) приложение
     * может удалять свои и чужие аудиофайлы в общей памяти напрямую, а на
     * API <= 28 достаточно обычного разрешения на запись.
     */
    fun needsUserConsent(uri: String?): Boolean {
        if (uri.isNullOrBlank()) return false
        if (!uri.startsWith("content:", ignoreCase = true)) return false
        return Build.VERSION.SDK_INT in Build.VERSION_CODES.Q until Build.VERSION_CODES.TIRAMISU
    }

    /**
     * Пытается удалить файл. Возвращает `true`, если файла больше нет — включая
     * случай, когда его не было изначально: цель «файла нет» достигнута, и
     * повторный вызов не должен считаться ошибкой.
     *
     * [consentGiven] означает, что пользователь уже подтвердил удаление в
     * системном диалоге, поэтому одноразовое исключение можно погасить.
     */
    fun deleteFile(
        resolver: ContentResolver,
        uri: String?,
        consentGiven: Boolean = false,
    ): Boolean {
        if (uri.isNullOrBlank()) return false

        // Скачанное приложением: обычный файл, никаких разрешений не нужно.
        resolveFile(uri)?.let { file ->
            val gone = runCatching { !file.exists() || file.delete() }
                .getOrElse { error ->
                    Log.w(TAG, "не удалось удалить ${file.name}: ${error.message}")
                    false
                }
            // MediaStore продолжит показывать удалённый файл, пока не узнает о
            // пропаже, и при следующем сканировании вернёт его обратно.
            if (gone) notifyRemoved(resolver, file.absolutePath)
            return gone
        }

        if (!uri.startsWith("content:", ignoreCase = true)) return false
        return runCatching {
            resolver.delete(Uri.parse(uri), null, null) > 0
        }.getOrElse { error ->
            Log.w(
                TAG,
                if (consentGiven) "удаление отклонено: ${error.message}"
                else "нужно подтверждение пользователя: ${error.message}",
            )
            false
        }
    }

    /** Сообщает MediaStore, что файла по этому пути больше нет. */
    private fun notifyRemoved(resolver: ContentResolver, absolutePath: String) {
        runCatching {
            resolver.delete(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.MediaColumns.DATA} = ?",
                arrayOf(absolutePath),
            )
        }.onFailure { Log.w(TAG, "MediaStore не принял удаление: ${it.message}") }
    }

    /**
     * Есть ли вообще файл за этим uri. Для `content://` проверяется
     * существование записи, для `file://` — файла.
     */
    fun exists(context: Context, uri: String?): Boolean {
        if (uri.isNullOrBlank()) return false
        resolveFile(uri)?.let { return it.isFile }
        if (!uri.startsWith("content:", ignoreCase = true)) return false
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
        }.getOrDefault(false)
    }
}
