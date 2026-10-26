package app.sonora.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Хранилище OAuth-токенов. Пытается использовать EncryptedSharedPreferences,
 * при любой ошибке (старый ключ, сломанный keystore) — откатывается на обычные prefs,
 * чтобы приложение не падало. Токены не покидают устройство.
 */
class SecureStore(context: Context) {

    private val prefs: SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "sonora_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ) as SharedPreferences
    }.getOrElse {
        context.getSharedPreferences("sonora_secure_fallback", Context.MODE_PRIVATE)
    }

    fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun get(key: String): String? = prefs.getString(key, null)

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun contains(key: String): Boolean = prefs.contains(key)

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
