package app.berilo.reader.settings

private const val KEY_OPENAI_API_KEY = "openai_api_key"
private const val KEY_ANTHROPIC_API_KEY = "anthropic_api_key"
private const val KEY_MODEL = "model"
private const val KEY_TARGET_LANG = "target_lang"
private const val KEY_DICTIONARY_MODEL = "dictionary_model"
private const val KEY_INTERPRETATION_MODEL = "interpretation_model"

/**
 * Reads/writes [LlmSettings] to a [KeyValueStore], one field per preference key rather
 * than a single serialized blob, so a corrupt or missing individual value never
 * invalidates the whole settings object — it just falls back to that field's default.
 */
class SettingsRepository(private val store: KeyValueStore) {

    /** Loads the current settings, filling in defaults ([DEFAULT_MODEL],
     * [DEFAULT_TARGET_LANG]) for anything never saved. */
    fun load(): LlmSettings =
        LlmSettings(
            openaiKey = store.getString(KEY_OPENAI_API_KEY),
            anthropicKey = store.getString(KEY_ANTHROPIC_API_KEY),
            model = store.getString(KEY_MODEL) ?: DEFAULT_MODEL,
            targetLang = store.getString(KEY_TARGET_LANG) ?: DEFAULT_TARGET_LANG,
            dictionaryModel = store.getString(KEY_DICTIONARY_MODEL),
            interpretationModel = store.getString(KEY_INTERPRETATION_MODEL),
        )

    /** Persists [settings], one key per field. */
    fun save(settings: LlmSettings) {
        store.putString(KEY_OPENAI_API_KEY, settings.openaiKey)
        store.putString(KEY_ANTHROPIC_API_KEY, settings.anthropicKey)
        store.putString(KEY_MODEL, settings.model)
        store.putString(KEY_TARGET_LANG, settings.targetLang)
        store.putString(KEY_DICTIONARY_MODEL, settings.dictionaryModel)
        store.putString(KEY_INTERPRETATION_MODEL, settings.interpretationModel)
    }
}
