package app.berilo.reader.annotations

/**
 * Renders a book's highlights/notes to plain Markdown (S2.6), Obsidian-friendly: a `#` title,
 * `##` per chapter, a `>` blockquote per highlighted excerpt, and the note text (if any) as a
 * following paragraph. Pure and Robolectric-free — [ChapterHighlights] already carries its
 * resolved chapter title, so no Locator/Readium decoding happens here.
 */
object MarkdownExporter {

    /** Renders [chapters] under a level-1 [bookTitle] heading. Always ends in a single
     * trailing newline. */
    fun export(bookTitle: String, chapters: List<ChapterHighlights>): String {
        val out = StringBuilder()
        out.append("# ").append(bookTitle).append("\n\n")
        for (chapter in chapters) {
            out.append("## ").append(chapter.chapterTitle).append("\n\n")
            for (highlight in chapter.highlights) {
                out.append(blockquote(highlight.selectedText)).append("\n\n")
                val note = highlight.note?.trim()
                if (!note.isNullOrEmpty()) {
                    out.append(note).append("\n\n")
                }
            }
        }
        return out.toString().trimEnd('\n') + "\n"
    }

    /** Prefixes every line of [text] with Markdown's `> ` blockquote marker, so a multi-line
     * excerpt still renders as one quote in Obsidian instead of breaking out after the first
     * line. */
    private fun blockquote(text: String): String = text.lineSequence().joinToString("\n") { "> $it" }
}
