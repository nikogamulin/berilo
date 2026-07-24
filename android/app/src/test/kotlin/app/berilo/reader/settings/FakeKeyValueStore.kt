package app.berilo.reader.settings

/** In-memory [KeyValueStore] fake: keeps [SettingsRepository] tests off the real
 * [EncryptedKeyValueStore], which needs a live Android Keystore. */
class FakeKeyValueStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String?) {
        if (value == null) {
            values.remove(key)
        } else {
            values[key] = value
        }
    }
}
