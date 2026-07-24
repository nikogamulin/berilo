package app.berilo.reader.interpretation

import app.berilo.reader.llm.LlmClient
import app.berilo.reader.llm.createLlmClient
import app.berilo.reader.settings.LlmSettings

/** Caps the completion length: a paragraph interpretation is a short paragraph of prose,
 * not an essay. Named per §4/CLAUDE.md ("no magic numbers"). */
private const val INTERPRETATION_MAX_TOKENS = 700

private const val INTERPRETATION_SYSTEM_PROMPT =
    "You are a literary companion helping a reader understand a passage from a book they " +
        "are reading in translation. Reply entirely in the target language, as flowing " +
        "prose (not a labeled list). Explain what the passage means, any implications or " +
        "subtext, and any references, allusions, or unfamiliar terms it contains, in plain " +
        "language a general reader can follow."

/** Result of a fresh (non-cached) interpretation call. */
data class InterpretationLookupResult(val text: String, val costEur: Double, val model: String)

/**
 * Calls the configured LLM for a paragraph/passage interpretation. One
 * [LlmClient.complete] call per interpretation, bounded by
 * [INTERPRETATION_MAX_TOKENS].
 *
 * Streaming: [LlmClient] exposes only a single bounded `complete()` call — there is no
 * token-streaming support today, so the sheet renders the complete text at once. If a
 * provider later adds streaming, this service is the seam to extend.
 *
 * @param createClient Factory for the [LlmClient] to use, defaulting to
 *   [createLlmClient]; overridden in tests with a fake that never touches the network.
 */
class InterpretationService(private val createClient: (LlmSettings) -> LlmClient = ::createLlmClient) {

    /**
     * Interprets [passage] from [bookTitle], in [LlmSettings.targetLang].
     *
     * Routes to [LlmSettings.interpretationModel] if the user set one, else falls back to
     * [LlmSettings.model] (S2.3 finding: `interpretationModel` may be null).
     *
     * @throws app.berilo.reader.llm.LlmError If the call fails for any reason.
     */
    suspend fun interpret(settings: LlmSettings, passage: String, bookTitle: String): InterpretationLookupResult {
        val effectiveModel = settings.interpretationModel ?: settings.model
        val client = createClient(settings.copy(model = effectiveModel))
        val result =
            client.complete(
                prompt = buildPrompt(passage, bookTitle, settings.targetLang),
                system = INTERPRETATION_SYSTEM_PROMPT,
                maxTokens = INTERPRETATION_MAX_TOKENS,
            )
        return InterpretationLookupResult(text = result.text.trim(), costEur = result.costEur, model = result.model)
    }

    private fun buildPrompt(passage: String, bookTitle: String, targetLang: String): String =
        "Book: \"$bookTitle\"\nPassage: \"$passage\"\nTarget language: $targetLang"
}
