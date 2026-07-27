package app.berilo.reader.annotations

/** Bucket used when a highlight's locator carried no chapter title. */
const val UNTITLED_CHAPTER = "Untitled"

/** One chapter's worth of highlights/notes, in the notebook's chapter-grouped list and the
 * Markdown export. */
data class ChapterHighlights(val chapterTitle: String, val highlights: List<Highlight>)

/** One chapter's worth of flagged translations (B9), the same shape [ChapterHighlights] has. */
data class ChapterFlags(val chapterTitle: String, val flags: List<TranslationFlag>)

/**
 * Groups [highlights] (already chronological — [AnnotationsRepository.observeForBook] orders
 * by `createdAt`) by [Highlight.chapterTitle], preserving each chapter's first-appearance
 * order rather than sorting alphabetically — the notebook and the export both read top-to-
 * bottom like the book itself.
 */
fun groupByChapter(highlights: List<Highlight>): List<ChapterHighlights> =
    groupByChapterTitle(highlights) { it.chapterTitle }
        .map { (title, items) -> ChapterHighlights(title, items) }

/** [groupByChapter] for flagged translations (B9), with the same ordering guarantees. */
fun groupFlagsByChapter(flags: List<TranslationFlag>): List<ChapterFlags> =
    groupByChapterTitle(flags) { it.chapterTitle }
        .map { (title, items) -> ChapterFlags(title, items) }

/**
 * Buckets [items] by the chapter title [titleOf] reports, first-appearance order preserved and
 * a missing or blank title folded into [UNTITLED_CHAPTER].
 *
 * Shared so the notebook's two sections cannot drift apart on how they bucket, ellipsise or
 * order chapters — the property both public wrappers actually promise.
 */
private fun <T> groupByChapterTitle(items: List<T>, titleOf: (T) -> String?): List<Pair<String, List<T>>> {
    val byChapter = LinkedHashMap<String, MutableList<T>>()
    for (item in items) {
        val title = titleOf(item)?.trim()?.takeIf { it.isNotEmpty() } ?: UNTITLED_CHAPTER
        byChapter.getOrPut(title) { mutableListOf() }.add(item)
    }
    return byChapter.map { (title, bucket) -> title to bucket.toList() }
}
