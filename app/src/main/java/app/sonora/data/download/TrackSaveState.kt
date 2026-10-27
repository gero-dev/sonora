package app.sonora.data.download

/** Этап сохранения трека из стриминга на устройство. */
enum class SaveStatus { QUEUED, DOWNLOADING, DONE, ERROR, UNAVAILABLE }

/**
 * Прогресс сохранения одного трека.
 *
 * [percent] = -1, пока сервер не сообщил размер файла: в этом случае UI
 * показывает неопределённый индикатор, а не «0 %».
 */
data class TrackSaveState(
    val status: SaveStatus,
    val percent: Int = -1,
    val message: String? = null,
) {
    val isActive: Boolean
        get() = status == SaveStatus.QUEUED || status == SaveStatus.DOWNLOADING

    val label: String
        get() = when (status) {
            SaveStatus.QUEUED -> "В очереди"
            SaveStatus.DOWNLOADING ->
                if (percent in 0..100) "Сохранение $percent %" else "Сохранение…"
            SaveStatus.DONE -> "Сохранено"
            SaveStatus.ERROR -> message?.let { "Ошибка: $it" } ?: "Ошибка"
            SaveStatus.UNAVAILABLE -> message ?: "Недоступно"
        }

    companion object {
        fun queued() = TrackSaveState(SaveStatus.QUEUED)
        fun downloading(percent: Int = -1) = TrackSaveState(SaveStatus.DOWNLOADING, percent)
        fun done() = TrackSaveState(SaveStatus.DONE, 100)
        fun error(message: String?) = TrackSaveState(SaveStatus.ERROR, message = message)
        fun unavailable(message: String? = null) = TrackSaveState(SaveStatus.UNAVAILABLE, message = message)
    }
}
