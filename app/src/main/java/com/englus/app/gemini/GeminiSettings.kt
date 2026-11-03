package com.englus.app.gemini

import android.content.Context
import android.content.SharedPreferences
import com.englus.app.ai.AiEngine
import com.englus.app.ai.AiRouter
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * BYOK: the user's own Gemini API key, stored on the device.
 *
 * A key baked into the APK would be extracted by anyone who decompiled it, and
 * this app is sideloaded rather than served from a backend that could hold one.
 */
class GeminiSettings(context: Context) {

    private val prefs: SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "gemini_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ) as SharedPreferences
    }.getOrElse {
        // Keystore failures do happen (restored backups, unusual OEM builds).
        // Falling back keeps the feature usable rather than bricking the screen;
        // app-private storage is still not readable by other apps.
        context.getSharedPreferences("gemini_plain", Context.MODE_PRIVATE)
    }

    var apiKey: String
        get() = prefs.getString(KEY_API, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_API, value.trim()).apply()

    /**
     * Model ids churn fast — 3.5 to 3.7 Flash inside a few weeks, and 2.5 Flash
     * is scheduled to shut down — so this is a stored preference the user can
     * repoint from the list the API itself returns, not a constant.
     */
    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
        set(value) = prefs.edit().putString(KEY_MODEL, value.trim()).apply()

    /**
     * The Live API runs on its own model family and will not accept an ordinary
     * text model id, so it cannot share [model]. Kept as a preference for the
     * same reason: these ids are previews and are renamed often.
     */
    var liveModel: String
        get() = prefs.getString(KEY_LIVE_MODEL, DEFAULT_LIVE_MODEL).orEmpty().ifBlank { DEFAULT_LIVE_MODEL }
        set(value) = prefs.edit().putString(KEY_LIVE_MODEL, value.trim()).apply()

    val configured: Boolean get() = apiKey.isNotBlank()

    /** Which model answers: see [AiRouter]. Not a secret, but kept beside the key it decides about. */
    var engine: AiEngine
        get() = prefs.getString(KEY_ENGINE, null)
            ?.let { runCatching { AiEngine.valueOf(it) }.getOrNull() } ?: AiEngine.AUTO
        set(value) = prefs.edit().putString(KEY_ENGINE, value.name).apply()

    fun clear() = prefs.edit().clear().apply()

    companion object {
        const val DEFAULT_MODEL = "gemini-3.7-flash"
        const val DEFAULT_LIVE_MODEL = "gemini-3.1-flash-live-preview"
        private const val KEY_API = "apiKey"
        private const val KEY_MODEL = "model"
        private const val KEY_LIVE_MODEL = "liveModel"
        private const val KEY_ENGINE = "engine"
    }
}
