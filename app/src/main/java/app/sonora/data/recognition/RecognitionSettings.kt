package app.sonora.data.recognition

import android.content.Context
import android.content.SharedPreferences

/**
 * Настройки модуля распознавания.
 *
 * Ключ AudD намеренно хранится в отдельном SharedPreferences, а не в SettingsStore:
 * так фича остаётся изолированной и её можно выпилить/заменить без правок ядра.
 */
class RecognitionSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** API-ключ AudD (пустая строка — ключ не задан). */
    var auddToken: String
        get() = prefs.getString(KEY_AUDD_TOKEN, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_AUDD_TOKEN, value.trim()).apply()
        }

    companion object {
        private const val PREFS_NAME = "sonora_recognition"
        private const val KEY_AUDD_TOKEN = "audd_api_token"
    }
}
