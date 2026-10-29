package app.sonora.core.util

/**
 * Сигнатура файла для инкрементального кэша сканирования.
 *
 * [folder] — тег папки/источника: для MediaStore это `@mediastore:<RELATIVE_PATH>`,
 * для папки импорта — её абсолютный путь. Одинаковые имена файлов из разных папок
 * не должны смешиваться в кэше, поэтому папка входит в [key].
 *
 * Чистый Kotlin без Android-зависимостей — сравнение кэша покрыто JVM-тестами.
 */
data class ScanSignature(
    val path: String,
    val size: Long,
    val dateModified: Long,
    val folder: String,
) {
    /** Уникальный ключ записи в кэше: папка + путь. */
    val key: String get() = folder + KEY_SEPARATOR + path

    companion object {
        /** Управляющий символ, которого не бывает в путях. */
        const val KEY_SEPARATOR = "\u0001"
    }
}

/** Результат сравнения текущих файлов с прошлым кэшем. */
data class ScanDiff(
    /** Не изменились: метаданные можно взять из кэша, retriever не нужен. */
    val unchanged: Set<String> = emptySet(),
    /** Новые или изменившиеся: метаданные нужно прочитать заново. */
    val changed: Set<String> = emptySet(),
    /** Файлов больше нет: записи кэша удаляются. */
    val removed: Set<String> = emptySet(),
)

/**
 * Сравнивает текущий список файлов с прошлым кэшем.
 *
 * [previous] — ключ → сигнатура из кэша (только для той папки/источника,
 * который сейчас сканируется: чужие записи не должны попадать в [ScanDiff.removed]).
 * Дубликаты в [current] учитываются один раз.
 */
fun diffScanSignatures(
    current: List<ScanSignature>,
    previous: Map<String, ScanSignature>,
): ScanDiff {
    if (previous.isEmpty()) {
        return ScanDiff(changed = current.mapTo(LinkedHashSet()) { it.key })
    }
    val unchanged = LinkedHashSet<String>()
    val changed = LinkedHashSet<String>()
    val seen = HashSet<String>(current.size)
    for (signature in current) {
        val key = signature.key
        if (!seen.add(key)) continue
        val cached = previous[key]
        if (cached != null &&
            cached.size == signature.size &&
            cached.dateModified == signature.dateModified
        ) {
            unchanged += key
        } else {
            changed += key
        }
    }
    val removed = LinkedHashSet<String>()
    previous.keys.forEach { key -> if (key !in seen) removed += key }
    return ScanDiff(unchanged = unchanged, changed = changed, removed = removed)
}
