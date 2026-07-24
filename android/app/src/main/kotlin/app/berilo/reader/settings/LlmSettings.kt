package app.berilo.reader.settings

/** Cheapest model that does the job (product spec §5.2) — the default until the user
 * picks something else in Settings. */
const val DEFAULT_MODEL = "gpt-5-mini"

/** Niko's own target language; the first user's default. */
const val DEFAULT_TARGET_LANG = "sl"

/** Models offered in the Settings picker, cheapest OpenAI option first. */
val AVAILABLE_MODELS = listOf("gpt-5-mini", "gpt-5", "gpt-5-nano", "claude-haiku-4-5", "claude-sonnet-4-5")

/**
 * User-configurable LLM settings: provider API keys, the default completion model, the
 * reading target language, and optional per-feature model overrides for the dictionary
 * (S2.4) and paragraph interpretation (S2.5).
 *
 * @property openaiKey The user's own OpenAI API key, or `null` if not configured.
 * @property anthropicKey The user's own Anthropic API key, or `null` if not configured.
 * @property model Default completion model; routes to OpenAI or Anthropic by prefix
 *   (see [app.berilo.reader.llm.createLlmClient]).
 * @property targetLang Target language code for translation/dictionary/interpretation
 *   calls (ISO 639-1, e.g. `"sl"`).
 * @property dictionaryModel Model override for dictionary lookups, or `null` to use
 *   [model].
 * @property interpretationModel Model override for paragraph interpretation, or `null`
 *   to use [model].
 */
data class LlmSettings(
    val openaiKey: String? = null,
    val anthropicKey: String? = null,
    val model: String = DEFAULT_MODEL,
    val targetLang: String = DEFAULT_TARGET_LANG,
    val dictionaryModel: String? = null,
    val interpretationModel: String? = null,
)
