package app.sonora.ui

/**
 * Совместимые имена моделей волны для UI-слоя.
 *
 * Сами модели и вся математика рекомендаций живут в [app.sonora.data.wave]
 * (чистый Kotlin, без Android) — так их можно покрывать JVM-тестами.
 * Typealias-ы сохранены, чтобы экраны и раньше времени не пришлось править
 * импорты: `import app.sonora.ui.WaveMood` продолжает работать.
 */
typealias WaveSourceMode = app.sonora.data.wave.WaveSourceMode
typealias WaveMood = app.sonora.data.wave.WaveMood
typealias WaveSort = app.sonora.data.wave.WaveSort
typealias WaveFilters = app.sonora.data.wave.WaveFilters
typealias WaveStatus = app.sonora.data.wave.WaveStatus
typealias WaveBuildResult = app.sonora.data.wave.WaveBuildResult
typealias WaveUiState = app.sonora.data.wave.WaveUiState
