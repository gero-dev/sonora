package app.sonora.data.remote.youtube.innertube

import app.sonora.core.security.SecureStore
import java.security.MessageDigest
import java.util.Locale

/**
 * Сессия Google/YouTube Music для InnerTube.
 *
 * Cookie получается через WebView-логин (как в Metrolist), а дальше каждый
 * запрос InnerTube подписывается SAPISIDHASH. Данные лежат в SecureStore.
 */
internal object InnerTubeSession {

    private const val KEY_COOKIE = "youtube.cookie"
    private const val KEY_VISITOR = "youtube.visitor"
    private const val KEY_DATA_SYNC = "youtube.data_sync_id"
    private const val KEY_AUTH_USER = "youtube.auth_user"
    private const val KEY_NAME = "youtube.account_name"
    private const val KEY_AVATAR = "youtube.account_avatar"

    const val ORIGIN = "https://music.youtube.com"

    @Volatile
    var cookie: String? = null
        private set

    @Volatile
    var visitorData: String? = null
        private set

    @Volatile
    var dataSyncId: String? = null
        private set

    @Volatile
    var authUser: String = "0"
        private set

    @Volatile
    var accountName: String? = null
        private set

    @Volatile
    var accountAvatarUrl: String? = null
        private set

    val isLoggedIn: Boolean
        get() {
            val value = cookie
            return !value.isNullOrBlank() && sapisid(value) != null
        }

    val onBehalfOfUser: String?
        get() = dataSyncId?.takeIf { it.isNotBlank() && isLoggedIn }

    fun restore(secure: SecureStore) {
        cookie = secure.get(KEY_COOKIE)?.takeIf { it.isNotBlank() }
        visitorData = secure.get(KEY_VISITOR)?.takeIf { it.isNotBlank() }
        dataSyncId = secure.get(KEY_DATA_SYNC)
            ?.substringBefore("||")
            ?.takeIf { it.isNotBlank() }
        authUser = secure.get(KEY_AUTH_USER)
            ?.filter { it.isDigit() }
            ?.ifBlank { "0" }
            ?: "0"
        accountName = secure.get(KEY_NAME)
        accountAvatarUrl = secure.get(KEY_AVATAR)
        if (!isLoggedIn) {
            cookie = null
            dataSyncId = null
        }
    }

    fun save(
        secure: SecureStore,
        cookie: String,
        visitorData: String?,
        dataSyncId: String?,
        authUser: String,
        accountName: String?,
        accountAvatarUrl: String?,
    ) {
        this.cookie = cookie
        this.visitorData = visitorData?.takeIf { it.isNotBlank() }
        this.dataSyncId = dataSyncId?.substringBefore("||")?.takeIf { it.isNotBlank() }
        this.authUser = authUser.filter { it.isDigit() }.ifBlank { "0" }
        this.accountName = accountName
        this.accountAvatarUrl = accountAvatarUrl
        secure.put(KEY_COOKIE, cookie)
        this.visitorData?.let { secure.put(KEY_VISITOR, it) }
        this.dataSyncId?.let { secure.put(KEY_DATA_SYNC, it) }
        secure.put(KEY_AUTH_USER, this.authUser)
        accountName?.let { secure.put(KEY_NAME, it) }
        accountAvatarUrl?.let { secure.put(KEY_AVATAR, it) }
    }

    fun updateProfile(secure: SecureStore, name: String?, avatarUrl: String?) {
        accountName = name
        accountAvatarUrl = avatarUrl
        name?.let { secure.put(KEY_NAME, it) }
        avatarUrl?.let { secure.put(KEY_AVATAR, it) }
    }

    fun clear(secure: SecureStore) {
        cookie = null
        visitorData = null
        dataSyncId = null
        authUser = "0"
        accountName = null
        accountAvatarUrl = null
        listOf(KEY_COOKIE, KEY_VISITOR, KEY_DATA_SYNC, KEY_AUTH_USER, KEY_NAME, KEY_AVATAR)
            .forEach(secure::remove)
    }

    fun authHeaders(): Map<String, String> {
        if (!isLoggedIn) return emptyMap()
        val headers = LinkedHashMap<String, String>()
        val cookieValue = cookie ?: return emptyMap()
        headers["Cookie"] = cookieValue
        headers["X-Goog-AuthUser"] = authUser
        visitorData?.let { headers["X-Goog-Visitor-Id"] = it }
        headers["Origin"] = ORIGIN
        headers["Referer"] = "$ORIGIN/"
        sapisidHash(cookieValue)?.let { headers["Authorization"] = it }
        return headers
    }

    private fun sapisidHash(cookie: String): String? {
        val sapisid = sapisid(cookie) ?: return null
        val timestamp = System.currentTimeMillis() / 1000
        val digest = sha1("$timestamp $sapisid $ORIGIN")
        return "SAPISIDHASH ${timestamp}_$digest"
    }

    private fun sapisid(cookie: String): String? {
        val values = cookie.split(';')
            .map { it.trim() }
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) null else part.substring(0, index) to part.substring(index + 1)
            }
            .toMap()
        return values["SAPISID"]
            ?: values["__Secure-3PAPISID"]
            ?: values["__Secure-1PAPISID"]
    }

    private fun sha1(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
    }
}
