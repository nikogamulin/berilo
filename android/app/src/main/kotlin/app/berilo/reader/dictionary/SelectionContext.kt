package app.berilo.reader.dictionary

/** Sentence-terminating characters used to find the selection's enclosing sentence. */
private val SENTENCE_TERMINATORS = charArrayOf('.', '!', '?')

/**
 * A word selected for lookup, together with the sentence it appears in — the context that
 * lets the LLM disambiguate ("bank" the riverbank vs. "bank" the institution).
 *
 * @property word The selected text, as tapped (not yet normalized for cache lookup — see
 *   [app.berilo.reader.dictionary.DictionaryRepository]).
 * @property sentence The sentence the word appears in.
 */
data class SelectionContext(val word: String, val sentence: String)

/**
 * Builds a [SelectionContext] from a Readium `Locator.Text` triple (`before`/`highlight`/
 * `after`), reconstructing the enclosing sentence around the highlighted selection.
 *
 * This is the pure, JVM-testable half of word capture (`docs/project_plan.md` S2.4): the
 * device-only half is just handing this function the three strings Readium's
 * `SelectableNavigator.currentSelection()` already returns on a `Locator.Text`.
 *
 * @param before Text immediately preceding the selection (may span multiple sentences).
 * @param highlight The selected text itself.
 * @param after Text immediately following the selection (may span multiple sentences).
 * @return `null` if [highlight] is blank (nothing was actually selected); otherwise a
 *   [SelectionContext] whose `sentence` is the [highlight] with the nearest sentence
 *   boundaries in [before]/[after] included, falling back to the raw concatenation if no
 *   terminator is found (e.g. a heading with no punctuation).
 */
fun buildSelectionContext(before: String, highlight: String, after: String): SelectionContext? {
    val word = highlight.trim()
    if (word.isEmpty()) return null

    val sentenceStart = before.lastIndexOfAny(SENTENCE_TERMINATORS).let { index ->
        if (index == -1) 0 else index + 1
    }
    val afterTerminator = after.indexOfAny(SENTENCE_TERMINATORS)
    val sentenceEnd = if (afterTerminator == -1) after.length else afterTerminator + 1

    val raw = before.substring(sentenceStart) + highlight + after.substring(0, sentenceEnd)
    // Collapse whitespace runs: the three Locator.Text fragments are trimmed independently by
    // the navigator/markup source and can leave doubled spaces at their seams.
    val sentence = raw.trim().replace(Regex("\\s+"), " ")
    return SelectionContext(word = word, sentence = sentence.ifEmpty { word })
}
