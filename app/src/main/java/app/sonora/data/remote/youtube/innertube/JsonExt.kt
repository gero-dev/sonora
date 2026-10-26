package app.sonora.data.remote.youtube.innertube

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Маленькие помощники для навигации по ответам InnerTube.
 * Ответы огромные и нестабильные по форме, поэтому ходим по JSON, а не по DTO.
 */

internal fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

internal fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

internal fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

/** Рекурсивно находит все объекты с ключом [key] на любой глубине. */
internal fun JsonElement.deepObjects(key: String): Sequence<JsonObject> = sequence {
    when (this@deepObjects) {
        is JsonObject -> {
            this@deepObjects[key]?.asObjectOrNull()?.let { yield(it) }
            this@deepObjects.values.forEach { yieldAll(it.deepObjects(key)) }
        }

        is JsonArray -> this@deepObjects.forEach { yieldAll(it.deepObjects(key)) }

        else -> Unit
    }
}

/** Текст из `{ "runs": [...] }` или `{ "simpleText": "..." }`. */
internal fun JsonElement?.text(): String? {
    val obj = this.asObjectOrNull() ?: return null
    obj.str("simpleText")?.let { return it }
    val runs = obj.arr("runs") ?: return null
    val text = runs.mapNotNull { it.asObjectOrNull()?.str("text") }.joinToString("")
    return text.ifBlank { null }
}

/** Все runs по порядку: нужны, чтобы отличить исполнителя от альбома по «•». */
internal fun JsonElement?.runTexts(): List<String> {
    val obj = this.asObjectOrNull() ?: return emptyList()
    obj.str("simpleText")?.let { return listOf(it) }
    val runs = obj.arr("runs") ?: return emptyList()
    return runs.mapNotNull { it.asObjectOrNull()?.str("text") }
}

internal fun JsonElement?.firstThumbnailUrl(): String? {
    val obj = this.asObjectOrNull() ?: return null
    // musicThumbnailRenderer.thumbnail.thumbnails, croppedSquareThumbnailRenderer, ...
    val thumbnails = findThumbnails(obj) ?: return null
    return thumbnails
        .mapNotNull { it.asObjectOrNull()?.str("url") }
        .lastOrNull { it.isNotBlank() }
}

private fun findThumbnails(obj: JsonObject): JsonArray? {
    obj.arr("thumbnails")?.let { return it }
    obj.values.forEach { value ->
        val child = value.asObjectOrNull() ?: return@forEach
        findThumbnails(child)?.let { return it }
    }
    return null
}

/** ISO-8601 (`PT1H2M3S`, `P1DT2H`) → миллисекунды. */
internal fun parseIsoDuration(text: String?): Long {
    if (text.isNullOrBlank()) return 0L
    val match = Regex("""P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?""")
        .find(text.trim().uppercase())
        ?: return 0L
    val days = match.groupValues[1].toLongOrNull() ?: 0L
    val hours = match.groupValues[2].toLongOrNull() ?: 0L
    val minutes = match.groupValues[3].toLongOrNull() ?: 0L
    val seconds = match.groupValues[4].toLongOrNull() ?: 0L
    return (((days * 24 + hours) * 60 + minutes) * 60 + seconds) * 1000L
}

/** `5:02`, `1:02:03` → миллисекунды. */
internal fun parseClockDuration(text: String?): Long {
    if (text.isNullOrBlank()) return 0L
    val parts = text.trim().split(':').mapNotNull { it.toLongOrNull() }
    if (parts.isEmpty()) return 0L
    var seconds = 0L
    parts.forEach { seconds = seconds * 60 + it }
    return seconds * 1000L
}

/** Похоже ли значение на длительность (`3:42`, `1:03:12`). */
internal fun looksLikeDuration(text: String): Boolean =
    Regex("""^\d{1,2}:\d{2}(?::\d{2})?$""").matches(text.trim())

/** YouTube отдаёт title с HTML-сущностями (`&amp;#39;`, `&quot;`). */
internal fun decodeHtml(text: String?): String {
    if (text.isNullOrBlank()) return ""
    return text
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
}
