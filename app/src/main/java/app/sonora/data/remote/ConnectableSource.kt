package app.sonora.data.remote

import app.sonora.core.model.Source

/**
 * Источник, который умеет подключаться по OAuth (или по API-ключу) к аккаунту пользователя.
 * Реализации живут в пакетах spotify / youtube / soundcloud.
 */
interface ConnectableSource : MusicSource {

    /** Настроен ли клиент (введён client id / api key). */
    fun isConfigured(): Boolean

    /**
     * Ссылка, которую нужно открыть в браузере/Custom Tabs для входа.
     * null — если источник не настроен или вход не поддерживается.
     */
    fun authUrl(): String?

    /**
     * Обработка redirect: sonora://oauth/<service>?code=...
     * Возвращает true, если токен получен.
     */
    suspend fun handleCallback(uri: String): Boolean

    /** Сколько треков библиотеки загружено в приложение (для UI). */
    fun cachedTrackCount(): Int
}
