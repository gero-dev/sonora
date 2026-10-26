package app.sonora.data.remote.spotify

import app.sonora.core.net.Http
import app.sonora.core.net.HttpException
import app.sonora.core.net.OAuthPkce
import app.sonora.core.security.SecureStore
import app.sonora.core.settings.SettingsStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder

/**
 * OAuth 2.0 Authorization Code + PKCE для Spotify, без client secret
 * (публичный клиент, как требует Spotify для мобильных приложений).
 *
 * Хранит в [SecureStore] только токены и служебные значения flow:
 * `spotify.access`, `spotify.refresh`, `spotify.expires_at`,
 * `spotify.user`, `spotify.verifier`, `spotify.state`.
 *
 * Никаких секретов в настройках (SharedPreferences) нет.
 */
class SpotifyAuth(
    private val settings: SettingsStore,
    private val secure: SecureStore,
) {

    /** Последняя ошибка OAuth/refresh; null — ошибок нет. */
    @Volatile
    var lastError: String? = null

    private val clientId: String
        get() = settings.current.spotifyClientId.trim()

    fun isConfigured(): Boolean = clientId.isNotBlank()

    /** Подключён, если есть refresh_token: access_token всегда можно обновить. */
    fun isConnected(): Boolean = !secure.get(KEY_REFRESH).isNullOrBlank()

    fun accountName(): String? = secure.get(KEY_USER)?.takeIf { it.isNotBlank() }

    /**
     * Собирает ссылку для Custom Tabs. Вернёт null, если Client ID не задан.
     * Каждый вызов генерирует новые `code_verifier` и `state` и сохраняет их.
     */
    fun authUrl(): String? {
        lastError = null
        val id = clientId
        if (id.isBlank()) {
            lastError = "Укажите Spotify Client ID в настройках (developer.spotify.com)."
            return null
        }
        val verifier = OAuthPkce.randomString(32)
        val state = OAuthPkce.randomString(16)
        secure.put(KEY_VERIFIER, verifier)
        secure.put(KEY_STATE, state)
        val challenge = OAuthPkce.codeChallenge(verifier)
        val params = listOf(
            "client_id" to id,
            "response_type" to "code",
            "redirect_uri" to OAuthPkce.redirectUri(SERVICE),
            "code_challenge_method" to "S256",
            "code_challenge" to challenge,
            "state" to state,
            "scope" to SCOPES,
            "show_dialog" to "false",
        )
        return AUTH_URL + "?" + params.joinToString("&") { (key, value) ->
            "${enc(key)}=${enc(value)}"
        }
    }

    /**
     * Обмен authorization code на токены (PKCE, без client_secret).
     * Возвращает true, если токен получен. Имя профиля грузится best-effort:
     * сбой /v1/me не делает подключение неуспешным.
     */
    suspend fun handleCallback(uri: String): Boolean {
        lastError = null
        val params = OAuthPkce.parseCallback(uri)
        if (!params.error.isNullOrBlank()) {
            lastError = "Spotify отклонил вход: ${params.errorDescription ?: params.error}"
            return false
        }
        val code = params.code
        if (code.isNullOrBlank()) {
            lastError = "В ответе Spotify нет кода авторизации."
            return false
        }
        val expectedState = secure.get(KEY_STATE)
        if (expectedState.isNullOrBlank() || params.state != expectedState) {
            lastError = "Проверка state не прошла — начните вход заново."
            return false
        }
        val verifier = secure.get(KEY_VERIFIER)
        if (verifier.isNullOrBlank()) {
            lastError = "Не найден code_verifier — начните вход заново."
            return false
        }
        if (clientId.isBlank()) {
            lastError = "Укажите Spotify Client ID в настройках (developer.spotify.com)."
            return false
        }
        return try {
            val body = Http.postForm(
                TOKEN_URL,
                mapOf(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "redirect_uri" to OAuthPkce.redirectUri(SERVICE),
                    "client_id" to clientId,
                    "code_verifier" to verifier,
                ),
            )
            val token = parseAndSaveTokens(body)
            if (token == null) {
                lastError = "Spotify вернул ответ без access_token."
                return false
            }
            secure.remove(KEY_VERIFIER)
            secure.remove(KEY_STATE)
            // Имя для UI — необязательная часть, ошибку не превращаем в провал входа.
            runCatching { SpotifyApi().me(token) }
                .onSuccess { user -> secure.put(KEY_USER, user.name) }
                .onFailure { error ->
                    lastError = "Не удалось загрузить профиль Spotify: ${error.message}"
                }
            true
        } catch (error: Exception) {
            lastError = describe(error)
            false
        }
    }

    /**
     * Гарантированно валидный access_token. Обновляет его, если до истечения
     * меньше минуты (или токена нет). Бросает IllegalStateException, если
     * аккаунт не подключён.
     */
    suspend fun requireToken(): String = validToken(forceRefresh = false)

    /**
     * Выполняет запрос с токеном. На 401 один раз принудительно обновляет
     * токен и повторяет запрос (требование Spotify на случай отзыва токена).
     */
    suspend fun <T> withToken(block: suspend (String) -> T): T {
        val firstToken = requireToken()
        return try {
            block(firstToken)
        } catch (error: HttpException) {
            if (error.code != 401) throw error
            val refreshedToken = validToken(forceRefresh = true)
            block(refreshedToken)
        }
    }

    fun disconnect() {
        clearTokens()
        secure.remove(KEY_VERIFIER)
        secure.remove(KEY_STATE)
        lastError = null
    }

    // ---------------------------------------------------------------- внутреннее

    private suspend fun validToken(forceRefresh: Boolean): String {
        if (forceRefresh) {
            val refresh = secure.get(KEY_REFRESH)
            if (refresh.isNullOrBlank()) {
                lastError = "Spotify не подключён."
                throw IllegalStateException("Spotify не подключён: нет refresh_token.")
            }
            return refreshTokens(refresh)
        }
        val access = secure.get(KEY_ACCESS)
        val storedRefresh = secure.get(KEY_REFRESH)
        val expiresAt = secure.get(KEY_EXPIRES_AT)?.toLongOrNull() ?: 0L
        val expiring = access.isNullOrBlank() ||
            expiresAt - System.currentTimeMillis() < REFRESH_SKEW_MS
        if (!expiring && access != null) return access
        if (storedRefresh.isNullOrBlank()) {
            lastError = "Spotify не подключён."
            throw IllegalStateException("Spotify не подключён: нет refresh_token.")
        }
        return refreshTokens(storedRefresh)
    }

    private suspend fun refreshTokens(refresh: String): String {
        val id = clientId
        if (id.isBlank()) {
            lastError = "Укажите Spotify Client ID в настройках (developer.spotify.com)."
            throw IllegalStateException("Не задан Spotify Client ID.")
        }
        val body = try {
            Http.postForm(
                TOKEN_URL,
                mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to refresh,
                    "client_id" to id,
                ),
            )
        } catch (error: Exception) {
            if (error.message?.contains("invalid_grant") == true) {
                clearTokens()
                lastError = "Сессия Spotify истекла — подключите аккаунт заново."
            } else {
                lastError = describe(error)
            }
            throw error
        }
        return parseAndSaveTokens(body) ?: run {
            lastError = "Spotify вернул ответ без access_token."
            throw IllegalStateException("Spotify: пустой ответ при обновлении токена.")
        }
    }

    /** Разбирает ответ token endpoint и сохраняет токены. Возвращает access_token. */
    private fun parseAndSaveTokens(body: String): String? {
        val root = runCatching { Http.parseJsonObject(body) }.getOrNull() ?: return null
        val access = root.str("access_token") ?: return null
        val refresh = root.str("refresh_token")
        val expiresIn = root.long("expires_in") ?: 3600L
        secure.put(KEY_ACCESS, access)
        if (!refresh.isNullOrBlank()) secure.put(KEY_REFRESH, refresh)
        secure.put(KEY_EXPIRES_AT, (System.currentTimeMillis() + expiresIn * 1000L).toString())
        return access
    }

    private fun clearTokens() {
        secure.remove(KEY_ACCESS)
        secure.remove(KEY_REFRESH)
        secure.remove(KEY_EXPIRES_AT)
        secure.remove(KEY_USER)
    }

    private fun describe(error: Throwable): String = when (error) {
        is HttpException -> when (error.code) {
            400, 401 -> "Spotify отклонил авторизацию — проверьте Client ID и Redirect URI."
            429 -> "Слишком много запросов к Spotify — попробуйте позже."
            else -> "Spotify: ${error.message}"
        }
        else -> error.message ?: "Ошибка Spotify"
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    companion object {
        const val SERVICE = "spotify"

        /** Скоупы: библиотека, плейлисты, профиль. Ничего сверх этого не просим. */
        const val SCOPES = "user-library-read playlist-read-private playlist-read-collaborative user-read-private user-read-email"

        private const val AUTH_URL = "https://accounts.spotify.com/authorize"
        private const val TOKEN_URL = "https://accounts.spotify.com/api/token"

        private const val KEY_ACCESS = "spotify.access"
        private const val KEY_REFRESH = "spotify.refresh"
        private const val KEY_EXPIRES_AT = "spotify.expires_at"
        private const val KEY_USER = "spotify.user"
        private const val KEY_VERIFIER = "spotify.verifier"
        private const val KEY_STATE = "spotify.state"

        private const val REFRESH_SKEW_MS = 60_000L
    }
}
