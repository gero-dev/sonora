package app.sonora.data.update

import kotlinx.serialization.Serializable

/** Сведения о доступном обновлении из GitHub Releases. */
data class UpdateInfo(
    val versionName: String,
    val tag: String,
    val apkUrl: String,
    val notes: String,
    val publishedAt: String?,
    val sizeBytes: Long?,
    val sha256: String?,
) {
    val sizeMb: String?
        get() = sizeBytes?.takeIf { it > 0 }
            ?.let { "%.1f МБ".format(it / 1024.0 / 1024.0).replace('.', ',') }
}

/** Кэш последней проверки: чтобы не дёргать GitHub API чаще раза в сутки. */
@Serializable
data class UpdateCache(
    val versionName: String = "",
    val tag: String = "",
    val apkUrl: String = "",
    val notes: String = "",
    val publishedAt: String? = null,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
    val etag: String? = null,
    val checkedAtMs: Long = 0L,
) {
    fun toInfo(): UpdateInfo? = if (versionName.isBlank() || apkUrl.isBlank()) {
        null
    } else {
        UpdateInfo(
            versionName = versionName,
            tag = tag,
            apkUrl = apkUrl,
            notes = notes,
            publishedAt = publishedAt,
            sizeBytes = sizeBytes,
            sha256 = sha256,
        )
    }

    companion object {
        fun from(info: UpdateInfo, etag: String?) = UpdateCache(
            versionName = info.versionName,
            tag = info.tag,
            apkUrl = info.apkUrl,
            notes = info.notes,
            publishedAt = info.publishedAt,
            sizeBytes = info.sizeBytes,
            sha256 = info.sha256,
            etag = etag,
            checkedAtMs = System.currentTimeMillis(),
        )
    }
}

/** Сравнение версий вида `1.10.0`; true — [latest] новее [current]. */
fun isNewerVersion(latest: String, current: String): Boolean {
    fun parts(value: String): List<Int> = value
        .trim()
        .removePrefix("v")
        .split('.', '-', '+')
        .mapNotNull { it.toIntOrNull() }
        .ifEmpty { listOf(0) }
    val a = parts(latest)
    val b = parts(current)
    val max = maxOf(a.size, b.size)
    for (i in 0 until max) {
        val left = a.getOrElse(i) { 0 }
        val right = b.getOrElse(i) { 0 }
        if (left != right) return left > right
    }
    return false
}
