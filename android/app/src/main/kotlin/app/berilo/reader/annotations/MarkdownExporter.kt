package app.berilo.reader.annotations

/** Heading the flagged-translation section is filed under, so flags can never be mistaken for
 * highlights when the export is read months later or pasted into an issue. */
private const val FLAGS_HEADING = "Flagged translations"

/** Line that marks an entry as a flag even when the user left no comment — without it a bare
 * flag would export as an unadorned blockquote, indistinguishable from a plain highlight. */
private const val FLAG_MARKER = "**Flagged as a bad translation.**"

/**
 * Renders a book's highlights/notes to plain Markdown (S2.6), Obsidian-friendly: a `#` title,
 * `##` per chapter, a `>` blockquote per highlighted excerpt, and the note text (if any) as a
 * following paragraph. Pure and Robolectric-free — [ChapterHighlights] already carries its
 * resolved chapter title, so no Locator/Readium decoding happens here.
 *
 * B9 appends a second, clearly separated section for flagged translations, each carrying its
 * recovered [TranslationProvenance] where there is one.
 */
object MarkdownExporter {

    /**
     * Renders [chapters] and [flagChapters] under a level-1 [bookTitle] heading. Always ends in
     * a single trailing newline.
     *
     * @param bookTitle Level-1 heading.
     * @param chapters Highlights and notes, chapter-grouped in reading order.
     * @param flagChapters Flagged translations, chapter-grouped in reading order. Empty by
     *   default so the S2.6 call shape still compiles and still produces byte-identical output.
     */
    fun export(
        bookTitle: String,
        chapters: List<ChapterHighlights>,
        flagChapters: List<ChapterFlags> = emptyList(),
    ): String {
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
        appendFlags(out, flagChapters)
        return out.toString().trimEnd('\n') + "\n"
    }

    /** Appends the flagged-translation section, or nothing at all when there are no flags —
     * an empty section heading would suggest the export lost something. */
    private fun appendFlags(out: StringBuilder, flagChapters: List<ChapterFlags>) {
        if (flagChapters.isEmpty()) return
        out.append("## ").append(FLAGS_HEADING).append("\n\n")
        for (chapter in flagChapters) {
            out.append("### ").append(chapter.chapterTitle).append("\n\n")
            for (flag in chapter.flags) {
                out.append(blockquote(flag.selectedText)).append("\n\n")
                out.append(FLAG_MARKER).append("\n\n")
                val comment = flag.comment?.trim()
                if (!comment.isNullOrEmpty()) {
                    out.append(comment).append("\n\n")
                }
                flag.provenance?.let { out.append(provenanceLine(it)).append("\n\n") }
            }
        }
    }

    /** One-line, copy-pasteable identity of the run that produced a flagged passage. Rendered
     * as inline code so a sha1 does not wrap or get auto-linked. */
    private fun provenanceLine(provenance: TranslationProvenance): String =
        "Source segment `${provenance.segmentHash}` · book `${provenance.bookHash}` · " +
            "${provenance.model} → ${provenance.lang} · prompt `${provenance.promptVersion}` · " +
            "glossary `${provenance.glossaryHash}`"

    /** Prefixes every line of [text] with Markdown's `> ` blockquote marker, so a multi-line
     * excerpt still renders as one quote in Obsidian instead of breaking out after the first
     * line. */
    private fun blockquote(text: String): String = text.lineSequence().joinToString("\n") { "> $it" }
}
