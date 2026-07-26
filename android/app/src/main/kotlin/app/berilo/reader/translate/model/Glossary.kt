package app.berilo.reader.translate.model

/**
 * A fixed `source term -> target rendering` map for one book, ported byte-exactly from
 * `berilo.glossary.Glossary` (`translator/berilo/glossary.py`).
 *
 * The glossary is injected into every batch prompt so terminology stays consistent across
 * chunk boundaries. [glossaryIdentity] is the sixth component of the on-device translation
 * cache's primary key (`translations` table, B4): the same segment translated under two
 * different glossaries must land in two distinct rows, because the glossary is prompt input
 * just as much as the segment text or the prompt version.
 *
 * @property terms Mapping from a source term to its fixed target rendering.
 */
data class Glossary(val terms: Map<String, String> = emptyMap()) {

    /** Returns `true` when the glossary holds no terms. */
    fun isEmpty(): Boolean = terms.isEmpty()

    /**
     * Render the glossary as a prompt-ready instruction block.
     *
     * Terms are rendered **sorted by source term**, never in map/insertion order. This block is
     * what [glossaryIdentity] hashes, and the extractor that produces a [Glossary] offers no
     * ordering contract: two extractions of a semantically identical glossary in a different
     * order must key identically, or a from-scratch re-extraction would re-translate the whole
     * book for no change at all (`docs/findings.md`, the defect A2 shipped and had to fix).
     *
     * @return A human-readable block listing every `term -> rendering` pair in source-term
     *   order, or an empty string when the glossary is empty.
     */
    fun toPromptBlock(): String {
        if (terms.isEmpty()) return ""
        val lines = terms.keys.sorted().map { source -> "- $source -> ${terms.getValue(source)}" }
        return "GLOSSARY (use these fixed renderings for the following terms; " +
            "inflect/decline them naturally as the target language requires):\n" +
            lines.joinToString("\n")
    }
}

/**
 * Derive the translation-cache component identifying [glossary].
 *
 * Mirrors `berilo.glossary.glossary_identity`: sha1 over [Glossary.toPromptBlock] — the exact
 * text the glossary contributes to every batch prompt — so the key tracks what the model
 * actually saw. `null` and an empty glossary render the same empty block and therefore share
 * one identity, which is correct: both inject nothing.
 *
 * @param glossary The glossary that will be injected, or `null`.
 * @return A 40-character lowercase hexadecimal sha1 digest.
 */
fun glossaryIdentity(glossary: Glossary?): String =
    sha1Hex((glossary?.toPromptBlock() ?: "").pythonStrip())
