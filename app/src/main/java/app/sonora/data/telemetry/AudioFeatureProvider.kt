package app.sonora.data.telemetry

import app.sonora.core.model.Track

/**
 * Тонкий контракт между «Моей волной» и телеметрией ReccoBeats.
 *
 * Волна не должна зависеть от Android-репозитория напрямую: в JVM-тестах
 * подставляется фейковый провайдер. Продакшен-реализация —
 * [AudioFeatureRepository].
 */
interface AudioFeatureProvider {

    /** Включена ли телеметрия в настройках. */
    val available: Boolean

    /** Только локальный кэш: быстро, без сети, никогда не бросает. */
    suspend fun cachedFeatures(tracks: List<Track>): Map<String, AudioFeature>

    /** Посчитать фичи (сеть + кэш). Может вернуть null при любой ошибке. */
    suspend fun fetchFeature(track: Track): AudioFeature?
}
