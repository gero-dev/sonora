package app.sonora.core.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class ThemeMode { SYSTEM, DARK, LIGHT }

@Serializable
enum class Accent(val label: String) {
    VIOLET("Фиолетовый"),
    BLUE("Синий"),
    GREEN("Зелёный"),
    ORANGE("Оранжевый"),
    PINK("Розовый"),
    MONO("Моно"),
}

@Serializable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val accent: Accent = Accent.VIOLET,
    val dynamicColor: Boolean = false,

    // Аудио
    val gapless: Boolean = true,
    val crossfadeMs: Int = 0,
    val skipSilence: Boolean = false,
    val eqEnabled: Boolean = false,
    val eqPreset: String = "flat",
    val eqBands: List<Float> = List(BANDS_COUNT) { 0f },
    val playbackSpeed: Float = 1f,

    // Библиотека
    val scanOnStart: Boolean = true,
    val excludeShortTracks: Boolean = true,
    /** Автоматически искать альбом/год/обложку/текст в MusicBrainz/LRCLIB после сканирования. */
    val enrichMetadataOnScan: Boolean = true,
    /** Автоматически достраивать очередь при запуске одного трека. */
    val autoQueue: Boolean = true,
    /** Бесконечно достраивать очередь случайными треками, когда она заканчивается. */
    val infiniteQueue: Boolean = true,
    val autoQueueSize: Int = 25,
    val downloadWifiOnly: Boolean = true,
    val downloadFolder: String = "Sonora",
    /** Показывать обучающий экран при первом запуске. */
    val onboardingDone: Boolean = false,
    /** Автоматически поднимать WARP-туннель при старте приложения (если есть конфиги). */
    val warpAutoConnect: Boolean = false,
    /** Версия схемы метаданных: после апгрейда сбрасываем устаревшие отметки «проверено». */
    val metadataSchemaVersion: Int = 0,

    // Ключи/подключения (не секреты — секреты в SecureStore)
    val spotifyClientId: String = "",
    val soundcloudClientId: String = "",
    val jamendoClientId: String = "",
    val lastfmApiKey: String = "",
) {
    companion object {
        const val BANDS_COUNT = 5
        val bandFrequencies = intArrayOf(60, 230, 910, 3600, 14000)
    }
}

/**
 * Настройки хранятся одной JSON-строкой в SharedPreferences.
 * Секреты (refresh/access tokens) сюда НЕ попадают — они в [app.sonora.core.security.SecureStore].
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    val current: AppSettings get() = _state.value

    fun update(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_state.value)
        _state.value = updated
        prefs.edit().putString(KEY, json.encodeToString(updated)).apply()
    }

    fun set(settings: AppSettings) = update { settings }

    private fun load(): AppSettings = runCatching {
        prefs.getString(KEY, null)?.let { json.decodeFromString<AppSettings>(it) }
    }.getOrNull() ?: AppSettings()

    private companion object {
        const val PREFS = "sonora_settings"
        const val KEY = "app_settings_v1"
    }
}
