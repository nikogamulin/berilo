package app.berilo.reader.dictionary

import app.berilo.reader.llm.LlmClient
import app.berilo.reader.llm.createLlmClient
import app.berilo.reader.settings.LlmSettings

/** Caps the completion length: a dictionary entry is a few short lines, never a paid-for
 * essay. Named per §4/CLAUDE.md ("no magic numbers"). */
private const val DICTIONARY_MAX_TOKENS = 300

private const val DICTIONARY_SYSTEM_PROMPT =
    "You are a concise bilingual dictionary. Reply in the target language using exactly " +
        "this labeled, one-field-per-line format and nothing else:\n" +
        "DEFINITION: <short definition/translation of the word>\n" +
        "CONTEXT: <what the word means in this specific sentence>\n" +
        "BASE FORM: <the word's dictionary/lemma form>\n" +
        "USAGE: <one brief usage note>"

/** Result of a fresh (non-cached) dictionary lookup call. */
data class DictionaryLookupResult(val definition: DictionaryDefinition, val costEur: Double, val model: String)

/**
 * Calls the configured LLM for a word-in-context dictionary lookup. One [LlmClient.complete]
 * call per lookup; the result is parsed by [parseDictionaryResponse].
 *
 * @param createClient Factory for the [LlmClient] to use, defaulting to
 *   [createLlmClient]; overridden in tests with a fake that never touches the network.
 */
class DictionaryService(private val createClient: (LlmSettings) -> LlmClient = ::createLlmClient) {

    /**
     * Looks up [word] as it appears in [sentence], in [LlmSettings.targetLang].
     *
     * Routes to [LlmSettings.dictionaryModel] if the user set one, else falls back to
     * [LlmSettings.model] (S2.3 finding: `dictionaryModel` may be null).
     *
     * @throws app.berilo.reader.llm.LlmError If the call fails for any reason.
     */
    suspend fun define(settings: LlmSettings, word: String, sentence: String): DictionaryLookupResult {
        val effectiveModel = settings.dictionaryModel ?: settings.model
        val client = createClient(settings.copy(model = effectiveModel))
        val result =
            client.complete(
                prompt = buildPrompt(word, sentence, settings.targetLang),
                system = DICTIONARY_SYSTEM_PROMPT,
                maxTokens = DICTIONARY_MAX_TOKENS,
            )
        return DictionaryLookupResult(
            definition = parseDictionaryResponse(word, result.text),
            costEur = result.costEur,
            model = result.model,
        )
    }

    private fun buildPrompt(word: String, sentence: String, targetLang: String): String =
        "Word: \"$word\"\nSentence: \"$sentence\"\nTarget language: $targetLang"
}
