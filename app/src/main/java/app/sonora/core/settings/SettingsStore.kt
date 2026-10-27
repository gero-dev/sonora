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

/**
 * Режим проигрывания, как в Spotify: можно разрешить стриминг в очереди,
 * а можно слушать только то, что уже сохранено на устройстве.
 */
@Serializable
enum class PlaybackMode(val label: String, val hint: String) {
    STREAMING("Онлайн + устройство", "В очередь попадают и стриминговые треки"),
    LOCAL_ONLY("Только загруженное", "Играем только то, что сохранено на устройстве"),
}

/** Тонкая настройка «Моей волны»: что и как подбирается в рекомендации. */
@Serializable
data class WaveSettings(
    /** Только музыка: без клипов, концертов, интервью и подкастов. */
    val musicOnly: Boolean = true,
    /** Скрывать видеоклипы даже при выключенном «только музыка». */
    val excludeVideos: Boolean = true,
    val includeFavorites: Boolean = true,
    /** Учитывать историю прослушиваний и пропусков. */
    val useHistory: Boolean = true,
    /** 0 — знакомое, 100 — новое. */
    val novelty: Int = 50,
    /** Максимум треков одного артиста в начале подборки. */
    val artistDiversity: Int = 2,
    /** Доля локальных треков в миксе, проценты. */
    val localShare: Int = 60,
    /** Нижняя граница длительности трека, секунды. */
    val minDurationSec: Int = 45,
    /** Верхняя граница длительности онлайн-трека, минуты. */
    val maxDurationMin: Int = 12,
) {
    /** Приведение значений к безопасным диапазонам из UI. */
    fun normalized(): WaveSettings {
        val maxMinutes = maxDurationMin.coerceIn(2, 45)
        val minSeconds = minDurationSec.coerceIn(15, 1800)
        // Верхняя граница не может быть меньше нижней, иначе волна останется пустой.
        val safeMinSeconds = minSeconds.coerceAtMost(maxMinutes * 60)
        return WaveSettings(
            musicOnly = musicOnly,
            excludeVideos = excludeVideos,
            includeFavorites = includeFavorites,
            useHistory = useHistory,
            novelty = novelty.coerceIn(0, 100),
            artistDiversity = artistDiversity.coerceIn(1, 5),
            localShare = localShare.coerceIn(0, 100),
            minDurationSec = safeMinSeconds,
            maxDurationMin = maxMinutes,
        )
    }
}

@Serializable
enum class Accent(val label: String) {
    VIOLET("Фиолетовый"),
    BLUE("Синий"),
    GREEN("Зелёный"),
    ORANGE("Оранжевый"),
    PINK("Розовый"),
    MONO("Моно"),
    RED("Красный"),
    TEAL("Бирюзовый"),
    YELLOW("Жёлтый"),
    INDIGO("Индиго"),
    CYAN("Голубой"),
    BROWN("Терракотовый"),
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

    // Воспроизведение: онлайн-стриминг можно выключить, как «офлайн-режим» в Spotify.
    val playbackMode: PlaybackMode = PlaybackMode.STREAMING,
    /** Разрешать стриминговые треки в очереди и автодополнении. */
    val streamingInQueue: Boolean = true,

    /** Вести историю прослушиваний: треки, исполнители, статистика. */
    val saveHistory: Boolean = true,

    // «Моя волна»: точность рекомендаций и фильтр «только музыка».
    val wave: WaveSettings = WaveSettings(),

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
    /** Читать sidecar-файлы (json/nfo/lrc, cover.jpg) рядом с аудиофайлами. */
    val readSidecarMetadata: Boolean = true,
    /** Доставать встроенную обложку из аудиофайла (ID3/FLAC/MP4). */
    val useEmbeddedArtwork: Boolean = true,
    /** Автоматически поднимать WARP-туннель при старте приложения (если есть конфиги). */
    val warpAutoConnect: Boolean = false,
    /**
     * Автовосстановление: если пользователь выключил свой WARP/VPN во время
     * активной сессии, Sonora снова поднимает свой туннель, когда внешний VPN исчез.
     */
    val warpKeepAlive: Boolean = true,
    /** Пользователь включал WARP; переживает перезапуск процесса для автоподхвата. */
    val warpUserEnabled: Boolean = false,
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

    /** Обновление настроек волны с приведением значений к допустимым диапазонам. */
    fun updateWave(transform: (WaveSettings) -> WaveSettings) {
        update { current -> current.copy(wave = transform(current.wave).normalized()) }
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
