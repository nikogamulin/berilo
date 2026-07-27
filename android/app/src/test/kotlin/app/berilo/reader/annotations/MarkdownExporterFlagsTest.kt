package app.berilo.reader.annotations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden test for B9's flagged-translation section of [MarkdownExporter] — byte-exact, like
 * [MarkdownExporterTest], because the output is what Obsidian actually renders and what gets
 * pasted into a bug report.
 */
class MarkdownExporterFlagsTest {

    private fun flag(
        text: String,
        comment: String? = null,
        provenance: TranslationProvenance? = null,
        chapterTitle: String? = "Poglavje ena",
    ) =
        TranslationFlag(
            id = "id-$text",
            bookId = "book-1",
            selectedText = text,
            comment = comment,
            locatorJson = "{}",
            chapterTitle = chapterTitle,
            provenance = provenance,
            createdAt = 0L,
            updatedAt = 0L,
        )

    private val provenance =
        TranslationProvenance(
            bookHash = "bookhash1",
            segmentHash = "seghash1",
            model = "gpt-5-mini",
            lang = "sl",
            promptVersion = "revise_v1",
            glossaryHash = "glosshash1",
        )

    @Test
    fun `a flag with a comment and provenance renders all three parts under its own heading`() {
        val markdown =
            MarkdownExporter.export(
                bookTitle = "Testna knjiga",
                chapters = emptyList(),
                flagChapters =
                    listOf(
                        ChapterFlags(
                            "Poglavje ena",
                            listOf(
                                flag(
                                    "Reke so starejše od meja.",
                                    comment = "Raje »gorovja« kot »hribovja« — šumniki: č, š, ž.",
                                    provenance = provenance,
                                ),
                            ),
                        ),
                    ),
            )

        val expected =
            "# Testna knjiga\n" +
                "\n" +
                "## Flagged translations\n" +
                "\n" +
                "### Poglavje ena\n" +
                "\n" +
                "> Reke so starejše od meja.\n" +
                "\n" +
                "**Flagged as a bad translation.**\n" +
                "\n" +
                "Raje »gorovja« kot »hribovja« — šumniki: č, š, ž.\n" +
                "\n" +
                "Source segment `seghash1` · book `bookhash1` · gpt-5-mini → sl · " +
                "prompt `revise_v1` · glossary `glosshash1`\n"

        assertEquals(expected, markdown)
    }

    @Test
    fun `a bare flag still carries the marker that distinguishes it from a highlight`() {
        val markdown =
            MarkdownExporter.export(
                bookTitle = "Knjiga",
                chapters = emptyList(),
                flagChapters = listOf(ChapterFlags("Poglavje ena", listOf(flag("Slab stavek.")))),
            )

        // Without the marker line this would be a bare blockquote — byte-identical to how a
        // plain highlight exports, which is exactly the confusion the section exists to avoid.
        assertEquals(
            "# Knjiga\n\n## Flagged translations\n\n### Poglavje ena\n\n" +
                "> Slab stavek.\n\n**Flagged as a bad translation.**\n",
            markdown,
        )
    }

    @Test
    fun `highlights and flags are exported as two separate sections`() {
        val highlight =
            Highlight(
                id = "h1",
                bookId = "book-1",
                color = app.berilo.reader.store.db.HighlightColor.AMBER,
                selectedText = "Lep stavek.",
                note = "Moja misel.",
                locatorJson = "{}",
                chapterTitle = "Poglavje ena",
                createdAt = 0L,
                updatedAt = 0L,
            )

        val markdown =
            MarkdownExporter.export(
                bookTitle = "Knjiga",
                chapters = listOf(ChapterHighlights("Poglavje ena", listOf(highlight))),
                flagChapters = listOf(ChapterFlags("Poglavje ena", listOf(flag("Slab stavek.")))),
            )

        assertEquals(
            "# Knjiga\n\n## Poglavje ena\n\n> Lep stavek.\n\nMoja misel.\n\n" +
                "## Flagged translations\n\n### Poglavje ena\n\n" +
                "> Slab stavek.\n\n**Flagged as a bad translation.**\n",
            markdown,
        )
        assertTrue(markdown.indexOf("> Lep stavek.") < markdown.indexOf("## Flagged translations"))
    }

    @Test
    fun `no flags renders no flag section at all`() {
        assertEquals("# Knjiga\n", MarkdownExporter.export("Knjiga", emptyList(), emptyList()))
    }

    @Test
    fun `a flag with no chapter title falls into the untitled bucket`() {
        val markdown =
            MarkdownExporter.export(
                bookTitle = "Knjiga",
                chapters = emptyList(),
                flagChapters = groupFlagsByChapter(listOf(flag("Slab stavek.", chapterTitle = null))),
            )

        assertTrue(markdown.contains("### $UNTITLED_CHAPTER\n"))
    }
}
