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
class SecureStore(private val context: Context) {

    /**
     * Создаётся при первом обращении, а не в конструкторе.
     *
     * `MasterKey.Builder().build()` обращается к Android Keystore: на слабом
     * устройстве это сотни миллисекунд. Конструктор вызывался при построении
     * AppGraph — то есть до первого кадра, — и приложение ждало Keystore только
     * ради того, чтобы показать главный экран. Токены нужны лишь тогда, когда
     * пользователь реально подключает Spotify или SoundCloud, поэтому хранилище
     * открывается при первом чтении или записи.
     *
     * `by lazy` синхронизирован по умолчанию, поэтому гонки между потоками нет.
     */
    private val prefs: SharedPreferences by lazy {
        runCatching {
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
