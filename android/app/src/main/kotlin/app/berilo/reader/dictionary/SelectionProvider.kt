package app.berilo.reader.dictionary

/**
 * Captures the current text selection as a [SelectionContext] (word + sentence). Behind an
 * interface so the dictionary lookup path is testable with a fake — the real implementation
 * (`ReaderActivity`) wraps Readium's `SelectableNavigator.currentSelection()`, which is
 * device/WebView territory and not exercised here.
 */
fun interface SelectionProvider {
    /**
     * @return The current [SelectionContext], or `null` if nothing is selected.
     */
    suspend fun captureSelection(): SelectionContext?
}
