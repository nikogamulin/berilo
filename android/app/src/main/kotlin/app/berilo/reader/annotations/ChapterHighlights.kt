package app.berilo.reader.annotations

/** Bucket used when a highlight's locator carried no chapter title. */
const val UNTITLED_CHAPTER = "Untitled"

/** One chapter's worth of highlights/notes, in the notebook's chapter-grouped list and the
 * Markdown export. */
data class ChapterHighlights(val chapterTitle: String, val highlights: List<Highlight>)

/**
 * Groups [highlights] (already chronological — [AnnotationsRepository.observeForBook] orders
 * by `createdAt`) by [Highlight.chapterTitle], preserving each chapter's first-appearance
 * order rather than sorting alphabetically — the notebook and the export both read top-to-
 * bottom like the book itself.
 */
fun groupByChapter(highlights: List<Highlight>): List<ChapterHighlights> {
    val byChapter = LinkedHashMap<String, MutableList<Highlight>>()
    for (highlight in highlights) {
        val title = highlight.chapterTitle?.trim()?.takeIf { it.isNotEmpty() } ?: UNTITLED_CHAPTER
        byChapter.getOrPut(title) { mutableListOf() }.add(highlight)
    }
    return byChapter.map { (title, items) -> ChapterHighlights(title, items) }
}
