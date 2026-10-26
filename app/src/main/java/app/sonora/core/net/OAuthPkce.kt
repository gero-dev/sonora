package app.sonora.core.net

import android.net.Uri
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** OAuth 2.0 Authorization Code + PKCE (RFC 7636), без клиентского секрета. */
object OAuthPkce {

    const val REDIRECT_SCHEME = "sonora"
    const val REDIRECT_HOST = "oauth"

    /** sonora://oauth/spotify , sonora://oauth/youtube */
    fun redirectUri(service: String): String = "$REDIRECT_SCHEME://$REDIRECT_HOST/$service"

    fun randomString(bytes: Int = 32): String {
        val buffer = ByteArray(bytes)
        SecureRandom().nextBytes(buffer)
        return base64Url(buffer)
    }

    fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return base64Url(digest)
    }

    /** Достаёт code/state/error из callback-ссылки. */
    fun parseCallback(uri: String): CallbackParams {
        val parsed = Uri.parse(uri)
        return CallbackParams(
            code = parsed.getQueryParameter("code"),
            state = parsed.getQueryParameter("state"),
            error = parsed.getQueryParameter("error"),
            errorDescription = parsed.getQueryParameter("error_description"),
            path = parsed.path?.trimStart('/'),
        )
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    data class CallbackParams(
        val code: String?,
        val state: String?,
        val error: String?,
        val errorDescription: String?,
        val path: String?,
    )
}
