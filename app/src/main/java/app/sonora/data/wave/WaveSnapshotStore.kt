package app.sonora.data.wave

import android.content.Context
import app.sonora.core.model.Track
import app.sonora.core.util.JsonFile
import kotlinx.serialization.Serializable

/**
 * Снимок «Моей волны» между запусками приложения.
 *
 * В отличие от настроек (они живут в SharedPreferences), здесь сохраняется
 * сама подборка: треки, фильтры, сортировка и уже показанные ключи. После
 * холодного старта волна показывает последнюю подборку сразу, не ожидая сеть,
 * а «Ещё рекомендации» не повторяют то, что уже было показано вчера.
 *
 * Работает офлайн: файл читается/пишется через [JsonFile] на IO-диспетчере,
 * ошибки диска проглатываются, UI не блокируется.
 */
@Serializable
data class WaveSnapshot(
    val version: Int = VERSION,
    val filters: WaveFilters = WaveFilters(),
    val sort: WaveSort = WaveSort.FOR_YOU,
    val tracks: List<Track> = emptyList(),
    val label: String = "",
    val hint: String? = null,
    val status: WaveStatus = WaveStatus.READY,
    val generatedAt: Long = 0L,
    val featuresUsed: Int = 0,
    val filterSummary: String = "",
    val explanation: Map<String, String> = emptyMap(),
    val seenIds: List<String> = emptyList(),
    val seenKeys: List<String> = emptyList(),
    val queryCursor: Int = 0,
    /** Оценки/дизлайки на момент снимка — для понимания, что показывали. */
    val likedCount: Int = 0,
    val dislikedCount: Int = 0,
) {
    companion object {
        /** Версия схемы: старые снимки после апгрейда просто игнорируются. */
        const val VERSION = 1

        /** Сколько треков хранить: больше — дольше сериализация при старте. */
        const val MAX_TRACKS = 200
    }
}

class WaveSnapshotStore(context: Context) {

    private val appContext = context.applicationContext

    /** Последний сохранённый снимок; null — снимка нет/он устарел. */
    suspend fun load(): WaveSnapshot? {
        val stored = runCatching {
            JsonFile.read(appContext, FILE_NAME, WaveSnapshot.serializer(), WaveSnapshot())
        }.getOrNull() ?: return null
        if (stored.version != WaveSnapshot.VERSION) return null
        if (stored.tracks.isEmpty()) return null
        return stored.copy(tracks = stored.tracks.take(WaveSnapshot.MAX_TRACKS))
    }

    suspend fun save(snapshot: WaveSnapshot) {
        runCatching {
            JsonFile.write(
                appContext,
                FILE_NAME,
                WaveSnapshot.serializer(),
                snapshot.copy(tracks = snapshot.tracks.take(WaveSnapshot.MAX_TRACKS)),
            )
        }
    }

    suspend fun clear() {
        runCatching {
            JsonFile.write(appContext, FILE_NAME, WaveSnapshot.serializer(), WaveSnapshot())
        }
    }

    private companion object {
        const val FILE_NAME = "wave_snapshot.json"
    }
}
