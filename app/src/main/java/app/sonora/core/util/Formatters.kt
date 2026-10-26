package app.sonora.core.util

import java.util.Locale

object Formatters {

    /** 3:45 / 1:02:33 */
    fun duration(ms: Long): String {
        if (ms <= 0) return "--:--"
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** 12 треков / 1 ч 4 мин */
    fun tracksAndTime(count: Int, totalMs: Long): String {
        val minutes = totalMs / 60000
        val hours = minutes / 60
        val mins = minutes % 60
        val time = if (hours > 0) "$hours ч $mins мин" else "$mins мин"
        return "$count трек(ов) · $time"
    }
}
