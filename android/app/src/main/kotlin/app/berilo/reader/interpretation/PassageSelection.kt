package app.berilo.reader.interpretation

/**
 * Resolves the passage text to interpret from the reader's current state
 * (`docs/project_plan.md` S2.5): the whole active text selection if the user selected one
 * (unlike the dictionary's single-word capture, the entire selection is sent — no sentence
 * reconstruction), else the currently visible locator's highlighted text as a fallback, else
 * `null` (the caller shows a toast asking the user to select something).
 *
 * This is the pure, JVM-testable half of passage capture — the device-only half is just
 * handing this function the two strings Readium's `SelectableNavigator.currentSelection()`
 * and `Navigator.currentLocator` already expose as `Locator.Text.highlight`.
 *
 * @param selectionHighlight The navigator's current text selection highlight, or null/blank
 *   if nothing is selected.
 * @param visibleLocatorHighlight The currently visible locator's `text.highlight`, or null.
 * @return The resolved passage, trimmed and non-blank, or `null` if neither source has text.
 */
fun resolveInterpretationPassage(selectionHighlight: String?, visibleLocatorHighlight: String?): String? {
    val selection = selectionHighlight?.trim()
    if (!selection.isNullOrEmpty()) return selection

    val visible = visibleLocatorHighlight?.trim()
    if (!visible.isNullOrEmpty()) return visible

    return null
}
