package app.berilo.reader.dictionary

/** Recognized labeled-field prefixes in the LLM response, matched case-insensitively. */
private val FIELD_PREFIXES =
    mapOf(
        "DEFINITION:" to Field.DEFINITION,
        "CONTEXT:" to Field.CONTEXT,
        "BASE FORM:" to Field.BASE_FORM,
        "USAGE:" to Field.USAGE,
    )

private enum class Field { DEFINITION, CONTEXT, BASE_FORM, USAGE }

/**
 * A dictionary lookup result for one word in one sentence context.
 *
 * @property word The looked-up word (as normalized for the cache key — lowercase).
 * @property definition Short definition/translation of the word.
 * @property contextMeaning The word's meaning in this specific sentence; empty if the
 *   model's response didn't use the labeled format (see [parseDictionaryResponse]).
 * @property baseForm The word's dictionary/lemma form; empty if not parsed out.
 * @property usageNote Brief usage note; empty if not parsed out.
 */
data class DictionaryDefinition(
    val word: String,
    val definition: String,
    val contextMeaning: String,
    val baseForm: String,
    val usageNote: String,
)

/**
 * Parses [DictionaryService]'s prompted labeled-field format
 * (`DEFINITION:`/`CONTEXT:`/`BASE FORM:`/`USAGE:`, one per line) out of a raw completion.
 *
 * Tolerant by design (`docs/project_plan.md` S2.4: "strict-ish parsing but tolerate free
 * text"): a model that ignores the format entirely still produces a usable result — the
 * whole trimmed response becomes [DictionaryDefinition.definition] and the other fields stay
 * empty, rather than throwing and surfacing an error state for a perfectly good answer.
 *
 * @param word The looked-up word, to stamp onto the result.
 * @param text The raw completion text.
 * @return The parsed [DictionaryDefinition].
 */
fun parseDictionaryResponse(word: String, text: String): DictionaryDefinition {
    val fields = mutableMapOf<Field, String>()
    for (line in text.lines()) {
        val trimmed = line.trim()
        val match = FIELD_PREFIXES.entries.firstOrNull { (prefix, _) -> trimmed.startsWith(prefix, ignoreCase = true) }
        if (match != null) {
            val (prefix, field) = match
            fields[field] = trimmed.substring(prefix.length).trim()
        }
    }

    if (fields.isEmpty()) {
        return DictionaryDefinition(word = word, definition = text.trim(), contextMeaning = "", baseForm = "", usageNote = "")
    }

    return DictionaryDefinition(
        word = word,
        definition = fields[Field.DEFINITION].orEmpty(),
        contextMeaning = fields[Field.CONTEXT].orEmpty(),
        baseForm = fields[Field.BASE_FORM].orEmpty(),
        usageNote = fields[Field.USAGE].orEmpty(),
    )
}
