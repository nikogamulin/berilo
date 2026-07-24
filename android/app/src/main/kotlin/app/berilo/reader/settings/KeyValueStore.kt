package app.berilo.reader.settings

/**
 * Minimal string key-value store. Exists so [SettingsRepository] can be unit-tested
 * against a plain in-memory fake instead of the real [EncryptedKeyValueStore], which
 * needs a live Android Keystore and is therefore only exercised on-device (R7 audit).
 */
interface KeyValueStore {
    fun getString(key: String): String?

    fun putString(key: String, value: String?)
}
