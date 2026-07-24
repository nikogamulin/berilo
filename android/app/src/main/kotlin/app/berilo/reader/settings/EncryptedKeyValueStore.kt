package app.berilo.reader.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

private const val PREFS_FILE_NAME = "berilo_secure_settings"

/**
 * Production [KeyValueStore], backed by [EncryptedSharedPreferences] with an AES256-GCM
 * master key held in the Android Keystore (keys and values are both encrypted at rest).
 * Needs a live Keystore + Tink, so it is exercised on-device (R7 audit: `adb logcat`
 * during a lookup session contains no key substring; backup extract contains no plaintext
 * key) rather than by JVM unit tests — [SettingsRepository] is tested against a fake
 * [KeyValueStore] instead.
 */
class EncryptedKeyValueStore(context: Context) : KeyValueStore {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String?) {
        prefs.edit().putString(key, value).apply()
    }
}
