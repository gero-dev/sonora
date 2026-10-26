package app.sonora.core.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Простое JSON-хранилище в filesDir: библиотека, плейлисты, кэш метаданных. */
object JsonFile {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    suspend fun <T> read(context: Context, name: String, serializer: KSerializer<T>, fallback: T): T =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = File(context.filesDir, name)
                if (!file.exists()) fallback
                else json.decodeFromString(serializer, file.readText())
            }.getOrElse { fallback }
        }

    suspend fun <T> write(context: Context, name: String, serializer: KSerializer<T>, value: T) =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = File(context.filesDir, name)
                val tmp = File(context.filesDir, "$name.tmp")
                tmp.writeText(json.encodeToString(serializer, value))
                if (file.exists()) file.delete()
                tmp.renameTo(file)
            }
        }
}
